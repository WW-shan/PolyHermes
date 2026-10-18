package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.MarketBook
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class EventMakerQuotePlanner {

    fun planBuy(
        config: EventMakerConfig,
        book: MarketBook,
        tickSize: BigDecimal,
        now: Long = System.currentTimeMillis()
    ): EventMakerQuoteIntent? {
        if (isStale(config, book, now)) return null
        val bestBid = book.bestBid ?: return null
        val bestAsk = book.bestAsk ?: return null
        if (bestBid < config.minPrice || bestBid >= config.maxPrice || bestAsk <= bestBid) return null

        val tick = tickSize.max(BigDecimal.valueOf(0.0001))
        val bid = BigDecimal.valueOf(bestBid)
        val ask = BigDecimal.valueOf(bestAsk)
        val spreadTicks = ask.subtract(bid).divide(tick, 0, RoundingMode.HALF_UP)
        if (spreadTicks < BigDecimal.valueOf(config.minSpreadTicks.toLong())) return null
        val entryOffset = BigDecimal.valueOf(config.entryOffsetCents / 100.0)
        val improved = bid.subtract(entryOffset).add(tick.multiply(BigDecimal(config.improveTicks)))
        val rawLimit = improved.min(ask - tick).max(tick)
        val limit = roundDown(rawLimit, tick)
        if (limit <= BigDecimal.ZERO || limit >= ask) return null

        val joinsBestBid = limit.subtract(bid).abs() <= tick.divide(BigDecimal.TEN)
        val queueAhead = if (joinsBestBid) book.bidSizeAt(bestBid) else 0.0
        if (queueAhead > config.maxQueueAheadShares) return null

        val shares = BigDecimal.valueOf(config.orderNotional)
            .divide(limit, 2, RoundingMode.DOWN)
            .max(BigDecimal.ONE)
        return EventMakerQuoteIntent(
            side = EventMakerSide.BUY,
            price = limit,
            shares = shares,
            queueAheadShares = queueAhead,
            reason = when {
                config.entryOffsetCents > 0.0 -> "passive_offset"
                config.improveTicks > 0 && queueAhead <= 0.0 -> "improved_price"
                else -> "join_queue"
            }
        )
    }

    fun planSell(
        config: EventMakerConfig,
        book: MarketBook,
        tickSize: BigDecimal,
        entryPrice: BigDecimal,
        heldShares: BigDecimal,
        now: Long = System.currentTimeMillis()
    ): EventMakerQuoteIntent? {
        if (isStale(config, book, now)) return null
        if (heldShares <= BigDecimal.ZERO) return null
        val bestBid = book.bestBid ?: return null
        val bestAsk = book.bestAsk ?: return null
        if (bestAsk <= bestBid) return null

        val tick = tickSize.max(BigDecimal.valueOf(0.0001))
        val bid = BigDecimal.valueOf(bestBid)
        val ask = BigDecimal.valueOf(bestAsk)
        val target = entryPrice + BigDecimal.valueOf(config.exitSpreadCents / 100.0)
        val rawLimit = if (config.exitImproveTicks > 0) {
            BigDecimal.valueOf(bestAsk) - tick.multiply(BigDecimal(config.exitImproveTicks))
        } else {
            target
        }
        val limit = roundUp(rawLimit, tick).min(BigDecimal.valueOf(0.999))
        if (limit <= bid || limit <= entryPrice) return null

        val queueAhead = if (limit > ask || limit < ask - tick.divide(BigDecimal.TEN)) {
            0.0
        } else {
            book.askSizeAtOrBelow(limit.toDouble())
        }
        if (queueAhead > config.maxQueueAheadShares) return null

        return EventMakerQuoteIntent(
            side = EventMakerSide.SELL,
            price = limit,
            shares = heldShares.setScale(2, RoundingMode.DOWN).max(BigDecimal.ZERO),
            queueAheadShares = queueAhead,
            reason = if (queueAhead <= 0.0) "inside_ask" else "join_ask"
        )
    }

    private fun isStale(config: EventMakerConfig, book: MarketBook, now: Long): Boolean =
        now - book.updatedAt > config.staleBookSeconds * 1000L

    private fun roundDown(value: BigDecimal, tick: BigDecimal): BigDecimal =
        value.divide(tick, 0, RoundingMode.FLOOR).multiply(tick).stripTrailingZeros()

    private fun roundUp(value: BigDecimal, tick: BigDecimal): BigDecimal =
        value.divide(tick, 0, RoundingMode.CEILING).multiply(tick).stripTrailingZeros()
}
