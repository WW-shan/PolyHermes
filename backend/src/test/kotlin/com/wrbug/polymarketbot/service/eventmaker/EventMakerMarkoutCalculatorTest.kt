package com.wrbug.polymarketbot.service.eventmaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EventMakerMarkoutCalculatorTest {
    @Test
    fun `buy markout uses best bid`() {
        val pnl = EventMakerMarkoutCalculator.calculate(
            EventMakerSide.BUY,
            BigDecimal("0.96"),
            BigDecimal("10"),
            bestBid = 0.97,
            bestAsk = 0.98
        )

        assertEquals(0.1, pnl!!, 1e-9)
    }

    @Test
    fun `sell markout uses best ask`() {
        val pnl = EventMakerMarkoutCalculator.calculate(
            EventMakerSide.SELL,
            BigDecimal("0.97"),
            BigDecimal("10"),
            bestBid = 0.95,
            bestAsk = 0.98
        )

        assertEquals(-0.1, pnl!!, 1e-9)
    }

    @Test
    fun `missing book side returns null`() {
        assertNull(
            EventMakerMarkoutCalculator.calculate(
                EventMakerSide.BUY,
                BigDecimal("0.96"),
                BigDecimal("10"),
                bestBid = null,
                bestAsk = 0.98
            )
        )
    }
}