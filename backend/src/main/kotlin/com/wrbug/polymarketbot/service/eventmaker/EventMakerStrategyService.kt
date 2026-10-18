package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LiveFeedEvent
import com.wrbug.polymarketbot.research.livepaper.LivePaperDataClient
import com.wrbug.polymarketbot.research.livepaper.LivePaperWsClient
import com.wrbug.polymarketbot.research.livepaper.MarketBook
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong

@Service
class EventMakerStrategyService(
    @Value("\${event-maker.proxy-url:}") private val proxyUrl: String = "",
    private val riskPolicy: EventMakerRiskPolicy,
    private val quotePlanner: EventMakerQuotePlanner,
    private val executionPort: EventMakerExecutionPort,
    private val shadowExecutionPort: EventMakerShadowExecutionPort,
    private val stateStore: EventMakerStateStore
) {
    private val logger = LoggerFactory.getLogger(EventMakerStrategyService::class.java)
    private val lock = Any()
    private val generation = AtomicLong(0)
    private val stateMutex = Mutex()

    @Volatile
    private var status = EventMakerStatus(
        running = false,
        mode = EventMakerMode.SHADOW,
        startedAt = null,
        stoppedAt = null,
        markets = emptyList(),
        trades = 0,
        bookUpdates = 0,
        completedCycles = 0,
        realizedPnl = 0.0,
        unrealizedPnl = 0.0,
        openInventoryShares = 0.0,
        totalInventoryNotional = 0.0
    )

    private var runScope: CoroutineScope? = null
    private var runJob: Job? = null
    private var dataClient: LivePaperDataClient? = null
    private var wsClient: LivePaperWsClient? = null
    private var feedChannel: Channel<LiveFeedEvent>? = null
    private var liveAccountId: Long? = null
    private var currentLiveStates: List<EventMakerLiveState> = emptyList()
    private var currentEngine: EventMakerLiveEngine? = null

    fun start(config: EventMakerConfig): Result<EventMakerStatus> = synchronized(lock) {
        if (status.running) return Result.failure(IllegalStateException("event_maker_already_running"))
        if (config.mode == EventMakerMode.LIVE) {
            if (!config.liveTradingEnabled) return Result.failure(IllegalStateException("live_trading_disabled"))
            if (config.accountId == null) return Result.failure(IllegalStateException("live_account_required"))
            val validation = runBlocking { executionPort.validateAccount(config.accountId) }
            if (validation.isFailure) {
                return Result.failure(validation.exceptionOrNull() ?: IllegalStateException("live_account_validation_failed"))
            }
        }
        val runId = generation.incrementAndGet()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = Channel<LiveFeedEvent>(Channel.UNLIMITED)
        val client = LivePaperDataClient(proxyUrl.takeIf { it.isNotBlank() })
        runScope = scope
        dataClient = client
        feedChannel = channel
        liveAccountId = config.accountId
        currentLiveStates = emptyList()
        currentEngine = null
        status = EventMakerStatus(
            running = true,
            mode = config.mode,
            startedAt = System.currentTimeMillis(),
            stoppedAt = null,
            markets = emptyList(),
            trades = 0,
            bookUpdates = 0,
            completedCycles = 0,
            realizedPnl = 0.0,
            unrealizedPnl = 0.0,
            openInventoryShares = 0.0,
            totalInventoryNotional = 0.0,
            lastError = null
        )
        runJob = scope.launch {
            try {
                when (config.mode) {
                    EventMakerMode.SHADOW -> runShadow(config, client, channel, runId)
                    EventMakerMode.LIVE -> runLive(config, client, channel, runId)
                }
            } catch (e: Exception) {
                if (runId == generation.get()) {
                    logger.error("Event maker run failed: mode=${config.mode}", e)
                    status = status.copy(
                        running = false,
                        stoppedAt = System.currentTimeMillis(),
                        lastError = e.message ?: e.javaClass.simpleName
                    )
                }
            }
        }
        Result.success(status)
    }

    fun stop(): EventMakerStatus = synchronized(lock) {
        generation.incrementAndGet()
        val job = runJob
        runBlocking { job?.cancelAndJoin() }
        runJob = null

        val engine = currentEngine
        val states = currentLiveStates
        val accountId = liveAccountId
        val stoppedMode = status.mode
        val stoppedStartedAt = status.startedAt
        if (engine != null && accountId != null && states.isNotEmpty()) {
            val cancelResult = runBlocking { engine.cancelAll(states, accountId) }
            if (cancelResult.errors.isNotEmpty()) {
                status = status.copy(lastError = cancelResult.errors.joinToString(";"))
            }
        }
        if (stoppedMode == EventMakerMode.LIVE && accountId != null && states.isNotEmpty()) {
            runCatching { persistLive(stoppedStartedAt, accountId, states) }
                .onFailure { logger.warn("Failed to persist stopped event-maker state", it) }
        }

        currentEngine = null
        currentLiveStates = emptyList()
        liveAccountId = null
        wsClient?.close()
        wsClient = null
        dataClient?.close()
        dataClient = null
        feedChannel?.close()
        feedChannel = null
        runScope?.cancel()
        runScope = null
        status = status.copy(running = false, stoppedAt = System.currentTimeMillis())
        status
    }

    @PreDestroy
    fun destroy() {
        stop()
    }

    fun currentStatus(): EventMakerStatus = status

    private suspend fun runShadow(
        config: EventMakerConfig,
        client: LivePaperDataClient,
        channel: Channel<LiveFeedEvent>,
        runId: Long
    ) {
        val prepared = prepareMarkets(config, client)
        val discovered = prepared.markets
        val books = prepared.books
        val states = discovered.map { EventMakerLiveState(it, config) }
        shadowExecutionPort.reset()
        val engine = EventMakerLiveEngine(config, quotePlanner, riskPolicy, shadowExecutionPort)
        currentEngine = engine
        val seenTrades = linkedMapOf<String, Boolean>()
        var trades = 0L
        var bookUpdates = 0L
        val ws = LivePaperWsClient(
            proxyUrl = proxyUrl.takeIf { it.isNotBlank() },
            tokenToCondition = discovered.flatMap { m -> m.tokenIds.map { it to m.conditionId } }.toMap(),
            onEvent = { channel.trySend(it) },
            onStatus = { logger.info("Event maker shadow WS: $it") }
        )
        wsClient = ws
        ws.start()
        val pollJob = runScope?.launch {
            while (isActive) {
                delay(config.pollSeconds * 1000L)
                discovered.forEach { market -> client.getRecentTrades(market).forEach { channel.trySend(LiveFeedEvent.Trade(it)) } }
            }
        }
        status = status.copy(markets = discovered.map { it.question })
        try {
            for (event in channel) {
                if (runId != generation.get()) break
                var shouldStop = false
                stateMutex.withLock {
                    when (event) {
                        is LiveFeedEvent.BookUpdated -> {
                            books[event.book.tokenId] = event.book
                            bookUpdates++
                            discovered.firstOrNull { it.favoriteTokenId == event.book.tokenId }?.let { market ->
                                states.filter { it.market.conditionId == market.conditionId }.forEach { it.onBook(event.book) }
                            }
                        }
                        is LiveFeedEvent.PricesChanged -> event.changes.forEach { change ->
                            val current = books[change.tokenId] ?: MarketBook(change.tokenId, emptyList(), emptyList(), change.timestamp)
                            val updated = current.applyPriceChange(change)
                            books[change.tokenId] = updated
                            bookUpdates++
                            discovered.firstOrNull { it.tokenIds.contains(change.tokenId) }?.let { market ->
                                states.filter { it.market.conditionId == market.conditionId }.forEach { it.onBook(updated) }
                            }
                        }
                        is LiveFeedEvent.Trade -> {
                            val trade = event.trade
                            if (seenTrades.put(trade.key, true) == null) {
                                trades++
                                shadowExecutionPort.onTrade(trade)
                            }
                        }
                        is LiveFeedEvent.Unknown -> logger.debug("Event maker shadow unknown WS event: ${event.eventType}")
                    }
                    val result = runEngineTick(engine, states, books, 0L)
                    shouldStop = applyEngineResult(result) || engine.haltedReason != null
                }
                updateStatus(states, books, trades, bookUpdates)
                if (shouldStop) break
                if (riskPolicy.shouldHalt(config, status.realizedPnl + status.unrealizedPnl)) {
                    status = status.copy(lastError = "daily_loss_limit")
                    break
                }
            }
        } finally {
            pollJob?.cancel()
            ws.close()
            client.close()
            if (runId == generation.get()) status = status.copy(running = false, stoppedAt = System.currentTimeMillis())
        }
    }

    private suspend fun runLive(
        config: EventMakerConfig,
        client: LivePaperDataClient,
        channel: Channel<LiveFeedEvent>,
        runId: Long
    ) {
        val accountId = config.accountId ?: throw IllegalStateException("live_account_required")
        val prepared = prepareMarkets(config, client)
        val discovered = prepared.markets
        val books = prepared.books
        val states = discovered.map { EventMakerLiveState(it, config) }
        val persisted = stateStore.load()?.takeIf { it.mode == "LIVE" && it.accountId == accountId }
        cancelOrphanedOrders(persisted, discovered.map { it.conditionId }.toSet(), accountId)
        persisted?.markets?.forEach { saved ->
            states.firstOrNull { it.market.conditionId == saved.conditionId }?.restore(saved)
        }
        currentLiveStates = states
        val engine = EventMakerLiveEngine(config, quotePlanner, riskPolicy, executionPort)
        engine.registerRestoredOrders(states, accountId)
        currentEngine = engine

        val seenTrades = linkedMapOf<String, Boolean>()
        var trades = 0L
        var bookUpdates = 0L
        val ws = LivePaperWsClient(
            proxyUrl = proxyUrl.takeIf { it.isNotBlank() },
            tokenToCondition = discovered.flatMap { m -> m.tokenIds.map { it to m.conditionId } }.toMap(),
            onEvent = { channel.trySend(it) },
            onStatus = { logger.info("Event maker live WS: $it") }
        )
        wsClient = ws
        ws.start()
        val pollJob = runScope?.launch {
            while (isActive) {
                delay(config.pollSeconds * 1000L)
                discovered.forEach { market -> client.getRecentTrades(market).forEach { channel.trySend(LiveFeedEvent.Trade(it)) } }
            }
        }
        val reconcileJob = runScope?.launch {
            while (isActive) {
                delay(config.reconcileSeconds * 1000L)
                stateMutex.withLock {
                    val result = runEngineTick(engine, states, books, accountId)
                    applyEngineResult(result)
                    updateStatus(states, books, trades, bookUpdates)
                    persistLive(status.startedAt, accountId, states)
                }
            }
        }
        status = status.copy(markets = discovered.map { it.question })

        stateMutex.withLock {
            val initial = runEngineTick(engine, states, books, accountId)
            applyEngineResult(initial)
            updateStatus(states, books, trades, bookUpdates)
            persistLive(status.startedAt, accountId, states)
        }
        try {
            for (event in channel) {
                if (runId != generation.get()) break
                var shouldStop = false
                stateMutex.withLock {
                    when (event) {
                        is LiveFeedEvent.BookUpdated -> {
                            books[event.book.tokenId] = event.book
                            bookUpdates++
                            discovered.firstOrNull { it.favoriteTokenId == event.book.tokenId }?.let { market ->
                                states.filter { it.market.conditionId == market.conditionId }.forEach { it.onBook(event.book) }
                            }
                        }
                        is LiveFeedEvent.PricesChanged -> event.changes.forEach { change ->
                            val current = books[change.tokenId] ?: MarketBook(change.tokenId, emptyList(), emptyList(), change.timestamp)
                            val updated = current.applyPriceChange(change)
                            books[change.tokenId] = updated
                            bookUpdates++
                            discovered.firstOrNull { it.tokenIds.contains(change.tokenId) }?.let { market ->
                                states.filter { it.market.conditionId == market.conditionId }.forEach { it.onBook(updated) }
                            }
                        }
                        is LiveFeedEvent.Trade -> {
                            val trade = event.trade
                            if (seenTrades.put(trade.key, true) == null) trades++
                        }
                        is LiveFeedEvent.Unknown -> logger.debug("Event maker live unknown WS event: ${event.eventType}")
                    }
                    val result = runEngineTick(engine, states, books, accountId)
                    shouldStop = applyEngineResult(result) || engine.haltedReason != null
                }
                updateStatus(states, books, trades, bookUpdates)
                persistLive(status.startedAt, accountId, states)
                if (shouldStop) break
                if (riskPolicy.shouldHalt(config, status.realizedPnl + status.unrealizedPnl)) {
                    status = status.copy(lastError = "daily_loss_limit")
                    break
                }
            }
        } finally {
            pollJob?.cancel()
            reconcileJob?.cancel()
            stateMutex.withLock {
                val cleanup = engine.cancelAll(states, accountId)
                applyEngineResult(cleanup)
                updateStatus(states, books, trades, bookUpdates)
                persistLive(status.startedAt, accountId, states)
            }
            ws.close()
            client.close()
            if (runId == generation.get()) status = status.copy(running = false, stoppedAt = System.currentTimeMillis())
        }
    }

    private suspend fun prepareMarkets(
        config: EventMakerConfig,
        client: LivePaperDataClient
    ): PreparedMarkets {
        val candidates = client.discoverMarkets(
            maxMarkets = (config.maxMarkets * 3).coerceAtLeast(config.maxMarkets),
            minVolume24hr = config.minVolume24hr,
            allowNegRisk = config.allowNegRisk,
            minRemainingMinutes = config.minRemainingMinutes
        )
        if (candidates.isEmpty()) throw IllegalStateException("no_eligible_markets")
        val books = mutableMapOf<String, MarketBook>()
        candidates.flatMap { it.tokenIds }.forEach { tokenId ->
            client.getBook(tokenId)?.let { books[tokenId] = it }
        }
        val selected = EventMakerMarketSelector.select(
            markets = candidates,
            books = books,
            maxMarkets = config.maxMarkets,
            minSpreadTicks = config.minSpreadTicks,
            minPrice = config.minPrice,
            maxPrice = config.maxPrice
        )
        if (selected.isEmpty()) throw IllegalStateException("no_eligible_markets_with_min_spread")
        return PreparedMarkets(selected, books)
    }

    private data class PreparedMarkets(
        val markets: List<com.wrbug.polymarketbot.research.livepaper.LivePaperMarket>,
        val books: MutableMap<String, MarketBook>
    )

    private suspend fun cancelOrphanedOrders(
        persisted: EventMakerPersistedState?,
        discoveredConditionIds: Set<String>,
        accountId: Long
    ) {
        persisted?.markets
            ?.filter { it.conditionId !in discoveredConditionIds && !it.activeOrderId.isNullOrBlank() }
            ?.forEach { saved ->
                val orderId = saved.activeOrderId ?: return@forEach
                executionPort.cancel(accountId, orderId).getOrElse {
                    throw IllegalStateException("orphan_order_cancel_failed:$orderId:${it.message}", it)
                }
            }
    }

    private suspend fun runEngineTick(
        engine: EventMakerLiveEngine,
        states: List<EventMakerLiveState>,
        books: Map<String, MarketBook>,
        accountId: Long
    ): EventMakerEngineResult {
        val now = System.currentTimeMillis()
        val reconciled = engine.reconcile(states, accountId, now)
        if (engine.haltedReason != null) return reconciled
        return reconciled.merge(engine.quote(states, books, accountId, now))
    }

    private fun applyEngineResult(result: EventMakerEngineResult): Boolean {
        result.placed.forEach {
            logger.info(
                "Event maker quote: market=${it.marketId}, side=${it.order.side}, price=${it.order.price}, shares=${it.order.originalShares}"
            )
        }
        result.canceled.forEach {
            logger.info("Event maker cancel: market=${it.marketId}, order=${it.orderId}, reason=${it.reason}")
        }
        result.reconciled.filter { it.events.isNotEmpty() }.forEach {
            logger.info("Event maker reconcile: market=${it.marketId}, order=${it.orderId}, events=${it.events}, status=${it.status}")
        }
        val actionableErrors = result.errors.filterNot { it.startsWith("risk_blocked:") }
        if (actionableErrors.isNotEmpty()) {
            status = status.copy(lastError = actionableErrors.last())
        }
        return result.haltedReason != null
    }

    private fun updateStatus(
        states: List<EventMakerLiveState>,
        books: Map<String, MarketBook>,
        trades: Long,
        bookUpdates: Long
    ) {
        val realized = states.sumOf { it.realizedPnl }
        val unrealized = states.sumOf { it.markToMarket(books[it.market.favoriteTokenId]) }
        status = status.copy(
            running = true,
            trades = trades,
            bookUpdates = bookUpdates,
            completedCycles = states.sumOf { it.completedCycles },
            realizedPnl = realized,
            unrealizedPnl = unrealized,
            openInventoryShares = states.sumOf { it.inventoryShares.toDouble() },
            totalInventoryNotional = states.sumOf { it.inventoryNotional() },
            recentFills = states.flatMap { it.fills }.sortedBy { it.timestamp }.takeLast(100),
            recentCycles = states.flatMap { it.cycles }.sortedBy { it.exitAt }.takeLast(100)
        )
    }

    private fun persistLive(startedAt: Long?, accountId: Long, states: List<EventMakerLiveState>) {
        runCatching {
            stateStore.save(
                EventMakerPersistedState(
                    mode = EventMakerMode.LIVE.name,
                    accountId = accountId,
                    startedAt = startedAt,
                    markets = states.map { it.toPersisted() }
                )
            )
        }.onFailure { logger.warn("Failed to persist event-maker state", it) }
    }
}