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

    val robustCandidates = summaries.filter { it.trainFilledMarkets >= 20 && it.testFilledMarkets >= 20 }
    val robustConfig = robustCandidates.maxByOrNull { minOf(it.trainRoi, it.testRoi) }?.config
    if (robustConfig != null) {
        val robustResult = engine.run(markets, robustConfig)
        writeMarketCsv(options.output.resolveSibling("$baseName-robust-markets.csv"), robustResult.second)
        writeCycleCsv(options.output.resolveSibling("$baseName-robust-cycles.csv"), robustResult.second)
        printSummary("robust-selected", robustResult.first)
        println()
    } else {
        println("No robust config has at least 20 filled markets in both train and test windows.")
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
    println("Top 15 by minimum(train ROI, test ROI), requiring >= 20 filled markets in each window:")
    robustCandidates
        .sortedByDescending { minOf(it.trainRoi, it.testRoi) }
        .take(15)
        .forEach { printSummary("robust", it) }
}

private fun buildGrid(): List<EventMakerConfig> {
    val bands = listOf(
        0.95 to 0.97,
        0.97 to 0.99,
        0.985 to 0.995
    )
    val offsets = listOf(0.25, 0.5, 1.0)
    val spreads = listOf(0.5, 1.0, 2.0)
    val queues = listOf(1.0, 2.0, 5.0)
    val cycles = listOf(1, 3)
    val result = mutableListOf<EventMakerConfig>()
    for ((minPrice, maxPrice) in bands) {
        for (offset in offsets) {
            for (spread in spreads) {
                for (queue in queues) {
                    for (maxCycles in cycles) {
                        val name = "p${minPrice}-${maxPrice}_off${offset}_spread${spread}_q${queue}_c$maxCycles"
                        result += EventMakerConfig(
                            name = name,
                            minPrice = minPrice,
                            maxPrice = maxPrice,
                            entryOffsetCents = offset,
                            exitSpreadCents = spread,
                            queueFactor = queue,
                            requoteSeconds = 300,
                            orderNotional = 100.0,
                            maxCycles = maxCycles,
                            makerFeeRate = 0.0
                        )
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
        "config", "min_price", "max_price", "entry_offset_cents", "exit_spread_cents", "queue_factor", "max_cycles",
        "total_markets", "eligible_markets", "filled_markets", "fill_rate", "total_cycles", "sold_cycles", "settled_cycles",
        "win_rate", "deployed_notional", "total_pnl", "total_roi", "avg_pnl_per_filled_market", "worst_market_pnl",
        "max_drawdown", "avg_holding_seconds", "hold_baseline_roi", "profitable_filled_markets", "total_fees", "train_pnl", "train_roi", "train_filled_markets", "train_cycles", "test_pnl", "test_roi", "test_filled_markets", "test_cycles"
    ).joinToString(",")
    val lines = summaries.sortedByDescending { it.totalRoi }.map { s ->
        listOf(
            s.config.name, s.config.minPrice, s.config.maxPrice, s.config.entryOffsetCents, s.config.exitSpreadCents,
            s.config.queueFactor, s.config.maxCycles, s.totalMarkets, s.eligibleMarkets, s.filledMarkets, s.fillRate,
            s.totalCycles, s.soldCycles, s.settledCycles, s.winRate, s.deployedNotional, s.totalPnl, s.totalRoi,
            s.averagePnlPerFilledMarket, s.worstMarketPnl, s.maxDrawdown, s.averageHoldingSeconds,
            s.holdBaselineRoi, s.profitableFilledMarkets, s.totalFees, s.trainPnl, s.trainRoi, s.trainFilledMarkets, s.trainCycles, s.testPnl, s.testRoi, s.testFilledMarkets, s.testCycles
        ).joinToString(",") { csvCell(it) }
    }
    Files.write(path, listOf(header) + lines, StandardCharsets.UTF_8)
    println("wrote ${path.toAbsolutePath()}")
}

private fun writeMarketCsv(path: Path, results: List<EventMakerMarketResult>) {
    Files.createDirectories(path.parent)
    val header = "condition_id,question,tag,volume,closed_timestamp,reference_price,favorite_outcome,favorite_won,eligible,filled,cycles,deployed_notional,pnl,roi,hold_baseline_roi"
    val lines = results.map { r ->
        listOf(
            r.conditionId, r.question, r.tag, r.volume, r.closedTimestamp, r.referencePrice ?: "", r.favoriteOutcomeIndex ?: "",
            r.favoriteWon ?: "", r.eligible, r.filled, r.cycles.size, r.deployedNotional, r.pnl, r.roi, r.holdBaselineRoi
        ).joinToString(",") { csvCell(it) }
    }
    Files.write(path, listOf(header) + lines, StandardCharsets.UTF_8)
    println("wrote ${path.toAbsolutePath()}")
}

private fun writeCycleCsv(path: Path, results: List<EventMakerMarketResult>) {
    Files.createDirectories(path.parent)
    val header = "condition_id,question,tag,closed_timestamp,reference_price,favorite_outcome,favorite_won,entry_timestamp,entry_price,shares,exit_timestamp,exit_price,exit_type,holding_seconds,pnl,fee"
    val lines = results.flatMap { r ->
        r.cycles.map { c ->
            listOf(
                r.conditionId, r.question, r.tag, r.closedTimestamp, r.referencePrice ?: "",
                r.favoriteOutcomeIndex ?: "", r.favoriteWon ?: "", c.entryTimestamp, c.entryPrice, c.shares,
                c.exitTimestamp ?: "", c.exitPrice ?: "", c.exitType, c.holdingSeconds, c.pnl, c.fee
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
            "%s %-46s eligible=%d filled=%d cycles=%d sold=%d settled=%d win=%.1f%% pnl=%.2f roi=%.2f%% dd=%.2f holdRoi=%.2f%% train=(%d,%d,%.2f%%) test=(%d,%d,%.2f%%)",
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