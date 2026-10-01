package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.api.GammaFeeScheduleResponse
import com.wrbug.polymarketbot.api.MarketResponse
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.entity.Market
import com.wrbug.polymarketbot.repository.MarketRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import retrofit2.Response
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val OPEN_ID = "0xopen"
private const val CLOSED_ID = "0xclosed"

/** 仅实现 listMarkets：默认查询只返回未结束市场，closed=true 只返回已结束市场（与 Gamma 实测行为一致） */
private class FakeGammaApi : PolymarketGammaApi by Mockito.mock(PolymarketGammaApi::class.java) {
    val calls = mutableListOf<Pair<List<String>?, Boolean?>>()
    var failDefault = false

    override suspend fun listMarkets(
        conditionIds: List<String>?,
        clobTokenIds: List<String>?,
        includeTag: Boolean?,
        closed: Boolean?
    ): Response<List<MarketResponse>> {
        calls.add(conditionIds to closed)
        if (failDefault && closed != true) {
            return Response.error(500, okhttp3.ResponseBody.create(null, "boom"))
        }
        val all = listOf(
            MarketResponse(conditionId = OPEN_ID, question = "open", closed = false, negRisk = false),
            MarketResponse(
                conditionId = CLOSED_ID,
                question = "BTC Up or Down 5m",
                closed = true,
                endDate = "2026-09-20T12:05:00Z",
                feesEnabled = true,
                negRisk = true,
                feeSchedule = GammaFeeScheduleResponse(rate = BigDecimal("0.07"))
            )
        )
        val body = all.filter { conditionIds?.contains(it.conditionId) == true }
            .filter { (it.closed == true) == (closed == true) }
        return Response.success(body)
    }
}

/**
 * 回归测试：Gamma 对已结束市场的默认查询返回 []，必须用 closed=true 补查，
 * 否则回测/统计拿不到 endDate、标题与费率。
 */
class MarketServiceClosedMarketTest {

    private val marketRepository: MarketRepository = Mockito.mock(MarketRepository::class.java)
    private val retrofitFactory: RetrofitFactory = Mockito.mock(RetrofitFactory::class.java)
    private val gammaApi = FakeGammaApi()
    private val service = MarketService(marketRepository, retrofitFactory)
    private val store = ConcurrentHashMap<String, Market>()

    init {
        Mockito.`when`(retrofitFactory.createGammaApi()).thenReturn(gammaApi)
        Mockito.`when`(marketRepository.findByMarketId(Mockito.anyString())).thenAnswer { store[it.getArgument(0)] }
        Mockito.`when`(marketRepository.findByMarketIdIn(Mockito.anyList())).thenAnswer { inv ->
            inv.getArgument<List<String>>(0).mapNotNull { store[it] }
        }
        Mockito.`when`(marketRepository.save(Mockito.any(Market::class.java))).thenAnswer { inv ->
            inv.getArgument<Market>(0).copy(id = 1L).also { store[it.marketId] = it }
        }
    }

    @Test
    fun `getMarket falls back to closed=true for ended markets`() {
        val market = service.getMarket(CLOSED_ID)

        assertNotNull(market)
        assertEquals("BTC Up or Down 5m", market!!.title)
        assertEquals(Instant.parse("2026-09-20T12:05:00Z").toEpochMilli(), market.endDate)
        assertEquals(0, BigDecimal("0.07").compareTo(market.takerFeeRate))
        assertEquals(listOf(listOf(CLOSED_ID) to null, listOf(CLOSED_ID) to true), gammaApi.calls)
    }

    @Test
    fun `open markets do not trigger closed query`() {
        assertNotNull(service.getMarket(OPEN_ID))
        assertEquals(1, gammaApi.calls.size)
    }

    @Test
    fun `getMarkets resolves both open and closed markets`() {
        val result = service.getMarkets(listOf(OPEN_ID, CLOSED_ID))
        assertEquals(setOf(OPEN_ID, CLOSED_ID), result.keys)
    }

    @Test
    fun `negRisk lookup fails instead of returning false when Gamma errors`() {
        gammaApi.failDefault = true
        val result = runBlocking { service.fetchNegRiskByConditionId(OPEN_ID) }
        assertTrue(result.isFailure)
        assertNull(runBlocking { service.getNegRiskByConditionId(OPEN_ID) })
    }

    @Test
    fun `negRisk lookup resolves ended markets`() {
        val result = runBlocking { service.fetchNegRiskByConditionId(CLOSED_ID) }
        assertEquals(true, result.getOrNull())
    }

    @Test
    fun `unknown fee rate is reported as null`() {
        store["0xnofee"] = Market(id = 2L, marketId = "0xnofee", title = "x", takerFeeRate = null)
        assertNull(service.findTakerFeeRate("0xnofee"))
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getTakerFeeRate("0xnofee")))
    }
}
