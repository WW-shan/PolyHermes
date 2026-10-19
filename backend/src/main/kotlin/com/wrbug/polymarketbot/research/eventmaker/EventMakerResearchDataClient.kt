package com.wrbug.polymarketbot.research.eventmaker

import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Research-only data client. It intentionally does not touch the application database or
 * the user's live accounts.
 */
class EventMakerResearchDataClient(
    proxyUrl: String? = null,
    private val concurrency: Int = 6,
    private val progress: (String) -> Unit = {}
) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .apply {
            if (!proxyUrl.isNullOrBlank()) {
                val uri = URI(proxyUrl)
                val port = if (uri.port > 0) uri.port else 10808
                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, port)))
            }
        }
        .build()

    suspend fun fetchMarkets(
        fromDate: LocalDate,
        toDate: LocalDate,
        minVolume: Double,
        maxMarkets: Int,
        entryHorizonSeconds: Long
    ): List<EventMakerMarket> = withContext(Dispatchers.IO) {
        val markets = mutableMapOf<String, EventMakerMarket>()
        var month = YearMonth.from(fromDate)
        val lastMonth = YearMonth.from(toDate)
        for (tag in listOf(Tag.POLITICS, Tag.GEOPOLITICS)) {
            while (!month.isAfter(lastMonth)) {
                val monthStart = month.atDay(1)
                val monthEnd = month.plusMonths(1).atDay(1)
                var offset = 0
                while (offset <= 5_000) {
                    val page = runCatching {
                        fetchGammaPage(tag, monthStart, monthEnd, offset, minVolume)
                    }.onFailure {
                        progress("Gamma failed tag=${tag.label} month=$month offset=$offset: ${it.message}")
                    }.getOrNull() ?: break
                    if (page.isEmpty()) break
                    for (raw in page) {
                        val parsed = parseMarket(raw, tag, minVolume) ?: continue
                        markets[parsed.conditionId] = parsed
                    }
                    if (page.size < 100) break
                    offset += 100
                }
                month = month.plusMonths(1)
            }
            month = YearMonth.from(fromDate)
        }

        val sampled = sampleEvenly(markets.values.sortedBy { it.closedTimestamp }, maxMarkets)
        progress("markets=${markets.size} sampled=${sampled.size}; fetching trade history")
        val counter = AtomicInteger(0)
        val failedTradeFetches = AtomicInteger(0)
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        coroutineScope {
            sampled.map { market ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val trades = runCatching {
                            fetchTrades(market.conditionId, market.closedTimestamp, entryHorizonSeconds)
                        }.onFailure {
                            failedTradeFetches.incrementAndGet()
                            progress("trades failed ${market.conditionId}: ${it.message}")
                        }.getOrDefault(emptyList())
                        val done = counter.incrementAndGet()
                        if (done % 20 == 0 || done == sampled.size) {
                            progress("trade history $done/${sampled.size} failures=${failedTradeFetches.get()}")
                        }
                        delay(35)
                        market.copy(trades = trades)
                    }
                }
            }.awaitAll()
        }
    }

    private fun fetchGammaPage(
        tag: Tag,
        start: LocalDate,
        endExclusive: LocalDate,
        offset: Int,
        minVolume: Double
    ): List<GammaMarketJson> {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("gamma-api.polymarket.com")
            .addPathSegment("markets")
            .addQueryParameter("tag_id", tag.id)
            .addQueryParameter("closed", "true")
            .addQueryParameter("end_date_min", start.toString())
            .addQueryParameter("end_date_max", endExclusive.toString())
            .addQueryParameter("volume_num_min", minVolume.toString())
            .addQueryParameter("limit", "100")
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("order", "endDate")
            .addQueryParameter("ascending", "false")
            .build()
        return executeGet(url).let { body ->
            gson.fromJson(body, Array<GammaMarketJson>::class.java)?.toList() ?: emptyList()
        }
    }

    private suspend fun fetchTrades(
        conditionId: String,
        closedTimestamp: Long,
        entryHorizonSeconds: Long
    ): List<EventMakerTrade> {
        val start = (closedTimestamp - entryHorizonSeconds - 3_600L).coerceAtLeast(0L)
        var cursorEnd = closedTimestamp
        val result = mutableMapOf<String, EventMakerTrade>()
        var pages = 0
        while (pages < 20) {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("data-api.polymarket.com")
                .addPathSegment("trades")
                .addQueryParameter("market", conditionId)
                .addQueryParameter("start", start.toString())
                .addQueryParameter("end", cursorEnd.toString())
                .addQueryParameter("limit", "500")
                .addQueryParameter("offset", "0")
                .build()
            val rows = executeGet(url).let { body ->
                gson.fromJson(body, Array<TradeJson>::class.java)?.toList() ?: emptyList()
            }
            if (rows.isEmpty()) break
            for (row in rows) {
                val ts = row.timestamp ?: continue
                val price = row.price ?: continue
                val size = row.size ?: continue
                val outcome = row.outcomeIndex ?: continue
                if (ts < start || ts > closedTimestamp || outcome !in 0..1 || price <= 0.0 || price >= 1.0 || size <= 0.0) continue
                val key = "${row.transactionHash ?: ""}|$ts|$outcome|$price|$size"
                result[key] = EventMakerTrade(ts, outcome, price, size, row.side?.uppercase())
            }
            val oldest = rows.minOfOrNull { it.timestamp ?: Long.MAX_VALUE } ?: break
            if (rows.size < 500 || oldest <= start) break
            cursorEnd = oldest - 1
            pages++
            delay(35)
        }
        return result.values.sortedBy { it.timestamp }
    }

    private fun parseMarket(raw: GammaMarketJson, tag: Tag, minVolume: Double): EventMakerMarket? {
        val conditionId = raw.conditionId ?: return null
        val outcomes = readStringArray(raw.outcomes)
        val prices = readStringArray(raw.outcomePrices)
        val tokens = readStringArray(raw.clobTokenIds)
        if (outcomes.size != 2 || prices.size != 2 || tokens.size != 2) return null
        if (raw.negRisk == true) return null
        val p0 = prices[0].toDoubleOrNull() ?: return null
        val p1 = prices[1].toDoubleOrNull() ?: return null
        val winner = when {
            p0 >= 0.99 && p1 <= 0.01 -> 0
            p1 >= 0.99 && p0 <= 0.01 -> 1
            else -> return null
        }
        val volume = raw.volumeNum ?: raw.volume ?: return null
        if (volume < minVolume) return null
        val closed = parseTimestamp(raw.closedTime ?: raw.endDate) ?: return null
        return EventMakerMarket(
            conditionId = conditionId,
            question = raw.question ?: conditionId,
            tag = tag.label,
            volume = volume,
            closedTimestamp = closed,
            winnerIndex = winner,
            tickSize = raw.orderPriceMinTickSize?.takeIf { it > 0.0 } ?: 0.001,
            trades = emptyList()
        )
    }

    private fun executeGet(url: HttpUrl): String {
        var lastError: Exception? = null
        val maxAttempts = 6
        for (attempt in 0 until maxAttempts) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "PolyHermes-EventMakerResearch/1.0")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return response.body?.string() ?: ""
                    val code = response.code
                    if (code == 429 || code >= 500) {
                        lastError = IllegalStateException("HTTP $code for $url")
                    } else {
                        throw NonRetryableHttpException("HTTP $code for $url")
                    }
                }
            } catch (e: NonRetryableHttpException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt < maxAttempts - 1) {
                Thread.sleep(500L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("request failed: $url")
    }

    private fun readStringArray(value: JsonElement?): List<String> {
        if (value == null || value.isJsonNull) return emptyList()
        if (value.isJsonArray) return value.asJsonArray.mapNotNull { it.takeUnless { e -> e.isJsonNull }?.asString }
        if (value.isJsonPrimitive) {
            val text = value.asString
            return runCatching { gson.fromJson(text, Array<String>::class.java)?.toList() ?: emptyList() }.getOrDefault(emptyList())
        }
        return emptyList()
    }

    private fun parseTimestamp(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            OffsetDateTime.parse(value.replaceFirst(' ', 'T')).toEpochSecond()
        }.getOrNull() ?: runCatching {
            OffsetDateTime.parse(value).toEpochSecond()
        }.getOrNull()
    }

    private fun sampleEvenly(markets: List<EventMakerMarket>, maxMarkets: Int): List<EventMakerMarket> {
        if (maxMarkets <= 0 || markets.size <= maxMarkets) return markets
        return (0 until maxMarkets).map { i ->
            markets[(i.toLong() * markets.size / maxMarkets).toInt().coerceIn(0, markets.lastIndex)]
        }.distinctBy { it.conditionId }
    }

    private enum class Tag(val id: String, val label: String) {
        POLITICS("2", "Politics"),
        GEOPOLITICS("100265", "Geopolitics")
    }

    private data class GammaMarketJson(
        val conditionId: String?,
        val question: String?,
        val closedTime: String?,
        val endDate: String?,
        val volume: Double?,
        val volumeNum: Double?,
        val outcomes: JsonElement?,
        val outcomePrices: JsonElement?,
        val clobTokenIds: JsonElement?,
        val negRisk: Boolean?,
        val orderPriceMinTickSize: Double?
    )

    private class NonRetryableHttpException(message: String) : Exception(message)

    private data class TradeJson(
        val timestamp: Long?,
        val outcomeIndex: Int?,
        val price: Double?,
        val size: Double?,
        val side: String?,
        val transactionHash: String?
    )
}