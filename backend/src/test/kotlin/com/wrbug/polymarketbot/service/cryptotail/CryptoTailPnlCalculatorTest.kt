package com.wrbug.polymarketbot.service.cryptotail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CryptoTailPnlCalculatorTest {

    @Test
    fun `winning trade subtracts crypto taker fee`() {
        val pnl = CryptoTailPnlCalculator.pnlFromFill(
            price = BigDecimal("0.50"),
            sizeMatched = BigDecimal("100"),
            amountUsdc = BigDecimal("50"),
            won = true
        )
        assertEquals(0, BigDecimal("48.25").compareTo(pnl))
    }

    @Test
    fun `losing trade includes crypto taker fee`() {
        val pnl = CryptoTailPnlCalculator.pnlFromFill(
            price = BigDecimal("0.50"),
            sizeMatched = BigDecimal("100"),
            amountUsdc = BigDecimal("50"),
            won = false
        )
        assertEquals(0, BigDecimal("-51.75").compareTo(pnl))
    }

    @Test
    fun `fallback uses fixed price fee formula`() {
        val pnl = CryptoTailPnlCalculator.pnlFallback(BigDecimal("99"), won = true)
        assertEquals(0, BigDecimal("0.9307").compareTo(pnl))
    }
}
