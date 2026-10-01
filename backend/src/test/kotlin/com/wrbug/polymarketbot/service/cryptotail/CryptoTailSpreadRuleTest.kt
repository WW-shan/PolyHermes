package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.enums.SpreadDirection
import com.wrbug.polymarketbot.service.binance.BinanceKlineAutoSpreadService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

class CryptoTailSpreadRuleTest {

    private fun bd(v: String) = BigDecimal(v)

    @Test
    fun `missing threshold is fail closed`() {
        assertFalse(CryptoTailSpreadRule.passes(SpreadDirection.MIN, bd("100"), bd("150"), 0, null))
        assertFalse(CryptoTailSpreadRule.passes(SpreadDirection.MAX, bd("100"), bd("100"), 0, null))
        assertFalse(CryptoTailSpreadRule.passes(SpreadDirection.MIN, bd("100"), bd("150"), 0, bd("-1")))
    }

    @Test
    fun `min spread requires move in the bought direction`() {
        // 大幅下跌不能确认买 Up
        assertFalse(CryptoTailSpreadRule.passes(SpreadDirection.MIN, bd("100"), bd("50"), 0, bd("10")))
        assertTrue(CryptoTailSpreadRule.passes(SpreadDirection.MIN, bd("100"), bd("50"), 1, bd("10")))
        assertTrue(CryptoTailSpreadRule.passes(SpreadDirection.MIN, bd("100"), bd("110"), 0, bd("10")))
        assertFalse(CryptoTailSpreadRule.passes(SpreadDirection.MIN, bd("100"), bd("110"), 1, bd("10")))
        assertFalse(CryptoTailSpreadRule.passes(SpreadDirection.MIN, bd("100"), bd("109.99"), 0, bd("10")))
    }

    @Test
    fun `fixed max with zero only passes when spread is zero`() {
        assertTrue(CryptoTailSpreadRule.passes(SpreadDirection.MAX, bd("100"), bd("100"), 0, BigDecimal.ZERO))
        assertFalse(CryptoTailSpreadRule.passes(SpreadDirection.MAX, bd("100"), bd("100.01"), 0, BigDecimal.ZERO))
        assertTrue(CryptoTailSpreadRule.passes(SpreadDirection.MAX, bd("100"), bd("95"), 0, bd("5")))
        assertFalse(CryptoTailSpreadRule.passes(SpreadDirection.MAX, bd("100"), bd("94"), 1, bd("5")))
    }

    private val autoService = BinanceKlineAutoSpreadService(Mockito.mock(com.wrbug.polymarketbot.util.RetrofitFactory::class.java))

    private fun kline(openTimeMs: Long, open: String, close: String): List<Any> =
        listOf(openTimeMs.toDouble(), open, "0", "0", close)

    @Test
    fun `auto base spread excludes current unclosed kline`() {
        val periodStartMs = 3_000_000L
        val klines = listOf(
            kline(periodStartMs - 600_000, "100", "110"),
            kline(periodStartMs - 300_000, "100", "90"),
            // 当前周期未收盘 K 线，巨大波动不能进入基准
            kline(periodStartMs, "100", "1000")
        )
        val (up, down) = autoService.computeBaseSpreads(klines, periodStartMs)!!
        assertEquals(0, bd("10").compareTo(up))
        assertEquals(0, bd("10").compareTo(down))
    }

    @Test
    fun `direction with zero samples falls back to all klines average`() {
        val periodStartMs = 3_000_000L
        val klines = listOf(
            kline(periodStartMs - 900_000, "100", "104"),
            kline(periodStartMs - 600_000, "100", "106"),
            kline(periodStartMs - 300_000, "100", "105")
        )
        val (up, down) = autoService.computeBaseSpreads(klines, periodStartMs)!!
        assertEquals(0, bd("5").compareTo(up))
        assertEquals(0, bd("5").compareTo(down))
    }

    @Test
    fun `no usable klines returns null`() {
        assertNull(autoService.computeBaseSpreads(emptyList(), 3_000_000L))
        assertNull(autoService.computeBaseSpreads(listOf(kline(0, "100", "100")), 3_000_000L))
        assertNotNull(autoService.computeBaseSpreads(listOf(kline(0, "100", "101")), 3_000_000L))
    }
}
