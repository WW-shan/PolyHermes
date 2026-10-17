package com.wrbug.polymarketbot.research.livepaper

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class LivePaperMarket(
    val conditionId: String,
    val question: String,
    val tag: String,
    val volume24hr: Double,
    val endTimestamp: Long,
    val tickSize: Double,
    val outcomes: List<String>,
    val tokenIds: List<String>,
    val favoriteIndex: Int
) {
    val favoriteTokenId: String get() = tokenIds[favoriteIndex]
}

data class BookLevel(val price: Double, val size: Double)

data class MarketBook(
    val tokenId: String,
    val bids: List<BookLevel>,
    val asks: List<BookLevel>,
    val updatedAt: Long
) {
    val bestBid: Double? get() = bids.filter { it.size > 0.0 }.maxOfOrNull { it.price }
    val bestAsk: Double? get() = asks.filter { it.size > 0.0 }.minOfOrNull { it.price }

    fun bidSizeAt(price: Double): Double =
        bids.filter { kotlin.math.abs(it.price - price) <= 1e-9 }.sumOf { it.size }

    fun askSizeAtOrBelow(price: Double): Double =
        asks.filter { it.price <= price + 1e-9 }.sumOf { it.size }

    fun applyPriceChange(change: BookPriceChange): MarketBook {
        if (change.tokenId != tokenId) return this
        val isBid = change.side.equals("BUY", true) || change.side.equals("BID", true)
        val current = if (isBid) bids else asks
        val updated = current.toMutableList()
        val index = updated.indexOfFirst { kotlin.math.abs(it.price - change.price) <= 1e-9 }
        if (change.size <= 0.0) {
            if (index >= 0) updated.removeAt(index)
        } else if (index >= 0) {
            updated[index] = BookLevel(change.price, change.size)
        } else {
            updated += BookLevel(change.price, change.size)
        }
        return if (isBid) copy(bids = updated, updatedAt = change.timestamp)
        else copy(asks = updated, updatedAt = change.timestamp)
    }
}

data class BookPriceChange(
    val tokenId: String,
    val price: Double,
    val size: Double,
    val side: String,
    val timestamp: Long
)

data class LiveTradePrint(
    val conditionId: String,
    val tokenId: String,
    val timestamp: Long,
    val side: String,
    val price: Double,
    val size: Double,
    val source: String,
    val transactionHash: String? = null
) {
    val key: String
        get() {
            val tx = transactionHash?.trim()?.lowercase()?.removePrefix("0x")?.takeIf { it.isNotBlank() }
            val normalizedPrice = String.format(Locale.US, "%.6f", price)
            val normalizedSize = String.format(Locale.US, "%.6f", size)
            return if (tx != null) {
                // WS and Data API disagree on fill price precision, so price is not part of the tx key.
                "tx|$tx|$tokenId|${side.uppercase()}|$normalizedSize"
            } else {
                "$conditionId|$tokenId|${timestamp / 1000}|${side.uppercase()}|$normalizedPrice|$normalizedSize"
            }
        }
}

data class PaperArmConfig(
    val name: String,
    val minPrice: Double,
    val maxPrice: Double,
    val exitSpreadCents: Double,
    val improveTicks: Int = 0,
    val exitJoinAsk: Boolean = false,
    val exitImproveTicks: Int = 0,
    val queueFactor: Double = 1.0,
    val orderNotional: Double = 100.0,
    val requoteSeconds: Long = 300,
    val maxCycles: Int = 3,
    val feeRateBps: Double = 0.0
) {
    init {
        require(minPrice >= 0.0 && minPrice < maxPrice && maxPrice <= 1.0)
        require(exitSpreadCents > 0.0)
        require(improveTicks >= 0)
        require(exitImproveTicks >= 0)
        require(queueFactor > 0.0)
        require(orderNotional > 0.0)
        require(requoteSeconds > 0)
        require(maxCycles >= 1)
        require(feeRateBps >= 0.0)
    }
}

enum class PaperPhase { FLAT, BUY_PENDING, LONG, SELL_PENDING }
enum class PaperSide { BUY, SELL }

data class PaperOrder(
    val side: PaperSide,
    val price: Double,
    val shares: Double,
    val placedAt: Long,
    var queueAhead: Double,
    val requiredVolume: Double,
    var remainingShares: Double = shares
)

data class PaperInventory(val entryAt: Long, val entryPrice: Double, var shares: Double)

data class PaperCycle(
    val entryAt: Long,
    val entryPrice: Double,
    val exitAt: Long,
    val exitPrice: Double,
    val shares: Double,
    val pnl: Double,
    val holdingSeconds: Long
)
sealed interface PaperEvent {
    val timestamp: Long
    val marketId: String
    val arm: String

    data class QuotePlaced(
        override val timestamp: Long,
        override val marketId: String,
        override val arm: String,
        val side: PaperSide,
        val price: Double,
        val shares: Double,
        val queueAhead: Double,
        val requiredVolume: Double
    ) : PaperEvent

    data class QuoteCanceled(
        override val timestamp: Long,
        override val marketId: String,
        override val arm: String,
        val side: PaperSide,
        val price: Double,
        val reason: String
    ) : PaperEvent

    data class Fill(
        override val timestamp: Long,
        override val marketId: String,
        override val arm: String,
        val side: PaperSide,
        val price: Double,
        val shares: Double,
        val tradePrice: Double,
        val tradeSource: String
    ) : PaperEvent
}

data class PaperSnapshot(
    val timestamp: Long,
    val marketId: String,
    val question: String,
    val arm: String,
    val phase: PaperPhase,
    val bestBid: Double?,
    val bestAsk: Double?,
    val entryPrice: Double?,
    val shares: Double,
    val realizedPnl: Double,
    val unrealizedPnl: Double,
    val totalPnl: Double,
    val deployedNotional: Double,
    val completedCycles: Int,
    val quotePrice: Double?,
    val quoteAgeSeconds: Long
)
class PaperArmState(
    val market: LivePaperMarket,
    val config: PaperArmConfig,
    private val tickSize: Double
) {
    var phase: PaperPhase = PaperPhase.FLAT
        private set
    var buyOrder: PaperOrder? = null
        private set
    var sellOrder: PaperOrder? = null
        private set
    var inventory: PaperInventory? = null
        private set
    val cycles: MutableList<PaperCycle> = mutableListOf()
    var quotesPlaced: Int = 0
        private set
    var buyFills: Int = 0
        private set
    var sellFills: Int = 0
        private set
    var lastBook: MarketBook? = null
        private set
    private var closedPnl: Double = 0.0
    private var totalBuyNotional: Double = 0.0
    private var tripSoldShares: Double = 0.0
    private var tripExitNotional: Double = 0.0
    private var tripPnl: Double = 0.0

    val completedCycles: Int get() = cycles.size
    val realizedPnl: Double get() = closedPnl
    val deployedNotional: Double get() = totalBuyNotional

    fun onBook(book: MarketBook, now: Long): List<PaperEvent> {
        if (book.tokenId != market.favoriteTokenId) return emptyList()
        lastBook = book
        val events = mutableListOf<PaperEvent>()
        val bestBid = book.bestBid ?: return events
        val bestAsk = book.bestAsk ?: return events
        if (bestAsk <= bestBid) return events

        when (phase) {
            PaperPhase.FLAT -> {
                if (cycles.size >= config.maxCycles) return events
                val current = buyOrder
                if (current == null) {
                    placeBuy(book, now)?.let { events += it } ?: return events
                } else if (now - current.placedAt >= config.requoteSeconds * 1000L) {
                    events += cancelOrder(current, now, "requote")
                    buyOrder = null
                    placeBuy(book, now)?.let { events += it } ?: return events
                }
            }
            PaperPhase.LONG -> {
                val currentInventory = inventory ?: return events
                val current = sellOrder
                if (current == null) {
                    placeSell(book, currentInventory, now)?.let { events += it } ?: return events
                } else if (now - current.placedAt >= config.requoteSeconds * 1000L) {
                    events += cancelOrder(current, now, "requote")
                    sellOrder = null
                    placeSell(book, currentInventory, now)?.let { events += it } ?: return events
                }
            }
            PaperPhase.BUY_PENDING, PaperPhase.SELL_PENDING -> Unit
        }
        return events
    }

    fun onTrade(trade: LiveTradePrint, now: Long): List<PaperEvent> {
        if (trade.tokenId != market.favoriteTokenId) return emptyList()
        val events = mutableListOf<PaperEvent>()
        when (phase) {
            PaperPhase.BUY_PENDING -> {
                val order = buyOrder ?: return events
                if (trade.timestamp < order.placedAt - 1_000L) return events
                if (trade.side.equals("SELL", true) && trade.price <= order.price + 1e-9) {
                    val available = consumeQueue(order, trade.size)
                    val filled = min(order.remainingShares, available / config.queueFactor)
                    if (filled > 1e-9) {
                        val wasPartial = filled + 1e-9 < order.remainingShares
                        order.remainingShares -= filled
                        inventory = PaperInventory(now, order.price, filled)
                        totalBuyNotional += order.price * filled
                        tripSoldShares = 0.0
                        tripExitNotional = 0.0
                        tripPnl = 0.0
                        buyOrder = null
                        sellOrder = null
                        phase = PaperPhase.LONG
                        buyFills++
                        events += PaperEvent.Fill(now, market.conditionId, config.name, PaperSide.BUY, order.price, filled, trade.price, trade.source)
                        if (wasPartial) {
                            events += PaperEvent.QuoteCanceled(now, market.conditionId, config.name, PaperSide.BUY, order.price, "partial_fill_remainder_canceled")
                        }
                        lastBook?.let { book -> placeSell(book, inventory!!, now)?.let { events += it } }
                    }
                }
            }
            PaperPhase.SELL_PENDING -> {
                val order = sellOrder ?: return events
                val held = inventory ?: return events
                if (trade.timestamp < order.placedAt - 1_000L) return events
                if (trade.side.equals("BUY", true) && trade.price >= order.price - 1e-9) {
                    val available = consumeQueue(order, trade.size)
                    val filled = min(order.remainingShares, available / config.queueFactor)
                    if (filled > 1e-9) {
                        val gross = (order.price - held.entryPrice) * filled
                        val fee = (held.entryPrice + order.price) * filled * config.feeRateBps / 10_000.0
                        val net = gross - fee
                        closedPnl += net
                        tripSoldShares += filled
                        tripExitNotional += order.price * filled
                        tripPnl += net
                        sellFills++
                        order.remainingShares -= filled
                        held.shares -= filled
                        events += PaperEvent.Fill(now, market.conditionId, config.name, PaperSide.SELL, order.price, filled, trade.price, trade.source)
                        if (held.shares <= 1e-9) {
                            val averageExitPrice = if (tripSoldShares > 0.0) tripExitNotional / tripSoldShares else order.price
                            cycles += PaperCycle(
                                held.entryAt,
                                held.entryPrice,
                                now,
                                averageExitPrice,
                                tripSoldShares,
                                tripPnl,
                                max(0L, (now - held.entryAt) / 1000L)
                            )
                            tripSoldShares = 0.0
                            tripExitNotional = 0.0
                            tripPnl = 0.0
                            inventory = null
                            sellOrder = null
                            phase = PaperPhase.FLAT
                            if (cycles.size < config.maxCycles) {
                                lastBook?.let { book -> placeBuy(book, now)?.let { events += it } }
                            }
                        } else {
                            events += PaperEvent.QuoteCanceled(now, market.conditionId, config.name, PaperSide.SELL, order.price, "partial_fill_requote")
                            sellOrder = null
                            phase = PaperPhase.LONG
                            lastBook?.let { book -> placeSell(book, held, now)?.let { events += it } }
                        }
                    }
                }
            }
            PaperPhase.FLAT, PaperPhase.LONG -> Unit
        }
        return events
    }

    private fun consumeQueue(order: PaperOrder, tradeSize: Double): Double {
        var remaining = tradeSize
        if (order.queueAhead > 0.0) {
            val consumed = min(order.queueAhead, remaining)
            order.queueAhead -= consumed
            remaining -= consumed
        }
        return remaining
    }

    fun snapshot(book: MarketBook?, now: Long): PaperSnapshot {
        val bestBid = book?.bestBid
        val bestAsk = book?.bestAsk
        val entry = inventory?.entryPrice
        val shares = inventory?.shares ?: 0.0
        val unrealized = inventory?.let { inv -> bestBid?.let { (it - inv.entryPrice) * inv.shares } } ?: 0.0
        val quote = when (phase) {
            PaperPhase.BUY_PENDING -> buyOrder
            PaperPhase.SELL_PENDING -> sellOrder
            else -> null
        }
        return PaperSnapshot(now, market.conditionId, market.question, config.name, phase, bestBid, bestAsk,
            entry, shares, realizedPnl, unrealized, realizedPnl + unrealized, totalBuyNotional, cycles.size,
            quote?.price, quote?.let { max(0L, (now - it.placedAt) / 1000L) } ?: 0L)
    }

    private fun placeBuy(book: MarketBook, now: Long): PaperEvent? {
        if (cycles.size >= config.maxCycles) return null
        val bestBid = book.bestBid ?: return null
        val bestAsk = book.bestAsk ?: return null
        if (bestBid < config.minPrice || bestBid >= config.maxPrice || bestAsk <= bestBid) return null
        val improved = bestBid + tickSize * config.improveTicks
        val rawLimit = min(improved, bestAsk - tickSize).coerceAtLeast(tickSize)
        val limit = roundToTick(rawLimit)
        if (limit <= 0.0 || limit >= 1.0) return null
        val queueAhead = if (limit <= bestBid + 1e-9) book.bidSizeAt(bestBid) else 0.0
        val shares = config.orderNotional / limit
        val order = PaperOrder(PaperSide.BUY, limit, shares, now, queueAhead, queueAhead + shares * config.queueFactor)
        buyOrder = order
        phase = PaperPhase.BUY_PENDING
        quotesPlaced++
        return PaperEvent.QuotePlaced(now, market.conditionId, config.name, PaperSide.BUY, limit, shares, queueAhead, order.requiredVolume)
    }

    private fun placeSell(book: MarketBook, held: PaperInventory, now: Long): PaperEvent? {
        val bestBid = book.bestBid ?: return null
        val bestAsk = book.bestAsk ?: return null
        if (bestAsk <= bestBid) return null
        val target = held.entryPrice + config.exitSpreadCents / 100.0
        val rawLimit = when {
            config.exitImproveTicks > 0 -> bestAsk - tickSize * config.exitImproveTicks
            config.exitJoinAsk -> min(target, bestAsk)
            target > bestBid + 1e-9 -> target
            else -> bestAsk
        }
        val limit = roundToTick(rawLimit).coerceAtMost(0.999)
        if (limit <= bestBid + 1e-9 || limit <= held.entryPrice) return null
        val queueAhead = if (limit < bestAsk - 1e-9) 0.0 else book.askSizeAtOrBelow(limit)
        val order = PaperOrder(PaperSide.SELL, limit, held.shares, now, queueAhead, queueAhead + held.shares * config.queueFactor)
        sellOrder = order
        phase = PaperPhase.SELL_PENDING
        quotesPlaced++
        return PaperEvent.QuotePlaced(now, market.conditionId, config.name, PaperSide.SELL, limit, held.shares, queueAhead, order.requiredVolume)
    }

    private fun cancelOrder(order: PaperOrder, now: Long, reason: String): PaperEvent =
        PaperEvent.QuoteCanceled(now, market.conditionId, config.name, order.side, order.price, reason)

    private fun roundToTick(value: Double): Double {
        val tick = tickSize.takeIf { it > 0.0 } ?: 0.001
        return (kotlin.math.round(value / tick) * tick).coerceIn(tick, 0.999)
    }
}
sealed interface LiveFeedEvent {
    data class BookUpdated(val book: MarketBook, val raw: String? = null) : LiveFeedEvent
    data class PricesChanged(val changes: List<BookPriceChange>, val raw: String? = null) : LiveFeedEvent
    data class Trade(val trade: LiveTradePrint, val raw: String? = null) : LiveFeedEvent
    data class Unknown(val eventType: String, val raw: String) : LiveFeedEvent
}
