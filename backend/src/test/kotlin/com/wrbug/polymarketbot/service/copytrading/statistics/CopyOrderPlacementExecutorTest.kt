package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.NewOrderResponse
import com.wrbug.polymarketbot.api.OpenOrder
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.SignedOrderObject
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal

class CopyOrderPlacementExecutorTest {

    private val executor = CopyOrderPlacementExecutor(maxAttempts = 3, retryDelayMs = 0)
    private val signed = SignedOrderObject(
        salt = 1L, maker = "0xm", signer = "0xs", taker = "0x0", tokenId = "1",
        makerAmount = "5000000", takerAmount = "10000000", side = "BUY", signatureType = 2,
        timestamp = "1", expiration = "0", metadata = "0x0", builder = "0x0", signature = "0xsig"
    )

    @Test
    fun `matched buy records taking amount as shares and average price`() = runBlocking {
        val api = Mockito.mock(PolymarketClobApi::class.java)
        Mockito.`when`(api.createOrder(anyRequest())).thenReturn(
            Response.success(NewOrderResponse(success = true, orderId = "0xabc", status = "matched", makingAmount = "3.3", takingAmount = "6"))
        )
        val outcome = executor.place(api, signed, "0xabc", "owner", "t").getOrThrow() as OrderOutcome.Settled
        assertEquals(0, BigDecimal("6").compareTo(outcome.filledShares))
        assertEquals(0, BigDecimal("0.55").compareTo(outcome.avgPrice))
    }

    @Test
    fun `delayed response is pending not filled`() = runBlocking {
        val api = Mockito.mock(PolymarketClobApi::class.java)
        Mockito.`when`(api.createOrder(anyRequest())).thenReturn(
            Response.success(NewOrderResponse(success = true, orderId = "0xabc", status = "delayed"))
        )
        val outcome = executor.place(api, signed, "0xabc", "owner", "t").getOrThrow()
        assertTrue(outcome is OrderOutcome.Pending)
    }

    @Test
    fun `sell matched uses making amount as shares`() {
        val outcome = CopyOrderPlacementExecutor.outcomeFromResponse(
            NewOrderResponse(success = true, orderId = "0x1", status = "matched", makingAmount = "4", takingAmount = "2.2"),
            "SELL"
        ) as OrderOutcome.Settled
        assertEquals(0, BigDecimal("4").compareTo(outcome.filledShares))
        assertEquals(0, BigDecimal("0.55").compareTo(outcome.avgPrice))
    }

    @Test
    fun `4xx is not retried`() = runBlocking {
        val api = Mockito.mock(PolymarketClobApi::class.java)
        Mockito.`when`(api.createOrder(anyRequest())).thenReturn(
            Response.error(400, "{\"error\":\"not enough balance\"}".toResponseBody())
        )
        val result = executor.place(api, signed, "0xabc", "owner", "t")
        assertTrue(result.exceptionOrNull() is OrderRejectedException)
        Mockito.verify(api, Mockito.times(1)).createOrder(anyRequest())
        Mockito.verify(api, Mockito.never()).getOrder(Mockito.anyString())
    }

    @Test
    fun `timeout then query finds accepted order and does not resend`() = runBlocking {
        val api = Mockito.mock(PolymarketClobApi::class.java)
        Mockito.`when`(api.createOrder(anyRequest())).thenAnswer { throw IOException("timeout") }
        Mockito.`when`(api.getOrder("0xabc")).thenReturn(Response.success(openOrder("MATCHED", "4")))
        val outcome = executor.place(api, signed, "0xabc", "owner", "t").getOrThrow() as OrderOutcome.Pending
        assertEquals("MATCHED", outcome.status)
        Mockito.verify(api, Mockito.times(1)).createOrder(anyRequest())
    }

    @Test
    fun `filled order is terminal`() {
        assertTrue(CopyOrderPlacementExecutor.isTerminal(openOrder("FILLED", "4")))
    }

    @Test
    fun `5xx retries reuse the same signed order`() = runBlocking {
        val api = Mockito.mock(PolymarketClobApi::class.java)
        Mockito.`when`(api.createOrder(anyRequest())).thenReturn(
            Response.error(503, "busy".toResponseBody()),
            Response.success(NewOrderResponse(success = true, orderId = "0xabc", status = "matched", makingAmount = "5", takingAmount = "10"))
        )
        Mockito.`when`(api.getOrder("0xabc")).thenReturn(Response.error(404, "".toResponseBody()))
        val outcome = executor.place(api, signed, "0xabc", "owner", "t").getOrThrow()
        assertTrue(outcome is OrderOutcome.Settled)
        val captor = ArgumentCaptor.forClass(NewOrderRequest::class.java)
        Mockito.verify(api, Mockito.times(2)).createOrder(captorCapture(captor))
        assertEquals(1, captor.allValues.map { it.order }.distinct().size)
    }

    @Test
    fun `all attempts ambiguous yields unknown status`() = runBlocking {
        val api = Mockito.mock(PolymarketClobApi::class.java)
        Mockito.`when`(api.createOrder(anyRequest())).thenAnswer { throw IOException("reset") }
        Mockito.`when`(api.getOrder("0xabc")).thenReturn(Response.error(404, "".toResponseBody()))
        val result = executor.place(api, signed, "0xabc", "owner", "t")
        assertTrue(result.exceptionOrNull() is OrderStatusUnknownException)
    }

    private fun openOrder(status: String, sizeMatched: String) = OpenOrder(
        id = "0xabc", status = status, owner = "o", makerAddress = "0xm", market = "m", assetId = "1",
        side = "BUY", originalSize = "10", sizeMatched = sizeMatched, price = "0.5", outcome = "Yes",
        expiration = "0", orderType = "FAK", createdAt = 1L
    )

    private fun anyRequest(): NewOrderRequest {
        Mockito.any(NewOrderRequest::class.java)
        return NewOrderRequest(order = signed, owner = "", orderType = "FAK")
    }

    private fun captorCapture(captor: ArgumentCaptor<NewOrderRequest>): NewOrderRequest {
        captor.capture()
        return NewOrderRequest(order = signed, owner = "", orderType = "FAK")
    }
}
