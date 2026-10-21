package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.research.livepaper.LiveTradePrint
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process maker fill simulator used by Shadow mode.
 *
 * It consumes public taker trades after the simulated order's queue-ahead has been cleared.
 * It never signs, submits, or cancels a real order.
 */
data class EventMakerShadowFill(
    val orderId: String,
    val marketId: String,
    val side: EventMakerSide,
    val price: BigDecimal,
    val shares: BigDecimal,
    val timestamp: Long
)

data class EventMakerShadowOrderDiagnostics(
    val orderId: String,
    val marketId: String,
    val side: EventMakerSide,
    val price: BigDecimal,
    val originalShares: BigDecimal,
    val filledShares: BigDecimal,
    val queueAheadAtPlacement: BigDecimal,
    val queueAheadRemaining: BigDecimal,
    val opposingTradeCount: Int,
    val crossingTradeCount: Int,
    val crossingTradeShares: BigDecimal,
    val eligibleCrossingShares: BigDecimal,
    val queueConsumedShares: BigDecimal,
    val status: String,
    val takerEntry: Boolean,
    val createdAt: Long,
    val canceledAt: Long?
)

data class EventMakerShadowExecutionDiagnostics(
    val fillVolumeFactor: Double,
    val totalOrders: Int,
    val activeOrders: Int,
    val ordersWithCrossingTrades: Int,
    val totalOpposingTrades: Int,
    val totalCrossingTrades: Int,
    val totalCrossingShares: BigDecimal,
    val totalEligibleCrossingShares: BigDecimal,
    val totalQueueConsumedShares: BigDecimal,
    val totalFilledShares: BigDecimal,
    val orders: List<EventMakerShadowOrderDiagnostics>
)

@Component
class EventMakerShadowExecutionPort : EventMakerExecutionPort {
    private val lock = Any()
    private val nextId = AtomicLong(0)
    private val orders = linkedMapOf<String, SimulatedOrder>()
    private val immediateFills = mutableListOf<EventMakerShadowFill>()
    private var orderIdPrefix = "shadow-${ProcessHandle.current().pid()}-${System.currentTimeMillis()}-${INSTANCE_SEQUENCE.incrementAndGet()}"

    @Volatile
    private var fillVolumeFactor = 1.0

    private var settlementResolver: (suspend (LivePaperMarket) -> Result<EventMakerSettlementSnapshot>)? = null

    /**
     * Requires cumulative crossing taker flow of [factor] times the order size after queue
     * ahead is exhausted. The default 1 is the conservative baseline; tests may explicitly
     * set 0 only to reproduce the legacy optimistic behavior.
     */
    fun configureFillModel(factor: Double) = synchronized(lock) {
        require(factor >= 0.0) { "fillVolumeFactor must be non-negative" }
        fillVolumeFactor = factor
    }

    /**
     * Gives every process a distinct order-id namespace. Shadow ledgers survive
     * restarts, so reusing "shadow-1" would make old fills and new orders
     * indistinguishable.
     */
    fun configureOrderIdPrefix(prefix: String) = synchronized(lock) {
        require(prefix.isNotBlank()) { "orderIdPrefix must not be blank" }
        orderIdPrefix = prefix
        nextId.set(0)
    }

    fun configureSettlementResolver(
        resolver: suspend (LivePaperMarket) -> Result<EventMakerSettlementSnapshot>
    ) = synchronized(lock) {
        settlementResolver = resolver
    }

    override suspend fun validateAccount(accountId: Long): Result<Unit> = Result.success(Unit)

    override suspend fun listOpenOrderIds(accountId: Long): Result<Set<String>> = synchronized(lock) {
        Result.success(orders.filterValues { it.status == "LIVE" }.keys.toSet())
    }

    override suspend fun listOpenPositions(accountId: Long): Result<Map<String, java.math.BigDecimal>> =
        Result.success(emptyMap())

    override suspend fun place(
        accountId: Long,
        market: LivePaperMarket,
        intent: EventMakerQuoteIntent
    ): Result<EventMakerLiveOrder> = synchronized(lock) {
        val orderId = "$orderIdPrefix-${nextId.incrementAndGet()}"
        val now = System.currentTimeMillis()
        val queueAhead = if (intent.takerEntry) BigDecimal.ZERO else BigDecimal.valueOf(intent.queueAheadShares)
        val order = SimulatedOrder(
            id = orderId,
            market = market,
            side = intent.side,
            price = intent.price,
            originalShares = intent.shares,
            remainingShares = if (intent.takerEntry) BigDecimal.ZERO else intent.shares,
            queueAheadAtPlacement = queueAhead,
            queueAheadRemaining = queueAhead,
            createdAt = now,
            status = if (intent.takerEntry) "FILLED" else "LIVE",
            takerEntry = intent.takerEntry
        )
        orders[orderId] = order
        if (intent.takerEntry) {
            immediateFills += EventMakerShadowFill(
                orderId = orderId,
                marketId = market.conditionId,
                side = intent.side,
                price = intent.price,
                shares = intent.shares,
                timestamp = now
            )
        }
        Result.success(order.toLiveOrder())
    }

    override suspend fun getSettlementSnapshot(
        accountId: Long,
        market: LivePaperMarket
    ): Result<EventMakerSettlementSnapshot> {
        val resolver = synchronized(lock) { settlementResolver }
            ?: return Result.failure(IllegalStateException("shadow_settlement_resolver_missing"))
        return resolver(market)
    }

    /**
     * Shadow positions are virtual. Once the engine has booked the terminal
     * payoff, the corresponding on-chain position is therefore already absent.
     */
    override suspend fun getSettlementPosition(
        accountId: Long,
        settlement: EventMakerSettlementRecord
    ): Result<EventMakerSettlementPosition> = Result.success(
        EventMakerSettlementPosition(
            tokenId = settlement.favoriteTokenId,
            shares = BigDecimal.ZERO,
            redeemable = false,
            outcomeIndex = settlement.outcomeIndex,
            negativeRisk = settlement.negativeRisk
        )
    )
    override suspend fun getOrder(accountId: Long, orderId: String): Result<EventMakerLiveOrder> = synchronized(lock) {
        val order = orders[orderId]
            ?: return@synchronized Result.failure(EventMakerOrderNotFoundException(orderId))
        Result.success(order.toLiveOrder())
    }

    override suspend fun cancel(accountId: Long, orderId: String): Result<Unit> = synchronized(lock) {
        val order = orders[orderId]
            ?: return@synchronized Result.failure(EventMakerOrderNotFoundException(orderId))
        if (order.status == "FILLED") {
            return@synchronized Result.success(Unit)
        }
        if (order.status == "LIVE") {
            order.status = "CANCELLED"
            order.canceledAt = System.currentTimeMillis()
        }
        Result.success(Unit)
    }

    /** Returns fills created by marketable orders since the previous drain. */
    fun drainImmediateFills(): List<EventMakerShadowFill> = synchronized(lock) {
        val drained = immediateFills.toList()
        immediateFills.clear()
        drained
    }

    fun onTrade(trade: LiveTradePrint): List<EventMakerShadowFill> = synchronized(lock) {
        val filled = mutableListOf<EventMakerShadowFill>()
        orders.values.forEach { order ->
            if (order.status != "LIVE" || trade.tokenId != order.market.favoriteTokenId) return@forEach
            if (trade.timestamp < order.createdAt - 1_000L) return@forEach

            val oppositeSide = when (order.side) {
                EventMakerSide.BUY -> trade.side.equals("SELL", true)
                EventMakerSide.SELL -> trade.side.equals("BUY", true)
            }
            if (!oppositeSide) return@forEach
            order.opposingTradeCount++

            val crosses = when (order.side) {
                EventMakerSide.BUY -> trade.price <= order.price.toDouble() + 1e-9
                EventMakerSide.SELL -> trade.price >= order.price.toDouble() - 1e-9
            }
            if (!crosses) return@forEach

            var available = BigDecimal.valueOf(trade.size)
            order.crossingTradeCount++
            order.crossingTradeShares += available
            if (order.queueAheadRemaining > BigDecimal.ZERO) {
                val consumed = order.queueAheadRemaining.min(available)
                order.queueAheadRemaining -= consumed
                order.queueConsumedShares += consumed
                available -= consumed
            }
            if (available <= BigDecimal.ZERO) return@forEach

            order.eligibleCrossingShares += available
            val requiredCrossingShares = order.originalShares.multiply(BigDecimal.valueOf(fillVolumeFactor))
            if (order.eligibleCrossingShares + SHARE_EPSILON < requiredCrossingShares) return@forEach

            val quantity = if (fillVolumeFactor <= 0.0) {
                order.remainingShares.min(available)
            } else {
                order.remainingShares
            }
            if (quantity > BigDecimal.ZERO) {
                order.remainingShares -= quantity
                if (order.remainingShares <= SHARE_EPSILON) {
                    order.remainingShares = BigDecimal.ZERO
                    order.status = "FILLED"
                }
                filled += EventMakerShadowFill(order.id, order.market.conditionId, order.side, order.price, quantity, trade.timestamp)
            }
        }
        filled
    }

    fun diagnostics(): EventMakerShadowExecutionDiagnostics = synchronized(lock) {
        val snapshots = orders.values.map { order ->
            EventMakerShadowOrderDiagnostics(
                orderId = order.id,
                marketId = order.market.conditionId,
                side = order.side,
                price = order.price,
                originalShares = order.originalShares,
                filledShares = order.originalShares - order.remainingShares,
                queueAheadAtPlacement = order.queueAheadAtPlacement,
                queueAheadRemaining = order.queueAheadRemaining,
                opposingTradeCount = order.opposingTradeCount,
                crossingTradeCount = order.crossingTradeCount,
                crossingTradeShares = order.crossingTradeShares,
                eligibleCrossingShares = order.eligibleCrossingShares,
                queueConsumedShares = order.queueConsumedShares,
                status = order.status,
                takerEntry = order.takerEntry,
                createdAt = order.createdAt,
                canceledAt = order.canceledAt
            )
        }
        EventMakerShadowExecutionDiagnostics(
            fillVolumeFactor = fillVolumeFactor,
            totalOrders = snapshots.size,
            activeOrders = snapshots.count { it.status == "LIVE" },
            ordersWithCrossingTrades = snapshots.count { it.crossingTradeCount > 0 },
            totalOpposingTrades = snapshots.sumOf { it.opposingTradeCount },
            totalCrossingTrades = snapshots.sumOf { it.crossingTradeCount },
            totalCrossingShares = snapshots.fold(BigDecimal.ZERO) { total, item -> total + item.crossingTradeShares },
            totalEligibleCrossingShares = snapshots.fold(BigDecimal.ZERO) { total, item -> total + item.eligibleCrossingShares },
            totalQueueConsumedShares = snapshots.fold(BigDecimal.ZERO) { total, item -> total + item.queueConsumedShares },
            totalFilledShares = snapshots.fold(BigDecimal.ZERO) { total, item -> total + item.filledShares },
            orders = snapshots
        )
    }

    fun reset() = synchronized(lock) {
        orders.clear()
        immediateFills.clear()
        settlementResolver = null
    }

    fun activeOrderCount(): Int = synchronized(lock) { orders.values.count { it.status == "LIVE" } }

    private data class SimulatedOrder(
        val id: String,
        val market: LivePaperMarket,
        val side: EventMakerSide,
        val price: BigDecimal,
        val originalShares: BigDecimal,
        var remainingShares: BigDecimal,
        val queueAheadAtPlacement: BigDecimal,
        var queueAheadRemaining: BigDecimal,
        val createdAt: Long,
        var status: String = "LIVE",
        val takerEntry: Boolean = false,
        var canceledAt: Long? = null,
        var opposingTradeCount: Int = 0,
        var crossingTradeCount: Int = 0,
        var crossingTradeShares: BigDecimal = BigDecimal.ZERO,
        var eligibleCrossingShares: BigDecimal = BigDecimal.ZERO,
        var queueConsumedShares: BigDecimal = BigDecimal.ZERO
    ) {
        fun toLiveOrder(): EventMakerLiveOrder = EventMakerLiveOrder(
            orderId = id,
            side = side,
            price = price,
            originalShares = originalShares,
            matchedShares = originalShares - remainingShares,
            status = status,
            createdAt = createdAt,
            tokenId = market.favoriteTokenId,
            conditionId = market.conditionId,
            makerAddress = "shadow"
        )
    }

    companion object {
        private val SHARE_EPSILON = BigDecimal("0.000001")
        private val INSTANCE_SEQUENCE = AtomicLong(0)
    }
}
