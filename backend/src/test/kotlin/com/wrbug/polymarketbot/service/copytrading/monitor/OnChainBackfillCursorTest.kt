package com.wrbug.polymarketbot.service.copytrading.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OnChainBackfillCursorTest {

    @Test
    fun `failed backfill remains ahead of advancing live head`() {
        val cursor = OnChainBackfillCursor()
        cursor.observe(100)
        cursor.finishBackfill(fromBlock = 100, latestBlock = 120, allCallbacksSucceeded = false)
        cursor.observe(125)

        assertEquals(100, cursor.nextBackfillBlock())

        cursor.finishBackfill(fromBlock = 100, latestBlock = 125, allCallbacksSucceeded = true)

        assertEquals(125, cursor.nextBackfillBlock())
    }

    @Test
    fun `live callback failure pins its block until a successful replay`() {
        val cursor = OnChainBackfillCursor()
        cursor.observe(200)
        cursor.finishBackfill(fromBlock = 200, latestBlock = 210, allCallbacksSucceeded = true)
        cursor.observe(215)
        cursor.markCallbackFailure(212)

        assertEquals(212, cursor.nextBackfillBlock())

        cursor.finishBackfill(fromBlock = 212, latestBlock = 215, allCallbacksSucceeded = true)

        assertEquals(215, cursor.nextBackfillBlock())
    }
}
