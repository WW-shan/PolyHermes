package com.wrbug.polymarketbot.research.eventmaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventMakerBondScalpGridTest {
    @Test
    fun `bond scalp grid keeps its validated parameter space`() {
        val grid = bondScalpGridForTest()

        // 4 bands x 2 offsets x 3 exits x 3 queue factors x 2 cycle caps x 1 requote.
        assertEquals(144, grid.size)

        assertEquals(
            setOf(0.90 to 0.95, 0.95 to 0.97, 0.97 to 0.99, 0.99 to 0.999),
            grid.map { it.minPrice to it.maxPrice }.toSet()
        )
        assertEquals(setOf(0.0, 0.25), grid.map { it.entryOffsetCents }.toSet())
        assertEquals(setOf(1.0, 2.0, 3.0), grid.map { it.exitSpreadCents }.toSet())
        assertEquals(setOf(1.0, 2.0, 5.0), grid.map { it.queueFactor }.toSet())
        assertEquals(setOf(3, 10), grid.map { it.maxCycles }.toSet())
        assertEquals(setOf(300L), grid.map { it.requoteSeconds }.toSet())
        assertTrue(grid.all { it.makerFeeRate == 0.0 })
    }

    @Test
    fun `scalp wide grid covers the leader's multi-band universe`() {
        val grid = scalpWideGridForTest()

        // 6 bands x 2 offsets x 3 exits x 3 queue factors x 1 cycle cap x 1 requote.
        assertEquals(108, grid.size)
        assertEquals(
            setOf(
                0.5 to 0.8,
                0.8 to 0.9,
                0.9 to 0.95,
                0.95 to 0.97,
                0.97 to 0.99,
                0.99 to 0.999
            ),
            grid.map { it.minPrice to it.maxPrice }.toSet()
        )
        assertEquals(setOf(1.0, 2.0, 3.0), grid.map { it.exitSpreadCents }.toSet())
        assertEquals(setOf(3), grid.map { it.maxCycles }.toSet())
    }
}
