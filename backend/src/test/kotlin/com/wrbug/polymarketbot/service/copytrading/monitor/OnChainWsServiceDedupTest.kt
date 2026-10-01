package com.wrbug.polymarketbot.service.copytrading.monitor

import com.google.gson.JsonParser
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.api.JsonRpcResponse
import com.wrbug.polymarketbot.api.MarketResponse
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

/**
 * P1-S5 / P2-S10 回归：链上路径按 (leader, tx) 去重，同一 tx 的多个 Leader 都会被处理；
 * Gamma 查询失败时不交给下游、不写去重缓存，后续通知可重试。
 */
class OnChainWsServiceDedupTest {

    private val tx = "0xf4d0fdf3c11a8dd661f1f802955be7bac93ce9e44d6054f7035bb46d73db94b0"
    private val tokenUp = "77073345465811379192787177281719442316070301560733975710653689726674114112036"
    private val tokenDown = "82320564380274369670394295556788957448871625488159004567616219313132556758615"

    private val retrofitFactory = Mockito.mock(RetrofitFactory::class.java)
    private val tracking = Mockito.mock(CopyOrderTrackingService::class.java)
    private val service = OnChainWsService(
        Mockito.mock(UnifiedOnChainWsService::class.java),
        retrofitFactory,
        tracking,
        Mockito.mock(LeaderRepository::class.java)
    ).also {
        it.maxProcessAttempts = 1
        it.retryDelayMs = 0
    }

    private val gammaCalls = AtomicInteger(0)

    @BeforeEach
    fun stubSuccessfulTradeProcessing() {
        runBlocking {
            Mockito.doReturn(Result.success(Unit)).`when`(tracking).processTrade(
                Mockito.anyLong(),
                Mockito.any(TradeResponse::class.java) ?: TradeResponse(
                    id = "stub", market = "market", side = "BUY", price = "0.5", size = "1", timestamp = "1", user = null
                ),
                Mockito.anyString()
            )
        }
    }

    private fun stubGamma(failFirst: Int) = runBlocking {
        val gamma = Mockito.mock(PolymarketGammaApi::class.java)
        Mockito.`when`(gamma.listMarkets(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer {
            if (gammaCalls.incrementAndGet() <= failFirst) {
                Response.error<List<MarketResponse>>(503, "".toResponseBody())
            } else {
                Response.success(listOf(MarketResponse(conditionId = "0xmarket", outcomes = "[\"Up\",\"Down\"]", clobTokenIds = "[\"$tokenUp\",\"$tokenDown\"]")))
            }
        }
        Mockito.`when`(retrofitFactory.createGammaApi()).thenReturn(gamma)
    }

    private fun rpc(): EthereumRpcApi = runBlocking {
        val receipt = JsonParser.parseString(this::class.java.getResource("/onchain/trade_taker_multi_fill.json")!!.readText())
            .asJsonObject.get("result")
        val rpc = Mockito.mock(EthereumRpcApi::class.java)
        Mockito.`when`(rpc.call(anyReq())).thenAnswer { inv ->
            val req = inv.arguments[0] as JsonRpcRequest
            if (req.method == "eth_getTransactionReceipt") Response.success(JsonRpcResponse(result = receipt))
            else Response.success(JsonRpcResponse(result = JsonParser.parseString("""{"timestamp":"0x1"}""")))
        }
        rpc
    }

    private fun delivered(): List<Pair<Long, TradeResponse>> {
        Thread.sleep(300)
        return Mockito.mockingDetails(tracking).invocations
            .filter { it.method.name.startsWith("processTrade") }
            .map { (it.arguments[0] as Long) to (it.arguments[1] as TradeResponse) }
    }

    @Test
    fun `two leaders in the same tx are both processed and each fill group delivered once`() = runBlocking {
        stubGamma(failFirst = 0)
        service.addLeader(Leader(id = 1, leaderAddress = "0x1D1AdE627D0bB0205758580B20D808573E720DF6"))
        service.addLeader(Leader(id = 2, leaderAddress = "0xf1404010a21a61c1f5693beee65285273be47cd8"))
        val rpc = rpc()
        repeat(2) {
            service.handleLeaderTransaction(1L, tx, OkHttpClient(), rpc)
            service.handleLeaderTransaction(2L, tx, OkHttpClient(), rpc)
        }

        val trades = delivered()
        assertEquals(3, trades.size, trades.map { "${it.first}:${it.second.side}:${it.second.size}" }.toString())
        assertEquals(1, trades.count { it.first == 1L })
        assertEquals(2, trades.count { it.first == 2L })
        val taker = trades.single { it.first == 1L }.second
        assertEquals("BUY", taker.side)
        assertEquals("0.55", taker.price)
        assertEquals(OnChainWsUtils.buildTradeId(tx, tokenUp, "BUY"), taker.id)
        assertEquals(0, taker.outcomeIndex)
    }

    @Test
    fun `gamma failure does not deliver or mark processed and later notification retries`() = runBlocking {
        stubGamma(failFirst = 1) // Gamma 暂时不可用
        service.addLeader(Leader(id = 1, leaderAddress = "0x1D1AdE627D0bB0205758580B20D808573E720DF6"))
        val rpc = rpc()

        service.handleLeaderTransaction(1L, tx, OkHttpClient(), rpc)
        assertEquals(0, delivered().size, "缺 market 信息时不能交给下游")

        service.handleLeaderTransaction(1L, tx, OkHttpClient(), rpc)
        assertEquals(1, delivered().size, "失败不写去重缓存，后续通知应能重试成功")
    }

    private fun anyReq(): JsonRpcRequest {
        Mockito.any(JsonRpcRequest::class.java)
        return JsonRpcRequest(method = "x", params = emptyList())
    }
}
