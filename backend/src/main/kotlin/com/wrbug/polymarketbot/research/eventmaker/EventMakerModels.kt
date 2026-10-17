package com.wrbug.polymarketbot.research.eventmaker

/**
 * Historical market used by the event-maker research backtest.
 *
 * This module is intentionally independent from the live strategy scheduler. It replays
 * public trade prints to estimate whether a leader-style passive quoting approach survives
 * conservative queue and settlement assumptions.
 */
data class EventMakerMarket(
    val conditionId: String,
    val question: String,
    val tag: String,
    val volume: Double,
    val closedTimestamp: Long,
    val winnerIndex: Int,
    val tickSize: Double,
    val trades: List<EventMakerTrade>
)

data class EventMakerTrade(
    val timestamp: Long,
    val outcomeIndex: Int,
    val price: Double,
    val size: Double
)

data class EventMakerConfig(
    val name: String,
    val entryHorizonSeconds: Long = 86_400,
    val minPrice: Double = 0.97,
    val maxPrice: Double = 0.99,
    val entryOffsetCents: Double = 0.5,
    val exitSpreadCents: Double = 1.0,
    val queueFactor: Double = 2.0,
    val requoteSeconds: Long = 300,
    val orderNotional: Double = 100.0,
    val maxCycles: Int = 1,
    val makerFeeRate: Double = 0.0
) {
    init {
        require(entryHorizonSeconds > 0) { "entryHorizonSeconds must be positive" }
        require(minPrice >= 0.0 && minPrice < maxPrice && maxPrice <= 1.0) { "invalid price band" }
        require(entryOffsetCents >= 0.0) { "entryOffsetCents must be non-negative" }
        require(exitSpreadCents >= 0.0) { "exitSpreadCents must be non-negative" }
        require(queueFactor > 0.0) { "queueFactor must be positive" }
        require(requoteSeconds >= 0) { "requoteSeconds must be non-negative" }
        require(orderNotional > 0.0) { "orderNotional must be positive" }
        require(maxCycles >= 1) { "maxCycles must be at least 1" }
        require(makerFeeRate >= 0.0) { "makerFeeRate must be non-negative" }
    }
}

enum class MakerExitType {
    SELL,
    SETTLE
}

data class EventMakerCycle(
    val entryTimestamp: Long,
    val entryPrice: Double,
    val shares: Double,
    val exitTimestamp: Long?,
    val exitPrice: Double?,
    val exitType: MakerExitType,
    val holdingSeconds: Long,
    val pnl: Double,
    val fee: Double
)

data class EventMakerMarketResult(
    val conditionId: String,
    val question: String,
    val tag: String,
    val volume: Double,
    val closedTimestamp: Long,
    val referencePrice: Double?,
    val favoriteOutcomeIndex: Int?,
    val favoriteWon: Boolean?,
    val eligible: Boolean,
    val cycles: List<EventMakerCycle>,
    val deployedNotional: Double,
    val pnl: Double,
    val roi: Double,
    val holdBaselinePnl: Double,
    val holdBaselineRoi: Double,
    val holdBaselineDeployed: Double
) {
    val filled: Boolean get() = cycles.isNotEmpty()
}

data class EventMakerSummary(
    val config: EventMakerConfig,
    val totalMarkets: Int,
    val eligibleMarkets: Int,
    val filledMarkets: Int,
    val fillRate: Double,
    val totalCycles: Int,
    val soldCycles: Int,
    val settledCycles: Int,
    val winningCycles: Int,
    val losingCycles: Int,
    val winRate: Double,
    val deployedNotional: Double,
    val totalPnl: Double,
    val totalRoi: Double,
    val averagePnlPerFilledMarket: Double,
    val worstMarketPnl: Double,
    val maxDrawdown: Double,
    val averageHoldingSeconds: Double,
    val holdBaselineDeployed: Double,
    val holdBaselinePnl: Double,
    val holdBaselineRoi: Double,
    val profitableFilledMarkets: Int,
    val totalFees: Double,
    val trainPnl: Double,
    val trainRoi: Double,
    val trainFilledMarkets: Int,
    val trainCycles: Int,
    val testPnl: Double,
    val testRoi: Double,
    val testFilledMarkets: Int,
    val testCycles: Int,
    val splitTimestamp: Long
)
