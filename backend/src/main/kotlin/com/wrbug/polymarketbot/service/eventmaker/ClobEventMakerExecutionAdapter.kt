package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Real CLOB execution adapter. Every order is post-only GTC; there is no taker fallback.
 */
@Service
@Primary
class ClobEventMakerExecutionAdapter(
    private val accountRepository: AccountRepository,
    private val accountService: AccountService,
    private val cryptoUtils: CryptoUtils,
    private val retrofitFactory: RetrofitFactory,
    private val orderSigningService: OrderSigningService,
    private val clobService: PolymarketClobService,
    private val blockchainService: BlockchainService
) : EventMakerExecutionPort {

    private val logger = LoggerFactory.getLogger(ClobEventMakerExecutionAdapter::class.java)

    override suspend fun validateAccount(accountId: Long): Result<Unit> {
        val context = accountContext(accountId)
            ?: return Result.failure(IllegalStateException("account_not_found_or_credentials_missing"))
        if (context.account.proxyAddress.isBlank()) {
            return Result.failure(IllegalStateException("proxy_wallet_missing"))
        }
        return blockchainService.checkProxyDeployed(context.account.proxyAddress).fold(
            onSuccess = { deployed ->
                if (deployed) Result.success(Unit)
                else Result.failure(IllegalStateException("proxy_wallet_not_deployed"))
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun listOpenOrderIds(accountId: Long): Result<Set<String>> {
        val context = accountContext(accountId)
            ?: return Result.failure(IllegalStateException("account_not_found_or_credentials_missing"))
        val orderIds = linkedSetOf<String>()
        var cursor: String? = null
        repeat(MAX_ACTIVE_ORDER_PAGES) {
            val response = try {
                context.api.getActiveOrders(next_cursor = cursor)
            } catch (e: Exception) {
                logger.error("Event maker active order lookup failed: accountId=$accountId", e)
                return Result.failure(IllegalStateException("active_orders_unavailable", e))
            }
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                return Result.failure(IllegalStateException("active_orders_failed_http_${response.code()}"))
            }
            body.data.forEach { order ->
                if (order.id.isBlank()) {
                    return Result.failure(EventMakerUnknownOrderStateException("active_order_id_missing"))
                }
                orderIds += order.id
            }
            val next = body.next_cursor?.takeIf { it.isNotBlank() }
            if (next == null) return Result.success(orderIds)
            if (next == cursor) return Result.failure(IllegalStateException("active_orders_cursor_repeated:$next"))
            cursor = next
        }
        return Result.failure(IllegalStateException("active_orders_pagination_limit"))
    }

    override suspend fun listOpenPositionConditionIds(accountId: Long): Result<Set<String>> {
        val context = accountContext(accountId)
            ?: return Result.failure(IllegalStateException("account_not_found_or_credentials_missing"))
        val positions = blockchainService.getPositions(context.account.proxyAddress)
            .getOrElse { return Result.failure(IllegalStateException("positions_unavailable:${it.message}", it)) }
        val conditionIds = linkedSetOf<String>()
        for (position in positions) {
            val size = position.size
            if (size == null) {
                if ((position.currentValue ?: 0.0) > 0.0) {
                    return Result.failure(EventMakerUnknownOrderStateException("position_size_missing"))
                }
                continue
            }
            if (size < 0.0) return Result.failure(EventMakerUnknownOrderStateException("position_size_negative"))
            if (size <= 0.0) continue
            val conditionId = position.conditionId?.takeIf { it.isNotBlank() }
                ?: return Result.failure(EventMakerUnknownOrderStateException("position_condition_missing"))
            if (!conditionIds.add(conditionId)) {
                return Result.failure(EventMakerUnknownOrderStateException("multiple_position_assets:$conditionId"))
            }
        }
        return Result.success(conditionIds)
    }

    override suspend fun place(
        accountId: Long,
        market: LivePaperMarket,
        intent: EventMakerQuoteIntent
    ): Result<EventMakerLiveOrder> {
        val context = accountContext(accountId)
            ?: return Result.failure(IllegalStateException("account_not_found_or_credentials_missing"))
        val tick = clobService.getTickSize(market.favoriteTokenId)
            .getOrElse { return Result.failure(it) }
        val negRisk = clobService.getNegRisk(market.favoriteTokenId)
            .getOrElse { return Result.failure(it) }
        val orderbook = clobService.getOrderbookByTokenId(market.favoriteTokenId)
            .getOrElse { return Result.failure(it) }

        val price = intent.price.setScale(tick.stripTrailingZeros().scale(), RoundingMode.DOWN)
        val shares = intent.shares.setScale(2, RoundingMode.DOWN)
        val normalizedIntent = intent.copy(price = price, shares = shares)
        EventMakerExecutionGuards.validateOrderShape(
            normalizedIntent,
            tick,
            orderbook.minOrderSize?.toBigDecimalOrNull()
        ).getOrElse { return Result.failure(it) }

        val exchange = orderSigningService.getExchangeContract(negRisk)
        val fundingCheck = when (intent.side) {
            EventMakerSide.BUY -> {
                val balance = blockchainService.getUsdcBalance(
                    context.account.walletAddress,
                    context.account.proxyAddress
                ).getOrElse { return Result.failure(EventMakerPreflightRejectedException("usdc_balance_unavailable:${it.message}")) }
                    .toBigDecimalOrNull()
                val allowance = blockchainService.getUsdcAllowance(
                    context.account.proxyAddress,
                    exchange
                ).getOrElse { return Result.failure(EventMakerPreflightRejectedException("usdc_allowance_unavailable:${it.message}")) }
                EventMakerExecutionGuards.validateBuyFunding(
                    availableUsdc = balance,
                    allowanceRaw = allowance.toBigDecimal(),
                    requiredUsdc = price.multiply(shares)
                )
            }
            EventMakerSide.SELL -> {
                val ctfBalance = blockchainService.getCtfBalance(
                    context.account.proxyAddress,
                    market.favoriteTokenId
                ).getOrElse { return Result.failure(EventMakerPreflightRejectedException("ctf_balance_unavailable:${it.message}")) }
                val approved = blockchainService.isErc1155ApprovedForAll(
                    context.account.proxyAddress,
                    exchange
                ).getOrElse { return Result.failure(EventMakerPreflightRejectedException("ctf_approval_unavailable:${it.message}")) }
                EventMakerExecutionGuards.validateSellInventory(ctfBalance, approved, shares)
            }
        }
        fundingCheck.getOrElse { return Result.failure(it) }

        val signed = orderSigningService.createAndSignOrder(
            privateKey = context.privateKey,
            makerAddress = context.account.proxyAddress,
            tokenId = market.favoriteTokenId,
            side = intent.side.name,
            price = price.toPlainString(),
            size = shares.toPlainString(),
            signatureType = orderSigningService.getSignatureTypeForWalletType(context.account.walletType),
            exchangeContract = exchange,
            tickSize = tick,
            strictTick = true
        )
        val request = EventMakerOrderRequestFactory.build(signed, context.apiKey)
        val response = try {
            context.api.createOrder(request)
        } catch (e: Exception) {
            logger.error("Event maker live order placement outcome unknown: accountId=$accountId, side=${intent.side}", e)
            return Result.failure(EventMakerAmbiguousOrderException("create_order_outcome_unknown", e))
        }
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            val reason = response.errorBody()?.string()?.take(300) ?: "create_order_failed_http_${response.code()}"
            return if (response.code() >= 500 || response.code() == 408 || response.code() == 429) {
                Result.failure(EventMakerAmbiguousOrderException(reason))
            } else {
                Result.failure(EventMakerPreflightRejectedException(reason))
            }
        }
        if (!body.success || body.orderId.isNullOrBlank()) {
            return Result.failure(EventMakerPreflightRejectedException(body.getErrorMessage()))
        }
        return Result.success(
            EventMakerLiveOrder(
                orderId = body.orderId,
                side = intent.side,
                price = price,
                originalShares = shares,
                matchedShares = BigDecimal.ZERO,
                status = body.status ?: "LIVE"
            )
        )
    }

    override suspend fun getOrder(accountId: Long, orderId: String): Result<EventMakerLiveOrder> {
        val context = accountContext(accountId)
            ?: return Result.failure(IllegalStateException("account_not_found_or_credentials_missing"))
        val response = try {
            context.api.getOrder(orderId)
        } catch (e: Exception) {
            logger.error("Event maker order lookup failed: accountId=$accountId, orderId=$orderId", e)
            return Result.failure(e)
        }
        if (response.code() == 404) {
            return Result.failure(EventMakerOrderNotFoundException(orderId))
        }
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            return Result.failure(IllegalStateException("get_order_failed_http_${response.code()}"))
        }
        val side = when {
            body.side.equals("BUY", true) -> EventMakerSide.BUY
            body.side.equals("SELL", true) -> EventMakerSide.SELL
            else -> return Result.failure(EventMakerUnknownOrderStateException("unknown_order_side"))
        }
        val price = body.price.toBigDecimalOrNull()
            ?: return Result.failure(EventMakerUnknownOrderStateException("invalid_order_price"))
        val original = body.originalSize.toBigDecimalOrNull()
            ?: return Result.failure(EventMakerUnknownOrderStateException("invalid_order_size"))
        val matched = body.sizeMatched.toBigDecimalOrNull()
            ?: return Result.failure(EventMakerUnknownOrderStateException("invalid_order_matched_size"))
        if (body.id != orderId || price <= BigDecimal.ZERO || price >= BigDecimal.ONE ||
            original <= BigDecimal.ZERO || matched < BigDecimal.ZERO || matched > original ||
            body.status.isBlank() || body.status.uppercase() !in KNOWN_ORDER_STATUSES
        ) {
            return Result.failure(EventMakerUnknownOrderStateException("invalid_order_snapshot"))
        }
        return Result.success(
            EventMakerLiveOrder(
                orderId = body.id,
                side = side,
                price = price,
                originalShares = original,
                matchedShares = matched,
                status = body.status,
                createdAt = body.createdAt.takeIf { it > 0L }?.times(1000L) ?: System.currentTimeMillis()
            )
        )
    }

    override suspend fun cancel(accountId: Long, orderId: String): Result<Unit> {
        val context = accountContext(accountId)
            ?: return Result.failure(IllegalStateException("account_not_found_or_credentials_missing"))
        val response = try {
            context.api.cancelOrder(orderId)
        } catch (e: Exception) {
            logger.error("Event maker order cancellation failed: accountId=$accountId, orderId=$orderId", e)
            return Result.failure(e)
        }
        if (response.code() == 404) {
            return Result.failure(EventMakerOrderNotFoundException(orderId))
        }
        if (!response.isSuccessful || response.body() == null) {
            return Result.failure(IllegalStateException("cancel_order_failed_http_${response.code()}"))
        }
        return Result.success(Unit)
    }

    private fun accountContext(accountId: Long): AccountContext? {
        val account = accountRepository.findById(accountId).orElse(null) ?: return null
        val apiKey = account.apiKey?.takeIf { it.isNotBlank() } ?: return null
        val secret = decrypt(account.apiSecret) ?: return null
        val passphrase = decrypt(account.apiPassphrase) ?: return null
        val privateKey = runCatching { accountService.decryptPrivateKey(account) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        return AccountContext(
            account = account,
            apiKey = apiKey,
            api = retrofitFactory.createClobApi(apiKey, secret, passphrase, account.walletAddress),
            privateKey = privateKey
        )
    }

    private fun decrypt(value: String?): String? =
        value?.let { runCatching { cryptoUtils.decrypt(it) }.getOrNull() }?.takeIf { it.isNotBlank() }

    companion object {
        private const val MAX_ACTIVE_ORDER_PAGES = 100
        private val KNOWN_ORDER_STATUSES = setOf(
            "LIVE", "OPEN", "MATCHED", "FILLED", "CANCELLED", "CANCELED", "EXPIRED", "REJECTED"
        )
    }

    private data class AccountContext(
        val account: Account,
        val apiKey: String,
        val api: PolymarketClobApi,
        val privateKey: String
    )
}