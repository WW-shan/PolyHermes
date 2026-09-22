package com.wrbug.polymarketbot.service.binance

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Polymarket RTDS 的 Chainlink 60 秒 TWAP 行情。
 *
 * 5/15 分钟加密 Up/Down 市场现在按 Chainlink TWAP 结算，而不是 Binance 现货快照。
 * 本服务按需订阅官方 RTDS topic，并缓存最近一段时间的观察值，供 Crypto Tail
 * 计算周期开盘价和当前价；数据未就绪时调用方可回退到 Binance K 线。
 */
@Service
class ChainlinkTwapService(
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val RTDS_WS_URL = "wss://ws-live-data.polymarket.com"
        const val SIXTY_SECOND_TOPIC = "crypto_prices_twap_sixty"
        const val WINDOW_SECONDS = 60

        private const val PING_INTERVAL_MS = 5_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_HISTORY_MS = 20 * 60 * 1000L
        private const val OPEN_PRICE_TOLERANCE_MS = 15_000L
        private const val CURRENT_PRICE_MAX_STALENESS_MS = 15_000L
        private val E18 = BigDecimal("1000000000000000000")
    }

    private data class TwapPoint(
        val timestampMs: Long,
        val value: BigDecimal
    )

    private val logger = LoggerFactory.getLogger(ChainlinkTwapService::class.java)
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val client by lazy { createClient().build() }
    private val connectionLock = Any()

    /** symbol（如 btc/usd）-> 观察时间 -> TWAP 值 */
    private val history = ConcurrentHashMap<String, ConcurrentSkipListMap<Long, BigDecimal>>()

    /** 当前需要订阅的 symbol 集合 */
    private val requiredSymbols = AtomicReference<Set<String>>(emptySet())

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var connected = false

    private var pingJob: Job? = null
    private var reconnectJob: Job? = null

    /**
     * 根据启用的市场刷新订阅；传入空集合时关闭连接。
     */
    fun updateSubscriptions(marketPrefixes: Set<String>) {
        val symbols = marketPrefixes.mapNotNull(::marketToSymbol).toSet()
        val previous = requiredSymbols.getAndSet(symbols)

        if (symbols.isEmpty()) {
            synchronized(connectionLock) {
                closeConnectionLocked()
            }
            return
        }

        synchronized(connectionLock) {
            if (symbols == previous && webSocket != null) return
            closeConnectionLocked()
            connectLocked()
        }
    }

    /** 供 API 健康检查使用：各 symbol 的 RTDS 连接状态 */
    fun getConnectionStatuses(): Map<String, Boolean> {
        return requiredSymbols.get().associateWith { connected }
    }

    /**
     * 返回该周期开始时的 TWAP 与最新 TWAP。
     * 开盘观察值允许在周期起点前后 15 秒内，当前值必须足够新鲜，否则返回 null 让调用方回退。
     */
    fun getOpenClose(marketSlugPrefix: String, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? {
        val symbol = marketToSymbol(marketSlugPrefix) ?: return null
        val points = history[symbol] ?: return null
        val periodStartMs = periodStartUnix * 1000L
        val openEntry = points.floorEntry(periodStartMs) ?: points.ceilingEntry(periodStartMs)
        if (openEntry == null || abs(openEntry.key - periodStartMs) > OPEN_PRICE_TOLERANCE_MS) return null

        val latest = points.lastEntry() ?: return null
        if (currentTimeMillis() - latest.key > CURRENT_PRICE_MAX_STALENESS_MS) return null

        return openEntry.value to latest.value
    }

    internal fun marketToSymbol(marketSlugPrefix: String): String? {
        val base = marketSlugPrefix
            .lowercase()
            .removeSuffix("-15m")
            .removeSuffix("-5m")
        return when (base) {
            "btc-updown" -> "btc/usd"
            "eth-updown" -> "eth/usd"
            "sol-updown" -> "sol/usd"
            "xrp-updown" -> "xrp/usd"
            else -> null
        }
    }

    /**
     * 解析 RTDS 的初始快照或增量消息，并写入内存缓存。
     * 保持 internal 以便用真实消息格式做单元测试。
     */
    internal fun ingestMessage(text: String) {
        try {
            val root = JsonParser.parseString(text).asJsonObject
            if (root.get("topic")?.asString != SIXTY_SECOND_TOPIC) return
            val payload = root.getAsJsonObject("payload") ?: return
            val symbol = payload.get("symbol")?.takeIf { !it.isJsonNull }?.asString ?: return

            val data = payload.getAsJsonArray("data")
            if (data != null) {
                for (element in data) {
                    val point = element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parsePoint) ?: continue
                    store(symbol, point)
                }
                return
            }

            parsePoint(payload)?.let { store(symbol, it) }
        } catch (e: Exception) {
            logger.debug("解析 Chainlink TWAP 消息失败: ${e.message}")
        }
    }

    private fun parsePoint(obj: JsonObject): TwapPoint? {
        val timestampMs = obj.get("timestamp")?.takeIf { !it.isJsonNull }?.asLong ?: return null
        val fullAccuracy = obj.get("full_accuracy_value")
            ?.takeIf { !it.isJsonNull }
            ?.asString
            ?.takeIf { it.isNotBlank() }
        val value = if (fullAccuracy != null) {
            fullAccuracy.toBigDecimalOrNull()?.divide(E18, 18, RoundingMode.HALF_UP)
        } else {
            obj.get("value")?.takeIf { !it.isJsonNull }?.asString?.toSafeBigDecimal()
        }
        if (value == null || value <= BigDecimal.ZERO) return null
        return TwapPoint(timestampMs, value)
    }

    private fun store(symbol: String, point: TwapPoint) {
        val points = history.computeIfAbsent(symbol) { ConcurrentSkipListMap() }
        points[point.timestampMs] = point.value
        val cutoff = point.timestampMs - MAX_HISTORY_MS
        points.headMap(cutoff, false).clear()
    }

    private fun connectLocked() {
        if (requiredSymbols.get().isEmpty() || webSocket != null) return
        val request = Request.Builder().url(RTDS_WS_URL).build()
        var createdSocket: WebSocket? = null
        createdSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                if (webSocket !== createdSocket) return
                connected = true
                logger.info("Chainlink TWAP RTDS 已连接: ${requiredSymbols.get().sorted()}")
                sendSubscriptions()
                startPing()
            }

            override fun onMessage(socket: WebSocket, text: String) {
                if (webSocket !== createdSocket) return
                ingestMessage(text)
            }

            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== createdSocket) return
                connected = false
                webSocket = null
                pingJob?.cancel()
                pingJob = null
                logger.warn("Chainlink TWAP RTDS 异常: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosing(socket: WebSocket, code: Int, reason: String) {
                if (webSocket !== createdSocket) return
                connected = false
                webSocket = null
                pingJob?.cancel()
                pingJob = null
                if (code != 1000) scheduleReconnect()
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                connected = false
                if (webSocket === createdSocket) webSocket = null
            }
        })
        webSocket = createdSocket
    }

    private fun sendSubscriptions() {
        val symbols = requiredSymbols.get()
        if (symbols.isEmpty()) return
        val subscriptions = symbols.sorted().map { symbol ->
            mapOf(
                "topic" to SIXTY_SECOND_TOPIC,
                "type" to "update",
                "filters" to gson.toJson(mapOf("symbol" to symbol))
            )
        }
        val message = mapOf(
            "action" to "subscribe",
            "subscriptions" to subscriptions
        )
        webSocket?.send(gson.toJson(message))
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                webSocket?.send("PING")
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            reconnectJob = null
            if (requiredSymbols.get().isEmpty()) return@launch
            synchronized(connectionLock) {
                if (webSocket == null) connectLocked()
            }
        }
    }

    private fun closeConnectionLocked() {
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        val socket = webSocket
        connected = false
        webSocket = null
        try {
            socket?.close(1000, "subscription_update")
        } catch (e: Exception) {
            logger.debug("关闭 Chainlink TWAP RTDS 连接失败: ${e.message}")
        }
    }

    @PreDestroy
    fun destroy() {
        synchronized(connectionLock) {
            closeConnectionLocked()
        }
        scope.coroutineContext[Job]?.cancel()
    }
}
