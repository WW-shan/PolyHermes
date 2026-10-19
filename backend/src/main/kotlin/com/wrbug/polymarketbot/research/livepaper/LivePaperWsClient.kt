package com.wrbug.polymarketbot.research.livepaper

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

class LivePaperWsClient(
    proxyUrl: String?,
    private val tokenToCondition: Map<String, String>,
    private val onEvent: (LiveFeedEvent) -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val wsUrl: String = DEFAULT_WS_URL,
    private val reconnectDelayMs: Long = 3_000L
) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .apply {
            if (!proxyUrl.isNullOrBlank()) {
                val uri = URI(proxyUrl)
                val port = if (uri.port > 0) uri.port else 10808
                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, port)))
            }
        }
        .build()

    private val lock = Any()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = true
    @Volatile private var heartbeat: Job? = null
    @Volatile private var reconnectJob: Job? = null

    fun start() {
        connect()
    }

    fun close() {
        running = false
        heartbeat?.cancel()
        heartbeat = null
        reconnectJob?.cancel()
        reconnectJob = null
        synchronized(lock) {
            try {
                socket?.close(1000, "paper_run_finished")
            } catch (_: Exception) {
            }
            socket = null
        }
        scopeJob.cancel()
        http.dispatcher.executorService.shutdownNow()
        http.connectionPool.evictAll()
    }

    private fun connect() {
        if (!running) return
        synchronized(lock) {
            if (socket != null) return
            val request = Request.Builder().url(wsUrl).build()
            var created: WebSocket? = null
            created = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    if (socket !== created) return
                    onStatus("WS_OPEN")
                    sendSubscription(webSocket)
                    startHeartbeat(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (socket !== created) return
                    parseAndDispatch(text)
                }


                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket !== created) return
                    // Server-initiated close: complete the handshake so onClosed
                    // fires immediately. Without this, OkHttp waits out its
                    // 60-second close timeout before we reconnect, leaving a
                    // blind window with no book/trade feed during a live run.
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket !== created) return
                    socket = null
                    heartbeat?.cancel()
                    heartbeat = null
                    onStatus("WS_CLOSED code=$code reason=$reason")
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    if (socket !== created) return
                    socket = null
                    heartbeat?.cancel()
                    heartbeat = null
                    onStatus("WS_FAILURE ${t.message}")
                    scheduleReconnect()
                }
            })
            socket = created
        }
    }

    private fun sendSubscription(webSocket: WebSocket) {
        val array = JsonArray()
        tokenToCondition.keys.forEach { array.add(it) }
        val message = JsonObject().apply {
            addProperty("type", "MARKET")
            add("assets_ids", array)
        }
        webSocket.send(message.toString())
        onStatus("WS_SUBSCRIBED tokens=${tokenToCondition.size}")
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(10_000)
                if (socket !== webSocket) break
                runCatching { webSocket.send("PING") }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!running || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectJob = null
            if (running) connect()
        }
    }

    private fun parseAndDispatch(text: String) {
        if (text.isBlank() || text.equals("PONG", true) || text.equals("pong", true)) return
        val element = runCatching { JsonParser.parseString(text) }.getOrElse {
            onEvent(LiveFeedEvent.Unknown("parse_error", text.take(1000)))
            return
        }
        when {
            element.isJsonArray -> element.asJsonArray.forEach { item ->
                if (item.isJsonObject) handleObject(item.asJsonObject, text)
            }
            element.isJsonObject -> handleObject(element.asJsonObject, text)
            else -> onEvent(LiveFeedEvent.Unknown("non_object", text.take(1000)))
        }
    }

    private fun handleObject(obj: JsonObject, raw: String) {
        val eventType = obj.string("event_type") ?: return
        when (eventType) {
            "book" -> parseBook(obj, raw)?.let { onEvent(it) }
            "price_change" -> parsePriceChanges(obj, raw)?.let { onEvent(it) }
            "last_trade_price" -> parseTrade(obj, raw)?.let { onEvent(it) }
            else -> onEvent(LiveFeedEvent.Unknown(eventType, raw.take(2000)))
        }
    }
    private fun parseBook(obj: JsonObject, raw: String): LiveFeedEvent.BookUpdated? {
        val tokenId = obj.string("asset_id") ?: return null
        val ts = timestampMs(obj)
        val bids = parseLevels(obj.get("bids") as? JsonArray)
        val asks = parseLevels(obj.get("asks") as? JsonArray)
        return LiveFeedEvent.BookUpdated(MarketBook(tokenId, bids, asks, ts), raw)
    }

    private fun parsePriceChanges(obj: JsonObject, raw: String): LiveFeedEvent.PricesChanged? {
        val changes = obj.get("price_changes") as? JsonArray ?: return null
        val ts = timestampMs(obj)
        val parsed = changes.mapNotNull { element ->
            val change = element as? JsonObject ?: return@mapNotNull null
            val tokenId = change.string("asset_id") ?: return@mapNotNull null
            val price = change.string("price")?.toDoubleOrNull() ?: return@mapNotNull null
            val size = change.string("size")?.toDoubleOrNull() ?: return@mapNotNull null
            val side = change.string("side") ?: return@mapNotNull null
            if (price <= 0.0 || price >= 1.0) return@mapNotNull null
            BookPriceChange(tokenId, price, size, side, ts)
        }
        return if (parsed.isEmpty()) null else LiveFeedEvent.PricesChanged(parsed, raw)
    }

    private fun parseTrade(obj: JsonObject, raw: String): LiveFeedEvent.Trade? {
        val tokenId = obj.string("asset_id") ?: return null
        val conditionId = tokenToCondition[tokenId] ?: return null
        val price = obj.string("price")?.toDoubleOrNull() ?: return null
        val size = obj.string("size")?.toDoubleOrNull() ?: return null
        val side = obj.string("side") ?: return null
        if (price <= 0.0 || price >= 1.0 || size <= 0.0) return null
        return LiveFeedEvent.Trade(
            LiveTradePrint(conditionId, tokenId, timestampMs(obj), side.uppercase(), price, size, "ws", obj.string("transaction_hash") ?: obj.string("hash")),
            raw
        )
    }

    private fun parseLevels(array: JsonArray?): List<BookLevel> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            when {
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    val price = obj.string("price")?.toDoubleOrNull() ?: return@mapNotNull null
                    val size = obj.string("size")?.toDoubleOrNull() ?: return@mapNotNull null
                    if (price <= 0.0 || price >= 1.0 || size <= 0.0) null else BookLevel(price, size)
                }
                element.isJsonArray -> {
                    val values = element.asJsonArray
                    if (values.size() < 2) return@mapNotNull null
                    val price = values.get(0).asString.toDoubleOrNull() ?: return@mapNotNull null
                    val size = values.get(1).asString.toDoubleOrNull() ?: return@mapNotNull null
                    if (price <= 0.0 || price >= 1.0 || size <= 0.0) null else BookLevel(price, size)
                }
                else -> null
            }
        }
    }

    private fun timestampMs(obj: JsonObject): Long {
        val value = obj.get("timestamp") ?: return System.currentTimeMillis()
        val raw = when {
            value.isJsonPrimitive -> value.asString
            else -> return System.currentTimeMillis()
        }
        val parsed = raw.toDoubleOrNull() ?: return System.currentTimeMillis()
        return if (parsed > 10_000_000_000.0) parsed.toLong() else (parsed * 1000.0).toLong()
    }

    companion object {
        const val DEFAULT_WS_URL = "wss://ws-subscriptions-clob.polymarket.com/ws/market"
    }

    private fun JsonObject.string(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asString }.getOrNull()
    }
}