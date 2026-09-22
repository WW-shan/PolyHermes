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
