package com.wrbug.polymarketbot.service.binance

import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import jakarta.annotation.PreDestroy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 加密价差策略行情服务：优先使用官方 Chainlink 60 秒 TWAP，未就绪时回退到币安 K 线。
 * 仅当存在启用策略且策略使用到某市场时才订阅对应币种，无策略时不建立连接。
 */
@Service
class BinanceKlineService(
    private val chainlinkTwapService: ChainlinkTwapService
) {

    private val logger = LoggerFactory.getLogger(BinanceKlineService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 官方公开行情 WebSocket 镜像；主域名在部分地区会返回 451。
    private val wsBase = "wss://data-stream.binance.vision"
    private val client by lazy {
        createClient().build()
    }

    /** 币安 K 线快照：开盘价、最新价与本地收到时间 */
    private data class KlineSnapshot(
        val open: BigDecimal,
        val close: BigDecimal,
        val receivedAtMs: Long
    )

    /** (marketSlugPrefix, intervalSeconds, periodStartUnix) -> K 线快照 */
    private val openCloseByPeriod = ConcurrentHashMap<String, KlineSnapshot>()
    
    /** 市场 slug 前缀（如 btc-updown）-> Binance 交易对映射 */
    private val marketToSymbol = mapOf(
        "btc-updown" to "BTCUSDC",
        "eth-updown" to "ETHUSDC",
        "sol-updown" to "SOLUSDC",
        "xrp-updown" to "XRPUSDC"
    )
    
    /** 已连接的 WebSocket: wsKey (symbol-interval) -> WebSocket */
    private val connectedWebSockets = ConcurrentHashMap<String, WebSocket>()
    /** 当前需要订阅的完整市场集合（如 btc-updown-5m、btc-updown-15m），由加密价差策略刷新时更新 */
    private val requiredMarketPrefixes = AtomicReference<Set<String>>(emptySet())
    private val subscriptionLock = Any()
    private var reconnectJob: Job? = null

    /** 解析完整市场 slug（如 btc-updown-5m）为 (basePrefix, interval)，不支持则返回 null */
    private fun parseMarketSlug(full: String): Pair<String, String>? {
        val lower = full.lowercase()
        return when {
            lower.endsWith("-5m") -> Pair(lower.removeSuffix("-5m"), "5m")
            lower.endsWith("-15m") -> Pair(lower.removeSuffix("-15m"), "15m")
            else -> null
        }
    }

    /** 从市场 base 前缀（如 btc-updown）获取 Binance 交易对 */
    private fun getSymbol(basePrefix: String): String? = marketToSymbol[basePrefix]

    private fun key(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): String {
        return "$marketSlugPrefix-$intervalSeconds-$periodStartUnix"
    }

    fun getCurrentOpenClose(marketSlugPrefix: String, intervalSeconds: Int, periodStartUnix: Long): Pair<BigDecimal, BigDecimal>? {
        // 5/15 分钟加密市场以 Chainlink 60 秒 TWAP 结算；RTDS 未就绪时再回退到币安 K 线。
        chainlinkTwapService.getOpenClose(marketSlugPrefix, periodStartUnix)?.let { return it }
        val snapshot = openCloseByPeriod[key(marketSlugPrefix.lowercase(), intervalSeconds, periodStartUnix)] ?: return null
        // 回退数据必须足够新鲜，否则宁可不下单也不能用过期价格判断价差
        if (System.currentTimeMillis() - snapshot.receivedAtMs > FALLBACK_MAX_STALENESS_MS) return null
        return snapshot.open to snapshot.close
    }

    /** 定时清理过期周期的 K 线快照，避免无界增长 */
    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    fun cleanExpiredSnapshots() {
        val threshold = System.currentTimeMillis() - SNAPSHOT_RETENTION_MS
        openCloseByPeriod.entries.removeIf { it.value.receivedAtMs < threshold }
    }

    companion object {
        /** 回退到币安数据时允许的最大延迟 */
        const val FALLBACK_MAX_STALENESS_MS = 15_000L

        /** K 线快照保留时长 */
        private const val SNAPSHOT_RETENTION_MS = 60 * 60 * 1000L
    }

    /** 供 API 健康检查使用：各币种各周期的连接状态 */
    fun getConnectionStatuses(): Map<String, Boolean> {
        return connectedWebSockets.keys.associateWith { connectedWebSockets[it] != null }
    }

    /**
     * 按需更新订阅：仅订阅策略用到的 (币种, 周期)，例如只开 btc 5min 则只建 btc 5min K 线连接。
     * 由 CryptoTailOrderbookWsService 在刷新订阅时根据启用策略的 marketSlugPrefix 调用。
     * @param marketPrefixes 当前启用策略用到的完整市场集合，如 ["btc-updown-5m"] 或 ["btc-updown-5m", "eth-updown-15m"]；空集合时关闭所有连接
     */
    fun updateSubscriptions(marketPrefixes: Set<String>) {
        val normalized = marketPrefixes.map { it.lowercase() }.toSet()
        chainlinkTwapService.updateSubscriptions(normalized)

        val parsed = normalized.mapNotNull { full ->
            parseMarketSlug(full)?.let { (base, interval) ->
                getSymbol(base)?.let { symbol -> Triple(full, symbol, interval) }
            }
        }.toSet()
        val wsKeysNeeded = parsed.map { (_, symbol, interval) -> "$symbol-$interval" }.toSet()
        
        // 检查是否有需要的 WebSocket 连接缺失（可能因网络问题断开）
        val hasMissingConnection = wsKeysNeeded.any { it !in connectedWebSockets.keys }
        
        // 只有当集合相同且所有需要的连接都存在时才跳过
        if (normalized == requiredMarketPrefixes.get() && !hasMissingConnection) return
        requiredMarketPrefixes.set(normalized)
        synchronized(subscriptionLock) {
            connectedWebSockets.keys.toList().forEach { wsKey ->
                if (wsKey !in wsKeysNeeded) {
                    connectedWebSockets.remove(wsKey)?.close(1000, "subscription_update")
                    logger.info("币安 K 线 WS 已关闭（无策略使用）: $wsKey")
                }
            }
            parsed.forEach { (fullPrefix, symbol, interval) ->
                connectStream(symbol, interval, fullPrefix) { marketPrefixParam, intervalSec, tMs, openP, closeP ->
                    val periodSec = tMs / 1000
                    openCloseByPeriod[key(marketPrefixParam, intervalSec, periodSec)] =
                        KlineSnapshot(openP, closeP, System.currentTimeMillis())
                }
            }
        }
    }

    private fun connectStream(
        symbol: String,
        interval: String,
        marketPrefix: String,
        onKline: (marketPrefix: String, intervalSeconds: Int, openTimeMs: Long, open: BigDecimal, close: BigDecimal) -> Unit
    ) {
        val streamName = "${symbol.lowercase()}@kline_$interval"
        val wsKey = "$symbol-$interval"
        if (connectedWebSockets[wsKey] != null) return
        
        val url = "$wsBase/ws/$streamName"
        val intervalSeconds = when (interval) {
            "5m" -> 300
            "15m" -> 900
            else -> 300
        }
        val request = Request.Builder().url(url).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                connectedWebSockets[wsKey] = webSocket
                logger.info("币安 K 线 WS 已连接: $streamName")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseKlineMessage(text)?.let { (tMs, o, c) ->
                    onKline(marketPrefix, intervalSeconds, tMs, o, c)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                // 主动关闭的连接已先从 map 移除，其迟到回调不再触发重连
                val wasCurrent = connectedWebSockets.remove(wsKey, webSocket)
                logger.warn("币安 K 线 WS 异常 $streamName: ${t.message}")
                if (wasCurrent || connectedWebSockets[wsKey] == null) scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // 非主动关闭（包括服务端发来的 1000）都要重连
                if (connectedWebSockets.remove(wsKey, webSocket)) {
                    logger.warn("币安 K 线 WS 被关闭，准备重连 $streamName: code=$code, reason=$reason")
                    scheduleReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectedWebSockets.remove(wsKey, webSocket)
            }
        })
    }

    private fun parseKlineMessage(text: String): Triple<Long, BigDecimal, BigDecimal>? {
        return try {
            val json = com.google.gson.JsonParser.parseString(text).asJsonObject
            if (json.get("e")?.asString != "kline") return null
            val k = json.getAsJsonObject("k") ?: return null
            val tMs = k.get("t")?.asLong ?: return null
            val o = k.get("o")?.asString?.toSafeBigDecimal() ?: return null
            val c = k.get("c")?.asString?.toSafeBigDecimal() ?: return null
            Triple(tMs, o, c)
        } catch (e: Exception) {
            logger.debug("解析币安 K 线消息失败: ${e.message}")
            null
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(3_000)
            reconnectJob = null
            val current = requiredMarketPrefixes.get()
            // 先清空再关闭，旧连接的迟到回调因不在 map 中而被忽略
            val old = connectedWebSockets.values.toList()
            connectedWebSockets.clear()
            old.forEach { it.close(1000, "reconnect") }
            logger.info("币安 K 线 WS 尝试重连")
            // 清空 requiredMarketPrefixes，否则 updateSubscriptions(current) 内会因 normalized == requiredMarketPrefixes.get() 直接 return，不会重新 connectStream
            requiredMarketPrefixes.set(emptySet())
            updateSubscriptions(current)
        }
    }

    @PreDestroy
    fun destroy() {
        reconnectJob?.cancel()
        connectedWebSockets.values.forEach { it.close(1000, "shutdown") }
        connectedWebSockets.clear()
    }
}
