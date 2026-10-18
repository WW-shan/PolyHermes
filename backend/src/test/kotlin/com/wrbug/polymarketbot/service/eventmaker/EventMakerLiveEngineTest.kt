package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.BookLevel
import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.MarketBook
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

class EventMakerLiveEngineTest {
    private val config = EventMakerConfig(
        orderNotional = 10.0,
        maxTotalInventoryNotional = 100.0,
        requoteSeconds = 120,
        staleBookSeconds = 60,
        maxCyclesPerMarket = 3
    )
    private val market = market()

    @Test
    fun `mock live flow completes buy and sell cycle through authoritative order status`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        state.onBook(book(0.95, 0.97, now))
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)

        val buyQuote = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)
        val buyOrder = buyQuote.placed.single().order
        assertEquals(EventMakerSide.BUY, buyOrder.side)

        port.fill(buyOrder.orderId, buyOrder.originalShares)
        engine.reconcile(listOf(state), 7L, now + 1)
        assertEquals(EventMakerPhase.LONG, state.phase)
        assertEquals(buyOrder.originalShares, state.inventoryShares)

        state.onBook(book(0.96, 0.98, now + 2))
        val sellQuote = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 2)
        val sellOrder = sellQuote.placed.single().order
        assertEquals(EventMakerSide.SELL, sellOrder.side)

        port.fill(sellOrder.orderId, sellOrder.originalShares)
        engine.reconcile(listOf(state), 7L, now + 3)
        assertEquals(EventMakerPhase.FLAT, state.phase)
        assertEquals(BigDecimal.ZERO, state.inventoryShares)
        assertEquals(1, state.completedCycles)
        assertTrue(state.realizedPnl > 0.0)
    }

    @Test
    fun `partial fill is reconciled then remaining order is cancelled`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        state.onBook(book(0.95, 0.97, now))
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        val order = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now).placed.single().order

        port.fill(order.orderId, BigDecimal("3.00"))
        val result = engine.reconcile(listOf(state), 7L, now + 1)

        assertEquals(1, result.canceled.size)
        assertEquals("partial_fill", result.canceled.single().reason)
        assertNull(state.activeOrder)
        assertEquals(BigDecimal("3.00"), state.inventoryShares)
        assertEquals(EventMakerPhase.LONG, state.phase)
    }

    @Test
    fun `unknown order status halts quoting`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        state.onBook(book(0.95, 0.97, now))
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        val order = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now).placed.single().order
        port.setStatus(order.orderId, "MYSTERY")

        val result = engine.reconcile(listOf(state), 7L, now + 1)

        assertNotNull(result.haltedReason)
        assertTrue(result.haltedReason!!.startsWith("order_state_unknown"))
        assertTrue(engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 2).haltedReason != null)
    }

    @Test
    fun `missing order halts and stop cancels only after successful cancel`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        state.onBook(book(0.95, 0.97, now))
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        val order = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now).placed.single().order
        port.getOrderFailure = EventMakerOrderNotFoundException(order.orderId)

        val missing = engine.reconcile(listOf(state), 7L, now + 1)
        assertTrue(missing.haltedReason!!.startsWith("order_state_unknown"))

        val cancelEngine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        cancelEngine.registerRestoredOrders(listOf(state), 7L)
        val canceled = cancelEngine.cancelAll(listOf(state), 7L)
        assertEquals(1, canceled.canceled.size)
        assertNull(state.activeOrder)
    }

    @Test
    fun `ambiguous placement fails closed`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        state.onBook(book(0.95, 0.97, now))
        val port = FakeExecutionPort().apply {
            placeFailure = EventMakerAmbiguousOrderException("timeout")
        }
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)

        val result = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)

        assertTrue(result.haltedReason!!.startsWith("order_placement_unknown"))
        assertNull(state.activeOrder)
    }

    @Test
    fun `repeated transient reconciliation failures fail closed`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        state.onBook(book(0.95, 0.97, now))
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)
        port.getOrderFailure = IllegalStateException("timeout")

        repeat(2) { engine.reconcile(listOf(state), 7L, now + it + 1) }
        assertNull(engine.haltedReason)
        val result = engine.reconcile(listOf(state), 7L, now + 4)

        assertTrue(result.haltedReason!!.startsWith("order_state_unknown_after_retries"))
    }
    @Test
    fun `failed stop cancel preserves active order`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        state.onBook(book(0.95, 0.97, now))
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)
        port.cancelFailure = IllegalStateException("cancel timeout")

        val result = engine.cancelAll(listOf(state), 7L)

        assertEquals(1, result.errors.size)
        assertNotNull(state.activeOrder)
        assertTrue(result.errors.single().startsWith("stop_cancel_failed"))
    }
    private fun book(bid: Double, ask: Double, now: Long) = MarketBook(
        tokenId = "token",
        bids = listOf(BookLevel(bid, 100.0)),
        asks = listOf(BookLevel(ask, 100.0)),
        updatedAt = now
    )

    private fun market() = LivePaperMarket(
        conditionId = "condition",
        question = "question",
        tag = "Politics",
        volume24hr = 100_000.0,
        endTimestamp = Long.MAX_VALUE,
        tickSize = 0.01,
        outcomes = listOf("Yes", "No"),
        tokenIds = listOf("token", "other"),
        favoriteIndex = 0
    )

    private class FakeExecutionPort : EventMakerExecutionPort {
        private val sequence = AtomicInteger(0)
        private val orders = linkedMapOf<String, EventMakerLiveOrder>()
        var placeFailure: Throwable? = null
        var getOrderFailure: Throwable? = null
        var cancelFailure: Throwable? = null
        var cancelCount: Int = 0
            private set

        override suspend fun validateAccount(accountId: Long): Result<Unit> = Result.success(Unit)

        override suspend fun place(
            accountId: Long,
            market: LivePaperMarket,
            intent: EventMakerQuoteIntent
        ): Result<EventMakerLiveOrder> {
            placeFailure?.let { return Result.failure(it) }
            val order = EventMakerLiveOrder(
                orderId = "fake-${sequence.incrementAndGet()}",
                side = intent.side,
                price = intent.price,
                originalShares = intent.shares,
                matchedShares = BigDecimal.ZERO,
                status = "LIVE"
            )
            orders[order.orderId] = order
            return Result.success(order)
        }

        override suspend fun getOrder(accountId: Long, orderId: String): Result<EventMakerLiveOrder> {
            getOrderFailure?.let { return Result.failure(it) }
            return orders[orderId]?.let { Result.success(it) }
                ?: Result.failure(EventMakerOrderNotFoundException(orderId))
        }

        override suspend fun cancel(accountId: Long, orderId: String): Result<Unit> {
            cancelFailure?.let { return Result.failure(it) }
            val order = orders[orderId] ?: return Result.failure(EventMakerOrderNotFoundException(orderId))
            cancelCount++
            orders[orderId] = order.copy(status = "CANCELLED")
            return Result.success(Unit)
        }

        fun fill(orderId: String, shares: BigDecimal) {
            val order = orders.getValue(orderId)
            val matched = (order.matchedShares + shares).min(order.originalShares)
            orders[orderId] = order.copy(
                matchedShares = matched,
                status = if (matched >= order.originalShares) "FILLED" else "LIVE"
            )
        }

        fun setStatus(orderId: String, status: String) {
            orders[orderId] = orders.getValue(orderId).copy(status = status)
        }
    }
}