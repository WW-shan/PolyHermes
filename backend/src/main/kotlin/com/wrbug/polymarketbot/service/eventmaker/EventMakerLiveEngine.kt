package com.wrbug.polymarketbot.service.eventmaker

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

data class EventMakerEngineResult(
    val placed: List<EventMakerEnginePlacement> = emptyList(),
    val canceled: List<EventMakerEngineCancel> = emptyList(),
    val reconciled: List<EventMakerEngineReconcile> = emptyList(),
    val errors: List<String> = emptyList(),
    val haltedReason: String? = null
) {
    fun merge(other: EventMakerEngineResult): EventMakerEngineResult = EventMakerEngineResult(
        placed = placed + other.placed,
        canceled = canceled + other.canceled,
        reconciled = reconciled + other.reconciled,
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

    fun registerRestoredOrders(states: List<EventMakerLiveState>, accountId: Long) {
        states.forEach { state ->
            state.activeOrder?.let { activeAccounts[it.orderId] = accountId }
        }
    }

    suspend fun quote(
        states: List<EventMakerLiveState>,
        books: Map<String, MarketBook>,
        accountId: Long,
        now: Long = System.currentTimeMillis()
    ): EventMakerEngineResult {
        haltedReason?.let { return EventMakerEngineResult.halted(it) }
        var result = EventMakerEngineResult()
        for (state in states) {
            val active = state.activeOrder
            if (active != null) {
                val book = books[state.market.favoriteTokenId] ?: state.lastBook
                val tick = BigDecimal.valueOf(state.market.tickSize)
                val bookFresh = book != null && now - book.updatedAt <= config.staleBookSeconds * 1000L
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
                val shouldCancel = activeBookStale ||
                    maxAgeReached ||
                    (ageMs >= config.requoteSeconds * 1000L && priceChanged)
                if (shouldCancel) {
                    val reason = when {
                        activeBookStale -> "stale_book"
                        maxAgeReached -> "max_age"
                        else -> "requote"
                    }
                    executionPort.cancel(accountId, active.orderId).fold(
                        onSuccess = {
                            activeAccounts.remove(active.orderId)
                            state.onOrderCanceled()
                            result = result.merge(
                                EventMakerEngineResult(
                                    canceled = listOf(
                                        EventMakerEngineCancel(state.market.conditionId, active.orderId, reason)
                                    )
                                )
                            )
                        },
                        onFailure = { error ->
                            result = result.merge(EventMakerEngineResult(errors = listOf("cancel_failed:${error.message}")))
                        }
                    )
                }
                continue
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

            executionPort.place(accountId, state.market, intent).fold(
                onSuccess = { order ->
                    if (order.side != intent.side || order.price != intent.price ||
                        order.originalShares != intent.shares
                    ) {
                        state.onOrderPlaced(order)
                        val cancelOutcome = executionPort.cancel(accountId, order.orderId)
                        if (cancelOutcome.isSuccess) state.onOrderCanceled()
                        val reason = if (cancelOutcome.isSuccess) {
                            "execution_order_mismatch:${order.orderId}:canceled"
                        } else {
                            "execution_order_mismatch:${order.orderId}:cancel_failed:${cancelOutcome.exceptionOrNull()?.message}"
                        }
                        halt(reason)
                        result = result.merge(EventMakerEngineResult.halted(haltedReason!!))
                    } else {
                        state.onOrderPlaced(order)
                        activeAccounts[order.orderId] = accountId
                        result = result.merge(
                            EventMakerEngineResult(
                                placed = listOf(EventMakerEnginePlacement(state.market.conditionId, order, intent.queueAheadShares))
                            )
                        )
                    }
                },
                onFailure = { error ->
                    val message = "place_failed:${error.message}"
                    if (error is EventMakerAmbiguousOrderException) {
                        halt("order_placement_unknown:${error.message}")
                        result = result.merge(EventMakerEngineResult.halted(haltedReason!!))
                    } else {
                        result = result.merge(EventMakerEngineResult(errors = listOf(message)))
                    }
                }
            )
        }
        return result
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
                    val validation = validateSnapshot(active, snapshot)
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
                        executionPort.cancel(accountId, active.orderId).fold(
                            onSuccess = {
                                activeAccounts.remove(active.orderId)
                                state.onOrderCanceled()
                                result = result.merge(
                                    EventMakerEngineResult(
                                        canceled = listOf(
                                            EventMakerEngineCancel(state.market.conditionId, active.orderId, "partial_fill")
                                        )
                                    )
                                )
                            },
                            onFailure = { error ->
                                val reason = "partial_cancel_failed:${error.message}"
                                halt(reason)
                                result = result.merge(EventMakerEngineResult.halted(reason))
                            }
                        )
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
            val validation = validateSnapshot(active, snapshot)
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
                if (cancelResult.isSuccess && snapshot.status.uppercase() !in FILLED_ORDER_STATUSES) {
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

    private fun validateSnapshot(expected: EventMakerLiveOrder, actual: EventMakerLiveOrder): String? {
        if (actual.orderId != expected.orderId) return "order_state_unknown:order_id_mismatch"
        if (actual.side != expected.side) return "order_state_unknown:side_mismatch"
        if (actual.price <= BigDecimal.ZERO || actual.price >= BigDecimal.ONE) return "order_state_unknown:invalid_price"
        if (actual.price.compareTo(expected.price) != 0) return "order_state_unknown:price_mismatch"
        if (actual.originalShares <= BigDecimal.ZERO) return "order_state_unknown:invalid_original_size"
        if (actual.originalShares.compareTo(expected.originalShares) != 0) {
            return "order_state_unknown:original_size_mismatch"
        }
        if (actual.matchedShares < BigDecimal.ZERO || actual.matchedShares > actual.originalShares) {
            return "order_state_unknown:invalid_matched_size"
        }
        if (actual.status.isBlank() || actual.status.uppercase() !in KNOWN_ORDER_STATUSES) {
            return "order_state_unknown:status_${actual.status.ifBlank { "blank" }}"
        }
        return null
    }

    private fun halt(reason: String) {
        haltedReason = reason
    }

    companion object {
        private const val MAX_RECONCILE_FAILURES = 3
        private val FILLED_ORDER_STATUSES = setOf("FILLED")
        private val KNOWN_ORDER_STATUSES = setOf(
            "LIVE", "OPEN", "MATCHED", "FILLED", "CANCELLED", "CANCELED", "EXPIRED", "REJECTED"
        )
    }
}
