package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.PositionResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import retrofit2.Response
import java.lang.reflect.Proxy

/**
 * getPositions：必须传 sizeThreshold=0 并按 offset 翻页取全；任一页失败整体失败
 */
class BlockchainServicePositionsTest {

    /** 记录 (sizeThreshold, limit, offset) 的假 Data API；pages[i] 为第 i 页条数，null 表示该页 HTTP 失败 */
    private fun fakeDataApi(pages: List<Int?>, seen: MutableList<Triple<Double?, Int?, Int?>>): PolymarketDataApi {
        return Proxy.newProxyInstance(
            PolymarketDataApi::class.java.classLoader,
            arrayOf(PolymarketDataApi::class.java)
        ) { _, method, args ->
            require(method.name == "getPositions")
            // 参数顺序：user, market, eventId, sizeThreshold, redeemable, mergeable, limit, offset, ...
            val sizeThreshold = args[3] as Double?
            val limit = args[6] as Int?
            val offset = args[7] as Int?
            seen.add(Triple(sizeThreshold, limit, offset))
            val index = (offset ?: 0) / (limit ?: 500)
            val count = pages.getOrNull(index)
            if (count == null) {
                Response.error<List<PositionResponse>>(500, okhttp3.ResponseBody.create(null, "err"))
            } else {
                Response.success(List(count) { PositionResponse(proxyWallet = "0xabc", asset = "t$index-$it") })
            }
        } as PolymarketDataApi
    }

    private fun service(api: PolymarketDataApi): BlockchainService {
        val s = FakeRpc.blockchainService(FakeRpc())
        s.dataApiOverride = api
        return s
    }

    @Test
    fun `pages through all positions with sizeThreshold zero`() = runBlocking {
        val seen = mutableListOf<Triple<Double?, Int?, Int?>>()
        val result = service(fakeDataApi(listOf(500, 500, 3), seen)).getPositions("0xabc").getOrThrow()
        assertEquals(1003, result.size)
        assertEquals(listOf(0, 500, 1000), seen.map { it.third })
        assertTrue(seen.all { it.first == 0.0 && it.second == 500 })
    }

    @Test
    fun `failure on a later page fails the whole query`() = runBlocking {
        val seen = mutableListOf<Triple<Double?, Int?, Int?>>()
        val result = service(fakeDataApi(listOf(500, null), seen)).getPositions("0xabc")
        assertTrue(result.isFailure)
    }
}
