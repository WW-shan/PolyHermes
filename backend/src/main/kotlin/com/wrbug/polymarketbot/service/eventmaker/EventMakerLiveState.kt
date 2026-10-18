package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.MarketBook
import java.math.BigDecimal

/**
 * Live order/inventory state for one market. The CLOB order status is the authoritative fill source.
 */
class EventMakerLiveState(
    val market: LivePaperMarket,
    val config: EventMakerConfig
) {
    var phase: EventMakerPhase = EventMakerPhase.FLAT
        private set
    var inventoryShares: BigDecimal = BigDecimal.ZERO
        private set
    var entryPrice: BigDecimal = BigDecimal.ZERO
        private set
    var entryAt: Long = 0L
        private set
    var activeOrder: EventMakerLiveOrder? = null
        private set
    var realizedPnl: Double = 0.0
        private set
    var completedCycles: Int = 0
        private set
    var totalBuyNotional: Double = 0.0
        private set
    var lastBook: MarketBook? = null
        private set
    val fills: MutableList<EventMakerFillRecord> = mutableListOf()
    val cycles: MutableList<EventMakerCycleRecord> = mutableListOf()
    private var currentEntryOrderId: String? = null
    private var currentEntryShares: BigDecimal = BigDecimal.ZERO
    private var reconciledShares: BigDecimal = BigDecimal.ZERO

    fun onBook(book: MarketBook) {
        if (book.tokenId == market.favoriteTokenId) lastBook = book
    }

    fun planBuy(planner: EventMakerQuotePlanner, now: Long): EventMakerQuoteIntent? {
        if (phase != EventMakerPhase.FLAT || activeOrder != null || completedCycles >= config.maxCyclesPerMarket) return null
        val book = lastBook ?: return null
        return planner.planBuy(config, book, BigDecimal.valueOf(market.tickSize), now)
    }

    fun planSell(planner: EventMakerQuotePlanner, now: Long): EventMakerQuoteIntent? {
        if (phase != EventMakerPhase.LONG || activeOrder != null || inventoryShares <= BigDecimal.ZERO) return null
        val book = lastBook ?: return null
        return planner.planSell(
            config = config,
            book = book,
            tickSize = BigDecimal.valueOf(market.tickSize),
            entryPrice = entryPrice,
            heldShares = inventoryShares,
            now = now
        )
    }

    fun onOrderPlaced(order: EventMakerLiveOrder) {
        activeOrder = order
        reconciledShares = BigDecimal.ZERO
        phase = if (order.side == EventMakerSide.BUY) EventMakerPhase.BUY_PENDING else EventMakerPhase.SELL_PENDING
    }

    fun reconcile(order: EventMakerLiveOrder, now: Long): LiveReconcileResult {
        val active = activeOrder ?: return LiveReconcileResult(emptyList(), null)
        if (active.orderId != order.orderId) return LiveReconcileResult(emptyList(), null)
        val delta = (order.matchedShares - reconciledShares).max(BigDecimal.ZERO)
        val events = mutableListOf<String>()
        if (delta > BigDecimal.ZERO) {
            if (active.side == EventMakerSide.BUY) {
                if (inventoryShares <= BigDecimal.ZERO) {
                    entryAt = now
                    currentEntryOrderId = active.orderId
                    currentEntryShares = delta
                } else {
                    currentEntryShares += delta
                }
                inventoryShares += delta
                entryPrice = active.price
                totalBuyNotional += active.price.multiply(delta).toDouble()
                fills += EventMakerFillRecord(
                    marketId = market.conditionId,
                    orderId = active.orderId,
                    side = EventMakerSide.BUY,
                    price = active.price,
                    shares = delta,
                    timestamp = now
                )
                phase = EventMakerPhase.LONG
                events += "buy_fill"
            } else {
                val qty = delta.min(inventoryShares)
                if (qty > BigDecimal.ZERO) {
                    val pnl = active.price.subtract(entryPrice).multiply(qty).toDouble()
                    realizedPnl += pnl
                    fills += EventMakerFillRecord(
                        marketId = market.conditionId,
                        orderId = active.orderId,
                        side = EventMakerSide.SELL,
                        price = active.price,
                        shares = qty,
                        timestamp = now
                    )
                    inventoryShares -= qty
                    events += "sell_fill"
                    if (inventoryShares <= BigDecimal.ZERO) {
                        inventoryShares = BigDecimal.ZERO
                        completedCycles++
                        cycles += EventMakerCycleRecord(
                            marketId = market.conditionId,
                            entryOrderId = currentEntryOrderId ?: "unknown",
                            exitOrderId = active.orderId,
                            entryAt = entryAt,
                            exitAt = now,
                            entryPrice = entryPrice,
                            exitPrice = active.price,
                            shares = qty,
                            pnl = pnl,
                            holdingSeconds = (now - entryAt).coerceAtLeast(0L)
                        )
                        phase = EventMakerPhase.FLAT
                        entryPrice = BigDecimal.ZERO
                        entryAt = 0L
                        currentEntryOrderId = null
                        currentEntryShares = BigDecimal.ZERO
                        events += "cycle_complete"
                    } else {
                        phase = EventMakerPhase.LONG
                    }
                }
            }
        }
        reconciledShares = order.matchedShares.max(reconciledShares)
        if (isTerminal(order.status) || order.matchedShares >= order.originalShares) {
            activeOrder = null
            reconciledShares = BigDecimal.ZERO
            phase = if (inventoryShares > BigDecimal.ZERO) EventMakerPhase.LONG else EventMakerPhase.FLAT
        }
        return LiveReconcileResult(events, activeOrder?.status)
    }
    fun onOrderCanceled() {
        activeOrder = null
        phase = if (inventoryShares > BigDecimal.ZERO) EventMakerPhase.LONG else EventMakerPhase.FLAT
        reconciledShares = BigDecimal.ZERO
    }

    fun toPersisted(): EventMakerPersistedMarket = EventMakerPersistedMarket(
        conditionId = market.conditionId,
        phase = phase.name,
        inventoryShares = inventoryShares.toPlainString(),
        entryPrice = entryPrice.toPlainString(),
        entryAt = entryAt,
        realizedPnl = realizedPnl,
        completedCycles = completedCycles,
        activeOrderId = activeOrder?.orderId,
        activeOrderSide = activeOrder?.side?.name,
        activeOrderPrice = activeOrder?.price?.toPlainString(),
        activeOrderOriginalShares = activeOrder?.originalShares?.toPlainString(),
        activeOrderMatchedShares = activeOrder?.matchedShares?.toPlainString(),
        activeOrderStatus = activeOrder?.status,
        totalBuyNotional = totalBuyNotional,
        activeOrderCreatedAt = activeOrder?.createdAt ?: 0L,
        currentEntryOrderId = currentEntryOrderId,
        currentEntryShares = currentEntryShares.toPlainString(),
        fills = fills.toList(),
        cycles = cycles.toList()
    )

    fun restore(persisted: EventMakerPersistedMarket) {
        if (persisted.conditionId != market.conditionId) return
        inventoryShares = persisted.inventoryShares.toBigDecimalOrNull()
            ?.takeIf { it >= BigDecimal.ZERO }
            ?: BigDecimal.ZERO
        entryPrice = persisted.entryPrice.toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO && it < BigDecimal.ONE }
            ?: BigDecimal.ZERO
        entryAt = persisted.entryAt.coerceAtLeast(0L)
        realizedPnl = persisted.realizedPnl.takeIf { it.isFinite() } ?: 0.0
        completedCycles = persisted.completedCycles.coerceAtLeast(0)
        totalBuyNotional = persisted.totalBuyNotional.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        currentEntryOrderId = persisted.currentEntryOrderId
        currentEntryShares = persisted.currentEntryShares?.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO } ?: BigDecimal.ZERO
        fills.clear()
        fills.addAll(persisted.fills.orEmpty())
        cycles.clear()
        cycles.addAll(persisted.cycles.orEmpty())

        val orderId = persisted.activeOrderId?.takeIf { it.isNotBlank() }
        val side = persisted.activeOrderSide?.let { runCatching { EventMakerSide.valueOf(it) }.getOrNull() }
        val price = persisted.activeOrderPrice?.toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO && it < BigDecimal.ONE }
        val original = persisted.activeOrderOriginalShares?.toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO }
        val matched = persisted.activeOrderMatchedShares?.toBigDecimalOrNull()
            ?.takeIf { it >= BigDecimal.ZERO }
        val validOrder = orderId != null && side != null && price != null && original != null &&
            matched != null && matched <= original
        activeOrder = if (validOrder) {
            EventMakerLiveOrder(
                orderId = orderId!!,
                side = side!!,
                price = price!!,
                originalShares = original!!,
                matchedShares = matched!!,
                status = persisted.activeOrderStatus?.takeIf { it.isNotBlank() } ?: "UNKNOWN",
                createdAt = persisted.activeOrderCreatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        } else {
            null
        }
        reconciledShares = activeOrder?.matchedShares ?: BigDecimal.ZERO
        phase = when {
            inventoryShares > BigDecimal.ZERO -> EventMakerPhase.LONG
            else -> EventMakerPhase.FLAT
        }
        if (activeOrder != null) {
            phase = if (activeOrder!!.side == EventMakerSide.BUY) EventMakerPhase.BUY_PENDING else EventMakerPhase.SELL_PENDING
        }
    }
    fun inventoryNotional(): Double = entryPrice.multiply(inventoryShares).toDouble()

    fun markToMarket(book: MarketBook?): Double {
        if (inventoryShares <= BigDecimal.ZERO) return 0.0
        val bid = book?.bestBid ?: return 0.0
        return BigDecimal.valueOf(bid).subtract(entryPrice).multiply(inventoryShares).toDouble()
    }

    private fun isTerminal(status: String): Boolean {
        val normalized = status.uppercase()
        return normalized in setOf("FILLED", "CANCELLED", "CANCELED", "EXPIRED", "REJECTED")
    }
}

data class LiveReconcileResult(
    val events: List<String>,
    val orderStatus: String?
)