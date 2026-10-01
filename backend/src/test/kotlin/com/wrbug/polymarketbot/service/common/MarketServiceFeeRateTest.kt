package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.api.GammaFeeScheduleResponse
import com.wrbug.polymarketbot.api.MarketResponse
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.entity.Market
import com.wrbug.polymarketbot.repository.MarketRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import retrofit2.Response
import java.math.BigDecimal

/**
 * 回归测试：Gamma 已不再返回 market.category，费率必须取 feeSchedule.rate（或 feeType 兜底），
 * 否则跟单/回测盈亏会漏扣手续费。
 */
class MarketServiceFeeRateTest {

    private val marketRepository: MarketRepository = Mockito.mock(MarketRepository::class.java)
    private val retrofitFactory: RetrofitFactory = Mockito.mock(RetrofitFactory::class.java)
    private val gammaApi: PolymarketGammaApi = Mockito.mock(PolymarketGammaApi::class.java)
    private val service = MarketService(marketRepository, retrofitFactory)

    private val conditionId = "0x95f345095db7ade90544047a7b8dd285f73e9bb9d311828788ebf43185501a52"

    private fun stubGamma(response: MarketResponse) {
        Mockito.`when`(retrofitFactory.createGammaApi()).thenReturn(gammaApi)
        runBlocking {
            Mockito.`when`(gammaApi.listMarkets(Mockito.anyList(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Response.success(listOf(response)))
        }
        val saved = java.util.concurrent.atomic.AtomicReference<Market?>(null)
        Mockito.`when`(marketRepository.findByMarketId(conditionId)).thenAnswer { saved.get() }
        Mockito.`when`(marketRepository.save(Mockito.any(Market::class.java))).thenAnswer { invocation ->
            invocation.getArgument<Market>(0).copy(id = 1L).also { saved.set(it) }
        }
    }

    @Test
    fun `persists feeSchedule rate even when category is missing`() {
        stubGamma(
            MarketResponse(
                id = "4613531",
                conditionId = conditionId,
                question = "Cincinnati Reds vs. Atlanta Braves",
                category = null,
                feesEnabled = true,
                feeType = "sports_fees_v3",
                feeSchedule = GammaFeeScheduleResponse(
                    rate = BigDecimal("0.05"),
                    exponent = 1,
                    takerOnly = true,
                    rebateRate = BigDecimal("0.15")
                )
            )
        )

        val market = service.getMarket(conditionId)

        assertEquals(0, BigDecimal("0.05").compareTo(market?.takerFeeRate))
        assertEquals(0, BigDecimal("0.05").compareTo(service.getTakerFeeRate(conditionId)))
    }

    @Test
    fun `falls back to feeType mapping when feeSchedule is absent`() {
        stubGamma(
            MarketResponse(
                id = "999",
                conditionId = conditionId,
                question = "Will Shakhtar win?",
                category = null,
                feesEnabled = true,
                feeType = "sports_fees_v2"
            )
        )

        val market = service.getMarket(conditionId)

        // sports_fees_v2 线上实测 0.03，不能按 sports 分类的 0.05 处理
        assertEquals(0, BigDecimal("0.03").compareTo(market?.takerFeeRate))
    }

    @Test
    fun `stores zero when fees are disabled`() {
        stubGamma(
            MarketResponse(
                id = "665374",
                conditionId = conditionId,
                question = "Will the U.S. invade Iran before 2027?",
                category = null,
                feesEnabled = false
            )
        )

        val market = service.getMarket(conditionId)

        assertEquals(0, BigDecimal.ZERO.compareTo(market?.takerFeeRate))
    }

    @Test
    fun `legacy rows without stored rate are refreshed once and use fallback category`() {
        val legacy = Market(
            id = 7L,
            marketId = conditionId,
            title = "legacy",
            category = "Sports",
            takerFeeRate = null
        )
        Mockito.`when`(marketRepository.findByMarketId(conditionId)).thenReturn(legacy)
        Mockito.`when`(retrofitFactory.createGammaApi()).thenReturn(gammaApi)
        // 刷新失败：仍应按分类兜底而不是 0
        runBlocking {
            Mockito.`when`(gammaApi.listMarkets(Mockito.anyList(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Response.error(500, okhttp3.ResponseBody.create(null, "boom")))
        }

        assertEquals(0, BigDecimal("0.05").compareTo(service.getTakerFeeRate(conditionId)))
    }
}
