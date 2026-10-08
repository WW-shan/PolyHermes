package com.wrbug.polymarketbot.service.system

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 去重记录保留窗口必须覆盖链上 WebSocket 断线补数据的最大回溯窗口（约 1 小时），
 * 否则同一笔成交会在 activity 与 onchain 两条路径上各下一次单（重复下单）。
 */
class ProcessedTradeCleanupRetentionTest {

    @Test
    fun `processed trade retention covers onchain backfill window with margin`() {
        val oneHourMs = 60 * 60 * 1000L
        assertTrue(
            ProcessedTradeCleanupService.RETENTION_MS >= 2 * oneHourMs,
            "去重记录保留时间过短: ${ProcessedTradeCleanupService.RETENTION_MS}ms"
        )
    }
}
