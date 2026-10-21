package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LiveTradePrint
import com.wrbug.polymarketbot.research.livepaper.MarketBook
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

data class EventMakerEnginePlacement(
    val marketId: String,
    val order: EventMakerLiveOrder,
    val queueAheadShares: Double = 0.0
)

data class EventMakerEngineCancel(
    val marketId: String,
    val orderId: String,
    val reason: String
)

data class EventMakerEngineReconcile(
    val marketId: String,
    val orderId: String,
    val events: List<String>,
    val status: String
)

data class EventMakerEngineRedemption(
    val marketId: String,
    val status: EventMakerRedemptionStatus,
    val transactionHash: String? = null
)

data class EventMakerEngineResult(
    val placed: List<EventMakerEnginePlacement> = emptyList(),
    val canceled: List<EventMakerEngineCancel> = emptyList(),
    val reconciled: List<EventMakerEngineReconcile> = emptyList(),
    val settlements: List<EventMakerSettlementRecord> = emptyList(),
    val redemptions: List<EventMakerEngineRedemption> = emptyList(),
    val errors: List<String> = emptyList(),
    val haltedReason: String? = null
) {
    fun merge(other: EventMakerEngineResult): EventMakerEngineResult = EventMakerEngineResult(
        placed = placed + other.placed,
        canceled = canceled + other.canceled,
        reconciled = reconciled + other.reconciled,
        settlements = settlements + other.settlements,
        redemptions = redemptions + other.redemptions,
        errors = errors + other.errors,
        haltedReason = other.haltedReason ?: haltedReason
    )

    companion object {
        fun halted(reason: String) = EventMakerEngineResult(errors = listOf(reason), haltedReason = reason)
    }
}

/**
 * Shared orchestration for Shadow and Live. It only talks to [EventMakerExecutionPort],
 * so the same quote, risk, cancel and reconciliation path is exercised in both modes.
 */
class EventMakerLiveEngine(
    private val config: EventMakerConfig,
    private val quotePlanner: EventMakerQuotePlanner,
    private val riskPolicy: EventMakerRiskPolicy,
    private val executionPort: EventMakerExecutionPort,
    val initialRealizedPnl: Double = 0.0
) {
    private val activeAccounts = ConcurrentHashMap<String, Long>()
    private val reconcileFailures = ConcurrentHashMap<String, Int>()
    private val placementBackoffUntil = ConcurrentHashMap<String, Long>()
    private val cancelBackoffUntil = ConcurrentHashMap<String, Long>()
    private val adverseFlowGuard = EventMakerAdverseFlowGuard(config)
    private val flowSuppressedMarkets = ConcurrentHashMap.newKeySet<String>()
    private val settlementFailures = ConcurrentHashMap<String, Int>()
    private val redemptionFailures = ConcurrentHashMap<String, Int>()
    private val redemptionBackoffUntil = ConcurrentHashMap<String, Long>()
    private val settlementCheckAfter = ConcurrentHashMap<String, Long>()

    @Volatile
    var haltedReason: String? = null
        private set

    /**
     * Cumulative PnL at the start of the current trading day. The risk policy's
     * `maxDailyLoss` is a DAILY budget, so the service advances this baseline when
     * the day rolls over; without it the limit would compare against the whole run
     * and a single early loss would lock the strategy out permanently.
     */
    @Volatile
    var dailyPnlBaseline: Double = 0.0

    /**
     * Re-anchor the daily loss budget at the start of a run. Restored cumulative
     * PnL must not be treated as a loss for the current day after a restart.
     */
    fun resetDailyPnlBaseline(states: List<EventMakerLiveState>) {
        dailyPnlBaseline = initialRealizedPnl + states.sumOf { it.realizedPnl }
    }

    fun registerRestoredOrders(states: List<EventMakerLiveState>, accountId: Long) {
        states.forEach { state ->
            state.activeOrder?.let { activeAccounts[it.orderId] = accountId }
        }
    }

    /** Feed every unique public print before the next quote tick. */
    fun onTrade(trade: LiveTradePrint) {
        adverseFlowGuard.record(trade)
    }

    fun flowGuardEnabled(): Boolean = adverseFlowGuard.enabled

    fun resetFlowGuard() = adverseFlowGuard.reset()

    suspend fun quote(
        states: List<EventMakerLiveState>,
        books: Map<String, MarketBook>,
        accountId: Long,
        now: Long = System.currentTimeMillis(),
        /**
         * Optional entry whitelist. Restored positions outside the current
         * discovery window must still be allowed to exit, but must not open a
         * fresh cycle after they become flat again.
         */
        eligibleMarketIds: Set<String>? = null
    ): EventMakerEngineResult {
        haltedReason?.let { return EventMakerEngineResult.halted(it) }
        var result = EventMakerEngineResult()
        for (state in states) {
            val entryEligible = eligibleMarketIds == null || state.market.conditionId in eligibleMarketIds
            if (!entryEligible) {
                val recoveredActive = state.activeOrder
                if (recoveredActive == null && state.inventoryShares <= BigDecimal.ZERO) continue
                if (recoveredActive != null && recoveredActive.side == EventMakerSide.BUY) {
                    result = result.merge(
                        cancelAndReconcile(state, accountId, now, "outside_entry_window", recoveredActive)
                    )
                    continue
                }
            }
            val active = state.activeOrder
            if (active != null) {
                val book = books[state.market.favoriteTokenId] ?: state.lastBook
                val tick = BigDecimal.valueOf(state.market.tickSize)
                val bookFresh = book != null && now - book.updatedAt <= config.staleBookSeconds * 1000L
                val adverseFlow = active.side == EventMakerSide.BUY &&
                    adverseFlowGuard.blocks(state.market.favoriteTokenId, now)
                if (adverseFlow) flowSuppressedMarkets.add(state.market.conditionId)
                val desired = if (bookFresh) {
                    when (active.side) {
                        EventMakerSide.BUY -> quotePlanner.planBuy(config, book!!, tick, now)
                        EventMakerSide.SELL -> quotePlanner.planSell(
                            config = config,
                            book = book!!,
                            tickSize = tick,
                            entryPrice = state.entryPrice,
                            heldShares = state.inventoryShares,
                            now = now
                        )
                    }
                } else {
                    null
                }
                val ageMs = now - active.createdAt
                val activeBookStale = book == null ||
                    now - book.updatedAt > config.activeOrderStaleBookSeconds * 1000L
                val priceChanged = bookFresh && (desired == null || desired.price.compareTo(active.price) != 0)
                val maxAgeReached = ageMs >= config.maxOrderAgeSeconds * 1000L
                val marketExpired = state.isExpired(now)
                val insideTailWindow = active.side == EventMakerSide.BUY && isInsideTailWindow(state, now)
                val shouldCancel = marketExpired ||
                    insideTailWindow ||
                    adverseFlow ||
                    activeBookStale ||
                    maxAgeReached ||
                    (ageMs >= config.requoteSeconds * 1000L && priceChanged)
                if (shouldCancel && now >= (cancelBackoffUntil[active.orderId] ?: 0L)) {
                    val reason = when {
                        marketExpired -> "market_expired"
                        insideTailWindow -> "tail_window"
                        adverseFlow -> "adverse_flow"
                        activeBookStale -> "stale_book"
                        maxAgeReached -> "max_age"
                        else -> "requote"
                    }
                    result = result.merge(
                        cancelAndReconcile(state, accountId, now, reason, active)
                    )
                    if (state.activeOrder == null) {
                        cancelBackoffUntil.remove(active.orderId)
                    } else {
                        cancelBackoffUntil[active.orderId] = now + ORDER_RETRY_BACKOFF_MS
                    }
                }
                continue
            }

            if (state.isExpired(now)) continue

            if (state.phase == EventMakerPhase.FLAT) {
                if (isInsideTailWindow(state, now)) continue
                if (adverseFlowGuard.blocks(state.market.favoriteTokenId, now)) {
                    if (flowSuppressedMarkets.add(state.market.conditionId)) {
                        result = result.merge(
                            EventMakerEngineResult(errors = listOf("flow_guard_blocked:${state.market.conditionId}"))
                        )
                    }
                    continue
                }
                flowSuppressedMarkets.remove(state.market.conditionId)
            }

            val intent = when (state.phase) {
                EventMakerPhase.FLAT -> state.planBuy(quotePlanner, now)
                EventMakerPhase.LONG -> state.planSell(quotePlanner, now)
                else -> null
            } ?: continue

            if (intent.side == EventMakerSide.BUY) {
                val snapshot = EventMakerRiskSnapshot(
                    totalInventoryNotional = states.sumOf { it.inventoryNotional() + it.pendingBuyNotional() },
                    openMarketCount = states.count { it.inventoryShares > BigDecimal.ZERO || it.activeOrder != null },
                    dailyPnl = initialRealizedPnl + states.sumOf { it.realizedPnl } +
                        states.sumOf { it.markToMarket(books[it.market.favoriteTokenId]) } - dailyPnlBaseline
                )
                val decision = riskPolicy.canOpenBuy(config, snapshot)
                if (!decision.allowed) {
                    result = result.merge(EventMakerEngineResult(errors = listOf("risk_blocked:${decision.reason}")))
                    continue
                }
            }

            if (now < (placementBackoffUntil[state.market.conditionId] ?: 0L)) continue

            executionPort.place(accountId, state.market, intent).fold(
                onSuccess = { order ->
                    placementBackoffUntil.remove(state.market.conditionId)
                    state.onOrderPlaced(order)
                    activeAccounts[order.orderId] = accountId
                    result = result.merge(
                        EventMakerEngineResult(
                            placed = listOf(EventMakerEnginePlacement(state.market.conditionId, order, intent.queueAheadShares))
                        )
                    )
                    val shapeMatches = order.side == intent.side &&
                        order.price.compareTo(intent.price) == 0 &&
                        order.originalShares.compareTo(intent.shares) == 0
                    val marketMatches =
                        (order.conditionId == null || order.conditionId.equals(state.market.conditionId, ignoreCase = true)) &&
                            (order.tokenId == null || order.tokenId.equals(state.market.favoriteTokenId, ignoreCase = true))
                    val statusKnown = EventMakerOrderStatuses.isKnown(order.status)
                    if (!shapeMatches || !marketMatches || !statusKnown) {
                        val reason = when {
                            !shapeMatches -> "execution_order_mismatch:${order.orderId}"
                            !marketMatches -> "execution_order_market_mismatch:${order.orderId}"
                            else -> "execution_order_status_unknown:${order.orderId}:${order.status}"
                        }
                        result = result.merge(
                            cancelAndReconcile(state, accountId, now, "execution_order_rejected", order)
                        ).merge(EventMakerEngineResult.halted(reason))
                        halt(reason)
                    }
                },
                onFailure = { error ->
                    val message = "place_failed:${error.message}"
                    if (error is EventMakerAmbiguousOrderException) {
                        halt("order_placement_unknown:${error.message}")
                        result = result.merge(EventMakerEngineResult.halted(haltedReason!!))
                    } else {
                        placementBackoffUntil[state.market.conditionId] = now + ORDER_RETRY_BACKOFF_MS
                        result = result.merge(EventMakerEngineResult(errors = listOf(message)))
                    }
                }
            )
        }
        return result
    }

    /**
     * Converts an expired LONG position into a SETTLE cycle once Gamma exposes a
     * terminal 0/1 outcome. This method never redeems: the service persists the
     * returned record first, then calls [redeemPendingRedemptions].
     */
    suspend fun settleResolved(
        states: List<EventMakerLiveState>,
        accountId: Long,
        now: Long = System.currentTimeMillis()
    ): EventMakerEngineResult {
        haltedReason?.let { return EventMakerEngineResult.halted(it) }
        var result = EventMakerEngineResult()
        for (state in states) {
            if (state.activeOrder != null || state.inventoryShares <= BigDecimal.ZERO ||
                state.pendingRedemption() != null
            ) {
                continue
            }
            val key = state.market.conditionId
            if (now < (settlementCheckAfter[key] ?: 0L)) continue
            settlementCheckAfter[key] = now + SETTLEMENT_CHECK_INTERVAL_MS
            val snapshot = executionPort.getSettlementSnapshot(accountId, state.market).fold(
                onSuccess = { it },
                onFailure = { error ->
                    val count = settlementFailures.merge(key, 1, Int::plus) ?: 1
                    if (count >= MAX_SETTLEMENT_FAILURES) {
                        val reason = "settlement_snapshot_unavailable:${error.message}"
                        halt(reason)
                        return EventMakerEngineResult.halted(reason)
                    }
                    return EventMakerEngineResult(errors = listOf("settlement_snapshot_failed:${error.message}"))
                }
            )
            settlementFailures.remove(key)
            if (!snapshot.resolved) continue
            val position = snapshot.position
            if (position != null && (position.shares <= BigDecimal.ZERO ||
                    position.shares.compareTo(state.inventoryShares) != 0)
            ) {
                val reason =
                    "settlement_position_size_mismatch:${state.market.conditionId}:${position.shares}!=${state.inventoryShares}"
                halt(reason)
                return EventMakerEngineResult.halted(reason)
            }
            val outcomeIndex = position?.outcomeIndex ?: state.market.favoriteIndex
            val negativeRisk = position?.negativeRisk ?: state.market.negRisk
            if (outcomeIndex != state.market.favoriteIndex) {
                val reason = "settlement_position_market_mismatch:${state.market.conditionId}"
                halt(reason)
                return EventMakerEngineResult.halted(reason)
            }
            val exitPrice = if (snapshot.resolutionOutcomeIndex == outcomeIndex) BigDecimal.ONE else BigDecimal.ZERO
            try {
                val settlement = state.recordSettlement(exitPrice, now, outcomeIndex, negativeRisk)
                settlementFailures.remove(key)
                settlementCheckAfter.remove(key)
                result = result.merge(EventMakerEngineResult(settlements = listOf(settlement)))
            } catch (error: Exception) {
                val reason = "settlement_booking_failed:${error.message}"
                halt(reason)
                return result.merge(EventMakerEngineResult.halted(reason))
            }
        }
        return result
    }

    /**
     * Redeems a booked settlement. A missing on-chain position is accepted only
     * because the payout was already persisted; this is the crash-recovery path.
     */
    suspend fun redeemPendingRedemptions(
        states: List<EventMakerLiveState>,
        accountId: Long,
        now: Long = System.currentTimeMillis()
    ): EventMakerEngineResult {
        haltedReason?.let { return EventMakerEngineResult.halted(it) }
        var result = EventMakerEngineResult()
        for (state in states) {
            val pending = state.pendingRedemption() ?: continue
            if (now < (redemptionBackoffUntil[pending.cycleExitOrderId] ?: 0L)) continue
            val position = executionPort.getSettlementPosition(accountId, pending).fold(
                onSuccess = { it },
                onFailure = { error ->
                    return redemptionFailure(
                        pending = pending,
                        message = "settlement_position_failed:${error.message}",
                        now = now
                    )
                }
            )
            redemptionFailures.remove(pending.cycleExitOrderId)
            if (position.shares < BigDecimal.ZERO) {
                val reason = "settlement_position_negative:${pending.marketId}"
                halt(reason)
                return result.merge(EventMakerEngineResult.halted(reason))
            }
            if (position.shares.compareTo(BigDecimal.ZERO) == 0) {
                val updated = state.markRedemption(
                    pending.cycleExitOrderId,
                    EventMakerRedemptionStatus.ALREADY_ABSENT,
                    transactionHash = null,
                    now = now
                )
                redemptionFailures.remove(pending.cycleExitOrderId)
                redemptionBackoffUntil.remove(pending.cycleExitOrderId)
                if (updated != null) {
                    result = result.merge(
                        EventMakerEngineResult(
                            redemptions = listOf(
                                EventMakerEngineRedemption(pending.marketId, updated.redemptionStatus)
                            )
                        )
                    )
                }
                continue
            }
            if (position.shares.compareTo(pending.shares) != 0 ||
                (position.outcomeIndex != null && position.outcomeIndex != pending.outcomeIndex) ||
                (position.negativeRisk != null && position.negativeRisk != pending.negativeRisk)
            ) {
                val reason = "settlement_redemption_position_mismatch:${pending.marketId}"
                halt(reason)
                return result.merge(EventMakerEngineResult.halted(reason))
            }
            if (!position.redeemable) continue
            executionPort.redeemSettlement(accountId, pending).fold(
                onSuccess = { transactionHash ->
                    if (transactionHash.isBlank()) {
                        return redemptionFailure(
                            pending = pending,
                            message = "settlement_redemption_hash_missing:${pending.marketId}",
                            now = now
                        )
                    }
                    val updated = state.markRedemption(
                        pending.cycleExitOrderId,
                        EventMakerRedemptionStatus.REDEEMED,
                        transactionHash = transactionHash,
                        now = now
                    )
                    redemptionFailures.remove(pending.cycleExitOrderId)
                    redemptionBackoffUntil.remove(pending.cycleExitOrderId)
                    if (updated != null) {
                        result = result.merge(
                            EventMakerEngineResult(
                                redemptions = listOf(
                                    EventMakerEngineRedemption(pending.marketId, updated.redemptionStatus, transactionHash)
                                )
                            )
                        )
                    }
                },
                onFailure = { error ->
                    return redemptionFailure(
                        pending = pending,
                        message = "settlement_redemption_failed:${error.message}",
                        now = now
                    )
                }
            )
        }
        return result
    }

    private fun redemptionFailure(
        pending: EventMakerSettlementRecord,
        message: String,
        now: Long
    ): EventMakerEngineResult {
        val failures = redemptionFailures.merge(pending.cycleExitOrderId, 1, Int::plus) ?: 1
        redemptionBackoffUntil[pending.cycleExitOrderId] = now + REDEMPTION_RETRY_BACKOFF_MS
        return if (failures >= MAX_SETTLEMENT_FAILURES) {
            val reason = "settlement_redemption_unresolved:${pending.marketId}:$message"
            halt(reason)
            EventMakerEngineResult.halted(reason)
        } else {
            EventMakerEngineResult(errors = listOf(message))
        }
    }
    suspend fun reconcile(
        states: List<EventMakerLiveState>,
        accountId: Long,
        now: Long = System.currentTimeMillis()
    ): EventMakerEngineResult {
        haltedReason?.let { return EventMakerEngineResult.halted(it) }
        var result = EventMakerEngineResult()
        for (state in states) {
            val active = state.activeOrder ?: continue
            executionPort.getOrder(accountId, active.orderId).fold(
                onSuccess = { snapshot ->
                    reconcileFailures.remove(active.orderId)
                    val validation = validateSnapshot(state, active, snapshot)
                    if (validation != null) {
                        halt(validation)
                        result = result.merge(EventMakerEngineResult.halted(validation))
                        return@fold
                    }
                    val reconciled = state.reconcile(snapshot, now)
                    result = result.merge(
                        EventMakerEngineResult(
                            reconciled = listOf(
                                EventMakerEngineReconcile(
                                    marketId = state.market.conditionId,
                                    orderId = active.orderId,
                                    events = reconciled.events,
                                    status = snapshot.status
                                )
                            )
                        )
                    )
                    if (state.activeOrder == null) {
                        activeAccounts.remove(active.orderId)
                        reconcileFailures.remove(active.orderId)
                    } else if (snapshot.matchedShares > BigDecimal.ZERO) {
                        result = result.merge(
                            cancelAndReconcile(state, accountId, now, "partial_fill", active)
                        )
                        if (state.activeOrder != null) {
                            val reason = "partial_cancel_failed:${active.orderId}"
                            halt(reason)
                            result = result.merge(EventMakerEngineResult.halted(reason))
                        }
                    }
                },
                onFailure = { error ->
                    if (error is EventMakerOrderNotFoundException) {
                        val reason = "order_state_unknown:${active.orderId}"
                        halt(reason)
                        result = result.merge(EventMakerEngineResult.halted(reason))
                    } else {
                        val failures = reconcileFailures.merge(active.orderId, 1, Int::plus) ?: 1
                        if (failures >= MAX_RECONCILE_FAILURES) {
                            val reason = "order_state_unknown_after_retries:${active.orderId}"
                            halt(reason)
                            result = result.merge(EventMakerEngineResult.halted(reason))
                        } else {
                            result = result.merge(
                                EventMakerEngineResult(errors = listOf("reconcile_failed:${active.orderId}:${error.message}"))
                            )
                        }
                    }
                }
            )
        }
        return result
    }

    /**
     * Cancel an order and always read the authoritative final snapshot. A fill can
     * race the cancel request; clearing local state without that snapshot would
     * silently lose inventory and PnL.
     */
    private suspend fun cancelAndReconcile(
        state: EventMakerLiveState,
        accountId: Long,
        now: Long,
        reason: String,
        expected: EventMakerLiveOrder
    ): EventMakerEngineResult {
        if (state.activeOrder?.orderId != expected.orderId) {
            return EventMakerEngineResult(
                errors = listOf("cancel_state_unconfirmed:${expected.orderId}:active_order_changed")
            )
        }
        val cancelResult = executionPort.cancel(accountId, expected.orderId)
        val snapshotResult = executionPort.getOrder(accountId, expected.orderId)
        val snapshot = snapshotResult.getOrNull()
        if (snapshot == null) {
            val cancelMessage = cancelResult.exceptionOrNull()?.message ?: "ok"
            val lookupMessage = snapshotResult.exceptionOrNull()?.message ?: "unknown"
            return EventMakerEngineResult(
                errors = listOf(
                    "cancel_state_unconfirmed:${expected.orderId}:cancel=$cancelMessage:lookup=$lookupMessage"
                )
            )
        }
        val validation = validateSnapshot(state, expected, snapshot)
        if (validation != null) {
            return EventMakerEngineResult(errors = listOf("cancel_reconcile_rejected:${expected.orderId}:$validation"))
        }
        val reconciled = try {
            state.reconcile(snapshot, now)
        } catch (e: Exception) {
            return EventMakerEngineResult(
                errors = listOf("cancel_reconcile_failed:${expected.orderId}:${e.message}")
            )
        }
        var result = EventMakerEngineResult()
        if (reconciled.events.isNotEmpty()) {
            result = result.merge(
                EventMakerEngineResult(
                    reconciled = listOf(
                        EventMakerEngineReconcile(
                            marketId = state.market.conditionId,
                            orderId = expected.orderId,
                            events = reconciled.events,
                            status = snapshot.status
                        )
                    )
                )
            )
        }
        if (state.activeOrder == null) {
            activeAccounts.remove(expected.orderId)
            reconcileFailures.remove(expected.orderId)
            if (cancelResult.isSuccess && !EventMakerOrderStatuses.isMatchedTerminal(snapshot.status)) {
                result = result.merge(
                    EventMakerEngineResult(
                        canceled = listOf(
                            EventMakerEngineCancel(state.market.conditionId, expected.orderId, reason)
                        )
                    )
                )
            }
        } else {
            val cancelMessage = cancelResult.exceptionOrNull()?.message ?: "ok"
            result = result.merge(
                EventMakerEngineResult(
                    errors = listOf(
                        "cancel_order_still_active:${expected.orderId}:cancel=$cancelMessage:status=${snapshot.status}"
                    )
                )
            )
        }
        return result
    }

    suspend fun cancelAll(states: List<EventMakerLiveState>, accountId: Long): EventMakerEngineResult {
        var result = EventMakerEngineResult()
        val now = System.currentTimeMillis()
        for (state in states) {
            val active = state.activeOrder ?: continue
            val cancelResult = executionPort.cancel(accountId, active.orderId)
            val snapshotResult = executionPort.getOrder(accountId, active.orderId)
            val snapshot = snapshotResult.getOrNull()
            if (snapshot == null) {
                val cancelMessage = cancelResult.exceptionOrNull()?.message ?: "ok"
                val snapshotMessage = snapshotResult.exceptionOrNull()?.message ?: "unknown"
                result = result.merge(
                    EventMakerEngineResult(
                        errors = listOf(
                            "stop_state_unconfirmed:${active.orderId}:cancel=$cancelMessage:lookup=$snapshotMessage"
                        )
                    )
                )
                continue
            }
            val validation = validateSnapshot(state, active, snapshot)
            if (validation != null) {
                result = result.merge(EventMakerEngineResult(errors = listOf("stop_state_unconfirmed:$validation")))
                continue
            }
            val reconciled = try {
                state.reconcile(snapshot, now)
            } catch (e: Exception) {
                result = result.merge(
                    EventMakerEngineResult(errors = listOf("stop_reconcile_failed:${active.orderId}:${e.message}"))
                )
                continue
            }
            if (reconciled.events.isNotEmpty()) {
                result = result.merge(
                    EventMakerEngineResult(
                        reconciled = listOf(
                            EventMakerEngineReconcile(
                                marketId = state.market.conditionId,
                                orderId = active.orderId,
                                events = reconciled.events,
                                status = snapshot.status
                            )
                        )
                    )
                )
            }
            if (state.activeOrder == null) {
                activeAccounts.remove(active.orderId)
                reconcileFailures.remove(active.orderId)
                if (cancelResult.isSuccess && !EventMakerOrderStatuses.isMatchedTerminal(snapshot.status)) {
                    result = result.merge(
                        EventMakerEngineResult(
                            canceled = listOf(
                                EventMakerEngineCancel(state.market.conditionId, active.orderId, "stop")
                            )
                        )
                    )
                }
            } else {
                val cancelMessage = cancelResult.exceptionOrNull()?.message ?: "ok"
                result = result.merge(
                    EventMakerEngineResult(
                        errors = listOf(
                            "stop_order_still_active:${active.orderId}:cancel=$cancelMessage:status=${snapshot.status}"
                        )
                    )
                )
            }
        }
        return result
    }

    fun activeOrderCount(): Int = activeAccounts.size

    private fun validateSnapshot(
        state: EventMakerLiveState,
        expected: EventMakerLiveOrder,
        actual: EventMakerLiveOrder
    ): String? {
        if (!actual.orderId.equals(expected.orderId, ignoreCase = true)) {
            return "order_state_unknown:order_id_mismatch"
        }
        if (actual.side != expected.side) return "order_state_unknown:side_mismatch"
        if (actual.price <= BigDecimal.ZERO || actual.price >= BigDecimal.ONE) {
            return "order_state_unknown:invalid_price"
        }
        if (actual.price.compareTo(expected.price) != 0) return "order_state_unknown:price_mismatch"
        if (actual.originalShares <= BigDecimal.ZERO) {
            return "order_state_unknown:invalid_original_size"
        }
        if (actual.originalShares.compareTo(expected.originalShares) != 0) {
            return "order_state_unknown:original_size_mismatch"
        }
        if (actual.matchedShares < BigDecimal.ZERO || actual.matchedShares > actual.originalShares) {
            return "order_state_unknown:invalid_matched_size"
        }
        if (!actual.conditionId.equals(state.market.conditionId, ignoreCase = true)) {
            return "order_state_unknown:condition_mismatch"
        }
        if (!actual.tokenId.equals(state.market.favoriteTokenId, ignoreCase = true)) {
            return "order_state_unknown:token_mismatch"
        }
        if (actual.makerAddress.isNullOrBlank()) return "order_state_unknown:maker_missing"
        val status = EventMakerOrderStatuses.normalize(actual.status)
        if (!EventMakerOrderStatuses.isKnown(status)) {
            return "order_state_unknown:status_${actual.status.ifBlank { "blank" }}"
        }
        if (EventMakerOrderStatuses.isMatchedTerminal(status) &&
            actual.matchedShares.compareTo(actual.originalShares) != 0
        ) {
            return "order_state_unknown:matched_status_size_mismatch"
        }
        return null
    }

    /**
     * Causal pre-trade tail rule: inside the configured last-N-minutes window a
     * BUY must not rest or be placed. Uses only the public market close timestamp;
     * SELL exits are never suppressed.
     */
    private fun isInsideTailWindow(state: EventMakerLiveState, now: Long): Boolean {
        if (config.tailWindowMinutes <= 0) return false
        val remainingSeconds = state.market.endTimestamp - now / 1_000L
        return remainingSeconds < config.tailWindowMinutes * 60L
    }

    private fun halt(reason: String) {
        haltedReason = reason
    }

    companion object {
        private const val MAX_RECONCILE_FAILURES = 3
        private const val MAX_SETTLEMENT_FAILURES = 3
        private const val REDEMPTION_RETRY_BACKOFF_MS = 30_000L
        private const val SETTLEMENT_CHECK_INTERVAL_MS = 30_000L
        private const val ORDER_RETRY_BACKOFF_MS = 5_000L
    }
}
