package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.MarketBook

/**
 * Selects markets where a one-tick maker improvement is still possible.
 * A one-tick spread cannot be improved without crossing, so those markets are excluded by default.
 */
object EventMakerMarketSelector {
    fun select(
        markets: List<LivePaperMarket>,
        books: Map<String, MarketBook>,
        maxMarkets: Int,
        minSpreadTicks: Int,
        minPrice: Double = 0.0,
        maxPrice: Double = 1.0
    ): List<LivePaperMarket> {
        if (maxMarkets <= 0 || minSpreadTicks <= 0) return emptyList()
        return markets.asSequence()
            .filter { market ->
                val book = books[market.favoriteTokenId] ?: return@filter false
                val bestBid = book.bestBid ?: return@filter false
                val bestAsk = book.bestAsk ?: return@filter false
                if (bestBid < minPrice || bestBid >= maxPrice) return@filter false
                val tick = market.tickSize.takeIf { it > 0.0 } ?: return@filter false
                bestAsk - bestBid + 1e-9 >= tick * minSpreadTicks
            }
            .take(maxMarkets)
            .toList()
    }
}