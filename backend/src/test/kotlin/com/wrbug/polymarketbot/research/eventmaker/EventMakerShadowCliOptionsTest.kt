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
    fun `parses taker entry flag`() {
        val options = parseOptions(
            arrayOf("--taker-entry", "--taker-entry-slippage-ticks", "1", "--minutes", "60")
        )
        assertEquals(true, options.takerEntry)
        assertEquals(1, options.takerEntrySlippageTicks)
    }

    @Test
    fun `parses momentum filter`() {
        val options = parseOptions(
            arrayOf(
                "--entry-momentum-min-cents", "-5",
                "--entry-momentum-max-cents", "-2",
                "--entry-momentum-lookback-seconds", "3600",
                "--minutes", "60"
            )
        )
        assertEquals(-5.0, options.entryMomentumMinCents, 0.0)
        assertEquals(-2.0, options.entryMomentumMaxCents, 0.0)
        assertEquals(3600L, options.entryMomentumLookbackSeconds)
    }

    @Test
    fun `parses atomic ladder offsets`() {
        val options = parseOptions(
            arrayOf("--ladder-offsets-cents", "0,0.5,1,2", "--minutes", "60")
        )
        assertEquals(listOf(0.0, 0.5, 1.0, 2.0), options.ladderOffsetsCents)
    }

    @Test
    fun `parses forced flatten window`() {
        val options = parseOptions(
            arrayOf(
                "--flatten-before-close-minutes", "1440",
                "--flatten-slippage-ticks", "2",
                "--minutes", "60"
            )
        )
        assertEquals(1440L, options.flattenBeforeCloseMinutes)
        assertEquals(2, options.flattenSlippageTicks)
    }
    @Test
    fun `rejects ladder offsets combined with taker entry`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseOptions(
                arrayOf("--ladder-offsets-cents", "0,1", "--taker-entry", "--minutes", "60")
            )
        }
    }
    @Test
    fun `rejects inverted momentum range`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseOptions(
                arrayOf(
                    "--entry-momentum-min-cents", "-1",
                    "--entry-momentum-max-cents", "-2",
                    "--minutes", "60"
                )
            )
        }
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
