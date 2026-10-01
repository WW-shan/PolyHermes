package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.enums.SpreadMode
import com.wrbug.polymarketbot.event.CryptoTailStrategyChangedEvent
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.service.binance.BinanceKlineAutoSpreadService
import com.wrbug.polymarketbot.service.binance.BinanceKlineService
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 加密价差策略订单簿 WebSocket 监听：订阅 CLOB Market 频道，收到订单簿/价格变更时若满足条件立即触发下单。
 */
@Service
class CryptoTailOrderbookWsService(
    private val strategyRepository: CryptoTailStrategyRepository,
    private val executionService: CryptoTailStrategyExecutionService,
    private val retrofitFactory: RetrofitFactory,
    private val binanceKlineAutoSpreadService: BinanceKlineAutoSpreadService,
    private val binanceKlineService: BinanceKlineService
) {

    private val logger = LoggerFactory.getLogger(CryptoTailOrderbookWsService::class.java)

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + scopeJob)

    /** tokenId -> list of (strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex) */
    private val tokenToEntries = AtomicReference<Map<String, List<WsBookEntry>>>(emptyMap())

    @Volatile
    private var webSocket: WebSocket? = null
    private val wsUrl = PolymarketConstants.RTDS_WS_URL + "/ws/market"
    private val client = createClient().build()

    /** 下一次按周期/窗口刷新订阅的倒计时 Job */
    @Volatile
    private var periodEndCountdownJob: Job? = null

    /** 心跳 Job：官方要求每 10 秒发送文本 PING，否则约 2–3 分钟被断开 */
    @Volatile
    private var pingJob: Job? = null

    /** 重连延迟（毫秒） */
    private val reconnectDelayMs = 3_000L

    /** 因无启用策略而主动关闭 WS 时置为 true，此时不重连、不处理消息 */
    private val closedForNoStrategies = AtomicBoolean(false)

    /** 保护 connect() 的互斥锁，避免多线程并发创建连接 */
    private val connectLock = Any()

    /** 标记是否正在刷新订阅 */
    private val isRefreshing = AtomicBoolean(false)

    /** 刷新进行中又收到刷新请求时置为 true，本轮结束后补刷一次 */
    private val refreshPending = AtomicBoolean(false)

    /** Gamma 事件缓存：slug（含周期起点）-> 事件，同一周期只拉一次 */
    private val eventCache: Cache<String, GammaEventBySlugResponse> = Caffeine.newBuilder()
        .expireAfterWrite(20, TimeUnit.MINUTES)
        .maximumSize(500)
        .build()

    data class WsBookEntry(
        val strategy: CryptoTailStrategy,
        val periodStartUnix: Long,
        val marketTitle: String?,
        val tokenIds: List<String>,
        val outcomeIndex: Int
    )

    companion object {
        /** 心跳间隔 */
        const val PING_INTERVAL_MS = 10_000L

        /** 兜底检查间隔：订阅集合与应订阅集合不一致时刷新 */
        const val RECONCILE_INTERVAL_MS = 20_000L

        /**
         * 应当订阅的策略及其周期：启用且当前周期时间窗口尚未结束的策略。
         * 已过窗口的策略在下一周期开始时才需要订阅。
         */
        fun desiredStrategyPeriods(strategies: List<CryptoTailStrategy>, nowSeconds: Long): Map<Long, Long> =
            strategies.filter { it.id != null && it.intervalSeconds > 0 }
                .mapNotNull { s ->
                    val periodStart = (nowSeconds / s.intervalSeconds) * s.intervalSeconds
                    if (nowSeconds >= periodStart + s.windowEndSeconds) null else s.id!! to periodStart
                }
                .toMap()

        /** 下一次需要刷新订阅的时间（秒）：各策略下一周期开始中最早的一个 */
        fun nextRefreshAtSeconds(strategies: List<CryptoTailStrategy>, nowSeconds: Long): Long? =
            strategies.filter { it.intervalSeconds > 0 }
                .minOfOrNull { s -> (nowSeconds / s.intervalSeconds) * s.intervalSeconds + s.intervalSeconds }
    }

    @PostConstruct
    fun init() {
        if (strategyRepository.findAllByEnabledTrue().isNotEmpty()) requestRefresh("startup")
    }

    @PreDestroy
    fun destroy() {
        periodEndCountdownJob?.cancel()
        periodEndCountdownJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        stopPing()
        synchronized(precomputeJobs) {
            precomputeJobs.forEach { it.cancel() }
            precomputeJobs.clear()
        }
        closedForNoStrategies.set(true)
        try {
            webSocket?.close(1000, "shutdown")
        } catch (e: Exception) {
            logger.debug("关闭加密价差策略 WebSocket 时异常: ${e.message}")
        }
        webSocket = null
        scopeJob.cancel()
    }

    /** 建立连接；onOpen 时用回调参数里的 socket 发送当前订阅，旧连接的迟到回调一律忽略 */
    private fun connect() {
        synchronized(connectLock) {
            if (webSocket != null) return
            try {
                val request = Request.Builder().url(wsUrl).build()
                var createdSocket: WebSocket? = null
                createdSocket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        if (this@CryptoTailOrderbookWsService.webSocket !== createdSocket) return
                        logger.info("加密价差策略订单簿 WebSocket 已连接")
                        sendSubscription(webSocket)
                        startPing(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (this@CryptoTailOrderbookWsService.webSocket !== createdSocket) return
                        handleMessage(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        if (!clearIfCurrent(createdSocket)) return
                        if (!closedForNoStrategies.get()) scheduleReconnect()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                        if (!clearIfCurrent(createdSocket)) return
                        logger.warn("加密价差策略订单簿 WebSocket 异常: ${t.message}")
                        if (!closedForNoStrategies.get()) scheduleReconnect()
                    }
                })
                webSocket = createdSocket
            } catch (e: Exception) {
                logger.error("加密价差策略订单簿 WebSocket 连接失败: ${e.message}", e)
                scheduleReconnect()
            }
        }
    }

    /** 若 socket 仍是当前连接则清空并停止心跳，返回是否为当前连接 */
    private fun clearIfCurrent(socket: WebSocket?): Boolean {
        synchronized(connectLock) {
            if (socket == null || webSocket !== socket) return false
            webSocket = null
        }
        stopPing()
        return true
    }

    private fun startPing(socket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (webSocket !== socket) break
                try {
                    socket.send("PING")
                } catch (e: Exception) {
                    logger.debug("发送订单簿 WS 心跳失败: ${e.message}")
                }
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    @Volatile
    private var reconnectJob: Job? = null

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectJob = null
            if (strategyRepository.findAllByEnabledTrue().isEmpty()) return@launch
            logger.info("加密价差策略订单簿 WebSocket 尝试重连")
            // 重连前刷新订阅集合（周期可能已切换），刷新流程会在需要时建立连接
            requestRefresh("reconnect")
        }
    }

    private fun handleMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.equals("pong", ignoreCase = true)) return
        if (closedForNoStrategies.get()) return
        checkPeriodRollover()
        val element = try {
            JsonParser.parseString(trimmed)
        } catch (e: Exception) {
            logger.debug("解析订单簿 WS 消息失败: ${e.message}")
            return
        }
        // 订阅后的首帧 book 快照是 JSON 数组，增量消息是对象，两种都要处理
        when {
            element.isJsonArray -> element.asJsonArray.forEach { if (it.isJsonObject) handleEvent(it.asJsonObject) }
            element.isJsonObject -> handleEvent(element.asJsonObject)
        }
    }

    private fun handleEvent(json: JsonObject) {
        val eventType = (json.get("event_type") as? JsonPrimitive)?.asString ?: return
        when (eventType) {
            "book" -> {
                val assetId = (json.get("asset_id") as? JsonPrimitive)?.asString ?: return
                val bids = json.get("bids") as? JsonArray
                if (bids == null || bids.isEmpty) return
                // Polymarket book 的 bids 为价格升序，bids[0] 为最低买价；bestBid 应取最高买价
                var bestBid: BigDecimal? = null
                for (i in 0 until bids.size()) {
                    val level = bids.get(i) as? JsonObject ?: continue
                    val p = (level.get("price") as? JsonPrimitive)?.asString?.toSafeBigDecimal() ?: continue
                    if (bestBid == null || p.gt(bestBid)) bestBid = p
                }
                if (bestBid != null) onBestBid(assetId, bestBid)
            }

            "price_change" -> {
                val priceChanges = json.get("price_changes") as? JsonArray ?: return
                for (i in 0 until priceChanges.size()) {
                    val pc = priceChanges.get(i) as? JsonObject ?: continue
                    val assetId = (pc.get("asset_id") as? JsonPrimitive)?.asString ?: continue
                    val bestBidStr = (pc.get("best_bid") as? JsonPrimitive)?.asString
                    val bestBid = bestBidStr?.toSafeBigDecimal()
                    if (bestBid != null) onBestBid(assetId, bestBid)
                }
            }
        }
    }

    private fun onBestBid(tokenId: String, bestBid: BigDecimal) {
        if (closedForNoStrategies.get()) return
        val entries = tokenToEntries.get()[tokenId]
        if (entries == null) return
        for (e in entries) {
            if (!CryptoTailTiming.isWithinExecutionWindow(
                    e.strategy,
                    e.periodStartUnix,
                    System.currentTimeMillis()
                )
            ) continue
            scope.launch {
                try {
                    executionService.tryTriggerWithPriceFromWs(
                        strategy = e.strategy,
                        periodStartUnix = e.periodStartUnix,
                        marketTitle = e.marketTitle,
                        tokenIds = e.tokenIds,
                        outcomeIndex = e.outcomeIndex,
                        bestBid = bestBid
                    )
                } catch (ex: Exception) {
                    logger.error("WS 触发下单异常: strategyId=${e.strategy.id}, ${ex.message}", ex)
                }
            }
        }
    }

    /**
     * 收到消息时只做纯内存判断：已订阅条目的周期是否已切换。不查库、不在读线程上发 HTTP，
     * 需要刷新时交给 [requestRefresh] 异步合并执行。策略集合变化由事件与兜底定时检查处理。
     */
    private fun checkPeriodRollover() {
        val nowSeconds = System.currentTimeMillis() / 1000
        val rolled = tokenToEntries.get().values.asSequence().flatten().any { e ->
            val interval = e.strategy.intervalSeconds
            interval > 0 && (nowSeconds / interval) * interval != e.periodStartUnix
        }
        if (rolled) requestRefresh("period_rollover")
    }

    /**
     * 异步刷新订阅（合并去抖）：正在刷新时只标记「待刷新」，本轮结束后补刷一次，保证请求不丢失。
     */
    private fun requestRefresh(reason: String) {
        if (!isRefreshing.compareAndSet(false, true)) {
            refreshPending.set(true)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                do {
                    refreshPending.set(false)
                    try {
                        refreshAndSubscribe()
                    } catch (e: Exception) {
                        logger.warn("加密价差策略订阅刷新失败: reason=$reason, ${e.message}", e)
                    }
                } while (refreshPending.get())
            } finally {
                isRefreshing.set(false)
                if (refreshPending.getAndSet(false)) requestRefresh("pending")
            }
        }
    }

    private suspend fun refreshAndSubscribe() {
        val strategies = strategyRepository.findAllByEnabledTrue()
        binanceKlineService.updateSubscriptions(strategies.map { it.marketSlugPrefix }.toSet())
        val oldTokenIds = tokenToEntries.get().keys.toSet()
        val (tokenIds, newMap) = buildSubscriptionMap(strategies)
        tokenToEntries.set(newMap)
        // 无论本周期是否有可订阅的 token，都要安排下一周期开始时的刷新，避免永久休眠
        scheduleNextRefresh(strategies)
        if (tokenIds.isEmpty()) {
            closeWebSocketForNoStrategies()
            return
        }
        closedForNoStrategies.set(false)
        precomputeAutoSpreadForCurrentPeriods(newMap)
        if (webSocket == null) {
            connect()
            return
        }
        if (oldTokenIds == tokenIds.toSet()) return
        closeWebSocketAndReconnect()
    }

    /** 用给定连接发送当前 token 订阅 */
    private fun sendSubscription(socket: WebSocket) {
        val map = tokenToEntries.get()
        val tokenIds = map.keys.toList()
        if (tokenIds.isEmpty()) return
        val marketSlugs = map.values.asSequence().flatten()
            .map { "${it.strategy.marketSlugPrefix}-${it.periodStartUnix}" }
            .distinct()
            .toList()
        val msg = """{"type":"MARKET","assets_ids":${tokenIds.toJson()}}"""
        try {
            socket.send(msg)
            logger.info("加密价差策略订单簿订阅: ${tokenIds.size} 个 token, 市场: $marketSlugs")
        } catch (e: Exception) {
            logger.warn("发送订阅失败: ${e.message}")
        }
    }

    /**
     * 订阅集合变化：关闭旧连接并立即建立新连接，新连接 onOpen 时按最新集合订阅。
     */
    private fun closeWebSocketAndReconnect() {
        val old = synchronized(connectLock) {
            val ws = webSocket
            webSocket = null
            ws
        }
        stopPing()
        if (old != null) {
            try {
                old.close(1000, "subscription_change")
            } catch (e: Exception) {
                logger.debug("关闭加密价差策略 WebSocket 时异常: ${e.message}")
            }
            logger.info("加密价差策略订单簿 WebSocket 已关闭（订阅更新，重新连接）")
        }
        connect()
    }

    /** 跟踪预计算价差的协程 Job，用于在关闭时取消 */
    private val precomputeJobs = mutableSetOf<Job>()

    /**
     * AUTO 模式：在周期开始（刷新订阅）时预拉历史 30 根 K 线并计算该周期价差，触发时直接用缓存。
     */
    private fun precomputeAutoSpreadForCurrentPeriods(newMap: Map<String, List<WsBookEntry>>) {
        val autoPeriods = newMap.values.asSequence().flatten()
            .filter { it.strategy.spreadMode == SpreadMode.AUTO }
            .distinctBy { "${it.strategy.marketSlugPrefix}-${it.strategy.intervalSeconds}-${it.periodStartUnix}" }
            .map { Triple(it.strategy.marketSlugPrefix, it.strategy.intervalSeconds, it.periodStartUnix) }
            .toList()
        if (autoPeriods.isEmpty()) return
        val job = scope.launch {
            for ((marketPrefix, intervalSeconds, periodStartUnix) in autoPeriods) {
                try {
                    val pair = binanceKlineAutoSpreadService.computeAndCache(marketPrefix, intervalSeconds, periodStartUnix)
                    if (pair != null) {
                        logger.info(
                            "周期开始初始价差: market=$marketPrefix interval=${intervalSeconds}s periodStartUnix=$periodStartUnix " +
                                    "baseSpreadUp=${pair.first.toPlainString()} baseSpreadDown=${pair.second.toPlainString()}"
                        )
                    }
                } catch (e: Exception) {
                    logger.warn("周期开始预计算 AUTO 价差失败: market=$marketPrefix interval=$intervalSeconds periodStartUnix=$periodStartUnix ${e.message}")
                }
            }
        }
        synchronized(precomputeJobs) {
            precomputeJobs.add(job)
            // 清理已完成的 Job，避免集合无限增长
            precomputeJobs.removeIf { !it.isActive }
        }
    }

    /**
     * 无需订阅时关闭 WebSocket（不重连）；下一周期的刷新已由 [scheduleNextRefresh] 安排，兜底由 [reconcileSubscriptions] 保证。
     */
    private fun closeWebSocketForNoStrategies() {
        reconnectJob?.cancel()
        reconnectJob = null
        closedForNoStrategies.set(true)
        val ws = synchronized(connectLock) {
            val current = webSocket
            webSocket = null
            current
        }
        stopPing()
        if (ws != null) {
            try {
                ws.close(1000, "no_enabled_strategies")
            } catch (e: Exception) {
                logger.debug("关闭加密价差策略 WebSocket 时异常: ${e.message}")
            }
            logger.info("加密价差策略订单簿 WebSocket 已关闭（当前无需订阅的策略）")
        }
    }

    /**
     * 安排下一次刷新：各启用策略下一周期开始中最早的时刻（+2 秒等待 Gamma 创建市场）。
     */
    private fun scheduleNextRefresh(strategies: List<CryptoTailStrategy>) {
        periodEndCountdownJob?.cancel()
        periodEndCountdownJob = null
        val nowMs = System.currentTimeMillis()
        val nextAtSeconds = nextRefreshAtSeconds(strategies, nowMs / 1000) ?: return
        val delayMs = (nextAtSeconds * 1000 - nowMs + 2000).coerceAtLeast(1000)
        periodEndCountdownJob = scope.launch {
            delay(delayMs)
            requestRefresh("period_start")
        }
        logger.debug("加密价差策略订单簿订阅倒计时: ${delayMs / 1000}s 后刷新")
    }

    /**
     * 兜底检查：订阅集合与「应当订阅的策略集合」不一致、或需要订阅但连接缺失时刷新。
     */
    @Scheduled(fixedDelay = RECONCILE_INTERVAL_MS, initialDelay = RECONCILE_INTERVAL_MS)
    fun reconcileSubscriptions() {
        try {
            val strategies = strategyRepository.findAllByEnabledTrue()
            val subscribed = tokenToEntries.get().values.asSequence().flatten()
                .associate { it.strategy.id!! to it.periodStartUnix }
            val desired = desiredStrategyPeriods(strategies, System.currentTimeMillis() / 1000)
            val connectionMissing = subscribed.isNotEmpty() && webSocket == null && reconnectJob?.isActive != true
            if (subscribed != desired || connectionMissing) {
                requestRefresh("reconcile")
            }
        } catch (e: Exception) {
            logger.warn("加密价差策略订阅兜底检查失败: ${e.message}")
        }
    }

    private suspend fun buildSubscriptionMap(
        strategies: List<CryptoTailStrategy>
    ): Pair<List<String>, Map<String, List<WsBookEntry>>> {
        val nowSeconds = System.currentTimeMillis() / 1000
        val desired = desiredStrategyPeriods(strategies, nowSeconds)
        val tokenIdSet = linkedSetOf<String>()
        val map = mutableMapOf<String, MutableList<WsBookEntry>>()

        for (strategy in strategies) {
            val periodStartUnix = desired[strategy.id] ?: continue
            val slug = "${strategy.marketSlugPrefix}-$periodStartUnix"
            val event = fetchEventCached(slug)
            if (event == null) {
                logger.warn("加密价差策略跳过（拉取事件失败）: strategyId=${strategy.id}, slug=$slug，稍后重试")
                continue
            }
            val tokenIds = parseClobTokenIds(event.markets?.firstOrNull()?.clobTokenIds)
            if (tokenIds.size < 2) {
                logger.warn("加密价差策略跳过（token 数量不足）: strategyId=${strategy.id}, slug=$slug, tokenCount=${tokenIds.size}")
                continue
            }
            tokenIdSet.addAll(tokenIds)
            for (i in tokenIds.indices) {
                map.getOrPut(tokenIds[i]) { mutableListOf() }.add(
                    WsBookEntry(strategy, periodStartUnix, event.title, tokenIds, i)
                )
            }
        }

        return Pair(tokenIdSet.toList(), map)
    }

    /** 按 slug（含周期起点）缓存 Gamma 事件；只缓存 token 完整的结果，失败不缓存以便重试 */
    private suspend fun fetchEventCached(slug: String): GammaEventBySlugResponse? {
        eventCache.getIfPresent(slug)?.let { return it }
        val event = fetchEventBySlugWithRetry(slug).getOrNull() ?: return null
        if (parseClobTokenIds(event.markets?.firstOrNull()?.clobTokenIds).size >= 2) {
            eventCache.put(slug, event)
        }
        return event
    }

    /** 拉取事件，失败时重试最多 2 次（间隔 1s），避免瞬时失败导致多策略只订阅到其中一个 */
    private suspend fun fetchEventBySlugWithRetry(slug: String, maxAttempts: Int = 3): Result<GammaEventBySlugResponse> {
        var lastFailure: Exception? = null
        repeat(maxAttempts) { attempt ->
            val result = fetchEventBySlug(slug)
            if (result.isSuccess) return result
            lastFailure = result.exceptionOrNull() as? Exception
            if (attempt < maxAttempts - 1) delay(1000L)
        }
        return Result.failure(lastFailure ?: Exception("fetchEventBySlug failed"))
    }

    private suspend fun fetchEventBySlug(slug: String): Result<GammaEventBySlugResponse> {
        return try {
            val api = retrofitFactory.createGammaApi()
            val response = api.getEventBySlug(slug)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseClobTokenIds(clobTokenIds: String?): List<String> {
        if (clobTokenIds.isNullOrBlank()) return emptyList()
        val parsed = clobTokenIds.fromJson<List<String>>()
        return parsed ?: emptyList()
    }

    @EventListener
    fun onStrategyChanged(event: CryptoTailStrategyChangedEvent) {
        requestRefresh("strategy_changed")
    }
}
