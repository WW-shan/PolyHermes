package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LiveTradePrint
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventMakerAdverseFlowGuardTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `blocks at the inclusive signed-flow and price-drop boundary`() {
        val guard = EventMakerAdverseFlowGuard(EventMakerConfig(flowGuardWindowSeconds = 600))
        guard.record(trade(now - 200_000L, "BUY", 0.980, 30.0))
        guard.record(trade(now - 100_000L, "SELL", 0.975, 70.0))

        assertTrue(guard.blocks("token", now))
    }

    @Test
    fun `does not block balanced or buy-led flow`() {
        val guard = EventMakerAdverseFlowGuard(EventMakerConfig(flowGuardWindowSeconds = 600))
        guard.record(trade(now - 200_000L, "BUY", 0.980, 70.0))
        guard.record(trade(now - 100_000L, "SELL", 0.975, 30.0))

        assertFalse(guard.blocks("token", now))
    }

    @Test
    fun `ignores unknown sides for flow while retaining their prices`() {
        val guard = EventMakerAdverseFlowGuard(EventMakerConfig(flowGuardWindowSeconds = 600))
        guard.record(trade(now - 200_000L, "UNKNOWN", 0.980, 70.0))
        guard.record(trade(now - 100_000L, "UNKNOWN", 0.975, 70.0))

        assertFalse(guard.blocks("token", now))
    }

    @Test
    fun `expired adverse flow no longer blocks`() {
        val guard = EventMakerAdverseFlowGuard(EventMakerConfig(flowGuardWindowSeconds = 600))
        guard.record(trade(now - 700_000L, "BUY", 0.980, 30.0))
        guard.record(trade(now - 650_000L, "SELL", 0.975, 70.0))

        assertFalse(guard.blocks("token", now))
    }

    @Test
    fun `zero window keeps the guard disabled`() {
        val guard = EventMakerAdverseFlowGuard(EventMakerConfig(flowGuardWindowSeconds = 0))
        guard.record(trade(now - 200_000L, "BUY", 0.980, 30.0))
        guard.record(trade(now - 100_000L, "SELL", 0.975, 70.0))

        assertFalse(guard.blocks("token", now))
    }

    private fun trade(timestamp: Long, side: String, price: Double, size: Double) = LiveTradePrint(
        conditionId = "condition",
        tokenId = "token",
        timestamp = timestamp,
        side = side,
        price = price,
        size = size,
        source = "test",
        transactionHash = "0x$timestamp$side"
    )
}
