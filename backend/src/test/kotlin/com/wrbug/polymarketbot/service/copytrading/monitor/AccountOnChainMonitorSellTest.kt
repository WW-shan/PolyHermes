package com.wrbug.polymarketbot.service.copytrading.monitor

import com.google.gson.JsonParser
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.api.JsonRpcResponse
import com.wrbug.polymarketbot.api.MarketResponse
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.entity.SellMatchRecord
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.ApplicationContext
import retrofit2.Response
import java.math.BigDecimal
import java.util.Optional

/**
 * P0-S3 回归：账户链上监听以 OrderFilled 为准，
 * 本系统订单（orderHash = SellMatchRecord.sellOrderId）跳过；外部卖出按剩余量在所有配置间分摊一次。
 */
class AccountOnChainMonitorSellTest {

    private val proxy = "0xf1404010a21a61c1f5693beee65285273be47cd8"
    private val sellOrderHash = "0x70e30c89ea1845d4ed7c792153d837be2f9bf1c2b742414efa6d9ec412e56801"
    private val tokenUp = "77073345465811379192787177281719442316070301560733975710653689726674114112036"
    private val txHash = "0xf4d0fdf3c11a8dd661f1f802955be7bac93ce9e44d6054f7035bb46d73db94b0"

    private val retrofitFactory = Mockito.mock(RetrofitFactory::class.java)
    private val copyTradingRepository = Mockito.mock(CopyTradingRepository::class.java)
    private val trackingRepository = Mockito.mock(CopyOrderTrackingRepository::class.java)
    private val recordRepository = Mockito.mock(SellMatchRecordRepository::class.java)
    private val detailRepository = Mockito.mock(SellMatchDetailRepository::class.java)

    private val service = AccountOnChainMonitorService(
        Mockito.mock(UnifiedOnChainWsService::class.java),
        retrofitFactory,
        Mockito.mock(AccountRepository::class.java),
        copyTradingRepository,
        trackingRepository,
        recordRepository,
        detailRepository
    ).also { svc ->
        val ctx = Mockito.mock(ApplicationContext::class.java)
        Mockito.`when`(ctx.getBean(AccountOnChainMonitorService::class.java)).thenReturn(svc)
        svc.setApplicationContext(ctx)
        svc.selfOrderRecheckDelayMs = 0
    }

    private val account = Account(id = 7L, privateKey = "x", walletAddress = "0x1", proxyAddress = proxy, walletType = "safe")

    private val savedRecords = mutableListOf<SellMatchRecord>()

    private fun setup(
        systemOrderIds: List<String>,
        trackings: Map<Long, List<CopyOrderTracking>>,
        disabledConfigIds: Set<Long> = emptySet()
    ) = runBlocking {
        val receipt = JsonParser.parseString(this::class.java.getResource("/onchain/trade_taker_multi_fill.json")!!.readText())
            .asJsonObject.get("result")
        val gamma = Mockito.mock(PolymarketGammaApi::class.java)
        Mockito.`when`(gamma.listMarkets(null, listOf(tokenUp), null, null)).thenReturn(
            Response.success(listOf(MarketResponse(conditionId = "0xmarket", outcomes = "[\"Up\",\"Down\"]", clobTokenIds = "[\"$tokenUp\",\"1\"]")))
        )
        Mockito.`when`(retrofitFactory.createGammaApi()).thenReturn(gamma)
        Mockito.`when`(recordRepository.findBySellOrderIdIn(Mockito.anyCollection())).thenAnswer { inv ->
            val ids = (inv.arguments[0] as Collection<*>).map { it.toString().lowercase() }
            systemOrderIds.filter { it.lowercase() in ids }.map { id ->
                SellMatchRecord(copyTradingId = 1, sellOrderId = id, leaderSellTradeId = "t", marketId = "0xmarket", side = "0",
                    totalMatchedQuantity = BigDecimal.ONE, sellPrice = BigDecimal.ONE, totalRealizedPnl = BigDecimal.ZERO)
            }
        }
        Mockito.`when`(copyTradingRepository.findByAccountId(7L)).thenReturn(
            trackings.keys.map { CopyTrading(id = it, accountId = 7L, leaderId = it, enabled = it !in disabledConfigIds) }
        )
        trackings.forEach { (ctId, list) ->
            Mockito.`when`(trackingRepository.findByCopyTradingId(ctId)).thenReturn(list)
            list.forEach { t -> Mockito.`when`(trackingRepository.findById(t.id!!)).thenReturn(Optional.of(t)) }
        }
        Mockito.`when`(recordRepository.save(Mockito.any(SellMatchRecord::class.java) ?: dummyRecord())).thenAnswer {
            val r = (it.arguments[0] as SellMatchRecord).copy(id = savedRecords.size + 1L)
            savedRecords.add(r)
            r
        }
        Mockito.`when`(detailRepository.save(Mockito.any(SellMatchDetail::class.java) ?: dummyDetail())).thenAnswer { it.arguments[0] }
        service.start(listOf(account))
        val rpc = Mockito.mock(EthereumRpcApi::class.java)
        Mockito.`when`(rpc.call(anyReq())).thenAnswer { inv ->
            val req = inv.arguments[0] as JsonRpcRequest
            if (req.method == "eth_getTransactionReceipt") Response.success(JsonRpcResponse(result = receipt))
            else Response.success(JsonRpcResponse(result = JsonParser.parseString("""{"timestamp":"0x1"}""")))
        }
        service.handleAccountTransaction(7L, txHash, OkHttpClient(), rpc)
    }

    private fun tracking(id: Long, ctId: Long, remaining: String, status: String = "filled") = CopyOrderTracking(
        id = id, copyTradingId = ctId, accountId = 7L, leaderId = ctId, marketId = "0xmarket", side = "0", outcomeIndex = 0,
        buyOrderId = "0xb$id", leaderBuyTradeId = "t$id", quantity = BigDecimal(remaining), price = BigDecimal("0.5"),
        remainingQuantity = BigDecimal(remaining), status = status, source = "activity-ws"
    )

    @Test
    fun `own copy sell order fill is skipped by order hash`() {
        val t = tracking(100, 10, "300")
        setup(systemOrderIds = listOf(sellOrderHash.uppercase().replace("0X", "0x")), trackings = mapOf(10L to listOf(t)))

        assertEquals(0, BigDecimal("300").compareTo(t.remainingQuantity))
        assertEquals(0, savedRecords.size)
    }

    @Test
    fun `external sell is split once across configs by remaining including disabled configs`() {
        val a = tracking(100, 10, "300")
        val b = tracking(101, 11, "100")
        val pending = tracking(102, 11, "50", status = "pending")
        setup(systemOrderIds = emptyList(), trackings = mapOf(10L to listOf(a), 11L to listOf(b, pending)), disabledConfigIds = setOf(11L))

        // 外部卖出 200 份：按剩余 300:100 分摊为 150 / 50（pending 行不参与）
        assertEquals(0, BigDecimal("150").compareTo(a.remainingQuantity))
        assertEquals(0, BigDecimal("50").compareTo(b.remainingQuantity))
        assertEquals(0, BigDecimal("50").compareTo(pending.remainingQuantity))
        assertEquals(2, savedRecords.size)
        assertEquals(0, BigDecimal("0.55").compareTo(savedRecords.first().sellPrice))
        assertEquals(txHash, savedRecords.first().sourceTxHash)
    }

    @Test
    fun `allocation gives each config its full remaining when sold exceeds total`() {
        val result = service.allocateByRemaining(mapOf(1L to BigDecimal("10"), 2L to BigDecimal("5")), BigDecimal("20"))
        assertEquals(0, BigDecimal("10").compareTo(result[1L]))
        assertEquals(0, BigDecimal("5").compareTo(result[2L]))
    }

    private fun anyReq(): JsonRpcRequest {
        Mockito.any(JsonRpcRequest::class.java)
        return JsonRpcRequest(method = "x", params = emptyList())
    }

    private fun dummyRecord() = SellMatchRecord(copyTradingId = 0, sellOrderId = "", leaderSellTradeId = "", marketId = "", side = "",
        totalMatchedQuantity = BigDecimal.ZERO, sellPrice = BigDecimal.ZERO, totalRealizedPnl = BigDecimal.ZERO)

    private fun dummyDetail() = SellMatchDetail(matchRecordId = 0, trackingId = 0, buyOrderId = "", matchedQuantity = BigDecimal.ZERO,
        buyPrice = BigDecimal.ZERO, sellPrice = BigDecimal.ZERO, realizedPnl = BigDecimal.ZERO)
}
