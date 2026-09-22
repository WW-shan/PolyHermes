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
                windowEndMs - CryptoTailTiming.TAKER_DELAY_MS - 1
            )
        )
        assertFalse(
            CryptoTailTiming.isWithinExecutionWindow(
                strategy,
                periodStartUnix,
                windowEndMs - CryptoTailTiming.TAKER_DELAY_MS
            )
        )
        assertFalse(CryptoTailTiming.isWithinExecutionWindow(strategy, periodStartUnix, windowEndMs))
    }
}
