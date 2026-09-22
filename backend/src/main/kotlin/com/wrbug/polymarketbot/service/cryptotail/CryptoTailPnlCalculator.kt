package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.util.PolymarketTradingFee
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Crypto Tail 已实现盈亏：盈利时赎回数量按 1 USDC/份，亏损时归零，
 * 两种情况都扣除买入时的 Crypto taker 手续费。
 */
object CryptoTailPnlCalculator {
    private const val CRYPTO_CATEGORY = "crypto"
    private const val PNL_SCALE = 8
    private val FIXED_PRICE = BigDecimal("0.99")

    fun pnlFromFill(
        price: BigDecimal,
        sizeMatched: BigDecimal,
        amountUsdc: BigDecimal,
        won: Boolean
    ): BigDecimal {
        val fee = PolymarketTradingFee.takerFee(sizeMatched, price, CRYPTO_CATEGORY)
        return if (won) {
            sizeMatched.subtract(amountUsdc).subtract(fee)
        } else {
            amountUsdc.negate().subtract(fee)
        }.setScale(PNL_SCALE, RoundingMode.HALF_UP)
    }

    fun pnlFallback(amountUsdc: BigDecimal, won: Boolean): BigDecimal {
        val shares = amountUsdc.divide(FIXED_PRICE, 18, RoundingMode.HALF_UP)
        val fee = PolymarketTradingFee.takerFee(shares, FIXED_PRICE, CRYPTO_CATEGORY)
        return if (won) {
            shares.subtract(amountUsdc).subtract(fee)
        } else {
            amountUsdc.negate().subtract(fee)
        }.setScale(PNL_SCALE, RoundingMode.HALF_UP)
    }
}
