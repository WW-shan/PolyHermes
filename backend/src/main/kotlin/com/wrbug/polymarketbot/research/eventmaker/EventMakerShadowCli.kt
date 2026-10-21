package com.wrbug.polymarketbot.research.eventmaker

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
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
import com.wrbug.polymarketbot.service.eventmaker.EventMakerMode
import com.wrbug.polymarketbot.service.eventmaker.EventMakerPersistedMarket
import com.wrbug.polymarketbot.service.eventmaker.EventMakerPersistedState
import com.wrbug.polymarketbot.service.eventmaker.EventMakerQuotePlanner
import com.wrbug.polymarketbot.service.eventmaker.EventMakerRiskPolicy
import com.wrbug.polymarketbot.service.eventmaker.EventMakerShadowExecutionPort
import com.wrbug.polymarketbot.service.eventmaker.EventMakerShadowFill
import com.wrbug.polymarketbot.service.eventmaker.EventMakerSettlementPosition
import com.wrbug.polymarketbot.service.eventmaker.EventMakerSettlementSnapshot
import com.wrbug.polymarketbot.service.eventmaker.EventMakerStateStore
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

internal data class ShadowOptions(
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
    val entryMomentumMinCents: Double,
    val entryMomentumMaxCents: Double,
    val entryMomentumLookbackSeconds: Long,
    val minPrice: Double,
    val maxPrice: Double,
    val tags: List<String>,
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
    val takerEntry: Boolean,
    val takerEntrySlippageTicks: Int,
    val exitSpreadCents: Double,
    val requoteSeconds: Long,
    val maxOrderAgeSeconds: Long,
    val tailWindowMinutes: Long,
    val flowGuardWindowSeconds: Long,
    val flowGuardMaxSignedFlow: Double,
    val flowGuardMinPriceDropCents: Double,
    val staleBookSeconds: Long,
    val activeOrderStaleBookSeconds: Long,
    val orderNotional: Double,
    val fillVolumeFactor: Double,
    val maxCyclesPerMarket: Int,
    val maxTotalInventoryNotional: Double,
    val maxDailyLoss: Double
)

private data class ShadowSummary(
    val mode: String,
    val resumed: Boolean,
    val recoveredFills: Int,
    val recoveredCycles: Int,
    val recoveredSettlements: Int,
    val recoveredInventoryShares: Double,
    val abandonedRecoveredOrders: Int,
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
    val settlements: Int,
    val settlementLosses: Int,
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

private data class MomentumFilterRow(
    val conditionId: String,
    val question: String,
    val currentPrice: Double?,
    val pastPrice: Double?,
    val deltaCents: Double?,
    val tradeCount: Int,
    val selected: Boolean,
    val reason: String
)

private data class MomentumFilterReport(
    val lookbackSeconds: Long,
    val minCents: Double,
    val maxCents: Double,
    val rows: List<MomentumFilterRow>
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

internal fun parseOptions(args: Array<String>): ShadowOptions {
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
    val tailWindowMinutes = option("tail-window-minutes")?.toLongOrNull() ?: 0L
    val flowGuardWindowSeconds = option("flow-guard-window-seconds")?.toLongOrNull() ?: 3600L
    val flowGuardMaxSignedFlow = option("flow-guard-max-signed-flow")?.toDoubleOrNull() ?: -0.4
    val flowGuardMinPriceDropCents = option("flow-guard-min-price-drop-cents")?.toDoubleOrNull() ?: 0.5
    val staleBookSeconds = option("stale-book-seconds")?.toLongOrNull() ?: 30L
    val activeOrderStaleBookSeconds = option("active-order-stale-seconds")?.toLongOrNull() ?: 600L
    val orderNotional = option("order-notional")?.toDoubleOrNull() ?: 25.0
    val fillVolumeFactor = option("fill-volume-factor")?.toDoubleOrNull() ?: 1.0
    val maxCyclesPerMarket = option("max-cycles-per-market")?.toIntOrNull() ?: 3
    val maxTotalInventoryNotional = option("max-total-inventory-notional")?.toDoubleOrNull() ?: 100.0
    val maxDailyLoss = option("max-daily-loss")?.toDoubleOrNull() ?: 5.0
    val discoveryPages = option("discovery-pages")?.toIntOrNull() ?: 50
    val waitForMarketMinutes = option("wait-for-market-minutes")?.toIntOrNull() ?: 0
    val discoveryRetrySeconds = option("discovery-retry-seconds")?.toLongOrNull() ?: 60L
    val entryMomentumMinCents = option("entry-momentum-min-cents")?.toDoubleOrNull() ?: -999.0
    val entryMomentumMaxCents = option("entry-momentum-max-cents")?.toDoubleOrNull() ?: 999.0
    val entryMomentumLookbackSeconds = option("entry-momentum-lookback-seconds")?.toLongOrNull() ?: 86_400L
    require(entryMomentumMinCents <= entryMomentumMaxCents)
    require(entryMomentumLookbackSeconds > 0)
    val minPrice = option("min-price")?.toDoubleOrNull() ?: 0.90
    val takerEntry = args.contains("--taker-entry")
    val takerEntrySlippageTicks = option("taker-entry-slippage-ticks")?.toIntOrNull() ?: 0
    require(takerEntrySlippageTicks >= 0)
    val maxPrice = option("max-price")?.toDoubleOrNull() ?: 0.99
    require(minutes > 0)
    require(snapshotSeconds > 0)
    require(pollSeconds > 0)
    require(requoteSeconds > 0)
    require(maxOrderAgeSeconds >= requoteSeconds)
    require(tailWindowMinutes >= 0)
    require(flowGuardWindowSeconds >= 0)
    require(flowGuardMaxSignedFlow in -1.0..1.0)
    require(flowGuardMinPriceDropCents >= 0.0)
    require(staleBookSeconds > 0)
    require(activeOrderStaleBookSeconds >= staleBookSeconds)
    require(orderNotional > 0.0)
    require(fillVolumeFactor >= 0.0)
    require(maxCyclesPerMarket >= 1)
    require(maxTotalInventoryNotional > 0.0)
    require(maxDailyLoss > 0.0)
    require(discoveryPages in 1..100)
    require(waitForMarketMinutes >= 0)
    require(discoveryRetrySeconds > 0)
    require(minPrice >= 0.0 && minPrice < maxPrice && maxPrice <= 1.0)
    val tags = (option("tags") ?: "Politics,Geopolitics")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    require(tags.isNotEmpty()) { "at least one --tags value is required" }
    val supportedTags = setOf("Politics", "Geopolitics")
    require(tags.all { tag -> supportedTags.any { it.equals(tag, ignoreCase = true) } }) {
        "unsupported --tags value: $tags"
    }
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
        entryMomentumMinCents = entryMomentumMinCents,
        entryMomentumMaxCents = entryMomentumMaxCents,
        entryMomentumLookbackSeconds = entryMomentumLookbackSeconds,
        minPrice = option("min-price")?.toDoubleOrNull() ?: 0.90,
        maxPrice = option("max-price")?.toDoubleOrNull() ?: 0.99,
        tags = tags,
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
        takerEntry = takerEntry,
        takerEntrySlippageTicks = takerEntrySlippageTicks,
        exitSpreadCents = option("exit-spread-cents")?.toDoubleOrNull() ?: 1.0,
        requoteSeconds = requoteSeconds,
        maxOrderAgeSeconds = maxOrderAgeSeconds,
        tailWindowMinutes = tailWindowMinutes,
        flowGuardWindowSeconds = flowGuardWindowSeconds,
        flowGuardMaxSignedFlow = flowGuardMaxSignedFlow,
        flowGuardMinPriceDropCents = flowGuardMinPriceDropCents,
        staleBookSeconds = staleBookSeconds,
        activeOrderStaleBookSeconds = activeOrderStaleBookSeconds,
        orderNotional = orderNotional,
        fillVolumeFactor = fillVolumeFactor,
        maxCyclesPerMarket = maxCyclesPerMarket,
        maxTotalInventoryNotional = maxTotalInventoryNotional,
        maxDailyLoss = maxDailyLoss
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
    private val stateStore = EventMakerStateStore(options.outputDir.resolve("state.json").toString())
    private val recoveryPath = options.outputDir.resolve("recovery.json")
    private var resumed = false
    private var runStartedAt = 0L
    private var dailyPnlDay = 0L
    private var dailyPnlBaseline = 0.0
    private var recoveredFills = 0
    private var recoveredCycles = 0
    private var recoveredSettlements = 0
    private var recoveredInventoryShares = 0.0
    private var abandonedRecoveredOrders = 0

    fun run(): ShadowSummary = runBlocking {
        val runnerStartedAt = System.currentTimeMillis()
        Files.createDirectories(options.outputDir)
        val persistedState = loadShadowState()
        if (persistedState != null && persistedState.mode != EventMakerMode.SHADOW.name) {
            throw IllegalStateException("shadow_output_contains_non_shadow_state:${persistedState.mode}")
        }
        val persistedRecovery = EventMakerShadowRecoveryStore.load(recoveryPath)
        resumed = persistedState != null
        runStartedAt = persistedState?.startedAt ?: runnerStartedAt
        val today = utcEpochDay(runnerStartedAt)
        dailyPnlDay = persistedState?.dailyPnlDay?.takeIf { it == today } ?: today
        dailyPnlBaseline = if (persistedState?.dailyPnlDay == today) {
            persistedState.dailyPnlBaseline ?: persistedState.cumulativeRealizedPnl
        } else {
            persistedState?.cumulativeRealizedPnl ?: 0.0
        }
        recoveredFills = persistedState?.markets?.sumOf { it.fills.orEmpty().size } ?: 0
        recoveredCycles = persistedState?.markets?.sumOf { it.cycles.orEmpty().size } ?: 0
        recoveredSettlements = persistedState?.markets?.sumOf { it.settlements.orEmpty().size } ?: 0
        recoveredInventoryShares = persistedState?.markets?.sumOf {
            it.inventoryShares.toBigDecimalOrNull()?.toDouble() ?: 0.0
        } ?: 0.0
        val recoveryMarkets = persistedRecovery?.markets.orEmpty()

        // summary/diagnostics are per-process views and are rewritten during this run.
        // state.json and recovery.json are the durable ledger and must survive a restart.
        listOf("summary.json", "execution-diagnostics.json", "market-watch-timeout.json", "markets.json").forEach { name ->
            Files.deleteIfExists(options.outputDir.resolve(name))
        }
        fun openLog(name: String): java.io.BufferedWriter {
            val path = options.outputDir.resolve(name)
            val openOptions = if (resumed) {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
            } else {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            }
            return Files.newBufferedWriter(path, StandardCharsets.UTF_8, *openOptions)
        }
        fun needsHeader(name: String): Boolean {
            val path = options.outputDir.resolve(name)
            return !resumed || !Files.exists(path) || Files.size(path) == 0L
        }

        eventsWriter = openLog("events.jsonl")
        val snapshotHeader = needsHeader("snapshots.csv")
        snapshotsWriter = openLog("snapshots.csv")
        if (snapshotHeader) {
            snapshotsWriter.write("timestamp,market_id,phase,best_bid,best_ask,entry_price,inventory_shares,active_order_id,active_order_price,realized_pnl,unrealized_pnl,completed_cycles,fills\n")
        }
        val tradeHeader = needsHeader("trades.csv")
        tradesWriter = openLog("trades.csv")
        if (tradeHeader) {
            tradesWriter.write("timestamp,condition_id,token_id,side,price,size,source,transaction_hash\n")
        }
        val markoutHeader = needsHeader("markouts.csv")
        markoutsWriter = openLog("markouts.csv")
        if (markoutHeader) {
            markoutsWriter.write("fill_timestamp,market_id,order_id,side,fill_price,shares,horizon_seconds,markout_at,book_age_ms,best_bid,best_ask,markout_pnl\n")
        }
        val discoveryAttemptsWriter = openLog("discovery-attempts.jsonl")
        if (resumed) {
            writeEvent(
                mapOf(
                    "type" to "shadow_resume",
                    "timestamp" to runnerStartedAt,
                    "originalStartedAt" to runStartedAt,
                    "recoveredFills" to recoveredFills,
                    "recoveredCycles" to recoveredCycles,
                    "recoveredSettlements" to recoveredSettlements,
                    "recoveredInventoryShares" to recoveredInventoryShares
                )
            )
        }
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
                tags = options.tags,
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
            candidates = applyMomentumFilter(candidates, System.currentTimeMillis())
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
                    exitSpreadCents = options.exitSpreadCents,
                    takerEntry = options.takerEntry
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
                    exitSpreadCents = options.exitSpreadCents,
                    takerEntry = options.takerEntry
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
            val recoveredHasWork = persistedState?.markets?.any { persistedMarket ->
                (persistedMarket.inventoryShares.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) >
                    java.math.BigDecimal.ZERO ||
                    persistedMarket.settlements.orEmpty().any {
                        it.redemptionStatus == com.wrbug.polymarketbot.service.eventmaker.EventMakerRedemptionStatus.PENDING
                    }
            } == true
            if (discovered.isNotEmpty() || recoveredHasWork) break
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
        val ledgerMarkets = persistedState?.markets.orEmpty()
        val ledgerByCondition = ledgerMarkets.associateBy { it.conditionId }
        val recoveredByCondition = recoveryMarkets.associateBy { it.conditionId }
        val recoveredMarketMetadata = ledgerMarkets.mapNotNull { persistedMarket ->
            recoveredByCondition[persistedMarket.conditionId]
                ?: EventMakerShadowRecoveryStore.reconstructMarket(persistedMarket)
                ?: throw IllegalStateException(
                    "shadow_recovery_market_metadata_missing:${persistedMarket.conditionId}"
                )
        }
        val allMarkets = EventMakerShadowRecoveryStore.merge(discovered, recoveredMarketMetadata)
        val discoveredConditionIds = discovered.map { it.conditionId }.toSet()
        val config = EventMakerConfig(
            mode = EventMakerMode.SHADOW,
            maxMarkets = maxOf(1, allMarkets.size),
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
            takerEntry = options.takerEntry,
            takerEntrySlippageTicks = options.takerEntrySlippageTicks,
            maxTotalInventoryNotional = options.maxTotalInventoryNotional,
            maxDailyLoss = options.maxDailyLoss,
            maxCyclesPerMarket = options.maxCyclesPerMarket,
            requoteSeconds = options.requoteSeconds,
            maxOrderAgeSeconds = options.maxOrderAgeSeconds,
            tailWindowMinutes = options.tailWindowMinutes,
            flowGuardWindowSeconds = options.flowGuardWindowSeconds,
            flowGuardMaxSignedFlow = options.flowGuardMaxSignedFlow,
            flowGuardMinPriceDropCents = options.flowGuardMinPriceDropCents,
            staleBookSeconds = options.staleBookSeconds,
            activeOrderStaleBookSeconds = options.activeOrderStaleBookSeconds
        )
        selectedMarkets = allMarkets
        val states = allMarkets.map { market ->
            EventMakerLiveState(market, config).apply {
                ledgerByCondition[market.conditionId]?.let { persistedMarket ->
                    restore(persistedMarket)
                    // A Shadow order lives only in the previous JVM. The ledger
                    // keeps fills/inventory, but the resting queue position is gone.
                    if (activeOrder != null) {
                        onOrderCanceled()
                        abandonedRecoveredOrders++
                    }
                }
            }
        }
        if (abandonedRecoveredOrders > 0) {
            writeEvent(
                mapOf(
                    "type" to "shadow_recovered_orders_abandoned",
                    "timestamp" to System.currentTimeMillis(),
                    "count" to abandonedRecoveredOrders
                )
            )
        }
        shadowExecution = EventMakerShadowExecutionPort()
        shadowExecution.reset()
        shadowExecution.configureOrderIdPrefix(
            "shadow-${ProcessHandle.current().pid()}-$runnerStartedAt"
        )
        shadowExecution.configureFillModel(options.fillVolumeFactor)
        shadowExecution.configureSettlementResolver { market ->
            dataClient.getMarketResolution(market.conditionId).map { resolution ->
                val state = states.firstOrNull { it.market.conditionId == market.conditionId }
                val position = state
                    ?.takeIf { it.inventoryShares.signum() > 0 }
                    ?.let {
                        EventMakerSettlementPosition(
                            tokenId = market.favoriteTokenId,
                            shares = it.inventoryShares,
                            redeemable = true,
                            outcomeIndex = market.favoriteIndex,
                            negativeRisk = market.negRisk
                        )
                    }
                EventMakerSettlementSnapshot(
                    resolutionOutcomeIndex = resolution.resolvedOutcomeIndex,
                    position = position
                )
            }
        }
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), shadowExecution)
        engine.resetDailyPnlBaseline(states)
        val watchedMarkets = allMarkets.filter { market ->
            market.conditionId in discoveredConditionIds ||
                states.first { it.market.conditionId == market.conditionId }.inventoryShares > java.math.BigDecimal.ZERO
        }
        if (options.flowGuardWindowSeconds > 0) {
            watchedMarkets.forEach { market -> dataClient.getRecentTrades(market).forEach(engine::onTrade) }
        }
        watchedMarkets.forEach { market ->
            if (!books.containsKey(market.favoriteTokenId)) {
                dataClient.getBook(market.favoriteTokenId)?.let { books[market.favoriteTokenId] = it }
            }
        }
        allMarkets.forEach { market ->
            books[market.favoriteTokenId]?.let { book ->
                states.firstOrNull { it.market.conditionId == market.conditionId }?.onBook(book)
            }
        }
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + options.minutes * 60_000L
        lastSnapshotAt = startedAt
        writeMarketList(allMarkets)
        writeState(states)
        println(
            "markets=${allMarkets.size} discovered=${discovered.size} resumed=$resumed " +
                "minutes=${options.minutes} minRemaining=${options.minRemainingMinutes}"
        )

        wsClient = LivePaperWsClient(
            proxyUrl = options.proxyUrl,
            tokenToCondition = watchedMarkets.flatMap { market -> market.tokenIds.map { it to market.conditionId } }.toMap(),
            onEvent = { channel.trySend(ShadowEvent.Feed(it)) },
            onStatus = { status -> println(status); writeEvent(mapOf("type" to "ws_status", "status" to status, "timestamp" to System.currentTimeMillis())) }
        ).also { it.start() }
        val jobs = mutableListOf<Job>()
        jobs += scope.launch {
            val lastSeen = loadLastTradeTimestamps().toMutableMap()
            while (isActive) {
                delay(options.pollSeconds * 1000L)
                watchedMarkets.forEach { market ->
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
                        is LiveFeedEvent.BookUpdated -> applyBook(feed.book, states, allMarkets)
                        is LiveFeedEvent.PricesChanged -> feed.changes.forEach { change ->
                            val current = books[change.tokenId] ?: MarketBook(change.tokenId, emptyList(), emptyList(), change.timestamp)
                            applyBook(current.applyPriceChange(change), states, allMarkets)
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
                val result = engine.reconcile(states, 0L, now)
                    .merge(engine.settleResolved(states.filter { it.isExpired(now) }, 0L, now))
                    .merge(engine.redeemPendingRedemptions(states, 0L, now))
                    .merge(engine.quote(states, books, 0L, now, discoveredConditionIds))
                recordEngineResult(result)
                if (result.hasStateChanges()) writeState(states)
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

        writeExecutionDiagnostics()
        val diagnostics = shadowExecution.diagnostics()
        val stoppedAt = System.currentTimeMillis()
        val realized = states.sumOf { it.realizedPnl }
        val unrealized = states.sumOf { it.markToMarket(books[it.market.favoriteTokenId]) }
        val cycles = states.flatMap { it.cycles }
        val summary = ShadowSummary(
            mode = "SHADOW",
            resumed = resumed,
            recoveredFills = recoveredFills,
            recoveredCycles = recoveredCycles,
            recoveredSettlements = recoveredSettlements,
            recoveredInventoryShares = recoveredInventoryShares,
            abandonedRecoveredOrders = abandonedRecoveredOrders,
            startedAt = runStartedAt,
            stoppedAt = stoppedAt,
            durationSeconds = (stoppedAt - runStartedAt) / 1000L,
            markets = allMarkets.size,
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
            settlements = cycles.count { it.exitType == "SETTLE" },
            settlementLosses = cycles.count { it.exitType == "SETTLE" && it.pnl < 0.0 },
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
    /**
     * Optional 24h/1h momentum gate. It compares the current favorite best bid
     * with the first favorite-token print inside the lookback window. A negative
     * delta means the favorite has fallen, which is the leader's strongest entry
     * subset. The report is written even when the gate rejects every market.
     */
    private suspend fun applyMomentumFilter(
        markets: List<LivePaperMarket>,
        now: Long
    ): List<LivePaperMarket> {
        val active = options.entryMomentumMinCents > -900.0 || options.entryMomentumMaxCents < 900.0
        if (!active) return markets
        val cutoff = now - options.entryMomentumLookbackSeconds * 1_000L
        val rows = markets.map { market ->
            val trades = dataClient.getRecentTrades(market, cutoff)
                .filter { it.tokenId == market.favoriteTokenId && it.timestamp >= cutoff }
            val pastPrice = trades.firstOrNull()?.price
            val currentPrice = books[market.favoriteTokenId]?.bestBid ?: trades.lastOrNull()?.price
            val deltaCents = if (pastPrice != null && currentPrice != null) {
                (currentPrice - pastPrice) * 100.0
            } else {
                null
            }
            val selected = deltaCents != null &&
                deltaCents >= options.entryMomentumMinCents &&
                deltaCents <= options.entryMomentumMaxCents
            MomentumFilterRow(
                conditionId = market.conditionId,
                question = market.question,
                currentPrice = currentPrice,
                pastPrice = pastPrice,
                deltaCents = deltaCents,
                tradeCount = trades.size,
                selected = selected,
                reason = when {
                    deltaCents == null -> "insufficient_history"
                    selected -> "selected"
                    else -> "momentum_out_of_range"
                }
            )
        }
        Files.writeString(
            options.outputDir.resolve("momentum-filter.json"),
            prettyGson.toJson(
                MomentumFilterReport(
                    lookbackSeconds = options.entryMomentumLookbackSeconds,
                    minCents = options.entryMomentumMinCents,
                    maxCents = options.entryMomentumMaxCents,
                    rows = rows
                )
            ),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        return markets.filter { market -> rows.first { it.conditionId == market.conditionId }.selected }
    }

    /**
     * The first Shadow builds wrote only {"markets":[...]}. Accept that shape
     * once, then immediately persist the full versioned ledger on startup.
     */
    private fun loadShadowState(): EventMakerPersistedState? {
        val path = options.outputDir.resolve("state.json")
        if (!Files.exists(path)) return null
        val root = try {
            gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject::class.java)
        } catch (error: Exception) {
            throw IllegalStateException("shadow_state_corrupt", error)
        } ?: throw IllegalStateException("shadow_state_empty")
        if (root.has("mode")) return stateStore.load()
        val legacy = try {
            gson.fromJson(root, LegacyShadowState::class.java)
        } catch (error: Exception) {
            throw IllegalStateException("shadow_state_legacy_corrupt", error)
        }
        val now = System.currentTimeMillis()
        return EventMakerPersistedState(
            mode = EventMakerMode.SHADOW.name,
            accountId = null,
            startedAt = now,
            cumulativeRealizedPnl = legacy.markets.sumOf { it.realizedPnl },
            dailyPnlDay = utcEpochDay(now),
            dailyPnlBaseline = 0.0,
            markets = legacy.markets
        )
    }

    private data class LegacyShadowState(
        val markets: List<EventMakerPersistedMarket> = emptyList()
    )

    private fun loadLastTradeTimestamps(): Map<String, Long> {
        val path = options.outputDir.resolve("trades.csv")
        if (!resumed || !Files.exists(path)) return emptyMap()
        val latest = mutableMapOf<String, Long>()
        Files.newBufferedReader(path, StandardCharsets.UTF_8).useLines { lines ->
            lines.drop(1).forEach { line ->
                val fields = line.split(',', limit = 3)
                if (fields.size >= 2) {
                    val timestamp = fields[0].toLongOrNull() ?: return@forEach
                    val conditionId = fields[1]
                    if (conditionId.isNotBlank()) latest[conditionId] = maxOf(latest[conditionId] ?: 0L, timestamp)
                }
            }
        }
        return latest
    }

    private fun writeState(states: List<EventMakerLiveState>) {
        val now = System.currentTimeMillis()
        val today = utcEpochDay(now)
        val realized = states.sumOf { it.realizedPnl }
        if (dailyPnlDay != today) {
            dailyPnlDay = today
            dailyPnlBaseline = realized
        }
        stateStore.save(
            EventMakerPersistedState(
                mode = EventMakerMode.SHADOW.name,
                accountId = null,
                startedAt = runStartedAt,
                cumulativeRealizedPnl = realized,
                dailyPnlDay = dailyPnlDay,
                dailyPnlBaseline = dailyPnlBaseline,
                markets = states.map { it.toPersisted() }
            )
        )
        EventMakerShadowRecoveryStore.save(recoveryPath, selectedMarkets, now)
    }

    private fun EventMakerEngineResult.hasStateChanges(): Boolean =
        placed.isNotEmpty() || canceled.isNotEmpty() ||
            reconciled.any { it.events.isNotEmpty() } ||
            settlements.isNotEmpty() || redemptions.isNotEmpty()

    private fun utcEpochDay(epochMillis: Long): Long =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()

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
        shadowExecution.onTrade(trade).forEach(::scheduleMarkouts)
        return false
    }

    private fun scheduleMarkouts(fill: EventMakerShadowFill) {
        MARKOUT_HORIZONS_SECONDS.forEach { horizonSeconds ->
            pendingMarkouts += PendingMarkout(
                fill = fill,
                horizonSeconds = horizonSeconds,
                dueAt = fill.timestamp + horizonSeconds * 1_000L
            )
        }
    }

    private lateinit var shadowExecution: EventMakerShadowExecutionPort

    private fun recordEngineResult(result: EventMakerEngineResult) {
        // Marketable entries are filled synchronously by the shadow port. Drain
        // their fills here so markouts and execution diagnostics include them.
        shadowExecution.drainImmediateFills().forEach(::scheduleMarkouts)
        quotesPlaced += result.placed.size
        cancels += result.canceled.size
        val flowGuardBlocks = result.errors.filter { it.startsWith("flow_guard_blocked:") }
        val actionableErrors = result.errors.filterNot {
            it.startsWith("risk_blocked:") || it.startsWith("flow_guard_blocked:")
        }
        actionableErrors.forEach { addError(it) }
        result.haltedReason?.let { addError(it) }
        val meaningfulReconciled = result.reconciled.filter { it.events.isNotEmpty() }
        if (result.placed.isNotEmpty() || result.canceled.isNotEmpty() ||
            meaningfulReconciled.isNotEmpty() || actionableErrors.isNotEmpty() || flowGuardBlocks.isNotEmpty()
        ) {
            writeEvent(
                mapOf(
                    "type" to "engine",
                    "timestamp" to System.currentTimeMillis(),
                    "placed" to result.placed,
                    "canceled" to result.canceled,
                    "reconciled" to meaningfulReconciled,
                    "blocked" to flowGuardBlocks,
                    "settlements" to result.settlements,
                    "redemptions" to result.redemptions,
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
        writeExecutionDiagnostics()
        writeState(states)
    }

    private fun writeExecutionDiagnostics() {
        Files.writeString(
            options.outputDir.resolve("execution-diagnostics.json"),
            prettyGson.toJson(shadowExecution.diagnostics()),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
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