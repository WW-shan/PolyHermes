package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.BookLevel
import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.MarketBook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EventMakerMarketSelectorTest {
    @Test
    fun `one tick spread is excluded because maker price cannot improve without crossing`() {
        val market = market("one-tick", 0.01)
        val books = mapOf("token-one-tick" to book("token-one-tick", 0.97, 0.98))

        val selected = EventMakerMarketSelector.select(listOf(market), books, maxMarkets = 3, minSpreadTicks = 2)

        assertEquals(emptyList<LivePaperMarket>(), selected)
    }

    @Test
    fun `two tick spread is selected and result respects max markets`() {
        val first = market("first", 0.01)
        val second = market("second", 0.01)
        val books = mapOf(
            "token-first" to book("token-first", 0.95, 0.97),
            "token-second" to book("token-second", 0.96, 0.98)
        )

        val selected = EventMakerMarketSelector.select(
            listOf(first, second),
            books,
            maxMarkets = 1,
            minSpreadTicks = 2
        )

        assertEquals(listOf(first), selected)
    }

    @Test
    fun `price outside configured range is excluded`() {
        val market = market("too-high", 0.001)
        val books = mapOf("token-too-high" to book("token-too-high", 0.994, 0.997))

        val selected = EventMakerMarketSelector.select(
            listOf(market),
            books,
            maxMarkets = 3,
            minSpreadTicks = 2,
            minPrice = 0.90,
            maxPrice = 0.99
        )

        assertEquals(emptyList<LivePaperMarket>(), selected)
    }
    private fun book(tokenId: String, bid: Double, ask: Double) = MarketBook(
        tokenId = tokenId,
        bids = listOf(BookLevel(bid, 100.0)),
        asks = listOf(BookLevel(ask, 100.0)),
        updatedAt = 1_000L
    )

    private fun market(id: String, tick: Double) = LivePaperMarket(
        conditionId = id,
        question = id,
        tag = "Politics",
        volume24hr = 100_000.0,
        endTimestamp = Long.MAX_VALUE,
        tickSize = tick,
        outcomes = listOf("Yes", "No"),
        tokenIds = listOf("token-$id", "other-$id"),
        favoriteIndex = 0
    )
}