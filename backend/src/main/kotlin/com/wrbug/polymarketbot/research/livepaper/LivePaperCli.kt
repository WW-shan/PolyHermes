package com.wrbug.polymarketbot.research.livepaper

import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = parseOptions(args)
    println("Live event-maker paper test")
    println("proxy=${options.proxyUrl ?: "(direct)"} output=${options.outputDir.toAbsolutePath()}")
    val runner = LivePaperRunner(options)
    runner.run()
    exitProcess(0)
}

private fun parseOptions(args: Array<String>): LivePaperOptions {
    fun option(name: String): String? {
        val index = args.indexOf("--$name")
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val defaultOutput = Paths.get("build", "research", "live-paper-$stamp")
    val minMinutes = option("min-minutes")?.toIntOrNull() ?: 120
    val minRemainingMinutes = option("min-remaining-minutes")?.toIntOrNull() ?: 120
    val maxMinutes = option("minutes")?.toIntOrNull() ?: 720
    if (minMinutes <= 0 || minRemainingMinutes <= 0 || maxMinutes <= 0 || minMinutes > maxMinutes) {
        error("minutes/min-minutes/min-remaining-minutes invalid")
    }
    return LivePaperOptions(
        proxyUrl = option("proxy"),
        outputDir = Paths.get(option("output") ?: defaultOutput.toString()),
        maxMarkets = option("max-markets")?.toIntOrNull() ?: 8,
        minVolume24hr = option("min-volume")?.toDoubleOrNull() ?: 25_000.0,
        minMinutes = minMinutes,
        minRemainingMinutes = minRemainingMinutes,
        maxMinutes = maxMinutes,
        targetCycles = option("target-cycles")?.toIntOrNull() ?: 12,
        allowNegRisk = args.contains("--allow-neg-risk"),
        tradePollSeconds = option("poll-seconds")?.toLongOrNull() ?: 3L,
        snapshotSeconds = option("snapshot-seconds")?.toLongOrNull() ?: 10L
    )
}