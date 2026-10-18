package com.wrbug.polymarketbot.research.livepaper

import com.google.gson.Gson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Executors

class LivePaperDataClientDiscoveryTest {
    private val gson = Gson()
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var server: HttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var baseUrl: String
    private lateinit var marketsResponder: (String?, String?, Int) -> FixturePage

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            runCatching { handle(exchange) }.onFailure { error ->
                respond(exchange, 500, mapOf("error" to (error.message ?: "test server failure")))
            }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
        marketsResponder = { tag, cursor, limit -> defaultPoliticsRows(tag, cursor, limit) }
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun `pagination reaches a price-band market below the first forty high-volume rows`() = runBlocking {
        val client = client()
        try {
            val result = client.discoverMarketsDetailed(
                maxMarkets = 1,
                minVolume24hr = 1_000.0,
                minPrice = 0.97,
                maxPrice = 0.99,
                maxPagesPerTag = 3,
                pageLimit = 50
            )

            assertEquals(listOf("band-after-forty"), result.markets.map { it.conditionId })
            assertEquals(1, result.stats.uniqueMarkets)
            assertEquals("candidate_target_reached", result.stats.stoppedReason)
            assertEquals(2, result.stats.tags.first { it.tag == "Politics" }.pagesFetched)
            assertTrue(requests.any { it.contains("/markets/keyset") && it.contains("tag_id=2") && it.contains("after_cursor=page-2") })
        } finally {
            client.close()
        }
    }

    @Test
    fun `zero activity thresholds skip the data api`() = runBlocking {
        val client = client()
        try {
            marketsResponder = { tag, _, _ ->
                if (tag == "2") FixturePage(listOf(market("active-candidate", 0.98, 0.985, 250_000.0)), null) else FixturePage(emptyList(), null)
            }
            val result = client.discoverMarketsDetailed(
                maxMarkets = 1,
                minVolume24hr = 1_000.0,
                minPrice = 0.97,
                maxPrice = 0.99,
                maxPagesPerTag = 1,
                pageLimit = 100
            )

            assertEquals(1, result.markets.size)
            assertEquals(false, result.stats.activityFilterApplied)
            assertEquals(0, result.stats.activityChecked)
            assertTrue(result.activityDiagnostics.isEmpty())
            assertTrue(requests.none { it.contains("/trades") })
        } finally {
            client.close()
        }
    }

    @Test
    fun `activity thresholds reject a market without recent prints`() = runBlocking {
        val client = client()
        try {
            marketsResponder = { tag, _, _ ->
                if (tag == "2") FixturePage(listOf(market("inactive-candidate", 0.98, 0.985, 250_000.0)), null) else FixturePage(emptyList(), null)
            }
            val result = client.discoverMarketsDetailed(
                maxMarkets = 1,
                minVolume24hr = 1_000.0,
                minRecentTrades30m = 3,
                minRecentTradesPerSide30m = 1,
                minPrice = 0.97,
                maxPrice = 0.99,
                maxPagesPerTag = 1,
                pageLimit = 100
            )

            assertTrue(result.markets.isEmpty())
            assertTrue(result.stats.activityFilterApplied)
            assertEquals(1, result.stats.activityChecked)
            assertEquals(0, result.stats.activityPassed)
            assertEquals("recent_trades_30m_below_min", result.activityDiagnostics.single().rejectionReason)
            assertTrue(requests.any { it.contains("/trades") })
        } finally {
            client.close()
        }
    }

    @Test
    fun `page cap is reported instead of silently claiming exhaustion`() = runBlocking {
        val client = client()
        try {
            marketsResponder = { tag, cursor, _ ->
                if (tag == "2") {
                    FixturePage(listOf(market("out-of-band-$cursor", 0.50, 0.51, 9_000_000.0)), cursor ?: "cap-2")
                } else {
                    FixturePage(emptyList(), null)
                }
            }
            val result = client.discoverMarketsDetailed(
                maxMarkets = 2,
                minVolume24hr = 1_000.0,
                minPrice = 0.97,
                maxPrice = 0.99,
                maxPagesPerTag = 2,
                pageLimit = 1
            )

            assertEquals("page_cap_reached", result.stats.stoppedReason)
            assertEquals(2, result.stats.tags.first { it.tag == "Politics" }.pagesFetched)
            assertTrue(!result.stats.tags.first { it.tag == "Politics" }.exhausted)
            assertTrue(result.markets.isEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun `volume floor stops pagination before lower volume pages`() = runBlocking {
        val client = client()
        try {
            marketsResponder = { tag, _, _ ->
                if (tag == "2") {
                    FixturePage(listOf(market("below-volume", 0.98, 0.985, 500.0)), "more-pages")
                } else {
                    FixturePage(emptyList(), null)
                }
            }
            val result = client.discoverMarketsDetailed(
                maxMarkets = 10,
                minVolume24hr = 1_000.0,
                minPrice = 0.97,
                maxPrice = 0.99,
                maxPagesPerTag = 50,
                pageLimit = 100
            )

            assertEquals("volume_floor_reached", result.stats.stoppedReason)
            assertTrue(result.stats.tags.first { it.tag == "Politics" }.volumeFloorReached)
            assertEquals(1, result.stats.tags.first { it.tag == "Politics" }.pagesFetched)
            assertEquals(1, requests.count { it.contains("/markets/keyset") && it.contains("tag_id=2") })
        } finally {
            client.close()
        }
    }
    private fun client(): LivePaperDataClient = LivePaperDataClient(
        gammaBaseUrl = baseUrl,
        dataBaseUrl = baseUrl,
        clobBaseUrl = baseUrl
    )

    private fun handle(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/markets", "/markets/keyset" -> handleMarkets(exchange)
            "/trades" -> respond(exchange, 200, emptyList<Any>())
            else -> respond(exchange, 404, mapOf("error" to "not found"))
        }
    }

    private fun handleMarkets(exchange: HttpExchange) {
        val query = queryParams(exchange)
        val tag = query["tag_id"]
        val cursor = query["after_cursor"]
        val limit = query["limit"]?.toIntOrNull() ?: 100
        val page = marketsResponder(tag, cursor, limit)
        respond(
            exchange,
            200,
            mapOf(
                "markets" to page.markets,
                "next_cursor" to page.nextCursor
            )
        )
    }

    private fun defaultPoliticsRows(tag: String?, cursor: String?, limit: Int): FixturePage = when {
        tag != "2" -> FixturePage(emptyList(), null)
        cursor == null && limit >= 50 -> FixturePage(
            (1..50).map { index ->
                market(
                    id = "high-volume-outside-band-$index",
                    bid = 0.50,
                    ask = 0.51,
                    volume = 10_000_000.0 - index
                )
            },
            "page-2"
        )
        cursor == null -> FixturePage(
            listOf(market("high-volume-outside-band-1", 0.50, 0.51, 10_000_000.0)),
            null
        )
        cursor == "page-2" -> FixturePage(
            listOf(market("band-after-forty", 0.98, 0.985, 250_000.0)),
            null
        )
        else -> FixturePage(emptyList(), null)
    }

    private data class FixturePage(
        val markets: List<Map<String, Any>>,
        val nextCursor: String?
    )

    private fun market(id: String, bid: Double, ask: Double, volume: Double): Map<String, Any> = mapOf(
        "conditionId" to id,
        "question" to id,
        "volume24hr" to volume,
        "endDate" to "2099-12-31T00:00:00Z",
        "bestBid" to bid,
        "bestAsk" to ask,
        "outcomes" to listOf("Yes", "No"),
        "clobTokenIds" to listOf("$id-fav", "$id-other"),
        "negRisk" to false,
        "enableOrderBook" to true,
        "orderPriceMinTickSize" to 0.001
    )

    private fun queryParams(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery.orEmpty().split("&").filter { "=" in it }.associate { part ->
            val pieces = part.split("=", limit = 2)
            pieces[0] to pieces.getOrElse(1) { "" }
        }

    private fun respond(exchange: HttpExchange, status: Int, body: Any) {
        val bytes = gson.toJson(body).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}