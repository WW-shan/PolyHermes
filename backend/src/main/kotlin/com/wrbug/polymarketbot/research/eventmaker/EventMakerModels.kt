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
    val size: Double,
    /**
     * Taker side as reported by the Data API ("BUY"/"SELL"). A resting maker BUY can
     * only be filled by taker SELL flow, and vice versa. Null means the side was not
     * available; those prints are treated as non-filling so the simulation never
     * assumes liquidity it cannot prove.
     */
    val side: String? = null
) {
    val takerIsBuy: Boolean get() = side.equals("BUY", ignoreCase = true)
    val takerIsSell: Boolean get() = side.equals("SELL", ignoreCase = true)
}

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
    val makerFeeRate: Double = 0.0,
    /**
     * Optional activity gate mirroring the Shadow/Live discovery filter. When set,
     * a resting order is only placed if the trailing window on the quoted token had
     * enough recent prints/notional on both sides. This keeps the backtest from
     * claiming fills in markets the live selector would never quote. All zeros
     * disable the gate (legacy behavior).
     */
    val minRecentTrades30m: Int = 0,
    val minRecentNotional30m: Double = 0.0,
    val minRecentTradesPerSide30m: Int = 0,
    val minRecentTrades2h: Int = 0,
    val minRecentNotional2h: Double = 0.0,
    val minRecentTradesPerSide2h: Int = 0
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
        require(minRecentTrades30m >= 0 && minRecentTrades2h >= 0) { "activity trade counts must be non-negative" }
        require(minRecentTradesPerSide30m >= 0 && minRecentTradesPerSide2h >= 0) { "activity per-side counts must be non-negative" }
        require(minRecentNotional30m >= 0.0 && minRecentNotional2h >= 0.0) { "activity notional must be non-negative" }
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
    val fee: Double,
    val timeToFillSeconds: Long = 0,
    val maxAdverseExcursion: Double = 0.0,
    val markout60sPnl: Double? = null
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
    val holdBaselineDeployed: Double,
    val maxAdverseExcursion: Double = 0.0
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
    val splitTimestamp: Long,
    val maxAdverseExcursion: Double = 0.0,
    val averageTimeToFillSeconds: Double = 0.0,
    val settledLosses: Int = 0,
    val breakEvenLossRate: Double = 0.0
)
