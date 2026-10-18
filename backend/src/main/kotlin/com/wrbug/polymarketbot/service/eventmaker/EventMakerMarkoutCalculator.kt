package com.wrbug.polymarketbot.service.eventmaker

import java.math.BigDecimal

object EventMakerMarkoutCalculator {
    fun calculate(
        side: EventMakerSide,
        fillPrice: BigDecimal,
        shares: BigDecimal,
        bestBid: Double?,
        bestAsk: Double?
    ): Double? = when (side) {
        EventMakerSide.BUY -> bestBid?.let { (it - fillPrice.toDouble()) * shares.toDouble() }
        EventMakerSide.SELL -> bestAsk?.let { (fillPrice.toDouble() - it) * shares.toDouble() }
    }
}