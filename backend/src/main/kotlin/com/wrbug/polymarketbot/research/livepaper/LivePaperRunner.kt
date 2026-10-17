package com.wrbug.polymarketbot.research.livepaper

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Locale

data class LivePaperOptions(
    val proxyUrl: String?,
    val outputDir: Path,
    val maxMarkets: Int,
    val minVolume24hr: Double,
    val minMinutes: Int,
    val maxMinutes: Int,
    val targetCycles: Int,
    val minRemainingMinutes: Int = 120,
    val allowNegRisk: Boolean = false,
    val tradePollSeconds: Long = 3,
    val snapshotSeconds: Long = 10
)

data class LivePaperSummary(
    val arm: String,
    val markets: Int,
    val quotesPlaced: Int,
    val buyFills: Int,
    val sellFills: Int,
    val completedCycles: Int,
    val winningCycles: Int,
    val losingCycles: Int,
    val realizedPnl: Double,
    val unrealizedPnl: Double,
    val totalPnl: Double,
    val deployedNotional: Double,
    val roi: Double,
    val averageHoldingSeconds: Double,
    val maxDrawdown: Double,
    val openInventoryShares: Double
)

class LivePaperRunner(
    private val options: LivePaperOptions,
    private val arms: List<PaperArmConfig> = defaultArms()
) {
    private val gson = Gson()
    private val dataClient = LivePaperDataClient(options.proxyUrl)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val channel = Channel<RunnerEvent>(Channel.UNLIMITED)
    private val books = mutableMapOf<String, MarketBook>()
    private val markets = linkedMapOf<String, LivePaperMarket>()
    private val states = mutableListOf<PaperArmState>()
    private val seenTrades = object : LinkedHashMap<String, Boolean>(20_000, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 100_000
    }
    private val drawdowns = mutableMapOf<String, Drawdown>()
    private lateinit var eventsWriter: java.io.BufferedWriter
    private lateinit var snapshotWriter: java.io.BufferedWriter
    private lateinit var tradesWriter: java.io.BufferedWriter
    private lateinit var samplesWriter: java.io.BufferedWriter
    private var wsClient: LivePaperWsClient? = null
    private var startedAt = 0L
    private var tradeCount = 0L
    private var duplicateTradeEvents = 0L
    private var bookCount = 0L
    private var unknownCount = 0L
    private var rawSamples = 0
    private var stoppedReason = ""
    private var lastProgressAt = 0L

    fun run(): List<LivePaperSummary> {
        Files.createDirectories(options.outputDir)
        eventsWriter = Files.newBufferedWriter(options.outputDir.resolve("events.jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        snapshotWriter = Files.newBufferedWriter(options.outputDir.resolve("snapshots.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        tradesWriter = Files.newBufferedWriter(options.outputDir.resolve("trades.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        samplesWriter = Files.newBufferedWriter(options.outputDir.resolve("ws-samples.jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        snapshotWriter.write("timestamp,market_id,question,arm,phase,best_bid,best_ask,entry_price,shares,realized_pnl,unrealized_pnl,total_pnl,deployed_notional,completed_cycles,quote_price,quote_age_seconds\n")
        tradesWriter.write("timestamp,condition_id,token_id,side,price,size,source,transaction_hash\n")

        var discovered = kotlinx.coroutines.runBlocking {
            dataClient.discoverMarkets(
                options.maxMarkets,
                options.minVolume24hr,
                options.allowNegRisk,
                options.minRemainingMinutes
            )
        }
        require(discovered.isNotEmpty()) { "No live eligible markets found" }
        discovered = kotlinx.coroutines.runBlocking {
            discovered.map { market ->
                val tick = dataClient.getTickSize(market.favoriteTokenId)
                if (tick != null) market.copy(tickSize = tick) else market
            }
        }
        discovered.forEach { markets[it.conditionId] = it }
        writeMarketList(discovered)
        println("Live paper start: markets=${discovered.size} arms=${arms.size} minRun=${options.minMinutes}m minRemaining=${options.minRemainingMinutes}m max=${options.maxMinutes}m targetCycles=${options.targetCycles}")
        discovered.forEach { market ->
            println("  ${market.question} | tag=${market.tag} tick=${market.tickSize} vol24h=${market.volume24hr} end=${Instant.ofEpochSecond(market.endTimestamp)}")
            arms.forEach { armsConfig -> states += PaperArmState(market, armsConfig, market.tickSize) }
        }

        val initialBooks = kotlinx.coroutines.runBlocking {
            discovered.flatMap { it.tokenIds }.mapNotNull { tokenId ->
                dataClient.getBook(tokenId)?.also { books[tokenId] = it }
            }
        }
        println("initial books=${initialBooks.size}/${discovered.sumOf { it.tokenIds.size }}")

        startedAt = System.currentTimeMillis()
        lastProgressAt = startedAt
        seedQuotesAndWriteEvents()

        val tokenMap = discovered.flatMap { market -> market.tokenIds.map { it to market.conditionId } }.toMap()
        wsClient = LivePaperWsClient(
            proxyUrl = options.proxyUrl,
            tokenToCondition = tokenMap,
            onEvent = { event -> channel.trySend(RunnerEvent.Feed(event)) },
            onStatus = { status -> println(status); channel.trySend(RunnerEvent.Status(status)) }
        ).also { it.start() }

        val jobs = mutableListOf<Job>()
        jobs += scope.launch {
            while (isActive) {
                delay(options.tradePollSeconds * 1000L)
                for (market in discovered) {
                    dataClient.getRecentTrades(market).forEach { trade ->
                        channel.trySend(RunnerEvent.Feed(LiveFeedEvent.Trade(trade)))
                    }
                }
            }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(1_000)
                channel.trySend(RunnerEvent.Tick(System.currentTimeMillis()))
            }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(options.snapshotSeconds * 1000L)
                channel.trySend(RunnerEvent.SnapshotTick(System.currentTimeMillis()))
            }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(15_000)
                channel.trySend(RunnerEvent.CheckStop(System.currentTimeMillis()))
            }
        }

        kotlinx.coroutines.runBlocking {
            try {
                for (event in channel) {
                    when (event) {
                        is RunnerEvent.Feed -> handleFeed(event.event)
                        is RunnerEvent.Tick -> handleTick(event.now)
                        is RunnerEvent.SnapshotTick -> handleSnapshot(event.now)
                        is RunnerEvent.CheckStop -> if (shouldStop(event.now)) {
                            stoppedReason = stopReason(event.now)
                            break
                        }
                        is RunnerEvent.Status -> Unit
                    }
                }
            } finally {
                jobs.forEach { it.cancel() }
                wsClient?.close()
                dataClient.close()
                scope.cancel()
                eventsWriter.close()
                snapshotWriter.close()
                tradesWriter.close()
                samplesWriter.close()
            }
        }

        val summaries = buildSummaries()
        writeSummaries(summaries)
        writeCycles()
        writeRunReport(summaries)
        println("Live paper finished: reason=$stoppedReason uniqueTrades=$tradeCount duplicateTradeEvents=$duplicateTradeEvents books=$bookCount unknown=$unknownCount")
        return summaries
    }
    private fun handleFeed(event: LiveFeedEvent) {
        when (event) {
            is LiveFeedEvent.BookUpdated -> {
                val book = event.book
                books[book.tokenId] = book
                bookCount++
                sample(event.raw)
                val market = marketForToken(book.tokenId) ?: return
                statesFor(market).forEach { writePaperEvents(it.onBook(book, book.updatedAt)) }
            }
            is LiveFeedEvent.PricesChanged -> {
                event.changes.forEach { change ->
                    val current = books[change.tokenId] ?: MarketBook(change.tokenId, emptyList(), emptyList(), change.timestamp)
                    val updated = current.applyPriceChange(change)
                    books[change.tokenId] = updated
                    bookCount++
                    val market = marketForToken(change.tokenId) ?: return@forEach
                    statesFor(market).forEach { writePaperEvents(it.onBook(updated, change.timestamp)) }
                }
                sample(event.raw)
            }
            is LiveFeedEvent.Trade -> {
                val trade = event.trade
                if (trade.timestamp < startedAt - 60_000L) return
                if (seenTrades.put(trade.key, true) == null) {
                    tradeCount++
                    writeTrade(trade)
                    val market = markets[trade.conditionId] ?: return
                    statesFor(market).forEach { writePaperEvents(it.onTrade(trade, trade.timestamp)) }
                } else {
                    duplicateTradeEvents++
                }
            }
            is LiveFeedEvent.Unknown -> {
                unknownCount++
                samplesWriter.write(gson.toJson(mapOf("kind" to "unknown", "event" to event.eventType, "raw" to event.raw)) + "\n")
                samplesWriter.flush()
            }
        }
    }

    private fun handleTick(now: Long) {
        states.forEach { state ->
            val book = books[state.market.favoriteTokenId] ?: return@forEach
            writePaperEvents(state.onBook(book, now))
        }
        if (now - lastProgressAt >= 60_000) {
            lastProgressAt = now
            val cycles = states.sumOf { it.completedCycles }
            val pnl = states.sumOf { it.snapshot(books[it.market.favoriteTokenId], now).totalPnl }
            println("progress elapsed=${elapsedMinutes(now)}m trades=$tradeCount books=$bookCount cycles=$cycles pnl=${fmt(pnl)} unknown=$unknownCount")
        }
    }

    private fun handleSnapshot(now: Long) {
        val byArm = arms.associate { it.name to mutableListOf<PaperSnapshot>() }
        states.forEach { state ->
            val snapshot = state.snapshot(books[state.market.favoriteTokenId], now)
            byArm[state.config.name]?.add(snapshot)
            writeSnapshot(snapshot)
        }
        byArm.forEach { (arm, snapshots) ->
            drawdowns.getOrPut(arm) { Drawdown() }.update(snapshots.sumOf { it.totalPnl })
        }
        snapshotWriter.flush()
    }

    private fun seedQuotesAndWriteEvents() {
        states.forEach { state ->
            val book = books[state.market.favoriteTokenId] ?: return@forEach
            writePaperEvents(state.onBook(book, startedAt))
        }
    }

    private fun shouldStop(now: Long): Boolean {
        val elapsed = (now - startedAt) / 60_000.0
        if (elapsed >= options.maxMinutes) return true
        val cycles = states.sumOf { it.completedCycles }
        return elapsed >= options.minMinutes && cycles >= options.targetCycles
    }

    private fun stopReason(now: Long): String {
        val elapsed = (now - startedAt) / 60_000.0
        val cycles = states.sumOf { it.completedCycles }
        return if (elapsed >= options.maxMinutes) "max_minutes cycles=$cycles"
        else "target_cycles=$cycles elapsed=${String.format(Locale.US, "%.1f", elapsed)}m"
    }

    private fun elapsedMinutes(now: Long): String =
        String.format(Locale.US, "%.1f", (now - startedAt) / 60_000.0)

    private fun statesFor(market: LivePaperMarket): List<PaperArmState> =
        states.filter { it.market.conditionId == market.conditionId }

    private fun marketForToken(tokenId: String): LivePaperMarket? =
        markets.values.firstOrNull { it.tokenIds.contains(tokenId) }

    private fun writePaperEvents(events: List<PaperEvent>) {
        events.forEach { event ->
            val payload = when (event) {
                is PaperEvent.QuotePlaced -> mapOf(
                    "kind" to "QUOTE", "at" to event.timestamp, "market" to event.marketId, "arm" to event.arm,
                    "side" to event.side.name, "price" to event.price, "shares" to event.shares,
                    "queue_ahead" to event.queueAhead, "required_volume" to event.requiredVolume
                )
                is PaperEvent.QuoteCanceled -> mapOf(
                    "kind" to "CANCEL", "at" to event.timestamp, "market" to event.marketId, "arm" to event.arm,
                    "side" to event.side.name, "price" to event.price, "reason" to event.reason
                )
                is PaperEvent.Fill -> mapOf(
                    "kind" to "FILL", "at" to event.timestamp, "market" to event.marketId, "arm" to event.arm,
                    "side" to event.side.name, "price" to event.price, "shares" to event.shares,
                    "trade_price" to event.tradePrice, "trade_source" to event.tradeSource
                )
            }
            eventsWriter.write(gson.toJson(payload) + "\n")
            println("PAPER ${payload["kind"]} ${payload["arm"]} ${payload["side"]} price=${payload["price"]} shares=${payload["shares"]} market=${payload["market"]}")
        }
        if (events.isNotEmpty()) eventsWriter.flush()
    }

    private fun writeTrade(trade: LiveTradePrint) {
        tradesWriter.write("${trade.timestamp},${csv(trade.conditionId)},${csv(trade.tokenId)},${trade.side},${trade.price},${trade.size},${trade.source},${csv(trade.transactionHash)}\n")
        tradesWriter.flush()
    }

    private fun writeSnapshot(snapshot: PaperSnapshot) {
        snapshotWriter.write(listOf(
            snapshot.timestamp, csv(snapshot.marketId), csv(snapshot.question), csv(snapshot.arm), snapshot.phase,
            snapshot.bestBid ?: "", snapshot.bestAsk ?: "", snapshot.entryPrice ?: "", snapshot.shares,
            snapshot.realizedPnl, snapshot.unrealizedPnl, snapshot.totalPnl, snapshot.deployedNotional,
            snapshot.completedCycles, snapshot.quotePrice ?: "", snapshot.quoteAgeSeconds
        ).joinToString(",") + "\n")
    }

    private fun sample(raw: String?) {
        if (raw == null || rawSamples >= 30) return
        rawSamples++
        samplesWriter.write(gson.toJson(mapOf("kind" to "ws_sample", "at" to System.currentTimeMillis(), "raw" to raw.take(4000))) + "\n")
        samplesWriter.flush()
    }
    private fun writeMarketList(discovered: List<LivePaperMarket>) {
        val header = "condition_id,question,tag,volume_24h,end_timestamp,tick_size,outcomes,token_ids,favorite_index"
        val lines = discovered.map { m ->
            listOf(m.conditionId, csv(m.question), m.tag, m.volume24hr, m.endTimestamp, m.tickSize,
                csv(m.outcomes.joinToString("|")), csv(m.tokenIds.joinToString("|")), m.favoriteIndex)
                .joinToString(",")
        }
        Files.write(options.outputDir.resolve("markets.csv"), listOf(header) + lines, StandardCharsets.UTF_8)
    }

    private fun buildSummaries(): List<LivePaperSummary> {
        return arms.map { config ->
            val armStates = states.filter { it.config.name == config.name }
            val cycles = armStates.flatMap { it.cycles }
            val open = armStates.mapNotNull { it.inventory }
            val realized = armStates.sumOf { it.realizedPnl }
            val unrealized = armStates.sumOf { state ->
                val inv = state.inventory ?: return@sumOf 0.0
                val bid = books[state.market.favoriteTokenId]?.bestBid ?: return@sumOf 0.0
                (bid - inv.entryPrice) * inv.shares
            }
            val deployed = armStates.sumOf { it.deployedNotional }
            val total = realized + unrealized
            LivePaperSummary(
                arm = config.name,
                markets = armStates.map { it.market.conditionId }.distinct().size,
                quotesPlaced = armStates.sumOf { it.quotesPlaced },
                buyFills = armStates.sumOf { it.buyFills },
                sellFills = armStates.sumOf { it.sellFills },
                completedCycles = cycles.size,
                winningCycles = cycles.count { it.pnl > 0.0 },
                losingCycles = cycles.count { it.pnl < 0.0 },
                realizedPnl = realized,
                unrealizedPnl = unrealized,
                totalPnl = total,
                deployedNotional = deployed,
                roi = if (deployed > 0.0) total / deployed else 0.0,
                averageHoldingSeconds = if (cycles.isEmpty()) 0.0 else cycles.sumOf { it.holdingSeconds }.toDouble() / cycles.size,
                maxDrawdown = drawdowns[config.name]?.maxDrawdown ?: 0.0,
                openInventoryShares = open.sumOf { it.shares }
            )
        }.sortedByDescending { it.totalPnl }
    }

    private fun writeSummaries(summaries: List<LivePaperSummary>) {
        val header = "arm,markets,quotes_placed,buy_fills,sell_fills,completed_cycles,winning_cycles,losing_cycles,realized_pnl,unrealized_pnl,total_pnl,deployed_notional,roi,avg_holding_seconds,max_drawdown,open_inventory_shares"
        val lines = summaries.map { s ->
            listOf(s.arm, s.markets, s.quotesPlaced, s.buyFills, s.sellFills, s.completedCycles, s.winningCycles,
                s.losingCycles, s.realizedPnl, s.unrealizedPnl, s.totalPnl, s.deployedNotional, s.roi,
                s.averageHoldingSeconds, s.maxDrawdown, s.openInventoryShares).joinToString(",")
        }
        Files.write(options.outputDir.resolve("summary.csv"), listOf(header) + lines, StandardCharsets.UTF_8)
    }

    private fun writeCycles() {
        val header = "arm,condition_id,question,entry_at,entry_price,exit_at,exit_price,shares,pnl,holding_seconds"
        val lines = arms.flatMap { config ->
            states.filter { it.config.name == config.name }.flatMap { state ->
                state.cycles.map { c ->
                    listOf(config.name, csv(state.market.conditionId), csv(state.market.question), c.entryAt, c.entryPrice,
                        c.exitAt, c.exitPrice, c.shares, c.pnl, c.holdingSeconds).joinToString(",")
                }
            }
        }
        Files.write(options.outputDir.resolve("cycles.csv"), listOf(header) + lines, StandardCharsets.UTF_8)
    }

    private fun writeRunReport(summaries: List<LivePaperSummary>) {
        val lines = mutableListOf<String>()
        lines += "Live paper event-maker report"
        lines += "started_at=${Instant.ofEpochMilli(startedAt)}"
        lines += "finished_at=${Instant.now()}"
        lines += "stop_reason=$stoppedReason"
        lines += "markets=${markets.size} arms=${arms.size} unique_trades=$tradeCount duplicate_trade_events=$duplicateTradeEvents book_updates=$bookCount unknown=$unknownCount"
        lines += ""
        summaries.forEach { s ->
            val line = String.format(Locale.US,
                "%-28s markets=%d quotes=%d buys=%d sells=%d cycles=%d win=%d loss=%d realized=%.4f unrealized=%.4f total=%.4f deployed=%.2f roi=%.4f%% dd=%.4f openShares=%.2f",
                s.arm, s.markets, s.quotesPlaced, s.buyFills, s.sellFills, s.completedCycles, s.winningCycles,
                s.losingCycles, s.realizedPnl, s.unrealizedPnl, s.totalPnl, s.deployedNotional, s.roi * 100.0,
                s.maxDrawdown, s.openInventoryShares)
            lines += line
            println(line)
        }
        lines += ""
        lines += "NOTE: strategy arms are independent alternatives. Do not sum their PnL as one portfolio."
        Files.write(options.outputDir.resolve("report.txt"), lines, StandardCharsets.UTF_8)
        println(lines.last())
    }

    private fun csv(value: Any?): String {
        val text = value?.toString() ?: ""
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else text
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.4f", value)
}

sealed interface RunnerEvent {
    data class Feed(val event: LiveFeedEvent) : RunnerEvent
    data class Tick(val now: Long) : RunnerEvent
    data class SnapshotTick(val now: Long) : RunnerEvent
    data class CheckStop(val now: Long) : RunnerEvent
    data class Status(val message: String) : RunnerEvent
}

class Drawdown {
    private var peak = 0.0
    var maxDrawdown = 0.0
        private set

    fun update(equity: Double) {
        if (equity > peak) peak = equity
        val dd = peak - equity
        if (dd > maxDrawdown) maxDrawdown = dd
    }
}

fun defaultArms(): List<PaperArmConfig> = listOf(
    PaperArmConfig("join-090-099-sp1", 0.90, 0.99, 1.0, improveTicks = 0, queueFactor = 1.0),
    PaperArmConfig("join-095-099-sp2", 0.95, 0.99, 2.0, improveTicks = 0, queueFactor = 1.0),
    PaperArmConfig("join-097-099-sp1", 0.97, 0.99, 1.0, improveTicks = 0, queueFactor = 1.0),
    PaperArmConfig("improve1-095-099-sp1", 0.95, 0.99, 1.0, improveTicks = 1, queueFactor = 1.0),
    PaperArmConfig("improve1-097-099-sp2", 0.97, 0.99, 2.0, improveTicks = 1, queueFactor = 1.0),
    PaperArmConfig("joinask-095-099-sp1", 0.95, 0.99, 1.0, improveTicks = 0, exitJoinAsk = true, queueFactor = 1.0),
    PaperArmConfig("improveask1-095-099-sp1", 0.95, 0.99, 1.0, improveTicks = 1, exitImproveTicks = 1, queueFactor = 1.0),
    PaperArmConfig("improveask1-097-099-sp1", 0.97, 0.99, 1.0, improveTicks = 1, exitImproveTicks = 1, queueFactor = 1.0),
    PaperArmConfig("join-0985-0995-sp05", 0.985, 0.995, 0.5, improveTicks = 0, queueFactor = 1.0)
)
