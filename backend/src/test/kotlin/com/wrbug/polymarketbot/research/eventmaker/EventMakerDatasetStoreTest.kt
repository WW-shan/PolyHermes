package com.wrbug.polymarketbot.research.eventmaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class EventMakerDatasetStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `round trips markets and trades through a compressed dataset`() {
        val market = EventMakerMarket(
            conditionId = "condition",
            question = "question",
            tag = "Politics",
            volume = 123_456.0,
            closedTimestamp = 2_000L,
            winnerIndex = 0,
            tickSize = 0.001,
            trades = listOf(
                EventMakerTrade(1_000L, 0, 0.98, 12.5, "BUY"),
                EventMakerTrade(1_100L, 0, 0.975, 25.0, "SELL")
            )
        )
        val snapshot = EventMakerDatasetSnapshot(
            createdAt = 42L,
            fromDate = "2024-01-01",
            toDate = "2026-10-01",
            minVolume = 100_000.0,
            maxMarkets = 5_000,
            entryHorizonSeconds = 86_400,
            markets = listOf(market)
        )
        val path = tempDir.resolve("dataset.json.gz")

        EventMakerDatasetStore.write(path, snapshot)
        val loaded = EventMakerDatasetStore.read(path)

        assertEquals(snapshot, loaded)
        assertTrue(
            EventMakerDatasetStore.matches(
                loaded,
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2026-10-01"),
                100_000.0,
                5_000,
                86_400
            )
        )
        assertTrue(
            EventMakerDatasetStore.matches(
                loaded,
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2026-10-01"),
                100_000.0,
                5_000,
                86_400
            )
        )
        assertFalse(
            EventMakerDatasetStore.matches(
                loaded,
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2026-10-01"),
                100_000.0,
                5_000,
                172_800
            )
        )
        assertFalse(
            EventMakerDatasetStore.matches(
                loaded,
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2026-10-01"),
                100_000.0,
                5_001,
                86_400
            )
        )
    }

    @Test
    fun `rejects a file that is not gzip json`() {
        val path = tempDir.resolve("broken.json.gz")
        Files.writeString(path, "not gzip")

        assertThrows(IllegalArgumentException::class.java) {
            EventMakerDatasetStore.read(path)
        }
    }

    @Test
    fun `replaces an existing dataset without leaving temporary files`() {
        val path = tempDir.resolve("dataset.json.gz")
        val first = EventMakerDatasetSnapshot(
            createdAt = 1L,
            fromDate = "2024-01-01",
            toDate = "2026-10-01",
            minVolume = 100_000.0,
            maxMarkets = 5_000,
            entryHorizonSeconds = 604_800,
            markets = listOf(
                EventMakerMarket(
                    conditionId = "condition",
                    question = "question",
                    tag = "Politics",
                    volume = 123_456.0,
                    closedTimestamp = 2_000L,
                    winnerIndex = 0,
                    tickSize = 0.001,
                    trades = emptyList()
                )
            )
        )

        EventMakerDatasetStore.write(path, first)
        EventMakerDatasetStore.write(path, first.copy(createdAt = 2L))

        assertEquals(2L, EventMakerDatasetStore.read(path).createdAt)
        assertEquals(1L, Files.list(tempDir).use { it.count() })
    }
}