package com.wrbug.polymarketbot.service.eventmaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EventMakerExecutionGuardsTest {
    private val intent = EventMakerQuoteIntent(
        side = EventMakerSide.BUY,
        price = BigDecimal("0.96"),
        shares = BigDecimal("10.00"),
        queueAheadShares = 0.0,
        reason = "test"
    )

    @Test
    fun `order shape rejects off tick price and missing minimum size`() {
        assertRejected(
            EventMakerExecutionGuards.validateOrderShape(intent, BigDecimal("0.01"), null),
            "min_order_size_unavailable"
        )
        assertRejected(
            EventMakerExecutionGuards.validateOrderShape(
                intent.copy(price = BigDecimal("0.965")),
                BigDecimal("0.01"),
                BigDecimal("5")
            ),
            "price_not_on_tick"
        )
        assertRejected(
            EventMakerExecutionGuards.validateOrderShape(intent, BigDecimal("0.01"), BigDecimal("11")),
            "below_min_order_size"
        )
    }

    @Test
    fun `buy funding requires balance and allowance`() {
        assertRejected(
            EventMakerExecutionGuards.validateBuyFunding(
                availableUsdc = BigDecimal("5"),
                allowanceRaw = BigDecimal("100000000"),
                requiredUsdc = BigDecimal("10")
            ),
            "insufficient_usdc_balance"
        )
        assertRejected(
            EventMakerExecutionGuards.validateBuyFunding(
                availableUsdc = BigDecimal("20"),
                allowanceRaw = BigDecimal("9999999"),
                requiredUsdc = BigDecimal("10")
            ),
            "insufficient_usdc_allowance"
        )
        assertTrue(
            EventMakerExecutionGuards.validateBuyFunding(
                availableUsdc = BigDecimal("20"),
                allowanceRaw = BigDecimal("10000000"),
                requiredUsdc = BigDecimal("10")
            ).isSuccess
        )
    }

    @Test
    fun `sell inventory requires shares and exchange approval`() {
        assertRejected(
            EventMakerExecutionGuards.validateSellInventory(BigDecimal("9"), true, BigDecimal("10")),
            "insufficient_ctf_inventory"
        )
        assertRejected(
            EventMakerExecutionGuards.validateSellInventory(BigDecimal("10"), false, BigDecimal("10")),
            "ctf_exchange_not_approved"
        )
        assertTrue(
            EventMakerExecutionGuards.validateSellInventory(BigDecimal("10"), true, BigDecimal("10")).isSuccess
        )
    }

    private fun assertRejected(result: Result<Unit>, message: String) {
        assertTrue(result.isFailure)
        assertEquals(message, result.exceptionOrNull()?.message)
    }
}