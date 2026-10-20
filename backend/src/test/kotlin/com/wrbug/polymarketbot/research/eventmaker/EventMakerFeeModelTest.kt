package com.wrbug.polymarketbot.research.eventmaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EventMakerFeeModelTest {

    @Test
    fun `maker fee defaults to zero for taker-only prediction markets`() {
        val config = EventMakerConfig(name = "bond")
        assertEquals(0.0, config.makerFeeRate, 0.0)
    }

    @Test
    fun `negative maker fee is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            EventMakerConfig(name = "bad", makerFeeRate = -0.01)
        }
    }
}
