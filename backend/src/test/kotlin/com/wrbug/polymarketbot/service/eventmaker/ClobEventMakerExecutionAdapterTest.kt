package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.api.CancelOrderPayload
import com.wrbug.polymarketbot.api.CancelOrdersResponse
import com.wrbug.polymarketbot.api.GetActiveOrdersResponse
import com.wrbug.polymarketbot.api.NegRiskResponse
import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.NewOrderResponse
import com.wrbug.polymarketbot.api.OpenOrder
import com.wrbug.polymarketbot.api.OrderResponse
import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.SignedOrderObject
import com.wrbug.polymarketbot.api.TickSizeResponse
import com.wrbug.polymarketbot.dto.AccountRedeemTransaction
import com.wrbug.polymarketbot.dto.PositionRedeemRequest
import com.wrbug.polymarketbot.dto.PositionRedeemResponse
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import retrofit2.Response
import retrofit2.http.DELETE
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Optional

class ClobEventMakerExecutionAdapterTest {
    private val accountRepository: AccountRepository = Mockito.mock(AccountRepository::class.java)
    private val accountService: AccountService = Mockito.mock(AccountService::class.java)
    private val cryptoUtils: CryptoUtils = Mockito.mock(CryptoUtils::class.java)
    private val retrofitFactory: RetrofitFactory = Mockito.mock(RetrofitFactory::class.java)
    private val signing: OrderSigningService = Mockito.mock(OrderSigningService::class.java)
    private val clobService: PolymarketClobService = Mockito.mock(PolymarketClobService::class.java)
    private val blockchainService: BlockchainService = Mockito.mock(BlockchainService::class.java)
    private val marketService: MarketService = Mockito.mock(MarketService::class.java)
    private val relayClientService: RelayClientService = Mockito.mock(RelayClientService::class.java)
    private val api: PolymarketClobApi = Mockito.mock(PolymarketClobApi::class.java)
    private val adapter = ClobEventMakerExecutionAdapter(
        accountRepository,
        accountService,
        cryptoUtils,
        retrofitFactory,
        signing,
        clobService,
        blockchainService,
        marketService,
        relayClientService
    )

    @Test
    fun `validate account fails closed when auto redemption has no builder key`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(blockchainService.checkProxyDeployed("0xproxy")).thenReturn(Result.success(true))
        Mockito.`when`(relayClientService.isBuilderApiKeyConfigured()).thenReturn(false)

        val result = adapter.validateAccount(1L)

        assertTrue(result.isFailure)
        assertEquals("settlement_builder_api_key_missing", result.exceptionOrNull()?.message)
    }

    @Test
    fun `signing failure is returned as a preflight failure instead of escaping`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(blockchainService.getUsdcBalance("0xwallet", "0xproxy"))
            .thenReturn(Result.success("100.00"))
        Mockito.`when`(blockchainService.getUsdcAllowance("0xproxy", "0xexchange"))
            .thenReturn(Result.success(BigInteger("1000000000")))
        Mockito.`when`(
            signing.createAndSignOrder(
                privateKey = Mockito.anyString(),
                makerAddress = Mockito.anyString(),
                tokenId = Mockito.anyString(),
                side = Mockito.anyString(),
                price = Mockito.anyString(),
                size = Mockito.anyString(),
                signatureType = Mockito.anyInt(),
                exchangeContract = Mockito.anyString(),
                tickSize = Mockito.any(),
                strictTick = Mockito.eq(true)
            )
        ).thenThrow(IllegalStateException("bad signature"))

        val result = adapter.place(1L, market(), intent(EventMakerSide.BUY))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message!!.startsWith("order_signing_failed"))
        Mockito.verify(api, Mockito.never()).createOrder(anyRequest())
    }


    @Test
    fun `buy order is post only GTC after funding preflight`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(blockchainService.getUsdcBalance("0xwallet", "0xproxy"))
            .thenReturn(Result.success("100.00"))
        Mockito.`when`(blockchainService.getUsdcAllowance("0xproxy", "0xexchange"))
            .thenReturn(Result.success(BigInteger("1000000000")))
        Mockito.`when`(api.createOrder(anyRequest()))
            .thenReturn(Response.success(NewOrderResponse(success = true, orderId = "order-1", status = "LIVE")))

        val result = adapter.place(1L, market(), intent(EventMakerSide.BUY))

        assertTrue(result.isSuccess)
        val captor = ArgumentCaptor.forClass(NewOrderRequest::class.java)
        Mockito.verify(api).createOrder(captorCapture(captor))
        assertTrue(captor.value.postOnly)
        assertEquals("GTC", captor.value.orderType)
        assertEquals("order-1", result.getOrThrow().orderId)
    }

    @Test
    fun `insufficient balance rejects before create order`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(blockchainService.getUsdcBalance("0xwallet", "0xproxy"))
            .thenReturn(Result.success("1.00"))
        Mockito.`when`(blockchainService.getUsdcAllowance("0xproxy", "0xexchange"))
            .thenReturn(Result.success(BigInteger("1000000000")))

        val result = adapter.place(1L, market(), intent(EventMakerSide.BUY))

        assertTrue(result.isFailure)
        assertEquals("insufficient_usdc_balance", result.exceptionOrNull()?.message)
        Mockito.verify(api, Mockito.never()).createOrder(anyRequest())
    }

    @Test
    fun `get order 404 becomes explicit unknown state`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(api.getOrder("order-1")).thenReturn(Response.error(404, "".toResponseBody()))

        val result = adapter.getOrder(1L, "order-1")

        assertTrue(result.exceptionOrNull() is EventMakerOrderNotFoundException)
    }

    @Test
    fun `active order lookup paginates and deduplicates`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(api.getActiveOrders(null, null, null, "MA==")).thenReturn(
            Response.success(
                GetActiveOrdersResponse(
                    data = listOf(orderResponse("order-1"), orderResponse("order-2")),
                    next_cursor = "next"
                )
            )
        )
        Mockito.`when`(api.getActiveOrders(null, null, null, "next")).thenReturn(
            Response.success(
                GetActiveOrdersResponse(
                    data = listOf(orderResponse("order-2"), orderResponse("order-3")),
                    next_cursor = null
                )
            )
        )

        val result = adapter.listOpenOrderIds(1L)

        assertTrue(result.isSuccess)
        assertEquals(setOf("order-1", "order-2", "order-3"), result.getOrThrow())
    }

    @Test
    fun `active order terminal cursor stops pagination`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(api.getActiveOrders(null, null, null, "MA==")).thenReturn(
            Response.success(GetActiveOrdersResponse(listOf(orderResponse("order-1")), "LTE="))
        )

        val result = adapter.listOpenOrderIds(1L)

        assertTrue(result.isSuccess)
        assertEquals(setOf("order-1"), result.getOrThrow())
        Mockito.verify(api, Mockito.never()).getActiveOrders(null, null, null, "LTE=")
    }


    @Test
    fun `repeated active order cursor fails closed`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(api.getActiveOrders(null, null, null, "MA==")).thenReturn(
            Response.success(GetActiveOrdersResponse(listOf(orderResponse("order-1")), "same"))
        )
        Mockito.`when`(api.getActiveOrders(null, null, null, "same")).thenReturn(
            Response.success(GetActiveOrdersResponse(listOf(orderResponse("order-1")), "same"))
        )

        val result = adapter.listOpenOrderIds(1L)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message!!.startsWith("active_orders_cursor_repeated"))
    }

    @Test
    fun `open positions expose only positive condition ids`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(blockchainService.getPositions("0xproxy")).thenReturn(
            Result.success(
                listOf(
                    position("condition-1", 5.0),
                    position("condition-2", 0.0),
                    position(null, null)
                )
            )
        )

        val result = adapter.listOpenPositions(1L)

        assertTrue(result.isSuccess)
        assertEquals(mapOf("condition-1" to BigDecimal("5.0")), result.getOrThrow())
    }

    @Test
    fun `multiple assets for one condition fail closed`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(blockchainService.getPositions("0xproxy")).thenReturn(
            Result.success(listOf(position("condition-1", 1.0), position("condition-1", 1.0)))
        )

        val result = adapter.listOpenPositions(1L)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message!!.startsWith("multiple_position_assets"))
    }

    @Test
    fun `positive position without condition fails closed`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(blockchainService.getPositions("0xproxy")).thenReturn(
            Result.success(listOf(position(null, 1.0)))
        )

        val result = adapter.listOpenPositions(1L)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EventMakerUnknownOrderStateException)
    }

    @Test
    fun `invalid order snapshot is rejected as unknown`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(api.getOrder("order-1")).thenReturn(
            Response.success(
                openOrder(status = "MYSTERY")
            )
        )

        val result = adapter.getOrder(1L, "order-1")

        assertTrue(result.exceptionOrNull() is EventMakerUnknownOrderStateException)
    }



    @Test
    fun `successful placement without an order id is ambiguous`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(blockchainService.getUsdcBalance("0xwallet", "0xproxy"))
            .thenReturn(Result.success("100.00"))
        Mockito.`when`(blockchainService.getUsdcAllowance("0xproxy", "0xexchange"))
            .thenReturn(Result.success(BigInteger("1000000000")))
        Mockito.`when`(api.createOrder(anyRequest()))
            .thenReturn(Response.success(NewOrderResponse(success = true, orderId = null, status = "LIVE")))

        val result = adapter.place(1L, market(), intent(EventMakerSide.BUY))

        assertTrue(result.exceptionOrNull() is EventMakerAmbiguousOrderException)
    }

    @Test
    fun `rejected placement carrying an order id is ambiguous`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(blockchainService.getUsdcBalance("0xwallet", "0xproxy"))
            .thenReturn(Result.success("100.00"))
        Mockito.`when`(blockchainService.getUsdcAllowance("0xproxy", "0xexchange"))
            .thenReturn(Result.success(BigInteger("1000000000")))
        Mockito.`when`(api.createOrder(anyRequest()))
            .thenReturn(Response.success(NewOrderResponse(success = false, orderId = "order-1", status = "")))

        val result = adapter.place(1L, market(), intent(EventMakerSide.BUY))

        assertTrue(result.exceptionOrNull() is EventMakerAmbiguousOrderException)
    }

    @Test
    fun `cancel uses the documented endpoint and body and confirms the order id`() = runTest {
        stubAccountAndMarket()
        val payload = CancelOrderPayload(orderID = "order-1")
        Mockito.`when`(api.cancelOrder(payload)).thenReturn(
            Response.success(CancelOrdersResponse(canceled = listOf("order-1"), notCanceled = emptyMap()))
        )

        val result = adapter.cancel(1L, "order-1")

        assertTrue(result.isSuccess)
        Mockito.verify(api).cancelOrder(payload)
        val method = PolymarketClobApi::class.java.methods.single { it.name == "cancelOrder" }
        assertEquals(CancelOrderPayload::class.java, method.parameterTypes.first())
        assertEquals("/order", method.getAnnotation(DELETE::class.java).value)
    }

    @Test
    fun `cancel response with not_canceled is a failure`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(api.cancelOrder(CancelOrderPayload("order-1"))).thenReturn(
            Response.success(
                CancelOrdersResponse(
                    canceled = emptyList(),
                    notCanceled = mapOf("order-1" to "Order not found or already canceled")
                )
            )
        )

        val result = adapter.cancel(1L, "order-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message!!.startsWith("cancel_not_confirmed"))
    }

    @Test
    fun `cancel response without positive confirmation is rejected`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(api.cancelOrder(CancelOrderPayload("order-1"))).thenReturn(
            Response.success(CancelOrdersResponse(canceled = null, notCanceled = emptyMap()))
        )

        val result = adapter.cancel(1L, "order-1")

        assertTrue(result.exceptionOrNull() is EventMakerUnknownOrderStateException)
    }

    @Test
    fun `documented terminal statuses are accepted`() = runTest {
        stubAccountAndMarket()
        for (status in listOf("CANCELED_MARKET_RESOLVED", "INVALID")) {
            Mockito.`when`(api.getOrder("order-1")).thenReturn(Response.success(openOrder(status = status)))
            val result = adapter.getOrder(1L, "order-1")
            assertTrue(result.isSuccess, "status $status should be accepted: ${result.exceptionOrNull()?.message}")
            assertEquals(status, result.getOrThrow().status)
        }
    }

    @Test
    fun `matched status with an unfilled remainder is rejected`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(api.getOrder("order-1")).thenReturn(
            Response.success(openOrder(status = "MATCHED", matched = "0.00"))
        )

        val result = adapter.getOrder(1L, "order-1")

        assertTrue(result.exceptionOrNull() is EventMakerUnknownOrderStateException)
    }

    @Test
    fun `order snapshot from a different maker is rejected`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(api.getOrder("order-1")).thenReturn(
            Response.success(openOrder(status = "LIVE", makerAddress = "0xother"))
        )

        val result = adapter.getOrder(1L, "order-1")

        assertTrue(result.exceptionOrNull() is EventMakerUnknownOrderStateException)
    }

    @Test
    fun `cancel 404 is surfaced and never treated as success`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(api.cancelOrder(CancelOrderPayload("order-1"))).thenReturn(Response.error(404, "".toResponseBody()))

        val result = adapter.cancel(1L, "order-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EventMakerOrderNotFoundException)
    }

    @Test
    fun `settlement snapshot uses terminal gamma outcome and exact token position`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(marketService.getMarketResolution("condition")).thenReturn(
            Result.success(
                com.wrbug.polymarketbot.service.common.MarketResolutionSnapshot(
                    closed = true,
                    outcomePrices = listOf(BigDecimal.ONE, BigDecimal.ZERO),
                    resolvedOutcomeIndex = 0
                )
            )
        )
        Mockito.`when`(blockchainService.getPositions("0xproxy")).thenReturn(
            Result.success(
                listOf(
                    PositionResponse(
                        proxyWallet = "0xproxy",
                        asset = "token",
                        conditionId = "condition",
                        size = 10.0,
                        redeemable = true,
                        outcomeIndex = 0,
                        negativeRisk = false,
                        currentValue = 10.0
                    )
                )
            )
        )

        val snapshot = adapter.getSettlementSnapshot(1L, market()).getOrThrow()

        assertEquals(0, snapshot.resolutionOutcomeIndex)
        assertEquals(BigDecimal.valueOf(10.0), snapshot.position?.shares)
        assertEquals(true, snapshot.position?.redeemable)
    }

    @Test
    fun `unresolved market does not read account positions`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(marketService.getMarketResolution("condition")).thenReturn(
            Result.success(
                com.wrbug.polymarketbot.service.common.MarketResolutionSnapshot(
                    closed = false,
                    outcomePrices = listOf(BigDecimal("0.5"), BigDecimal("0.5")),
                    resolvedOutcomeIndex = null
                )
            )
        )

        val snapshot = adapter.getSettlementSnapshot(1L, market()).getOrThrow()

        assertNull(snapshot.resolutionOutcomeIndex)
        assertNull(snapshot.position)
        Mockito.verifyNoInteractions(blockchainService)
    }

    @Test
    fun `settlement redemption returns confirmed transaction hash`() = runTest {
        stubAccountAndMarket()
        Mockito.`when`(accountService.redeemPositions(Mockito.any(PositionRedeemRequest::class.java) ?: PositionRedeemRequest(emptyList()))).thenReturn(
            Result.success(
                PositionRedeemResponse(
                    transactions = listOf(
                        AccountRedeemTransaction(
                            accountId = 1L,
                            accountName = "account",
                            transactionHash = "0xtx",
                            positions = emptyList()
                        )
                    ),
                    totalRedeemedValue = "10",
                    createdAt = 2_000L
                )
            )
        )

        val hash = adapter.redeemSettlement(1L, settlementRecord()).getOrThrow()

        assertEquals("0xtx", hash)
        val captor = ArgumentCaptor.forClass(PositionRedeemRequest::class.java)
        Mockito.verify(accountService).redeemPositions(captorCaptureRedeem(captor))
        assertEquals("condition", captor.value.positions.single().marketId)
        assertEquals(0, captor.value.positions.single().outcomeIndex)
    }
    @Test
    fun `live adapter is the primary execution port bean`() {
        assertTrue(
            ClobEventMakerExecutionAdapter::class.java.isAnnotationPresent(
                org.springframework.context.annotation.Primary::class.java
            )
        )
    }
    private fun anyRequest(): NewOrderRequest {
        Mockito.any(NewOrderRequest::class.java)
        return NewOrderRequest(order = signedOrder(), owner = "", orderType = "GTC")
    }

    private fun captorCaptureRedeem(captor: ArgumentCaptor<PositionRedeemRequest>): PositionRedeemRequest {
        captor.capture()
        return PositionRedeemRequest(emptyList())
    }
    private fun captorCapture(captor: ArgumentCaptor<NewOrderRequest>): NewOrderRequest {
        captor.capture()
        return NewOrderRequest(order = signedOrder(), owner = "", orderType = "GTC")
    }
    private suspend fun stubAccountAndMarket() {
        val account = Account(
            id = 1L,
            privateKey = "encrypted-private-key",
            walletAddress = "0xwallet",
            proxyAddress = "0xproxy",
            apiKey = "api-key",
            apiSecret = "encrypted-secret",
            apiPassphrase = "encrypted-passphrase",
            walletType = "safe"
        )
        Mockito.`when`(accountRepository.findById(1L)).thenReturn(Optional.of(account))
        Mockito.`when`(cryptoUtils.decrypt("encrypted-secret")).thenReturn("secret")
        Mockito.`when`(cryptoUtils.decrypt("encrypted-passphrase")).thenReturn("passphrase")
        Mockito.`when`(accountService.decryptPrivateKey(account)).thenReturn("private-key")
        Mockito.`when`(retrofitFactory.createClobApi("api-key", "secret", "passphrase", "0xwallet"))
            .thenReturn(api)
        Mockito.`when`(clobService.getTickSize("token")).thenReturn(Result.success(BigDecimal("0.01")))
        Mockito.`when`(clobService.getNegRisk("token")).thenReturn(Result.success(false))
        Mockito.`when`(clobService.getOrderbookByTokenId("token")).thenReturn(
            Result.success(OrderbookResponse(emptyList(), emptyList(), minOrderSize = "5"))
        )
        Mockito.`when`(signing.getExchangeContract(false)).thenReturn("0xexchange")
        Mockito.`when`(signing.getSignatureTypeForWalletType("safe")).thenReturn(2)
        Mockito.`when`(
            signing.createAndSignOrder(
                privateKey = Mockito.anyString(),
                makerAddress = Mockito.anyString(),
                tokenId = Mockito.anyString(),
                side = Mockito.anyString(),
                price = Mockito.anyString(),
                size = Mockito.anyString(),
                signatureType = Mockito.anyInt(),
                exchangeContract = Mockito.anyString(),
                tickSize = Mockito.any(),
                strictTick = Mockito.eq(true)
            )
        ).thenReturn(signedOrder())
    }

    private fun intent(side: EventMakerSide) = EventMakerQuoteIntent(
        side = side,
        price = BigDecimal("0.96"),
        shares = BigDecimal("10.00"),
        queueAheadShares = 0.0,
        reason = "test"
    )

    private fun market() = LivePaperMarket(
        conditionId = "condition",
        question = "question",
        tag = "Politics",
        volume24hr = 100_000.0,
        endTimestamp = Long.MAX_VALUE,
        tickSize = 0.01,
        outcomes = listOf("Yes", "No"),
        tokenIds = listOf("token", "other"),
        favoriteIndex = 0
    )

    private fun settlementRecord() = EventMakerSettlementRecord(
        marketId = "condition",
        favoriteTokenId = "token",
        entryOrderId = "buy-1",
        cycleExitOrderId = "settlement:condition:2000",
        settledAt = 2_000L,
        entryAt = 1_000L,
        entryPrice = BigDecimal("0.96"),
        exitPrice = BigDecimal.ONE,
        shares = BigDecimal("10.00"),
        pnl = 0.4,
        outcomeIndex = 0,
        negativeRisk = false,
        redemptionStatus = EventMakerRedemptionStatus.PENDING
    )
    private fun signedOrder() = SignedOrderObject(
        salt = 1L,
        maker = "0xproxy",
        signer = "0xwallet",
        taker = "0x0",
        tokenId = "token",
        makerAmount = "9600000",
        takerAmount = "10000000",
        side = "BUY",
        signatureType = 2,
        timestamp = "1",
        expiration = "0",
        metadata = "0x0",
        builder = "0x0",
        signature = "0xsignature"
    )

    private fun position(conditionId: String?, size: Double?) = PositionResponse(
        proxyWallet = "0xproxy",
        conditionId = conditionId,
        size = size,
        currentValue = size
    )

    private fun orderResponse(id: String) = OrderResponse(
        id = id,
        market = "condition",
        side = "BUY",
        price = "0.96",
        size = "10.00",
        filled = "0.00",
        status = "LIVE",
        createdAt = "1"
    )

    private fun openOrder(
        status: String,
        matched: String = "0.00",
        makerAddress: String = "0xproxy"
    ) = OpenOrder(
        id = "order-1",
        status = status,
        owner = "api-key",
        makerAddress = makerAddress,
        market = "condition",
        assetId = "token",
        side = "BUY",
        originalSize = "10.00",
        sizeMatched = matched,
        price = "0.96",
        outcome = "Yes",
        expiration = "0",
        orderType = "GTC",
        createdAt = 1L
    )
}
