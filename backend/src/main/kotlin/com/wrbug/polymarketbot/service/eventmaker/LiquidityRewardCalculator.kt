package com.wrbug.polymarketbot.service.eventmaker

import kotlin.math.max
import kotlin.math.min

/**
 * One side of an outcome-token order book used by Polymarket's liquidity
 * reward score. Sizes are share-denominated, matching the official formula.
 */
data class LiquidityRewardLevel(
    val price: Double,
    val sizeShares: Double
)

/**
 * A reward-eligible snapshot. The midpoint is explicit because Polymarket's
 * scoring uses a size-cutoff-adjusted midpoint rather than necessarily using
 * the raw best bid/ask midpoint.
 */
data class LiquidityRewardBook(
    val bids: List<LiquidityRewardLevel>,
    val asks: List<LiquidityRewardLevel>,
    val midpoint: Double
) {
    init {
        require(midpoint > 0.0 && midpoint < 1.0) { "midpoint must be in (0, 1)" }
        require(bids.all { it.price in 0.0..1.0 && it.sizeShares >= 0.0 }) { "invalid bid" }
        require(asks.all { it.price in 0.0..1.0 && it.sizeShares >= 0.0 }) { "invalid ask" }
    }
}

data class LiquidityRewardConfig(
    val minSizeShares: Double,
    val maxSpreadCents: Double,
    val scalingFactor: Double = 3.0,
    val inGameMultiplier: Double = 1.0
) {
    init {
        require(minSizeShares > 0.0) { "minSizeShares must be positive" }
        require(maxSpreadCents > 0.0) { "maxSpreadCents must be positive" }
        require(scalingFactor >= 1.0) { "scalingFactor must be at least 1" }
        require(inGameMultiplier >= 0.0) { "inGameMultiplier must be non-negative" }
    }
}

data class LiquidityRewardScore(
    val qOne: Double,
    val qTwo: Double,
    val qMin: Double,
    val qualifyingYesBidShares: Double,
    val qualifyingYesAskShares: Double,
    val qualifyingNoBidShares: Double,
    val qualifyingNoAskShares: Double
)

/**
 * Pure implementation of the public liquidity-reward scoring formula.
 *
 * Q_one = scored YES bids + scored NO asks
 * Q_two = scored YES asks + scored NO bids
 *
 * At a reference midpoint in [0.10, 0.90], one-sided liquidity is allowed at
 * one third weight. Outside that band, both sides are required.
 */
object LiquidityRewardCalculator {
    private const val EPSILON = 1e-9

    fun scoreOrder(
        price: Double,
        side: EventMakerSide,
        midpoint: Double,
        config: LiquidityRewardConfig
    ): Double {
        if (!price.isFinite() || !midpoint.isFinite() || price < 0.0 || price > 1.0) return 0.0
        val spreadCents = when (side) {
            EventMakerSide.BUY -> (midpoint - price) * 100.0
            EventMakerSide.SELL -> (price - midpoint) * 100.0
        }
        if (spreadCents < -EPSILON || spreadCents > config.maxSpreadCents + EPSILON) return 0.0
        val normalized = ((config.maxSpreadCents - max(0.0, spreadCents)) / config.maxSpreadCents)
            .coerceIn(0.0, 1.0)
        return normalized * normalized * config.inGameMultiplier
    }

    fun calculate(
        yesBook: LiquidityRewardBook,
        noBook: LiquidityRewardBook,
        config: LiquidityRewardConfig,
        referenceMidpoint: Double = (yesBook.midpoint + (1.0 - noBook.midpoint)) / 2.0
    ): LiquidityRewardScore {
        require(referenceMidpoint > 0.0 && referenceMidpoint < 1.0) { "referenceMidpoint must be in (0, 1)" }
        val yesBids = scoredSide(yesBook.bids, EventMakerSide.BUY, yesBook.midpoint, config)
        val yesAsks = scoredSide(yesBook.asks, EventMakerSide.SELL, yesBook.midpoint, config)
        val noBids = scoredSide(noBook.bids, EventMakerSide.BUY, noBook.midpoint, config)
        val noAsks = scoredSide(noBook.asks, EventMakerSide.SELL, noBook.midpoint, config)
        val qOne = yesBids + noAsks
        val qTwo = yesAsks + noBids
        return LiquidityRewardScore(
            qOne = qOne,
            qTwo = qTwo,
            qMin = qMin(qOne, qTwo, referenceMidpoint, config.scalingFactor),
            qualifyingYesBidShares = qualifyingShares(yesBook.bids, EventMakerSide.BUY, config, yesBook.midpoint),
            qualifyingYesAskShares = qualifyingShares(yesBook.asks, EventMakerSide.SELL, config, yesBook.midpoint),
            qualifyingNoBidShares = qualifyingShares(noBook.bids, EventMakerSide.BUY, config, noBook.midpoint),
            qualifyingNoAskShares = qualifyingShares(noBook.asks, EventMakerSide.SELL, config, noBook.midpoint)
        )
    }

    fun qMin(qOne: Double, qTwo: Double, referenceMidpoint: Double, scalingFactor: Double = 3.0): Double {
        require(qOne >= 0.0 && qTwo >= 0.0) { "q scores must be non-negative" }
        require(referenceMidpoint >= 0.0 && referenceMidpoint <= 1.0) { "referenceMidpoint must be in [0, 1]" }
        require(scalingFactor >= 1.0) { "scalingFactor must be at least 1" }
        val minimum = min(qOne, qTwo)
        return if (referenceMidpoint >= 0.10 && referenceMidpoint <= 0.90) {
            max(minimum, max(qOne / scalingFactor, qTwo / scalingFactor))
        } else {
            minimum
        }
    }

    fun estimateShare(ourQMin: Double, competitorQMin: Double): Double {
        require(ourQMin >= 0.0 && competitorQMin >= 0.0) { "q scores must be non-negative" }
        val denominator = ourQMin + competitorQMin
        return if (denominator <= EPSILON) 0.0 else ourQMin / denominator
    }

    fun estimateDailyReward(dailyPool: Double, share: Double): Double {
        require(dailyPool >= 0.0) { "dailyPool must be non-negative" }
        require(share in 0.0..1.0) { "share must be in [0, 1]" }
        return dailyPool * share
    }

    private fun scoredSide(
        levels: List<LiquidityRewardLevel>,
        side: EventMakerSide,
        midpoint: Double,
        config: LiquidityRewardConfig
    ): Double = levels.asSequence()
        .filter { it.sizeShares + EPSILON >= config.minSizeShares }
        .sumOf { scoreOrder(it.price, side, midpoint, config) * it.sizeShares }

    private fun qualifyingShares(
        levels: List<LiquidityRewardLevel>,
        side: EventMakerSide,
        config: LiquidityRewardConfig,
        midpoint: Double
    ): Double = levels.asSequence()
        .filter { it.sizeShares + EPSILON >= config.minSizeShares }
        .filter { scoreOrder(it.price, side, midpoint, config) > 0.0 }
        .sumOf { it.sizeShares }
}