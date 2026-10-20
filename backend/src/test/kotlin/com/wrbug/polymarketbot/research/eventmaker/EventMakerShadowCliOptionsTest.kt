package com.wrbug.polymarketbot.research.eventmaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EventMakerShadowCliOptionsTest {
    @Test
    fun `parses inventory and daily loss caps`() {
        val options = parseOptions(
            arrayOf(
                "--max-total-inventory-notional", "1000",
                "--max-daily-loss", "100",
                "--minutes", "60"
            )
        )
        assertEquals(1000.0, options.maxTotalInventoryNotional, 0.0)
        assertEquals(100.0, options.maxDailyLoss, 0.0)
    }

    @Test
    fun `rejects non-positive inventory cap`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseOptions(arrayOf("--max-total-inventory-notional", "0"))
        }
    }

    @Test
    fun `parses tag filter`() {
        val options = parseOptions(arrayOf("--tags", "Geopolitics", "--minutes", "60"))
        assertEquals(listOf("Geopolitics"), options.tags)
    }

    @Test
    fun `rejects unsupported tag`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseOptions(arrayOf("--tags", "Sports", "--minutes", "60"))
        }
    }
}
