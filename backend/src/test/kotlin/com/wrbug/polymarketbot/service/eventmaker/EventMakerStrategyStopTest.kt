package com.wrbug.polymarketbot.service.eventmaker

import com.sun.net.httpserver.HttpServer
import com.wrbug.polymarketbot.entity.ProxyConfig
import com.wrbug.polymarketbot.research.livepaper.LivePaperDataClient
import com.wrbug.polymarketbot.util.ProxyConfigProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EventMakerStrategyStopTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `stop aborts an in-flight discovery call instead of waiting for the http timeout`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestStarted = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { exchange ->
            requestStarted.countDown()
            runCatching { Thread.sleep(30_000) }
            runCatching {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.close()
            }
        }
        server.start()

        val service = service()
        service.dataClientFactory = { proxy ->
            LivePaperDataClient(
                proxyUrl = proxy,
                gammaBaseUrl = "http://127.0.0.1:${server.address.port}",
                dataBaseUrl = "http://127.0.0.1:${server.address.port}",
                clobBaseUrl = "http://127.0.0.1:${server.address.port}"
            )
        }
        try {
            val started = service.start(EventMakerConfig(mode = EventMakerMode.SHADOW))
            assertTrue(started.isSuccess)
            assertTrue(requestStarted.await(10, TimeUnit.SECONDS), "discovery request never started")

            val begin = System.currentTimeMillis()
            service.stop()
            val elapsed = System.currentTimeMillis() - begin

            assertTrue(elapsed < 10_000, "stop took ${elapsed}ms; blocked HTTP call was not aborted")
            assertTrue(!service.currentStatus().running)
        } finally {
            service.stop()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `system proxy config is used when no explicit event maker proxy is set`() {
        ProxyConfigProvider.setProxyConfig(
            ProxyConfig(type = "HTTP", enabled = true, host = "127.0.0.1", port = 10809)
        )
        val service = service()
        var seenProxy: String? = null
        service.dataClientFactory = { proxy ->
            seenProxy = proxy
            LivePaperDataClient(
                proxyUrl = proxy,
                gammaBaseUrl = "http://127.0.0.1:1",
                dataBaseUrl = "http://127.0.0.1:1",
                clobBaseUrl = "http://127.0.0.1:1"
            )
        }
        try {
            service.start(EventMakerConfig(mode = EventMakerMode.SHADOW))
            assertEquals("http://127.0.0.1:10809", seenProxy)
        } finally {
            service.stop()
            ProxyConfigProvider.setProxyConfig(null)
        }
    }

    @Test
    fun `explicit event maker proxy wins over the system proxy config`() {
        ProxyConfigProvider.setProxyConfig(
            ProxyConfig(type = "HTTP", enabled = true, host = "127.0.0.1", port = 10809)
        )
        val service = service(proxyUrl = "http://127.0.0.1:10808")
        var seenProxy: String? = null
        service.dataClientFactory = { proxy ->
            seenProxy = proxy
            LivePaperDataClient(
                proxyUrl = proxy,
                gammaBaseUrl = "http://127.0.0.1:1",
                dataBaseUrl = "http://127.0.0.1:1",
                clobBaseUrl = "http://127.0.0.1:1"
            )
        }
        try {
            service.start(EventMakerConfig(mode = EventMakerMode.SHADOW))
            assertEquals("http://127.0.0.1:10808", seenProxy)
        } finally {
            service.stop()
            ProxyConfigProvider.setProxyConfig(null)
        }
    }

    private fun service(proxyUrl: String = "") = EventMakerStrategyService(
        proxyUrl = proxyUrl,
        riskPolicy = EventMakerRiskPolicy(),
        quotePlanner = EventMakerQuotePlanner(),
        executionPort = NoopExecutionPort(),
        shadowExecutionPort = EventMakerShadowExecutionPort(),
        stateStore = EventMakerStateStore(tempDir.resolve("state.json").toString())
    )

    private class NoopExecutionPort : EventMakerExecutionPort {
        override suspend fun validateAccount(accountId: Long): Result<Unit> = Result.success(Unit)

        override suspend fun listOpenOrderIds(accountId: Long): Result<Set<String>> =
            Result.success(emptySet())

        override suspend fun listOpenPositions(accountId: Long): Result<Map<String, java.math.BigDecimal>> =
            Result.success(emptyMap())

        override suspend fun place(
            accountId: Long,
            market: com.wrbug.polymarketbot.research.livepaper.LivePaperMarket,
            intent: EventMakerQuoteIntent
        ): Result<EventMakerLiveOrder> = Result.failure(UnsupportedOperationException())

        override suspend fun getOrder(accountId: Long, orderId: String): Result<EventMakerLiveOrder> =
            Result.failure(UnsupportedOperationException())

        override suspend fun cancel(accountId: Long, orderId: String): Result<Unit> =
            Result.failure(UnsupportedOperationException())
    }
}