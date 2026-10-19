package com.wrbug.polymarketbot.service.eventmaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class EventMakerStateStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `load accepts lower-case documented order statuses`() {
        val store = EventMakerStateStore(tempDir.resolve("state.json").toString())
        val state = persistedState()
        val lowerCase = state.copy(
            markets = state.markets.map { it.copy(activeOrderStatus = "canceled_market_resolved") }
        )

        store.save(lowerCase)

        assertEquals(lowerCase, store.load())
    }


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
    fun `load fails closed for malformed json`() {
        val path = tempDir.resolve("state.json")
        Files.writeString(path, "not-json")
        val store = EventMakerStateStore(path.toString())

        val error = assertThrows(IllegalStateException::class.java) { store.load() }

        assertEquals("event_maker_state_corrupt", error.message)
        store.clear()
        assertTrue(Files.notExists(path))
    }

    @Test
    fun `load rejects unsupported state version`() {
        val path = tempDir.resolve("state.json")
        Files.writeString(path, """{"version":99,"mode":"LIVE","accountId":7,"markets":[]}""")
        val store = EventMakerStateStore(path.toString())

        val error = assertThrows(IllegalStateException::class.java) { store.load() }

        assertEquals("event_maker_state_version_unsupported:99", error.message)
    }

    @Test
    fun `load rejects live state without account`() {
        val path = tempDir.resolve("state.json")
        Files.writeString(
            path,
            """{"version":2,"mode":"LIVE","accountId":null,"markets":[],"startedAt":1000}"""
        )
        val store = EventMakerStateStore(path.toString())

        val error = assertThrows(IllegalStateException::class.java) { store.load() }

        assertEquals("event_maker_state_invalid:live_account_missing", error.message)
    }

    private fun persistedState() = EventMakerPersistedState(
        mode = "LIVE",
        accountId = 7L,
        startedAt = 1_000L,
        markets = listOf(
            EventMakerPersistedMarket(
                conditionId = "condition",
                phase = "SELL_PENDING",
                inventoryShares = "10.00",
                entryPrice = "0.96",
                entryAt = 1_100L,
                realizedPnl = 0.0,
                completedCycles = 0,
                activeOrderId = "sell-1",
                activeOrderSide = "SELL",
                activeOrderPrice = "0.97",
                activeOrderOriginalShares = "10.00",
                activeOrderMatchedShares = "0.00",
                activeOrderStatus = "LIVE",
                activeOrderCreatedAt = 1_200L,
                currentEntryOrderId = "buy-1",
                currentEntryShares = "10.00",
                fills = listOf(
                    EventMakerFillRecord(
                        "condition",
                        "buy-1",
                        EventMakerSide.BUY,
                        BigDecimal("0.96"),
                        BigDecimal("10.00"),
                        1_100L
                    )
                ),
                cycles = emptyList(),
                favoriteTokenId = "token"
            )
        )
    )
}
