package com.wrbug.polymarketbot.service.copytrading.monitor

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.dto.ActivityTradeMessage
import com.wrbug.polymarketbot.dto.ActivityTradePayload
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.event.ProxyConfigChangedEvent
import com.wrbug.polymarketbot.enums.LeaderResearchSourceStatus
import com.wrbug.polymarketbot.enums.LeaderResearchSourceType
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.research.LeaderActivityIngestionService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchSourceHealthService
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.websocket.PolymarketWebSocketClient
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Polymarket Activity WebSocket 监听服务
 * 通过订阅全局 activity 交易流（trades + orders_matched），客户端过滤 Leader 地址，实现实时交易检测
 * 延迟 < 100ms，适合快速跟单场景
 */
@Service
class PolymarketActivityWsService(
    private val copyOrderTrackingService: CopyOrderTrackingService,
    private val leaderRepository: LeaderRepository,
    private val researchIngestionProvider: ObjectProvider<LeaderActivityIngestionService>,
    private val researchSourceHealthProvider: ObjectProvider<LeaderResearchSourceHealthService>,
    @Value("\${leader.research.global-capture.enabled:false}") private val researchGlobalCaptureEnabled: Boolean,
    @Value("\${leader.research.global-capture.max-writes-per-minute:120}") private val researchGlobalCaptureMaxWritesPerMinute: Long
) {

    private val logger = LoggerFactory.getLogger(PolymarketActivityWsService::class.java)

    private val websocketUrl: String = PolymarketConstants.ACTIVITY_WS_URL

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 单例 WebSocket 客户端
    private var wsClient: PolymarketWebSocketClient? = null

    // 要监听的 Leader 地址集合（小写地址 -> leaderId）
    private val monitoredAddresses = ConcurrentHashMap<String, Long>()

    // 已交给下游的聚合成交：key = "leaderId:tradeId"，窗口结束后迟到的同一成交消息直接丢弃
    private val processedTxHashes: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build()

    /**
     * 同一 (leader, tx, asset, side) 在聚合窗口内的成交累计。
     * trades 消息逐笔 fill（maker 每个被撮合订单一条、taker 一条汇总），orders_matched 只针对 taker 且与其 trades 汇总重复，
     * 因此两类分别累计：有 trades 时以 trades 为准，否则退回 orders_matched。
     */
    private class PendingAggregate(
        val leaderId: Long,
        val txHash: String,
        val template: TradeResponse
    ) {
        var tradesSize: BigDecimal = BigDecimal.ZERO
        var tradesNotional: BigDecimal = BigDecimal.ZERO
        var matchedSize: BigDecimal = BigDecimal.ZERO
        var matchedNotional: BigDecimal = BigDecimal.ZERO
    }

    private val pendingAggregates = ConcurrentHashMap<String, PendingAggregate>()

    // 聚合窗口（毫秒），测试中可调小
    internal var aggregationWindowMs = 1500L

    // 下游处理失败时，在内存中对同一成交做有限重试
    internal var maxDeliveryAttempts = 3
    internal var deliveryRetryDelayMs = 500L

    // 超过该时长未收到任何帧则主动重连（毫秒）
    internal var activityTimeoutMs = 60_000L

    // 按 Leader 串行交付，保证同一 Leader 的 BUY/SELL 按到达顺序处理
    private val dispatcher = LeaderTradeDispatcher(scope) { leaderId, trade, source ->
        copyOrderTrackingService.processTrade(leaderId = leaderId, trade = trade, source = source)
    }

    // 是否已订阅
    @Volatile
    private var isSubscribed = false

    // 最后一次收到 activity 消息的时间（毫秒时间戳）
    @Volatile
    private var lastActivityTime: Long = 0

    // Activity 消息超时检测任务
    private var activityTimeoutJob: Job? = null

    // 性能统计
    private var totalMessagesProcessed = 0L
    private var addressMatchMessages = 0L
    private var jsonParseMessages = 0L
    private var duplicateTxHashMessages = 0L
    private var researchCaptureWindowMinute = 0L
    private var researchCaptureWritesThisMinute = 0L
    private var researchCaptureLastHealthStatus: LeaderResearchSourceStatus? = null
    private var researchCaptureLastHealthWriteAt = 0L

    /**
     * 启动监听
     */
    fun start(leaders: List<Leader>) {
        monitoredAddresses.clear()
        leaders.forEach { leader ->
            val leaderId = leader.id
            if (leaderId != null) {
                monitoredAddresses[leader.leaderAddress.lowercase()] = leaderId
            }
        }

        if (monitoredAddresses.isEmpty()) {
            logger.info("没有需要监听的 Leader，停止 Activity WebSocket")
            stop()
            return
        }

        logger.info("启动 Activity WebSocket 监听（trades + orders_matched），监控 ${monitoredAddresses.size} 个 Leader 地址")
        connectAndSubscribe()
    }

    /**
     * 添加 Leader
     */
    fun addLeader(leader: Leader) {
        if (leader.id == null) {
            logger.warn("Leader ID 为空，跳过: ${leader.leaderAddress}")
            return
        }

        val address = leader.leaderAddress.lowercase()
        val existingLeaderId = monitoredAddresses[address]

        if (existingLeaderId != null && existingLeaderId == leader.id) {
            logger.debug("Leader 已在监听列表中: ${leader.leaderName} (${address})")
            return
        }

        val leaderId = leader.id
        if (leaderId != null) {
            monitoredAddresses[address] = leaderId
            logger.info("添加 Leader 到 Activity WS 监听: ${leader.leaderName} (${address})")

            // 如果 WebSocket 未连接，连接
            val client = wsClient
            if (client == null || !client.isConnected()) {
                connectAndSubscribe()
            }
        }
    }

    /**
     * 移除 Leader
     */
    fun removeLeader(leaderId: Long) {
        val addressToRemove = monitoredAddresses.entries
            .find { it.value == leaderId }?.key

        if (addressToRemove != null) {
            monitoredAddresses.remove(addressToRemove)
            dispatcher.remove(leaderId)
            logger.info("从 Activity WS 监听移除 Leader: leaderId=$leaderId, address=$addressToRemove")
        }

        // 如果没有 Leader 了，停止监听
        if (monitoredAddresses.isEmpty()) {
            logger.info("没有 Leader 需要监听了，停止 Activity WebSocket")
            stop()
        }
    }

    /**
     * 代理配置变更后按新代理重建连接（PolymarketWebSocketClient 每次 connect 都会读取当前代理）
     */
    @EventListener(ProxyConfigChangedEvent::class)
    fun onProxyConfigChanged() {
        if (monitoredAddresses.isEmpty()) {
            return
        }
        logger.info("代理配置已变更，重建 Activity WebSocket 连接")
        reconnect()
    }

    /**
     * 主动断开并重新连接
     */
    fun reconnect() {
        stopActivityTimeoutCheck()
        wsClient?.closeConnection()
        wsClient = null
        isSubscribed = false
        connectAndSubscribe()
    }

    /**
     * 连接并订阅
     */
    private fun connectAndSubscribe() {
        val existingClient = wsClient
        if (existingClient != null && existingClient.isConnected()) {
            // 如果已连接但未订阅，发送订阅
            if (!isSubscribed) {
                subscribeAllActivity()
            }
            return
        }

        logger.info("连接 Activity WebSocket: $websocketUrl")
        // 旧客户端可能仍在自动重连，先关闭避免出现两条连接重复推送
        existingClient?.closeConnection()

        val newClient = PolymarketWebSocketClient(
            url = websocketUrl,
            sessionId = "copy-trading-activity",
            onMessage = { message -> handleMessage(message) },
            onOpen = {
                logger.info("Activity WebSocket 连接成功")
                subscribeAllActivity()
            },
            onReconnect = {
                logger.info("Activity WebSocket 重连成功，重新订阅")
                subscribeAllActivity()
            }
        )

        wsClient = newClient

        scope.launch {
            try {
                newClient.connect()
            } catch (e: Exception) {
                logger.error("连接 Activity WebSocket 失败", e)
            }
        }
    }

    /**
     * 订阅全局 activity
     * 根据 @polymarket/real-time-data-client 的协议格式
     * 使用 "action": "subscribe" 而不是 "type": "subscribe"
     * 同时订阅 trades 和 orders_matched 两种类型
     */
    private fun subscribeAllActivity() {
        val client = wsClient
        if (client == null || !client.isConnected()) {
            logger.warn("WebSocket 未连接，无法订阅")
            return
        }

        try {
            // 根据 real-time-data-client 的协议格式
            // 订阅消息应包含 "action": "subscribe" 和 "subscriptions" 数组
            // 同时订阅 trades 和 orders_matched 两种类型
            val subscribeMessage = """
            {
                "action": "subscribe",
                "subscriptions": [
                    {
                        "topic": "activity",
                        "type": "trades"
                    },
                    {
                        "topic": "activity",
                        "type": "orders_matched"
                    }
                ]
            }
            """.trimIndent()

            client.sendMessage(subscribeMessage)
            isSubscribed = true
            // 重置最后一次收到 activity 消息的时间
            lastActivityTime = System.currentTimeMillis()
            // 启动 Activity 消息超时检测
            startActivityTimeoutCheck()
            logger.info("Activity WebSocket 订阅成功（全局交易流: trades + orders_matched）")
        } catch (e: Exception) {
            logger.error("订阅 Activity WebSocket 失败", e)
            isSubscribed = false
        }
    }

    /**
     * 启动 Activity 消息超时检测
     * 每30秒检查一次，如果超过30秒没有收到activity消息，则重连
     */
    private fun startActivityTimeoutCheck() {
        // 先停止之前的检测任务
        stopActivityTimeoutCheck()

        activityTimeoutJob = scope.launch {
            while (isActive && isSubscribed) {
                delay((activityTimeoutMs / 2).coerceAtLeast(1000L))

                // 如果已经取消订阅，停止检测
                if (!isSubscribed) {
                    break
                }

                // 如果 lastActivityTime 为 0，说明还没有收到过消息，跳过本次检测
                if (lastActivityTime == 0L) {
                    continue
                }

                val currentTime = System.currentTimeMillis()
                val timeSinceLastActivity = currentTime - lastActivityTime

                // 超时未收到任何帧，触发重连
                if (timeSinceLastActivity >= activityTimeoutMs) {
                    logger.warn("超过 ${activityTimeoutMs}ms 未收到 Activity 消息，触发重连。距离上次消息: ${timeSinceLastActivity}ms")
                    // 关闭当前连接并重连
                    wsClient?.closeConnection()
                    wsClient = null
                    isSubscribed = false
                    // 重新连接
                    connectAndSubscribe()
                    break  // 重连后会重新启动检测任务
                }
            }
        }
    }

    /**
     * 停止 Activity 消息超时检测
     */
    private fun stopActivityTimeoutCheck() {
        activityTimeoutJob?.cancel()
        activityTimeoutJob = null
    }

    /**
     * 检查消息是否包含监听的 Leader 地址
     * 快速过滤，避免不必要的 JSON 解析
     * 只需要检查 "proxyWallet":"0x..." 或 "trader":{"address":"0x..."} 格式
     */
    private fun containsMonitoredAddress(message: String): Boolean {
        // 快速检查：如果消息很短，不可能包含地址
        if (message.length < 50) {
            return false
        }

        // 遍历所有监听的地址
        for ((address, leaderId) in monitoredAddresses) {
            // 检查 proxyWallet：格式为 "proxyWallet":"0x..."
            if (message.contains("\"proxyWallet\":\"$address\"", ignoreCase = true)) {
                addressMatchMessages++
                return true
            }

            // 检查 trader.address：格式为 "trader":{"address":"0x..."}
            if (message.contains("\"trader\"", ignoreCase = true) &&
                message.contains("\"address\":\"$address\"", ignoreCase = true)
            ) {
                addressMatchMessages++
                return true
            }
        }

        return false
    }

    /**
     * 处理消息
     */
    private fun handleMessage(message: String) {
        try {
            totalMessagesProcessed++
            // 任意帧（包括 PONG、其他地址的成交）都说明连接存活，必须在地址过滤之前更新
            lastActivityTime = System.currentTimeMillis()

            // 处理 PONG 响应
            if (message.trim() == "PONG" || message.trim() == "pong") {
                return
            }

            maybeCaptureResearchActivity(message)

            // 快速预检查：检查是否包含监听地址
            // 绝大部分消息会在这一步被过滤掉，避免不必要的 JSON 解析
            if (!containsMonitoredAddress(message)) {
                return
            }
            logger.info("发现leader交易：${message}")
            // 使用扩展函数解析消息（只对包含监听地址的消息）
            val tradeMessage = message.fromJson<ActivityTradeMessage>() ?: run {
                // 不是有效的 JSON 或格式不匹配，跳过
                logger.warn("无法解析为 ActivityTradeMessage: ${message.take(200)}")
                return
            }

            jsonParseMessages++

            // 检查是否是 activity 消息（trades 或 orders_matched）
            if (tradeMessage.topic != "activity" || 
                (tradeMessage.type != "trades" && tradeMessage.type != "orders_matched")) {
                return
            }

            val payload = tradeMessage.payload

            // 先按地址匹配 Leader，再做去重/聚合（同一 tx 可能包含多个被跟 Leader 的成交）
            val traderAddress = extractTraderAddress(payload) ?: run {
                logger.warn("Activity Trade 消息中没有交易者地址: trader=${payload.trader}, proxyWallet=${payload.proxyWallet}, asset=${payload.asset}")
                return
            }
            val leaderId = monitoredAddresses[traderAddress.lowercase()] ?: return

            val trade = parseActivityTrade(payload, leaderId)
            if (trade == null) {
                logger.warn("解析交易数据失败: leaderId=$leaderId, address=$traderAddress, asset=${payload.asset}, side=${payload.side}")
                return
            }
            logger.info("检测到 Leader 成交: leaderId=$leaderId, address=$traderAddress, type=${tradeMessage.type}, side=${trade.side}, market=${trade.market}, size=${trade.size}, price=${trade.price}")

            val txHash = payload.transactionHash
            if (txHash.isNullOrBlank()) {
                // 没有 txHash 无法聚合/去重，直接按 Leader 串行交付
                scope.launch {
                    if (!deliverTradeWithRetry(leaderId, trade, "activity-ws")) {
                        logger.error("Activity 成交多次交付失败: leaderId=$leaderId, tradeId=${trade.id}")
                    }
                }
                return
            }
            addToAggregate(leaderId, txHash, trade, isOrdersMatched = tradeMessage.type == "orders_matched")
        } catch (e: Exception) {
            logger.error("处理 Activity WebSocket 消息失败: ${e.message}", e)
        }
    }

    /**
     * 将一笔成交加入 (leader, tx, asset, side) 聚合；首笔到达时启动窗口计时，窗口结束后一次性交付
     */
    private fun addToAggregate(leaderId: Long, txHash: String, trade: TradeResponse, isOrdersMatched: Boolean) {
        val asset = trade.tokenId ?: ""
        val tradeId = OnChainWsUtils.buildTradeId(txHash, asset, trade.side)
        if (processedTxHashes.getIfPresent("$leaderId:$tradeId") != null) {
            duplicateTxHashMessages++
            logger.debug("成交已交付过，忽略迟到消息: leaderId=$leaderId, tradeId=$tradeId")
            return
        }
        val size = trade.size.toBigDecimalOrNull() ?: return
        val price = trade.price.toBigDecimalOrNull() ?: return
        val key = "$leaderId|$tradeId"
        var created = false
        val aggregate = pendingAggregates.computeIfAbsent(key) {
            created = true
            PendingAggregate(leaderId, txHash, trade.copy(id = tradeId))
        }
        synchronized(aggregate) {
            if (isOrdersMatched) {
                aggregate.matchedSize = aggregate.matchedSize.add(size)
                aggregate.matchedNotional = aggregate.matchedNotional.add(size.multiply(price))
            } else {
                aggregate.tradesSize = aggregate.tradesSize.add(size)
                aggregate.tradesNotional = aggregate.tradesNotional.add(size.multiply(price))
            }
        }
        if (created) {
            scope.launch {
                delay(aggregationWindowMs)
                flushAggregate(key)
            }
        }
    }

    /**
     * 窗口结束：size 求和、价格按成交量加权，以 tradeId 交给下游（与链上路径的 id 一致）
     */
    private suspend fun flushAggregate(key: String) {
        val aggregate = pendingAggregates.remove(key) ?: return
        val (size, notional) = synchronized(aggregate) {
            if (aggregate.tradesSize > BigDecimal.ZERO) {
                aggregate.tradesSize to aggregate.tradesNotional
            } else {
                aggregate.matchedSize to aggregate.matchedNotional
            }
        }
        if (size <= BigDecimal.ZERO) return
        val price = notional.divide(size, 8, java.math.RoundingMode.HALF_UP)
        val trade = aggregate.template.copy(
            size = size.stripTrailingZeros().toPlainString(),
            price = price.stripTrailingZeros().toPlainString()
        )
        if (deliverTradeWithRetry(aggregate.leaderId, trade, "activity-ws")) {
            processedTxHashes.put("${aggregate.leaderId}:${trade.id}", System.currentTimeMillis())
            logger.info("交付 Leader 聚合成交: leaderId=${aggregate.leaderId}, tradeId=${trade.id}, side=${trade.side}, size=${trade.size}, price=${trade.price}")
        } else {
            logger.error("Activity 聚合成交多次交付失败，未写入去重缓存: leaderId=${aggregate.leaderId}, tradeId=${trade.id}")
        }
    }

    private suspend fun deliverTradeWithRetry(leaderId: Long, trade: TradeResponse, source: String): Boolean {
        for (attempt in 1..maxDeliveryAttempts) {
            if (dispatcher.deliver(leaderId, trade, source)) return true
            if (attempt < maxDeliveryAttempts) delay(deliveryRetryDelayMs)
        }
        return false
    }

    private fun maybeCaptureResearchActivity(message: String) {
        if (!researchGlobalCaptureEnabled) {
            recordResearchCaptureHealth(
                status = LeaderResearchSourceStatus.DISABLED,
                disabledReason = "Global activity capture is disabled"
            )
            return
        }
        val currentMinute = System.currentTimeMillis() / 60_000
        if (researchCaptureWindowMinute != currentMinute) {
            researchCaptureWindowMinute = currentMinute
            researchCaptureWritesThisMinute = 0
        }
        if (researchCaptureWritesThisMinute >= researchGlobalCaptureMaxWritesPerMinute) {
            recordResearchCaptureHealth(
                status = LeaderResearchSourceStatus.DEGRADED,
                errorClass = "WriteCapReached",
                errorMessage = "write capped at $researchGlobalCaptureMaxWritesPerMinute events per minute"
            )
            return
        }
        val tradeMessage = message.fromJson<ActivityTradeMessage>() ?: run {
            recordResearchCaptureHealth(
                status = LeaderResearchSourceStatus.FAILURE,
                errorClass = "JsonParseFailure",
                errorMessage = "failed to parse activity websocket message"
            )
            return
        }
        if (tradeMessage.topic != "activity" || (tradeMessage.type != "trades" && tradeMessage.type != "orders_matched")) {
            return
        }
        val ingestionService = researchIngestionProvider.getIfAvailable() ?: return
        try {
            val event = ingestionService.ingestWebSocketTrade(tradeMessage)
            researchCaptureWritesThisMinute++
            recordResearchCaptureHealth(
                status = LeaderResearchSourceStatus.SUCCESS,
                candidateCount = researchCaptureWritesThisMinute.toInt(),
                lastCursor = "${event.eventTime}:${event.stableEventKey}"
            )
        } catch (e: Exception) {
            logger.warn("Research global activity capture failed: {}", e.message)
            recordResearchCaptureHealth(
                status = LeaderResearchSourceStatus.FAILURE,
                errorClass = e::class.java.simpleName,
                errorMessage = e.message
            )
        }
    }

    private fun recordResearchCaptureHealth(
        status: LeaderResearchSourceStatus,
        candidateCount: Int = 0,
        errorClass: String? = null,
        errorMessage: String? = null,
        disabledReason: String? = null,
        lastCursor: String? = null
    ) {
        if (shouldThrottleResearchCaptureHealth(status)) {
            return
        }
        try {
            researchSourceHealthProvider.getIfAvailable()?.record(
                sourceType = LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE,
                status = status,
                candidateCount = candidateCount,
                errorClass = errorClass,
                errorMessage = errorMessage,
                disabledReason = disabledReason,
                lastCursor = lastCursor
            )
        } catch (e: Exception) {
            logger.warn("记录研究全局 activity 来源健康失败: status={}, error={}", status, e.message)
        }
    }

    private fun shouldThrottleResearchCaptureHealth(status: LeaderResearchSourceStatus): Boolean {
        val now = System.currentTimeMillis()
        val throttleWindow = when (status) {
            LeaderResearchSourceStatus.DISABLED -> RESEARCH_CAPTURE_DISABLED_HEALTH_THROTTLE_MS
            LeaderResearchSourceStatus.SUCCESS -> 0L
            else -> RESEARCH_CAPTURE_HEALTH_THROTTLE_MS
        }
        val throttle = throttleWindow > 0 &&
            status == researchCaptureLastHealthStatus &&
            now - researchCaptureLastHealthWriteAt < throttleWindow
        if (!throttle) {
            researchCaptureLastHealthStatus = status
            researchCaptureLastHealthWriteAt = now
        }
        return throttle
    }

    /**
     * 提取交易者地址
     * 优先检查 trader.address，fallback 到 proxyWallet
     */
    private fun extractTraderAddress(payload: ActivityTradePayload): String? {
        // 优先检查 trader.address
        val address = payload.trader?.address
            ?: payload.proxyWallet

        return address
    }

    /**
     * 解析 Activity Trade 为 TradeResponse
     */
    private fun parseActivityTrade(payload: ActivityTradePayload, leaderId: Long): TradeResponse? {
        return try {
            // 提取必需字段并验证
            val asset = payload.asset
            val conditionId = payload.conditionId
            val sideRaw = payload.side

            if (asset.isBlank() || conditionId.isBlank() || sideRaw.isBlank()) {
                logger.warn("Activity Trade 消息缺少必需字段: asset=$asset, conditionId=$conditionId, side=$sideRaw")
                return null
            }

            val side = sideRaw.uppercase()

            // 验证 side 必须是 BUY 或 SELL
            if (side != "BUY" && side != "SELL") {
                logger.warn("Activity Trade 消息 side 字段无效: side=$side")
                return null
            }

            // price 和 size 可能是数字或字符串，统一转换为字符串
            val price = convertToString(payload.price) ?: run {
                logger.warn("Activity Trade 消息 price 字段无效: ${payload.price}")
                return null
            }

            val size = convertToString(payload.size) ?: run {
                logger.warn("Activity Trade 消息 size 字段无效: ${payload.size}")
                return null
            }

            // 时间戳处理：可能是秒或毫秒，可能是数字或字符串
            val timestamp = when {
                payload.timestamp == null -> System.currentTimeMillis().toString()
                payload.timestamp is Number -> {
                    val ts = (payload.timestamp as Number).toLong()
                    // 如果时间戳小于 1e12（秒级），转换为毫秒
                    if (ts < 1e12) (ts * 1000).toString() else ts.toString()
                }

                payload.timestamp is String -> {
                    val tsStr = payload.timestamp as String
                    val ts = tsStr.toLongOrNull() ?: System.currentTimeMillis()
                    if (ts < 1e12) (ts * 1000).toString() else tsStr
                }

                else -> System.currentTimeMillis().toString()
            }

            // 解析 outcome 和 outcomeIndex
            // 优先使用消息中的 outcomeIndex，如果没有则从 outcome 字符串解析
            val outcome = payload.outcome
            val outcomeIndex = payload.outcomeIndex
                ?: parseOutcomeIndex(outcome)

            // 使用 transactionHash 作为 trade ID，如果没有则生成 fallback ID
            val tradeId = payload.transactionHash ?: "${leaderId}_${System.currentTimeMillis()}_${asset.take(10)}"

            // asset 即 CLOB 的 tokenId，必须写入 TradeResponse，跟单下单时用此 tokenId 请求订单簿/下单，否则会用 conditionId+outcomeIndex 链上重算，可能得到与 CLOB 不一致的 tokenId
            TradeResponse(
                id = tradeId,
                market = conditionId,
                side = side,
                price = price,
                size = size,
                timestamp = timestamp,
                user = null, // Activity WS 中不需要
                outcomeIndex = outcomeIndex,
                outcome = outcome,
                tokenId = asset
            )
        } catch (e: Exception) {
            logger.error("解析 Activity Trade 失败: ${e.message}", e)
            null
        }
    }

    /**
     * 将 Any 类型的值转换为 String
     * 支持 Number、String、BigDecimal 等类型
     */
    private fun convertToString(value: Any?): String? {
        if (value == null) return null

        return when (value) {
            is String -> value
            is Number -> {
                // 如果是数字，转换为 BigDecimal 再转为字符串（保持精度）
                try {
                    BigDecimal(value.toString()).toPlainString()
                } catch (e: Exception) {
                    value.toString()
                }
            }

            is BigDecimal -> value.toPlainString()
            else -> value.toString()
        }
    }

    /**
     * 解析 outcome 为 outcomeIndex
     */
    private fun parseOutcomeIndex(outcome: String?): Int? {
        return when (outcome?.uppercase()) {
            "YES", "UP", "TRUE" -> 0
            "NO", "DOWN", "FALSE" -> 1
            else -> null
        }
    }

    /**
     * 停止监听
     */
    fun stop() {
        logger.info("停止 Activity WebSocket 监听")
        stopActivityTimeoutCheck()
        wsClient?.closeConnection()
        wsClient = null
        isSubscribed = false
        monitoredAddresses.clear()
        pendingAggregates.clear()
        dispatcher.clear()
        processedTxHashes.invalidateAll()  // 清空去重缓存
        lastActivityTime = 0
    }

    /**
     * 获取连接状态
     */
    fun isConnected(): Boolean {
        return wsClient?.isConnected() ?: false
    }

    /**
     * 当前监听中的 Leader ID
     */
    fun getMonitoredLeaderIds(): Set<Long> = monitoredAddresses.values.toSet()

    /**
     * 获取监听的 Leader 数量
     */
    fun getMonitoredCount(): Int {
        return monitoredAddresses.size
    }

    /**
     * 获取性能统计信息
     */
    fun getPerformanceStats(): Map<String, Any> {
        val jsonParseRate = if (totalMessagesProcessed > 0) {
            (jsonParseMessages.toDouble() / totalMessagesProcessed * 100).toInt()
        } else {
            0
        }

        return mapOf(
            "totalMessages" to totalMessagesProcessed,
            "addressMatches" to addressMatchMessages,
            "jsonParses" to jsonParseMessages,
            "duplicateTxHashes" to duplicateTxHashMessages,
            "jsonParseRate" to "$jsonParseRate%",
            "filteringEfficiency" to if (totalMessagesProcessed > 0) {
                ((1.0 - jsonParseMessages.toDouble() / totalMessagesProcessed) * 100).toInt()
            } else {
                0
            }
        )
    }

    @PreDestroy
    fun destroy() {
        logger.info("Activity WS 性能统计: ${getPerformanceStats()}")
        stop()
        scope.cancel()
    }

    companion object {
        private const val RESEARCH_CAPTURE_HEALTH_THROTTLE_MS = 60_000L
        private const val RESEARCH_CAPTURE_DISABLED_HEALTH_THROTTLE_MS = 60L * 60L * 1000L
    }
}
