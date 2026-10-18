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
@Component
data class EventMakerShadowFill(
    val orderId: String,
    val marketId: String,
    val side: EventMakerSide,
    val price: BigDecimal,
    val shares: BigDecimal,
    val timestamp: Long
)

class EventMakerShadowExecutionPort : EventMakerExecutionPort {
    private val lock = Any()
    private val nextId = AtomicLong(0)
    private val orders = linkedMapOf<String, SimulatedOrder>()

    override suspend fun validateAccount(accountId: Long): Result<Unit> = Result.success(Unit)

    override suspend fun place(
        accountId: Long,
        market: LivePaperMarket,
        intent: EventMakerQuoteIntent
    ): Result<EventMakerLiveOrder> = synchronized(lock) {
        val orderId = "shadow-${nextId.incrementAndGet()}"
        val order = SimulatedOrder(
            id = orderId,
            market = market,
            side = intent.side,
            price = intent.price,
            originalShares = intent.shares,
            remainingShares = intent.shares,
            queueAhead = BigDecimal.valueOf(intent.queueAheadShares),
            createdAt = System.currentTimeMillis()
        )
        orders[orderId] = order
        Result.success(order.toLiveOrder())
    }

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
        order.status = "CANCELLED"
        Result.success(Unit)
    }

    fun onTrade(trade: LiveTradePrint): List<EventMakerShadowFill> = synchronized(lock) {
        val filled = mutableListOf<EventMakerShadowFill>()
        orders.values.forEach { order ->
            if (order.status != "LIVE" || trade.tokenId != order.market.favoriteTokenId) return@forEach
            if (trade.timestamp < order.createdAt - 1_000L) return@forEach
            val crosses = when (order.side) {
                EventMakerSide.BUY -> trade.side.equals("SELL", true) && trade.price <= order.price.toDouble() + 1e-9
                EventMakerSide.SELL -> trade.side.equals("BUY", true) && trade.price >= order.price.toDouble() - 1e-9
            }
            if (!crosses) return@forEach

            var available = BigDecimal.valueOf(trade.size)
            if (order.queueAhead > BigDecimal.ZERO) {
                val consumed = order.queueAhead.min(available)
                order.queueAhead -= consumed
                available -= consumed
            }
            if (available <= BigDecimal.ZERO) return@forEach

            val quantity = order.remainingShares.min(available)
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

    fun reset() = synchronized(lock) {
        orders.clear()
    }

    fun activeOrderCount(): Int = synchronized(lock) { orders.values.count { it.status == "LIVE" } }

    private data class SimulatedOrder(
        val id: String,
        val market: LivePaperMarket,
        val side: EventMakerSide,
        val price: BigDecimal,
        val originalShares: BigDecimal,
        var remainingShares: BigDecimal,
        var queueAhead: BigDecimal,
        val createdAt: Long,
        var status: String = "LIVE"
    ) {
        fun toLiveOrder(): EventMakerLiveOrder = EventMakerLiveOrder(
            orderId = id,
            side = side,
            price = price,
            originalShares = originalShares,
            matchedShares = originalShares - remainingShares,
            status = status,
            createdAt = createdAt
        )
    }

    companion object {
        private val SHARE_EPSILON = BigDecimal("0.000001")
    }
}