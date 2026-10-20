package com.wrbug.polymarketbot.service.eventmaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LiquidityRewardCalculatorTest {
    private val config = LiquidityRewardConfig(
        minSizeShares = 20.0,
        maxSpreadCents = 5.0
    )

    @Test
    fun `order score follows quadratic distance from midpoint`() {
        assertEquals(0.64, LiquidityRewardCalculator.scoreOrder(0.94, EventMakerSide.BUY, 0.95, config), 1e-9)
        assertEquals(0.36, LiquidityRewardCalculator.scoreOrder(0.97, EventMakerSide.SELL, 0.95, config), 1e-9)
        assertEquals(0.0, LiquidityRewardCalculator.scoreOrder(0.89, EventMakerSide.BUY, 0.95, config), 1e-9)
    }

    @Test
    fun `middle band allows single sided liquidity at one third weight`() {
        val yes = book(0.50, bids = listOf(LiquidityRewardLevel(0.49, 100.0)))
        val no = LiquidityRewardBook(emptyList(), emptyList(), 0.50)

        val score = LiquidityRewardCalculator.calculate(
            yesBook = yes,
            noBook = no,
            config = LiquidityRewardConfig(minSizeShares = 20.0, maxSpreadCents = 3.0)
        )

        assertEquals(44.4444444444, score.qOne, 1e-9)
        assertEquals(0.0, score.qTwo, 1e-9)
        assertEquals(14.8148148148, score.qMin, 1e-9)
    }

    @Test
    fun `high probability band requires both sides`() {
        val yes = book(0.95, bids = listOf(LiquidityRewardLevel(0.94, 100.0)))
        val no = LiquidityRewardBook(emptyList(), emptyList(), 0.05)

        val singleSided = LiquidityRewardCalculator.calculate(yes, no, config)

        assertEquals(64.0, singleSided.qOne, 1e-9)
        assertEquals(0.0, singleSided.qTwo, 1e-9)
        assertEquals(0.0, singleSided.qMin, 1e-9)
    }

    @Test
    fun `complementary bids score both sides near extreme`() {
        val yes = book(0.95, bids = listOf(LiquidityRewardLevel(0.94, 100.0)))
        val no = book(0.05, bids = listOf(LiquidityRewardLevel(0.04, 100.0)))

        val score = LiquidityRewardCalculator.calculate(yes, no, config)

        assertEquals(64.0, score.qOne, 1e-9)
        assertEquals(64.0, score.qTwo, 1e-9)
        assertEquals(64.0, score.qMin, 1e-9)
    }

    @Test
    fun `orders below minimum reward size do not score`() {
        val yes = book(0.95, bids = listOf(LiquidityRewardLevel(0.94, 19.0)))
        val no = book(0.05, bids = listOf(LiquidityRewardLevel(0.04, 19.0)))

        val score = LiquidityRewardCalculator.calculate(yes, no, config)

        assertEquals(0.0, score.qOne, 1e-9)
        assertEquals(0.0, score.qTwo, 1e-9)
        assertEquals(0.0, score.qMin, 1e-9)
    }

    @Test
    fun `single sided rule starts at ten and ends at ninety cents`() {
        assertEquals(
            100.0 / 3.0,
            LiquidityRewardCalculator.qMin(qOne = 100.0, qTwo = 0.0, referenceMidpoint = 0.10),
            1e-9
        )
        assertEquals(
            100.0 / 3.0,
            LiquidityRewardCalculator.qMin(qOne = 100.0, qTwo = 0.0, referenceMidpoint = 0.90),
            1e-9
        )
        assertEquals(
            0.0,
            LiquidityRewardCalculator.qMin(qOne = 100.0, qTwo = 0.0, referenceMidpoint = 0.9001),
            1e-9
        )
    }

    @Test
    fun `share and daily reward use our q min over total q min`() {
        val share = LiquidityRewardCalculator.estimateShare(ourQMin = 100.0, competitorQMin = 900.0)
        assertEquals(0.10, share, 1e-9)
        assertEquals(5.0, LiquidityRewardCalculator.estimateDailyReward(50.0, share), 1e-9)
    }

    private fun book(
        midpoint: Double,
        bids: List<LiquidityRewardLevel> = emptyList(),
        asks: List<LiquidityRewardLevel> = emptyList()
    ) = LiquidityRewardBook(bids = bids, asks = asks, midpoint = midpoint)
}