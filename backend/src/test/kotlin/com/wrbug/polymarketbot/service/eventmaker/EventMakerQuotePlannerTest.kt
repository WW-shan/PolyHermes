package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.BookLevel
import com.wrbug.polymarketbot.research.livepaper.MarketBook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EventMakerQuotePlannerTest {
    private val planner = EventMakerQuotePlanner()
    private val config = EventMakerConfig(
        minPrice = 0.90,
        maxPrice = 0.99,
        improveTicks = 1,
        exitSpreadCents = 1.0,
        exitImproveTicks = 1,
        orderNotional = 100.0,
        maxQueueAheadShares = 500.0
    )
    private val tick = BigDecimal("0.001")

    @Test
    fun `improved buy gets queue priority`() {
        val intent = planner.planBuy(config, book(0.96, 5000.0, 0.97, 5000.0), tick, 2_000)

        assertNotNull(intent)
        assertEquals(BigDecimal("0.961"), intent!!.price)
        assertEquals(0.0, intent.queueAheadShares, 1e-9)
        assertEquals(EventMakerSide.BUY, intent.side)
    }

    @Test
    fun `joining a large queue is rejected`() {
        val joinConfig = config.copy(improveTicks = 0)
        val intent = planner.planBuy(joinConfig, book(0.96, 5000.0, 0.97, 5000.0), tick, 2_000)

        assertNull(intent)
    }

    @Test
    fun `sell is placed inside best ask`() {
        val intent = planner.planSell(
            config,
            book(0.96, 100.0, 0.97, 100.0),
            tick,
            BigDecimal("0.96"),
            BigDecimal("100.00"),
            2_000
        )

        assertNotNull(intent)
        assertEquals(BigDecimal("0.969"), intent!!.price)
        assertEquals(0.0, intent.queueAheadShares, 1e-9)
    }

    @Test
    fun `stale book is rejected`() {
        val intent = planner.planBuy(config, book(0.96, 100.0, 0.97, 100.0, updatedAt = 1_000), tick, 100_000)

        assertNull(intent)
    }

    @Test
    fun `tiny tick near one is rounded correctly`() {
        val tinyTick = BigDecimal("0.0001")
        val nearOne = config.copy(minPrice = 0.998, maxPrice = 0.9995)
        val intent = planner.planBuy(nearOne, book(0.999, 100.0, 0.9992, 100.0), tinyTick, 2_000)

        assertNotNull(intent)
        assertEquals(BigDecimal("0.9991"), intent!!.price)
    }

    @Test
    fun `sell price is capped below one`() {
        val sellConfig = config.copy(exitImproveTicks = 0)
        val intent = planner.planSell(
            sellConfig,
            book(0.998, 100.0, 0.999, 100.0),
            tick,
            BigDecimal("0.99"),
            BigDecimal("10.00"),
            2_000
        )

        assertNotNull(intent)
        assertEquals(BigDecimal("0.999"), intent!!.price)
    }

    @Test
    fun `crossed or locked book is rejected`() {
        assertNull(planner.planBuy(config, book(0.96, 100.0, 0.96, 100.0), tick, 2_000))
        assertNull(planner.planSell(config, book(0.97, 100.0, 0.96, 100.0), tick, BigDecimal("0.96"), BigDecimal("10.00"), 2_000))
    }
    private fun book(
        bid: Double,
        bidSize: Double,
        ask: Double,
        askSize: Double,
        updatedAt: Long = 1_000
    ) = MarketBook(
        tokenId = "token",
        bids = listOf(BookLevel(bid, bidSize)),
        asks = listOf(BookLevel(ask, askSize)),
        updatedAt = updatedAt
    )
}
