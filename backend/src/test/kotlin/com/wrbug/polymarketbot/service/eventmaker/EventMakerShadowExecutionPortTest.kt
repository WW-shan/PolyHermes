package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.LiveTradePrint
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EventMakerShadowExecutionPortTest {
    private val port = EventMakerShadowExecutionPort()
    private val market = market()

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