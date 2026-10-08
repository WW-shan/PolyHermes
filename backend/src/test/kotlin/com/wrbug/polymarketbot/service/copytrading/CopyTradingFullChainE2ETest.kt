package com.wrbug.polymarketbot.service.copytrading

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.ClobMakerOrder
import com.wrbug.polymarketbot.api.ClobTrade
import com.wrbug.polymarketbot.api.ClobTradesResponse
import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.NewOrderResponse
import com.wrbug.polymarketbot.api.OpenOrder
import com.wrbug.polymarketbot.api.OrderbookEntry
import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.FilteredOrder
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.entity.ProcessedTrade
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.FilteredOrderRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.ProcessedTradeRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.service.copytrading.monitor.OnChainWsUtils
import com.wrbug.polymarketbot.service.copytrading.monitor.PolymarketActivityWsService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderActivityIngestionService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchSourceHealthService
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderLedgerService
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import com.wrbug.polymarketbot.service.copytrading.statistics.InMemoryLedgerRepos
import com.wrbug.polymarketbot.service.copytrading.statistics.OrderStatusUpdateService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationContext
import org.web3j.crypto.Credentials
import retrofit2.Response
import java.math.BigDecimal
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 跟单全链路实战测试（识别 → 风控/过滤 → 限价/数量 → 签名 → 下单 → 成交回填 → 跟单卖出）。
 *
 * 使用真实的生产代码：Activity WebSocket 消息解析、Leader 串行调度、市场上下文解析、
 * 订单 EIP-712 签名、记账（预占/核销）、卖出 FIFO 匹配与盈亏计算；
 * 只把 CLOB/链上/DB 这类外部 IO 换成可控桩，从而验证“从识别到下单再到卖出”的完整链路。
 */
class CopyTradingFullChainE2ETest {

    private val gson = Gson()

    private val leaderId = 7L
    private val copyTradingId = 3L
    private val accountId = 1L

    private val txHash = "0x" + "ab".repeat(32)
    private val marketId = "0x" + "cd".repeat(32)
    private val tokenId = "71415733604130718937169208468568525979471104242421476284166554545535981523581"
    private val leaderAddress = "0x1111111111111111111111111111111111111111"
    private val proxyAddress = "0x2222222222222222222222222222222222222222"

    private val privateKey = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val eoaAddress: String = Credentials.create(privateKey.removePrefix("0x")).address

    private val ledgerRepos = InMemoryLedgerRepos()
    private val clobApi: PolymarketClobApi = Mockito.mock(PolymarketClobApi::class.java)
    private val retrofitFactory: RetrofitFactory = Mockito.mock(RetrofitFactory::class.java)
    private val clobService: PolymarketClobService = Mockito.mock(PolymarketClobService::class.java)
    private val marketService: MarketService = Mockito.mock(MarketService::class.java)
    private val cryptoUtils: CryptoUtils = Mockito.mock(CryptoUtils::class.java)
    private val accountRepository: AccountRepository = Mockito.mock(AccountRepository::class.java)
    private val copyTradingRepository: CopyTradingRepository = Mockito.mock(CopyTradingRepository::class.java)
    private val processedTrades = ConcurrentHashMap<String, ProcessedTrade>()
    private val processedTradeRepository: ProcessedTradeRepository = Mockito.mock(ProcessedTradeRepository::class.java)

    /** 实际提交给 CLOB 的订单（顺序即下单顺序） */
    private val placedOrders = CopyOnWriteArrayList<NewOrderRequest>()

    private val account = Account(
        id = accountId,
        privateKey = "enc:privateKey",
        walletAddress = eoaAddress,
        proxyAddress = proxyAddress,
        apiKey = "api-key",
        apiSecret = "enc:apiSecret",
        apiPassphrase = "enc:apiPassphrase",
        accountName = "e2e-account",
        isEnabled = true,
        walletType = "safe"
    )

    private val copyTrading = CopyTrading(
        id = copyTradingId,
        accountId = accountId,
        leaderId = leaderId,
        enabled = true,
        copyMode = "RATIO",
        copyRatio = BigDecimal("0.1"),
        minOrderSize = BigDecimal("1"),
        maxOrderSize = BigDecimal("1000"),
        maxDailyOrders = 100,
        maxDailyLoss = BigDecimal("1000"),
        priceTolerance = BigDecimal("5"),
        delaySeconds = 0,
        supportSell = true
    )

    private lateinit var trackingService: CopyOrderTrackingService

    private fun orderbook(): OrderbookResponse = OrderbookResponse(
        bids = listOf(OrderbookEntry(price = "0.50", size = "1000")),
        asks = listOf(OrderbookEntry(price = "0.56", size = "1000")),
        tickSize = "0.01",
        negRisk = false,
        minOrderSize = "1"
    )

    private fun buildService() {
        val filterService = CopyTradingFilterService(
            clobService = clobService,
            accountService = Mockito.mock(AccountService::class.java),
            copyOrderTrackingRepository = ledgerRepos.trackingRepo,
            jsonUtils = JsonUtils(gson)
        )
        trackingService = CopyOrderTrackingService(
            copyOrderTrackingRepository = ledgerRepos.trackingRepo,
            sellMatchRecordRepository = ledgerRepos.recordRepo,
            sellMatchDetailRepository = ledgerRepos.detailRepo,
            processedTradeRepository = processedTradeRepository,
            filteredOrderRepository = Mockito.mock(FilteredOrderRepository::class.java),
            copyTradingRepository = copyTradingRepository,
            accountRepository = accountRepository,
            filterService = filterService,
            leaderRepository = Mockito.mock(LeaderRepository::class.java),
            orderSigningService = OrderSigningService(),
            blockchainService = Mockito.mock(BlockchainService::class.java),
            clobService = clobService,
            retrofitFactory = retrofitFactory,
            cryptoUtils = cryptoUtils,
            marketService = marketService,
            ledger = ledgerRepos.ledger(),
            telegramNotificationService = null
        )
        val context = Mockito.mock(ApplicationContext::class.java)
        Mockito.`when`(context.getBean(CopyOrderTrackingService::class.java)).thenReturn(trackingService)
        trackingService.setApplicationContext(context)
    }

    private suspend fun stubExternalIo() {
        Mockito.`when`(retrofitFactory.createClobApi(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenReturn(clobApi)
        Mockito.`when`(cryptoUtils.decrypt("enc:privateKey")).thenReturn(privateKey)
        Mockito.`when`(cryptoUtils.decrypt("enc:apiSecret")).thenReturn("api-secret")
        Mockito.`when`(cryptoUtils.decrypt("enc:apiPassphrase")).thenReturn("api-passphrase")
        Mockito.`when`(accountRepository.findById(accountId)).thenReturn(Optional.of(account))
        Mockito.`when`(copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)).thenReturn(listOf(copyTrading))
        Mockito.`when`(copyTradingRepository.findById(copyTradingId)).thenReturn(Optional.of(copyTrading))
        Mockito.`when`(copyTradingRepository.findByAccountId(accountId)).thenReturn(listOf(copyTrading))
        Mockito.`when`(marketService.getTakerFeeRate(Mockito.anyString())).thenReturn(BigDecimal.ZERO)
        Mockito.`when`(clobService.getOrderbookByTokenId(tokenId)).thenReturn(Result.success(orderbook()))

        Mockito.`when`(processedTradeRepository.findByLeaderIdAndLeaderTradeId(Mockito.anyLong(), Mockito.anyString()))
            .thenAnswer { inv -> processedTrades["${inv.getArgument<Long>(0)}:${inv.getArgument<String>(1)}"] }
        Mockito.`when`(processedTradeRepository.save(Mockito.any(ProcessedTrade::class.java))).thenAnswer { inv ->
            val trade = inv.getArgument<ProcessedTrade>(0)
            processedTrades["${trade.leaderId}:${trade.leaderTradeId}"] = trade
            trade
        }

        // 未匹配买入（卖出 FIFO 需要）
        Mockito.`when`(ledgerRepos.trackingRepo.findUnmatchedBuyOrdersByOutcomeIndex(Mockito.anyLong(), Mockito.anyString(), Mockito.anyInt()))
            .thenAnswer { inv ->
                val id = inv.getArgument<Long>(0)
                val market = inv.getArgument<String>(1)
                val outcome = inv.getArgument<Int>(2)
                ledgerRepos.trackings.values
                    .filter { it.copyTradingId == id && it.marketId == market && it.outcomeIndex == outcome && it.remainingQuantity.signum() > 0 }
                    .sortedBy { it.createdAt }
            }
        Mockito.`when`(ledgerRepos.trackingRepo.findByCopyTradingIdAndMarketIdAndOutcomeIndex(Mockito.anyLong(), Mockito.anyString(), Mockito.anyInt()))
            .thenAnswer { inv ->
                val id = inv.getArgument<Long>(0)
                val market = inv.getArgument<String>(1)
                val outcome = inv.getArgument<Int>(2)
                ledgerRepos.trackings.values.filter { it.copyTradingId == id && it.marketId == market && it.outcomeIndex == outcome }
            }
        Mockito.`when`(ledgerRepos.trackingRepo.countByCopyTradingIdAndMarketIdAndOutcomeIndexAndStatus(Mockito.anyLong(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyString()))
            .thenReturn(0L)
        Mockito.`when`(ledgerRepos.trackingRepo.existsByCopyTradingIdAndLeaderBuyTradeId(Mockito.anyLong(), Mockito.anyString()))
            .thenAnswer { inv ->
                val id = inv.getArgument<Long>(0)
                val tradeId = inv.getArgument<String>(1)
                ledgerRepos.trackings.values.any { it.copyTradingId == id && it.leaderBuyTradeId == tradeId }
            }
        Mockito.`when`(ledgerRepos.trackingRepo.countByCopyTradingIdAndCreatedAtGreaterThanEqual(Mockito.anyLong(), Mockito.anyLong()))
            .thenReturn(0L)
        Mockito.`when`(ledgerRepos.trackingRepo.findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(Mockito.anyString(), Mockito.anyLong()))
            .thenAnswer { inv ->
                val status = inv.getArgument<String>(0)
                val before = inv.getArgument<Long>(1)
                ledgerRepos.trackings.values.filter { it.status == status && it.createdAt < before }.sortedBy { it.createdAt }
            }
        Mockito.`when`(ledgerRepos.recordRepo.findByCopyTradingId(Mockito.anyLong())).thenAnswer { inv ->
            ledgerRepos.records.values.filter { it.copyTradingId == inv.getArgument<Long>(0) }
        }
        Mockito.`when`(ledgerRepos.recordRepo.existsByCopyTradingIdAndLeaderSellTradeId(Mockito.anyLong(), Mockito.anyString()))
            .thenAnswer { inv ->
                val id = inv.getArgument<Long>(0)
                val tradeId = inv.getArgument<String>(1)
                ledgerRepos.records.values.any { it.copyTradingId == id && it.leaderSellTradeId == tradeId }
            }
    }

    /** Kotlin 接口的非空参数会做 null 检查，Mockito 匹配器需配合真实占位实例 */
    private fun anyNewOrder(): NewOrderRequest {
        Mockito.any(NewOrderRequest::class.java)
        return NewOrderRequest(
            order = com.wrbug.polymarketbot.api.SignedOrderObject(
                salt = 0, maker = "0x0", signer = "0x0", taker = "0x0", tokenId = "0",
                makerAmount = "0", takerAmount = "0", side = "BUY", signatureType = 2,
                timestamp = "0", expiration = "0", metadata = "0x0", builder = "0x0", signature = "0x0"
            ),
            owner = "0",
            orderType = "FAK"
        )
    }

    /** 下单响应：买单返回 delayed（待确认），卖单返回 matched（成交确定） */
    private suspend fun stubClobPlacement() {
        Mockito.`when`(clobApi.createOrder(anyNewOrder())).thenAnswer { inv ->
            val request = inv.getArgument<NewOrderRequest>(0)
            placedOrders.add(request)
            if (request.order.side == "BUY") {
                Response.success(NewOrderResponse(success = true, orderId = "pending-" + request.order.side, status = "delayed"))
            } else {
                Response.success(
                    NewOrderResponse(
                        success = true,
                        orderId = "sell-order",
                        status = "matched",
                        makingAmount = "10",
                        takingAmount = "4.8"
                    )
                )
            }
        }
    }

    private fun activityMessage(side: String, price: String, size: String): String = gson.toJson(
        mapOf(
            "topic" to "activity",
            "type" to "trades",
            "timestamp" to 1700000000000L,
            "payload" to mapOf(
                "asset" to tokenId,
                "conditionId" to marketId,
                "outcome" to "Up",
                "outcomeIndex" to 0,
                "side" to side,
                "price" to price,
                "size" to size,
                "timestamp" to 1700000000,
                "transactionHash" to txHash,
                "proxyWallet" to leaderAddress
            )
        )
    )

    private fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(condition(), "等待条件超时")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> provider(value: T): ObjectProvider<T> {
        val p = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<T>
        Mockito.`when`(p.getIfAvailable()).thenReturn(value)
        return p
    }

    @Test
    fun `activity ws buy then reconcile then leader sell completes the whole chain`() = runBlocking {
        buildService()
        stubExternalIo()
        stubClobPlacement()

        val activityWs = PolymarketActivityWsService(
            copyOrderTrackingService = trackingService,
            leaderRepository = Mockito.mock(LeaderRepository::class.java),
            researchIngestionProvider = provider(Mockito.mock(LeaderActivityIngestionService::class.java)),
            researchSourceHealthProvider = provider(Mockito.mock(LeaderResearchSourceHealthService::class.java)),
            researchGlobalCaptureEnabled = false,
            researchGlobalCaptureMaxWritesPerMinute = 120
        )
        activityWs.aggregationWindowMs = 50

        val addressesField = PolymarketActivityWsService::class.java.getDeclaredField("monitoredAddresses")
        addressesField.isAccessible = true
        (addressesField.get(activityWs) as ConcurrentHashMap<String, Long>)[leaderAddress] = leaderId
        val handleMessage = PolymarketActivityWsService::class.java.getDeclaredMethod("handleMessage", String::class.java)
        handleMessage.isAccessible = true

        // 1) Leader 买入 100 股 @0.55 → 跟单 10 股（ratio 0.1），限价 0.55×1.05=0.5775 → 0.57
        handleMessage.invoke(activityWs, activityMessage("BUY", "0.55", "100"))
        awaitCondition { placedOrders.size == 1 }

        val buyOrder = placedOrders[0].order
        assertEquals("BUY", buyOrder.side)
        assertEquals(proxyAddress, buyOrder.maker)
        assertEquals(eoaAddress, buyOrder.signer)
        assertEquals(tokenId, buyOrder.tokenId)
        assertEquals(OrderSigningService.SIGNATURE_TYPE_POLY_GNOSIS_SAFE, buyOrder.signatureType)
        // 10 股 × 0.57 = 5.7 USDC
        assertEquals("5700000", buyOrder.makerAmount)
        assertEquals("10000000", buyOrder.takerAmount)
        assertTrue(buyOrder.signature.startsWith("0x") && buyOrder.signature.length == 132, "签名应为 65 字节")

        val pending = ledgerRepos.trackings.values.single()
        assertEquals(CopyOrderTracking.STATUS_PENDING, pending.status)
        assertEquals(copyTradingId, pending.copyTradingId)
        assertEquals(marketId, pending.marketId)
        assertEquals(0, pending.outcomeIndex)
        assertEquals(OnChainWsUtils.buildTradeId(txHash, tokenId, "BUY"), pending.leaderBuyTradeId)
        assertEquals(0, BigDecimal("100").compareTo(pending.leaderBuyQuantity))
        assertEquals(0, BigDecimal("0.57").compareTo(pending.price))
        // 待确认记录需“变老”才能被轮询核对（生产上下单后 5 秒才开始核对）
        ledgerRepos.trackings[pending.id!!] = pending.copy(createdAt = System.currentTimeMillis() - 60_000)

        // 2) 轮询回填：订单终态 + 成交明细 → 真实均价 0.56
        val buyHash = buyOrder.let { OrderSigningService().computeOrderHash(it, OrderSigningService().getExchangeContract(false)) }
        Mockito.`when`(clobApi.getOrder(buyHash)).thenReturn(
            Response.success(
                OpenOrder(
                    id = buyHash,
                    status = "MATCHED",
                    owner = "api-key",
                    makerAddress = proxyAddress,
                    market = marketId,
                    assetId = tokenId,
                    side = "BUY",
                    originalSize = "10",
                    sizeMatched = "10",
                    price = "0.57",
                    outcome = "Up",
                    expiration = "0",
                    orderType = "FAK",
                    associateTrades = listOf("t1"),
                    createdAt = System.currentTimeMillis()
                )
            )
        )
        Mockito.`when`(clobApi.getClobTrades(id = "t1", makerAddress = proxyAddress)).thenReturn(
            Response.success(
                ClobTradesResponse(
                    data = listOf(
                        ClobTrade(
                            id = "t1",
                            takerOrderId = buyHash,
                            market = marketId,
                            assetId = tokenId,
                            side = "BUY",
                            size = "10",
                            price = "0.56",
                            status = "CONFIRMED",
                            makerOrders = listOf(
                                ClobMakerOrder(orderId = buyHash, matchedAmount = "10", price = "0.56", assetId = tokenId, side = "BUY")
                            ),
                            traderSide = "TAKER"
                        )
                    )
                )
            )
        )

        val statusService = OrderStatusUpdateService(
            sellMatchRecordRepository = ledgerRepos.recordRepo,
            sellMatchDetailRepository = ledgerRepos.detailRepo,
            copyTradingRepository = copyTradingRepository,
            accountRepository = accountRepository,
            copyOrderTrackingRepository = ledgerRepos.trackingRepo,
            leaderRepository = Mockito.mock(LeaderRepository::class.java),
            retrofitFactory = retrofitFactory,
            cryptoUtils = cryptoUtils,
            trackingService = trackingService,
            marketService = marketService,
            telegramNotificationService = null,
            blockchainService = Mockito.mock(BlockchainService::class.java),
            ledger = ledgerRepos.ledger()
        )
        runBlocking { statusService.reconcilePendingBuyOrders() }

        val filled = ledgerRepos.trackings.values.single()
        assertEquals(CopyOrderTracking.STATUS_FILLED, filled.status)
        assertEquals(0, BigDecimal("10").compareTo(filled.quantity))
        assertEquals(0, BigDecimal("10").compareTo(filled.remainingQuantity))
        assertEquals(0, BigDecimal("0.56").compareTo(filled.price), "必须回填真实成交均价而不是下单限价")

        // 3) Leader 卖出 100 股 → 跟单卖出 10 股，bestBid 0.50 × 0.95 = 0.475 → 0.48
        handleMessage.invoke(activityWs, activityMessage("SELL", "0.50", "100"))
        // 下单与结算在同一协程内顺序执行：等到卖单提交且已按成交核销
        awaitCondition {
            placedOrders.size == 2 &&
                ledgerRepos.records.values.any {
                    it.fillStatus == com.wrbug.polymarketbot.entity.SellMatchRecord.FILL_STATUS_FILLED
                }
        }

        val sellOrder = placedOrders[1].order
        assertEquals("SELL", sellOrder.side)
        assertEquals("10000000", sellOrder.makerAmount)   // 卖出 10 股
        assertEquals("4800000", sellOrder.takerAmount)   // 10 × 0.48

        val matched = ledgerRepos.trackings.values.single()
        assertEquals(CopyOrderTracking.STATUS_FULLY_MATCHED, matched.status)
        assertEquals(0, matched.remainingQuantity.compareTo(BigDecimal.ZERO))
        assertEquals(0, BigDecimal("10").compareTo(matched.matchedQuantity))

        val sellRecord = ledgerRepos.records.values.single()
        assertEquals(com.wrbug.polymarketbot.entity.SellMatchRecord.FILL_STATUS_FILLED, sellRecord.fillStatus)
        assertEquals(0, BigDecimal("10").compareTo(sellRecord.totalMatchedQuantity))
        assertEquals(0, BigDecimal("0.48").compareTo(sellRecord.sellPrice))
        // (0.48 - 0.56) × 10 = -0.80
        assertEquals(0, BigDecimal("-0.8").compareTo(sellRecord.totalRealizedPnl))
        assertEquals(1, ledgerRepos.details.size)

        // 两笔交易都已标记处理（跨来源去重依赖它）
        assertEquals(2, processedTrades.size)
        assertNotNull(processedTrades["$leaderId:${OnChainWsUtils.buildTradeId(txHash, tokenId, "BUY")}"])
        assertNotNull(processedTrades["$leaderId:${OnChainWsUtils.buildTradeId(txHash, tokenId, "SELL")}"])

        activityWs.destroy()
    }

    // ---------------------------------------------------------------------
    // 跨来源去重：同一笔链上交易（activity WS 与 on-chain WS 双路推送）只允许下一次单
    // ---------------------------------------------------------------------

    private val onchainTx = "0xf4d0fdf3c11a8dd661f1f802955be7bac93ce9e44d6054f7035bb46d73db94b0"
    private val onchainLeaderAddress = "0x1D1AdE627D0bB0205758580B20D808573E720DF6"
    private val onchainTokenId = "77073345465811379192787177281719442316070301560733975710653689726674114112036"

    private fun stubGammaFor(token: String) = runBlocking {
        val gamma = Mockito.mock(com.wrbug.polymarketbot.api.PolymarketGammaApi::class.java)
        Mockito.`when`(gamma.listMarkets(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(
            Response.success(
                listOf(
                    com.wrbug.polymarketbot.api.MarketResponse(
                        conditionId = marketId,
                        outcomes = "[\"Up\",\"Down\"]",
                        clobTokenIds = "[\"$token\",\"82320564380274369670394295556788957448871625488159004567616219313132556758615\"]"
                    )
                )
            )
        )
        Mockito.`when`(retrofitFactory.createGammaApi()).thenReturn(gamma)
    }

    private fun anyJsonRpc(): com.wrbug.polymarketbot.api.JsonRpcRequest {
        Mockito.any(com.wrbug.polymarketbot.api.JsonRpcRequest::class.java)
        return com.wrbug.polymarketbot.api.JsonRpcRequest(method = "x", params = emptyList())
    }

    private fun stubOnchainReceipt(): com.wrbug.polymarketbot.api.EthereumRpcApi = runBlocking {
        val receipt = com.google.gson.JsonParser
            .parseString(this::class.java.getResource("/onchain/trade_taker_multi_fill.json")!!.readText())
            .asJsonObject.get("result")
        val rpc = Mockito.mock(com.wrbug.polymarketbot.api.EthereumRpcApi::class.java)
        Mockito.`when`(rpc.call(anyJsonRpc())).thenAnswer { inv ->
            val req = inv.arguments[0] as com.wrbug.polymarketbot.api.JsonRpcRequest
            if (req.method == "eth_getTransactionReceipt") {
                Response.success(com.wrbug.polymarketbot.api.JsonRpcResponse(result = receipt))
            } else {
                Response.success(com.wrbug.polymarketbot.api.JsonRpcResponse(result = com.google.gson.JsonParser.parseString("{\"timestamp\":\"0x1\"}")))
            }
        }
        rpc
    }

    private fun onchainService(): com.wrbug.polymarketbot.service.copytrading.monitor.OnChainWsService {
        val service = com.wrbug.polymarketbot.service.copytrading.monitor.OnChainWsService(
            unifiedOnChainWsService = Mockito.mock(com.wrbug.polymarketbot.service.copytrading.monitor.UnifiedOnChainWsService::class.java),
            retrofitFactory = retrofitFactory,
            copyOrderTrackingService = trackingService,
            leaderRepository = Mockito.mock(LeaderRepository::class.java)
        )
        service.maxProcessAttempts = 1
        service.retryDelayMs = 0
        return service
    }

    private fun activityMessageFor(
        side: String,
        price: String,
        size: String,
        tx: String?,
        token: String,
        leader: String
    ): String {
        val payload = mutableMapOf<String, Any?>(
            "asset" to token,
            "conditionId" to marketId,
            "outcome" to "Up",
            "outcomeIndex" to 0,
            "side" to side,
            "price" to price,
            "size" to size,
            "timestamp" to 1700000000,
            "proxyWallet" to leader
        )
        if (tx != null) payload["transactionHash"] = tx
        return gson.toJson(mapOf("topic" to "activity", "type" to "trades", "timestamp" to 1700000000000L, "payload" to payload))
    }

    private fun newActivityWs(): PolymarketActivityWsService {
        val ws = PolymarketActivityWsService(
            copyOrderTrackingService = trackingService,
            leaderRepository = Mockito.mock(LeaderRepository::class.java),
            researchIngestionProvider = provider(Mockito.mock(LeaderActivityIngestionService::class.java)),
            researchSourceHealthProvider = provider(Mockito.mock(LeaderResearchSourceHealthService::class.java)),
            researchGlobalCaptureEnabled = false,
            researchGlobalCaptureMaxWritesPerMinute = 120
        )
        ws.aggregationWindowMs = 50
        return ws
    }

    @Suppress("UNCHECKED_CAST")
    private fun monitor(ws: PolymarketActivityWsService, address: String, leaderId: Long) {
        val field = PolymarketActivityWsService::class.java.getDeclaredField("monitoredAddresses")
        field.isAccessible = true
        (field.get(ws) as ConcurrentHashMap<String, Long>)[address.lowercase()] = leaderId
    }

    private fun feed(ws: PolymarketActivityWsService, message: String) {
        val handle = PolymarketActivityWsService::class.java.getDeclaredMethod("handleMessage", String::class.java)
        handle.isAccessible = true
        handle.invoke(ws, message)
    }

    @Test
    fun `same tx delivered by both sources places exactly one order`() = runBlocking {
        buildService()
        stubExternalIo()
        stubClobPlacement()
        stubGammaFor(onchainTokenId)
        Mockito.`when`(clobService.getOrderbookByTokenId(onchainTokenId)).thenReturn(Result.success(orderbook()))

        val onchain = onchainService()
        onchain.addLeader(Leader(id = leaderId, leaderAddress = onchainLeaderAddress))
        val rpc = stubOnchainReceipt()

        // 链上兜底路径先到
        onchain.handleLeaderTransaction(leaderId, onchainTx, okhttp3.OkHttpClient(), rpc)
        awaitCondition { placedOrders.size == 1 }

        // Activity 快路径随后推送同一笔成交 → 必须被去重，不能下第二单
        val ws = newActivityWs()
        monitor(ws, onchainLeaderAddress, leaderId)
        feed(ws, activityMessageFor("BUY", "0.55", "66130.09", onchainTx, onchainTokenId, onchainLeaderAddress))

        Thread.sleep(600)
        assertEquals(1, placedOrders.size, "同一笔链上交易只能下一次单")
        assertEquals(1, ledgerRepos.trackings.size)

        val placed = placedOrders.single().order
        assertEquals(OnChainWsUtils.buildTradeId(onchainTx, onchainTokenId, "BUY"), ledgerRepos.trackings.values.single().leaderBuyTradeId)
        assertTrue(placed.signature.length == 132)

        onchain.stop()
        ws.destroy()
    }

    @Test
    fun `activity message without tx hash is not traded blindly`() = runBlocking {
        buildService()
        stubExternalIo()
        stubClobPlacement()

        val ws = newActivityWs()
        monitor(ws, leaderAddress, leaderId)
        feed(ws, activityMessageFor("BUY", "0.55", "100", null, tokenId, leaderAddress))

        Thread.sleep(600)
        assertEquals(0, placedOrders.size, "缺少 txHash 的成交无法跨来源去重，不能直接下单")
        assertEquals(0, ledgerRepos.trackings.size)

        ws.destroy()
    }
}
