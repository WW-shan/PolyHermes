package com.wrbug.polymarketbot.research.eventmaker

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.wrbug.polymarketbot.research.livepaper.LiveFeedEvent
import com.wrbug.polymarketbot.research.livepaper.LivePaperDataClient
import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.LivePaperWsClient
import com.wrbug.polymarketbot.research.livepaper.LiveTradePrint
import com.wrbug.polymarketbot.research.livepaper.MarketBook
import com.wrbug.polymarketbot.service.eventmaker.LiquidityRewardBook
import com.wrbug.polymarketbot.service.eventmaker.LiquidityRewardCalculator
import com.wrbug.polymarketbot.service.eventmaker.LiquidityRewardConfig
import com.wrbug.polymarketbot.service.eventmaker.LiquidityRewardLevel
import com.wrbug.polymarketbot.service.eventmaker.PairedRewardQuotePlan
import com.wrbug.polymarketbot.service.eventmaker.PairedRewardQuotePlanner
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.system.exitProcess

private data class PairedRewardShadowOptions(
    val proxyUrl: String?,
    val outputDir: Path,
    val minutes: Int,
    val maxMarkets: Int,
    val discoveryMarkets: Int,
    val discoveryPages: Int,
    val minVolume24hr: Double,
    val minPrice: Double,
    val maxPrice: Double,
    val minRemainingMinutes: Int,
    val maxRemainingMinutes: Int?,
    val shares: Double,
    val improveTicks: Int,
    val maxPairCost: Double,
    val maxHedgePairCost: Double,
    val requoteSeconds: Long,
    val hedgeGraceMs: Long,
    val hedgeSlippageCents: Double,
    val takerFeeRate: Double,
    val fillVolumeFactor: Double,
    val snapshotSeconds: Long,
    val pollSeconds: Long
)

private data class CandidateRecord(
    val conditionId: String,
    val question: String,
    val rewardDailyRate: Double,
    val rewardsMinSize: Double,
    val rewardsMaxSpread: Double,
    val midpoint: Double?,
    val plan: PairedRewardQuotePlan?,
    val selected: Boolean,
    val reason: String
)

private data class SimFill(
    val timestamp: Long,
    val marketId: String,
    val tokenId: String,
    val kind: String,
    val price: Double,
    val shares: Double,
    val pairPnl: Double? = null,
    val fee: Double = 0.0
)

private data class PairedRewardShadowSummary(
    val mode: String,
    val startedAt: Long,
    val stoppedAt: Long,
    val durationSeconds: Long,
    val rewardCandidates: Int,
    val selectedMarkets: Int,
    val quoteCycles: Int,
    val requotes: Int,
    val makerFills: Int,
    val hedgeFills: Int,
    val naturalPairs: Int,
    val hedgedPairs: Int,
    val unresolvedSingleLegMarkets: Int,
    val estimatedRewardUsdc: Double,
    val realizedPairPnlUsdc: Double,
    val estimatedNetUsdc: Double,
    val unresolvedOpenRiskUsdc: Double,
    val marketsWithEstimatedRewardAtLeast1: Int,
    val lastErrors: List<String>
)

private class SimQuote(
    val marketId: String,
    val tokenId: String,
    val price: Double,
    val shares: Double,
    val queueAhead: Double,
    val createdAt: Long,
    var queueAheadRemaining: Double = queueAhead,
    var eligibleCrossingShares: Double = 0.0,
    var filledShares: Double = 0.0,
    var status: String = "LIVE",
    var canceledAt: Long? = null
) {
    val remainingShares: Double get() = (shares - filledShares).coerceAtLeast(0.0)
}

private class RewardMarketState(
    val market: LivePaperMarket,
    val yesBook: MarketBook,
    val noBook: MarketBook,
    var pairCost: Double
) {
    val yesTokenId: String = market.tokenIds[0]
    val noTokenId: String = market.tokenIds[1]
    var yesQuote: SimQuote? = null
    var noQuote: SimQuote? = null
    var yesMakerFill: SimFill? = null
    var noMakerFill: SimFill? = null
    var hedgeFill: SimFill? = null
    var phase: String = "WAITING"
    var hedgeDueAt: Long? = null
    var lastQuoteAt: Long = 0L
    var lastAccrualAt: Long = System.currentTimeMillis()
    var estimatedReward: Double = 0.0
    var realizedPairPnl: Double = 0.0
    var quoteCycles: Int = 0
    var requotes: Int = 0
    var lastShare: Double = 0.0
    var lastDailyRewardEstimate: Double = 0.0
    var lastOurQMin: Double = 0.0
    var lastCompetitorQMin: Double = 0.0
    var openRiskUsdc: Double = 0.0
}

private class PairedRewardShadowRunner(
    private val options: PairedRewardShadowOptions
) {
    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()
    private val dataClient = LivePaperDataClient(options.proxyUrl)
    private val channel = Channel<LiveFeedEvent>(Channel.UNLIMITED)
    private val books = java.util.concurrent.ConcurrentHashMap<String, MarketBook>()
    private val states = linkedMapOf<String, RewardMarketState>()
    private val seenTrades = object : LinkedHashMap<String, Boolean>(20_000, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 100_000
    }
    private val errors = ArrayDeque<String>()
    private lateinit var eventsWriter: java.io.BufferedWriter
    private lateinit var tradesWriter: java.io.BufferedWriter
    private lateinit var quotesWriter: java.io.BufferedWriter
    private lateinit var fillsWriter: java.io.BufferedWriter
    private lateinit var rewardsWriter: java.io.BufferedWriter
    private lateinit var snapshotsWriter: java.io.BufferedWriter
    private var wsClient: LivePaperWsClient? = null
    private var startedAt = 0L
    private var lastSnapshotAt = 0L
    private var quoteCycles = 0
    private var requotes = 0
    private var makerFills = 0
    private var hedgeFills = 0
    private var naturalPairs = 0
    private var hedgedPairs = 0
    private var candidateCount = 0

    fun run(): PairedRewardShadowSummary = runBlocking {
        Files.createDirectories(options.outputDir)
        startedAt = System.currentTimeMillis()
        lastSnapshotAt = startedAt - options.snapshotSeconds * 1_000L
        openWriters()
        try {
            val discovery = dataClient.discoverMarketsDetailed(
                maxMarkets = options.discoveryMarkets,
                minVolume24hr = options.minVolume24hr,
                allowNegRisk = true,
                minRemainingMinutes = options.minRemainingMinutes,
                maxRemainingMinutes = options.maxRemainingMinutes,
                minPrice = options.minPrice,
                maxPrice = options.maxPrice,
                maxPagesPerTag = options.discoveryPages
            )
            println("paired reward discovery unique=${discovery.stats.uniqueMarkets} returned=${discovery.markets.size}")
            val candidates = selectCandidates(discovery.markets)
            candidateCount = candidates.count { it.rewardDailyRate > 0.0 }
            Files.writeString(
                options.outputDir.resolve("candidates.json"),
                prettyGson.toJson(candidates),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            val selected = candidates.filter { it.selected }
            if (selected.isEmpty()) {
                addError("no_reward_markets_selected")
                return@runBlocking writeSummary(states.values.toList(), System.currentTimeMillis())
            }
            val selectedMarkets = selected.mapNotNull { candidate -> discovery.markets.firstOrNull { it.conditionId == candidate.conditionId } }
            Files.writeString(
                options.outputDir.resolve("markets.json"),
                prettyGson.toJson(selectedMarkets),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            println("selected=${selectedMarkets.size} rewardPoolPerDay=${selected.sumOf { it.rewardDailyRate }}")
            selectedMarkets.forEach { market ->
                val yesBook = dataClient.getBook(market.tokenIds[0]) ?: return@forEach
                val noBook = dataClient.getBook(market.tokenIds[1]) ?: return@forEach
                books[market.tokenIds[0]] = yesBook
                books[market.tokenIds[1]] = noBook
                val plan = PairedRewardQuotePlanner.plan(
                    market = market,
                    yesBook = yesBook,
                    noBook = noBook,
                    improveTicks = options.improveTicks,
                    requestedShares = options.shares,
                    maxPairCostPerShare = options.maxPairCost,
                    maxHedgePairCostPerShare = options.maxHedgePairCost,
                    hedgeSlippageCents = options.hedgeSlippageCents
                )
                if (!plan.eligible) {
                    addError("selected_market_replan_failed:${market.conditionId}:${plan.reason}")
                    return@forEach
                }
                val state = RewardMarketState(market, yesBook, noBook, plan.pairCostPerShare)
                states[market.conditionId] = state
                placeQuotes(state, System.currentTimeMillis())
            }
            if (states.isEmpty()) {
                addError("no_selected_market_could_be_quoted")
                return@runBlocking writeSummary(emptyList(), System.currentTimeMillis())
            }

            wsClient = LivePaperWsClient(
                proxyUrl = options.proxyUrl,
                tokenToCondition = states.values.flatMap { state ->
                    listOf(state.yesTokenId to state.market.conditionId, state.noTokenId to state.market.conditionId)
                }.toMap(),
                onEvent = { channel.trySend(it) },
                onStatus = { status ->
                    println(status)
                    writeEvent(mapOf("type" to "ws_status", "status" to status, "timestamp" to System.currentTimeMillis()))
                }
            ).also { it.start() }

            val deadline = startedAt + options.minutes * 60_000L
            var lastTickAt = 0L
            while (System.currentTimeMillis() < deadline) {
                val event = withTimeoutOrNull(options.pollSeconds * 1_000L) { channel.receive() }
                if (event != null) handleFeed(event)
                val now = System.currentTimeMillis()
                if (now - lastTickAt >= 1_000L) {
                    tick(now)
                    lastTickAt = now
                }
            }
            shutdown(System.currentTimeMillis())
        } finally {
            closeWriters()
            dataClient.close()
            channel.close()
        }
    }

    private suspend fun selectCandidates(markets: List<LivePaperMarket>): List<CandidateRecord> {
        val records = mutableListOf<CandidateRecord>()
        val rewardMarkets = markets
            .filter { it.rewardDailyRate > 0.0 && it.rewardsMinSize > 0.0 && it.rewardsMaxSpread > 0.0 }
            .sortedByDescending { it.rewardDailyRate }
            .take(maxOf(options.maxMarkets * 10, 50))
        for (market in rewardMarkets) {
            val yesBook = dataClient.getBook(market.tokenIds[0])
            val noBook = dataClient.getBook(market.tokenIds[1])
            if (yesBook == null || noBook == null) {
                records += CandidateRecord(market.conditionId, market.question, market.rewardDailyRate, market.rewardsMinSize, market.rewardsMaxSpread, null, null, false, "missing_book")
                continue
            }
            val midpoint = midpoint(yesBook, noBook)
            if (midpoint == null || midpoint < options.minPrice || midpoint > options.maxPrice) {
                records += CandidateRecord(market.conditionId, market.question, market.rewardDailyRate, market.rewardsMinSize, market.rewardsMaxSpread, midpoint, null, false, "outside_price_range")
                continue
            }
            val plan = PairedRewardQuotePlanner.plan(
                market = market,
                yesBook = yesBook,
                noBook = noBook,
                improveTicks = options.improveTicks,
                requestedShares = options.shares,
                maxPairCostPerShare = options.maxPairCost,
                maxHedgePairCostPerShare = options.maxHedgePairCost,
                hedgeSlippageCents = options.hedgeSlippageCents
            )
            records += CandidateRecord(market.conditionId, market.question, market.rewardDailyRate, market.rewardsMinSize, market.rewardsMaxSpread, midpoint, plan, plan.eligible, plan.reason)
        }
        val selectedIds = records.asSequence()
            .filter { it.selected }
            .sortedWith(compareByDescending<CandidateRecord> { it.rewardDailyRate }.thenBy { it.plan?.pairCostPerShare ?: 2.0 })
            .take(options.maxMarkets)
            .map { it.conditionId }
            .toSet()
        return records.map { if (it.selected && it.conditionId !in selectedIds) it.copy(selected = false, reason = "not_in_top_selection") else it }
    }
    private fun openWriters() {
        eventsWriter = Files.newBufferedWriter(options.outputDir.resolve("events.jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        tradesWriter = Files.newBufferedWriter(options.outputDir.resolve("trades.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        quotesWriter = Files.newBufferedWriter(options.outputDir.resolve("quotes.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        fillsWriter = Files.newBufferedWriter(options.outputDir.resolve("fills.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        rewardsWriter = Files.newBufferedWriter(options.outputDir.resolve("rewards.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        snapshotsWriter = Files.newBufferedWriter(options.outputDir.resolve("snapshots.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        tradesWriter.write("timestamp,condition_id,token_id,side,price,size,transaction_hash\n")
        quotesWriter.write("timestamp,market_id,token_id,price,shares,queue_ahead,reason\n")
        fillsWriter.write("timestamp,market_id,token_id,kind,price,shares,pair_pnl,fee\n")
        rewardsWriter.write("timestamp,market_id,estimated_share,estimated_daily_reward,cumulative_reward,our_qmin,competitor_qmin\n")
        snapshotsWriter.write("timestamp,market_id,phase,yes_price,no_price,yes_filled,no_filled,estimated_reward,pair_pnl,open_risk\n")
    }

    private fun handleFeed(event: LiveFeedEvent) {
        when (event) {
            is LiveFeedEvent.BookUpdated -> books[event.book.tokenId] = event.book
            is LiveFeedEvent.PricesChanged -> event.changes.forEach { change ->
                books.computeIfPresent(change.tokenId) { _, book -> book.applyPriceChange(change) }
            }
            is LiveFeedEvent.Trade -> handleTrade(event.trade)
            is LiveFeedEvent.Unknown -> writeEvent(mapOf("type" to "feed_unknown", "event" to event.eventType))
        }
    }

    private fun handleTrade(trade: LiveTradePrint) {
        if (seenTrades.put(trade.key, true) != null) return
        tradesWriter.write(listOf(trade.timestamp, csv(trade.conditionId), csv(trade.tokenId), csv(trade.side), trade.price, trade.size, csv(trade.transactionHash)).joinToString(","))
        tradesWriter.newLine()
        tradesWriter.flush()
        states.values.filter { it.market.conditionId == trade.conditionId }.forEach { state ->
            if (state.phase != "QUOTING" && state.phase != "SINGLE_LEG") return@forEach
            val now = System.currentTimeMillis()
            tryFill(state.yesQuote, trade)?.let { fill ->
                state.yesMakerFill = fill
                makerFills++
                writeFill(fill)
            }
            tryFill(state.noQuote, trade)?.let { fill ->
                state.noMakerFill = fill
                makerFills++
                writeFill(fill)
            }
            updatePairState(state, now)
        }
    }

    private fun tryFill(quote: SimQuote?, trade: LiveTradePrint): SimFill? {
        if (quote == null || quote.status != "LIVE" || quote.tokenId != trade.tokenId) return null
        if (trade.timestamp < quote.createdAt - 1_000L || !trade.side.equals("SELL", true)) return null
        if (trade.price > quote.price + 1e-9) return null
        var available = trade.size
        if (quote.queueAheadRemaining > 0.0) {
            val consumed = min(quote.queueAheadRemaining, available)
            quote.queueAheadRemaining -= consumed
            available -= consumed
        }
        if (available <= 0.0) return null
        quote.eligibleCrossingShares += available
        val required = quote.shares * options.fillVolumeFactor
        if (quote.eligibleCrossingShares + 1e-9 < required) return null
        val quantity = quote.remainingShares
        if (quantity <= 0.0) return null
        quote.filledShares += quantity
        if (quote.remainingShares <= 1e-9) quote.status = "FILLED"
        return SimFill(trade.timestamp, quote.marketId, quote.tokenId, "MAKER", quote.price, quantity)
    }

    private fun updatePairState(state: RewardMarketState, now: Long) {
        if (state.yesMakerFill != null && state.noMakerFill != null) {
            if (state.phase != "PAIRED") {
                state.phase = "PAIRED"
                state.hedgeDueAt = null
                state.realizedPairPnl = makerFillPairPnl(state)
                naturalPairs++
                writeEvent(mapOf("type" to "natural_pair", "marketId" to state.market.conditionId, "timestamp" to now, "pairPnl" to state.realizedPairPnl))
            }
            return
        }
        if (state.yesMakerFill != null || state.noMakerFill != null) {
            if (state.phase != "SINGLE_LEG") {
                state.phase = "SINGLE_LEG"
                state.hedgeDueAt = now + options.hedgeGraceMs
            }
        }
    }

    private fun tick(now: Long) {
        states.values.forEach { state ->
            when (state.phase) {
                "QUOTING" -> {
                    accrueReward(state, now)
                    if (state.yesQuote?.status != "LIVE" || state.noQuote?.status != "LIVE" || now - state.lastQuoteAt >= options.requoteSeconds * 1_000L) {
                        placeQuotes(state, now, requote = state.quoteCycles > 0)
                    }
                }
                "SINGLE_LEG" -> if ((state.hedgeDueAt ?: Long.MAX_VALUE) <= now) executeHedge(state, now)
                "WAITING" -> if (now - state.lastQuoteAt >= options.requoteSeconds * 1_000L) placeQuotes(state, now, requote = state.quoteCycles > 0)
            }
        }
        if (now - lastSnapshotAt >= options.snapshotSeconds * 1_000L) {
            writeSnapshots(now)
            lastSnapshotAt = now
        }
    }

    private fun placeQuotes(state: RewardMarketState, now: Long, requote: Boolean = false) {
        val yesBook = books[state.yesTokenId] ?: state.yesBook
        val noBook = books[state.noTokenId] ?: state.noBook
        if (now - yesBook.updatedAt > 30_000L || now - noBook.updatedAt > 30_000L) {
            cancelQuotes(state, now, "stale_book")
            state.phase = "WAITING"
            state.lastQuoteAt = now
            state.lastAccrualAt = now
            return
        }
        cancelQuotes(state, now, if (requote) "requote" else "replan")
        val plan = PairedRewardQuotePlanner.plan(
            market = state.market,
            yesBook = yesBook,
            noBook = noBook,
            improveTicks = options.improveTicks,
            requestedShares = options.shares,
            maxPairCostPerShare = options.maxPairCost,
            maxHedgePairCostPerShare = options.maxHedgePairCost,
            hedgeSlippageCents = options.hedgeSlippageCents
        )
        if (!plan.eligible) {
            state.phase = "WAITING"
            state.lastQuoteAt = now
            state.lastAccrualAt = now
            writeEvent(mapOf("type" to "quote_skipped", "marketId" to state.market.conditionId, "reason" to plan.reason, "timestamp" to now))
            return
        }
        val yesQueue = if (abs(plan.yesPrice.toDouble() - (yesBook.bestBid ?: 0.0)) <= 1e-9) yesBook.bidSizeAt(yesBook.bestBid ?: 0.0) else 0.0
        val noQueue = if (abs(plan.noPrice.toDouble() - (noBook.bestBid ?: 0.0)) <= 1e-9) noBook.bidSizeAt(noBook.bestBid ?: 0.0) else 0.0
        state.yesQuote = SimQuote(state.market.conditionId, state.yesTokenId, plan.yesPrice.toDouble(), plan.shares.toDouble(), yesQueue, now)
        state.noQuote = SimQuote(state.market.conditionId, state.noTokenId, plan.noPrice.toDouble(), plan.shares.toDouble(), noQueue, now)
        state.pairCost = plan.pairCostPerShare
        state.phase = "QUOTING"
        state.lastQuoteAt = now
        state.lastAccrualAt = now
        state.quoteCycles++
        quoteCycles++
        if (requote) requotes++
        writeQuote(state.yesQuote!!, if (requote) "requote" else "initial")
        writeQuote(state.noQuote!!, if (requote) "requote" else "initial")
        writeEvent(
            mapOf(
                "type" to if (requote) "requote" else "quotes_placed",
                "marketId" to state.market.conditionId,
                "timestamp" to now,
                "yesPrice" to plan.yesPrice.toPlainString(),
                "noPrice" to plan.noPrice.toPlainString(),
                "shares" to plan.shares.toPlainString(),
                "pairCost" to plan.pairCostPerShare,
                "rewardDailyRate" to state.market.rewardDailyRate
            )
        )
    }

    private fun cancelQuotes(state: RewardMarketState, now: Long, reason: String) {
        listOfNotNull(state.yesQuote, state.noQuote).forEach { quote ->
            if (quote.status == "LIVE") {
                quote.status = "CANCELLED"
                quote.canceledAt = now
                writeEvent(mapOf("type" to "quote_canceled", "marketId" to state.market.conditionId, "tokenId" to quote.tokenId, "reason" to reason))
            }
        }
    }

    private fun accrueReward(state: RewardMarketState, now: Long) {
        val yesQuote = state.yesQuote
        val noQuote = state.noQuote
        if (yesQuote == null || noQuote == null || yesQuote.status != "LIVE" || noQuote.status != "LIVE") {
            state.lastAccrualAt = now
            return
        }
        val yesBook = books[state.yesTokenId] ?: return
        val noBook = books[state.noTokenId] ?: return
        if (now - yesBook.updatedAt > 30_000L || now - noBook.updatedAt > 30_000L) {
            state.lastAccrualAt = now
            return
        }
        val config = LiquidityRewardConfig(minSizeShares = state.market.rewardsMinSize, maxSpreadCents = state.market.rewardsMaxSpread)
        val yesBid = yesBook.bestBid ?: return
        val yesAsk = yesBook.bestAsk ?: return
        val noBid = noBook.bestBid ?: return
        val noAsk = noBook.bestAsk ?: return
        val yesMid = (yesBid + yesAsk) / 2.0
        val noMid = (noBid + noAsk) / 2.0
        val ours = LiquidityRewardCalculator.calculate(
            yesBook = LiquidityRewardBook(listOf(LiquidityRewardLevel(yesQuote.price, yesQuote.remainingShares)), emptyList(), yesMid),
            noBook = LiquidityRewardBook(listOf(LiquidityRewardLevel(noQuote.price, noQuote.remainingShares)), emptyList(), noMid),
            config = config
        )
        val competitor = LiquidityRewardCalculator.calculate(rewardBook(yesBook, yesMid), rewardBook(noBook, noMid), config)
        val share = LiquidityRewardCalculator.estimateShare(ours.qMin, competitor.qMin)
        val daily = LiquidityRewardCalculator.estimateDailyReward(state.market.rewardDailyRate, share)
        val dtSeconds = ((now - state.lastAccrualAt) / 1_000.0).coerceIn(0.0, 5.0)
        if (dtSeconds > 0.0) state.estimatedReward += daily * dtSeconds / 86_400.0
        state.lastShare = share
        state.lastDailyRewardEstimate = daily
        state.lastOurQMin = ours.qMin
        state.lastCompetitorQMin = competitor.qMin
        state.lastAccrualAt = now
    }

    private fun rewardBook(book: MarketBook, midpoint: Double): LiquidityRewardBook = LiquidityRewardBook(
        bids = book.bids.map { LiquidityRewardLevel(it.price, it.size) },
        asks = book.asks.map { LiquidityRewardLevel(it.price, it.size) },
        midpoint = midpoint
    )
    private fun executeHedge(state: RewardMarketState, now: Long) {
        if (state.yesMakerFill != null && state.noMakerFill != null) {
            updatePairState(state, now)
            return
        }
        val yesFilled = state.yesMakerFill != null
        val noFilled = state.noMakerFill != null
        if (!yesFilled && !noFilled) return
        val missingToken = if (yesFilled) state.noTokenId else state.yesTokenId
        val bestAsk = books[missingToken]?.bestAsk
        if (bestAsk == null) {
            addError("hedge_book_missing:${state.market.conditionId}")
            return
        }
        val tick = state.market.tickSize.takeIf { it > 0.0 } ?: 0.001
        val hedgePrice = roundToTick(bestAsk + options.hedgeSlippageCents / 100.0, tick).coerceAtMost(0.999)
        val makerFill = state.yesMakerFill ?: state.noMakerFill!!
        val shares = makerFill.shares
        val fee = shares * options.takerFeeRate * hedgePrice * (1.0 - hedgePrice)
        val pairPnl = shares * (1.0 - makerFill.price - hedgePrice) - fee
        val hedge = SimFill(now, state.market.conditionId, missingToken, "HEDGE", hedgePrice, shares, pairPnl, fee)
        state.hedgeFill = hedge
        state.realizedPairPnl = pairPnl
        state.phase = "HEDGED"
        state.openRiskUsdc = 0.0
        hedgeFills++
        hedgedPairs++
        cancelQuotes(state, now, "hedged")
        writeFill(hedge)
        writeEvent(
            mapOf(
                "type" to "hedge",
                "marketId" to state.market.conditionId,
                "timestamp" to now,
                "missingToken" to missingToken,
                "hedgePrice" to hedgePrice,
                "makerPrice" to makerFill.price,
                "shares" to shares,
                "fee" to fee,
                "pairPnl" to pairPnl
            )
        )
    }

    private fun makerFillPairPnl(state: RewardMarketState): Double {
        val yes = state.yesMakerFill ?: return 0.0
        val no = state.noMakerFill ?: return 0.0
        return yes.shares * (1.0 - yes.price - no.price)
    }

    private fun writeQuote(quote: SimQuote, reason: String) {
        quotesWriter.write(listOf(quote.createdAt, csv(quote.marketId), csv(quote.tokenId), quote.price, quote.shares, quote.queueAhead, csv(reason)).joinToString(","))
        quotesWriter.newLine()
        quotesWriter.flush()
    }

    private fun writeFill(fill: SimFill) {
        fillsWriter.write(listOf(fill.timestamp, csv(fill.marketId), csv(fill.tokenId), csv(fill.kind), fill.price, fill.shares, fill.pairPnl ?: "", fill.fee).joinToString(","))
        fillsWriter.newLine()
        fillsWriter.flush()
    }

    private fun writeSnapshots(now: Long) {
        states.values.forEach { state ->
            snapshotsWriter.write(
                listOf(
                    now,
                    csv(state.market.conditionId),
                    csv(state.phase),
                    state.yesQuote?.price ?: "",
                    state.noQuote?.price ?: "",
                    state.yesMakerFill?.shares ?: 0.0,
                    state.noMakerFill?.shares ?: 0.0,
                    state.estimatedReward,
                    state.realizedPairPnl,
                    state.openRiskUsdc
                ).joinToString(",")
            )
            snapshotsWriter.newLine()
            rewardsWriter.write(
                listOf(
                    now,
                    csv(state.market.conditionId),
                    state.lastShare,
                    state.lastDailyRewardEstimate,
                    state.estimatedReward,
                    state.lastOurQMin,
                    state.lastCompetitorQMin
                ).joinToString(",")
            )
            rewardsWriter.newLine()
        }
        snapshotsWriter.flush()
        rewardsWriter.flush()
    }

    private fun shutdown(now: Long): PairedRewardShadowSummary {
        states.values.filter { it.phase == "SINGLE_LEG" }.forEach { state ->
            executeHedge(state, now)
            if (state.phase == "SINGLE_LEG") {
                val fill = state.yesMakerFill ?: state.noMakerFill
                state.phase = "OPEN_RISK"
                state.openRiskUsdc = (fill?.shares ?: 0.0) * (fill?.price ?: 0.0)
                addError("unresolved_single_leg:${state.market.conditionId}")
            }
        }
        states.values.forEach { cancelQuotes(it, now, "shutdown") }
        writeSnapshots(now)
        writeEvent(mapOf("type" to "shutdown", "timestamp" to now))
        return writeSummary(states.values.toList(), now)
    }

    private fun writeSummary(snapshot: List<RewardMarketState>, stoppedAt: Long): PairedRewardShadowSummary {
        val unresolved = snapshot.count { it.phase == "OPEN_RISK" || it.phase == "SINGLE_LEG" }
        val estimatedReward = snapshot.sumOf { it.estimatedReward }
        val pairPnl = snapshot.sumOf { it.realizedPairPnl }
        val openRisk = snapshot.sumOf { it.openRiskUsdc }
        val summary = PairedRewardShadowSummary(
            mode = "paired-reward-shadow",
            startedAt = startedAt,
            stoppedAt = stoppedAt,
            durationSeconds = ((stoppedAt - startedAt) / 1_000L).coerceAtLeast(0L),
            rewardCandidates = candidateCount,
            selectedMarkets = states.size,
            quoteCycles = quoteCycles,
            requotes = requotes,
            makerFills = makerFills,
            hedgeFills = hedgeFills,
            naturalPairs = naturalPairs,
            hedgedPairs = hedgedPairs,
            unresolvedSingleLegMarkets = unresolved,
            estimatedRewardUsdc = estimatedReward,
            realizedPairPnlUsdc = pairPnl,
            estimatedNetUsdc = estimatedReward + pairPnl,
            unresolvedOpenRiskUsdc = openRisk,
            marketsWithEstimatedRewardAtLeast1 = snapshot.count { it.estimatedReward >= 1.0 },
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
        val report = buildString {
            appendLine("paired reward shadow")
            appendLine("durationSeconds=${summary.durationSeconds} selected=${summary.selectedMarkets} quoteCycles=${summary.quoteCycles} requotes=${summary.requotes}")
            appendLine("makerFills=${summary.makerFills} hedgeFills=${summary.hedgeFills} naturalPairs=${summary.naturalPairs} hedgedPairs=${summary.hedgedPairs}")
            appendLine(String.format(Locale.US, "estimatedReward=%.6f pairPnl=%.6f net=%.6f openRisk=%.6f", summary.estimatedRewardUsdc, summary.realizedPairPnlUsdc, summary.estimatedNetUsdc, summary.unresolvedOpenRiskUsdc))
            appendLine("errors=${summary.lastErrors}")
        }
        Files.writeString(
            options.outputDir.resolve("report.txt"),
            report,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        println(report)
        return summary
    }

    private fun closeWriters() {
        runCatching { eventsWriter.close() }
        runCatching { tradesWriter.close() }
        runCatching { quotesWriter.close() }
        runCatching { fillsWriter.close() }
        runCatching { rewardsWriter.close() }
        runCatching { snapshotsWriter.close() }
    }

    private fun addError(message: String) {
        errors.addLast(message)
        while (errors.size > 100) errors.removeFirst()
    }

    private fun writeEvent(value: Any) {
        eventsWriter.write(gson.toJson(value))
        eventsWriter.newLine()
        eventsWriter.flush()
    }

    private fun midpoint(yesBook: MarketBook, noBook: MarketBook): Double? {
        val yesBid = yesBook.bestBid ?: return null
        val yesAsk = yesBook.bestAsk ?: return null
        val noBid = noBook.bestBid ?: return null
        val noAsk = noBook.bestAsk ?: return null
        return ((yesBid + yesAsk) / 2.0 + (1.0 - (noBid + noAsk) / 2.0)) / 2.0
    }

    private fun roundToTick(value: Double, tick: Double): Double = kotlin.math.round(value / tick) * tick

    private fun csv(value: Any?): String {
        val text = value?.toString() ?: ""
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }
}

fun main(args: Array<String>) {
    val options = parsePairedRewardOptions(args)
    println("paired reward shadow")
    println("output=${options.outputDir.toAbsolutePath()} minutes=${options.minutes} maxMarkets=${options.maxMarkets}")
    PairedRewardShadowRunner(options).run()
    exitProcess(0)
}

private fun parsePairedRewardOptions(args: Array<String>): PairedRewardShadowOptions {
    fun option(name: String): String? {
        val index = args.indexOf("--$name")
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }
    fun <T> value(name: String, default: T, parse: (String) -> T?): T = option(name)?.let(parse) ?: default
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val options = PairedRewardShadowOptions(
        proxyUrl = option("proxy"),
        outputDir = Paths.get(option("output") ?: "build/research/paired-reward-shadow-$stamp"),
        minutes = value("minutes", 60, String::toIntOrNull),
        maxMarkets = value("max-markets", 7, String::toIntOrNull),
        discoveryMarkets = value("discovery-markets", 1_000, String::toIntOrNull),
        discoveryPages = value("discovery-pages", 10, String::toIntOrNull),
        minVolume24hr = value("min-volume", 1_000.0, String::toDoubleOrNull),
        minPrice = value("min-price", 0.90, String::toDoubleOrNull),
        maxPrice = value("max-price", 0.955, String::toDoubleOrNull),
        minRemainingMinutes = value("min-remaining-minutes", 1_440, String::toIntOrNull),
        maxRemainingMinutes = value("max-remaining-minutes", 43_200, String::toIntOrNull),
        shares = value("shares", 0.0, String::toDoubleOrNull),
        improveTicks = value("improve-ticks", 0, String::toIntOrNull),
        maxPairCost = value("max-pair-cost", 1.0, String::toDoubleOrNull),
        maxHedgePairCost = value("max-hedge-pair-cost", 1.0, String::toDoubleOrNull),
        requoteSeconds = value("requote-seconds", 120L, String::toLongOrNull),
        hedgeGraceMs = value("hedge-grace-ms", 5_000L, String::toLongOrNull),
        hedgeSlippageCents = value("hedge-slippage-cents", 0.2, String::toDoubleOrNull),
        takerFeeRate = value("taker-fee-rate", 0.04, String::toDoubleOrNull),
        fillVolumeFactor = value("fill-volume-factor", 1.0, String::toDoubleOrNull),
        snapshotSeconds = value("snapshot-seconds", 10L, String::toLongOrNull),
        pollSeconds = value("poll-seconds", 1L, String::toLongOrNull)
    )
    require(options.minutes > 0 && options.maxMarkets > 0 && options.discoveryMarkets > 0 && options.discoveryPages > 0)
    require(options.minPrice >= 0.0 && options.minPrice < options.maxPrice && options.maxPrice <= 1.0)
    require(options.shares >= 0.0 && options.improveTicks >= 0)
    require(options.maxPairCost > 0.0 && options.maxHedgePairCost > 0.0 && options.requoteSeconds > 0 && options.hedgeGraceMs >= 0)
    require(options.hedgeSlippageCents >= 0.0 && options.takerFeeRate >= 0.0 && options.fillVolumeFactor > 0.0)
    require(options.snapshotSeconds > 0 && options.pollSeconds > 0)
    return options
}