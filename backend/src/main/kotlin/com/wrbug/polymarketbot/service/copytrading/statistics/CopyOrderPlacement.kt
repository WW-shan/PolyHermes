package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.NewOrderResponse
import com.wrbug.polymarketbot.api.OpenOrder
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.SignedOrderObject
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 下单结果
 * - [Settled]：成交结果已确定（filledShares 可能为 0，表示 FAK 未成交）
 * - [Pending]：订单已被受理但成交未确认（delayed/live/unmatched），需轮询 size_matched 回填
 */
sealed class OrderOutcome {
    abstract val orderId: String

    data class Settled(
        override val orderId: String = "",
        val filledShares: BigDecimal = BigDecimal.ZERO,
        val avgPrice: BigDecimal? = null
    ) : OrderOutcome()

    data class Pending(
        override val orderId: String = "",
        val status: String = ""
    ) : OrderOutcome()
}

/** 下单被明确拒绝（4xx/业务错误），订单未被受理，可以安全放弃 */
class OrderRejectedException(message: String) : RuntimeException(message)

/** 多次尝试后仍无法确认订单是否被受理（超时/5xx 且查询失败），需保留待确认记录由轮询核对 */
class OrderStatusUnknownException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 跟单下单执行器（幂等重试）
 * - 只签一次：所有重试复用同一个 SignedOrder（同一订单 hash），同一订单在交易所最多成交一次
 * - 超时/IO 异常/5xx：先用本地订单 hash 调 GET /data/order/{hash} 确认是否已受理，已受理按查询结果处理，未受理再重发
 * - 4xx/业务错误：不重试
 */
class CopyOrderPlacementExecutor(
    private val maxAttempts: Int = 3,
    private val retryDelayMs: Long = 2000L
) {
    private val logger = LoggerFactory.getLogger(CopyOrderPlacementExecutor::class.java)

    /**
     * 提交已签名订单（FAK），带幂等重试
     * @param orderHash 本地计算的订单 hash（= CLOB orderID）
     * @param logContext 日志上下文
     */
    suspend fun place(
        clobApi: PolymarketClobApi,
        signedOrder: SignedOrderObject,
        orderHash: String,
        owner: String,
        logContext: String
    ): Result<OrderOutcome> {
        val request = NewOrderRequest(order = signedOrder, owner = owner, orderType = "FAK")
        val side = signedOrder.side
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                val response = clobApi.createOrder(request)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    if (body.success && !body.orderId.isNullOrBlank()) {
                        logger.info("创建订单成功: $logContext, orderId=${body.orderId}, status=${body.status}, attempt=$attempt")
                        return Result.success(outcomeFromResponse(body, side))
                    }
                    // HTTP 200 但业务失败：订单未被受理
                    return Result.failure(OrderRejectedException("errorMsg=${body.getErrorMessage()}"))
                }
                val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
                val errorMsg = "code=${response.code()}, errorBody=${errorBody ?: "null"}"
                if (response.code() in 400..499) {
                    logger.error("创建订单被拒绝，不重试: $logContext, $errorMsg")
                    return Result.failure(OrderRejectedException(errorMsg))
                }
                lastError = Exception(errorMsg)
                logger.error("创建订单服务端错误 (尝试 $attempt/$maxAttempts): $logContext, $errorMsg")
            } catch (e: Exception) {
                lastError = e
                logger.error("创建订单异常 (尝试 $attempt/$maxAttempts): $logContext, error=${e.message}")
            }

            // 结果不确定：先按本地 hash 查询订单是否已被受理
            delay(retryDelayMs)
            val queried = queryOrder(clobApi, orderHash)
            if (queried != null) {
                logger.warn("订单提交结果不确定，但查询到订单已受理: $logContext, orderId=$orderHash, status=${queried.status}, sizeMatched=${queried.sizeMatched}")
                return Result.success(outcomeFromOpenOrder(queried))
            }
            // 未查询到（或查询失败）：重发同一个签名订单（同一 hash，不会重复成交）
        }
        return Result.failure(
            OrderStatusUnknownException("多次尝试后无法确认订单是否受理: orderId=$orderHash, lastError=${lastError?.message}", lastError)
        )
    }

    /** 查询订单详情；不存在或查询失败返回 null */
    suspend fun queryOrder(clobApi: PolymarketClobApi, orderHash: String): OpenOrder? {
        return try {
            val response = clobApi.getOrder(orderHash)
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            logger.warn("查询订单失败: orderId=$orderHash, error=${e.message}")
            null
        }
    }

    companion object {
        /** V2 订单终态（不会再成交） */
        private val TERMINAL_ORDER_STATUSES = setOf("MATCHED", "FILLED", "CANCELED", "CANCELLED", "INVALID", "CANCELED_MARKET_RESOLVED")

        /**
         * 解析下单响应
         * matched：BUY 的 takingAmount 为得到的 shares、makingAmount 为花费的 USDC；SELL 的 makingAmount 为卖出的 shares、takingAmount 为得到的 USDC
         * delayed/live/unmatched 等：待确认
         */
        fun outcomeFromResponse(body: NewOrderResponse, side: String): OrderOutcome {
            val orderId = body.orderId.orEmpty()
            if (!body.status.equals("matched", ignoreCase = true)) {
                return OrderOutcome.Pending(orderId = orderId, status = body.status.orEmpty())
            }
            val making = body.makingAmount?.trim()?.toBigDecimalOrNull()
            val taking = body.takingAmount?.trim()?.toBigDecimalOrNull()
            val isBuy = side.equals("BUY", ignoreCase = true)
            val shares = if (isBuy) taking else making
            val usdc = if (isBuy) making else taking
            if (shares == null || usdc == null) {
                // 成交金额缺失：不能猜测，交给轮询按 size_matched 回填
                return OrderOutcome.Pending(orderId = orderId, status = body.status.orEmpty())
            }
            val avg = if (shares.signum() > 0) usdc.divide(shares, 8, RoundingMode.HALF_UP) else null
            return OrderOutcome.Settled(orderId = orderId, filledShares = shares, avgPrice = avg)
        }

        /**
         * 由订单详情得到结果：这里不能把订单限价当成成交均价。
         * 即使订单已终态，也返回 Pending，由 OrderStatusUpdateService 回查成交明细后按真实均价确认。
         */
        fun outcomeFromOpenOrder(order: OpenOrder): OrderOutcome =
            OrderOutcome.Pending(orderId = order.id, status = order.status)

        /** 订单是否已到终态 */
        fun isTerminal(order: OpenOrder): Boolean = order.status.uppercase() in TERMINAL_ORDER_STATUSES
    }
}
