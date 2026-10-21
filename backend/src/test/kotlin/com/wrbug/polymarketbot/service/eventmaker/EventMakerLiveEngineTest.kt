package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.BookLevel
import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.LiveTradePrint
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
    fun `restored cumulative loss does not consume the new daily budget`() = runTest {
        val now = System.currentTimeMillis()
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(
            config,
            EventMakerQuotePlanner(),
            EventMakerRiskPolicy(),
            port,
            initialRealizedPnl = -100.0
        )
        val state = EventMakerLiveState(market, config)
        engine.resetDailyPnlBaseline(listOf(state))
        state.onBook(book(0.95, 0.97, now))

        val result = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)

        assertEquals(-100.0, engine.dailyPnlBaseline)
        assertTrue(result.placed.isNotEmpty(), "unexpected errors: ${result.errors}")
        assertTrue(result.errors.none { it.contains("daily_loss_limit") })
    }


    @Test
    fun `taker entry is filled immediately and reconciled into inventory`() = runTest {
        val now = System.currentTimeMillis()
        val takerConfig = config.copy(takerEntry = true, takerEntrySlippageTicks = 0)
        val state = EventMakerLiveState(market, takerConfig)
        state.onBook(book(0.95, 0.97, now))
        val port = EventMakerShadowExecutionPort()
        val engine = EventMakerLiveEngine(takerConfig, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)

        val quote = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)
        val order = quote.placed.single().order

        assertEquals(EventMakerSide.BUY, order.side)
        assertEquals("FILLED", order.status)
        assertEquals(0, order.originalShares.compareTo(order.matchedShares))
        assertEquals(0, state.inventoryShares.compareTo(BigDecimal.ZERO))

        val reconciled = engine.reconcile(listOf(state), 7L, now + 1)

        assertEquals(EventMakerPhase.LONG, state.phase)
        assertEquals(0, order.originalShares.compareTo(state.inventoryShares))
        assertTrue(reconciled.reconciled.single().events.contains("buy_fill"))
        assertEquals(1, port.drainImmediateFills().size)
    }

    @Test
    fun `outside entry window blocks new buys but still allows inventory exits`() = runTest {
        val now = System.currentTimeMillis()
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        val state = EventMakerLiveState(market, config)
        state.onBook(book(0.95, 0.97, now))
        val books = mapOf("token" to state.lastBook!!)

        val blockedEntry = engine.quote(listOf(state), books, 7L, now, emptySet())
        assertTrue(blockedEntry.placed.isEmpty())
        assertEquals(0, port.placeCount)

        val entry = engine.quote(listOf(state), books, 7L, now, setOf("condition"))
        val buyOrder = entry.placed.single().order
        assertEquals(EventMakerSide.BUY, buyOrder.side)
        port.fill(buyOrder.orderId, buyOrder.originalShares)
        engine.reconcile(listOf(state), 7L, now + 1)
        assertEquals(EventMakerPhase.LONG, state.phase)

        state.onBook(book(0.96, 0.98, now + 2))
        val exit = engine.quote(
            listOf(state),
            mapOf("token" to state.lastBook!!),
            7L,
            now + 2,
            emptySet()
        )
        assertEquals(EventMakerSide.SELL, exit.placed.single().order.side)
    }

    @Test    fun `mock live flow completes buy and sell cycle through authoritative order status`() = runTest {
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
    fun `bond scalp stops opening new buys after max cycles`() = runTest {
        val now = System.currentTimeMillis()
        val scalpConfig = config.copy(maxCyclesPerMarket = 3, exitSpreadCents = 3.0)
        val state = EventMakerLiveState(market, scalpConfig)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(scalpConfig, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        var t = now

        repeat(3) { index ->
            state.onBook(book(0.95, 0.97, t))
            val buy = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, t)
            val buyOrder = buy.placed.single().order
            assertEquals(EventMakerSide.BUY, buyOrder.side)
            port.fill(buyOrder.orderId, buyOrder.originalShares)
            engine.reconcile(listOf(state), 7L, t + 1)
            assertEquals(EventMakerPhase.LONG, state.phase)

            t += 2
            state.onBook(book(0.96, 0.98, t))
            val sell = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, t)
            val sellOrder = sell.placed.single().order
            assertEquals(EventMakerSide.SELL, sellOrder.side)
            port.fill(sellOrder.orderId, sellOrder.originalShares)
            engine.reconcile(listOf(state), 7L, t + 1)
            assertEquals(EventMakerPhase.FLAT, state.phase)
            assertEquals(index + 1, state.completedCycles)
            t += 2
        }

        state.onBook(book(0.95, 0.97, t))
        val afterCap = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, t)
        assertTrue(afterCap.placed.isEmpty(), "must not open a 4th cycle")
        assertEquals(3, state.completedCycles)
        assertEquals(EventMakerPhase.FLAT, state.phase)
    }

    @Test
    fun `sell exit is not suppressed by adverse flow guard`() = runTest {
        val now = System.currentTimeMillis()
        val guardedConfig = config.copy(flowGuardWindowSeconds = 600, exitSpreadCents = 3.0)
        val state = EventMakerLiveState(market, guardedConfig)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(guardedConfig, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)

        state.onBook(book(0.95, 0.97, now))
        val buy = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)
        val buyOrder = buy.placed.single().order
        port.fill(buyOrder.orderId, buyOrder.originalShares)
        engine.reconcile(listOf(state), 7L, now + 1)
        assertEquals(EventMakerPhase.LONG, state.phase)

        // Same flow that blocks a BUY must never block the exit.
        engine.onTrade(LiveTradePrint("condition", "token", now + 2_000L, "BUY", 0.980, 30.0, "test", "0xbuy"))
        engine.onTrade(LiveTradePrint("condition", "token", now + 3_000L, "SELL", 0.975, 70.0, "test", "0xsell"))

        state.onBook(book(0.96, 0.98, now + 4_000L))
        val sell = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 4_000L)
        assertEquals(1, sell.placed.size)
        assertEquals(EventMakerSide.SELL, sell.placed.single().order.side)
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
    fun `requote cancellation reconciles a fill that races the cancel`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, now))
        val order = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now).placed.single().order
        port.fill(order.orderId, BigDecimal("2.00"))

        state.onBook(book(0.94, 0.96, now + 121_000L))
        val result = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 121_000L)

        assertTrue(result.errors.isEmpty(), "unexpected errors: ${result.errors}")
        assertEquals("requote", result.canceled.single().reason)
        assertNull(state.activeOrder)
        assertEquals(BigDecimal("2.00"), state.inventoryShares)
        assertEquals(EventMakerPhase.LONG, state.phase)
        assertEquals(1, port.getOrderCount)
    }

    @Test
    fun `partial fill cancellation reconciles a second fill during cancel`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, now))
        val order = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now).placed.single().order
        port.fill(order.orderId, BigDecimal("2.00"))
        port.fillOnCancelShares = BigDecimal("1.00")

        val result = engine.reconcile(listOf(state), 7L, now + 1)

        assertTrue(result.errors.isEmpty(), "unexpected errors: ${result.errors}")
        assertNull(state.activeOrder)
        assertEquals(BigDecimal("3.00"), state.inventoryShares)
        assertEquals(EventMakerPhase.LONG, state.phase)
        assertEquals(2, state.fills.size)
    }

    @Test
    fun `cancel lookup failure keeps the active order fail closed`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, now))
        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)
        port.getOrderFailure = IllegalStateException("timeout")
        state.onBook(book(0.94, 0.96, now + 121_000L))

        val result = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 121_000L)

        assertNotNull(state.activeOrder)
        assertTrue(result.errors.single().startsWith("cancel_state_unconfirmed"))
        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 122_000L)
        assertEquals(1, port.getOrderCount)
        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 126_000L)
        assertEquals(2, port.getOrderCount)
    }

    @Test
    fun `unknown placement status halts and keeps recovery safe`() = runTest {
        val now = System.currentTimeMillis()
        val state = EventMakerLiveState(market, config)
        val port = FakeExecutionPort().apply { placeStatus = "MYSTERY" }
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, now))

        val result = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)

        assertTrue(result.haltedReason!!.startsWith("execution_order_status_unknown"))
        assertEquals(1, port.cancelCount)
        assertNull(state.activeOrder)
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

    @Test
    fun `daily pnl baseline scopes the loss budget to the current day`() = runTest {
        // A run that is already down 4.0 must still be allowed to quote when the
        // daily baseline is advanced past that loss (new trading day).
        val now = System.currentTimeMillis()
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        val state = EventMakerLiveState(market, config)
        state.onBook(book(0.95, 0.97, now))

        val blocked = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)
        assertTrue(blocked.placed.isNotEmpty(), "fresh run should quote")

        engine.dailyPnlBaseline = 10_000.0
        val afterReset = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)
        assertTrue(
            afterReset.placed.isNotEmpty() || afterReset.errors.none { it.contains("daily_loss_limit") },
            "advanced baseline must not report a daily loss block: ${afterReset.errors}"
        )
    }

    @Test
    fun `rejected placement is rate limited per market`() = runTest {
        val now = System.currentTimeMillis()
        val port = FakeExecutionPort().apply {
            placeFailure = EventMakerPreflightRejectedException("post_only_rejected")
        }
        val state = EventMakerLiveState(market, config)
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, now))

        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)
        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 1_000L)
        assertEquals(1, port.placeCount)

        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 5_000L)
        assertEquals(2, port.placeCount)
    }


    @Test
    fun `adverse flow cancels a resting buy and suppresses replacement`() = runTest {
        val now = System.currentTimeMillis()
        val guardedConfig = config.copy(flowGuardWindowSeconds = 600)
        val state = EventMakerLiveState(market, guardedConfig)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(guardedConfig, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, now))
        val placed = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now)
        assertEquals(1, placed.placed.size)

        engine.onTrade(
            LiveTradePrint("condition", "token", now + 1_000L, "BUY", 0.980, 30.0, "test", "0xbuy")
        )
        engine.onTrade(
            LiveTradePrint("condition", "token", now + 2_000L, "SELL", 0.975, 70.0, "test", "0xsell")
        )
        val canceled = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 3_000L)

        assertEquals("adverse_flow", canceled.canceled.single().reason)
        assertNull(state.activeOrder)
        val blocked = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, now + 4_000L)
        assertTrue(blocked.placed.isEmpty())
    }

    @Test
    fun `quote does not perform order lookups`() = runTest {
        // The LIVE event loop quotes on every WS event. Order reconciliation is a
        // REST call per active order, so quote() must never issue one; otherwise a
        // busy market would hammer the CLOB API thousands of times per hour.
        val now = System.currentTimeMillis()
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        val state = EventMakerLiveState(market, config)

        engine.quote(listOf(state), mapOf(market.favoriteTokenId to book(0.975, 0.98, now)), 7L, now)

        assertEquals(0, port.getOrderCount)
    }

    @Test
    fun `atomic ladder places one order per offset in the same market`() = runTest {
        val start = 1_800_000_000_000L
        val ladderConfig = config.copy(
            maxMarkets = 1,
            orderNotional = 10.0,
            maxTotalInventoryNotional = 40.0,
            improveTicks = 0,
            ladderOffsetsCents = listOf(0.0, 0.5, 1.0, 2.0)
        )
        val ladderMarket = market().copy(tickSize = 0.001)
        val states = ladderConfig.effectiveLadderOffsetsCents().map { offset ->
            EventMakerLiveState(
                ladderMarket,
                ladderConfig.copy(entryOffsetCents = offset, ladderOffsetsCents = emptyList())
            )
        }
        val currentBook = book(0.98, 0.985, start)
        states.forEach { it.onBook(currentBook) }
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(ladderConfig, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)

        val result = engine.quote(states, mapOf("token" to currentBook), 7L, start)

        assertEquals(4, result.placed.size, "errors=${result.errors}")
        assertEquals(listOf(0.98, 0.975, 0.97, 0.96), result.placed.map { it.order.price.toDouble() })
        assertTrue(result.errors.none { it.contains("max_open_markets") || it.contains("total_inventory_limit") })
    }
    @Test
    fun `partial fill on one ladder leg cancels only that leg`() = runTest {
        val now = System.currentTimeMillis()
        val ladderConfig = config.copy(
            maxMarkets = 1,
            orderNotional = 10.0,
            maxTotalInventoryNotional = 40.0,
            improveTicks = 0,
            ladderOffsetsCents = listOf(0.0, 1.0)
        )
        val ladderMarket = market().copy(tickSize = 0.001)
        val states = ladderConfig.effectiveLadderOffsetsCents().map { offset ->
            EventMakerLiveState(
                ladderMarket,
                ladderConfig.copy(entryOffsetCents = offset, ladderOffsetsCents = emptyList())
            )
        }
        val currentBook = book(0.98, 0.985, now)
        states.forEach { it.onBook(currentBook) }
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(ladderConfig, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)

        val placed = engine.quote(states, mapOf("token" to currentBook), 7L, now).placed
        assertEquals(2, placed.size)
        val shallow = placed.first { it.order.price.compareTo(BigDecimal("0.98")) == 0 }.order
        val deep = placed.first { it.order.price.compareTo(BigDecimal("0.97")) == 0 }.order

        port.fill(shallow.orderId, BigDecimal("4.00"))
        val result = engine.reconcile(states, 7L, now + 1)

        assertTrue(result.errors.isEmpty(), "unexpected errors: ${result.errors}")
        assertEquals(listOf(shallow.orderId), result.canceled.map { it.orderId })
        assertEquals(BigDecimal("4.00"), states.first { it.legOffsetCents == 0.0 }.inventoryShares)
        assertEquals(BigDecimal.ZERO, states.first { it.legOffsetCents == 1.0 }.inventoryShares)
        assertNotNull(states.first { it.legOffsetCents == 1.0 }.activeOrder)

        port.fill(deep.orderId, deep.originalShares)
        engine.reconcile(states, 7L, now + 2)
        assertEquals(
            deep.originalShares,
            states.first { it.legOffsetCents == 1.0 }.inventoryShares
        )
    }
    @Test
    fun `atomic ladder requotes each leg against its own offset`() = runTest {
        val start = System.currentTimeMillis()
        val ladderConfig = config.copy(
            maxMarkets = 1,
            orderNotional = 10.0,
            maxTotalInventoryNotional = 40.0,
            improveTicks = 0,
            requoteSeconds = 1,
            ladderOffsetsCents = listOf(0.0, 0.5, 1.0, 2.0)
        )
        val ladderMarket = market().copy(tickSize = 0.001)
        val states = ladderConfig.effectiveLadderOffsetsCents().map { offset ->
            EventMakerLiveState(
                ladderMarket,
                ladderConfig.copy(entryOffsetCents = offset, ladderOffsetsCents = emptyList())
            )
        }
        val currentBook = book(0.98, 0.985, start)
        states.forEach { it.onBook(currentBook) }
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(ladderConfig, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)

        assertEquals(4, engine.quote(states, mapOf("token" to currentBook), 7L, start).placed.size)

        val later = start + 2_000L
        val laterBook = book(0.98, 0.985, later)
        states.forEach { it.onBook(laterBook) }
        val result = engine.quote(states, mapOf("token" to laterBook), 7L, later)

        assertTrue(result.canceled.isEmpty(), "unexpected requotes=${result.canceled}")
        assertTrue(result.placed.isEmpty(), "unexpected replacements=${result.placed}")
        assertEquals(
            listOf(0.98, 0.975, 0.97, 0.96),
            states.map { it.activeOrder!!.price.toDouble() }
        )
    }

    @Test
    fun `persisted leg outside configured ladder cannot reopen a position`() = runTest {
        val start = 1_800_000_000_000L
        val ladderConfig = config.copy(
            maxMarkets = 1,
            orderNotional = 10.0,
            maxTotalInventoryNotional = 40.0,
            improveTicks = 0,
            ladderOffsetsCents = listOf(0.0, 0.5)
        )
        val ladderMarket = market().copy(tickSize = 0.001)
        val states = listOf(0.0, 0.5, 1.0).map { offset ->
            EventMakerLiveState(
                ladderMarket,
                ladderConfig.copy(entryOffsetCents = offset, ladderOffsetsCents = emptyList())
            )
        }
        val currentBook = book(0.98, 0.985, start)
        states.forEach { it.onBook(currentBook) }
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(ladderConfig, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)

        val result = engine.quote(
            states,
            mapOf("token" to currentBook),
            7L,
            start,
            setOf("condition")
        )

        assertEquals(2, result.placed.size, "errors=${result.errors}")
        assertNull(states.last().activeOrder)
        assertEquals(listOf(0.0, 0.5), states.take(2).map { it.legOffsetCents })
    }
    @Test
    fun `multi leg settlement and redemption aggregate by market`() = runTest {
        val start = 1_800_000_000_000L
        val ladderConfig = config.copy(
            maxMarkets = 1,
            orderNotional = 10.0,
            maxTotalInventoryNotional = 40.0,
            improveTicks = 0,
            ladderOffsetsCents = listOf(0.0, 1.0)
        )
        val ladderMarket = market().copy(tickSize = 0.001)
        val states = listOf(0.0, 1.0).map { offset ->
            EventMakerLiveState(
                ladderMarket,
                ladderConfig.copy(entryOffsetCents = offset, ladderOffsetsCents = emptyList())
            )
        }
        val currentBook = book(0.98, 0.985, start)
        states.forEach { it.onBook(currentBook) }
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(ladderConfig, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)

        val placed = engine.quote(states, mapOf("token" to currentBook), 7L, start).placed
        assertEquals(2, placed.size)
        placed.forEach { placement -> port.fill(placement.order.orderId, placement.order.originalShares) }
        engine.reconcile(states, 7L, start + 1)
        val totalInventory = states.fold(BigDecimal.ZERO) { sum, state -> sum + state.inventoryShares }

        port.settlementSnapshot = EventMakerSettlementSnapshot(
            resolutionOutcomeIndex = 0,
            position = EventMakerSettlementPosition(
                tokenId = "token",
                shares = totalInventory,
                redeemable = true,
                outcomeIndex = 0,
                negativeRisk = false
            )
        )
        val settled = engine.settleResolved(states, 7L, start + 2_000L)

        assertEquals(2, settled.settlements.size)
        assertEquals(2, settled.settlements.map { it.cycleExitOrderId }.distinct().size)
        assertEquals(2, states.count { it.pendingRedemption() != null })

        port.settlementPosition = EventMakerSettlementPosition(
            tokenId = "token",
            shares = totalInventory,
            redeemable = true,
            outcomeIndex = 0,
            negativeRisk = false
        )
        val redeemed = engine.redeemPendingRedemptions(states, 7L, start + 3_000L)

        assertEquals(1, port.redeemSettlementCount)
        assertEquals(1, redeemed.redemptions.size)
        assertEquals(
            listOf(EventMakerRedemptionStatus.REDEEMED, EventMakerRedemptionStatus.REDEEMED),
            states.map { it.settlements.single().redemptionStatus }
        )
    }
    @Test
    fun `resolved market books settlement then confirms redemption`() = runTest {
        val start = 1_800_000_000_000L
        val liveMarket = market()
        val state = EventMakerLiveState(liveMarket, config)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, start))

        val buy = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, start).placed.single().order
        port.fill(buy.orderId, buy.originalShares)
        engine.reconcile(listOf(state), 7L, start + 1)
        assertEquals(EventMakerPhase.LONG, state.phase)

        port.settlementSnapshot = EventMakerSettlementSnapshot(
            resolutionOutcomeIndex = 0,
            position = EventMakerSettlementPosition(
                tokenId = liveMarket.favoriteTokenId,
                shares = state.inventoryShares,
                redeemable = true,
                outcomeIndex = 0,
                negativeRisk = false
            )
        )
        val settlementResult = engine.settleResolved(listOf(state), 7L, start + 2_000L)

        assertEquals(1, settlementResult.settlements.size)
        assertEquals(BigDecimal.ZERO, state.inventoryShares)
        assertEquals(1, state.completedCycles)
        assertEquals("SETTLE", state.cycles.single().exitType)
        assertNotNull(state.pendingRedemption())

        port.settlementPosition = EventMakerSettlementPosition(
            tokenId = liveMarket.favoriteTokenId,
            shares = buy.originalShares,
            redeemable = true,
            outcomeIndex = 0,
            negativeRisk = false
        )
        val redemptionResult = engine.redeemPendingRedemptions(listOf(state), 7L, start + 2_100L)

        assertEquals(1, port.redeemSettlementCount)
        assertEquals(1, redemptionResult.redemptions.size)
        assertEquals(EventMakerRedemptionStatus.REDEEMED, state.settlements.single().redemptionStatus)
        assertEquals("0xtx", state.settlements.single().transactionHash)
        assertNull(state.pendingRedemption())
    }

    @Test
    fun `resolved market with missing position fails closed without booking payout`() = runTest {
        val start = 1_800_000_000_000L
        val state = EventMakerLiveState(market(), config)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, start))
        val buy = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, start).placed.single().order
        port.fill(buy.orderId, buy.originalShares)
        engine.reconcile(listOf(state), 7L, start + 1)

        port.settlementSnapshot = EventMakerSettlementSnapshot(
            resolutionOutcomeIndex = 0,
            position = null
        )
        val first = engine.settleResolved(listOf(state), 7L, start + 2_000L)
        assertTrue(first.settlements.isEmpty())
        assertNull(engine.haltedReason)

        engine.settleResolved(listOf(state), 7L, start + 32_001L)
        engine.settleResolved(listOf(state), 7L, start + 62_002L)

        assertEquals("settlement_position_missing:condition", engine.haltedReason)
        assertEquals(0, state.completedCycles)
        assertEquals(0, state.settlements.size)
        assertNull(state.pendingRedemption())
    }

    @Test
    fun `successful settlement snapshot read resets transient failure counter`() = runTest {
        val start = 1_800_000_000_000L
        val state = EventMakerLiveState(market, config)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, start))
        val buy = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, start).placed.single().order
        port.fill(buy.orderId, buy.originalShares)
        engine.reconcile(listOf(state), 7L, start + 1)

        port.settlementSnapshotFailuresRemaining = 2
        engine.settleResolved(listOf(state), 7L, start + 2_000L)
        engine.settleResolved(listOf(state), 7L, start + 32_001L)
        assertNull(engine.haltedReason)

        port.settlementSnapshot = EventMakerSettlementSnapshot(null, null)
        engine.settleResolved(listOf(state), 7L, start + 62_002L)
        assertNull(engine.haltedReason)

        port.settlementSnapshotFailuresRemaining = 2
        engine.settleResolved(listOf(state), 7L, start + 92_003L)
        engine.settleResolved(listOf(state), 7L, start + 122_004L)
        assertNull(engine.haltedReason)
    }

    @Test
    fun `successful redemption position read resets transient failure counter`() = runTest {
        val start = 1_800_000_000_000L
        val liveMarket = market
        val state = EventMakerLiveState(liveMarket, config)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, start))
        val buy = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, start).placed.single().order
        port.fill(buy.orderId, buy.originalShares)
        engine.reconcile(listOf(state), 7L, start + 1)
        port.settlementSnapshot = EventMakerSettlementSnapshot(
            resolutionOutcomeIndex = 0,
            position = EventMakerSettlementPosition(
                tokenId = liveMarket.favoriteTokenId,
                shares = state.inventoryShares,
                redeemable = true,
                outcomeIndex = 0,
                negativeRisk = false
            )
        )
        engine.settleResolved(listOf(state), 7L, start + 2_000L)
        assertNotNull(state.pendingRedemption())

        port.settlementPositionFailuresRemaining = 2
        engine.redeemPendingRedemptions(listOf(state), 7L, start + 3_000L)
        engine.redeemPendingRedemptions(listOf(state), 7L, start + 33_001L)
        assertNull(engine.haltedReason)

        port.settlementPosition = EventMakerSettlementPosition(
            tokenId = liveMarket.favoriteTokenId,
            shares = buy.originalShares,
            redeemable = false,
            outcomeIndex = 0,
            negativeRisk = false
        )
        engine.redeemPendingRedemptions(listOf(state), 7L, start + 63_002L)
        assertNull(engine.haltedReason)

        port.settlementPositionFailuresRemaining = 2
        engine.redeemPendingRedemptions(listOf(state), 7L, start + 93_003L)
        engine.redeemPendingRedemptions(listOf(state), 7L, start + 123_004L)
        assertNull(engine.haltedReason)
    }

    @Test
    fun `expired market cancels a resting order before settlement`() = runTest {
        val start = 1_800_000_000_000L
        val state = EventMakerLiveState(market(endTimestamp = start / 1000L + 1), config)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, start))
        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, start)

        val result = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, start + 2_000L)

        assertEquals("market_expired", result.canceled.single().reason)
        assertNull(state.activeOrder)
    }
    @Test
    fun `resting buy is cancelled inside the tail window and not replaced`() = runTest {
        val start = System.currentTimeMillis()
        val tailConfig = config.copy(tailWindowMinutes = 60)
        val state = EventMakerLiveState(market(endTimestamp = start / 1000L + 7_200), tailConfig)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(tailConfig, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, start))
        engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, start)
        assertNotNull(state.activeOrder)

        val later = start + 90 * 60_000L
        state.onBook(book(0.95, 0.97, later))
        val result = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, later)

        assertEquals("tail_window", result.canceled.single().reason)
        assertNull(state.activeOrder)
        assertTrue(result.placed.isEmpty())
    }

    @Test
    fun `flat state does not quote inside the tail window`() = runTest {
        val start = System.currentTimeMillis()
        val tailConfig = config.copy(tailWindowMinutes = 60)
        val state = EventMakerLiveState(market(endTimestamp = start / 1000L + 1_800), tailConfig)
        val port = FakeExecutionPort()
        val engine = EventMakerLiveEngine(tailConfig, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        state.onBook(book(0.95, 0.97, start))

        val result = engine.quote(listOf(state), mapOf("token" to state.lastBook!!), 7L, start)

        assertTrue(result.placed.isEmpty())
        assertNull(state.activeOrder)
        assertEquals(0, port.placeCount)
    }
    private fun book(bid: Double, ask: Double, now: Long) = MarketBook(
        tokenId = "token",
        bids = listOf(BookLevel(bid, 100.0)),
        asks = listOf(BookLevel(ask, 100.0)),
        updatedAt = now
    )

    private fun market(endTimestamp: Long = Long.MAX_VALUE) = LivePaperMarket(
        conditionId = "condition",
        question = "question",
        tag = "Politics",
        volume24hr = 100_000.0,
        endTimestamp = endTimestamp,
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
        var placeStatus: String = "LIVE"
        var settlementSnapshot: EventMakerSettlementSnapshot? = null
        var settlementSnapshotFailuresRemaining: Int = 0
        var settlementPosition: EventMakerSettlementPosition? = null
        var settlementPositionFailuresRemaining: Int = 0
        var redeemSettlementCount: Int = 0
            private set
        var fillOnCancelShares: BigDecimal? = null
        var cancelCount: Int = 0
            private set
        var getOrderCount: Int = 0
            private set
        var placeCount: Int = 0
            private set

        override suspend fun validateAccount(accountId: Long): Result<Unit> = Result.success(Unit)

        override suspend fun listOpenOrderIds(accountId: Long): Result<Set<String>> =
            Result.success(orders.filterValues { it.status == "LIVE" }.keys.toSet())

        override suspend fun listOpenPositions(accountId: Long): Result<Map<String, BigDecimal>> =
            Result.success(emptyMap())

        override suspend fun getSettlementSnapshot(
            accountId: Long,
            market: LivePaperMarket
        ): Result<EventMakerSettlementSnapshot> {
            if (settlementSnapshotFailuresRemaining > 0) {
                settlementSnapshotFailuresRemaining--
                return Result.failure(IllegalStateException("settlement_snapshot_transient"))
            }
            return settlementSnapshot?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("settlement_snapshot_missing"))
        }

        override suspend fun getSettlementPosition(
            accountId: Long,
            settlement: EventMakerSettlementRecord
        ): Result<EventMakerSettlementPosition> {
            if (settlementPositionFailuresRemaining > 0) {
                settlementPositionFailuresRemaining--
                return Result.failure(IllegalStateException("settlement_position_transient"))
            }
            return settlementPosition?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("settlement_position_missing"))
        }

        override suspend fun redeemSettlement(
            accountId: Long,
            settlement: EventMakerSettlementRecord
        ): Result<String> {
            redeemSettlementCount++
            return Result.success("0xtx")
        }
        override suspend fun place(
            accountId: Long,
            market: LivePaperMarket,
            intent: EventMakerQuoteIntent
        ): Result<EventMakerLiveOrder> {
            placeCount++
            placeFailure?.let { return Result.failure(it) }
            val order = EventMakerLiveOrder(
                orderId = "fake-${sequence.incrementAndGet()}",
                side = intent.side,
                price = if (mismatchPlacement) intent.price.add(BigDecimal("0.01")) else intent.price,
                originalShares = intent.shares,
                matchedShares = BigDecimal.ZERO,
                status = placeStatus,
                tokenId = market.favoriteTokenId,
                conditionId = market.conditionId,
                makerAddress = "0xproxy"
            )
            orders[order.orderId] = order
            return Result.success(order)
        }

        override suspend fun getOrder(accountId: Long, orderId: String): Result<EventMakerLiveOrder> {
            getOrderCount++
            getOrderFailure?.let { return Result.failure(it) }
            return orders[orderId]?.let { Result.success(it) }
                ?: Result.failure(EventMakerOrderNotFoundException(orderId))
        }

        override suspend fun cancel(accountId: Long, orderId: String): Result<Unit> {
            cancelFailure?.let { return Result.failure(it) }
            val order = orders[orderId] ?: return Result.failure(EventMakerOrderNotFoundException(orderId))
            cancelCount++
            fillOnCancelShares?.let { shares ->
                val matched = (order.matchedShares + shares).min(order.originalShares)
                orders[orderId] = order.copy(matchedShares = matched)
            }
            orders[orderId] = orders.getValue(orderId).copy(status = "CANCELLED")
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
