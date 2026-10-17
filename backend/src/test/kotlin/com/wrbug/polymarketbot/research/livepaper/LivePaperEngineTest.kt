package com.wrbug.polymarketbot.research.livepaper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LivePaperEngineTest {
    private val market = LivePaperMarket(
        conditionId = "condition",
        question = "question",
        tag = "Politics",
        volume24hr = 100_000.0,
        endTimestamp = 20_000L,
        tickSize = 0.001,
        outcomes = listOf("Yes", "No"),
        tokenIds = listOf("fav", "other"),
        favoriteIndex = 0
    )

    @Test
    fun `join quote requires displayed queue and own size to trade through`() {
        val state = state()
        val book = book(bidSize = 50.0, askSize = 50.0)
        state.onBook(book, 1_000)

        assertTrue(state.buyOrder != null)
        assertEquals(0.96, state.buyOrder!!.price, 1e-9)
        assertFalse(state.onTrade(trade("SELL", 0.96, 40.0), 2_000).any { it is PaperEvent.Fill })

        val fills = state.onTrade(trade("SELL", 0.96, 120.0), 3_000)
        assertTrue(fills.any { it is PaperEvent.Fill && it.side == PaperSide.BUY })
        assertEquals(PaperPhase.SELL_PENDING, state.phase)
        assertTrue(state.sellOrder != null)
    }

    @Test
    fun `sell fills only after buy aggressor trades through target`() {
        val state = state()
        val book = book(bidSize = 50.0, askSize = 50.0)
        state.onBook(book, 1_000)
        state.onTrade(trade("SELL", 0.96, 160.0), 2_000)

        assertEquals(PaperPhase.SELL_PENDING, state.phase)
        val sell = state.sellOrder!!
        assertEquals(0.97, sell.price, 1e-9)
        assertFalse(state.onTrade(trade("SELL", 0.98, 500.0), 3_000).any { it is PaperEvent.Fill })
        val fills = state.onTrade(trade("BUY", 0.97, sell.requiredVolume + 1.0), 4_000)

        assertTrue(fills.any { it is PaperEvent.Fill && it.side == PaperSide.SELL })
        assertEquals(1, state.completedCycles)
        assertTrue(state.realizedPnl > 1.0)
    }

    @Test
    fun `improve one tick gives queue priority`() {
        val state = state(improveTicks = 1)
        state.onBook(book(bidSize = 1_000.0, askSize = 1_000.0), 1_000)

        assertEquals(0.961, state.buyOrder!!.price, 1e-9)
        assertEquals(0.0, state.buyOrder!!.queueAhead, 1e-9)
        val fills = state.onTrade(trade("SELL", 0.961, 200.0), 2_000)

        assertTrue(fills.any { it is PaperEvent.Fill && it.side == PaperSide.BUY })
    }

    @Test
    fun `partial sell fills real traded size and keeps remaining inventory`() {
        val state = state()
        state.onBook(book(bidSize = 50.0, askSize = 50.0), 1_000)
        state.onTrade(trade("SELL", 0.96, 200.0), 2_000)
        val sell = state.sellOrder!!
        val fills = state.onTrade(trade("BUY", 0.97, sell.queueAhead + 40.0), 3_000)

        assertTrue(fills.any { it is PaperEvent.Fill && it.side == PaperSide.SELL })
        assertEquals(0, state.completedCycles)
        assertTrue(state.inventory!!.shares > 0.0)
        assertEquals(PaperPhase.SELL_PENDING, state.phase)
        assertEquals(40.0 * (0.97 - 0.96), state.realizedPnl, 1e-6)

        val requoted = state.sellOrder!!
        val remaining = state.inventory!!.shares
        state.onTrade(trade("BUY", 0.97, requoted.queueAhead + remaining, 4_000), 4_000)

        assertEquals(1, state.completedCycles)
        assertEquals(null, state.inventory)
        assertEquals(2, state.sellFills)
        assertEquals(40.0 + remaining, state.cycles.single().shares, 1e-6)
        assertEquals((40.0 + remaining) * 0.01, state.realizedPnl, 1e-6)
    }

    @Test
    fun `wrong aggressor side does not fill`() {
        val state = state()
        state.onBook(book(bidSize = 50.0, askSize = 50.0), 1_000)

        val fills = state.onTrade(trade("BUY", 0.95, 10_000.0), 2_000)

        assertFalse(fills.any { it is PaperEvent.Fill })
        assertEquals(PaperPhase.BUY_PENDING, state.phase)
    }

    @Test
    fun `join ask exit uses current best ask when it is below fixed target`() {
        val state = state(exitJoinAsk = true)
        state.onBook(book(bidSize = 50.0, askSize = 50.0, askPrice = 0.968), 1_000)
        state.onTrade(trade("SELL", 0.96, 200.0), 2_000)

        assertEquals(PaperPhase.SELL_PENDING, state.phase)
        assertEquals(0.968, state.sellOrder!!.price, 1e-9)
        assertEquals(50.0, state.sellOrder!!.queueAhead, 1e-9)
    }

    @Test
    fun `improve ask exit places one tick inside the spread`() {
        val state = state(improveTicks = 1, exitImproveTicks = 1)
        state.onBook(book(bidSize = 50.0, askSize = 50.0, askPrice = 0.97), 1_000)
        state.onTrade(trade("SELL", 0.961, 200.0), 2_000)

        assertEquals(PaperPhase.SELL_PENDING, state.phase)
        assertEquals(0.969, state.sellOrder!!.price, 1e-9)
        assertEquals(0.0, state.sellOrder!!.queueAhead, 1e-9)
    }

    @Test
    fun `transaction hash deduplicates websocket and poll copies`() {
        val ws = trade("SELL", 0.908, 10.0, 2_000).copy(source = "ws", transactionHash = "0xabc")
        val polled = trade("SELL", 0.9080253921, 10.0, 2_005).copy(source = "data-api", transactionHash = "0xabc")

        assertEquals(ws.key, polled.key)
    }
    private fun state(improveTicks: Int = 0, exitJoinAsk: Boolean = false, exitImproveTicks: Int = 0) = PaperArmState(
        market = market,
        config = PaperArmConfig(
            name = "test",
            minPrice = 0.95,
            maxPrice = 0.99,
            exitSpreadCents = 1.0,
            improveTicks = improveTicks,
            exitJoinAsk = exitJoinAsk,
            exitImproveTicks = exitImproveTicks,
            queueFactor = 1.0,
            orderNotional = 100.0,
            requoteSeconds = 300,
            maxCycles = 1
        ),
        tickSize = 0.001
    )

    private fun book(bidSize: Double, askSize: Double, askPrice: Double = 0.97) = MarketBook(
        tokenId = "fav",
        bids = if (bidSize > 0.0) listOf(BookLevel(0.96, bidSize)) else emptyList(),
        asks = listOf(BookLevel(askPrice, askSize)),
        updatedAt = 1_000
    )

    private fun trade(side: String, price: Double, size: Double, timestamp: Long = 2_000) = LiveTradePrint(
        conditionId = "condition",
        tokenId = "fav",
        timestamp = timestamp,
        side = side,
        price = price,
        size = size,
        source = "test"
    )
}