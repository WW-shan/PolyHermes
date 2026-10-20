package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.MarketBook
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

data class PairedRewardQuotePlan(
    val yesPrice: BigDecimal,
    val noPrice: BigDecimal,
    val shares: BigDecimal,
    val pairCostPerShare: Double,
    val pairSpreadProfit: Double,
    val yesScore: Double,
    val noScore: Double,
    val ourQMin: Double,
    val eligible: Boolean,
    val reason: String
)

/**
 * Plans two post-only BUY orders on complementary outcome tokens. Both bids
 * must meet the market's reward minimum and remain inside its reward spread.
 * This planner does not assume that either side fills; execution/hedging is a
 * separate responsibility.
 */
object PairedRewardQuotePlanner {
    private const val EPSILON = 1e-9

    fun plan(
        market: LivePaperMarket,
        yesBook: MarketBook,
        noBook: MarketBook,
        improveTicks: Int = 0,
        requestedShares: Double = 0.0,
        maxPairCostPerShare: Double = 1.0
    ): PairedRewardQuotePlan {
        require(improveTicks >= 0) { "improveTicks must be non-negative" }
        require(requestedShares >= 0.0) { "requestedShares must be non-negative" }
        require(maxPairCostPerShare > 0.0) { "maxPairCostPerShare must be positive" }
        require(market.tokenIds.size >= 2) { "a binary market must have two token ids" }
        if (market.rewardDailyRate <= 0.0) return ineligible("no_active_reward")
        if (market.rewardsMinSize <= 0.0 || market.rewardsMaxSpread <= 0.0) {
            return ineligible("missing_reward_config")
        }
        if (yesBook.tokenId != market.tokenIds[0] || noBook.tokenId != market.tokenIds[1]) {
            return ineligible("book_token_mismatch")
        }
        val yesBestBid = yesBook.bestBid ?: return ineligible("missing_yes_bid")
        val yesBestAsk = yesBook.bestAsk ?: return ineligible("missing_yes_ask")
        val noBestBid = noBook.bestBid ?: return ineligible("missing_no_bid")
        val noBestAsk = noBook.bestAsk ?: return ineligible("missing_no_ask")
        if (yesBestAsk <= yesBestBid || noBestAsk <= noBestBid) return ineligible("crossed_book")

        val shares = max(market.rewardsMinSize, requestedShares)
        val yesPrice = postOnlyBid(yesBestBid, yesBestAsk, market.tickSize, improveTicks)
        val noPrice = postOnlyBid(noBestBid, noBestAsk, market.tickSize, improveTicks)
        val pairCost = yesPrice.toDouble() + noPrice.toDouble()
        val config = LiquidityRewardConfig(
            minSizeShares = shares,
            maxSpreadCents = market.rewardsMaxSpread
        )
        val yesMidpoint = (yesBestBid + yesBestAsk) / 2.0
        val noMidpoint = (noBestBid + noBestAsk) / 2.0
        val yesRewardBook = LiquidityRewardBook(
            bids = listOf(LiquidityRewardLevel(yesPrice.toDouble(), shares)),
            asks = emptyList(),
            midpoint = yesMidpoint
        )
        val noRewardBook = LiquidityRewardBook(
            bids = listOf(LiquidityRewardLevel(noPrice.toDouble(), shares)),
            asks = emptyList(),
            midpoint = noMidpoint
        )
        val score = LiquidityRewardCalculator.calculate(yesRewardBook, noRewardBook, config)
        val eligible = score.qMin > EPSILON && pairCost <= maxPairCostPerShare + EPSILON
        return PairedRewardQuotePlan(
            yesPrice = yesPrice,
            noPrice = noPrice,
            shares = BigDecimal.valueOf(shares).setScale(6, RoundingMode.DOWN),
            pairCostPerShare = pairCost,
            pairSpreadProfit = (1.0 - pairCost) * shares,
            yesScore = LiquidityRewardCalculator.scoreOrder(
                yesPrice.toDouble(),
                EventMakerSide.BUY,
                yesMidpoint,
                config
            ),
            noScore = LiquidityRewardCalculator.scoreOrder(
                noPrice.toDouble(),
                EventMakerSide.BUY,
                noMidpoint,
                config
            ),
            ourQMin = score.qMin,
            eligible = eligible,
            reason = when {
                score.qMin <= EPSILON -> "not_reward_eligible"
                pairCost > maxPairCostPerShare + EPSILON -> "pair_cost_above_limit"
                else -> "eligible"
            }
        )
    }

    private fun postOnlyBid(bestBid: Double, bestAsk: Double, tickSize: Double, improveTicks: Int): BigDecimal {
        val tick = tickSize.takeIf { it > 0.0 } ?: 0.001
        val tickDecimal = BigDecimal.valueOf(tick)
        val bid = BigDecimal.valueOf(bestBid)
            .add(tickDecimal.multiply(BigDecimal.valueOf(improveTicks.toLong())))
            .min(BigDecimal.valueOf(bestAsk).subtract(tickDecimal))
        return bid.divide(tickDecimal, 0, RoundingMode.HALF_UP).multiply(tickDecimal)
            .setScale(6, RoundingMode.HALF_UP)
    }

    private fun ineligible(reason: String): PairedRewardQuotePlan = PairedRewardQuotePlan(
        yesPrice = BigDecimal.ZERO,
        noPrice = BigDecimal.ZERO,
        shares = BigDecimal.ZERO,
        pairCostPerShare = 0.0,
        pairSpreadProfit = 0.0,
        yesScore = 0.0,
        noScore = 0.0,
        ourQMin = 0.0,
        eligible = false,
        reason = reason
    )
}