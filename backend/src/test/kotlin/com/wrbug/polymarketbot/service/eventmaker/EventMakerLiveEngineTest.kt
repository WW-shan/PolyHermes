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
        activeOrderStaleBookSeconds = 60,
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
    fun `unchanged quote keeps queue priority after requote interval`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, now))
        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)

        state.onBook(book(0.95, 0.97, now + 121_000L))
        val result = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 121_000L)

        assertTrue(result.canceled.isEmpty())
        assertNotNull(state.activeOrder)
        assertEquals(0, port.cancelCount)
    }

    @Test
    fun `changed quote is cancelled after requote cooldown`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, now))
        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)

        state.onBook(book(0.94, 0.96, now + 121_000L))
        val result = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 121_000L)

        assertEquals(1, result.canceled.size)
        assertEquals("requote", result.canceled.single().reason)
        assertNull(state.activeOrder)
    }

    @Test
    fun `active order is cancelled at max age and on stale book`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, now))
        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)

        state.onBook(book(0.95, 0.97, now + 3_601_000L))
        val maxAge = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 3_601_000L)
        assertEquals("max_age", maxAge.canceled.single().reason)

        val nextState = EventMakerLiveState(market, config)
        val nextPort = FakeExecutionPort()
        val nextEngine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), nextPort)
        nextState.onBook(book(0.95, 0.97, now))
        nextEngine.quote(listOf(nextState), mapOf("token" to nextState.lastBook!!), 7L, now)
        val stale = nextEngine.quote(listOf(nextState), mapOf("token" to nextState.lastBook!!), 7L, now + 61_000L)

        assertEquals("stale_book", stale.canceled.single().reason)
    }
    @Test
    fun `quiet book keeps active order until active stale threshold`() = runTest {
        val now = System.currentTimeMillis()
        val slowConfig = config.copy(activeOrderStaleBookSeconds = 600)
        val state = EventMakerLiveState(market, slowConfig)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(slowConfig, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, now))
        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)

        val result = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 61_000L)

        assertTrue(result.canceled.isEmpty())
        assertNotNull(state.activeOrder)
        assertEquals(0, port.cancelCount)
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

        port.getOrderFailure = null
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
        assertTrue(result.errors.single().startsWith("stop_order_still_active"))
    }

    @Test
    fun `stop reconciles partial fills before clearing the active order`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        state.onBook(book(0.95, 0.97, now))
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        val order = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now).placed.single().order
        port.fill(order.orderId, BigDecimal("2.00"))

        val result = engine.cancelAll(listOf(state), 7L)

        assertTrue(result.errors.isEmpty())
        assertEquals(1, result.canceled.size)
        assertNull(state.activeOrder)
        assertEquals(BigDecimal("2.00"), state.inventoryShares)
    }

    @Test
    fun `stop lookup failure preserves active order`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        state.onBook(book(0.95, 0.97, now))
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)
        port.getOrderFailure = IllegalStateException("timeout")

        val result = engine.cancelAll(listOf(state), 7L)

        assertEquals(1, result.errors.size)
        assertNotNull(state.activeOrder)
        assertTrue(result.errors.single().startsWith("stop_state_unconfirmed"))
    }
    @Test
    fun `pending buy order consumes aggregate risk budget`() = runTest {
        val now = System.currentTimeMillis()
        val limitedConfig = config.copy(maxTotalInventoryNotional = 15.0)
        val existing = EventMakerLiveState(market, limitedConfig)
        existing.onBook(book(0.95, 0.97, now))
        existing.onOrderPlaced(
            EventMakerLiveOrder(
                orderId = "existing-buy",
                side = EventMakerSide.BUY,
                price = BigDecimal("0.96"),
                originalShares = BigDecimal("10.00"),
                matchedShares = BigDecimal.ZERO,
                status = "LIVE",
                createdAt = now
            )
        )
        val candidate = EventMakerLiveState(market.copy(conditionId = "condition-2"), limitedConfig)
        candidate.onBook(book(0.95, 0.97, now))
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(limitedConfig, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)

        val result = engine.quote(
            listOf(existing, candidate),
            mapOf("token" to existing.lastBook!!),
            7L,
            now
        )

        assertTrue(result.placed.isEmpty())
        assertTrue(result.errors.any { it == "risk_blocked:total_inventory_limit" })
        assertNull(candidate.activeOrder)
    }

    @Test
    fun `mismatched placed order is canceled before halt`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        state.onBook(book(0.95, 0.97, now))
        val port = FakeExecutionPort().apply { mismatchPlacement = true }
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)

        val result = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)

        assertTrue(result.haltedReason!!.startsWith("execution_order_mismatch"))
        assertEquals(1, port.cancelCount)
        assertNull(state.activeOrder)
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
        var mismatchPlacement: Boolean = false
        var cancelCount: Int = 0
            private set

        override suspend fun validateAccount(accountId: Long): Result<Unit> = Result.success(Unit)

        override suspend fun listOpenOrderIds(accountId: Long): Result<Set<String>> =
            Result.success(orders.filterValues { it.status == "LIVE" }.keys.toSet())

        override suspend fun listOpenPositions(accountId: Long): Result<Map<String, BigDecimal>> =
            Result.success(emptyMap())

        override suspend fun place(
            accountId: Long,
            market: LivePaperMarket,
            intent: EventMakerQuoteIntent
        ): Result<EventMakerLiveOrder> {
            placeFailure?.let { return Result.failure(it) }
            val order = EventMakerLiveOrder(
                orderId = "fake-${sequence.incrementAndGet()}",
                side = intent.side,
                price = if (mismatchPlacement) intent.price.add(BigDecimal("0.01")) else intent.price,
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
