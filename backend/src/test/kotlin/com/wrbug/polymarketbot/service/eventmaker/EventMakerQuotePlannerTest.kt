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
    fun `maker ladder offsets map to exact tenth-cent levels`() {
        val ladderConfig = config.copy(
            minSpreadTicks = 1,
            improveTicks = 0,
            exitImproveTicks = 0,
            maxQueueAheadShares = 500.0
        )
        val prices = listOf(0.0, 0.5, 1.0, 2.0).map { offset ->
            planner.planBuy(
                ladderConfig.copy(entryOffsetCents = offset),
                book(0.98, 100.0, 0.981, 100.0),
                tick,
                2_000
            )!!.price
        }

        assertEquals(
            listOf("0.98", "0.975", "0.97", "0.96"),
            prices.map { it.stripTrailingZeros().toPlainString() }
        )
    }

    @Test
    fun `taker entry crosses the spread at the ask`() {
        val takerConfig = config.copy(takerEntry = true, takerEntrySlippageTicks = 0)
        val intent = planner.planBuy(takerConfig, book(0.96, 5000.0, 0.97, 5000.0), tick, 2_000)

        assertNotNull(intent)
        assertEquals(0, BigDecimal("0.970").compareTo(intent!!.price))
        assertEquals(true, intent.takerEntry)
        assertEquals("taker_entry", intent.reason)
        assertEquals(0.0, intent.queueAheadShares, 1e-9)
    }

    @Test
    fun `taker entry slippage moves above the ask`() {
        val takerConfig = config.copy(takerEntry = true, takerEntrySlippageTicks = 1)
        val intent = planner.planBuy(takerConfig, book(0.96, 5000.0, 0.97, 5000.0), tick, 2_000)

        assertNotNull(intent)
        assertEquals(BigDecimal("0.971"), intent!!.price)
    }

    @Test
    fun `taker entry size is capped by visible ask depth`() {
        val takerConfig = config.copy(takerEntry = true, takerEntrySlippageTicks = 0)
        val intent = planner.planBuy(takerConfig, book(0.96, 5000.0, 0.97, 25.0), tick, 2_000)

        assertNotNull(intent)
        assertEquals(0, BigDecimal("25.00").compareTo(intent!!.shares))
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
    fun `sell falls back to target when ask undercut is below a taker entry`() {
        val sellConfig = config.copy(exitSpreadCents = 3.0, exitImproveTicks = 1)
        val intent = planner.planSell(
            sellConfig,
            book(0.984, 100.0, 0.985, 100.0),
            tick,
            BigDecimal("0.985"),
            BigDecimal("100.00"),
            2_000
        )

        assertNotNull(intent)
        assertEquals(0, BigDecimal("0.999").compareTo(intent!!.price))
    }

    @Test
    fun `one cent passive offset places below best bid with zero queue`() {
        val passiveConfig = config.copy(
            improveTicks = 0,
            entryOffsetCents = 1.0,
            maxQueueAheadShares = 5_000.0
        )
        val intent = planner.planBuy(
            passiveConfig,
            book(0.985, 2_460.0, 0.987, 1_000.0),
            tick,
            2_000
        )

        assertNotNull(intent)
        assertEquals(BigDecimal("0.975"), intent!!.price)
        assertEquals(0.0, intent.queueAheadShares, 1e-9)
        assertEquals("passive_offset", intent.reason)
    }
    @Test
    fun `sell above best ask has no queue ahead`() {
        val aboveAskConfig = config.copy(
            exitImproveTicks = 0,
            exitSpreadCents = 1.0,
            maxQueueAheadShares = 5_000.0
        )
        val intent = planner.planSell(
            aboveAskConfig,
            book(0.973, 2_460.0, 0.974, 2_739.0),
            tick,
            BigDecimal("0.973"),
            BigDecimal("25.69"),
            2_000
        )

        assertNotNull(intent)
        assertEquals(BigDecimal("0.983"), intent!!.price)
        assertEquals(0.0, intent.queueAheadShares, 1e-9)
    }
    @Test
    fun `one tick spread is rejected at quote time`() {
        val intent = planner.planBuy(config, book(0.960, 100.0, 0.961, 100.0), tick, 2_000)

        assertNull(intent)
    }
    @Test
    fun `one tick spread is allowed when joining the queue`() {
        val joinConfig = config.copy(minSpreadTicks = 1, improveTicks = 0, maxQueueAheadShares = 5_000.0)
        val intent = planner.planBuy(joinConfig, book(0.973, 2_460.0, 0.974, 2_739.0), tick, 2_000)

        assertNotNull(intent)
        assertEquals(BigDecimal("0.973"), intent!!.price)
        assertEquals(2_460.0, intent.queueAheadShares, 1e-9)
    }

    @Test
    fun `sell joins best ask when exit improvement is disabled`() {
        val joinConfig = config.copy(exitImproveTicks = 0, exitSpreadCents = 0.1, maxQueueAheadShares = 5_000.0)
        val intent = planner.planSell(
            joinConfig,
            book(0.973, 2_460.0, 0.974, 2_739.0),
            tick,
            BigDecimal("0.973"),
            BigDecimal("25.69"),
            2_000
        )

        assertNotNull(intent)
        assertEquals(BigDecimal("0.974"), intent!!.price)
        assertEquals(2_739.0, intent.queueAheadShares, 1e-9)
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
