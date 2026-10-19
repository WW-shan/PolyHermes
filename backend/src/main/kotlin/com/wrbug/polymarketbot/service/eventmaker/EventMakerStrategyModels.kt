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
    val allowNegRisk: Boolean = true,
    val minPrice: Double = 0.90,
    val maxPrice: Double = 0.99,
    val improveTicks: Int = 1,
    val entryOffsetCents: Double = 0.0,
    val exitSpreadCents: Double = 1.0,
    val exitImproveTicks: Int = 1,
    val requoteSeconds: Long = 120,
    val maxOrderAgeSeconds: Long = 3600,
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
        require(minPrice >= 0.0 && minPrice < maxPrice && maxPrice <= 1.0)
        require(improveTicks >= 0)
        require(entryOffsetCents >= 0.0)
        require(exitSpreadCents > 0.0)
        require(exitImproveTicks >= 0)
        require(requoteSeconds > 0)
        require(maxOrderAgeSeconds >= requoteSeconds)
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
    val createdAt: Long = System.currentTimeMillis()
)

data class EventMakerPersistedState(
    val version: Int = 2,
    val mode: String,
    val accountId: Long?,
    val startedAt: Long?,
    val cumulativeRealizedPnl: Double = 0.0,
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
    val favoriteTokenId: String? = null
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
    val holdingSeconds: Long
)
