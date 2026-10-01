package com.wrbug.polymarketbot.service.common

import com.google.gson.JsonPrimitive
import com.wrbug.polymarketbot.api.ChainIdCheckedRpcApi
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.FailoverEthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcError
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.api.JsonRpcResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.CancellationException

class FailoverEthereumRpcApiTest {

    @Test
    fun `read request retries next node after transport failure`() = runBlocking {
        val first = FakeRpc { throw IOException("node unavailable") }
        val second = FakeRpc { Response.success(JsonRpcResponse(result = JsonPrimitive("0x123"))) }
        val api = FailoverEthereumRpcApi(
            initialEndpoint = "node-a" to first,
            nextEndpoint = { excluded -> if ("node-b" in excluded) null else "node-b" to second }
        )

        val response = api.call(JsonRpcRequest(method = "eth_blockNumber", params = emptyList()))

        assertEquals("0x123", response.body()!!.result!!.asString)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
    }

    @Test
    fun `write request is not retried after ambiguous transport failure`() {
        val first = FakeRpc { throw IOException("response lost") }
        var nextEndpointRequested = false
        val api = FailoverEthereumRpcApi(
            initialEndpoint = "node-a" to first,
            nextEndpoint = { nextEndpointRequested = true; "node-b" to FakeRpc { Response.success(JsonRpcResponse()) } }
        )

        assertThrows(IOException::class.java) {
            runBlocking { api.call(JsonRpcRequest(method = "eth_sendRawTransaction", params = listOf("0xraw"))) }
        }
        assertEquals(false, nextEndpointRequested)
        assertEquals(1, first.calls)
    }

    @Test
    fun `write request does not treat upstream error code as a preflight rejection`() = runBlocking {
        val first = FakeRpc {
            Response.success(
                JsonRpcResponse(error = JsonRpcError(ChainIdCheckedRpcApi.CHAIN_ID_CHECK_FAILURE_CODE, "wrong chain"))
            )
        }
        val second = FakeRpc { Response.success(JsonRpcResponse(result = JsonPrimitive("should-not-be-used"))) }
        var nextEndpointRequested = false
        val api = FailoverEthereumRpcApi(
            initialEndpoint = "node-a" to first,
            nextEndpoint = { nextEndpointRequested = true; "node-b" to second }
        )

        val response = api.call(JsonRpcRequest(method = "eth_sendRawTransaction", params = listOf("0xraw")))

        assertEquals(ChainIdCheckedRpcApi.CHAIN_ID_CHECK_FAILURE_CODE, response.body()!!.error!!.code)
        assertEquals(false, nextEndpointRequested)
        assertEquals(1, first.calls)
        assertEquals(0, second.calls)
    }

    @Test
    fun `transaction switches endpoint after chain id mismatch without submitting on wrong chain`() = runBlocking {
        var firstSubmissionCount = 0
        var secondSubmissionCount = 0
        val firstNode = FakeRpc { request ->
            if (request.method == "eth_chainId") {
                Response.success(JsonRpcResponse(result = JsonPrimitive("0x1")))
            } else {
                firstSubmissionCount++
                Response.success(JsonRpcResponse(result = JsonPrimitive("unexpected")))
            }
        }
        val secondNode = FakeRpc { request ->
            if (request.method == "eth_chainId") {
                Response.success(JsonRpcResponse(result = JsonPrimitive("0x89")))
            } else {
                secondSubmissionCount++
                Response.success(JsonRpcResponse(result = JsonPrimitive("0xtx")))
            }
        }
        val api = FailoverEthereumRpcApi(
            initialEndpoint = "http://wrong-chain-unique" to ChainIdCheckedRpcApi("http://wrong-chain-unique", firstNode),
            nextEndpoint = { excluded ->
                if ("http://polygon-chain-unique" in excluded) null
                else "http://polygon-chain-unique" to ChainIdCheckedRpcApi("http://polygon-chain-unique", secondNode)
            }
        )

        val response = api.call(JsonRpcRequest(method = "eth_sendRawTransaction", params = listOf("0xraw")))

        assertEquals("0xtx", response.body()!!.result!!.asString)
        assertEquals(0, firstSubmissionCount)
        assertEquals(1, secondSubmissionCount)
    }

    @Test
    fun `read request cancellation is propagated without trying another endpoint`() {
        var nextEndpointRequested = false
        val api = FailoverEthereumRpcApi(
            initialEndpoint = "node-a" to FakeRpc { throw CancellationException("cancelled") },
            nextEndpoint = { nextEndpointRequested = true; null }
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { api.call(JsonRpcRequest(method = "eth_call", params = emptyList())) }
        }
        assertEquals(false, nextEndpointRequested)
    }

    private class FakeRpc(
        private val handler: suspend (JsonRpcRequest) -> Response<JsonRpcResponse>
    ) : EthereumRpcApi {
        var calls = 0
        override suspend fun call(request: JsonRpcRequest): Response<JsonRpcResponse> {
            calls++
            return handler(request)
        }
    }
}
