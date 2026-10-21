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

class EventMakerShadowExecutionPortTest {
    private val port = EventMakerShadowExecutionPort().apply { configureFillModel(0.0) }
    private val market = market()

    @Test
    fun `shadow port is registered as a spring component`() {
        assertNotNull(EventMakerShadowExecutionPort::class.java.getAnnotation(org.springframework.stereotype.Component::class.java))
        assertNull(EventMakerShadowFill::class.java.getAnnotation(org.springframework.stereotype.Component::class.java))
    }

    @Test
    fun `default and configured order ids are unique across process-like instances`() = runTest {
        val first = EventMakerShadowExecutionPort()
        val second = EventMakerShadowExecutionPort().apply { configureOrderIdPrefix("shadow-restarted") }
        val firstOrder = first.place(0L, market, intent(EventMakerSide.BUY, "0.97", "10.00", 0.0).copy(takerEntry = true)).getOrThrow()
        val secondOrder = second.place(0L, market, intent(EventMakerSide.BUY, "0.97", "10.00", 0.0).copy(takerEntry = true)).getOrThrow()

        assertTrue(firstOrder.orderId != secondOrder.orderId)
        assertEquals(true, secondOrder.orderId.startsWith("shadow-restarted-"))
    }

    @Test
    fun `taker entry fills immediately without waiting for public flow`() = runTest {
        val placed = port.place(
            0L,
            market,
            EventMakerQuoteIntent(
                side = EventMakerSide.BUY,
                price = BigDecimal("0.97"),
                shares = BigDecimal("10.00"),
                queueAheadShares = 0.0,
                reason = "taker_entry",
                takerEntry = true
            )
        ).getOrThrow()

        assertEquals("FILLED", placed.status)
        assertEquals(0, placed.originalShares.compareTo(placed.matchedShares))
        assertEquals(0, port.activeOrderCount())

        val fills = port.drainImmediateFills()
        assertEquals(1, fills.size)
        assertEquals(0, BigDecimal("10.00").compareTo(fills.single().shares))
        assertTrue(port.drainImmediateFills().isEmpty())
        assertTrue(port.onTrade(trade("SELL", 0.97, 10.0, placed.createdAt + 10L)).isEmpty())
        assertEquals(0, BigDecimal("10.00").compareTo(port.getOrder(0L, placed.orderId).getOrThrow().matchedShares))
    }

    @Test
    fun `taker exit fills immediately`() = runTest {
        val placed = port.place(
            0L,
            market,
            intent(EventMakerSide.SELL, "0.98", "10.00", 0.0).copy(takerExit = true)
        ).getOrThrow()

        assertEquals("FILLED", placed.status)
        assertEquals(0, placed.originalShares.compareTo(placed.matchedShares))
        val fills = port.drainImmediateFills()
        assertEquals(1, fills.size)
        assertEquals(EventMakerSide.SELL, fills.single().side)
        assertEquals(0, BigDecimal("10.00").compareTo(fills.single().shares))
    }
    @Test
    fun `public trade first consumes queue then partially fills maker order`() = runTest {
        val placed = port.place(0L, market, intent(EventMakerSide.BUY, "0.96", "10.00", 2.0)).getOrThrow()
        val now = placed.createdAt + 10L

        val queueOnly = port.onTrade(trade("SELL", 0.96, 1.5, now))
        assertTrue(queueOnly.isEmpty())
        assertEquals(0, port.getOrder(0L, placed.orderId).getOrThrow().matchedShares.compareTo(BigDecimal.ZERO))

        val fills = port.onTrade(trade("SELL", 0.96, 1.0, now + 1))
        assertEquals(1, fills.size)
        assertEquals(0, BigDecimal("0.50").compareTo(fills.single().shares))
        val partial = port.getOrder(0L, placed.orderId).getOrThrow()
        assertEquals(0, BigDecimal("0.50").compareTo(partial.matchedShares))
        assertEquals("LIVE", partial.status)

        port.cancel(0L, placed.orderId).getOrThrow()
        assertEquals("CANCELLED", port.getOrder(0L, placed.orderId).getOrThrow().status)
    }

    @Test
    fun `buy and sell maker orders fill on crossing taker flow`() = runTest {
        val buy = port.place(0L, market, intent(EventMakerSide.BUY, "0.96", "10.00", 0.0)).getOrThrow()
        port.onTrade(trade("SELL", 0.96, 10.0, buy.createdAt + 10L))
        assertEquals("FILLED", port.getOrder(0L, buy.orderId).getOrThrow().status)

        val sell = port.place(0L, market, intent(EventMakerSide.SELL, "0.97", "10.00", 0.0)).getOrThrow()
        port.onTrade(trade("BUY", 0.98, 10.0, sell.createdAt + 10L))
        assertEquals("FILLED", port.getOrder(0L, sell.orderId).getOrThrow().status)
        assertEquals(0, port.activeOrderCount())
    }

    @Test
    fun `diagnostics expose queue consumption and crossing flow`() = runTest {
        val placed = port.place(0L, market, intent(EventMakerSide.BUY, "0.96", "10.00", 2.0)).getOrThrow()
        val now = placed.createdAt + 10L

        port.onTrade(trade("SELL", 0.96, 1.5, now))
        port.onTrade(trade("SELL", 0.96, 1.0, now + 1))

        val diagnostics = port.diagnostics()
        val order = diagnostics.orders.single()
        assertEquals(1, diagnostics.ordersWithCrossingTrades)
        assertEquals(2, diagnostics.totalCrossingTrades)
        assertEquals(0, BigDecimal("2.5").compareTo(order.crossingTradeShares))
        assertEquals(0, BigDecimal("2.0").compareTo(order.queueConsumedShares))
        assertEquals(0, BigDecimal("0.5").compareTo(order.filledShares))
        assertEquals(0, BigDecimal.ZERO.compareTo(order.queueAheadRemaining))
    }
    @Test
    fun `fill volume factor requires cumulative crossing flow`() = runTest {
        port.configureFillModel(2.0)
        val placed = port.place(0L, market, intent(EventMakerSide.BUY, "0.96", "10.00", 0.0)).getOrThrow()
        val now = placed.createdAt + 10L

        val first = port.onTrade(trade("SELL", 0.96, 10.0, now))
        assertTrue(first.isEmpty())
        assertEquals("LIVE", port.getOrder(0L, placed.orderId).getOrThrow().status)

        val fills = port.onTrade(trade("SELL", 0.96, 10.0, now + 1))
        assertEquals(1, fills.size)
        assertEquals(0, BigDecimal("10.00").compareTo(fills.single().shares))
        assertEquals("FILLED", port.getOrder(0L, placed.orderId).getOrThrow().status)
    }

    @Test
    fun `single taker print volume is shared across crossed ladder orders`() = runTest {
        val top = port.place(0L, market, intent(EventMakerSide.BUY, "0.98", "10.00", 0.0)).getOrThrow()
        val deep = port.place(0L, market, intent(EventMakerSide.BUY, "0.97", "10.00", 0.0)).getOrThrow()

        val fills = port.onTrade(trade("SELL", 0.96, 15.0, top.createdAt + 10L))

        assertEquals(2, fills.size)
        assertEquals(0, BigDecimal("10.00").compareTo(fills.first { it.orderId == top.orderId }.shares))
        assertEquals(0, BigDecimal("5.00").compareTo(fills.first { it.orderId == deep.orderId }.shares))
        assertEquals("FILLED", port.getOrder(0L, top.orderId).getOrThrow().status)
        assertEquals("LIVE", port.getOrder(0L, deep.orderId).getOrThrow().status)
        assertEquals(0, BigDecimal("5.00").compareTo(port.getOrder(0L, deep.orderId).getOrThrow().matchedShares))
    }

    @Test
    fun `same-price ladder orders fill in placement order`() = runTest {
        repeat(8) {
            port.place(
                0L,
                market,
                intent(EventMakerSide.BUY, "0.98", "1.00", 0.0).copy(takerEntry = true)
            ).getOrThrow()
        }
        port.drainImmediateFills()
        val first = port.place(0L, market, intent(EventMakerSide.BUY, "0.98", "10.00", 0.0)).getOrThrow()
        val second = port.place(0L, market, intent(EventMakerSide.BUY, "0.98", "10.00", 0.0)).getOrThrow()

        val fills = port.onTrade(trade("SELL", 0.96, 15.0, first.createdAt + 10L))

        assertEquals(2, fills.size)
        assertEquals(0, BigDecimal("10.00").compareTo(fills.first { it.orderId == first.orderId }.shares))
        assertEquals(0, BigDecimal("5.00").compareTo(fills.first { it.orderId == second.orderId }.shares))
    }
    @Test
    fun `buy print is shared across crossed sell orders by best price`() = runTest {
        val low = port.place(0L, market, intent(EventMakerSide.SELL, "0.97", "10.00", 0.0)).getOrThrow()
        val high = port.place(0L, market, intent(EventMakerSide.SELL, "0.98", "10.00", 0.0)).getOrThrow()

        val fills = port.onTrade(trade("BUY", 0.99, 15.0, low.createdAt + 10L))

        assertEquals(2, fills.size)
        assertEquals(0, BigDecimal("10.00").compareTo(fills.first { it.orderId == low.orderId }.shares))
        assertEquals(0, BigDecimal("5.00").compareTo(fills.first { it.orderId == high.orderId }.shares))
        assertEquals("FILLED", port.getOrder(0L, low.orderId).getOrThrow().status)
        assertEquals("LIVE", port.getOrder(0L, high.orderId).getOrThrow().status)
    }

    @Test
    fun `fill volume factor shares one print across ladder orders`() = runTest {
        port.configureFillModel(2.0)
        val top = port.place(0L, market, intent(EventMakerSide.BUY, "0.98", "10.00", 0.0)).getOrThrow()
        val deep = port.place(0L, market, intent(EventMakerSide.BUY, "0.97", "10.00", 0.0)).getOrThrow()

        val firstPrint = port.onTrade(trade("SELL", 0.96, 25.0, top.createdAt + 10L))
        assertEquals(1, firstPrint.size)
        assertEquals(top.orderId, firstPrint.single().orderId)
        assertEquals("LIVE", port.getOrder(0L, deep.orderId).getOrThrow().status)
        assertEquals(0, BigDecimal.ZERO.compareTo(port.getOrder(0L, deep.orderId).getOrThrow().matchedShares))

        val secondPrint = port.onTrade(trade("SELL", 0.96, 15.0, top.createdAt + 20L))
        assertEquals(1, secondPrint.size)
        assertEquals(deep.orderId, secondPrint.single().orderId)
        assertEquals("FILLED", port.getOrder(0L, deep.orderId).getOrThrow().status)
    }

    @Test
    fun `shadow settlement books a winning SETTLE cycle and virtual redemption`() = runTest {
        val state = openShadowLong(resolvedOutcomeIndex = 0)
        val settlement = state.settlements.single()

        assertEquals(BigDecimal.ONE, settlement.exitPrice)
        assertEquals(EventMakerRedemptionStatus.ALREADY_ABSENT, settlement.redemptionStatus)
        assertEquals("SETTLE", state.cycles.single().exitType)
        assertTrue(state.cycles.single().pnl > 0.0)
        assertTrue(state.realizedPnl > 0.0)
        assertEquals(0, BigDecimal.ZERO.compareTo(state.inventoryShares))
        assertNull(state.pendingRedemption())
    }

    @Test
    fun `shadow settlement books a losing SETTLE cycle`() = runTest {
        val state = openShadowLong(resolvedOutcomeIndex = 1)
        val settlement = state.settlements.single()

        assertEquals(BigDecimal.ZERO, settlement.exitPrice)
        assertEquals(EventMakerRedemptionStatus.ALREADY_ABSENT, settlement.redemptionStatus)
        assertEquals(1, state.settlementLossesForTest())
        assertTrue(state.realizedPnl < 0.0)
        assertEquals(0, BigDecimal.ZERO.compareTo(state.inventoryShares))
    }

    private suspend fun openShadowLong(resolvedOutcomeIndex: Int): EventMakerLiveState {
        val config = EventMakerConfig(
            mode = EventMakerMode.SHADOW,
            minPrice = 0.95,
            maxPrice = 0.99,
            minSpreadTicks = 2,
            improveTicks = 1,
            flowGuardWindowSeconds = 0,
            orderNotional = 10.0,
            shadowFillVolumeFactor = 0.0,
            maxCyclesPerMarket = 3,
            maxTotalInventoryNotional = 1_000.0,
            maxDailyLoss = 100.0
        )
        val state = EventMakerLiveState(market, config)
        val port = EventMakerShadowExecutionPort().apply {
            configureFillModel(0.0)
            configureSettlementResolver {
                Result.success(
                    EventMakerSettlementSnapshot(
                        resolvedOutcomeIndex,
                        EventMakerSettlementPosition(
                            tokenId = market.favoriteTokenId,
                            shares = state.inventoryShares,
                            redeemable = true,
                            outcomeIndex = market.favoriteIndex,
                            negativeRisk = market.negRisk
                        )
                    )
                )
            }
        }
        val engine = EventMakerLiveEngine(config, EventMakerQuotePlanner(), EventMakerRiskPolicy(), port)
        val start = System.currentTimeMillis()
        state.onBook(book(0.95, 0.97, start))
        val placed = engine.quote(listOf(state), mapOf(market.favoriteTokenId to state.lastBook!!), 0L, start)
            .placed.single().order
        val fills = port.onTrade(trade("SELL", 0.96, 20.0, placed.createdAt + 10L))
        assertTrue(fills.isNotEmpty())
        engine.reconcile(listOf(state), 0L, placed.createdAt + 20L)
        assertTrue(state.inventoryShares > BigDecimal.ZERO)

        val settlementResult = engine.settleResolved(listOf(state), 0L, placed.createdAt + 2_000L)
        assertEquals(1, settlementResult.settlements.size)
        val redemption = engine.redeemPendingRedemptions(listOf(state), 0L, placed.createdAt + 2_100L)
        assertEquals(EventMakerRedemptionStatus.ALREADY_ABSENT, redemption.redemptions.single().status)
        return state
    }

    private fun EventMakerLiveState.settlementLossesForTest(): Int =
        cycles.count { it.exitType == "SETTLE" && it.pnl < 0.0 }

    private fun book(bid: Double, ask: Double, now: Long) = MarketBook(
        tokenId = market.favoriteTokenId,
        bids = listOf(BookLevel(bid, 100.0)),
        asks = listOf(BookLevel(ask, 100.0)),
        updatedAt = now
    )

    private fun intent(side: EventMakerSide, price: String, shares: String, queue: Double) =
        EventMakerQuoteIntent(side, BigDecimal(price), BigDecimal(shares), queue, "test")

    private fun trade(side: String, price: Double, size: Double, timestamp: Long) = LiveTradePrint(
        conditionId = market.conditionId,
        tokenId = market.favoriteTokenId,
        timestamp = timestamp,
        side = side,
        price = price,
        size = size,
        source = "test",
        transactionHash = "tx-$timestamp-$side-$size"
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
}
