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
    val legOffsetCents: Double get() = config.entryOffsetCents

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
    val settlements: MutableList<EventMakerSettlementRecord> = mutableListOf()
    private var currentEntryOrderId: String? = null
    private var currentEntryShares: BigDecimal = BigDecimal.ZERO
    private var currentCycleSoldShares: BigDecimal = BigDecimal.ZERO
    private var currentCycleRealizedPnl: Double = 0.0
    private var reconciledShares: BigDecimal = BigDecimal.ZERO

    fun onBook(book: MarketBook) {
        if (book.tokenId == market.favoriteTokenId) lastBook = book
    }

    fun planBuy(planner: EventMakerQuotePlanner, now: Long): EventMakerQuoteIntent? {
        if (settlements.isNotEmpty() || isExpired(now) || phase != EventMakerPhase.FLAT || activeOrder != null ||
            completedCycles >= config.maxCyclesPerMarket
        ) return null
        val book = lastBook ?: return null
        return planner.planBuy(config, book, BigDecimal.valueOf(market.tickSize), now)
    }

    fun planSell(planner: EventMakerQuotePlanner, now: Long): EventMakerQuoteIntent? {
        if (isExpired(now) || phase != EventMakerPhase.LONG || activeOrder != null ||
            inventoryShares <= BigDecimal.ZERO
        ) return null
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
        if (EventMakerOrderStatuses.isMatchedTerminal(order.status) &&
            order.matchedShares.compareTo(order.originalShares) != 0
        ) {
            throw IllegalStateException("matched_status_size_mismatch:${order.orderId}")
        }
        val delta = (order.matchedShares - reconciledShares).max(BigDecimal.ZERO)
        val events = mutableListOf<String>()
        if (delta > BigDecimal.ZERO) {
            if (active.side == EventMakerSide.BUY) {
                if (inventoryShares <= BigDecimal.ZERO) {
                    entryAt = now
                    currentEntryOrderId = active.orderId
                    currentEntryShares = delta
                    currentCycleSoldShares = BigDecimal.ZERO
                    currentCycleRealizedPnl = 0.0
                } else {
                    if (currentEntryOrderId != active.orderId) {
                        throw IllegalStateException("buy_fill_without_current_entry:${active.orderId}")
                    }
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
                if (delta > inventoryShares) {
                    throw IllegalStateException(
                        "sell_fill_exceeds_inventory:${market.conditionId}:${active.orderId}:$delta>$inventoryShares"
                    )
                }
                val pnl = active.price.subtract(entryPrice).multiply(delta).toDouble()
                realizedPnl += pnl
                currentCycleRealizedPnl += pnl
                currentCycleSoldShares += delta
                fills += EventMakerFillRecord(
                    marketId = market.conditionId,
                    orderId = active.orderId,
                    side = EventMakerSide.SELL,
                    price = active.price,
                    shares = delta,
                    timestamp = now
                )
                inventoryShares -= delta
                events += "sell_fill"
                if (inventoryShares <= BigDecimal.ZERO) {
                    if (currentCycleSoldShares.compareTo(currentEntryShares) != 0) {
                        throw IllegalStateException(
                            "cycle_share_mismatch:${market.conditionId}:$currentCycleSoldShares!=$currentEntryShares"
                        )
                    }
                    inventoryShares = BigDecimal.ZERO
                    completedCycles++
                    cycles += EventMakerCycleRecord(
                        marketId = market.conditionId,
                        entryOrderId = currentEntryOrderId ?: throw IllegalStateException("cycle_entry_order_missing"),
                        exitOrderId = active.orderId,
                        entryAt = entryAt,
                        exitAt = now,
                        entryPrice = entryPrice,
                        exitPrice = active.price,
                        shares = currentEntryShares,
                        pnl = currentCycleRealizedPnl,
                        holdingSeconds = (now - entryAt).coerceAtLeast(0L)
                    )
                    phase = EventMakerPhase.FLAT
                    entryPrice = BigDecimal.ZERO
                    entryAt = 0L
                    currentEntryOrderId = null
                    currentEntryShares = BigDecimal.ZERO
                    currentCycleSoldShares = BigDecimal.ZERO
                    currentCycleRealizedPnl = 0.0
                    events += "cycle_complete"
                } else {
                    phase = EventMakerPhase.LONG
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

    fun isExpired(now: Long): Boolean = now / 1000L >= market.endTimestamp

    fun pendingRedemption(): EventMakerSettlementRecord? =
        settlements.lastOrNull { it.redemptionStatus == EventMakerRedemptionStatus.PENDING }

    /**
     * Books the market payout exactly once. The on-chain redemption is deliberately
     * separate: callers persist this record before attempting any outside transfer.
     */
    fun recordSettlement(
        exitPrice: BigDecimal,
        now: Long,
        outcomeIndex: Int,
        negativeRisk: Boolean
    ): EventMakerSettlementRecord {
        if (activeOrder != null || phase != EventMakerPhase.LONG || inventoryShares <= BigDecimal.ZERO) {
            throw IllegalStateException("settlement_without_inventory:${market.conditionId}")
        }
        if (exitPrice.compareTo(BigDecimal.ZERO) != 0 && exitPrice.compareTo(BigDecimal.ONE) != 0) {
            throw IllegalStateException("settlement_payoff_invalid:${market.conditionId}:$exitPrice")
        }
        if (outcomeIndex !in market.tokenIds.indices || outcomeIndex != market.favoriteIndex) {
            throw IllegalStateException("settlement_outcome_mismatch:${market.conditionId}:$outcomeIndex")
        }
        val entryOrderId = currentEntryOrderId
            ?: throw IllegalStateException("settlement_entry_order_missing:${market.conditionId}")
        val settledShares = inventoryShares
        if (currentCycleSoldShares.add(settledShares).compareTo(currentEntryShares) != 0) {
            throw IllegalStateException(
                "settlement_cycle_share_mismatch:${market.conditionId}:" +
                    "$currentCycleSoldShares+$settledShares!=$currentEntryShares"
            )
        }
        val settlementPnl = exitPrice.subtract(entryPrice).multiply(settledShares).toDouble()
        val cyclePnl = currentCycleRealizedPnl + settlementPnl
        val cycleExitOrderId = "settlement:${market.conditionId}:${entryOrderId}:$now"
        val settlement = EventMakerSettlementRecord(
            marketId = market.conditionId,
            favoriteTokenId = market.favoriteTokenId,
            entryOrderId = entryOrderId,
            cycleExitOrderId = cycleExitOrderId,
            settledAt = now,
            entryAt = entryAt,
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            shares = settledShares,
            pnl = settlementPnl,
            outcomeIndex = outcomeIndex,
            negativeRisk = negativeRisk,
            redemptionStatus = EventMakerRedemptionStatus.PENDING
        )
        realizedPnl += settlementPnl
        completedCycles++
        cycles += EventMakerCycleRecord(
            marketId = market.conditionId,
            entryOrderId = entryOrderId,
            exitOrderId = cycleExitOrderId,
            entryAt = entryAt,
            exitAt = now,
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            shares = currentEntryShares,
            pnl = cyclePnl,
            holdingSeconds = (now - entryAt).coerceAtLeast(0L),
            exitType = "SETTLE"
        )
        settlements += settlement
        inventoryShares = BigDecimal.ZERO
        entryPrice = BigDecimal.ZERO
        entryAt = 0L
        currentEntryOrderId = null
        currentEntryShares = BigDecimal.ZERO
        currentCycleSoldShares = BigDecimal.ZERO
        currentCycleRealizedPnl = 0.0
        phase = EventMakerPhase.FLAT
        return settlement
    }

    fun markRedemption(
        cycleExitOrderId: String,
        status: EventMakerRedemptionStatus,
        transactionHash: String?,
        now: Long
    ): EventMakerSettlementRecord? {
        if (status == EventMakerRedemptionStatus.PENDING) {
            throw IllegalArgumentException("redemption_status_must_be_terminal")
        }
        val index = settlements.indexOfFirst { it.cycleExitOrderId == cycleExitOrderId }
        if (index < 0) throw IllegalStateException("settlement_record_missing:$cycleExitOrderId")
        val current = settlements[index]
        if (current.redemptionStatus != EventMakerRedemptionStatus.PENDING) return current
        val updated = current.copy(
            redemptionStatus = status,
            transactionHash = transactionHash?.takeIf { it.isNotBlank() },
            redemptionUpdatedAt = now
        )
        settlements[index] = updated
        return updated
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
        cycles = cycles.toList(),
        favoriteTokenId = market.favoriteTokenId,
        settlements = settlements.toList(),
        favoriteIndex = market.favoriteIndex,
        negativeRisk = market.negRisk,
        endTimestamp = market.endTimestamp,
        tickSize = market.tickSize,
        legOffsetCents = legOffsetCents
    )

    fun restore(persisted: EventMakerPersistedMarket) {
        if (persisted.conditionId != market.conditionId) {
            throw IllegalStateException("persisted_condition_mismatch:${persisted.conditionId}")
        }
        if (persisted.favoriteTokenId != market.favoriteTokenId) {
            throw IllegalStateException(
                "persisted_favorite_token_mismatch:${market.conditionId}:${persisted.favoriteTokenId}!=" +
                    market.favoriteTokenId
            )
        }
        if (persisted.legOffsetCents != null &&
            kotlin.math.abs(persisted.legOffsetCents - legOffsetCents) > 1e-9
        ) {
            throw IllegalStateException(
                "persisted_ladder_offset_mismatch:${market.conditionId}:${persisted.legOffsetCents}!=$legOffsetCents"
            )
        }
        val persistedInventory = persisted.inventoryShares.toBigDecimalOrNull()
            ?.takeIf { it >= BigDecimal.ZERO }
            ?: throw IllegalStateException("persisted_inventory_invalid:${market.conditionId}")
        val persistedEntryPrice = persisted.entryPrice.toBigDecimalOrNull()
            ?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal.ONE }
            ?: throw IllegalStateException("persisted_entry_price_invalid:${market.conditionId}")
        val persistedRealized = persisted.realizedPnl.takeIf { it.isFinite() }
            ?: throw IllegalStateException("persisted_realized_pnl_invalid:${market.conditionId}")
        val persistedFills = persisted.fills.orEmpty()
        val persistedSettlements = persisted.settlements.orEmpty()
        val replay = replayLedger(persistedFills, persistedSettlements)
        if (replay.inventoryShares.compareTo(persistedInventory) != 0) {
            throw IllegalStateException(
                "persisted_inventory_mismatch:${market.conditionId}:${replay.inventoryShares}!=$persistedInventory"
            )
        }
        if (!closeEnough(replay.realizedPnl, persistedRealized)) {
            throw IllegalStateException(
                "persisted_realized_pnl_mismatch:${market.conditionId}:${replay.realizedPnl}!=$persistedRealized"
            )
        }
        if (replay.completedCycles != persisted.completedCycles) {
            throw IllegalStateException(
                "persisted_completed_cycles_mismatch:${market.conditionId}:${replay.completedCycles}!=$persisted.completedCycles"
            )
        }
        val persistedCycles = persisted.cycles.orEmpty()
        if (persistedCycles.size != replay.completedCycles) {
            throw IllegalStateException(
                "persisted_cycle_records_mismatch:${market.conditionId}:${persistedCycles.size}!=$replay.completedCycles"
            )
        }
        persistedCycles.zip(replay.cycles).forEach { (saved, replayed) -> validateCycle(market.conditionId, saved, replayed) }
        val expectedSettlements = replay.cycles.count { it.exitType == "SETTLE" }
        if (persistedSettlements.size != expectedSettlements) {
            throw IllegalStateException(
                "persisted_settlement_records_mismatch:${market.conditionId}:${persistedSettlements.size}!=$expectedSettlements"
            )
        }
        if (persistedInventory > BigDecimal.ZERO) {
            if (persistedEntryPrice.compareTo(replay.entryPrice) != 0) {
                throw IllegalStateException(
                    "persisted_entry_price_mismatch:${market.conditionId}:${replay.entryPrice}!=$persistedEntryPrice"
                )
            }
            if (persisted.currentEntryOrderId != replay.currentEntryOrderId) {
                throw IllegalStateException(
                    "persisted_entry_order_mismatch:${market.conditionId}:${replay.currentEntryOrderId}!=$persisted.currentEntryOrderId"
                )
            }
            val persistedCurrentEntryShares = persisted.currentEntryShares?.toBigDecimalOrNull()
                ?: throw IllegalStateException("persisted_entry_shares_missing:${market.conditionId}")
            if (persistedCurrentEntryShares.compareTo(replay.currentEntryShares) != 0) {
                throw IllegalStateException(
                    "persisted_entry_shares_mismatch:${market.conditionId}:${replay.currentEntryShares}!=$persistedCurrentEntryShares"
                )
            }
        } else if (persistedEntryPrice.compareTo(BigDecimal.ZERO) != 0) {
            throw IllegalStateException("persisted_flat_has_entry_price:${market.conditionId}")
        }

        inventoryShares = replay.inventoryShares
        entryPrice = replay.entryPrice
        entryAt = replay.entryAt
        realizedPnl = replay.realizedPnl
        completedCycles = replay.completedCycles
        totalBuyNotional = persisted.totalBuyNotional.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        currentEntryOrderId = replay.currentEntryOrderId
        currentEntryShares = replay.currentEntryShares
        currentCycleSoldShares = replay.currentCycleSoldShares
        currentCycleRealizedPnl = replay.currentCycleRealizedPnl
        fills.clear()
        fills.addAll(persistedFills)
        cycles.clear()
        cycles.addAll(replay.cycles)
        settlements.clear()
        settlements.addAll(persistedSettlements)

        activeOrder = restoreActiveOrder(persisted)
        reconciledShares = activeOrder?.matchedShares ?: BigDecimal.ZERO
        phase = when {
            activeOrder?.side == EventMakerSide.BUY -> EventMakerPhase.BUY_PENDING
            activeOrder?.side == EventMakerSide.SELL -> EventMakerPhase.SELL_PENDING
            inventoryShares > BigDecimal.ZERO -> EventMakerPhase.LONG
            else -> EventMakerPhase.FLAT
        }
    }

    fun inventoryNotional(): Double = entryPrice.multiply(inventoryShares).toDouble()

    fun pendingBuyNotional(): Double {
        val order = activeOrder ?: return 0.0
        if (order.side != EventMakerSide.BUY) return 0.0
        return order.price.multiply((order.originalShares - order.matchedShares).max(BigDecimal.ZERO)).toDouble()
    }

    fun markToMarket(book: MarketBook?): Double {
        if (inventoryShares <= BigDecimal.ZERO) return 0.0
        // With no visible bid the position has no demonstrable exit, so risk must
        // use the worst-case mark (zero) instead of silently treating loss as zero.
        val bid = book?.bestBid ?: 0.0
        return BigDecimal.valueOf(bid).subtract(entryPrice).multiply(inventoryShares).toDouble()
    }

    private fun replayLedger(
        sourceFills: List<EventMakerFillRecord>,
        sourceSettlements: List<EventMakerSettlementRecord>
    ): ReplayLedger {
        var replayInventory = BigDecimal.ZERO
        var replayEntryPrice = BigDecimal.ZERO
        var replayEntryAt = 0L
        var replayRealized = 0.0
        var replayCompletedCycles = 0
        var replayCurrentEntryOrderId: String? = null
        var replayCurrentEntryShares = BigDecimal.ZERO
        var replayCurrentCycleSoldShares = BigDecimal.ZERO
        var replayCurrentCyclePnl = 0.0
        val replayCycles = mutableListOf<EventMakerCycleRecord>()
        var lastTimestamp = Long.MIN_VALUE
        sourceFills.forEach { fill ->
            if (fill.marketId != market.conditionId) {
                throw IllegalStateException("fill_market_mismatch:${market.conditionId}:${fill.marketId}")
            }
            if (fill.orderId.isBlank() || fill.shares <= BigDecimal.ZERO ||
                fill.price <= BigDecimal.ZERO || fill.price >= BigDecimal.ONE
            ) {
                throw IllegalStateException("fill_invalid:${market.conditionId}:${fill.orderId}")
            }
            if (fill.timestamp < lastTimestamp) {
                throw IllegalStateException("fill_timestamp_out_of_order:${market.conditionId}")
            }
            lastTimestamp = fill.timestamp
            when (fill.side) {
                EventMakerSide.BUY -> {
                    if (replayInventory <= BigDecimal.ZERO) {
                        replayEntryPrice = fill.price
                        replayEntryAt = fill.timestamp
                        replayCurrentEntryOrderId = fill.orderId
                        replayCurrentEntryShares = fill.shares
                        replayCurrentCycleSoldShares = BigDecimal.ZERO
                        replayCurrentCyclePnl = 0.0
                    } else {
                        if (replayCurrentEntryOrderId != fill.orderId || replayEntryPrice.compareTo(fill.price) != 0) {
                            throw IllegalStateException("buy_fill_chain_invalid:${market.conditionId}:${fill.orderId}")
                        }
                        replayCurrentEntryShares += fill.shares
                    }
                    replayInventory += fill.shares
                }
                EventMakerSide.SELL -> {
                    if (replayInventory <= BigDecimal.ZERO || fill.shares > replayInventory) {
                        throw IllegalStateException("sell_fill_without_inventory:${market.conditionId}:${fill.orderId}")
                    }
                    val pnl = fill.price.subtract(replayEntryPrice).multiply(fill.shares).toDouble()
                    replayRealized += pnl
                    replayCurrentCyclePnl += pnl
                    replayCurrentCycleSoldShares += fill.shares
                    replayInventory -= fill.shares
                    if (replayInventory <= BigDecimal.ZERO) {
                        if (replayCurrentCycleSoldShares.compareTo(replayCurrentEntryShares) != 0) {
                            throw IllegalStateException("fill_cycle_share_mismatch:${market.conditionId}:${fill.orderId}")
                        }
                        replayCycles += EventMakerCycleRecord(
                            marketId = market.conditionId,
                            entryOrderId = replayCurrentEntryOrderId
                                ?: throw IllegalStateException("fill_cycle_entry_missing:${market.conditionId}"),
                            exitOrderId = fill.orderId,
                            entryAt = replayEntryAt,
                            exitAt = fill.timestamp,
                            entryPrice = replayEntryPrice,
                            exitPrice = fill.price,
                            shares = replayCurrentEntryShares,
                            pnl = replayCurrentCyclePnl,
                            holdingSeconds = (fill.timestamp - replayEntryAt).coerceAtLeast(0L)
                        )
                        replayCompletedCycles++
                        replayInventory = BigDecimal.ZERO
                        replayEntryPrice = BigDecimal.ZERO
                        replayEntryAt = 0L
                        replayCurrentEntryOrderId = null
                        replayCurrentEntryShares = BigDecimal.ZERO
                        replayCurrentCycleSoldShares = BigDecimal.ZERO
                        replayCurrentCyclePnl = 0.0
                    }
                }
            }
        }
        sourceSettlements.forEach { settlement ->
            if (settlement.marketId != market.conditionId ||
                settlement.favoriteTokenId != market.favoriteTokenId ||
                settlement.entryOrderId.isBlank() || settlement.cycleExitOrderId.isBlank() ||
                settlement.shares <= BigDecimal.ZERO || settlement.entryAt <= 0L || settlement.settledAt < lastTimestamp
            ) {
                throw IllegalStateException("settlement_record_invalid:${market.conditionId}")
            }
            if (settlement.exitPrice.compareTo(BigDecimal.ZERO) != 0 &&
                settlement.exitPrice.compareTo(BigDecimal.ONE) != 0
            ) {
                throw IllegalStateException("settlement_exit_price_invalid:${market.conditionId}")
            }
            if (settlement.outcomeIndex != market.favoriteIndex) {
                throw IllegalStateException("settlement_market_mismatch:${market.conditionId}")
            }
            if (replayInventory <= BigDecimal.ZERO ||
                replayCurrentEntryOrderId != settlement.entryOrderId ||
                replayEntryPrice.compareTo(settlement.entryPrice) != 0 ||
                settlement.entryAt != replayEntryAt ||
                settlement.shares.compareTo(replayInventory) != 0
            ) {
                throw IllegalStateException("settlement_ledger_mismatch:${market.conditionId}:${settlement.entryOrderId}")
            }
            if (replayCurrentEntryShares <= BigDecimal.ZERO ||
                replayCurrentCycleSoldShares.add(settlement.shares).compareTo(replayCurrentEntryShares) != 0
            ) {
                throw IllegalStateException("settlement_cycle_share_mismatch:${market.conditionId}:${settlement.entryOrderId}")
            }
            val settlementPnl = settlement.exitPrice.subtract(replayEntryPrice)
                .multiply(settlement.shares).toDouble()
            if (!closeEnough(settlement.pnl, settlementPnl)) {
                throw IllegalStateException("settlement_pnl_mismatch:${market.conditionId}:${settlement.entryOrderId}")
            }
            val cyclePnl = replayCurrentCyclePnl + settlementPnl
            replayCycles += EventMakerCycleRecord(
                marketId = market.conditionId,
                entryOrderId = settlement.entryOrderId,
                exitOrderId = settlement.cycleExitOrderId,
                entryAt = replayEntryAt,
                exitAt = settlement.settledAt,
                entryPrice = replayEntryPrice,
                exitPrice = settlement.exitPrice,
                shares = replayCurrentEntryShares,
                pnl = cyclePnl,
                holdingSeconds = (settlement.settledAt - replayEntryAt).coerceAtLeast(0L),
                exitType = "SETTLE"
            )
            replayRealized += settlementPnl
            replayCompletedCycles++
            lastTimestamp = settlement.settledAt
            replayInventory = BigDecimal.ZERO
            replayEntryPrice = BigDecimal.ZERO
            replayEntryAt = 0L
            replayCurrentEntryOrderId = null
            replayCurrentEntryShares = BigDecimal.ZERO
            replayCurrentCycleSoldShares = BigDecimal.ZERO
            replayCurrentCyclePnl = 0.0
        }

        return ReplayLedger(
            inventoryShares = replayInventory,
            entryPrice = replayEntryPrice,
            entryAt = replayEntryAt,
            realizedPnl = replayRealized,
            completedCycles = replayCompletedCycles,
            currentEntryOrderId = replayCurrentEntryOrderId,
            currentEntryShares = replayCurrentEntryShares,
            currentCycleSoldShares = replayCurrentCycleSoldShares,
            currentCycleRealizedPnl = replayCurrentCyclePnl,
            cycles = replayCycles
        )
    }

    private fun restoreActiveOrder(persisted: EventMakerPersistedMarket): EventMakerLiveOrder? {
        val orderId = persisted.activeOrderId?.takeIf { it.isNotBlank() } ?: return null
        val side = persisted.activeOrderSide?.let { runCatching { EventMakerSide.valueOf(it) }.getOrNull() }
            ?: throw IllegalStateException("persisted_active_order_side_invalid:${market.conditionId}")
        val price = persisted.activeOrderPrice?.toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO && it < BigDecimal.ONE }
            ?: throw IllegalStateException("persisted_active_order_price_invalid:${market.conditionId}")
        val original = persisted.activeOrderOriginalShares?.toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO }
            ?: throw IllegalStateException("persisted_active_order_size_invalid:${market.conditionId}")
        val matched = persisted.activeOrderMatchedShares?.toBigDecimalOrNull()
            ?.takeIf { it >= BigDecimal.ZERO && it <= original }
            ?: throw IllegalStateException("persisted_active_order_matched_invalid:${market.conditionId}")
        val status = persisted.activeOrderStatus?.takeIf { it.isNotBlank() && EventMakerOrderStatuses.isKnown(it) }
            ?: throw IllegalStateException("persisted_active_order_status_invalid:${market.conditionId}")
        return EventMakerLiveOrder(
            orderId = orderId,
            side = side,
            price = price,
            originalShares = original,
            matchedShares = matched,
            status = status,
            createdAt = persisted.activeOrderCreatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
            tokenId = market.favoriteTokenId,
            conditionId = market.conditionId
        )
    }

    private fun validateCycle(
        conditionId: String,
        saved: EventMakerCycleRecord,
        replayed: EventMakerCycleRecord
    ) {
        val mismatch = saved.marketId != replayed.marketId ||
            saved.entryOrderId != replayed.entryOrderId ||
            saved.exitOrderId != replayed.exitOrderId ||
            saved.entryAt != replayed.entryAt ||
            saved.exitAt != replayed.exitAt ||
            saved.entryPrice.compareTo(replayed.entryPrice) != 0 ||
            saved.exitPrice.compareTo(replayed.exitPrice) != 0 ||
            saved.shares.compareTo(replayed.shares) != 0 ||
            !closeEnough(saved.pnl, replayed.pnl) ||
            saved.holdingSeconds != replayed.holdingSeconds ||
            saved.exitType != replayed.exitType
        if (mismatch) throw IllegalStateException("persisted_cycle_mismatch:$conditionId:${saved.entryOrderId}")
    }

    private fun closeEnough(left: Double, right: Double): Boolean = kotlin.math.abs(left - right) <= 1e-7

    private fun isTerminal(status: String): Boolean =
        EventMakerOrderStatuses.isTerminal(status)

    private data class ReplayLedger(
        val inventoryShares: BigDecimal,
        val entryPrice: BigDecimal,
        val entryAt: Long,
        val realizedPnl: Double,
        val completedCycles: Int,
        val currentEntryOrderId: String?,
        val currentEntryShares: BigDecimal,
        val currentCycleSoldShares: BigDecimal,
        val currentCycleRealizedPnl: Double,
        val cycles: List<EventMakerCycleRecord>
    )
}

data class LiveReconcileResult(
    val events: List<String>,
    val orderStatus: String?
)
