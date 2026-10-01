package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CryptoTailTimingTest {

    private val strategy = CryptoTailStrategy().copy(
        intervalSeconds = 300,
        windowStartSeconds = 60,
        windowEndSeconds = 120
    )

    @Test
    fun `leaves full taker delay before window end`() {
        val periodStartUnix = 1_000L
        val windowStartMs = (periodStartUnix + 60) * 1000L
        val windowEndMs = (periodStartUnix + 120) * 1000L

        assertFalse(CryptoTailTiming.isWithinExecutionWindow(strategy, periodStartUnix, windowStartMs - 1))
        assertTrue(CryptoTailTiming.isWithinExecutionWindow(strategy, periodStartUnix, windowStartMs))
        assertTrue(
            CryptoTailTiming.isWithinExecutionWindow(
                strategy,
                periodStartUnix,
                windowEndMs - CryptoTailTiming.END_GUARD_MS - 1
            )
        )
        assertFalse(
            CryptoTailTiming.isWithinExecutionWindow(
                strategy,
                periodStartUnix,
                windowEndMs - CryptoTailTiming.END_GUARD_MS
            )
        )
        assertFalse(CryptoTailTiming.isWithinExecutionWindow(strategy, periodStartUnix, windowEndMs))
    }

    @Test
    fun `taker delay matches documented 250ms plus network margin`() {
        org.junit.jupiter.api.Assertions.assertEquals(250L, CryptoTailTiming.TAKER_DELAY_MS)
        assertTrue(CryptoTailTiming.END_GUARD_MS > CryptoTailTiming.TAKER_DELAY_MS)
    }

    @Test
    fun `manual order only allowed before current period closes`() {
        val periodStart = 1_800L
        val nowMs = (periodStart + 100) * 1000L
        org.junit.jupiter.api.Assertions.assertEquals(periodStart, CryptoTailTiming.currentPeriodStart(300, nowMs))
        assertTrue(CryptoTailTiming.isBeforeMarketClose(300, periodStart, nowMs))
        val periodEndMs = (periodStart + 300) * 1000L
        assertFalse(CryptoTailTiming.isBeforeMarketClose(300, periodStart, periodEndMs - CryptoTailTiming.END_GUARD_MS))
        // 上一周期不可下单
        assertFalse(CryptoTailTiming.isBeforeMarketClose(300, periodStart - 300, nowMs))
    }
}
