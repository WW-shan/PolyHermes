package com.wrbug.polymarketbot.service.eventmaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.math.BigDecimal
import java.nio.file.Path

class EventMakerStateStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `save and load round trips live state`() {
        val store = EventMakerStateStore(tempDir.resolve("state.json").toString())
        val state = persistedState()

        store.save(state)

        assertEquals(state, store.load())
    }

    @Test
    fun `load returns null when file does not exist`() {
        val store = EventMakerStateStore(tempDir.resolve("missing.json").toString())

        assertNull(store.load())
    }

    @Test
    fun `load returns null for malformed json and clear removes file`() {
        val path = tempDir.resolve("state.json")
        Files.writeString(path, "not-json")
        val store = EventMakerStateStore(path.toString())

        assertNull(store.load())

        store.clear()
        assertTrue(Files.notExists(path))
    }

    @Test
    fun `load rejects unsupported state version`() {
        val path = tempDir.resolve("state.json")
        Files.writeString(path, """{"version":99,"mode":"LIVE","accountId":7,"markets":[]}""")
        val store = EventMakerStateStore(path.toString())

        assertNull(store.load())
    }
    private fun persistedState() = EventMakerPersistedState(
        mode = "LIVE",
        accountId = 7L,
        startedAt = 1_000L,
        markets = listOf(
            EventMakerPersistedMarket(
                conditionId = "condition",
                phase = "LONG",
                inventoryShares = "6.00",
                entryPrice = "0.96",
                entryAt = 1_100L,
                realizedPnl = 0.04,
                completedCycles = 1,
                activeOrderId = "sell-1",
                activeOrderSide = "SELL",
                activeOrderPrice = "0.97",
                activeOrderOriginalShares = "6.00",
                activeOrderMatchedShares = "0.00",
                activeOrderStatus = "LIVE",
                currentEntryOrderId = "buy-1",
                currentEntryShares = "6.00",
                fills = listOf(
                    EventMakerFillRecord("condition", "buy-1", EventMakerSide.BUY, BigDecimal("0.96"), BigDecimal("10.00"), 1_100L)
                ),
                cycles = listOf(
                    EventMakerCycleRecord("condition", "buy-1", "sell-0", 1_000L, 1_200L, BigDecimal("0.95"), BigDecimal("0.97"), BigDecimal("10.00"), 0.2, 200L)
                )
            )
        )
    )
}