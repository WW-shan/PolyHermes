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
    println("from=${options.from} to=${options.to} minVolume=${options.minVolume} maxMarkets=${options.maxMarkets}")
    if (options.proxy != null) println("proxy=${options.proxy}")

    val markets = runBlocking {
        EventMakerResearchDataClient(
            proxyUrl = options.proxy,
            concurrency = options.concurrency,
            progress = { println(it) }
        ).fetchMarkets(
            fromDate = options.from,
            toDate = options.to,
            minVolume = options.minVolume,
            maxMarkets = options.maxMarkets,
            entryHorizonSeconds = 86_400L
        )
    }
    require(markets.isNotEmpty()) { "No eligible markets returned" }

    val engine = EventMakerBacktestEngine()
    val configs = buildGrid()
    println("configs=${configs.size}; simulating ${markets.size} markets")
    val summaries = configs.map { engine.run(markets, it).first }
    writeSummaryCsv(options.output.resolveSibling(options.output.fileName.toString() + "-summary.csv"), summaries)

    val balancedConfig = summaries.firstOrNull {
        it.config.minPrice == 0.97 && it.config.maxPrice == 0.99 && it.config.entryOffsetCents == 0.5 &&
            it.config.exitSpreadCents == 1.0 && it.config.queueFactor == 2.0 && it.config.maxCycles == 3
    }?.config ?: summaries.maxByOrNull { it.totalRoi }!!.config

    val balancedResult = engine.run(markets, balancedConfig)
    val baseName = options.output.fileName.toString()
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

private fun buildGrid(): List<EventMakerConfig> {
    val bands = listOf(
        0.95 to 0.97,
        0.97 to 0.99,
        0.985 to 0.995
    )
    val offsets = listOf(0.0, 0.25, 0.5, 1.0)
    val spreads = listOf(0.5, 1.0, 2.0)
    val queues = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0)
    val cycles = listOf(1, 3)
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
                                minPrice = minPrice,
                                maxPrice = maxPrice,
                                entryOffsetCents = offset,
                                exitSpreadCents = spread,
                                queueFactor = queue,
                                requoteSeconds = requote,
                                orderNotional = 100.0,
                                maxCycles = maxCycles,
                                makerFeeRate = 0.0
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
        "config", "min_price", "max_price", "entry_offset_cents", "exit_spread_cents", "queue_factor", "requote_seconds", "max_cycles",
        "total_markets", "eligible_markets", "filled_markets", "fill_rate", "total_cycles", "sold_cycles", "settled_cycles",
        "win_rate", "deployed_notional", "total_pnl", "total_roi", "avg_pnl_per_filled_market", "worst_market_pnl",
        "max_drawdown", "avg_holding_seconds", "hold_baseline_roi", "profitable_filled_markets", "total_fees", "train_pnl", "train_roi", "train_filled_markets", "train_cycles", "test_pnl", "test_roi", "test_filled_markets", "test_cycles", "max_adverse_excursion", "average_time_to_fill_seconds", "settled_losses", "break_even_loss_rate"
    ).joinToString(",")
    val lines = summaries.sortedByDescending { it.totalRoi }.map { s ->
        listOf(
            s.config.name, s.config.minPrice, s.config.maxPrice, s.config.entryOffsetCents, s.config.exitSpreadCents,
            s.config.queueFactor, s.config.requoteSeconds, s.config.maxCycles, s.totalMarkets, s.eligibleMarkets, s.filledMarkets, s.fillRate,
            s.totalCycles, s.soldCycles, s.settledCycles, s.winRate, s.deployedNotional, s.totalPnl, s.totalRoi,
            s.averagePnlPerFilledMarket, s.worstMarketPnl, s.maxDrawdown, s.averageHoldingSeconds,
            s.holdBaselineRoi, s.profitableFilledMarkets, s.totalFees, s.trainPnl, s.trainRoi, s.trainFilledMarkets, s.trainCycles, s.testPnl, s.testRoi, s.testFilledMarkets, s.testCycles, s.maxAdverseExcursion, s.averageTimeToFillSeconds, s.settledLosses, s.breakEvenLossRate
        ).joinToString(",") { csvCell(it) }
    }
    Files.write(path, listOf(header) + lines, StandardCharsets.UTF_8)
    println("wrote ${path.toAbsolutePath()}")
}

private fun writeFeeSensitivityCsv(path: Path, summaries: List<EventMakerSummary>) {
    Files.createDirectories(path.parent)
    val header = "maker_fee_bps,filled_markets,total_cycles,sold_cycles,settled_cycles,settled_losses,total_fees,total_pnl,total_roi,train_roi,test_roi,worst_market_pnl,break_even_loss_rate"
    val lines = summaries.map { s ->
        listOf(
            (s.config.makerFeeRate * 10_000).toInt(), s.filledMarkets, s.totalCycles, s.soldCycles,
            s.settledCycles, s.settledLosses, s.totalFees, s.totalPnl, s.totalRoi, s.trainRoi,
            s.testRoi, s.worstMarketPnl, s.breakEvenLossRate
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
            "%s %-46s eligible=%d filled=%d cycles=%d sold=%d settled=%d win=%.1f%% pnl=%.2f roi=%.2f%% dd=%.2f holdRoi=%.2f%% mae=%.2f fillH=%.2f tailLoss=%d breakEven=%.2f%% train=(%d,%d,%.2f%%) test=(%d,%d,%.2f%%)",
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

private data class CliOptions(
    val proxy: String?,
    val maxMarkets: Int,
    val minVolume: Double,
    val from: LocalDate,
    val to: LocalDate,
    val concurrency: Int,
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
                output = Paths.get(option("output") ?: "build/research/event-maker-backtest")
            )
        }
    }
}
