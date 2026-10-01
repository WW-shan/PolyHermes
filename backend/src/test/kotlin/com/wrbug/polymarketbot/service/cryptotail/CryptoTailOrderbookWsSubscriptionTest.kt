package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CryptoTailOrderbookWsSubscriptionTest {

    private fun strategy(id: Long, interval: Int, windowEnd: Int) =
        CryptoTailStrategy().copy(id = id, intervalSeconds = interval, windowStartSeconds = 0, windowEndSeconds = windowEnd)

    @Test
    fun `strategies past their window are not expected subscriptions`() {
        val now = 3_000L + 200 // 5m 周期起点 3000，已过 180s 窗口
        val desired = CryptoTailOrderbookWsService.desiredStrategyPeriods(
            listOf(strategy(1, 300, 180), strategy(2, 300, 290), strategy(3, 900, 900)),
            now
        )
        assertEquals(mapOf(2L to 3_000L, 3L to 2_700L), desired)
    }

    @Test
    fun `next refresh is earliest next period start even when nothing is subscribed`() {
        val now = 3_000L + 200
        assertEquals(
            3_300L,
            CryptoTailOrderbookWsService.nextRefreshAtSeconds(listOf(strategy(1, 300, 180), strategy(3, 900, 900)), now)
        )
        assertNull(CryptoTailOrderbookWsService.nextRefreshAtSeconds(emptyList(), now))
    }
}
