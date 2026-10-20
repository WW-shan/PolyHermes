package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.LiveTradePrint
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
