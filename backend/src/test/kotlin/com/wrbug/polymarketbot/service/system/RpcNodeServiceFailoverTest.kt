package com.wrbug.polymarketbot.service.system

import com.google.gson.JsonPrimitive
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.api.JsonRpcResponse
import com.wrbug.polymarketbot.entity.RpcNodeConfig
import com.wrbug.polymarketbot.repository.RpcNodeConfigRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import retrofit2.Response

class RpcNodeServiceFailoverTest {

    private val repository = Mockito.mock(RpcNodeConfigRepository::class.java)
    private val retrofitFactory = Mockito.mock(RetrofitFactory::class.java)
    private val service = RpcNodeService(
        rpcNodeConfigRepository = repository,
        cryptoUtils = Mockito.mock(CryptoUtils::class.java),
        retrofitFactory = retrofitFactory
    )

    @Test
    fun `excluded configured node falls through to public default`() {
        Mockito.`when`(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(listOf(customNode()))

        val result = service.getAvailableNode(setOf("https://rpc.example.test/"))

        assertEquals("https://polygon.publicnode.com", result.getOrThrow().httpUrl)
    }

    @Test
    fun `default node is not returned again after it has already failed`() {
        Mockito.`when`(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(emptyList())

        val result = service.getAvailableNode(setOf("https://polygon.publicnode.com/"))

        assertTrue(result.isFailure)
    }

    @Test
    fun `exhausted configured nodes and default produce no candidate`() {
        Mockito.`when`(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(listOf(customNode()))

        val result = service.getAvailableNode(setOf("https://rpc.example.test", "https://polygon.publicnode.com"))

        assertTrue(result.isFailure)
    }

    @Test
    fun `websocket reconnect can select another healthy rpc node`() {
        val failedNode = customNode(id = 10, httpUrl = "https://rpc-a.example.test", wsUrl = "wss://rpc-a.example.test")
        val backupNode = customNode(id = 11, httpUrl = "https://rpc-b.example.test", wsUrl = "wss://rpc-b.example.test")
        Mockito.`when`(repository.findAllByEnabledTrueOrderByPriorityAsc()).thenReturn(listOf(failedNode, backupNode))
        Mockito.`when`(retrofitFactory.createEthereumRpcApi(backupNode.httpUrl)).thenReturn(healthyRpc())

        val endpoint = service.getWebSocketEndpoint(setOf(failedNode.httpUrl)).getOrThrow()

        assertEquals(backupNode.httpUrl, endpoint.httpUrl)
        assertEquals(backupNode.wsUrl, endpoint.wsUrl)
    }

    private fun healthyRpc() = object : EthereumRpcApi {
        override suspend fun call(request: JsonRpcRequest) =
            Response.success(JsonRpcResponse(result = JsonPrimitive("0x123")))
    }

    private fun customNode(
        id: Long = 10,
        httpUrl: String = "https://rpc.example.test",
        wsUrl: String? = null
    ) = RpcNodeConfig(
        id = id,
        providerType = "CUSTOM",
        name = "test",
        httpUrl = httpUrl,
        wsUrl = wsUrl,
        enabled = true,
        priority = 1
    )
}
