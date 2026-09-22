package com.wrbug.polymarketbot.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PolymarketTradingFeeTest {

    @Test
    fun `uses current crypto and sports taker fee rates`() {
        assertEquals(0, BigDecimal("1.75").compareTo(PolymarketTradingFee.takerFee(BigDecimal("100"), BigDecimal("0.50"), "crypto")))
        assertEquals(0, BigDecimal("1.25").compareTo(PolymarketTradingFee.takerFee(BigDecimal("100"), BigDecimal("0.50"), "sports")))
    }

    @Test
    fun `realized pnl subtracts both taker legs`() {
        val pnl = PolymarketTradingFee.netRealizedPnl(
            buyPrice = BigDecimal("0.50"),
            sellPrice = BigDecimal("0.60"),
            shares = BigDecimal("100"),
            category = "sports"
        )
        // gross 10 - buy 1.25 - sell 1.20
        assertEquals(0, BigDecimal("7.55").compareTo(pnl))
    }

    @Test
    fun `unknown category and boundary prices are fee free`() {
        assertEquals(0, BigDecimal.ZERO.compareTo(PolymarketTradingFee.takerFee(BigDecimal("100"), BigDecimal("0.50"), null)))
        assertEquals(0, BigDecimal.ZERO.compareTo(PolymarketTradingFee.takerFee(BigDecimal("100"), BigDecimal.ONE, "crypto")))
    }
}
