package com.wrbug.polymarketbot.service.eventmaker

import java.math.BigDecimal
import java.math.RoundingMode

class EventMakerPreflightRejectedException(message: String) : IllegalStateException(message)

class EventMakerOrderNotFoundException(val orderId: String) :
    IllegalStateException("order_not_found:$orderId")

class EventMakerUnknownOrderStateException(message: String) : IllegalStateException(message)

class EventMakerAmbiguousOrderException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Pure order-shape and account-funding guards used before any signed order is sent.
 * These checks intentionally fail closed when an upstream value is missing or malformed.
 */
object EventMakerExecutionGuards {
    fun validateOrderShape(
        intent: EventMakerQuoteIntent,
        tickSize: BigDecimal,
        minOrderSize: BigDecimal?
    ): Result<Unit> {
        if (intent.price <= BigDecimal.ZERO || intent.price >= BigDecimal.ONE) {
            return Result.failure(EventMakerPreflightRejectedException("invalid_price"))
        }
        if (intent.shares <= BigDecimal.ZERO) {
            return Result.failure(EventMakerPreflightRejectedException("invalid_shares"))
        }
        if (tickSize <= BigDecimal.ZERO || tickSize >= BigDecimal.ONE) {
            return Result.failure(EventMakerPreflightRejectedException("invalid_tick_size"))
        }
        val normalized = intent.price.divide(tickSize, 0, RoundingMode.DOWN).multiply(tickSize)
        if (normalized.compareTo(intent.price) != 0) {
            return Result.failure(EventMakerPreflightRejectedException("price_not_on_tick"))
        }
        val minimum = minOrderSize?.takeIf { it > BigDecimal.ZERO }
            ?: return Result.failure(EventMakerPreflightRejectedException("min_order_size_unavailable"))
        if (intent.shares < minimum) {
            return Result.failure(EventMakerPreflightRejectedException("below_min_order_size"))
        }
        return Result.success(Unit)
    }

    fun validateBuyFunding(
        availableUsdc: BigDecimal?,
        allowanceRaw: BigDecimal?,
        requiredUsdc: BigDecimal
    ): Result<Unit> {
        if (availableUsdc == null || availableUsdc < requiredUsdc) {
            return Result.failure(EventMakerPreflightRejectedException("insufficient_usdc_balance"))
        }
        val requiredRaw = requiredUsdc.movePointRight(6).setScale(0, RoundingMode.CEILING)
        if (allowanceRaw == null || allowanceRaw < requiredRaw) {
            return Result.failure(EventMakerPreflightRejectedException("insufficient_usdc_allowance"))
        }
        return Result.success(Unit)
    }

    fun validateSellInventory(
        onChainShares: BigDecimal?,
        exchangeApproved: Boolean?,
        requiredShares: BigDecimal
    ): Result<Unit> {
        if (onChainShares == null || onChainShares < requiredShares) {
            return Result.failure(EventMakerPreflightRejectedException("insufficient_ctf_inventory"))
        }
        if (exchangeApproved != true) {
            return Result.failure(EventMakerPreflightRejectedException("ctf_exchange_not_approved"))
        }
        return Result.success(Unit)
    }
}