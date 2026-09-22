package com.wrbug.polymarketbot.service.cryptotail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 实盘语义（2026-09 线上 Data API 实测）：BUY activity 的 usdcSize = 成交额 + taker 手续费，
 * 因此按 usdcSize 计算盈亏时不能再额外扣一次买入手续费，否则会重复扣费。
 */
class CryptoTailPnlCalculatorTest {

    @Test
    fun `winning trade uses fee inclusive usdcSize without double counting fee`() {
        // 100 份 @0.5：成交额 50，crypto taker 费 1.75，usdcSize = 51.75，赎回 100
        val pnl = CryptoTailPnlCalculator.pnlFromFill(
            price = BigDecimal("0.50"),
            sizeMatched = BigDecimal("100"),
            usdcSize = BigDecimal("51.75"),
            won = true
        )
        assertEquals(0, BigDecimal("48.25").compareTo(pnl))
    }

    @Test
    fun `winning trade without usdcSize falls back to notional plus fee`() {
        val pnl = CryptoTailPnlCalculator.pnlFromFill(
            price = BigDecimal("0.50"),
            sizeMatched = BigDecimal("100"),
            usdcSize = null,
            won = true
        )
        assertEquals(0, BigDecimal("48.25").compareTo(pnl))
    }

    @Test
    fun `losing trade loses fee inclusive cost`() {
        val pnl = CryptoTailPnlCalculator.pnlFromFill(
            price = BigDecimal("0.50"),
            sizeMatched = BigDecimal("100"),
            usdcSize = BigDecimal("51.75"),
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
