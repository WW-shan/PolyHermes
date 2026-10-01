package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.util.PolymarketTradingFee
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Crypto Tail 已实现盈亏：盈利时赎回数量按 1 USDC/份，亏损时归零。
 *
 * 买入成本含 taker 手续费（Polymarket Data API 的 usdcSize 语义就是「实际支出，含手续费」），
 * 因此按 usdcSize 计算时不能再额外扣一次手续费；只有拿不到 usdcSize 时才用 成交额 + 手续费 估算。
 */
object CryptoTailPnlCalculator {
    private const val PNL_SCALE = 8
    private val FIXED_PRICE = BigDecimal("0.99")

    /**
     * @param usdcSize Data API 返回的实际买入支出（含 taker 手续费）；为 null 时用 price × size 加手续费估算
     */
    fun pnlFromFill(
        price: BigDecimal,
        sizeMatched: BigDecimal,
        usdcSize: BigDecimal?,
        won: Boolean
    ): BigDecimal {
        val cost = usdcSize?.takeIf { it > BigDecimal.ZERO }
            ?: sizeMatched.multiply(price)
                .add(PolymarketTradingFee.takerFee(sizeMatched, price, PolymarketTradingFee.CRYPTO_TAKER_FEE_RATE))
        return (if (won) sizeMatched else BigDecimal.ZERO)
            .subtract(cost)
            .setScale(PNL_SCALE, RoundingMode.HALF_UP)
    }

    /**
     * 按结算赔付比例计算盈亏（支持 50/50 等部分赔付）：赎回 = 成交份数 × payoutRatio。
     * @param payoutRatio payoutNumerator / payoutDenominator，赢为 1、输为 0、平局 0.5
     */
    fun pnlFromFillWithPayout(
        price: BigDecimal,
        sizeMatched: BigDecimal,
        usdcSize: BigDecimal?,
        payoutRatio: BigDecimal
    ): BigDecimal {
        val cost = usdcSize?.takeIf { it > BigDecimal.ZERO }
            ?: sizeMatched.multiply(price)
                .add(PolymarketTradingFee.takerFee(sizeMatched, price, PolymarketTradingFee.CRYPTO_TAKER_FEE_RATE))
        return sizeMatched.multiply(payoutRatio)
            .subtract(cost)
            .setScale(PNL_SCALE, RoundingMode.HALF_UP)
    }

    /**
     * 估算盈亏（activity 长期不可用时使用）：用已回写的成交价与投入金额，按含 taker 手续费口径估算。
     * 份数 = amountUsdc / price，成本 = amountUsdc + 手续费；price 无效时返回 null。
     */
    fun pnlEstimated(amountUsdc: BigDecimal, price: BigDecimal, payoutRatio: BigDecimal): BigDecimal? {
        if (price <= BigDecimal.ZERO || amountUsdc <= BigDecimal.ZERO) return null
        val shares = amountUsdc.divide(price, 18, RoundingMode.HALF_UP)
        return pnlFromFillWithPayout(price, shares, null, payoutRatio)
    }

    /**
     * 回退收益计算：无 API 数据时用触发时的 amountUsdc（成交额，不含手续费）与固定价 0.99。
     * 赢: amountUsdc/0.99 - amountUsdc - 手续费；输: -amountUsdc - 手续费
     */
    fun pnlFallback(amountUsdc: BigDecimal, won: Boolean): BigDecimal {
        val shares = amountUsdc.divide(FIXED_PRICE, 18, RoundingMode.HALF_UP)
        val fee = PolymarketTradingFee.takerFee(shares, FIXED_PRICE, PolymarketTradingFee.CRYPTO_TAKER_FEE_RATE)
        return if (won) {
            shares.subtract(amountUsdc).subtract(fee)
        } else {
            amountUsdc.negate().subtract(fee)
        }.setScale(PNL_SCALE, RoundingMode.HALF_UP)
    }
}
