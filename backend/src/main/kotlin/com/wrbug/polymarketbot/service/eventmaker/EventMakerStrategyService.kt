package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LiveFeedEvent
import com.wrbug.polymarketbot.research.livepaper.LivePaperDataClient
import com.wrbug.polymarketbot.research.livepaper.LivePaperWsClient
import com.wrbug.polymarketbot.research.livepaper.MarketBook
import com.wrbug.polymarketbot.util.ProxyConfigProvider
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    /**
     * Test seam: the run job owns its data client, so tests inject a factory to
     * point the client at a local fixture server.
     */
    internal var dataClientFactory: (String?) -> LivePaperDataClient = { LivePaperDataClient(it) }

    private val logger = LoggerFactory.getLogger(EventMakerStrategyService::class.java)
    private val lock = Any()
    private val generation = AtomicLong(0)
    private val stateMutex = Mutex()

    @Volatile
    private var stopping = false

    @Volatile
    private var realizedPnlBaseline = 0.0

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
            val accountId = config.accountId ?: return Result.failure(IllegalStateException("live_account_required"))
            val persisted = try {
                stateStore.load()
            } catch (e: Exception) {
                return Result.failure(IllegalStateException("persisted_state_rejected:${e.message}", e))
            }
            try {
                validatePersistedOwner(persisted, accountId)
            } catch (e: Exception) {
                return Result.failure(e)
            }
            val validation = runBlocking { executionPort.validateAccount(accountId) }
            if (validation.isFailure) {
                return Result.failure(validation.exceptionOrNull() ?: IllegalStateException("live_account_validation_failed"))
            }
            try {
                runBlocking {
                    ensureNoUnmanagedOpenOrders(accountId, persisted)
                    ensureNoUnmanagedPositions(accountId, persisted)
                }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        stopping = false
        realizedPnlBaseline = 0.0
        dailyBaselineDay = currentDayKey()
        val runId = generation.incrementAndGet()
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val channel = Channel<LiveFeedEvent>(Channel.UNLIMITED)
        val runProxy = effectiveProxyUrl()
        val client = dataClientFactory(runProxy)
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
                    EventMakerMode.SHADOW -> runShadow(config, client, channel, runId, runProxy)
                    EventMakerMode.LIVE -> runLive(config, client, channel, runId, runProxy)
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
        stopping = true
        generation.incrementAndGet()
        // Abort in-flight blocking HTTP calls first. Otherwise a discovery or
        // trade poll that is stuck in a 60-second OkHttp call keeps the run job
        // alive and delays the cancel-all that one-click stop promises.
        wsClient?.close()
        dataClient?.close()
        // Cancel every child (feed loop, trade poll, live reconcile tick) so a
        // blocked network call is interrupted, then wait for the run job to
        // finish its NonCancellable cancel-all/persist cleanup.
        runScope?.cancel()
        val job = runJob
        runBlocking { job?.cancelAndJoin() }
        runJob = null
        // A run job cancelled between preparing markets and entering its own
        // try/finally can still have installed a WS client; close it now.
        wsClient?.close()
        wsClient = null
        dataClient?.close()
        dataClient = null
        runBlocking { stateMutex.withLock { } }

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
                .onFailure {
                    logger.warn("Failed to persist stopped event-maker state", it)
                    status = status.copy(lastError = "stop_state_persist_failed:${it.message}")
                }
        }

        currentEngine = null
        currentLiveStates = emptyList()
        liveAccountId = null
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

    /**
     * Explicit `event-maker.proxy-url` wins; otherwise fall back to the system
     * proxy saved in the database so the REST path behaves like the CLI path.
     */
    private fun effectiveProxyUrl(): String? {
        proxyUrl.takeIf { it.isNotBlank() }?.let { return it }
        val config = ProxyConfigProvider.getProxyConfig() ?: return null
        if (!config.enabled || config.type != "HTTP") return null
        val host = config.host?.takeIf { it.isNotBlank() } ?: return null
        val port = config.port ?: return null
        return "http://$host:$port"
    }

    private suspend fun runShadow(
        config: EventMakerConfig,
        client: LivePaperDataClient,
        channel: Channel<LiveFeedEvent>,
        runId: Long,
        proxy: String?
    ) {
        val prepared = prepareMarkets(config, client)
        val discovered = prepared.markets
        val books = prepared.books
        val states = discovered.map { EventMakerLiveState(it, config) }
        shadowExecutionPort.reset()
        shadowExecutionPort.configureFillModel(config.shadowFillVolumeFactor)
        val engine = EventMakerLiveEngine(config, quotePlanner, riskPolicy, shadowExecutionPort)
        currentEngine = engine
        val seenTrades = linkedMapOf<String, Boolean>()
        var trades = 0L
        var bookUpdates = 0L
        val ws = LivePaperWsClient(
            proxyUrl = proxy,
            tokenToCondition = discovered.flatMap { m -> m.tokenIds.map { it to m.conditionId } }.toMap(),
            onEvent = { channel.trySend(it) },
            onStatus = { logger.info("Event maker shadow WS: $it") }
        )
        wsClient = ws
        ws.start()
        val pollJob = runScope?.launch {
            val lastSeen = mutableMapOf<String, Long>()
            while (isActive) {
                delay(config.pollSeconds * 1000L)
                discovered.forEach { market ->
                    // Only ask for prints newer than the last one we forwarded; the
                    // Data API otherwise re-serves the same 200 rows every poll.
                    val since = lastSeen[market.conditionId]
                    val fresh = client.getRecentTrades(market, since)
                    if (fresh.isNotEmpty()) {
                        lastSeen[market.conditionId] = fresh.maxOf { it.timestamp }
                        fresh.forEach { channel.trySend(LiveFeedEvent.Trade(it)) }
                    }
                }
            }
        }
        status = status.copy(markets = discovered.map { it.question })
        try {
            for (event in channel) {
                if (runId != generation.get()) break
                var shouldStop = false
                var duplicateTrade = false
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
                            } else {
                                // Duplicate print: nothing changed, so skip the engine
                                // tick instead of re-quoting on stale input.
                                duplicateTrade = true
                            }
                        }
                        is LiveFeedEvent.Unknown -> logger.debug("Event maker shadow unknown WS event: ${event.eventType}")
                    }
                    if (!duplicateTrade) {
                        val result = runEngineTick(engine, states, books, 0L)
                        shouldStop = applyEngineResult(result) || engine.haltedReason != null
                    }
                }
                updateStatus(states, books, trades, bookUpdates)
                if (shouldStop) break
                rollDailyPnlBaseline(engine, states)
                if (riskPolicy.shouldHalt(config, dailyPnlSinceBaseline(engine, states))) {
                    status = status.copy(lastError = "daily_loss_limit")
                    break
                }
            }
        } finally {
            pollJob?.cancel()
            withContext(NonCancellable) {
                ws.close()
                client.close()
                if (runId == generation.get()) status = status.copy(running = false, stoppedAt = System.currentTimeMillis())
            }
        }
    }

    private suspend fun runLive(
        config: EventMakerConfig,
        client: LivePaperDataClient,
        channel: Channel<LiveFeedEvent>,
        runId: Long,
        proxy: String?
    ) {
        val accountId = config.accountId ?: throw IllegalStateException("live_account_required")
        val persisted = stateStore.load()
        validatePersistedOwner(persisted, accountId)
        ensureNoUnmanagedOpenOrders(accountId, persisted)
        ensureNoUnmanagedPositions(accountId, persisted)
        val prepared = try {
            prepareMarkets(config, client)
        } catch (e: Exception) {
            val cleanupError = runCatching { cancelOrphanedOrders(persisted, emptySet(), accountId) }.exceptionOrNull()
            if (cleanupError != null) {
                throw IllegalStateException(
                    "market_prepare_failed_and_recovery_failed:${e.message}:${cleanupError.message}",
                    e
                )
            }
            throw e
        }
        val discovered = prepared.markets
        val books = prepared.books
        val states = discovered.map { EventMakerLiveState(it, config) }
        cancelOrphanedOrders(persisted, discovered.map { it.conditionId }.toSet(), accountId)
        persisted?.markets?.forEach { saved ->
            states.firstOrNull { it.market.conditionId == saved.conditionId }?.restore(saved)
        }
        realizedPnlBaseline = restoredRealizedBaseline(persisted, states.sumOf { it.realizedPnl })
        currentLiveStates = states
        val engine = EventMakerLiveEngine(
            config,
            quotePlanner,
            riskPolicy,
            executionPort,
            initialRealizedPnl = realizedPnlBaseline
        )
        engine.registerRestoredOrders(states, accountId)
        currentEngine = engine

        val seenTrades = linkedMapOf<String, Boolean>()
        var trades = 0L
        var bookUpdates = 0L
        val ws = LivePaperWsClient(
            proxyUrl = proxy,
            tokenToCondition = discovered.flatMap { m -> m.tokenIds.map { it to m.conditionId } }.toMap(),
            onEvent = { channel.trySend(it) },
            onStatus = { logger.info("Event maker live WS: $it") }
        )
        wsClient = ws
        ws.start()
        val pollJob = runScope?.launch {
            val lastSeen = mutableMapOf<String, Long>()
            while (isActive) {
                delay(config.pollSeconds * 1000L)
                discovered.forEach { market ->
                    val since = lastSeen[market.conditionId]
                    val fresh = client.getRecentTrades(market, since)
                    if (fresh.isNotEmpty()) {
                        lastSeen[market.conditionId] = fresh.maxOf { it.timestamp }
                        fresh.forEach { channel.trySend(LiveFeedEvent.Trade(it)) }
                    }
                }
            }
        }
        var lastPersistAt = System.currentTimeMillis()
        val reconcileJob = runScope?.launch {
            while (isActive) {
                delay(config.reconcileSeconds * 1000L)
                stateMutex.withLock {
                    val result = runEngineTick(engine, states, books, accountId)
                    applyEngineResult(result)
                    updateStatus(states, books, trades, bookUpdates)
                    persistLive(status.startedAt, accountId, states)
                    lastPersistAt = System.currentTimeMillis()
                }
            }
        }
        status = status.copy(markets = discovered.map { it.question })
        ensureNoUnmanagedOpenOrders(accountId, persisted)
        ensureNoUnmanagedPositions(accountId, persisted)

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
                var stateChanged = false
                var duplicateTrade = false
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
                            } else {
                                duplicateTrade = true
                            }
                        }
                        is LiveFeedEvent.Unknown -> logger.debug("Event maker live unknown WS event: ${event.eventType}")
                    }
                    // Book/trade events only drive quoting. Order reconciliation is a
                    // REST call per active order, so it stays on the dedicated
                    // reconcileJob cadence instead of firing on every WS event.
                    if (!duplicateTrade) {
                        val result = runQuoteTick(engine, states, books, accountId)
                        shouldStop = applyEngineResult(result) || engine.haltedReason != null
                        stateChanged = result.placed.isNotEmpty() ||
                            result.canceled.isNotEmpty() ||
                            result.reconciled.isNotEmpty()
                    }
                }
                updateStatus(states, books, trades, bookUpdates)
                val now = System.currentTimeMillis()
                if (stateChanged || now - lastPersistAt >= LIVE_PERSIST_INTERVAL_MS) {
                    persistLive(status.startedAt, accountId, states)
                    lastPersistAt = now
                }
                if (shouldStop) break
                rollDailyPnlBaseline(engine, states)
                if (riskPolicy.shouldHalt(config, dailyPnlSinceBaseline(engine, states))) {
                    status = status.copy(lastError = "daily_loss_limit")
                    break
                }
            }
        } finally {
            pollJob?.cancel()
            reconcileJob?.cancel()
            // Cleanup must survive cancellation: a cancelled run still has to
            // cancel every live order and persist the reconciled state.
            withContext(NonCancellable) {
                try {
                    stateMutex.withLock {
                        val cleanup = engine.cancelAll(states, accountId)
                        applyEngineResult(cleanup)
                        updateStatus(states, books, trades, bookUpdates)
                        persistLive(status.startedAt, accountId, states)
                    }
                } catch (e: Exception) {
                    logger.error("Failed to cancel and persist live event-maker state during shutdown", e)
                    status = status.copy(lastError = "shutdown_state_persist_failed:${e.message}")
                } finally {
                    ws.close()
                    client.close()
                    if (runId == generation.get()) status = status.copy(running = false, stoppedAt = System.currentTimeMillis())
                }
            }
        }
    }

    /**
     * Discovers quotable markets, retrying until one appears or the wait budget is
     * spent. The CLI path has always waited (a strict filter can legitimately see no
     * candidate for hours); without this the REST path died on the first empty scan.
     */
    private suspend fun prepareMarkets(
        config: EventMakerConfig,
        client: LivePaperDataClient
    ): PreparedMarkets {
        val deadline = System.currentTimeMillis() + config.waitForMarketMinutes * 60_000L
        var attempt = 0
        while (true) {
            attempt++
            val prepared = runCatching { discoverAndSelect(config, client) }.getOrNull()
            if (prepared != null) {
                if (attempt > 1) logger.info("Event maker market discovery succeeded on attempt $attempt")
                return prepared
            }
            if (config.waitForMarketMinutes <= 0 || System.currentTimeMillis() >= deadline) {
                throw IllegalStateException("no_eligible_markets")
            }
            logger.info(
                "Event maker waiting for an eligible market (attempt=$attempt, " +
                    "remainingMs=${(deadline - System.currentTimeMillis()).coerceAtLeast(0L)})"
            )
            delay(config.discoveryRetrySeconds * 1000L)
        }
    }

    private suspend fun discoverAndSelect(
        config: EventMakerConfig,
        client: LivePaperDataClient
    ): PreparedMarkets? {
        val candidates = client.discoverMarkets(
            maxMarkets = (config.maxMarkets * 3).coerceAtLeast(config.maxMarkets),
            minVolume24hr = config.minVolume24hr,
            allowNegRisk = config.allowNegRisk,
            minRemainingMinutes = config.minRemainingMinutes,
            maxRemainingMinutes = config.maxRemainingMinutes,
            minRecentTrades30m = config.minRecentTrades30m,
            minRecentNotional30m = config.minRecentNotional30m,
            minRecentTradesPerSide30m = config.minRecentTradesPerSide30m,
            minRecentTrades2h = config.minRecentTrades2h,
            minRecentNotional2h = config.minRecentNotional2h,
            minRecentTradesPerSide2h = config.minRecentTradesPerSide2h,
            minPrice = config.minPrice,
            maxPrice = config.maxPrice
        )
        if (candidates.isEmpty()) return null
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
            maxPrice = config.maxPrice,
            maxQueueAheadShares = config.maxQueueAheadShares,
            improveTicks = config.improveTicks,
            entryOffsetCents = config.entryOffsetCents,
            exitImproveTicks = config.exitImproveTicks,
            exitSpreadCents = config.exitSpreadCents
        )
        if (selected.isEmpty()) return null
        return PreparedMarkets(selected, books)
    }

    private data class PreparedMarkets(
        val markets: List<com.wrbug.polymarketbot.research.livepaper.LivePaperMarket>,
        val books: MutableMap<String, MarketBook>
    )

    internal suspend fun ensureNoUnmanagedOpenOrders(
        accountId: Long,
        persisted: EventMakerPersistedState?
    ) {
        val activeOrderIds = executionPort.listOpenOrderIds(accountId).getOrElse {
            throw IllegalStateException("open_orders_unavailable:${it.message}", it)
        }
        val managedOrderIds = persisted?.markets
            ?.mapNotNull { it.activeOrderId?.takeIf(String::isNotBlank) }
            ?.toSet()
            .orEmpty()
        val unmanaged = activeOrderIds - managedOrderIds
        if (unmanaged.isNotEmpty()) {
            throw IllegalStateException("unmanaged_open_orders:${unmanaged.sorted().joinToString(",")}")
        }
    }

    internal suspend fun ensureNoUnmanagedPositions(
        accountId: Long,
        persisted: EventMakerPersistedState?
    ) {
        val activePositions = executionPort.listOpenPositions(accountId).getOrElse {
            throw IllegalStateException("positions_unavailable:${it.message}", it)
        }.mapKeys { it.key.lowercase() }
        val managedPositions = persisted?.markets
            ?.mapNotNull { market ->
                val inventory = market.inventoryShares.toBigDecimalOrNull()
                    ?: throw IllegalStateException("persisted_inventory_invalid:${market.conditionId}")
                if (inventory > BigDecimal.ZERO) market.conditionId.lowercase() to inventory else null
            }
            ?.toMap()
            .orEmpty()
        val unmanaged = activePositions.keys - managedPositions.keys
        val missing = managedPositions.keys - activePositions.keys
        val sizeMismatch = (activePositions.keys intersect managedPositions.keys)
            .filter { activePositions.getValue(it).compareTo(managedPositions.getValue(it)) != 0 }
            .sorted()
            .joinToString(",") { conditionId ->
                "$conditionId:${activePositions.getValue(conditionId)}!=${managedPositions.getValue(conditionId)}"
            }
        if (unmanaged.isNotEmpty() || missing.isNotEmpty() || sizeMismatch.isNotEmpty()) {
            throw IllegalStateException(
                "open_position_state_mismatch:unmanaged=${unmanaged.sorted().joinToString(",")}:" +
                    "missing=${missing.sorted().joinToString(",")}:size_mismatch=$sizeMismatch"
            )
        }
    }

    internal fun restoredRealizedBaseline(
        persisted: EventMakerPersistedState?,
        restoredRealizedPnl: Double
    ): Double {
        if (persisted == null) return 0.0
        val persistedTotal = persisted.cumulativeRealizedPnl.takeIf { it.isFinite() } ?: 0.0
        val inferredMarketTotal = persisted.markets.sumOf { it.realizedPnl }
        val cumulative = if (kotlin.math.abs(persistedTotal) <= 1e-9 && kotlin.math.abs(inferredMarketTotal) > 1e-9) {
            inferredMarketTotal
        } else {
            persistedTotal
        }
        return cumulative - restoredRealizedPnl
    }

    private fun validatePersistedOwner(persisted: EventMakerPersistedState?, accountId: Long) {
        if (persisted == null) return
        if (persisted.mode != EventMakerMode.LIVE.name) {
            throw IllegalStateException("persisted_state_mode_mismatch:${persisted.mode}")
        }
        if (persisted.accountId != accountId) {
            throw IllegalStateException("persisted_state_account_mismatch:${persisted.accountId}!=$accountId")
        }
    }

    internal suspend fun cancelOrphanedOrders(
        persisted: EventMakerPersistedState?,
        discoveredConditionIds: Set<String>,
        accountId: Long
    ) {
        val orphaned = persisted?.markets
            ?.filter { it.conditionId !in discoveredConditionIds }
            .orEmpty()
        for (saved in orphaned) {
            val inventory = saved.inventoryShares.toBigDecimalOrNull()
                ?.takeIf { it >= BigDecimal.ZERO }
                ?: throw IllegalStateException("orphan_inventory_invalid:${saved.conditionId}")
            val orderId = saved.activeOrderId?.takeIf { it.isNotBlank() }
            if (orderId != null) {
                executionPort.cancel(accountId, orderId).getOrElse {
                    throw IllegalStateException("orphan_order_cancel_failed:$orderId:${it.message}", it)
                }
                confirmCanceledWithoutFill(accountId, saved, orderId)
            }
            if (inventory > BigDecimal.ZERO) {
                throw IllegalStateException("orphan_inventory_requires_reconciliation:${saved.conditionId}:$inventory")
            }
        }
    }

    private suspend fun confirmCanceledWithoutFill(
        accountId: Long,
        saved: EventMakerPersistedMarket,
        orderId: String
    ) {
        val persistedMatched = saved.activeOrderMatchedShares?.toBigDecimalOrNull()
            ?.takeIf { it >= BigDecimal.ZERO }
            ?: throw IllegalStateException("orphan_order_matched_invalid:$orderId")
        var lastFailure: String? = null
        repeat(ORPHAN_CONFIRM_ATTEMPTS) { attempt ->
            val result = executionPort.getOrder(accountId, orderId)
            if (result.isSuccess) {
                val snapshot = result.getOrThrow()
                if (snapshot.matchedShares > BigDecimal.ZERO || persistedMatched > BigDecimal.ZERO) {
                    throw IllegalStateException("orphan_order_has_fill:$orderId:${snapshot.matchedShares}")
                }
                if (snapshot.status.uppercase() in CANCELLED_ORDER_STATUSES) return
                lastFailure = "status=${snapshot.status}"
            } else {
                lastFailure = result.exceptionOrNull()?.message ?: "unknown"
            }
            if (attempt < ORPHAN_CONFIRM_ATTEMPTS - 1) delay(ORPHAN_CONFIRM_DELAY_MS)
        }
        throw IllegalStateException("orphan_order_state_unknown:$orderId:$lastFailure")
    }

    /**
     * Quote-only tick for the high-frequency WS event loop. It deliberately does
     * not reconcile orders: that issues one REST lookup per active order and must
     * stay on the [EventMakerConfig.reconcileSeconds] cadence.
     */
    private suspend fun runQuoteTick(
        engine: EventMakerLiveEngine,
        states: List<EventMakerLiveState>,
        books: Map<String, MarketBook>,
        accountId: Long
    ): EventMakerEngineResult {
        if (stopping) return EventMakerEngineResult()
        engine.haltedReason?.let { return EventMakerEngineResult.halted(it) }
        return engine.quote(states, books, accountId, System.currentTimeMillis())
    }

    private suspend fun runEngineTick(
        engine: EventMakerLiveEngine,
        states: List<EventMakerLiveState>,
        books: Map<String, MarketBook>,
        accountId: Long
    ): EventMakerEngineResult {
        if (stopping) return EventMakerEngineResult()
        val now = System.currentTimeMillis()
        val reconciled = engine.reconcile(states, accountId, now)
        if (engine.haltedReason != null) return reconciled
        if (stopping) return reconciled
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
        val realized = realizedPnlBaseline + states.sumOf { it.realizedPnl }
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

    /** Rolling day key (UTC) used to scope the daily loss budget. */
    private fun currentDayKey(now: Long = System.currentTimeMillis()): Long = now / 86_400_000L

    @Volatile
    private var dailyBaselineDay: Long = currentDayKey()

    /**
     * Advances the engine's daily PnL baseline when the UTC day rolls over, so
     * `maxDailyLoss` measures today's loss instead of the whole run's loss.
     */
    private fun rollDailyPnlBaseline(engine: EventMakerLiveEngine, states: List<EventMakerLiveState>) {
        val today = currentDayKey()
        if (today != dailyBaselineDay) {
            dailyBaselineDay = today
            engine.dailyPnlBaseline = engine.initialRealizedPnl + states.sumOf { it.realizedPnl }
            logger.info("Event maker daily loss budget reset for day=$today")
        }
    }

    private fun dailyPnlSinceBaseline(engine: EventMakerLiveEngine, states: List<EventMakerLiveState>): Double =
        engine.initialRealizedPnl + states.sumOf { it.realizedPnl } + status.unrealizedPnl - engine.dailyPnlBaseline

    private fun persistLive(startedAt: Long?, accountId: Long, states: List<EventMakerLiveState>) {
        stateStore.save(
            EventMakerPersistedState(
                mode = EventMakerMode.LIVE.name,
                accountId = accountId,
                startedAt = startedAt,
                cumulativeRealizedPnl = realizedPnlBaseline + states.sumOf { it.realizedPnl },
                markets = states.map { it.toPersisted() }
            )
        )
    }

    companion object {
        /**
         * Upper bound on how often the LIVE event loop rewrites the state file when
         * nothing changed. Order-affecting events always persist immediately.
         */
        private const val LIVE_PERSIST_INTERVAL_MS = 5_000L
        private const val ORPHAN_CONFIRM_ATTEMPTS = 3
        private const val ORPHAN_CONFIRM_DELAY_MS = 250L
        private val CANCELLED_ORDER_STATUSES = setOf(
            "CANCELLED", "CANCELED", "EXPIRED", "REJECTED"
        )
    }
}
