package com.wrbug.polymarketbot.dto

import com.wrbug.polymarketbot.service.eventmaker.EventMakerConfig
import com.wrbug.polymarketbot.service.eventmaker.EventMakerMode

data class EventMakerStartRequest(
    val mode: String = "SHADOW",
    val accountId: Long? = null,
    val maxMarkets: Int = 3,
    val minVolume24hr: Double = 20_000.0,
    val minRemainingMinutes: Int = 720,
    val allowNegRisk: Boolean = true,
    val minPrice: Double = 0.90,
    val maxPrice: Double = 0.99,
    val improveTicks: Int = 1,
    val exitSpreadCents: Double = 1.0,
    val exitImproveTicks: Int = 1,
    val requoteSeconds: Long = 120,
    val orderNotional: Double = 25.0,
    val maxQueueAheadShares: Double = 500.0,
    val minSpreadTicks: Int = 2,
    val maxTotalInventoryNotional: Double = 100.0,
    val maxDailyLoss: Double = 5.0,
    val maxCyclesPerMarket: Int = 3,
    val liveTradingEnabled: Boolean = false,
    val pollSeconds: Long = 3,
    val reconcileSeconds: Long = 2,
    val staleBookSeconds: Long = 30
) {
    fun toConfig(): EventMakerConfig = EventMakerConfig(
        mode = when (mode.uppercase()) {
            "LIVE" -> EventMakerMode.LIVE
            else -> EventMakerMode.SHADOW
        },
        accountId = accountId,
        maxMarkets = maxMarkets,
        minVolume24hr = minVolume24hr,
        minRemainingMinutes = minRemainingMinutes,
        allowNegRisk = allowNegRisk,
        minPrice = minPrice,
        maxPrice = maxPrice,
        improveTicks = improveTicks,
        exitSpreadCents = exitSpreadCents,
        exitImproveTicks = exitImproveTicks,
        requoteSeconds = requoteSeconds,
        orderNotional = orderNotional,
        maxQueueAheadShares = maxQueueAheadShares,
        minSpreadTicks = minSpreadTicks,
        maxTotalInventoryNotional = maxTotalInventoryNotional,
        maxDailyLoss = maxDailyLoss,
        maxCyclesPerMarket = maxCyclesPerMarket,
        liveTradingEnabled = liveTradingEnabled,
        pollSeconds = pollSeconds,
        reconcileSeconds = reconcileSeconds,
        staleBookSeconds = staleBookSeconds
    )
}