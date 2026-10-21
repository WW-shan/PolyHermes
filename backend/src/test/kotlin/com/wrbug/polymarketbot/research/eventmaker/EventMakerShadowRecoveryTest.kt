package com.wrbug.polymarketbot.research.eventmaker

import com.wrbug.polymarketbot.research.livepaper.BookLevel
import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.MarketBook
import com.wrbug.polymarketbot.service.eventmaker.EventMakerConfig
import com.wrbug.polymarketbot.service.eventmaker.EventMakerLiveOrder
import com.wrbug.polymarketbot.service.eventmaker.EventMakerLiveState
import com.wrbug.polymarketbot.service.eventmaker.EventMakerMode
import com.wrbug.polymarketbot.service.eventmaker.EventMakerPersistedState
import com.wrbug.polymarketbot.service.eventmaker.EventMakerPhase
import com.wrbug.polymarketbot.service.eventmaker.EventMakerRedemptionStatus
import com.wrbug.polymarketbot.service.eventmaker.EventMakerSide
import com.wrbug.polymarketbot.service.eventmaker.EventMakerStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class EventMakerShadowRecoveryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `recovery sidecar round trips full market metadata`() {
        val path = tempDir.resolve("recovery.json")
        val markets = listOf(market("condition-a", "token-a"), market("condition-b", "token-b"))

        EventMakerShadowRecoveryStore.save(path, markets, savedAt = 1234L)
        val loaded = EventMakerShadowRecoveryStore.load(path)

        assertNotNull(loaded)
        assertEquals(1234L, loaded!!.savedAt)
        assertEquals(markets, loaded.markets)
    }

    @Test
    fun `merge prefers freshly discovered metadata and keeps orphaned inventory markets`() {
        val recovered = listOf(market("same", "old-token"), market("orphan", "orphan-token"))
        val discovered = listOf(market("same", "fresh-token").copy(question = "fresh question"))

        val merged = EventMakerShadowRecoveryStore.merge(discovered, recovered)

        assertEquals(listOf("same", "orphan"), merged.map { it.conditionId })
        assertEquals("fresh-token", merged.first().favoriteTokenId)
        assertEquals("fresh question", merged.first().question)
    }

    @Test
    fun `restart restores fills cycles settlements and partial inventory`() {
        val config = EventMakerConfig()
        val inventoryMarket = market("inventory", "token-inventory")
        val settledMarket = market("settled", "token-settled")

        val inventoryState = EventMakerLiveState(inventoryMarket, config)
        val buy = order("buy-1", EventMakerSide.BUY, "0.96", "10.00", "10.00", "FILLED")
        inventoryState.onOrderPlaced(buy)
        inventoryState.reconcile(buy, 1_000L)
        val partialSell = order("sell-1", EventMakerSide.SELL, "0.97", "10.00", "4.00", "LIVE")
        inventoryState.onOrderPlaced(partialSell)
        inventoryState.reconcile(partialSell, 2_000L)

        val settledState = EventMakerLiveState(settledMarket, config)
        val settlementBuy = order("buy-2", EventMakerSide.BUY, "0.96", "10.00", "10.00", "FILLED")
        settledState.onOrderPlaced(settlementBuy)
        settledState.reconcile(settlementBuy, 3_000L)
        val settlement = settledState.recordSettlement(
            exitPrice = BigDecimal.ONE,
            now = 4_000L,
            outcomeIndex = settledMarket.favoriteIndex,
            negativeRisk = false
        )
        settledState.markRedemption(
            cycleExitOrderId = settlement.cycleExitOrderId,
            status = EventMakerRedemptionStatus.REDEEMED,
            transactionHash = "0xtx",
            now = 4_100L
        )

        val stateStore = EventMakerStateStore(tempDir.resolve("state.json").toString())
        val recoveryPath = tempDir.resolve("recovery.json")
        stateStore.save(
            EventMakerPersistedState(
                mode = EventMakerMode.SHADOW.name,
                accountId = null,
                startedAt = 500L,
                cumulativeRealizedPnl = inventoryState.realizedPnl + settledState.realizedPnl,
                dailyPnlDay = 1L,
                dailyPnlBaseline = 0.0,
                markets = listOf(inventoryState.toPersisted(), settledState.toPersisted())
            )
        )
        EventMakerShadowRecoveryStore.save(recoveryPath, listOf(inventoryMarket, settledMarket), 5_000L)

        val loaded = stateStore.load()!!
        val recovery = EventMakerShadowRecoveryStore.load(recoveryPath)!!
        val allMarkets = EventMakerShadowRecoveryStore.merge(emptyList(), recovery.markets)
        val persistedByCondition = loaded.markets.associateBy { it.conditionId }
        val restored = allMarkets.map { market ->
            EventMakerLiveState(market, config).apply { restore(persistedByCondition.getValue(market.conditionId)) }
        }
        val restoredInventory = restored.first { it.market.conditionId == "inventory" }
        val restoredSettled = restored.first { it.market.conditionId == "settled" }

        assertNotNull(restoredInventory.activeOrder)
        restoredInventory.onOrderCanceled()
        assertNull(restoredInventory.activeOrder)
        assertEquals(EventMakerPhase.LONG, restoredInventory.phase)
        assertEquals(0, BigDecimal("6.00").compareTo(restoredInventory.inventoryShares))
        assertEquals(2, restoredInventory.fills.size)
        assertEquals(0.04, restoredInventory.realizedPnl, 1e-9)

        assertEquals(0, BigDecimal.ZERO.compareTo(restoredSettled.inventoryShares))
        assertEquals(1, restoredSettled.cycles.size)
        assertEquals("SETTLE", restoredSettled.cycles.single().exitType)
        assertEquals(1, restoredSettled.settlements.size)
        assertEquals(EventMakerRedemptionStatus.REDEEMED, restoredSettled.settlements.single().redemptionStatus)
        assertTrue(restoredSettled.cycles.single().pnl > 0.0)
    }

    @Test
    fun `ledger metadata can reconstruct a market without the sidecar`() {
        val persisted = EventMakerLiveState(market("condition", "favorite-token"), EventMakerConfig()).toPersisted()

        val rebuilt = EventMakerShadowRecoveryStore.reconstructMarket(persisted)

        assertNotNull(rebuilt)
        assertEquals("condition", rebuilt!!.conditionId)
        assertEquals("favorite-token", rebuilt.favoriteTokenId)
        assertEquals(persisted.endTimestamp, rebuilt.endTimestamp)
    }

    private fun market(conditionId: String, favoriteTokenId: String) = LivePaperMarket(
        conditionId = conditionId,
        question = "question-$conditionId",
        tag = "Geopolitics",
        volume24hr = 100_000.0,
        endTimestamp = Long.MAX_VALUE / 4,
        tickSize = 0.01,
        outcomes = listOf("Yes", "No"),
        tokenIds = listOf(favoriteTokenId, "other-$conditionId"),
        favoriteIndex = 0
    )

    private fun order(
        id: String,
        side: EventMakerSide,
        price: String,
        original: String,
        matched: String,
        status: String
    ) = EventMakerLiveOrder(
        orderId = id,
        side = side,
        price = BigDecimal(price),
        originalShares = BigDecimal(original),
        matchedShares = BigDecimal(matched),
        status = status
    )
}