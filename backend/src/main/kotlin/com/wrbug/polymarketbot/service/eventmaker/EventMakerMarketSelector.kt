package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.MarketBook

data class EventMakerMarketEvaluation(
    val conditionId: String,
    val question: String,
    val favoriteTokenId: String,
    val bestBid: Double?,
    val bestAsk: Double?,
    val spreadTicks: Double?,
    val bidQueueShares: Double?,
    val askQueueShares: Double?,
    val entryQueueShares: Double?,
    val exitQueueShares: Double?,
    val selected: Boolean,
    val reason: String
)

/**
 * Selects markets for maker quoting and records why each candidate passed or failed.
 */
object EventMakerMarketSelector {
    fun evaluate(
        markets: List<LivePaperMarket>,
        books: Map<String, MarketBook>,
        minSpreadTicks: Int,
        minPrice: Double = 0.0,
        maxPrice: Double = 1.0,
        maxQueueAheadShares: Double = Double.MAX_VALUE,
        improveTicks: Int = 1,
        entryOffsetCents: Double = 0.0,
        exitImproveTicks: Int = 1,
        exitSpreadCents: Double = 1.0,
        takerEntry: Boolean = false
    ): List<EventMakerMarketEvaluation> = markets.map { market ->
        val book = books[market.favoriteTokenId]
        val bestBid = book?.bestBid
        val bestAsk = book?.bestAsk
        val tick = market.tickSize.takeIf { it > 0.0 }
        val spreadTicks = if (bestBid != null && bestAsk != null && tick != null) {
            (bestAsk - bestBid + 1e-9) / tick
        } else {
            null
        }
        val bandPrice = if (takerEntry) bestAsk else bestBid
        val bidQueue = if (book != null && bestBid != null) book.bidSizeAt(bestBid) else null
        val askQueue = if (book != null && bestAsk != null) book.askSizeAtOrBelow(bestAsk) else null
        val entryQueue = when {
            takerEntry -> 0.0
            spreadTicks != null && improveTicks > 0 && spreadTicks >= 2.0 -> 0.0
            entryOffsetCents > 0.0 -> 0.0
            else -> bidQueue
        }
        val exitTarget = if (bestBid != null) bestBid + exitSpreadCents / 100.0 else null
        val exitQueue = when {
            spreadTicks != null && exitImproveTicks > 0 && spreadTicks >= 2.0 -> 0.0
            exitTarget != null && bestAsk != null && exitTarget > bestAsk -> 0.0
            else -> askQueue
        }
        val reason = when {
            book == null -> "no_book"
            bestBid == null -> "no_bid"
            bestAsk == null -> "no_ask"
            bandPrice == null || bandPrice < minPrice || bandPrice >= maxPrice -> "price_out_of_range"
            tick == null -> "invalid_tick"
            !takerEntry && (spreadTicks == null || spreadTicks < minSpreadTicks) -> "spread_below_min"
            entryQueue == null || entryQueue > maxQueueAheadShares + 1e-9 -> "entry_queue_too_deep"
            exitQueue == null || exitQueue > maxQueueAheadShares + 1e-9 -> "exit_queue_too_deep"
            else -> "selected"
        }
        EventMakerMarketEvaluation(
            conditionId = market.conditionId,
            question = market.question,
            favoriteTokenId = market.favoriteTokenId,
            bestBid = bestBid,
            bestAsk = bestAsk,
            spreadTicks = spreadTicks,
            bidQueueShares = bidQueue,
            askQueueShares = askQueue,
            entryQueueShares = entryQueue,
            exitQueueShares = exitQueue,
            selected = reason == "selected",
            reason = reason
        )
    }

    fun select(
        markets: List<LivePaperMarket>,
        books: Map<String, MarketBook>,
        maxMarkets: Int,
        minSpreadTicks: Int,
        minPrice: Double = 0.0,
        maxPrice: Double = 1.0,
        maxQueueAheadShares: Double = Double.MAX_VALUE,
        improveTicks: Int = 1,
        entryOffsetCents: Double = 0.0,
        exitImproveTicks: Int = 1,
        exitSpreadCents: Double = 1.0,
        takerEntry: Boolean = false
    ): List<LivePaperMarket> {
        if (maxMarkets <= 0 || (!takerEntry && minSpreadTicks <= 0)) return emptyList()
        val selectedIds = evaluate(
            markets = markets,
            books = books,
            minSpreadTicks = minSpreadTicks,
            minPrice = minPrice,
            maxPrice = maxPrice,
            maxQueueAheadShares = maxQueueAheadShares,
            improveTicks = improveTicks,
            entryOffsetCents = entryOffsetCents,
            exitImproveTicks = exitImproveTicks,
            exitSpreadCents = exitSpreadCents,
            takerEntry = takerEntry
        ).asSequence()
            .filter { it.selected }
            .map { it.conditionId }
            .take(maxMarkets)
            .toSet()
        return markets.filter { it.conditionId in selectedIds }
    }
}
