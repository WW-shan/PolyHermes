package com.wrbug.polymarketbot.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Polymarket 协议 taker 手续费。
 *
 * 官方公式：fee = C × feeRate × p × (1 - p)，按 5 位小数四舍五入（docs.polymarket.com/trading/fees）。
 * Maker 不收费；只有 taker（FAK/市价成交）才计费。
 *
 * 费率来源优先级：
 * 1. 市场自身的 feeSchedule.rate（Gamma 返回，随市场下发，最权威）；
 * 2. [fallbackRate] 依据 feeType / category 推导（老数据或接口缺字段时的兜底）；
 * 3. 都拿不到时按 0 处理，避免误扣。
 */
object PolymarketTradingFee {

    /** 官方文档分类费率（docs.polymarket.com/trading/fees 费率表） */
    private val CATEGORY_RATES = mapOf(
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

    /**
     * Gamma feeType → 实际费率（2026-09 线上实测：sports_fees_v2 为 0.03，sports_fees_v3 为 0.05，
     * 因此不能只按分类推导）。
     */
    private val FEE_TYPE_RATES = mapOf(
        "crypto_fees_v2" to BigDecimal("0.07"),
        "sports_fees_v2" to BigDecimal("0.03"),
        "sports_fees_v3" to BigDecimal("0.05"),
        "politics_fees" to BigDecimal("0.04"),
        "finance_prices_fees" to BigDecimal("0.04"),
        "finance_fees" to BigDecimal("0.04"),
        "economics_fees" to BigDecimal("0.05"),
        "culture_fees" to BigDecimal("0.05"),
        "weather_fees" to BigDecimal("0.05"),
        "tech_fees" to BigDecimal("0.04"),
        "mentions_fees" to BigDecimal("0.04"),
        "geopolitics_fees" to BigDecimal.ZERO,
        "zero_fees" to BigDecimal.ZERO
    )

    /** Crypto Tail 等加密市场使用 crypto_fees_v2 费率 */
    val CRYPTO_TAKER_FEE_RATE: BigDecimal = BigDecimal("0.07")

    /**
     * 兜底费率推导：优先按 feeType 精确匹配，其次按分类（含 feeType 前缀），未知返回 0
     */
    fun fallbackRate(feeType: String?, category: String?): BigDecimal {
        val normalizedFeeType = feeType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (normalizedFeeType != null) {
            FEE_TYPE_RATES[normalizedFeeType]?.let { return it }
        }
        val normalizedCategory = category?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (normalizedCategory != null) {
            CATEGORY_RATES[normalizedCategory]?.let { return it }
        }
        // 未知 feeType 时尝试去掉后缀（如 crypto_fees_v9 -> crypto）
        if (normalizedFeeType != null) {
            val base = normalizedFeeType.substringBefore("_fees")
            CATEGORY_RATES[base]?.let { return it }
        }
        return BigDecimal.ZERO
    }

    /**
     * 已实现盈亏：先算价差，再扣除买入和卖出两次 taker 手续费。
     * 适用于 FAK/市价成交的跟单和回测；赎回没有手续费，不应调用本方法。
     *
     * @param feeRate 市场 taker 费率（见 [fallbackRate]，未知传 null）
     */
    fun netRealizedPnl(
        buyPrice: BigDecimal,
        sellPrice: BigDecimal,
        shares: BigDecimal,
        feeRate: BigDecimal?
    ): BigDecimal {
        val gross = sellPrice.subtract(buyPrice).multiply(shares)
        return gross
            .subtract(takerFee(shares, buyPrice, feeRate))
            .subtract(takerFee(shares, sellPrice, feeRate))
            .setScale(8, RoundingMode.HALF_UP)
    }

    fun takerFee(shares: BigDecimal, price: BigDecimal, feeRate: BigDecimal?): BigDecimal {
        if (shares <= BigDecimal.ZERO || price <= BigDecimal.ZERO || price >= BigDecimal.ONE) {
            return BigDecimal.ZERO
        }
        val rate = feeRate ?: BigDecimal.ZERO
        if (rate <= BigDecimal.ZERO) return BigDecimal.ZERO
        return shares
            .multiply(rate)
            .multiply(price)
            .multiply(BigDecimal.ONE.subtract(price))
            .setScale(5, RoundingMode.HALF_UP)
    }
}
