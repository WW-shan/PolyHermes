package com.wrbug.polymarketbot.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Polymarket 协议 taker 手续费。
 *
 * 官方公式：fee = C × feeRate × p × (1 - p)，按 5 位小数四舍五入。
 * Maker 不收费；未知分类按 0 处理，避免在无法确认市场类别时误扣。
 */
object PolymarketTradingFee {
    private val FEE_RATES = mapOf(
        "crypto" to BigDecimal("0.07"),
        "sports" to BigDecimal("0.05"),
        "finance" to BigDecimal("0.04"),
        "politics" to BigDecimal("0.04"),
        "economics" to BigDecimal("0.05"),
        "culture" to BigDecimal("0.05"),
        "weather" to BigDecimal("0.05"),
        "other" to BigDecimal("0.05"),
        "general" to BigDecimal("0.05"),
        "mentions" to BigDecimal("0.04"),
        "tech" to BigDecimal("0.04"),
        "geopolitics" to BigDecimal.ZERO
    )

    fun takerFeeRate(category: String?): BigDecimal {
        val normalized = category?.trim()?.lowercase() ?: return BigDecimal.ZERO
        return FEE_RATES[normalized] ?: BigDecimal.ZERO
    }

    /**
     * 已实现盈亏：先算价差，再扣除买入和卖出两次 taker 手续费。
     * 适用于 FAK/市价成交的跟单和回测；赎回没有手续费，不应调用本方法。
     */
    fun netRealizedPnl(
        buyPrice: BigDecimal,
        sellPrice: BigDecimal,
        shares: BigDecimal,
        category: String?
    ): BigDecimal {
        val gross = sellPrice.subtract(buyPrice).multiply(shares)
        return gross
            .subtract(takerFee(shares, buyPrice, category))
            .subtract(takerFee(shares, sellPrice, category))
            .setScale(8, RoundingMode.HALF_UP)
    }

    fun takerFee(shares: BigDecimal, price: BigDecimal, category: String?): BigDecimal {
        if (shares <= BigDecimal.ZERO || price <= BigDecimal.ZERO || price >= BigDecimal.ONE) {
            return BigDecimal.ZERO
        }
        val rate = takerFeeRate(category)
        if (rate <= BigDecimal.ZERO) return BigDecimal.ZERO
        return shares
            .multiply(rate)
            .multiply(price)
            .multiply(BigDecimal.ONE.subtract(price))
            .setScale(5, RoundingMode.HALF_UP)
    }
}
