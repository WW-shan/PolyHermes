package com.wrbug.polymarketbot.service.eventmaker

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventMakerRiskPolicyTest {
    private val policy = EventMakerRiskPolicy()
    private val config = EventMakerConfig(
        maxMarkets = 3,
        orderNotional = 25.0,
        maxTotalInventoryNotional = 100.0,
        maxDailyLoss = 5.0
    )

    @Test
    fun `allows buy within risk limits`() {
        assertTrue(policy.canOpenBuy(config, EventMakerRiskSnapshot(50.0, 2, 0.0)).allowed)
    }

    @Test
    fun `rejects buy above inventory limit`() {
        val decision = policy.canOpenBuy(config, EventMakerRiskSnapshot(90.0, 1, 0.0))

        assertFalse(decision.allowed)
        assertTrue(decision.reason == "total_inventory_limit")
    }

    @Test
    fun `rejects buy after daily loss limit`() {
        val decision = policy.canOpenBuy(config, EventMakerRiskSnapshot(0.0, 0, -5.0))

        assertFalse(decision.allowed)
        assertTrue(decision.reason == "daily_loss_limit")
        assertTrue(policy.shouldHalt(config, -5.0))
    }
}
