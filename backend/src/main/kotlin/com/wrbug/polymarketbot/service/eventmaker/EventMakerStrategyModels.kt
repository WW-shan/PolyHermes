package com.wrbug.polymarketbot.service.eventmaker

import java.math.BigDecimal

enum class EventMakerMode { SHADOW, LIVE }

enum class EventMakerSide { BUY, SELL }

enum class EventMakerPhase { FLAT, BUY_PENDING, LONG, SELL_PENDING }

data class EventMakerConfig(
    val mode: EventMakerMode = EventMakerMode.SHADOW,
    val accountId: Long? = null,
    val maxMarkets: Int = 3,
    val minVolume24hr: Double = 20_000.0,
    val minRecentTrades30m: Int = 0,
    val minRecentNotional30m: Double = 0.0,
    val minRecentTradesPerSide30m: Int = 0,
    val minRecentTrades2h: Int = 0,
    val minRecentNotional2h: Double = 0.0,
    val minRecentTradesPerSide2h: Int = 0,
    val minRemainingMinutes: Int = 720,
    /**
     * Optional upper bound on time to settlement, mirroring the backtest's
     * entry horizon. Null keeps the previous unbounded behavior.
     */
    val maxRemainingMinutes: Int? = null,
    /**
     * How long discovery may keep retrying while no market passes every filter.
     * 0 keeps the old fail-fast behavior.
     */
    val waitForMarketMinutes: Int = 0,
    val discoveryRetrySeconds: Long = 60,
    val allowNegRisk: Boolean = true,
    val minPrice: Double = 0.90,
    val maxPrice: Double = 0.99,
    val improveTicks: Int = 1,
    val entryOffsetCents: Double = 0.0,
    val exitSpreadCents: Double = 1.0,
    val exitImproveTicks: Int = 1,
    val requoteSeconds: Long = 120,
    val maxOrderAgeSeconds: Long = 3600,
    /**
     * Pre-trade tail window: stop quoting (and cancel a resting BUY) once fewer
     * than this many minutes remain before market close. This avoids late
     * gap-to-zero flows using only the public close timestamp. 0 disables it.
     */
    val tailWindowMinutes: Long = 0,
    /**
     * Causal adverse-flow guard. It blocks/cancels BUY quotes when the favorite
     * token has net taker SELL flow and a material price drop in this trailing
     * window. A zero window disables the guard.
     */
    val flowGuardWindowSeconds: Long = 3600,
    val flowGuardMaxSignedFlow: Double = -0.4,
    val flowGuardMinPriceDropCents: Double = 0.5,
    val orderNotional: Double = 25.0,
    val maxQueueAheadShares: Double = 500.0,
    val shadowFillVolumeFactor: Double = 1.0,
    val minSpreadTicks: Int = 2,
    val maxTotalInventoryNotional: Double = 100.0,
    val maxDailyLoss: Double = 5.0,
    val maxCyclesPerMarket: Int = 3,
    val liveTradingEnabled: Boolean = false,
    val pollSeconds: Long = 3,
    val reconcileSeconds: Long = 2,
    val staleBookSeconds: Long = 30,
    val activeOrderStaleBookSeconds: Long = 600
) {
    init {
        require(maxMarkets >= 1)
        require(minVolume24hr >= 0.0)
        require(minRecentTrades30m >= 0)
        require(minRecentNotional30m >= 0.0)
        require(minRecentTradesPerSide30m >= 0)
        require(minRecentTrades2h >= 0)
        require(minRecentNotional2h >= 0.0)
        require(minRecentTradesPerSide2h >= 0)
        require(minRemainingMinutes >= 1)
        require(maxRemainingMinutes == null || maxRemainingMinutes > minRemainingMinutes)
        require(waitForMarketMinutes >= 0)
        require(discoveryRetrySeconds > 0)
        require(minPrice >= 0.0 && minPrice < maxPrice && maxPrice <= 1.0)
        require(improveTicks >= 0)
        require(entryOffsetCents >= 0.0)
        require(exitSpreadCents > 0.0)
        require(exitImproveTicks >= 0)
        require(requoteSeconds > 0)
        require(maxOrderAgeSeconds >= requoteSeconds)
        require(tailWindowMinutes >= 0)
        require(tailWindowMinutes == 0L || tailWindowMinutes < minRemainingMinutes) {
            "tailWindowMinutes must be smaller than minRemainingMinutes"
        }
        require(flowGuardWindowSeconds >= 0)
        require(flowGuardMaxSignedFlow in -1.0..1.0)
        require(flowGuardMinPriceDropCents >= 0.0)
        require(orderNotional > 0.0)
        require(maxQueueAheadShares >= 0.0)
        require(shadowFillVolumeFactor >= 0.0)
        require(minSpreadTicks >= 1)
        require(maxTotalInventoryNotional > 0.0)
        require(maxDailyLoss > 0.0)
        require(maxCyclesPerMarket >= 1)
        require(pollSeconds > 0)
        require(reconcileSeconds > 0)
        require(staleBookSeconds > 0)
        require(activeOrderStaleBookSeconds >= staleBookSeconds)
    }
}

data class EventMakerQuoteIntent(
    val side: EventMakerSide,
    val price: BigDecimal,
    val shares: BigDecimal,
    val queueAheadShares: Double,
    val reason: String
)

data class EventMakerRiskSnapshot(
    val totalInventoryNotional: Double,
    val openMarketCount: Int,
    val dailyPnl: Double
)

data class EventMakerRiskDecision(
    val allowed: Boolean,
    val reason: String? = null
) {
    companion object {
        val ALLOWED = EventMakerRiskDecision(true)
        fun reject(reason: String) = EventMakerRiskDecision(false, reason)
    }
}

data class EventMakerLiveOrder(
    val orderId: String,
    val side: EventMakerSide,
    val price: BigDecimal,
    val originalShares: BigDecimal,
    val matchedShares: BigDecimal,
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** CLOB asset id. Present on orders created by the adapter and used for lookup validation. */
    val tokenId: String? = null,
    /** CLOB condition id. Present on orders created by the adapter and used for lookup validation. */
    val conditionId: String? = null,
    /** Funder/maker address returned by the CLOB API. */
    val makerAddress: String? = null
)

data class EventMakerPersistedState(
    val version: Int = 4,
    val mode: String,
    val accountId: Long?,
    val startedAt: Long?,
    val cumulativeRealizedPnl: Double = 0.0,
    /** UTC epoch day whose daily loss budget [dailyPnlBaseline] belongs to. */
    val dailyPnlDay: Long? = null,
    /** Cumulative realized PnL at the start of [dailyPnlDay]. */
    val dailyPnlBaseline: Double? = null,
    val markets: List<EventMakerPersistedMarket>
)

data class EventMakerPersistedMarket(
    val conditionId: String,
    val phase: String,
    val inventoryShares: String,
    val entryPrice: String,
    val entryAt: Long,
    val realizedPnl: Double,
    val completedCycles: Int,
    val activeOrderId: String?,
    val activeOrderSide: String?,
    val activeOrderPrice: String?,
    val activeOrderOriginalShares: String?,
    val activeOrderMatchedShares: String?,
    val activeOrderStatus: String?,
    val totalBuyNotional: Double = 0.0,
    val activeOrderCreatedAt: Long = 0L,
    val currentEntryOrderId: String? = null,
    val currentEntryShares: String? = null,
    val fills: List<EventMakerFillRecord>? = null,
    val cycles: List<EventMakerCycleRecord>? = null,
    val favoriteTokenId: String? = null,
    val settlements: List<EventMakerSettlementRecord>? = null,
    /** Minimal immutable market metadata used to recover an orphaned settlement after restart. */
    val favoriteIndex: Int? = null,
    val negativeRisk: Boolean? = null,
    val endTimestamp: Long? = null,
    val tickSize: Double? = null
)

data class EventMakerStatus(
    val running: Boolean,
    val mode: EventMakerMode,
    val startedAt: Long?,
    val stoppedAt: Long?,
    val markets: List<String>,
    val trades: Long,
    val bookUpdates: Long,
    val completedCycles: Int,
    val realizedPnl: Double,
    val unrealizedPnl: Double,
    val openInventoryShares: Double,
    val totalInventoryNotional: Double,
    val lastError: String? = null,
    val recentFills: List<EventMakerFillRecord> = emptyList(),
    val recentCycles: List<EventMakerCycleRecord> = emptyList()
)

data class EventMakerFillRecord(
    val marketId: String,
    val orderId: String,
    val side: EventMakerSide,
    val price: BigDecimal,
    val shares: BigDecimal,
    val timestamp: Long
)

data class EventMakerCycleRecord(
    val marketId: String,
    val entryOrderId: String,
    val exitOrderId: String,
    val entryAt: Long,
    val exitAt: Long,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val shares: BigDecimal,
    val pnl: Double,
    val holdingSeconds: Long,
    /** SELL for a maker exit, SETTLE for a resolved-market payout. */
    val exitType: String = "SELL"
)

enum class EventMakerRedemptionStatus {
    PENDING,
    REDEEMED,
    ALREADY_ABSENT
}

/**
 * An immutable settlement ledger entry. The payout is booked before redemption
 * so a crash can never make an on-chain position disappear without a local
 * record of its determined value.
 */
data class EventMakerSettlementRecord(
    val marketId: String,
    val favoriteTokenId: String,
    val entryOrderId: String,
    val cycleExitOrderId: String,
    val settledAt: Long,
    val entryAt: Long,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val shares: BigDecimal,
    val pnl: Double,
    val outcomeIndex: Int,
    val negativeRisk: Boolean,
    val redemptionStatus: EventMakerRedemptionStatus,
    val transactionHash: String? = null,
    val redemptionUpdatedAt: Long = settledAt
)

data class EventMakerSettlementPosition(
    val tokenId: String,
    val shares: BigDecimal,
    val redeemable: Boolean,
    val outcomeIndex: Int? = null,
    val negativeRisk: Boolean? = null
)

data class EventMakerSettlementSnapshot(
    val resolutionOutcomeIndex: Int?,
    val position: EventMakerSettlementPosition?
) {
    val resolved: Boolean get() = resolutionOutcomeIndex != null
}
