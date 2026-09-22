package com.wrbug.polymarketbot.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PolymarketTradingFeeTest {

    @Test
    fun `uses current crypto and sports taker fee rates`() {
        assertEquals(
            0,
            BigDecimal("1.75").compareTo(
                PolymarketTradingFee.takerFee(BigDecimal("100"), BigDecimal("0.50"), BigDecimal("0.07"))
            )
        )
        assertEquals(
            0,
            BigDecimal("1.25").compareTo(
                PolymarketTradingFee.takerFee(BigDecimal("100"), BigDecimal("0.50"), BigDecimal("0.05"))
            )
        )
    }

    @Test
    fun `realized pnl subtracts both taker legs`() {
        val pnl = PolymarketTradingFee.netRealizedPnl(
            buyPrice = BigDecimal("0.50"),
            sellPrice = BigDecimal("0.60"),
            shares = BigDecimal("100"),
            feeRate = BigDecimal("0.05")
        )
        // gross 10 - buy 1.25 - sell 1.20
        assertEquals(0, BigDecimal("7.55").compareTo(pnl))
    }

    @Test
    fun `unknown rate and boundary prices are fee free`() {
        assertEquals(0, BigDecimal.ZERO.compareTo(PolymarketTradingFee.takerFee(BigDecimal("100"), BigDecimal("0.50"), null)))
        assertEquals(0, BigDecimal.ZERO.compareTo(PolymarketTradingFee.takerFee(BigDecimal("100"), BigDecimal.ONE, BigDecimal("0.07"))))
    }

    @Test
    fun `fee type fallback matches live Gamma rates including sports v2`() {
        assertEquals(0, BigDecimal("0.07").compareTo(PolymarketTradingFee.fallbackRate("crypto_fees_v2", null)))
        // sports_fees_v2 线上实测为 0.03，不能按分类 0.05 处理
        assertEquals(0, BigDecimal("0.03").compareTo(PolymarketTradingFee.fallbackRate("sports_fees_v2", null)))
        assertEquals(0, BigDecimal("0.05").compareTo(PolymarketTradingFee.fallbackRate("sports_fees_v3", null)))
        assertEquals(0, BigDecimal("0.04").compareTo(PolymarketTradingFee.fallbackRate("politics_fees", null)))
        assertEquals(0, BigDecimal("0.04").compareTo(PolymarketTradingFee.fallbackRate("finance_prices_fees", null)))
        assertEquals(0, BigDecimal("0.05").compareTo(PolymarketTradingFee.fallbackRate("economics_fees", null)))
        assertEquals(0, BigDecimal("0.05").compareTo(PolymarketTradingFee.fallbackRate("culture_fees", null)))
        assertEquals(0, BigDecimal("0.05").compareTo(PolymarketTradingFee.fallbackRate("weather_fees", null)))
        assertEquals(0, BigDecimal("0.04").compareTo(PolymarketTradingFee.fallbackRate("tech_fees", null)))
        assertEquals(0, BigDecimal.ZERO.compareTo(PolymarketTradingFee.fallbackRate("zero_fees", null)))
    }

    @Test
    fun `fallback uses category for legacy rows and unknown fee types fall back to base category`() {
        assertEquals(0, BigDecimal("0.05").compareTo(PolymarketTradingFee.fallbackRate(null, "Sports")))
        assertEquals(0, BigDecimal.ZERO.compareTo(PolymarketTradingFee.fallbackRate(null, "geopolitics")))
        // 未知版本号仍按基础分类兜底
        assertEquals(0, BigDecimal("0.07").compareTo(PolymarketTradingFee.fallbackRate("crypto_fees_v9", null)))
        assertEquals(0, BigDecimal.ZERO.compareTo(PolymarketTradingFee.fallbackRate("brand_new_type", null)))
    }
}
