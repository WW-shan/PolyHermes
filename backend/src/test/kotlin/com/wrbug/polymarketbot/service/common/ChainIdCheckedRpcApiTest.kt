package com.wrbug.polymarketbot.service.common

import com.google.gson.JsonPrimitive
import com.wrbug.polymarketbot.api.ChainIdCheckRejectedException
import com.wrbug.polymarketbot.api.ChainIdCheckedRpcApi
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.api.JsonRpcResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import retrofit2.Response

/**
 * RPC 节点 chainId 校验：非 Polygon 主网节点拒绝使用，且校验结果按 URL 缓存
 */
class ChainIdCheckedRpcApiTest {

    private class Node(private val chainId: String) : EthereumRpcApi {
        var chainIdCalls = 0
        override suspend fun call(request: JsonRpcRequest): Response<JsonRpcResponse> {
            if (request.method == "eth_chainId") chainIdCalls++
            val result = if (request.method == "eth_chainId") chainId else "0x01"
            return Response.success(JsonRpcResponse(result = JsonPrimitive(result)))
        }
    }

    @Test
    fun `polygon node passes and chain id is checked once per url`() = runBlocking {
        val node = Node("0x89")
        val api = ChainIdCheckedRpcApi("http://polygon-node-test", node)
        repeat(3) { assertEquals("0x01", api.call(JsonRpcRequest(method = "eth_call", params = emptyList())).body()!!.result!!.asString) }
        assertEquals(1, node.chainIdCalls)
    }

    @Test
    fun `non polygon node is rejected`() {
        val api = ChainIdCheckedRpcApi("http://mainnet-node-test", Node("0x1"))
        assertThrows(ChainIdCheckRejectedException::class.java) {
            runBlocking { api.call(JsonRpcRequest(method = "eth_call", params = emptyList())) }
        }
    }
}
