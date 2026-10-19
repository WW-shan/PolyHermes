package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EventMakerLiveStateTest {
    private val state = EventMakerLiveState(market(), EventMakerConfig())

    @Test
    fun `buy fill enters long and sell fill completes cycle`() {
        val buy = order("buy-1", EventMakerSide.BUY, "0.96", "10.00", "10.00", "FILLED")
        state.onOrderPlaced(buy)
        state.reconcile(buy, 1_000)

        assertEquals(EventMakerPhase.LONG, state.phase)
        assertEquals(BigDecimal("10.00"), state.inventoryShares)
        assertEquals(0, state.completedCycles)
        assertEquals(1, state.fills.size)
        assertEquals(EventMakerSide.BUY, state.fills.single().side)

        val sell = order("sell-1", EventMakerSide.SELL, "0.97", "10.00", "10.00", "FILLED")
        state.onOrderPlaced(sell)
        state.reconcile(sell, 2_000)

        assertEquals(EventMakerPhase.FLAT, state.phase)
        assertEquals(BigDecimal.ZERO, state.inventoryShares)
        assertEquals(1, state.completedCycles)
        assertEquals(0.1, state.realizedPnl, 1e-9)
        assertEquals(2, state.fills.size)
        assertEquals(1, state.cycles.size)
        assertEquals(0.1, state.cycles.single().pnl, 1e-9)
        assertEquals(BigDecimal("10.00"), state.cycles.single().shares)
        assertEquals("buy-1", state.cycles.single().entryOrderId)
        assertEquals("sell-1", state.cycles.single().exitOrderId)
    }

    @Test
    fun `partial sell keeps remaining inventory and can be reconciled after cancel`() {
        val buy = order("buy-1", EventMakerSide.BUY, "0.96", "10.00", "10.00", "FILLED")
        state.onOrderPlaced(buy)
        state.reconcile(buy, 1_000)

        val sell = order("sell-1", EventMakerSide.SELL, "0.97", "10.00", "4.00", "LIVE")
        state.onOrderPlaced(sell)
        state.reconcile(sell, 2_000)

        assertEquals(EventMakerPhase.LONG, state.phase)
        assertEquals(BigDecimal("6.00"), state.inventoryShares)
        assertEquals(0.04, state.realizedPnl, 1e-9)
        assertEquals(0, state.completedCycles)
        assertEquals(2, state.fills.size)
        assertEquals(EventMakerSide.SELL, state.fills.last().side)

        state.onOrderCanceled()
        assertEquals(EventMakerPhase.LONG, state.phase)
        assertEquals(BigDecimal("6.00"), state.inventoryShares)
    }

    @Test
    fun `partial sells across two orders aggregate the full cycle pnl and shares`() {
        val buy = order("buy-1", EventMakerSide.BUY, "0.96", "10.00", "10.00", "FILLED")
        state.onOrderPlaced(buy)
        state.reconcile(buy, 1_000)

        val firstSell = order("sell-1", EventMakerSide.SELL, "0.97", "10.00", "4.00", "LIVE")
        state.onOrderPlaced(firstSell)
        state.reconcile(firstSell, 2_000)
        state.onOrderCanceled()

        val secondSell = order("sell-2", EventMakerSide.SELL, "0.975", "6.00", "6.00", "FILLED")
        state.onOrderPlaced(secondSell)
        state.reconcile(secondSell, 3_000)

        assertEquals(1, state.completedCycles)
        assertEquals(0.13, state.realizedPnl, 1e-9)
        assertEquals(1, state.cycles.size)
        assertEquals(BigDecimal("10.00"), state.cycles.single().shares)
        assertEquals(0.13, state.cycles.single().pnl, 1e-9)
        assertEquals("buy-1", state.cycles.single().entryOrderId)
        assertEquals("sell-2", state.cycles.single().exitOrderId)
        assertEquals(3, state.fills.size)
        assertEquals(BigDecimal.ZERO, state.inventoryShares)
    }

    @Test
    fun `sell fill larger than inventory fails closed`() {
        val buy = order("buy-1", EventMakerSide.BUY, "0.96", "10.00", "10.00", "FILLED")
        state.onOrderPlaced(buy)
        state.reconcile(buy, 1_000)

        val sell = order("sell-1", EventMakerSide.SELL, "0.97", "12.00", "12.00", "FILLED")
        state.onOrderPlaced(sell)

        val error = assertThrows(IllegalStateException::class.java) { state.reconcile(sell, 2_000) }

        assertEquals(true, error.message!!.startsWith("sell_fill_exceeds_inventory"))
    }

    @Test
    fun `rejected buy without fill returns to flat`() {
        val rejected = order("buy-rejected", EventMakerSide.BUY, "0.96", "10.00", "0.00", "REJECTED")
        state.onOrderPlaced(rejected)
        state.reconcile(rejected, 1_000)

        assertEquals(EventMakerPhase.FLAT, state.phase)
        assertEquals(BigDecimal.ZERO, state.inventoryShares)
        assertEquals(0, state.completedCycles)
    }

    @Test
    fun `persisted state restores inventory and active order`() {
        val buy = order("buy-1", EventMakerSide.BUY, "0.96", "10.00", "10.00", "FILLED")
        state.onOrderPlaced(buy)
        state.reconcile(buy, 1_000)
        val sell = order("sell-1", EventMakerSide.SELL, "0.97", "4.00", "0.00", "LIVE")
        state.onOrderPlaced(sell)

        val restored = EventMakerLiveState(market(), EventMakerConfig())
        restored.restore(state.toPersisted())

        assertEquals(EventMakerPhase.SELL_PENDING, restored.phase)
        assertEquals(BigDecimal("10.00"), restored.inventoryShares)
        assertEquals(BigDecimal("0.96"), restored.entryPrice)
        assertEquals(1_000L, restored.entryAt)
        assertEquals("sell-1", restored.activeOrder?.orderId)
        assertEquals(EventMakerSide.SELL, restored.activeOrder?.side)
        assertEquals("LIVE", restored.activeOrder?.status)
        assertEquals(1, restored.fills.size)
        assertEquals(EventMakerSide.BUY, restored.fills.single().side)
    }

    @Test
    fun `restore rejects inventory that is not backed by fills`() {
        val buy = order("buy-1", EventMakerSide.BUY, "0.96", "10.00", "10.00", "FILLED")
        state.onOrderPlaced(buy)
        state.reconcile(buy, 1_000)
        val corrupted = state.toPersisted().copy(fills = emptyList())

        val restored = EventMakerLiveState(market(), EventMakerConfig())

        val error = assertThrows(IllegalStateException::class.java) { restored.restore(corrupted) }

        assertEquals(true, error.message!!.startsWith("persisted_inventory_mismatch"))
    }

    @Test
    fun `restore rejects a different favorite token`() {
        val buy = order("buy-1", EventMakerSide.BUY, "0.96", "10.00", "10.00", "FILLED")
        state.onOrderPlaced(buy)
        state.reconcile(buy, 1_000)
        val corrupted = state.toPersisted().copy(favoriteTokenId = "other-token")

        val restored = EventMakerLiveState(market(), EventMakerConfig())

        val error = assertThrows(IllegalStateException::class.java) { restored.restore(corrupted) }

        assertEquals(true, error.message!!.startsWith("persisted_favorite_token_mismatch"))
    }

    @Test
    fun `pending buy notional counts only unfilled exposure`() {
        val buy = EventMakerLiveOrder(
            orderId = "buy-1",
            side = EventMakerSide.BUY,
            price = BigDecimal("0.50"),
            originalShares = BigDecimal("10.00"),
            matchedShares = BigDecimal("3.00"),
            status = "LIVE"
        )
        state.onOrderPlaced(buy)

        assertEquals(3.5, state.pendingBuyNotional(), 1e-9)
    }

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

    private fun market() = LivePaperMarket(
        conditionId = "condition",
        question = "question",
        tag = "Politics",
        volume24hr = 100_000.0,
        endTimestamp = Long.MAX_VALUE,
        tickSize = 0.001,
        outcomes = listOf("Yes", "No"),
        tokenIds = listOf("token", "other"),
        favoriteIndex = 0
    )
}