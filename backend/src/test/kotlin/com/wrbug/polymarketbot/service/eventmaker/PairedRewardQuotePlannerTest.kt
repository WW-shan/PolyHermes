package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.BookLevel
import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.MarketBook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PairedRewardQuotePlannerTest {
    @Test
    fun `paired bids near one are reward eligible and capture pair spread`() {
        val market = rewardMarket()
        val plan = PairedRewardQuotePlanner.plan(
            market = market,
            yesBook = book("yes", 0.95, 0.96),
            noBook = book("no", 0.04, 0.05)
        )

        assertTrue(plan.eligible)
        assertEquals("eligible", plan.reason)
        assertEquals(0, plan.yesPrice.compareTo(BigDecimal("0.95")))
        assertEquals(0, plan.noPrice.compareTo(BigDecimal("0.04")))
        assertEquals("20.000000", plan.shares.toPlainString())
        assertEquals(0.99, plan.pairCostPerShare, 1e-9)
        assertEquals(0.2, plan.pairSpreadProfit, 1e-9)
        assertTrue(plan.ourQMin > 0.0)
    }

    @Test
    fun `shares are raised to the reward minimum`() {
        val plan = PairedRewardQuotePlanner.plan(
            market = rewardMarket(),
            yesBook = book("yes", 0.95, 0.96),
            noBook = book("no", 0.04, 0.05),
            requestedShares = 5.0
        )

        assertEquals("20.000000", plan.shares.toPlainString())
    }

    @Test
    fun `pair cost above limit is not approved`() {
        val plan = PairedRewardQuotePlanner.plan(
            market = rewardMarket(),
            yesBook = book("yes", 0.96, 0.97),
            noBook = book("no", 0.05, 0.06)
        )

        assertFalse(plan.eligible)
        assertEquals("pair_cost_above_limit", plan.reason)
        assertEquals(1.01, plan.pairCostPerShare, 1e-9)
    }

    @Test
    fun `market without active reward is rejected`() {
        val plan = PairedRewardQuotePlanner.plan(
            market = rewardMarket(rewardDailyRate = 0.0),
            yesBook = book("yes", 0.95, 0.96),
            noBook = book("no", 0.04, 0.05)
        )

        assertFalse(plan.eligible)
        assertEquals("no_active_reward", plan.reason)
    }

    @Test
    fun `improving bid is clamped below ask to remain post only`() {
        val plan = PairedRewardQuotePlanner.plan(
            market = rewardMarket(),
            yesBook = book("yes", 0.95, 0.952),
            noBook = book("no", 0.048, 0.05),
            improveTicks = 2
        )

        assertEquals(0, plan.yesPrice.compareTo(BigDecimal("0.951")))
        assertTrue(plan.yesPrice < BigDecimal.valueOf(0.952))
    }

    private fun rewardMarket(rewardDailyRate: Double = 300.0) = LivePaperMarket(
        conditionId = "condition",
        question = "reward market",
        tag = "Politics",
        volume24hr = 10_000.0,
        endTimestamp = Long.MAX_VALUE,
        tickSize = 0.001,
        outcomes = listOf("Yes", "No"),
        tokenIds = listOf("yes", "no"),
        favoriteIndex = 0,
        rewardDailyRate = rewardDailyRate,
        rewardsMinSize = 20.0,
        rewardsMaxSpread = 6.5
    )

    private fun book(tokenId: String, bid: Double, ask: Double) = MarketBook(
        tokenId = tokenId,
        bids = listOf(BookLevel(bid, 1_000.0)),
        asks = listOf(BookLevel(ask, 1_000.0)),
        updatedAt = 1_000L
    )
}