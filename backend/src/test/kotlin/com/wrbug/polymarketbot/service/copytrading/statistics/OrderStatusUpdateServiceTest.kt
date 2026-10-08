package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.OpenOrder
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.math.BigDecimal
import java.util.Optional

class OrderStatusUpdateServiceTest {

    private val sellMatchRecordRepository = Mockito.mock(SellMatchRecordRepository::class.java)
    private val sellMatchDetailRepository = Mockito.mock(SellMatchDetailRepository::class.java)
    private val copyTradingRepository = Mockito.mock(CopyTradingRepository::class.java)
    private val accountRepository = Mockito.mock(AccountRepository::class.java)
    private val copyOrderTrackingRepository = Mockito.mock(CopyOrderTrackingRepository::class.java)
    private val leaderRepository = Mockito.mock(LeaderRepository::class.java)
    private val retrofitFactory = Mockito.mock(RetrofitFactory::class.java)
    private val cryptoUtils = Mockito.mock(CryptoUtils::class.java)
    private val trackingService = Mockito.mock(CopyOrderTrackingService::class.java)
    private val marketService = Mockito.mock(MarketService::class.java)
    private val blockchainService = Mockito.mock(BlockchainService::class.java)
    private val ledger = Mockito.mock(CopyOrderLedgerService::class.java)

    private val service = OrderStatusUpdateService(
        sellMatchRecordRepository = sellMatchRecordRepository,
        sellMatchDetailRepository = sellMatchDetailRepository,
        copyTradingRepository = copyTradingRepository,
        accountRepository = accountRepository,
        copyOrderTrackingRepository = copyOrderTrackingRepository,
        leaderRepository = leaderRepository,
        retrofitFactory = retrofitFactory,
        cryptoUtils = cryptoUtils,
        trackingService = trackingService,
        marketService = marketService,
        telegramNotificationService = null,
        blockchainService = blockchainService,
        ledger = ledger
    )

    @Test
    fun `terminal pending buy records actual execution price instead of order limit price`() = runBlocking {
        val order = pendingBuy()
        val account = account()
        val clobApi = Mockito.mock(PolymarketClobApi::class.java)
        val detail = OpenOrder(
            id = order.buyOrderId,
            status = "MATCHED",
            owner = "owner",
            makerAddress = account.proxyAddress,
            market = order.marketId,
            assetId = "123",
            side = "BUY",
            originalSize = "10",
            sizeMatched = "10",
            price = "0.50",
            outcome = "Yes",
            expiration = "0",
            orderType = "FAK",
            associateTrades = listOf("trade-1"),
            createdAt = 1L
        )

        Mockito.`when`(
            copyOrderTrackingRepository.findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                Mockito.anyString(),
                Mockito.anyLong()
            )
        ).thenReturn(listOf(order))
        Mockito.`when`(copyTradingRepository.findById(1L)).thenReturn(Optional.of(CopyTrading(id = 1L, accountId = 1L, leaderId = 1L)))
        Mockito.`when`(accountRepository.findById(1L)).thenReturn(Optional.of(account))
        Mockito.`when`(cryptoUtils.decrypt("enc-secret")).thenReturn("secret")
        Mockito.`when`(cryptoUtils.decrypt("enc-passphrase")).thenReturn("passphrase")
        Mockito.`when`(
            retrofitFactory.createClobApi("key", "secret", "passphrase", account.walletAddress)
        ).thenReturn(clobApi)
        Mockito.`when`(clobApi.getOrder(order.buyOrderId)).thenReturn(Response.success(detail))
        Mockito.`when`(
            trackingService.queryExecutionPrice(order.buyOrderId, clobApi, account.proxyAddress)
        ).thenReturn(BigDecimal("0.47"))

        service.reconcilePendingBuyOrders()

        Mockito.verify(ledger).confirmBuyFill(order.id!!, BigDecimal("10"), BigDecimal("0.47"))
        Unit
    }

    @Test
    fun `invalid terminal buy fill quantity is not treated as zero fill`() = runBlocking {
        val order = pendingBuy()
        val account = account()
        val clobApi = Mockito.mock(PolymarketClobApi::class.java)
        val detail = OpenOrder(
            id = order.buyOrderId,
            status = "MATCHED",
            owner = "owner",
            makerAddress = account.proxyAddress,
            market = order.marketId,
            assetId = "123",
            side = "BUY",
            originalSize = "10",
            sizeMatched = "not-a-number",
            price = "0.50",
            outcome = "Yes",
            expiration = "0",
            orderType = "FAK",
            createdAt = 1L
        )

        Mockito.`when`(
            copyOrderTrackingRepository.findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                Mockito.anyString(), Mockito.anyLong()
            )
        ).thenReturn(listOf(order))
        Mockito.`when`(copyTradingRepository.findById(1L)).thenReturn(Optional.of(CopyTrading(id = 1L, accountId = 1L, leaderId = 1L)))
        Mockito.`when`(accountRepository.findById(1L)).thenReturn(Optional.of(account))
        Mockito.`when`(cryptoUtils.decrypt("enc-secret")).thenReturn("secret")
        Mockito.`when`(cryptoUtils.decrypt("enc-passphrase")).thenReturn("passphrase")
        Mockito.`when`(retrofitFactory.createClobApi("key", "secret", "passphrase", account.walletAddress)).thenReturn(clobApi)
        Mockito.`when`(clobApi.getOrder(order.buyOrderId)).thenReturn(Response.success(detail))

        service.reconcilePendingBuyOrders()

        Mockito.verify(ledger).markBuyUnconfirmed(order.id!!)
        Mockito.verify(ledger, Mockito.never()).confirmBuyFill(order.id!!, BigDecimal.ZERO, BigDecimal("0.50"))
        Unit
    }

    @Test
    fun `invalid terminal sell fill quantity keeps reserved shares pending`() = runBlocking {
        val account = account()
        val clobApi = Mockito.mock(PolymarketClobApi::class.java)
        val record = com.wrbug.polymarketbot.entity.SellMatchRecord(
            id = 2L,
            copyTradingId = 1L,
            sellOrderId = "0xsell",
            leaderSellTradeId = "leader-sell",
            marketId = "0xmarket",
            side = "0",
            totalMatchedQuantity = BigDecimal("10"),
            sellPrice = BigDecimal("0.50"),
            totalRealizedPnl = BigDecimal.ZERO,
            fillStatus = com.wrbug.polymarketbot.entity.SellMatchRecord.FILL_STATUS_PENDING,
            createdAt = 1L
        )
        val detail = OpenOrder(
            id = record.sellOrderId,
            status = "MATCHED",
            owner = "owner",
            makerAddress = account.proxyAddress,
            market = record.marketId,
            assetId = "123",
            side = "SELL",
            originalSize = "10",
            sizeMatched = "not-a-number",
            price = "0.50",
            outcome = "Yes",
            expiration = "0",
            orderType = "FAK",
            createdAt = 1L
        )

        Mockito.`when`(
            sellMatchRecordRepository.findTop200ByFillStatusAndCreatedAtBeforeOrderByIdAsc(
                Mockito.anyString(), Mockito.anyLong()
            )
        ).thenReturn(listOf(record))
        Mockito.`when`(copyTradingRepository.findById(1L)).thenReturn(Optional.of(CopyTrading(id = 1L, accountId = 1L, leaderId = 1L)))
        Mockito.`when`(accountRepository.findById(1L)).thenReturn(Optional.of(account))
        Mockito.`when`(cryptoUtils.decrypt("enc-secret")).thenReturn("secret")
        Mockito.`when`(cryptoUtils.decrypt("enc-passphrase")).thenReturn("passphrase")
        Mockito.`when`(retrofitFactory.createClobApi("key", "secret", "passphrase", account.walletAddress)).thenReturn(clobApi)
        Mockito.`when`(clobApi.getOrder(record.sellOrderId)).thenReturn(Response.success(detail))

        service.reconcilePendingSellOrders()

        Mockito.verify(ledger).updateSellRecordState(
            record.id!!,
            fillStatus = com.wrbug.polymarketbot.entity.SellMatchRecord.FILL_STATUS_UNCONFIRMED
        )
        Mockito.verify(ledger, Mockito.never()).settleSell(record.id!!, BigDecimal.ZERO, null, null)
        Unit
    }

    @Test
    fun `filled buy price is corrected from execution trades before notification`() = runBlocking {
        val order = pendingBuy().copy(
            quantity = BigDecimal("10"),
            remainingQuantity = BigDecimal("10"),
            status = CopyOrderTracking.STATUS_FILLED,
            notificationSent = false
        )
        val account = account()
        val clobApi = Mockito.mock(PolymarketClobApi::class.java)
        val detail = OpenOrder(
            id = order.buyOrderId,
            status = "MATCHED",
            owner = "owner",
            makerAddress = account.proxyAddress,
            market = order.marketId,
            assetId = "123",
            side = "BUY",
            originalSize = "10",
            sizeMatched = "10",
            price = "0.50",
            outcome = "Yes",
            expiration = "0",
            orderType = "FAK",
            associateTrades = listOf("trade-1"),
            createdAt = 1L
        )
        val corrected = order.copy(price = BigDecimal("0.47"))

        Mockito.`when`(
            copyOrderTrackingRepository.findTop200ByNotificationSentFalseAndStatusNotInOrderByIdAsc(
                listOf(CopyOrderTracking.STATUS_PENDING, CopyOrderTracking.STATUS_UNCONFIRMED)
            )
        ).thenReturn(listOf(order))
        Mockito.`when`(copyTradingRepository.findById(1L)).thenReturn(Optional.of(CopyTrading(id = 1L, accountId = 1L, leaderId = 1L)))
        Mockito.`when`(accountRepository.findById(1L)).thenReturn(Optional.of(account))
        Mockito.`when`(cryptoUtils.decrypt("enc-secret")).thenReturn("secret")
        Mockito.`when`(cryptoUtils.decrypt("enc-passphrase")).thenReturn("passphrase")
        Mockito.`when`(
            retrofitFactory.createClobApi("key", "secret", "passphrase", account.walletAddress)
        ).thenReturn(clobApi)
        Mockito.`when`(clobApi.getOrder(order.buyOrderId)).thenReturn(Response.success(detail))
        Mockito.`when`(
            trackingService.queryExecutionPrice(order.buyOrderId, clobApi, account.proxyAddress)
        ).thenReturn(BigDecimal("0.47"))
        Mockito.`when`(ledger.updateBuyPrice(order.id!!, BigDecimal("0.47"), null)).thenReturn(corrected)
        Mockito.`when`(ledger.markBuyNotificationSent(order.id!!)).thenReturn(corrected)

        service.updatePendingBuyOrders()

        Mockito.verify(ledger).updateBuyPrice(order.id!!, BigDecimal("0.47"), null)
        Unit
    }

    @Test
    fun `filled buy with unavailable execution price remains retryable`() = runBlocking {
        val order = pendingBuy().copy(
            quantity = BigDecimal("10"),
            remainingQuantity = BigDecimal("10"),
            status = CopyOrderTracking.STATUS_FILLED,
            notificationSent = false
        )
        val account = account()
        val clobApi = Mockito.mock(PolymarketClobApi::class.java)
        val detail = OpenOrder(
            id = order.buyOrderId,
            status = "MATCHED",
            owner = "owner",
            makerAddress = account.proxyAddress,
            market = order.marketId,
            assetId = "123",
            side = "BUY",
            originalSize = "10",
            sizeMatched = "10",
            price = "0.50",
            outcome = "Yes",
            expiration = "0",
            orderType = "FAK",
            associateTrades = listOf("trade-1"),
            createdAt = 1L
        )

        Mockito.`when`(
            copyOrderTrackingRepository.findTop200ByNotificationSentFalseAndStatusNotInOrderByIdAsc(
                listOf(CopyOrderTracking.STATUS_PENDING, CopyOrderTracking.STATUS_UNCONFIRMED)
            )
        ).thenReturn(listOf(order))
        Mockito.`when`(copyTradingRepository.findById(1L)).thenReturn(Optional.of(CopyTrading(id = 1L, accountId = 1L, leaderId = 1L)))
        Mockito.`when`(accountRepository.findById(1L)).thenReturn(Optional.of(account))
        Mockito.`when`(cryptoUtils.decrypt("enc-secret")).thenReturn("secret")
        Mockito.`when`(cryptoUtils.decrypt("enc-passphrase")).thenReturn("passphrase")
        Mockito.`when`(
            retrofitFactory.createClobApi("key", "secret", "passphrase", account.walletAddress)
        ).thenReturn(clobApi)
        Mockito.`when`(clobApi.getOrder(order.buyOrderId)).thenReturn(Response.success(detail))
        Mockito.`when`(
            trackingService.queryExecutionPrice(order.buyOrderId, clobApi, account.proxyAddress)
        ).thenReturn(null)

        service.updatePendingBuyOrders()

        Mockito.verify(ledger, Mockito.never()).markBuyNotificationSent(order.id!!)
        Mockito.verify(ledger, Mockito.never()).updateBuyPrice(order.id!!, BigDecimal("0.47"), null)
        Unit
    }

    @Test
    fun `unconfirmed sell recheck settles when order becomes visible again`() = runBlocking {
        val account = account()
        val clobApi = Mockito.mock(PolymarketClobApi::class.java)
        val record = unconfirmedSell()
        val detail = OpenOrder(
            id = record.sellOrderId,
            status = "MATCHED",
            owner = "owner",
            makerAddress = account.proxyAddress,
            market = record.marketId,
            assetId = "123",
            side = "SELL",
            originalSize = "10",
            sizeMatched = "10",
            price = "0.50",
            outcome = "Yes",
            expiration = "0",
            orderType = "FAK",
            createdAt = 1L
        )

        Mockito.`when`(
            sellMatchRecordRepository.findTop200ByFillStatusAndCreatedAtBeforeOrderByIdAsc(
                Mockito.anyString(), Mockito.anyLong()
            )
        ).thenReturn(listOf(record))
        Mockito.`when`(copyTradingRepository.findById(1L)).thenReturn(Optional.of(CopyTrading(id = 1L, accountId = 1L, leaderId = 1L)))
        Mockito.`when`(accountRepository.findById(1L)).thenReturn(Optional.of(account))
        Mockito.`when`(cryptoUtils.decrypt("enc-secret")).thenReturn("secret")
        Mockito.`when`(cryptoUtils.decrypt("enc-passphrase")).thenReturn("passphrase")
        Mockito.`when`(retrofitFactory.createClobApi("key", "secret", "passphrase", account.walletAddress)).thenReturn(clobApi)
        Mockito.`when`(clobApi.getOrder(record.sellOrderId)).thenReturn(Response.success(detail))
        Mockito.`when`(marketService.getTakerFeeRate(record.marketId)).thenReturn(BigDecimal.ZERO)

        service.recheckUnconfirmedSellOrders()

        Mockito.verify(ledger).settleSell(record.id!!, BigDecimal("10"), null, BigDecimal.ZERO)
        Unit
    }

    @Test
    fun `unconfirmed sell recheck keeps reservation and backs off when still not found`() = runBlocking {
        val account = account()
        val clobApi = Mockito.mock(PolymarketClobApi::class.java)
        val record = unconfirmedSell()

        Mockito.`when`(
            sellMatchRecordRepository.findTop200ByFillStatusAndCreatedAtBeforeOrderByIdAsc(
                Mockito.anyString(), Mockito.anyLong()
            )
        ).thenReturn(listOf(record))
        Mockito.`when`(copyTradingRepository.findById(1L)).thenReturn(Optional.of(CopyTrading(id = 1L, accountId = 1L, leaderId = 1L)))
        Mockito.`when`(accountRepository.findById(1L)).thenReturn(Optional.of(account))
        Mockito.`when`(cryptoUtils.decrypt("enc-secret")).thenReturn("secret")
        Mockito.`when`(cryptoUtils.decrypt("enc-passphrase")).thenReturn("passphrase")
        Mockito.`when`(retrofitFactory.createClobApi("key", "secret", "passphrase", account.walletAddress)).thenReturn(clobApi)
        Mockito.`when`(clobApi.getOrder(record.sellOrderId)).thenReturn(Response.error(404, "".toResponseBody()))

        service.recheckUnconfirmedSellOrders()

        Mockito.verify(ledger).updateSellRecordState(record.id!!, incrementPriceQueryAttempts = true)
        Mockito.verify(ledger, Mockito.never()).settleSell(record.id!!, BigDecimal("10"), null, BigDecimal.ZERO)
        Unit
    }

    private fun unconfirmedSell() = com.wrbug.polymarketbot.entity.SellMatchRecord(
        id = 2L,
        copyTradingId = 1L,
        sellOrderId = "0xsell",
        leaderSellTradeId = "leader-sell",
        marketId = "0xmarket",
        side = "0",
        totalMatchedQuantity = BigDecimal("10"),
        sellPrice = BigDecimal("0.50"),
        totalRealizedPnl = BigDecimal.ZERO,
        fillStatus = com.wrbug.polymarketbot.entity.SellMatchRecord.FILL_STATUS_UNCONFIRMED,
        createdAt = 1L
    )

    private fun pendingBuy() = CopyOrderTracking(
        id = 1L,
        copyTradingId = 1L,
        accountId = 1L,
        leaderId = 1L,
        marketId = "0xmarket",
        side = "0",
        outcomeIndex = 0,
        buyOrderId = "0xabc",
        leaderBuyTradeId = "leader-trade",
        leaderBuyQuantity = BigDecimal("10"),
        quantity = BigDecimal.ZERO,
        price = BigDecimal("0.50"),
        remainingQuantity = BigDecimal.ZERO,
        status = CopyOrderTracking.STATUS_PENDING,
        source = "test",
        requestedQuantity = BigDecimal("10")
    )

    private fun account() = Account(
        id = 1L,
        privateKey = "enc-private-key",
        walletAddress = "0x1111111111111111111111111111111111111111",
        proxyAddress = "0x2222222222222222222222222222222222222222",
        apiKey = "key",
        apiSecret = "enc-secret",
        apiPassphrase = "enc-passphrase"
    )
}
