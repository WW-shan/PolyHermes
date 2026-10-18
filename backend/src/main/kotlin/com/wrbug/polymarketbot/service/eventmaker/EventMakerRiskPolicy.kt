package com.wrbug.polymarketbot.service.eventmaker

import org.springframework.stereotype.Component

@Component
class EventMakerRiskPolicy {

    fun canOpenBuy(config: EventMakerConfig, snapshot: EventMakerRiskSnapshot): EventMakerRiskDecision {
        if (snapshot.dailyPnl <= -config.maxDailyLoss) {
            return EventMakerRiskDecision.reject("daily_loss_limit")
        }
        if (snapshot.totalInventoryNotional + config.orderNotional > config.maxTotalInventoryNotional) {
            return EventMakerRiskDecision.reject("total_inventory_limit")
        }
        if (snapshot.openMarketCount >= config.maxMarkets) {
            return EventMakerRiskDecision.reject("max_open_markets")
        }
        return EventMakerRiskDecision.ALLOWED
    }

    fun shouldHalt(config: EventMakerConfig, dailyPnl: Double): Boolean =
        dailyPnl <= -config.maxDailyLoss
}
