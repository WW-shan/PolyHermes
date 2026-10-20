package com.wrbug.polymarketbot.research.eventmaker

import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.util.Locale

/**
 * Reproducible command-line research run.
 *
 * Example:
 *   ./gradlew eventMakerBacktest -PresearchProxy=http://127.0.0.1:10808 \
 *       -PresearchMaxMarkets=300 -PresearchMinVolume=250000
 */
fun main(args: Array<String>) {
    val options = CliOptions.parse(args)
    println("Event-maker research backtest")
    println(
        "from=${options.from} to=${options.to} minVolume=${options.minVolume} " +
            "maxMarkets=${options.maxMarkets} entryHorizonSeconds=${options.entryHorizonSeconds} " +
            "gridProfile=${options.gridProfile.apiName} maxOrderAgeSeconds=${options.maxOrderAgeSeconds} " +
            "activityGate=${options.activityGate} flowGuard=${options.flowGuard}"
    )
    if (options.proxy != null) println("proxy=${options.proxy}")

    val datasetPath = options.dataset
    val cachedDataset = datasetPath != null && !options.refreshDataset && Files.isRegularFile(datasetPath)
    val markets = if (cachedDataset) {
        val snapshot = EventMakerDatasetStore.read(datasetPath!!)
        require(
            EventMakerDatasetStore.matches(
                snapshot,
                options.from,
                options.to,
                options.minVolume,
                options.maxMarkets,
                options.entryHorizonSeconds
            )
        ) {
            "dataset metadata does not match requested fetch parameters: $datasetPath"
        }
        val loadedMarkets = snapshot.markets ?: error("dataset markets are missing")
        println("dataset loaded markets=${loadedMarkets.size} path=$datasetPath")
        loadedMarkets
    } else {
        val fetched = runBlocking {
            EventMakerResearchDataClient(
                proxyUrl = options.proxy,
                concurrency = options.concurrency,
                progress = { println(it) }
            ).fetchMarkets(
                fromDate = options.from,
                toDate = options.to,
                minVolume = options.minVolume,
                maxMarkets = options.maxMarkets,
                entryHorizonSeconds = options.entryHorizonSeconds
            )
        }
        if (datasetPath != null && fetched.isNotEmpty()) {
            EventMakerDatasetStore.write(
                datasetPath,
                EventMakerDatasetSnapshot(
                    createdAt = System.currentTimeMillis(),
                    fromDate = options.from.toString(),
                    toDate = options.to.toString(),
                    minVolume = options.minVolume,
                    maxMarkets = options.maxMarkets,
                    entryHorizonSeconds = options.entryHorizonSeconds,
                    markets = fetched
                )
            )
            println("dataset wrote markets=${fetched.size} path=$datasetPath")
        }
        fetched
    }
    require(markets.isNotEmpty()) { "No eligible markets returned" }

    val engine = EventMakerBacktestEngine()
    val configs = buildGrid(options.entryHorizonSeconds, options.activityGate, options.flowGuard, options.gridProfile, options.maxOrderAgeSeconds, options.minRemainingSeconds)
    val activityGate = options.activityGate
    println(
        "activityGate 30m=${activityGate.minRecentTrades30m}/${activityGate.minRecentNotional30m}/" +
            "${activityGate.minRecentTradesPerSide30m} 2h=${activityGate.minRecentTrades2h}/" +
            "${activityGate.minRecentNotional2h}/${activityGate.minRecentTradesPerSide2h} " +
            "(zero values mean the live Shadow/Live activity gate is NOT modelled)"
    )
    println("configs=${configs.size}; simulating ${markets.size} markets")
    val summaries = configs.map { engine.run(markets, it).first }
    writeSummaryCsv(options.output.resolveSibling(options.output.fileName.toString() + "-summary.csv"), summaries)
    val baseName = options.output.fileName.toString()

    if (options.focusConfig != null) {
        val focused = summaries.firstOrNull { it.config.name == options.focusConfig }
            ?: error("focus config not found: ${options.focusConfig}")
        val focusedResult = engine.run(markets, focused.config)
        writeMarketCsv(options.output.resolveSibling("$baseName-focus-markets.csv"), focusedResult.second)
        writeCycleCsv(options.output.resolveSibling("$baseName-focus-cycles.csv"), focusedResult.second)
        printSummary("focus", focusedResult.first)
        val focusFees = listOf(0.0, 0.001, 0.0025, 0.005, 0.01).map { feeRate ->
            engine.run(
                markets,
                focused.config.copy(
                    name = "${focused.config.name}_fee${(feeRate * 10_000).toInt()}bps",
                    makerFeeRate = feeRate
                )
            ).first
        }
        writeFeeSensitivityCsv(options.output.resolveSibling("$baseName-focus-fee-sensitivity.csv"), focusFees)
        println()
    }

    val balancedConfig = summaries.firstOrNull {
        it.config.minPrice == 0.97 && it.config.maxPrice == 0.99 && it.config.entryOffsetCents == 0.5 &&
            it.config.exitSpreadCents == 1.0 && it.config.queueFactor == 2.0 && it.config.maxCycles == 3
    }?.config ?: summaries.maxByOrNull { it.totalRoi }!!.config

    val balancedResult = engine.run(markets, balancedConfig)
    writeMarketCsv(options.output.resolveSibling("$baseName-balanced-markets.csv"), balancedResult.second)
    printSummary("balanced", balancedResult.first)
    println()

    // Parameter selection must not consult the test window.
    val robustCandidates = summaries.filter { it.trainFilledMarkets >= 20 && it.trainRoi > 0.0 }
    val robustConfig = robustCandidates.maxByOrNull { it.trainRoi }?.config
    if (robustConfig != null) {
        val robustResult = engine.run(markets, robustConfig)
        writeMarketCsv(options.output.resolveSibling("$baseName-robust-markets.csv"), robustResult.second)
        writeCycleCsv(options.output.resolveSibling("$baseName-robust-cycles.csv"), robustResult.second)
        printSummary("train-selected-oos", robustResult.first)
        println()

        val feeScenarios = listOf(0.0, 0.001, 0.0025, 0.005, 0.01).map { feeRate ->
            engine.run(
                markets,
                robustConfig.copy(
                    name = "${robustConfig.name}_fee${(feeRate * 10_000).toInt()}bps",
                    makerFeeRate = feeRate
                )
            ).first
        }
        writeFeeSensitivityCsv(
            options.output.resolveSibling("$baseName-fee-sensitivity.csv"),
            feeScenarios
        )
        println("Fee sensitivity for ${robustConfig.name} (maker fee per side):")
        feeScenarios.forEach { printSummary("fee", it) }
        println()
    } else {
        println("No config has >=20 training fills and positive training ROI.")
    }

    val topConfig = summaries.maxByOrNull { it.totalRoi }?.config
    if (topConfig != null) {
        val topResult = engine.run(markets, topConfig)
        writeMarketCsv(options.output.resolveSibling("$baseName-top-markets.csv"), topResult.second)
        writeCycleCsv(options.output.resolveSibling("$baseName-top-cycles.csv"), topResult.second)
    }

    println("Top 15 by total ROI (research only; do not treat as live proof):")
    summaries.sortedByDescending { it.totalRoi }.take(15).forEach { printSummary("top", it) }
    println()
    println("Top 15 by training ROI, requiring >=20 training fills; test ROI is reported only for evaluation:")
    robustCandidates
        .sortedByDescending { it.trainRoi }
        .take(15)
        .forEach { printSummary("robust", it) }
}

private fun buildGrid(
    entryHorizonSeconds: Long,
    activityGate: ActivityGate,
    flowGuard: FlowGuardSettings,
    gridProfile: GridProfile,
    maxOrderAgeSeconds: Long,
    minRemainingSeconds: Long
): List<EventMakerConfig> {
    val bands = when (gridProfile) {
        GridProfile.DEFAULT -> listOf(
            0.95 to 0.97,
            0.97 to 0.99,
            0.985 to 0.995
        )
        GridProfile.WIDE_EXIT -> listOf(
            0.95 to 0.97,
            0.97 to 0.99,
            0.985 to 0.995
        )
        GridProfile.CANDIDATE_NEIGHBORHOOD -> listOf(
            0.95 to 0.97,
            0.97 to 0.99,
            0.985 to 0.995
        )
    }
    val offsets = when (gridProfile) {
        GridProfile.DEFAULT -> listOf(0.0, 0.25, 0.5, 1.0)
        GridProfile.WIDE_EXIT -> listOf(0.25, 0.5, 1.0)
        GridProfile.CANDIDATE_NEIGHBORHOOD -> listOf(0.0, 0.25, 0.5, 1.0)
    }
    val spreads = when (gridProfile) {
        GridProfile.DEFAULT -> listOf(0.5, 1.0, 2.0)
        GridProfile.WIDE_EXIT -> listOf(2.0, 3.0, 5.0, 8.0, 10.0)
        GridProfile.CANDIDATE_NEIGHBORHOOD -> listOf(0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
    }
    val queues = when (gridProfile) {
        GridProfile.CANDIDATE_NEIGHBORHOOD -> listOf(10.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0)
        else -> listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0)
    }
    val cycles = if (gridProfile == GridProfile.CANDIDATE_NEIGHBORHOOD) listOf(1) else listOf(1, 3)
    val requotes = listOf(0L, 300L)
    val result = mutableListOf<EventMakerConfig>()
    for ((minPrice, maxPrice) in bands) {
        for (offset in offsets) {
            for (spread in spreads) {
                for (queue in queues) {
                    for (maxCycles in cycles) {
                        for (requote in requotes) {
                            val name = "p${minPrice}-${maxPrice}_off${offset}_spread${spread}_q${queue}_c$maxCycles" +
                                "_r$requote"
                            result += EventMakerConfig(
                                name = name,
                                entryHorizonSeconds = entryHorizonSeconds,
                                minPrice = minPrice,
                                maxPrice = maxPrice,
                                entryOffsetCents = offset,
                                exitSpreadCents = spread,
                                queueFactor = queue,
                                requoteSeconds = requote,
                                maxOrderAgeSeconds = maxOrderAgeSeconds,
                                minRemainingSeconds = minRemainingSeconds,
                                orderNotional = 100.0,
                                maxCycles = maxCycles,
                                makerFeeRate = 0.0,
                                minRecentTrades30m = activityGate.minRecentTrades30m,
                                minRecentNotional30m = activityGate.minRecentNotional30m,
                                minRecentTradesPerSide30m = activityGate.minRecentTradesPerSide30m,
                                minRecentTrades2h = activityGate.minRecentTrades2h,
                                minRecentNotional2h = activityGate.minRecentNotional2h,
                                minRecentTradesPerSide2h = activityGate.minRecentTradesPerSide2h,
                                flowGuardWindowSeconds = flowGuard.windowSeconds,
                                flowGuardMaxSignedFlow = flowGuard.maxSignedFlow,
                                flowGuardMinPriceDropCents = flowGuard.minPriceDropCents
                            )
                        }
                    }
                }
            }
        }
    }
    return result
}

private fun writeSummaryCsv(path: Path, summaries: List<EventMakerSummary>) {
    Files.createDirectories(path.parent)
    val header = listOf(
        "config", "min_price", "max_price", "entry_offset_cents", "exit_spread_cents", "queue_factor", "requote_seconds", "max_order_age_seconds", "max_cycles",
        "flow_guard_window_seconds", "flow_guard_max_signed_flow", "flow_guard_min_price_drop_cents", "min_remaining_seconds",
        "min_recent_trades_30m", "min_recent_notional_30m", "min_recent_trades_per_side_30m",
        "min_recent_trades_2h", "min_recent_notional_2h", "min_recent_trades_per_side_2h",
        "total_markets", "eligible_markets", "filled_markets", "fill_rate", "total_cycles", "sold_cycles", "settled_cycles",
        "win_rate", "deployed_notional", "total_pnl", "total_roi", "avg_pnl_per_filled_market", "worst_market_pnl",
        "max_drawdown", "avg_holding_seconds", "hold_baseline_roi", "profitable_filled_markets", "total_fees", "train_pnl", "train_roi", "train_filled_markets", "train_cycles", "test_pnl", "test_roi", "test_filled_markets", "test_cycles", "max_adverse_excursion", "average_time_to_fill_seconds", "settled_losses", "break_even_loss_rate", "flow_guard_triggers"
    ).joinToString(",")
    val lines = summaries.sortedByDescending { it.totalRoi }.map { s ->
        listOf(
            s.config.name, s.config.minPrice, s.config.maxPrice, s.config.entryOffsetCents, s.config.exitSpreadCents,
            s.config.queueFactor, s.config.requoteSeconds, s.config.maxOrderAgeSeconds, s.config.maxCycles,
            s.config.flowGuardWindowSeconds, s.config.flowGuardMaxSignedFlow, s.config.flowGuardMinPriceDropCents, s.config.minRemainingSeconds,
            s.config.minRecentTrades30m, s.config.minRecentNotional30m, s.config.minRecentTradesPerSide30m,
            s.config.minRecentTrades2h, s.config.minRecentNotional2h, s.config.minRecentTradesPerSide2h,
            s.totalMarkets, s.eligibleMarkets, s.filledMarkets, s.fillRate,
            s.totalCycles, s.soldCycles, s.settledCycles, s.winRate, s.deployedNotional, s.totalPnl, s.totalRoi,
            s.averagePnlPerFilledMarket, s.worstMarketPnl, s.maxDrawdown, s.averageHoldingSeconds,
            s.holdBaselineRoi, s.profitableFilledMarkets, s.totalFees, s.trainPnl, s.trainRoi, s.trainFilledMarkets, s.trainCycles, s.testPnl, s.testRoi, s.testFilledMarkets, s.testCycles, s.maxAdverseExcursion, s.averageTimeToFillSeconds, s.settledLosses, s.breakEvenLossRate, s.flowGuardTriggers
        ).joinToString(",") { csvCell(it) }
    }
    Files.write(path, listOf(header) + lines, StandardCharsets.UTF_8)
    println("wrote ${path.toAbsolutePath()}")
}

private fun writeFeeSensitivityCsv(path: Path, summaries: List<EventMakerSummary>) {
    Files.createDirectories(path.parent)
    val header = "maker_fee_bps,filled_markets,total_cycles,sold_cycles,settled_cycles,settled_losses,total_fees,total_pnl,total_roi,train_roi,test_roi,worst_market_pnl,break_even_loss_rate,flow_guard_triggers"
    val lines = summaries.map { s ->
        listOf(
            (s.config.makerFeeRate * 10_000).toInt(), s.filledMarkets, s.totalCycles, s.soldCycles,
            s.settledCycles, s.settledLosses, s.totalFees, s.totalPnl, s.totalRoi, s.trainRoi,
            s.testRoi, s.worstMarketPnl, s.breakEvenLossRate, s.flowGuardTriggers
        ).joinToString(",") { csvCell(it) }
    }
    Files.write(path, listOf(header) + lines, StandardCharsets.UTF_8)
    println("wrote ${path.toAbsolutePath()}")
}

private fun writeMarketCsv(path: Path, results: List<EventMakerMarketResult>) {
    Files.createDirectories(path.parent)
    val header = "condition_id,question,tag,volume,closed_timestamp,reference_price,favorite_outcome,favorite_won,eligible,filled,cycles,deployed_notional,pnl,roi,hold_baseline_roi,max_adverse_excursion"
    val lines = results.map { r ->
        listOf(
            r.conditionId, r.question, r.tag, r.volume, r.closedTimestamp, r.referencePrice ?: "", r.favoriteOutcomeIndex ?: "",
            r.favoriteWon ?: "", r.eligible, r.filled, r.cycles.size, r.deployedNotional, r.pnl, r.roi, r.holdBaselineRoi, r.maxAdverseExcursion
        ).joinToString(",") { csvCell(it) }
    }
    Files.write(path, listOf(header) + lines, StandardCharsets.UTF_8)
    println("wrote ${path.toAbsolutePath()}")
}

private fun writeCycleCsv(path: Path, results: List<EventMakerMarketResult>) {
    Files.createDirectories(path.parent)
    val header = "condition_id,question,tag,closed_timestamp,reference_price,favorite_outcome,favorite_won,entry_timestamp,entry_price,shares,exit_timestamp,exit_price,exit_type,holding_seconds,pnl,fee,time_to_fill_seconds,max_adverse_excursion,markout_60s_pnl"
    val lines = results.flatMap { r ->
        r.cycles.map { c ->
            listOf(
                r.conditionId, r.question, r.tag, r.closedTimestamp, r.referencePrice ?: "",
                r.favoriteOutcomeIndex ?: "", r.favoriteWon ?: "", c.entryTimestamp, c.entryPrice, c.shares,
                c.exitTimestamp ?: "", c.exitPrice ?: "", c.exitType, c.holdingSeconds, c.pnl, c.fee, c.timeToFillSeconds, c.maxAdverseExcursion, c.markout60sPnl ?: ""
            ).joinToString(",") { csvCell(it) }
        }
    }
    Files.write(path, listOf(header) + lines, StandardCharsets.UTF_8)
    println("wrote ${path.toAbsolutePath()}")
}

private fun printSummary(prefix: String, s: EventMakerSummary) {
    println(
        String.format(
            Locale.US,
            "%s %-46s eligible=%d filled=%d cycles=%d sold=%d settled=%d win=%.1f%% pnl=%.2f roi=%.2f%% dd=%.2f holdRoi=%.2f%% mae=%.2f fillH=%.2f tailLoss=%d guard=%d breakEven=%.2f%% train=(%d,%d,%.2f%%) test=(%d,%d,%.2f%%)",
            prefix,
            s.config.name,
            s.eligibleMarkets,
            s.filledMarkets,
            s.totalCycles,
            s.soldCycles,
            s.settledCycles,
            s.winRate * 100.0,
            s.totalPnl,
            s.totalRoi * 100.0,
            s.maxDrawdown,
            s.holdBaselineRoi * 100.0,
            s.maxAdverseExcursion,
            s.averageTimeToFillSeconds / 3600.0,
            s.settledLosses,
            s.flowGuardTriggers,
            s.breakEvenLossRate * 100.0,
            s.trainFilledMarkets,
            s.trainCycles,
            s.trainRoi * 100.0,
            s.testFilledMarkets,
            s.testCycles,
            s.testRoi * 100.0
        )
    )
}

private fun csvCell(value: Any?): String {
    val text = value?.toString() ?: ""
    return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + text.replace("\"", "\"\"") + "\""
    } else text
}

private enum class GridProfile(val apiName: String) {
    DEFAULT("default"),
    WIDE_EXIT("wide-exit"),
    CANDIDATE_NEIGHBORHOOD("candidate-neighborhood");

    companion object {
        fun parse(value: String?): GridProfile {
            val normalized = value?.trim()?.lowercase() ?: return DEFAULT
            return entries.firstOrNull { it.apiName == normalized }
                ?: throw IllegalArgumentException("unknown grid profile: $value")
        }
    }
}

private data class FlowGuardSettings(
    val windowSeconds: Long,
    val maxSignedFlow: Double,
    val minPriceDropCents: Double
) {
    override fun toString(): String = "window=${windowSeconds}s,maxFlow=${maxSignedFlow},minDrop=${minPriceDropCents}c"
}

private data class ActivityGate(
    val minRecentTrades30m: Int,
    val minRecentNotional30m: Double,
    val minRecentTradesPerSide30m: Int,
    val minRecentTrades2h: Int,
    val minRecentNotional2h: Double,
    val minRecentTradesPerSide2h: Int
)

private data class CliOptions(
    val proxy: String?,
    val maxMarkets: Int,
    val minVolume: Double,
    val from: LocalDate,
    val to: LocalDate,
    val concurrency: Int,
    val entryHorizonSeconds: Long,
    val activityGate: ActivityGate,
    val flowGuard: FlowGuardSettings,
    val gridProfile: GridProfile,
    val maxOrderAgeSeconds: Long,
    val minRemainingSeconds: Long,
    val dataset: Path?,
    val refreshDataset: Boolean,
    val focusConfig: String?,
    val output: Path
) {
    companion object {
        fun parse(args: Array<String>): CliOptions {
            fun option(name: String): String? {
                val index = args.indexOf("--$name")
                return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
            }
            return CliOptions(
                proxy = option("proxy"),
                maxMarkets = option("max-markets")?.toIntOrNull() ?: 300,
                minVolume = option("min-volume")?.toDoubleOrNull() ?: 250_000.0,
                from = LocalDate.parse(option("from") ?: "2024-01-01"),
                to = LocalDate.parse(option("to") ?: "2026-10-01"),
                concurrency = option("concurrency")?.toIntOrNull() ?: 6,
                entryHorizonSeconds = option("entry-horizon-seconds")?.toLongOrNull() ?: 86_400L,
                gridProfile = GridProfile.parse(option("grid-profile")),
                maxOrderAgeSeconds = option("max-order-age-seconds")?.toLongOrNull() ?: 1_800L,
        minRemainingSeconds = option("min-remaining-seconds")?.toLongOrNull() ?: 0L,
                dataset = option("dataset")?.let(Paths::get),
                refreshDataset = args.contains("--refresh-dataset"),
                focusConfig = option("focus-config"),
                flowGuard = FlowGuardSettings(
                    windowSeconds = option("flow-guard-window-seconds")?.toLongOrNull() ?: 0L,
                    maxSignedFlow = option("flow-guard-max-signed-flow")?.toDoubleOrNull() ?: -0.4,
                    minPriceDropCents = option("flow-guard-min-price-drop-cents")?.toDoubleOrNull() ?: 0.5
                ),
                activityGate = ActivityGate(
                    minRecentTrades30m = option("min-recent-trades")?.toIntOrNull() ?: 0,
                    minRecentNotional30m = option("min-recent-notional")?.toDoubleOrNull() ?: 0.0,
                    minRecentTradesPerSide30m = option("min-recent-trades-per-side")?.toIntOrNull() ?: 0,
                    minRecentTrades2h = option("min-recent-trades-2h")?.toIntOrNull() ?: 0,
                    minRecentNotional2h = option("min-recent-notional-2h")?.toDoubleOrNull() ?: 0.0,
                    minRecentTradesPerSide2h = option("min-recent-trades-per-side-2h")?.toIntOrNull() ?: 0
                ),
                output = Paths.get(option("output") ?: "build/research/event-maker-backtest")
            )
        }
    }
}
