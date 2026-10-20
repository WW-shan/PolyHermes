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
        val bandPrice = if (config.takerEntry) bestAsk else bestBid
        if (bandPrice < config.minPrice || bandPrice >= config.maxPrice || bestAsk <= bestBid) return null

        val tick = tickSize.max(BigDecimal.valueOf(0.0001))
        val bid = BigDecimal.valueOf(bestBid)
        val ask = BigDecimal.valueOf(bestAsk)
        val spreadTicks = ask.subtract(bid).divide(tick, 0, RoundingMode.HALF_UP)
        if (!config.takerEntry && spreadTicks < BigDecimal.valueOf(config.minSpreadTicks.toLong())) return null
        if (config.takerEntry) {
            val takerLimit = roundUp(
                ask.add(tick.multiply(BigDecimal(config.takerEntrySlippageTicks))),
                tick
            ).min(BigDecimal("0.999"))
            if (takerLimit <= BigDecimal.ZERO || takerLimit >= BigDecimal.ONE) return null
            val desiredShares = BigDecimal.valueOf(config.orderNotional)
                .divide(takerLimit, 2, RoundingMode.DOWN)
                .max(BigDecimal.ONE)
            val visibleAskShares = BigDecimal.valueOf(book.askSizeAtOrBelow(takerLimit.toDouble()))
            val takerShares = desiredShares.min(visibleAskShares).setScale(2, RoundingMode.DOWN)
            if (takerShares <= BigDecimal.ZERO) return null
            return EventMakerQuoteIntent(
                side = EventMakerSide.BUY,
                price = takerLimit,
                shares = takerShares,
                queueAheadShares = 0.0,
                reason = "taker_entry",
                takerEntry = true
            )
        }
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
        val improvedLimit = if (config.exitImproveTicks > 0) {
            BigDecimal.valueOf(bestAsk) - tick.multiply(BigDecimal(config.exitImproveTicks))
        } else {
            target
        }
        // A taker entry fills at the ask, so undercutting the ask by a tick can
        // land below the entry price. In that case fall back to the profit target
        // instead of refusing to place an exit.
        val rawLimit = if (config.exitImproveTicks > 0 && improvedLimit <= entryPrice) target else improvedLimit
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
