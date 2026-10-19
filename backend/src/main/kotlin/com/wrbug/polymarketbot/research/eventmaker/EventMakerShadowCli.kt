package com.wrbug.polymarketbot.research.eventmaker

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.wrbug.polymarketbot.research.livepaper.LiveFeedEvent
import com.wrbug.polymarketbot.research.livepaper.LivePaperDataClient
import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.LivePaperWsClient
import com.wrbug.polymarketbot.research.livepaper.LiveTradePrint
import com.wrbug.polymarketbot.research.livepaper.MarketBook
import com.wrbug.polymarketbot.research.livepaper.MarketDiscoveryResult
import com.wrbug.polymarketbot.research.livepaper.MarketDiscoveryStats
import com.wrbug.polymarketbot.service.eventmaker.EventMakerConfig
import com.wrbug.polymarketbot.service.eventmaker.EventMakerEngineResult
import com.wrbug.polymarketbot.service.eventmaker.EventMakerLiveEngine
import com.wrbug.polymarketbot.service.eventmaker.EventMakerLiveState
import com.wrbug.polymarketbot.service.eventmaker.EventMakerMarkoutCalculator
import com.wrbug.polymarketbot.service.eventmaker.EventMakerMarketSelector
import com.wrbug.polymarketbot.service.eventmaker.EventMakerQuotePlanner
import com.wrbug.polymarketbot.service.eventmaker.EventMakerRiskPolicy
import com.wrbug.polymarketbot.service.eventmaker.EventMakerShadowExecutionPort
import com.wrbug.polymarketbot.service.eventmaker.EventMakerShadowFill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private data class ShadowOptions(
    val proxyUrl: String?,
    val outputDir: Path,
    val maxMarkets: Int,
    val maxDiscoveryPages: Int,
    val waitForMarketMinutes: Int,
    val discoveryRetrySeconds: Long,
    val minVolume24hr: Double,

    val minRecentTrades30m: Int,
    val minRecentNotional30m: Double,
    val minRecentTradesPerSide30m: Int,
    val minRecentTrades2h: Int,
    val minRecentNotional2h: Double,
    val minRecentTradesPerSide2h: Int,
    val minPrice: Double,
    val maxPrice: Double,
    val minRemainingMinutes: Int,
    val maxRemainingMinutes: Int?,
    val minutes: Int,
    val allowNegRisk: Boolean,
    val pollSeconds: Long,
    val snapshotSeconds: Long,
    val maxQueueAheadShares: Double,
    val minSpreadTicks: Int,
    val improveTicks: Int,
    val entryOffsetCents: Double,
    val exitImproveTicks: Int,
    val exitSpreadCents: Double,
    val requoteSeconds: Long,
    val maxOrderAgeSeconds: Long,
    val flowGuardWindowSeconds: Long,
    val flowGuardMaxSignedFlow: Double,
    val flowGuardMinPriceDropCents: Double,
    val staleBookSeconds: Long,
    val activeOrderStaleBookSeconds: Long,
    val orderNotional: Double,
    val fillVolumeFactor: Double,
    val maxCyclesPerMarket: Int
)

private data class ShadowSummary(
    val mode: String,
    val startedAt: Long,
    val stoppedAt: Long,
    val durationSeconds: Long,
    val markets: Int,
    val trades: Long,
    val duplicateTradeEvents: Long,
    val bookUpdates: Long,
    val quotesPlaced: Int,
    val cancels: Int,
    val fills: Int,
    val crossingOrders: Int,
    val crossingTrades: Int,
    val crossingShares: Double,
    val queueConsumedShares: Double,
    val filledShares: Double,
    val executionDiagnosticsFile: String,
    val markoutHorizonsSeconds: List<Long>,
    val droppedPendingMarkouts: Int,
    val completedCycles: Int,
    val winningCycles: Int,
    val losingCycles: Int,
    val realizedPnl: Double,
    val unrealizedPnl: Double,
    val totalPnl: Double,
    val openInventoryShares: Double,
    val discoveryAttempts: Int,
    val marketWaitSeconds: Long,
    val marketDiscovery: MarketDiscoveryStats,
    val haltedReason: String?,

    val lastErrors: List<String>
)

private data class DiscoveryAttemptLog(
    val attempt: Int,
    val timestamp: Long,
    val qualifiedMarkets: Int,
    val activityPassedMarkets: Int,
    val selectedMarkets: Int,
    val activityRejectionReasons: Map<String, Int>,
    val selectionReasons: Map<String, Int>,
    val stoppedReason: String
)

private data class ShadowSnapshot(
    val timestamp: Long,
    val marketId: String,
    val phase: String,
    val bestBid: Double?,
    val bestAsk: Double?,
    val entryPrice: String,
    val inventoryShares: String,
    val activeOrderId: String?,
    val activeOrderPrice: String?,
    val realizedPnl: Double,
    val unrealizedPnl: Double,
    val completedCycles: Int,
    val fills: Int
)

private sealed interface ShadowEvent {
    data class Feed(val event: LiveFeedEvent) : ShadowEvent
    data class Tick(val now: Long) : ShadowEvent
}

fun main(args: Array<String>) {
    val options = parseOptions(args)
    println("Event Maker unified Shadow")
    println("proxy=${options.proxyUrl ?: "(direct)"} output=${options.outputDir.toAbsolutePath()}")
    val summary = EventMakerShadowRunner(options).run()
    println(GsonBuilder().setPrettyPrinting().create().toJson(summary))
    exitProcess(if (summary.haltedReason == null) 0 else 2)
}

private fun parseOptions(args: Array<String>): ShadowOptions {
    fun option(name: String): String? {
        val index = args.indexOf("--$name")
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val minutes = option("minutes")?.toIntOrNull() ?: 120
    val snapshotSeconds = option("snapshot-seconds")?.toLongOrNull() ?: 10L
    val pollSeconds = option("poll-seconds")?.toLongOrNull() ?: 3L
    val requoteSeconds = option("requote-seconds")?.toLongOrNull() ?: 120L
    val maxOrderAgeSeconds = option("max-order-age-seconds")?.toLongOrNull() ?: 3600L
    val flowGuardWindowSeconds = option("flow-guard-window-seconds")?.toLongOrNull() ?: 3600L
    val flowGuardMaxSignedFlow = option("flow-guard-max-signed-flow")?.toDoubleOrNull() ?: -0.4
    val flowGuardMinPriceDropCents = option("flow-guard-min-price-drop-cents")?.toDoubleOrNull() ?: 0.5
    val staleBookSeconds = option("stale-book-seconds")?.toLongOrNull() ?: 30L
    val activeOrderStaleBookSeconds = option("active-order-stale-seconds")?.toLongOrNull() ?: 600L
    val orderNotional = option("order-notional")?.toDoubleOrNull() ?: 25.0
    val fillVolumeFactor = option("fill-volume-factor")?.toDoubleOrNull() ?: 1.0
    val maxCyclesPerMarket = option("max-cycles-per-market")?.toIntOrNull() ?: 3
    val discoveryPages = option("discovery-pages")?.toIntOrNull() ?: 50
    val waitForMarketMinutes = option("wait-for-market-minutes")?.toIntOrNull() ?: 0
    val discoveryRetrySeconds = option("discovery-retry-seconds")?.toLongOrNull() ?: 60L
    val minPrice = option("min-price")?.toDoubleOrNull() ?: 0.90
    val maxPrice = option("max-price")?.toDoubleOrNull() ?: 0.99
    require(minutes > 0)
    require(snapshotSeconds > 0)
    require(pollSeconds > 0)
    require(requoteSeconds > 0)
    require(maxOrderAgeSeconds >= requoteSeconds)
    require(flowGuardWindowSeconds >= 0)
    require(flowGuardMaxSignedFlow in -1.0..1.0)
    require(flowGuardMinPriceDropCents >= 0.0)
    require(staleBookSeconds > 0)
    require(activeOrderStaleBookSeconds >= staleBookSeconds)
    require(orderNotional > 0.0)
    require(fillVolumeFactor >= 0.0)
    require(maxCyclesPerMarket >= 1)
    require(discoveryPages in 1..100)
    require(waitForMarketMinutes >= 0)
    require(discoveryRetrySeconds > 0)
    require(minPrice >= 0.0 && minPrice < maxPrice && maxPrice <= 1.0)
    return ShadowOptions(
        proxyUrl = option("proxy"),
        outputDir = Paths.get(option("output") ?: "build/research/event-maker-shadow-$stamp"),
        maxMarkets = option("max-markets")?.toIntOrNull() ?: 6,
        maxDiscoveryPages = discoveryPages,
        waitForMarketMinutes = waitForMarketMinutes,
        discoveryRetrySeconds = discoveryRetrySeconds,
        minVolume24hr = option("min-volume")?.toDoubleOrNull() ?: 50_000.0,

        minRecentTrades30m = option("min-recent-trades")?.toIntOrNull() ?: 0,
        minRecentNotional30m = option("min-recent-notional")?.toDoubleOrNull() ?: 0.0,
        minRecentTradesPerSide30m = option("min-recent-trades-per-side")?.toIntOrNull() ?: 0,
        minRecentTrades2h = option("min-recent-trades-2h")?.toIntOrNull() ?: 0,
        minRecentNotional2h = option("min-recent-notional-2h")?.toDoubleOrNull() ?: 0.0,
        minRecentTradesPerSide2h = option("min-recent-trades-per-side-2h")?.toIntOrNull() ?: 0,
        minPrice = option("min-price")?.toDoubleOrNull() ?: 0.90,
        maxPrice = option("max-price")?.toDoubleOrNull() ?: 0.99,
        minRemainingMinutes = option("min-remaining-minutes")?.toIntOrNull() ?: 120,
        maxRemainingMinutes = option("max-remaining-minutes")?.toIntOrNull(),
        minutes = minutes,
        allowNegRisk = args.contains("--allow-neg-risk"),
        pollSeconds = pollSeconds,
        snapshotSeconds = snapshotSeconds,
        maxQueueAheadShares = option("max-queue")?.toDoubleOrNull() ?: 500.0,
        minSpreadTicks = option("min-spread-ticks")?.toIntOrNull() ?: 2,
        improveTicks = option("improve-ticks")?.toIntOrNull() ?: 1,
        entryOffsetCents = option("entry-offset-cents")?.toDoubleOrNull() ?: 0.0,
        exitImproveTicks = option("exit-improve-ticks")?.toIntOrNull() ?: 1,
        exitSpreadCents = option("exit-spread-cents")?.toDoubleOrNull() ?: 1.0,
        requoteSeconds = requoteSeconds,
        maxOrderAgeSeconds = maxOrderAgeSeconds,
        flowGuardWindowSeconds = flowGuardWindowSeconds,
        flowGuardMaxSignedFlow = flowGuardMaxSignedFlow,
        flowGuardMinPriceDropCents = flowGuardMinPriceDropCents,
        staleBookSeconds = staleBookSeconds,
        activeOrderStaleBookSeconds = activeOrderStaleBookSeconds,
        orderNotional = orderNotional,
        fillVolumeFactor = fillVolumeFactor,
        maxCyclesPerMarket = maxCyclesPerMarket
    )
}

private val MARKOUT_HORIZONS_SECONDS = listOf(60L, 300L, 1_800L)

private class EventMakerShadowRunner(private val options: ShadowOptions) {
    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()
    private val dataClient = LivePaperDataClient(options.proxyUrl)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val channel = Channel<ShadowEvent>(Channel.UNLIMITED)
    private val books = mutableMapOf<String, MarketBook>()
    private val seenTrades = object : LinkedHashMap<String, Boolean>(20_000, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 100_000
    }
    private val errors = ArrayDeque<String>()
    private var wsClient: LivePaperWsClient? = null
    private var trades = 0L
    private var duplicateTradeEvents = 0L
    private var bookUpdates = 0L
    private var quotesPlaced = 0
    private var cancels = 0
    private var lastSnapshotAt = 0L
    private lateinit var eventsWriter: java.io.BufferedWriter
    private lateinit var snapshotsWriter: java.io.BufferedWriter
    private lateinit var tradesWriter: java.io.BufferedWriter
    private lateinit var markoutsWriter: java.io.BufferedWriter
    private val pendingMarkouts = mutableListOf<PendingMarkout>()
    private var droppedPendingMarkouts = 0
    private var selectedMarkets: List<LivePaperMarket> = emptyList()

    fun run(): ShadowSummary = runBlocking {
        val runnerStartedAt = System.currentTimeMillis()
        Files.createDirectories(options.outputDir)
        listOf("summary.json", "state.json", "execution-diagnostics.json", "market-watch-timeout.json", "markets.json").forEach { name ->
            Files.deleteIfExists(options.outputDir.resolve(name))
        }
        eventsWriter = Files.newBufferedWriter(
            options.outputDir.resolve("events.jsonl"),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        snapshotsWriter = Files.newBufferedWriter(
            options.outputDir.resolve("snapshots.csv"),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        snapshotsWriter.write("timestamp,market_id,phase,best_bid,best_ask,entry_price,inventory_shares,active_order_id,active_order_price,realized_pnl,unrealized_pnl,completed_cycles,fills\n")
        tradesWriter = Files.newBufferedWriter(
            options.outputDir.resolve("trades.csv"),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        tradesWriter.write("timestamp,condition_id,token_id,side,price,size,source,transaction_hash\n")
        markoutsWriter = Files.newBufferedWriter(
            options.outputDir.resolve("markouts.csv"),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        markoutsWriter.write("fill_timestamp,market_id,order_id,side,fill_price,shares,horizon_seconds,markout_at,book_age_ms,best_bid,best_ask,markout_pnl\n")

        val discoveryAttemptsWriter = Files.newBufferedWriter(
            options.outputDir.resolve("discovery-attempts.jsonl"),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        var discoveryAttempt = 0
        lateinit var discovery: MarketDiscoveryResult
        lateinit var discovered: List<LivePaperMarket>
        val waitDeadline = System.currentTimeMillis() + options.waitForMarketMinutes * 60_000L
        while (true) {
            discoveryAttempt++
            books.clear()
            discovery = dataClient.discoverMarketsDetailed(
                maxMarkets = options.maxMarkets * 3,
                minVolume24hr = options.minVolume24hr,
                allowNegRisk = options.allowNegRisk,
                minRemainingMinutes = options.minRemainingMinutes,
                maxRemainingMinutes = options.maxRemainingMinutes,
                minRecentTrades30m = options.minRecentTrades30m,
                minRecentNotional30m = options.minRecentNotional30m,
                minRecentTradesPerSide30m = options.minRecentTradesPerSide30m,
                minRecentTrades2h = options.minRecentTrades2h,
                minRecentNotional2h = options.minRecentNotional2h,
                minRecentTradesPerSide2h = options.minRecentTradesPerSide2h,
                minPrice = options.minPrice,
                maxPrice = options.maxPrice,
                maxPagesPerTag = options.maxDiscoveryPages
            )
            Files.writeString(
                options.outputDir.resolve("market-discovery.json"),
                prettyGson.toJson(discovery),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            var candidates = discovery.markets
            candidates = if (candidates.isEmpty()) {
                emptyList()
            } else {
                candidates.map { market ->
                    val tick = dataClient.getTickSize(market.favoriteTokenId)
                    if (tick != null) market.copy(tickSize = tick) else market
                }.also { priced ->
                    priced.flatMap { it.tokenIds }.forEach { tokenId ->
                        dataClient.getBook(tokenId)?.let { books[tokenId] = it }
                    }
                }
            }
            val evaluations = if (candidates.isEmpty()) {
                emptyList()
            } else {
                EventMakerMarketSelector.evaluate(
                    markets = candidates,
                    books = books,
                    minSpreadTicks = options.minSpreadTicks,
                    minPrice = options.minPrice,
                    maxPrice = options.maxPrice,
                    maxQueueAheadShares = options.maxQueueAheadShares,
                    improveTicks = options.improveTicks,
                    entryOffsetCents = options.entryOffsetCents,
                    exitImproveTicks = options.exitImproveTicks,
                    exitSpreadCents = options.exitSpreadCents
                )
            }
            Files.writeString(
                options.outputDir.resolve("market-selection.json"),
                prettyGson.toJson(evaluations),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            discovered = if (candidates.isEmpty()) {
                emptyList()
            } else {
                EventMakerMarketSelector.select(
                    markets = candidates,
                    books = books,
                    maxMarkets = options.maxMarkets,
                    minSpreadTicks = options.minSpreadTicks,
                    minPrice = options.minPrice,
                    maxPrice = options.maxPrice,
                    maxQueueAheadShares = options.maxQueueAheadShares,
                    improveTicks = options.improveTicks,
                    entryOffsetCents = options.entryOffsetCents,
                    exitImproveTicks = options.exitImproveTicks,
                    exitSpreadCents = options.exitSpreadCents
                )
            }
            val activityRejectionReasons = discovery.activityDiagnostics
                .mapNotNull { it.rejectionReason }
                .groupingBy { it }
                .eachCount()
            val selectionReasons = evaluations.groupingBy { it.reason }.eachCount()
            discoveryAttemptsWriter.write(
                gson.toJson(
                    DiscoveryAttemptLog(
                        attempt = discoveryAttempt,
                        timestamp = System.currentTimeMillis(),
                        qualifiedMarkets = discovery.qualifiedMarkets.size,
                        activityPassedMarkets = discovery.activityPassedMarkets.size,
                        selectedMarkets = discovered.size,
                        activityRejectionReasons = activityRejectionReasons,
                        selectionReasons = selectionReasons,
                        stoppedReason = discovery.stats.stoppedReason
                    )
                )
            )
            discoveryAttemptsWriter.newLine()
            discoveryAttemptsWriter.flush()
            println(
                "marketDiscoveryAttempt attempt=$discoveryAttempt qualified=${discovery.qualifiedMarkets.size} " +
                    "activityPassed=${discovery.activityPassedMarkets.size} selected=${discovered.size} " +
                    "activityRejections=$activityRejectionReasons reasons=$selectionReasons " +
                    "stopped=${discovery.stats.stoppedReason}"
            )
            if (discovered.isNotEmpty()) break
            val now = System.currentTimeMillis()
            if (now >= waitDeadline) {
                Files.writeString(
                    options.outputDir.resolve("market-watch-timeout.json"),
                    prettyGson.toJson(
                        mapOf(
                            "createdAt" to now,
                            "waitForMarketMinutes" to options.waitForMarketMinutes,
                            "attempts" to discoveryAttempt,
                            "lastDiscoveryStoppedReason" to discovery.stats.stoppedReason,
                            "lastSelectionReasons" to selectionReasons
                        )
                    ),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                )
                discoveryAttemptsWriter.close()
                eventsWriter.close()
                snapshotsWriter.close()
                tradesWriter.close()
                markoutsWriter.close()
                dataClient.close()
                throw IllegalArgumentException("No eligible markets after $discoveryAttempt discovery attempt(s)")
            }
            val sleepMs = minOf(options.discoveryRetrySeconds * 1_000L, waitDeadline - now).coerceAtLeast(250L)
            println("marketWait attempt=$discoveryAttempt retryInMs=$sleepMs waitRemainingSeconds=${maxOf(0L, waitDeadline - now) / 1_000L}")
            delay(sleepMs)
        }
        discoveryAttemptsWriter.close()
        val marketWaitSeconds = (System.currentTimeMillis() - runnerStartedAt) / 1_000L
        val config = EventMakerConfig(
            mode = com.wrbug.polymarketbot.service.eventmaker.EventMakerMode.SHADOW,
            maxMarkets = discovered.size,
            minVolume24hr = options.minVolume24hr,
            minRecentTrades30m = options.minRecentTrades30m,
            minRecentNotional30m = options.minRecentNotional30m,
            minRecentTradesPerSide30m = options.minRecentTradesPerSide30m,
            minRecentTrades2h = options.minRecentTrades2h,
            minRecentNotional2h = options.minRecentNotional2h,
            minRecentTradesPerSide2h = options.minRecentTradesPerSide2h,
            minRemainingMinutes = options.minRemainingMinutes,
            maxRemainingMinutes = options.maxRemainingMinutes,
            allowNegRisk = options.allowNegRisk,
            minPrice = options.minPrice,
            maxPrice = options.maxPrice,
            orderNotional = options.orderNotional,
            shadowFillVolumeFactor = options.fillVolumeFactor,
            maxQueueAheadShares = options.maxQueueAheadShares,
            minSpreadTicks = options.minSpreadTicks,
            improveTicks = options.improveTicks,
            entryOffsetCents = options.entryOffsetCents,
            exitSpreadCents = options.exitSpreadCents,
            exitImproveTicks = options.exitImproveTicks,
            maxTotalInventoryNotional = 100.0,
            maxDailyLoss = 5.0,
            maxCyclesPerMarket = options.maxCyclesPerMarket,
            requoteSeconds = options.requoteSeconds,
            maxOrderAgeSeconds = options.maxOrderAgeSeconds,
            flowGuardWindowSeconds = options.flowGuardWindowSeconds,
            flowGuardMaxSignedFlow = options.flowGuardMaxSignedFlow,
            flowGuardMinPriceDropCents = options.flowGuardMinPriceDropCents,
            staleBookSeconds = options.staleBookSeconds,
            activeOrderStaleBookSeconds = options.activeOrderStaleBookSeconds
        )
        selectedMarkets = discovered
        val states = discovered.map { EventMakerLiveState(it, config) }
        shadowExecution = EventMakerShadowExecutionPort()
        shadowExecution.reset()
        shadowExecution.configureFillModel(options.fillVolumeFactor)
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), shadowExecution)
        if (options.flowGuardWindowSeconds > 0) {
            discovered.forEach { market -> dataClient.getRecentTrades(market).forEach(engine::onTrade) }
        }
        discovered.forEach { market -> books[market.favoriteTokenId]?.let { states.first { s -> s.market.conditionId == market.conditionId }.onBook(it) } }
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + options.minutes * 60_000L
        lastSnapshotAt = startedAt
        writeMarketList(discovered)
        println("markets=${discovered.size} minutes=${options.minutes} minRemaining=${options.minRemainingMinutes}")

        wsClient = LivePaperWsClient(
            proxyUrl = options.proxyUrl,
            tokenToCondition = discovered.flatMap { market -> market.tokenIds.map { it to market.conditionId } }.toMap(),
            onEvent = { channel.trySend(ShadowEvent.Feed(it)) },
            onStatus = { status -> println(status); writeEvent(mapOf("type" to "ws_status", "status" to status, "timestamp" to System.currentTimeMillis())) }
        ).also { it.start() }
        val jobs = mutableListOf<Job>()
        jobs += scope.launch {
            val lastSeen = mutableMapOf<String, Long>()
            while (isActive) {
                delay(options.pollSeconds * 1000L)
                discovered.forEach { market ->
                    // Only ask for prints newer than the last forwarded one; the Data
                    // API otherwise re-serves the same 200 rows on every poll.
                    val since = lastSeen[market.conditionId]
                    val fresh = dataClient.getRecentTrades(market, since)
                    if (fresh.isNotEmpty()) {
                        lastSeen[market.conditionId] = fresh.maxOf { it.timestamp }
                        fresh.forEach { channel.trySend(ShadowEvent.Feed(LiveFeedEvent.Trade(it))) }
                    }
                }
            }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(1_000)
                channel.trySend(ShadowEvent.Tick(System.currentTimeMillis()))
            }
        }

        var haltedReason: String? = null
        try {
            loop@ for (event in channel) {
                var duplicateTrade = false
                when (event) {
                    is ShadowEvent.Feed -> when (val feed = event.event) {
                        is LiveFeedEvent.BookUpdated -> applyBook(feed.book, states, discovered)
                        is LiveFeedEvent.PricesChanged -> feed.changes.forEach { change ->
                            val current = books[change.tokenId] ?: MarketBook(change.tokenId, emptyList(), emptyList(), change.timestamp)
                            applyBook(current.applyPriceChange(change), states, discovered)
                        }
                        is LiveFeedEvent.Trade -> duplicateTrade = applyTrade(feed.trade, engine)
                        is LiveFeedEvent.Unknown -> writeEvent(mapOf("type" to "unknown", "eventType" to feed.eventType))
                    }
                    is ShadowEvent.Tick -> Unit
                }
                val now = System.currentTimeMillis()
                if (duplicateTrade) {
                    // Duplicate print: no new information, so skip the engine tick.
                    writeDueMarkouts(now)
                    if (now >= deadline) break@loop
                    continue@loop
                }
                val result = engine.reconcile(states, 0L, now).merge(engine.quote(states, books, 0L, now))
                recordEngineResult(result)
                if (result.haltedReason != null) {
                    haltedReason = result.haltedReason
                    break@loop
                }
                writeDueMarkouts(now)
                if (now - lastSnapshotAt >= options.snapshotSeconds * 1000L) {
                    writeSnapshots(states, now)
                    lastSnapshotAt = now
                }
                if (now >= deadline) break@loop
            }
        } finally {
            val shutdownResult = runBlocking { engine.cancelAll(states, 0L) }
            recordEngineResult(shutdownResult)
            val shutdownAt = System.currentTimeMillis()
            // Flush every markout that is already due; otherwise a fill near the
            // end of the run loses its 60s/300s/1800s rows silently.
            writeDueMarkouts(shutdownAt)
            droppedPendingMarkouts = pendingMarkouts.size
            writeSnapshots(states, shutdownAt)
            jobs.forEach { it.cancel() }
            wsClient?.close()
            dataClient.close()
            channel.close()
            scope.cancel()
            eventsWriter.close()
            snapshotsWriter.close()
            tradesWriter.close()
            markoutsWriter.close()
        }

        val diagnostics = shadowExecution.diagnostics()
        Files.writeString(
            options.outputDir.resolve("execution-diagnostics.json"),
            prettyGson.toJson(diagnostics),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        val stoppedAt = System.currentTimeMillis()
        val realized = states.sumOf { it.realizedPnl }
        val unrealized = states.sumOf { it.markToMarket(books[it.market.favoriteTokenId]) }
        val cycles = states.flatMap { it.cycles }
        val summary = ShadowSummary(
            mode = "SHADOW",
            startedAt = startedAt,
            stoppedAt = stoppedAt,
            durationSeconds = (stoppedAt - startedAt) / 1000L,
            markets = discovered.size,
            trades = trades,
            duplicateTradeEvents = duplicateTradeEvents,
            bookUpdates = bookUpdates,
            quotesPlaced = quotesPlaced,
            cancels = cancels,
            fills = states.sumOf { it.fills.size },
            crossingOrders = diagnostics.ordersWithCrossingTrades,
            crossingTrades = diagnostics.totalCrossingTrades,
            crossingShares = diagnostics.totalCrossingShares.toDouble(),
            queueConsumedShares = diagnostics.totalQueueConsumedShares.toDouble(),
            filledShares = diagnostics.totalFilledShares.toDouble(),
            executionDiagnosticsFile = "execution-diagnostics.json",
            markoutHorizonsSeconds = MARKOUT_HORIZONS_SECONDS,
            droppedPendingMarkouts = droppedPendingMarkouts,
            completedCycles = cycles.size,
            winningCycles = cycles.count { it.pnl > 0.0 },
            losingCycles = cycles.count { it.pnl < 0.0 },
            realizedPnl = realized,
            unrealizedPnl = unrealized,
            totalPnl = realized + unrealized,
            openInventoryShares = states.sumOf { it.inventoryShares.toDouble() },
            discoveryAttempts = discoveryAttempt,
            marketWaitSeconds = marketWaitSeconds,
            marketDiscovery = discovery.stats,
            haltedReason = haltedReason ?: engine.haltedReason,
            lastErrors = errors.toList()
        )
        Files.writeString(
            options.outputDir.resolve("summary.json"),
            prettyGson.toJson(summary),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        writeState(states)
        summary
    }

    /**
     * Persist the shadow state atomically-ish on every snapshot tick so a crash
     * still leaves auditable state, not just the final in-memory view.
     */
    private fun writeState(states: List<EventMakerLiveState>) {
        Files.writeString(
            options.outputDir.resolve("state.json"),
            prettyGson.toJson(mapOf("markets" to states.map { it.toPersisted() })),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    private fun applyBook(book: MarketBook, states: List<EventMakerLiveState>, markets: List<LivePaperMarket>) {
        books[book.tokenId] = book
        bookUpdates++
        markets.firstOrNull { it.favoriteTokenId == book.tokenId }?.let { market ->
            states.filter { it.market.conditionId == market.conditionId }.forEach { it.onBook(book) }
        }
    }

    /** Returns true when the print was already seen (no new information). */
    private fun applyTrade(trade: LiveTradePrint, engine: EventMakerLiveEngine): Boolean {
        if (seenTrades.put(trade.key, true) != null) {
            duplicateTradeEvents++
            return true
        }
        trades++
        tradesWriter.write(listOf(trade.timestamp, trade.conditionId, trade.tokenId, trade.side, trade.price, trade.size, trade.source, trade.transactionHash ?: "").joinToString(","))
        tradesWriter.newLine()
        // Flush per trade: a long unattended run must leave a complete,
        // line-aligned trade log even if the process is killed mid-run.
        tradesWriter.flush()
        engine.onTrade(trade)
        shadowExecution.onTrade(trade).forEach { fill ->
            MARKOUT_HORIZONS_SECONDS.forEach { horizonSeconds ->
                pendingMarkouts += PendingMarkout(
                    fill = fill,
                    horizonSeconds = horizonSeconds,
                    dueAt = fill.timestamp + horizonSeconds * 1_000L
                )
            }
        }
        return false
    }

    private lateinit var shadowExecution: EventMakerShadowExecutionPort

    private fun recordEngineResult(result: EventMakerEngineResult) {
        quotesPlaced += result.placed.size
        cancels += result.canceled.size
        val actionableErrors = result.errors.filterNot { it.startsWith("risk_blocked:") }
        actionableErrors.forEach { addError(it) }
        result.haltedReason?.let { addError(it) }
        val meaningfulReconciled = result.reconciled.filter { it.events.isNotEmpty() }
        if (result.placed.isNotEmpty() || result.canceled.isNotEmpty() || meaningfulReconciled.isNotEmpty() || actionableErrors.isNotEmpty()) {
            writeEvent(
                mapOf(
                    "type" to "engine",
                    "timestamp" to System.currentTimeMillis(),
                    "placed" to result.placed,
                    "canceled" to result.canceled,
                    "reconciled" to meaningfulReconciled,
                    "errors" to actionableErrors,
                    "haltedReason" to result.haltedReason
                )
            )
        }
    }

    private fun writeDueMarkouts(now: Long) {
        val due = pendingMarkouts.filter { it.dueAt <= now }
        if (due.isEmpty()) return
        pendingMarkouts.removeAll(due.toSet())
        due.forEach { pending ->
            val fill = pending.fill
            val market = selectedMarkets.firstOrNull { it.conditionId == fill.marketId }
            val book = market?.let { books[it.favoriteTokenId] }
            val bestBid = book?.bestBid
            val bestAsk = book?.bestAsk
            val bookAgeMs = book?.let { (now - it.updatedAt).coerceAtLeast(0L) }
            val pnl = EventMakerMarkoutCalculator.calculate(fill.side, fill.price, fill.shares, bestBid, bestAsk)
            markoutsWriter.write(
                listOf(
                    fill.timestamp,
                    fill.marketId,
                    fill.orderId,
                    fill.side.name,
                    fill.price.toPlainString(),
                    fill.shares.toPlainString(),
                    pending.horizonSeconds,
                    now,
                    bookAgeMs ?: "",
                    bestBid ?: "",
                    bestAsk ?: "",
                    pnl ?: ""
                ).joinToString(",")
            )
            markoutsWriter.newLine()
        }
        markoutsWriter.flush()
    }

    private data class PendingMarkout(
        val fill: EventMakerShadowFill,
        val horizonSeconds: Long,
        val dueAt: Long
    )
    private fun addError(message: String) {
        errors.addLast(message)
        while (errors.size > 100) errors.removeFirst()
    }

    private fun writeSnapshots(states: List<EventMakerLiveState>, now: Long) {
        states.forEach { state ->
            val book = books[state.market.favoriteTokenId]
            val snapshot = ShadowSnapshot(
                timestamp = now,
                marketId = state.market.conditionId,
                phase = state.phase.name,
                bestBid = book?.bestBid,
                bestAsk = book?.bestAsk,
                entryPrice = state.entryPrice.toPlainString(),
                inventoryShares = state.inventoryShares.toPlainString(),
                activeOrderId = state.activeOrder?.orderId,
                activeOrderPrice = state.activeOrder?.price?.toPlainString(),
                realizedPnl = state.realizedPnl,
                unrealizedPnl = state.markToMarket(book),
                completedCycles = state.completedCycles,
                fills = state.fills.size
            )
            snapshotsWriter.write(
                listOf(
                    snapshot.timestamp,
                    snapshot.marketId,
                    snapshot.phase,
                    snapshot.bestBid ?: "",
                    snapshot.bestAsk ?: "",
                    snapshot.entryPrice,
                    snapshot.inventoryShares,
                    snapshot.activeOrderId ?: "",
                    snapshot.activeOrderPrice ?: "",
                    snapshot.realizedPnl,
                    snapshot.unrealizedPnl,
                    snapshot.completedCycles,
                    snapshot.fills
                ).joinToString(",")
            )
            snapshotsWriter.newLine()
        }
        snapshotsWriter.flush()
        writeState(states)
    }

    private fun writeMarketList(markets: List<LivePaperMarket>) {
        Files.writeString(
            options.outputDir.resolve("markets.json"),
            prettyGson.toJson(markets),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    private fun writeEvent(value: Any) {
        eventsWriter.write(gson.toJson(value))
        eventsWriter.newLine()
        eventsWriter.flush()
    }
}