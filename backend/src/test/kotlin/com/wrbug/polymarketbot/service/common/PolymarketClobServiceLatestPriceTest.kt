package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.api.OrderbookEntry
import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.TickSizeResponse
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import retrofit2.Response
import java.lang.reflect.Proxy

class PolymarketClobServiceLatestPriceTest {

    @Test
    fun `latest price falls back to tick endpoint when orderbook omits tick`() = runBlocking {
        val orderbook = OrderbookResponse(
            bids = listOf(OrderbookEntry("0.500", "10")),
            asks = listOf(OrderbookEntry("0.520", "10")),
            tickSize = null
        )
        val api = Proxy.newProxyInstance(
            PolymarketClobApi::class.java.classLoader,
            arrayOf(PolymarketClobApi::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getOrderbook" -> Response.success(orderbook)
                "getTickSize" -> Response.success(TickSizeResponse("0.001"))
                else -> error("unexpected API call: ${method.name}")
            }
        } as PolymarketClobApi
        val service = PolymarketClobService(api, Mockito.mock(RetrofitFactory::class.java))

        val response = service.getLatestPrice("123").getOrThrow()

        assertTrue(response.toString().contains("tickSize=0.001"), response.toString())
    }

    @Test
    fun `latest price includes market tick size`() = runBlocking {
        val orderbook = OrderbookResponse(
            bids = listOf(OrderbookEntry("0.500", "10")),
            asks = listOf(OrderbookEntry("0.520", "10")),
            tickSize = "0.001"
        )
        val api = Proxy.newProxyInstance(
            PolymarketClobApi::class.java.classLoader,
            arrayOf(PolymarketClobApi::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getOrderbook" -> Response.success(orderbook)
                else -> error("unexpected API call: ${method.name}")
            }
        } as PolymarketClobApi
        val service = PolymarketClobService(api, Mockito.mock(RetrofitFactory::class.java))

        val response = service.getLatestPrice("123").getOrThrow()

        assertEquals("0.500", response.bestBid)
        assertTrue(response.toString().contains("tickSize=0.001"), response.toString())
    }
}
