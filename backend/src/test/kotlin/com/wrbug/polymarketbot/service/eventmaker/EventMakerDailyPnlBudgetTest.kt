package com.wrbug.polymarketbot.service.eventmaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class EventMakerDailyPnlBudgetTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()

    @Test
    fun `same UTC day reuses persisted baseline so losses survive restart`() {
        val today = EventMakerDailyPnlBudget.currentDay(now)

        val anchor = EventMakerDailyPnlBudget.resolve(
            now = now,
            persistedDay = today,
            persistedBaseline = 10.0,
            totalRealizedPnl = 7.0
        )

        assertEquals(today, anchor.day)
        assertEquals(10.0, anchor.baseline, 1e-9)
    }

    @Test
    fun `new UTC day re-anchors baseline to current cumulative realized pnl`() {
        val yesterday = EventMakerDailyPnlBudget.currentDay(now) - 1

        val anchor = EventMakerDailyPnlBudget.resolve(
            now = now,
            persistedDay = yesterday,
            persistedBaseline = 10.0,
            totalRealizedPnl = 7.0
        )

        assertEquals(EventMakerDailyPnlBudget.currentDay(now), anchor.day)
        assertEquals(7.0, anchor.baseline, 1e-9)
    }

    @Test
    fun `missing or invalid persisted baseline fails closed to a fresh anchor`() {
        val today = EventMakerDailyPnlBudget.currentDay(now)

        val missing = EventMakerDailyPnlBudget.resolve(now, today, null, -4.0)
        val notFinite = EventMakerDailyPnlBudget.resolve(now, today, Double.NaN, -4.0)

        assertEquals(-4.0, missing.baseline, 1e-9)
        assertEquals(-4.0, notFinite.baseline, 1e-9)
    }

    @Test
    fun `non-finite cumulative realized pnl is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            EventMakerDailyPnlBudget.resolve(now, null, null, Double.POSITIVE_INFINITY)
        }
    }
}