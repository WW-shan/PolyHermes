package com.wrbug.polymarketbot.research.livepaper

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Network-layer reconnect coverage for the Shadow/Live market feed.
 *
 * The engine-level tests cover order reconciliation after a reconnect; these
 * tests cover the WebSocket client itself: subscription on open, and automatic
 * reconnection after the server drops the connection.
 */
class LivePaperWsClientReconnectTest {
    private var server: TestWsServer? = null
    private var client: LivePaperWsClient? = null

    @AfterEach
    fun tearDown() {
        client?.close()
        server?.stop(500)
    }

    @Test
    fun `client resubscribes after the server drops the connection`() {
        val connections = AtomicInteger(0)
        val subscriptions = Collections.synchronizedList(mutableListOf<String>())
        val secondOpen = CountDownLatch(1)
        val openSockets = Collections.synchronizedList(mutableListOf<WebSocket>())
        val testServer = TestWsServer(
            openHandler = { webSocket ->
                openSockets += webSocket
                val index = connections.incrementAndGet()
                if (index >= 2) secondOpen.countDown()
                webSocket
            },
            messageHandler = { _, message -> subscriptions += message }
        )
        server = testServer
        testServer.start()
        testServer.awaitStarted()

        val statuses = Collections.synchronizedList(mutableListOf<String>())
        val firstSubscribe = CountDownLatch(1)
        val clientUnderTest = LivePaperWsClient(
            proxyUrl = null,
            tokenToCondition = mapOf("token-1" to "condition-1"),
            onEvent = {},
            onStatus = { status ->
                statuses += status
                if (status.startsWith("WS_SUBSCRIBED")) firstSubscribe.countDown()
            },
            wsUrl = "ws://127.0.0.1:${testServer.port}",
            reconnectDelayMs = 200L
        )
        client = clientUnderTest
        clientUnderTest.start()

        assertTrue(firstSubscribe.await(10, TimeUnit.SECONDS), "initial subscription never happened")
        // Abruptly drop the server side of the connection to force the client to reconnect.
        val socketsToClose = synchronized(openSockets) { openSockets.toList() }
        socketsToClose.forEach { socket -> runCatching { socket.close() } }

        val didReconnect = secondOpen.await(15, TimeUnit.SECONDS)
        val statusesAfterReconnect = synchronized(statuses) { statuses.toList() }
        val subscriptionsAfterReconnect = synchronized(subscriptions) { subscriptions.toList() }
        assertTrue(
            didReconnect,
            "client did not reconnect after the drop; statuses=$statusesAfterReconnect subscriptions=$subscriptionsAfterReconnect"
        )

        // The server-side accept can race the client-side onOpen callback, so wait
        // briefly for the client to report its second open and its resubscription.
        fun statusSnapshot() = synchronized(statuses) { statuses.toList() }
        fun subscriptionSnapshot() = synchronized(subscriptions) { subscriptions.toList() }

        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline &&
            (statusSnapshot().count { it.startsWith("WS_OPEN") } < 2 || subscriptionSnapshot().isEmpty())
        ) {
            Thread.sleep(50)
        }
        val finalSubscriptions = subscriptionSnapshot()
        assertTrue(
            finalSubscriptions.any { it.contains("MARKET") && it.contains("token-1") },
            "subscription payload missing market/token: $finalSubscriptions"
        )
        val finalStatuses = statusSnapshot()
        assertTrue(
            finalStatuses.any { it.startsWith("WS_FAILURE") || it.startsWith("WS_CLOSED") },
            "no drop status: $finalStatuses"
        )
        assertTrue(
            finalStatuses.count { it.startsWith("WS_OPEN") } >= 2,
            "expected a second WS_OPEN: $finalStatuses"
        )
    }

    private class TestWsServer(
        private val openHandler: (WebSocket) -> WebSocket,
        private val messageHandler: (WebSocket, String) -> Unit
    ) : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
        private val started = CountDownLatch(1)

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            openHandler(conn)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            println("TESTWS onClose code=$code remote=$remote")
        }

        override fun onMessage(conn: WebSocket, message: String) {
            messageHandler(conn, message)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            println("TESTWS onError: ${ex.javaClass.name}: ${ex.message}")
        }

        override fun onStart() {
            started.countDown()
        }

        fun awaitStarted() {
            check(started.await(10, TimeUnit.SECONDS)) { "test ws server did not start" }
        }
    }
}