package com.wrbug.polymarketbot.research.livepaper

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

private const val MAX_GAMMA_DISCOVERY_PAGES = 100
private const val DEFAULT_GAMMA_DISCOVERY_PAGES = 50
private const val MAX_GAMMA_PAGE_LIMIT = 100
private const val DEFAULT_GAMMA_PAGE_LIMIT = 100
private const val PRICE_PREFILTER_TOLERANCE = 0.01
private const val GAMMA_DISCOVERY_PAGE_DELAY_MS = 100L

data class MarketDiscoveryTagStats(
    val tag: String,
    val pagesFetched: Int,
    val rowsScanned: Int,
    val qualifiedMarkets: Int,
    val exhausted: Boolean,
    val volumeFloorReached: Boolean,
    val failure: String?
)

data class MarketActivityDiagnostic(
    val conditionId: String,
    val question: String,
    val tag: String,
    val volume24hr: Double,
    val recentTrades30m: Int,
    val recentNotional30m: Double,
    val buyTrades30m: Int,
    val sellTrades30m: Int,
    val recentTrades2h: Int,
    val recentNotional2h: Double,
    val buyTrades2h: Int,
    val sellTrades2h: Int,
    val passed: Boolean,
    val rejectionReason: String?
)
data class MarketDiscoveryStats(
    val startedAt: Long,
    val finishedAt: Long,
    val maxPagesPerTag: Int,
    val pageLimit: Int,
    val maxMarketsRequested: Int,
    val candidateTarget: Int,
    val minVolume24hr: Double,
    val minRemainingMinutes: Int,
    val allowNegRisk: Boolean,
    val minFavoriteBidPrefilter: Double?,
    val maxFavoriteBidPrefilterExclusive: Double?,
    val uniqueMarkets: Int,
    val activityFilterApplied: Boolean,
    val activityChecked: Int,
    val activityPassed: Int,
    val returnedMarkets: Int,
    val stoppedReason: String,
    val tags: List<MarketDiscoveryTagStats>
)

data class MarketDiscoveryResult(
    val markets: List<LivePaperMarket>,
    val qualifiedMarkets: List<LivePaperMarket>,
    val activityPassedMarkets: List<LivePaperMarket>,
    val activityDiagnostics: List<MarketActivityDiagnostic>,
    val stats: MarketDiscoveryStats
)

class LivePaperDataClient(
    proxyUrl: String? = null,
    private val gammaBaseUrl: String = "https://gamma-api.polymarket.com",
    private val dataBaseUrl: String = "https://data-api.polymarket.com",
    private val clobBaseUrl: String = "https://clob.polymarket.com"
) {
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
        minRemainingMinutes: Int = 30,
        minRecentTrades30m: Int = 0,
        minRecentNotional30m: Double = 0.0,
        minRecentTradesPerSide30m: Int = 0,
        minRecentTrades2h: Int = 0,
        minRecentNotional2h: Double = 0.0,
        minRecentTradesPerSide2h: Int = 0,
        minPrice: Double = 0.0,
        maxPrice: Double = 1.0,
        maxPagesPerTag: Int = DEFAULT_GAMMA_DISCOVERY_PAGES,
        pageLimit: Int = DEFAULT_GAMMA_PAGE_LIMIT
    ): List<LivePaperMarket> = discoverMarketsDetailed(
        maxMarkets = maxMarkets,
        minVolume24hr = minVolume24hr,
        allowNegRisk = allowNegRisk,
        minRemainingMinutes = minRemainingMinutes,
        minRecentTrades30m = minRecentTrades30m,
        minRecentNotional30m = minRecentNotional30m,
        minRecentTradesPerSide30m = minRecentTradesPerSide30m,
        minRecentTrades2h = minRecentTrades2h,
        minRecentNotional2h = minRecentNotional2h,
        minRecentTradesPerSide2h = minRecentTradesPerSide2h,
        minPrice = minPrice,
        maxPrice = maxPrice,
        maxPagesPerTag = maxPagesPerTag,
        pageLimit = pageLimit
    ).markets

    suspend fun discoverMarketsDetailed(
        maxMarkets: Int,
        minVolume24hr: Double,
        allowNegRisk: Boolean = false,
        minRemainingMinutes: Int = 30,
        minRecentTrades30m: Int = 0,
        minRecentNotional30m: Double = 0.0,
        minRecentTradesPerSide30m: Int = 0,
        minRecentTrades2h: Int = 0,
        minRecentNotional2h: Double = 0.0,
        minRecentTradesPerSide2h: Int = 0,
        minPrice: Double = 0.0,
        maxPrice: Double = 1.0,
        maxPagesPerTag: Int = DEFAULT_GAMMA_DISCOVERY_PAGES,
        pageLimit: Int = DEFAULT_GAMMA_PAGE_LIMIT
    ): MarketDiscoveryResult = withContext(Dispatchers.IO) {
        require(maxPrice > minPrice) { "maxPrice must be greater than minPrice" }
        val startedAt = System.currentTimeMillis()
        val effectiveMaxPages = maxPagesPerTag.coerceIn(1, MAX_GAMMA_DISCOVERY_PAGES)
        val effectivePageLimit = pageLimit.coerceIn(1, MAX_GAMMA_PAGE_LIMIT)
        val candidateTarget = maxMarkets.coerceAtLeast(1)
        val minFavoriteBidPrefilter = if (minPrice > 0.0) {
            (minPrice - PRICE_PREFILTER_TOLERANCE).coerceAtLeast(0.0)
        } else {
            null
        }
        val maxFavoriteBidPrefilterExclusive = if (maxPrice < 1.0) {
            (maxPrice + PRICE_PREFILTER_TOLERANCE).coerceAtMost(1.0)
        } else {
            null
        }
        val cursors = listOf(Tag.POLITICS, Tag.GEOPOLITICS).map { TagCursor(it) }
        val found = linkedMapOf<String, LivePaperMarket>()
        lateinit var stoppedReason: String

        while (true) {
            var fetchedAny = false
            for (cursor in cursors) {
                if (cursor.exhausted || cursor.failure != null || cursor.pages >= effectiveMaxPages) continue
                val page = runCatching {
                    val url = gammaPageUrl(cursor.tag, cursor.nextCursor, effectivePageLimit)
                    gson.fromJson(executeGet(url), GammaMarketPageJson::class.java)
                        ?: GammaMarketPageJson(emptyList(), null)
                }.getOrElse {
                    cursor.failure = it.message ?: it.javaClass.simpleName
                    println("Gamma keyset discovery failed tag=${cursor.tag.label}: ${cursor.failure}")
                    null
                }
                if (page == null || cursor.failure != null) continue
                fetchedAny = true
                cursor.pages++
                val rows = page.markets.orEmpty()
                cursor.rowsScanned += rows.size
                if (rows.isEmpty()) {
                    cursor.exhausted = true
                    continue
                }
                for (raw in rows) {
                    val market = parseMarket(
                        raw = raw,
                        tag = cursor.tag.label,
                        minVolume24hr = minVolume24hr,
                        allowNegRisk = allowNegRisk,
                        minRemainingMinutes = minRemainingMinutes,
                        minFavoriteBid = minFavoriteBidPrefilter,
                        maxFavoriteBidExclusive = maxFavoriteBidPrefilterExclusive
                    ) ?: continue
                    if (found.putIfAbsent(market.conditionId, market) == null) {
                        cursor.qualifiedMarkets++
                    }
                }
                cursor.nextCursor = page.nextCursor?.takeIf { it.isNotBlank() }
                val reachedVolumeFloor = rows.lastOrNull()?.volume24hr?.let { it < minVolume24hr } == true
                if (cursor.nextCursor == null || reachedVolumeFloor) {
                    cursor.exhausted = true
                    cursor.volumeFloorReached = reachedVolumeFloor
                }
                delay(GAMMA_DISCOVERY_PAGE_DELAY_MS)
            }

            if (found.size >= candidateTarget) {
                stoppedReason = "candidate_target_reached"
                break
            }
            if (!fetchedAny) {
                stoppedReason = when {
                    cursors.all { it.exhausted } && cursors.any { it.volumeFloorReached } -> "volume_floor_reached"
                    cursors.all { it.exhausted } -> "all_tags_exhausted"
                    cursors.any { it.failure != null } -> "tag_error"
                    cursors.all { it.exhausted || it.pages >= effectiveMaxPages } -> "page_cap_reached"
                    else -> "no_more_pages"
                }
                break
            }
        }

        val activityFilterApplied = minRecentTrades30m > 0 ||
            minRecentNotional30m > 0.0 ||
            minRecentTradesPerSide30m > 0 ||
            minRecentTrades2h > 0 ||
            minRecentNotional2h > 0.0 ||
            minRecentTradesPerSide2h > 0
        val now = System.currentTimeMillis()
        val minTrades30m = minRecentTrades30m.coerceAtLeast(0)
        val minNotional30m = minRecentNotional30m.coerceAtLeast(0.0)
        val minPerSide30m = minRecentTradesPerSide30m.coerceAtLeast(0)
        val minTrades2h = minRecentTrades2h.coerceAtLeast(0)
        val minNotional2h = minRecentNotional2h.coerceAtLeast(0.0)
        val minPerSide2h = minRecentTradesPerSide2h.coerceAtLeast(0)
        val candidates = found.values.sortedByDescending { it.volume24hr }
        val activityDiagnostics = mutableListOf<MarketActivityDiagnostic>()
        val activityPassed = if (activityFilterApplied) {
            candidates.mapNotNull { market ->
                val allRecent = runCatching { getRecentTrades(market) }.getOrDefault(emptyList())
                val recent30m = allRecent.filter { it.timestamp >= now - 30 * 60 * 1000L }
                val recent2h = allRecent.filter { it.timestamp >= now - 120 * 60 * 1000L }
                val recentNotional30m = recent30m.sumOf { it.price * it.size }
                val recentNotional2h = recent2h.sumOf { it.price * it.size }
                val buy30m = recent30m.count { it.side.equals("BUY", true) }
                val sell30m = recent30m.count { it.side.equals("SELL", true) }
                val buy2h = recent2h.count { it.side.equals("BUY", true) }
                val sell2h = recent2h.count { it.side.equals("SELL", true) }
                val rejectionReason = when {
                    recent30m.size < minTrades30m -> "recent_trades_30m_below_min"
                    recentNotional30m < minNotional30m -> "recent_notional_30m_below_min"
                    buy30m < minPerSide30m -> "buy_trades_30m_below_min"
                    sell30m < minPerSide30m -> "sell_trades_30m_below_min"
                    recent2h.size < minTrades2h -> "recent_trades_2h_below_min"
                    recentNotional2h < minNotional2h -> "recent_notional_2h_below_min"
                    buy2h < minPerSide2h -> "buy_trades_2h_below_min"
                    sell2h < minPerSide2h -> "sell_trades_2h_below_min"
                    else -> null
                }
                activityDiagnostics += MarketActivityDiagnostic(
                    conditionId = market.conditionId,
                    question = market.question,
                    tag = market.tag,
                    volume24hr = market.volume24hr,
                    recentTrades30m = recent30m.size,
                    recentNotional30m = recentNotional30m,
                    buyTrades30m = buy30m,
                    sellTrades30m = sell30m,
                    recentTrades2h = recent2h.size,
                    recentNotional2h = recentNotional2h,
                    buyTrades2h = buy2h,
                    sellTrades2h = sell2h,
                    passed = rejectionReason == null,
                    rejectionReason = rejectionReason
                )
                if (rejectionReason == null) {
                    Triple(market, recentNotional30m, recent30m.size)
                } else {
                    null
                }
            }.sortedWith(
                compareByDescending<Triple<LivePaperMarket, Double, Int>> { it.second }
                    .thenByDescending { it.third }
                    .thenByDescending { it.first.volume24hr }
            ).map { it.first }
        } else {
            candidates
        }
        val returned = activityPassed.take(maxMarkets.coerceAtLeast(1))
        val finishedAt = System.currentTimeMillis()
        val stats = MarketDiscoveryStats(
            startedAt = startedAt,
            finishedAt = finishedAt,
            maxPagesPerTag = effectiveMaxPages,
            pageLimit = effectivePageLimit,
            maxMarketsRequested = maxMarkets,
            candidateTarget = candidateTarget,
            minVolume24hr = minVolume24hr,
            minRemainingMinutes = minRemainingMinutes,
            allowNegRisk = allowNegRisk,
            minFavoriteBidPrefilter = minFavoriteBidPrefilter,
            maxFavoriteBidPrefilterExclusive = maxFavoriteBidPrefilterExclusive,
            uniqueMarkets = found.size,
            activityFilterApplied = activityFilterApplied,
            activityChecked = if (activityFilterApplied) candidates.size else 0,
            activityPassed = activityPassed.size,
            returnedMarkets = returned.size,
            stoppedReason = stoppedReason,
            tags = cursors.map { cursor ->
                MarketDiscoveryTagStats(
                    tag = cursor.tag.label,
                    pagesFetched = cursor.pages,
                    rowsScanned = cursor.rowsScanned,
                    qualifiedMarkets = cursor.qualifiedMarkets,
                    exhausted = cursor.exhausted,
                    volumeFloorReached = cursor.volumeFloorReached,
                    failure = cursor.failure
                )
            }
        )
        println(
            "marketDiscovery pages=${stats.tags.joinToString { "${it.tag}:${it.pagesFetched}" }} " +
                "qualified=${stats.uniqueMarkets} activityChecked=${stats.activityChecked} " +
                "activityPassed=${stats.activityPassed} returned=${stats.returnedMarkets} " +
                "stopped=${stats.stoppedReason}"
        )
        MarketDiscoveryResult(
            markets = returned,
            qualifiedMarkets = candidates,
            activityPassedMarkets = activityPassed,
            activityDiagnostics = activityDiagnostics,
            stats = stats
        )
    }
    suspend fun getBook(tokenId: String): MarketBook? = withContext(Dispatchers.IO) {
        val url = clobBaseUrl.toHttpUrl().newBuilder()
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
        val url = clobBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("tick-size")
            .addQueryParameter("token_id", tokenId)
            .build()
        runCatching {
            val raw = gson.fromJson(executeGet(url), TickSizeJson::class.java)
            raw.minimumTickSize?.toDoubleOrNull()?.takeIf { it > 0.0 }
        }.getOrNull()
    }

    suspend fun getRecentTrades(market: LivePaperMarket): List<LiveTradePrint> = withContext(Dispatchers.IO) {
        val url = dataBaseUrl.toHttpUrl().newBuilder()
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
    private fun gammaPageUrl(tag: Tag, afterCursor: String?, pageLimit: Int): HttpUrl {
        val builder = gammaBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("markets")
            .addPathSegment("keyset")
            .addQueryParameter("active", "true")
            .addQueryParameter("closed", "false")
            .addQueryParameter("tag_id", tag.id)
            .addQueryParameter("limit", pageLimit.toString())
            .addQueryParameter("order", "volume24hr")
            .addQueryParameter("ascending", "false")
        if (!afterCursor.isNullOrBlank()) builder.addQueryParameter("after_cursor", afterCursor)
        return builder.build()
    }

    private fun parseMarket(
        raw: GammaMarketJson,
        tag: String,
        minVolume24hr: Double,
        allowNegRisk: Boolean,
        minRemainingMinutes: Int,
        minFavoriteBid: Double?,
        maxFavoriteBidExclusive: Double?
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
        if (minFavoriteBid != null && favoriteBid < minFavoriteBid) return null
        if (maxFavoriteBidExclusive != null && favoriteBid >= maxFavoriteBidExclusive) return null
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

    private data class TagCursor(
        val tag: Tag,
        var nextCursor: String? = null,
        var pages: Int = 0,
        var rowsScanned: Int = 0,
        var qualifiedMarkets: Int = 0,
        var exhausted: Boolean = false,
        var volumeFloorReached: Boolean = false,
        var failure: String? = null
    )

    private data class GammaMarketPageJson(
        val markets: List<GammaMarketJson>?,
        @SerializedName("next_cursor") val nextCursor: String?
    )

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
