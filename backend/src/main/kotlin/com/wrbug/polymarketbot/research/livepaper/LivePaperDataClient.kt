package com.wrbug.polymarketbot.research.livepaper

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class LivePaperDataClient(proxyUrl: String? = null) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .apply {
            if (!proxyUrl.isNullOrBlank()) {
                val uri = URI(proxyUrl)
                val port = if (uri.port > 0) uri.port else 10808
                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, port)))
            }
        }
        .build()

    suspend fun discoverMarkets(
        maxMarkets: Int,
        minVolume24hr: Double,
        allowNegRisk: Boolean = false,
        minRemainingMinutes: Int = 30
    ): List<LivePaperMarket> =
        withContext(Dispatchers.IO) {
            val found = linkedMapOf<String, LivePaperMarket>()
            for (tag in listOf(Tag.POLITICS, Tag.GEOPOLITICS)) {
                val url = HttpUrl.Builder()
                    .scheme("https")
                    .host("gamma-api.polymarket.com")
                    .addPathSegment("markets")
                    .addQueryParameter("active", "true")
                    .addQueryParameter("closed", "false")
                    .addQueryParameter("tag_id", tag.id)
                    .addQueryParameter("limit", "100")
                    .addQueryParameter("order", "volume24hr")
                    .addQueryParameter("ascending", "false")
                    .build()
                val rows = runCatching {
                    gson.fromJson(executeGet(url), Array<GammaMarketJson>::class.java)?.toList() ?: emptyList()
                }.getOrElse {
                    println("Gamma discovery failed tag=${tag.label}: ${it.message}")
                    emptyList()
                }
                for (raw in rows) {
                    val market = parseMarket(raw, tag.label, minVolume24hr, allowNegRisk, minRemainingMinutes) ?: continue
                    found.putIfAbsent(market.conditionId, market)
                }
            }
            val now = System.currentTimeMillis()
            val ranked = found.values.sortedByDescending { it.volume24hr }.take(40).map { market ->
                val recent = runCatching { getRecentTrades(market) }.getOrDefault(emptyList())
                    .filter { it.timestamp >= now - 30 * 60 * 1000L }
                val recentNotional = recent.sumOf { it.price * it.size }
                Triple(market, recentNotional, recent.size)
            }.sortedWith(
                compareByDescending<Triple<LivePaperMarket, Double, Int>> { it.second }
                    .thenByDescending { it.third }
                    .thenByDescending { it.first.volume24hr }
            )
            ranked.take(maxMarkets.coerceAtLeast(1)).map { it.first }
        }

    suspend fun getBook(tokenId: String): MarketBook? = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("clob.polymarket.com")
            .addPathSegment("book")
            .addQueryParameter("token_id", tokenId)
            .build()
        runCatching {
            val raw = gson.fromJson(executeGet(url), BookJson::class.java)
            fun levels(values: List<BookLevelJson>?): List<BookLevel> = values.orEmpty().mapNotNull { level ->
                val price = level.price?.toDoubleOrNull() ?: return@mapNotNull null
                val size = level.size?.toDoubleOrNull() ?: return@mapNotNull null
                if (price <= 0.0 || price >= 1.0 || size <= 0.0) null else BookLevel(price, size)
            }
            MarketBook(tokenId, levels(raw.bids), levels(raw.asks), System.currentTimeMillis())
        }.getOrNull()
    }

    suspend fun getTickSize(tokenId: String): Double? = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("clob.polymarket.com")
            .addPathSegment("tick-size")
            .addQueryParameter("token_id", tokenId)
            .build()
        runCatching {
            val raw = gson.fromJson(executeGet(url), TickSizeJson::class.java)
            raw.minimumTickSize?.toDoubleOrNull()?.takeIf { it > 0.0 }
        }.getOrNull()
    }

    suspend fun getRecentTrades(market: LivePaperMarket): List<LiveTradePrint> = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("data-api.polymarket.com")
            .addPathSegment("trades")
            .addQueryParameter("market", market.conditionId)
            .addQueryParameter("limit", "200")
            .addQueryParameter("offset", "0")
            .build()
        runCatching {
            val rows = gson.fromJson(executeGet(url), Array<TradeJson>::class.java)?.toList() ?: emptyList()
            rows.mapNotNull { row ->
                val timestamp = row.timestamp ?: return@mapNotNull null
                val outcomeIndex = row.outcomeIndex ?: return@mapNotNull null
                val tokenId = market.tokenIds.getOrNull(outcomeIndex) ?: return@mapNotNull null
                val price = row.price ?: return@mapNotNull null
                val size = row.size ?: return@mapNotNull null
                val side = row.side?.uppercase() ?: return@mapNotNull null
                if (price <= 0.0 || price >= 1.0 || size <= 0.0) return@mapNotNull null
                LiveTradePrint(market.conditionId, tokenId, timestamp * 1000L, side, price, size, "data-api", row.transactionHash)
            }.sortedBy { it.timestamp }
        }.getOrElse {
            println("Data API trades failed market=${market.conditionId}: ${it.message}")
            emptyList()
        }
    }
    private fun parseMarket(
        raw: GammaMarketJson,
        tag: String,
        minVolume24hr: Double,
        allowNegRisk: Boolean,
        minRemainingMinutes: Int
    ): LivePaperMarket? {
        val conditionId = raw.conditionId ?: return null
        if ((!allowNegRisk && raw.negRisk == true) || raw.enableOrderBook == false) return null
        val outcomes = readStringArray(raw.outcomes)
        val tokens = readStringArray(raw.clobTokenIds)
        if (outcomes.size != 2 || tokens.size != 2) return null
        val volume = raw.volume24hr ?: return null
        if (volume < minVolume24hr) return null
        val end = parseTimestamp(raw.endDate) ?: return null
        if (end <= System.currentTimeMillis() / 1000 + minRemainingMinutes.coerceAtLeast(1) * 60L) return null
        val bid0 = raw.bestBid ?: return null
        val ask0 = raw.bestAsk ?: return null
        if (bid0 <= 0.0 || ask0 <= bid0 || ask0 >= 1.0) return null
        val favoriteIndex = if (bid0 >= 0.5) 0 else 1
        val favoriteBid = if (favoriteIndex == 0) bid0 else 1.0 - ask0
        val favoriteAsk = if (favoriteIndex == 0) ask0 else 1.0 - bid0
        if (favoriteBid < 0.88 || favoriteAsk >= 1.0) return null
        return LivePaperMarket(
            conditionId = conditionId,
            question = raw.question ?: conditionId,
            tag = tag,
            volume24hr = volume,
            endTimestamp = end,
            tickSize = raw.orderPriceMinTickSize?.takeIf { it > 0.0 } ?: 0.001,
            outcomes = outcomes,
            tokenIds = tokens,
            favoriteIndex = favoriteIndex
        )
    }

    private fun readStringArray(value: JsonElement?): List<String> {
        if (value == null || value.isJsonNull) return emptyList()
        if (value.isJsonArray) return value.asJsonArray.mapNotNull { it.takeUnless { e -> e.isJsonNull }?.asString }
        if (value.isJsonPrimitive) {
            return runCatching {
                gson.fromJson(value.asString, Array<String>::class.java)?.toList() ?: emptyList()
            }.getOrDefault(emptyList())
        }
        return emptyList()
    }

    private fun parseTimestamp(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value).toEpochSecond() }.getOrNull()
    }

    private fun executeGet(url: HttpUrl): String {
        var lastError: Exception? = null
        repeat(4) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "PolyHermes-LivePaper/1.0")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return response.body?.string() ?: ""
                    lastError = IllegalStateException("HTTP ${response.code} for $url")
                }
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt < 3) Thread.sleep(400L * (attempt + 1))
        }
        throw lastError ?: IllegalStateException("request failed: $url")
    }

    fun close() {
        http.dispatcher.executorService.shutdownNow()
        http.connectionPool.evictAll()
    }

    private enum class Tag(val id: String, val label: String) {
        POLITICS("2", "Politics"),
        GEOPOLITICS("100265", "Geopolitics")
    }

    private data class GammaMarketJson(
        val conditionId: String?,
        val question: String?,
        val volume24hr: Double?,
        val endDate: String?,
        val bestBid: Double?,
        val bestAsk: Double?,
        val outcomes: JsonElement?,
        val clobTokenIds: JsonElement?,
        val negRisk: Boolean?,
        val enableOrderBook: Boolean?,
        val orderPriceMinTickSize: Double?
    )

    private data class TickSizeJson(
        @SerializedName("minimum_tick_size") val minimumTickSize: String?
    )

    private data class BookJson(val bids: List<BookLevelJson>?, val asks: List<BookLevelJson>?)
    private data class BookLevelJson(val price: String?, val size: String?)

    private data class TradeJson(
        val timestamp: Long?,
        val outcomeIndex: Int?,
        val price: Double?,
        val size: Double?,
        val side: String?,
        val transactionHash: String?
    )
}
