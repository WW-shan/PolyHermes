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
            """{"version":4,"mode":"LIVE","accountId":null,"markets":[],"startedAt":1000}"""
        )
        val store = EventMakerStateStore(path.toString())

        val error = assertThrows(IllegalStateException::class.java) { store.load() }

        assertEquals("event_maker_state_invalid:live_account_missing", error.message)
    }

    @Test
    fun `load rejects live state without a daily pnl anchor`() {
        val path = tempDir.resolve("state.json")
        Files.writeString(
            path,
            """{"version":4,"mode":"LIVE","accountId":7,"markets":[],"startedAt":1000}"""
        )
        val store = EventMakerStateStore(path.toString())

        val error = assertThrows(IllegalStateException::class.java) { store.load() }

        assertEquals("event_maker_state_invalid:daily_pnl_day_invalid", error.message)
    }

    @Test
    fun `save rejects non-finite daily pnl baseline`() {
        val store = EventMakerStateStore(tempDir.resolve("state.json").toString())
        val invalid = persistedState().copy(dailyPnlBaseline = Double.NaN)

        val error = assertThrows(IllegalStateException::class.java) { store.save(invalid) }

        assertEquals("event_maker_state_invalid:daily_pnl_baseline_invalid", error.message)
    }

    @Test
    fun `save and load round trips settlement records`() {
        val store = EventMakerStateStore(tempDir.resolve("state.json").toString())
        val settled = persistedState().copy(
            markets = listOf(settledMarket())
        )

        store.save(settled)

        assertEquals(settled, store.load())
    }

    @Test
    fun `save rejects settlement while inventory is still open`() {
        val store = EventMakerStateStore(tempDir.resolve("state.json").toString())
        val invalid = persistedState().copy(
            markets = listOf(settledMarket().copy(inventoryShares = "1.00", entryPrice = "0.96"))
        )

        val error = assertThrows(IllegalStateException::class.java) { store.save(invalid) }

        assertEquals("event_maker_state_invalid:settlement_with_inventory:condition", error.message)
    }

    private fun settledMarket(): EventMakerPersistedMarket {
        val settlement = EventMakerSettlementRecord(
            marketId = "condition",
            favoriteTokenId = "token",
            entryOrderId = "buy-1",
            cycleExitOrderId = "settlement:condition:2000",
            settledAt = 2_000L,
            entryAt = 1_100L,
            entryPrice = BigDecimal("0.96"),
            exitPrice = BigDecimal.ONE,
            shares = BigDecimal("10.00"),
            pnl = 0.4,
            outcomeIndex = 0,
            negativeRisk = false,
            redemptionStatus = EventMakerRedemptionStatus.REDEEMED,
            transactionHash = "0xtx",
            redemptionUpdatedAt = 2_100L
        )
        return EventMakerPersistedMarket(
            conditionId = "condition",
            phase = "FLAT",
            inventoryShares = "0",
            entryPrice = "0",
            entryAt = 0L,
            realizedPnl = 0.4,
            completedCycles = 1,
            activeOrderId = null,
            activeOrderSide = null,
            activeOrderPrice = null,
            activeOrderOriginalShares = null,
            activeOrderMatchedShares = null,
            activeOrderStatus = null,
            currentEntryOrderId = null,
            currentEntryShares = "0",
            fills = listOf(
                EventMakerFillRecord(
                    marketId = "condition",
                    orderId = "buy-1",
                    side = EventMakerSide.BUY,
                    price = BigDecimal("0.96"),
                    shares = BigDecimal("10.00"),
                    timestamp = 1_100L
                )
            ),
            cycles = listOf(
                EventMakerCycleRecord(
                    marketId = "condition",
                    entryOrderId = "buy-1",
                    exitOrderId = "settlement:condition:2000",
                    entryAt = 1_100L,
                    exitAt = 2_000L,
                    entryPrice = BigDecimal("0.96"),
                    exitPrice = BigDecimal.ONE,
                    shares = BigDecimal("10.00"),
                    pnl = 0.4,
                    holdingSeconds = 900L,
                    exitType = "SETTLE"
                )
            ),
            favoriteTokenId = "token",
            settlements = listOf(settlement)
        )
    }
    private fun persistedState() = EventMakerPersistedState(
        mode = "LIVE",
        accountId = 7L,
        startedAt = 1_000L,
        cumulativeRealizedPnl = 1.25,
        dailyPnlDay = 20_000L,
        dailyPnlBaseline = 0.75,
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
