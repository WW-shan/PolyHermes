package com.wrbug.polymarketbot.service.common

import com.google.gson.JsonPrimitive
import com.wrbug.polymarketbot.api.ChainIdCheckedRpcApi
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcError
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.api.JsonRpcResponse
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.service.system.RpcNodeService
import com.wrbug.polymarketbot.service.system.SystemConfigService
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.mockito.Mockito
import retrofit2.Response

/**
 * 测试用 JSON-RPC 假节点：按 eth_call 的 (to, data 前缀) 返回预设结果，未匹配的调用返回 RPC 错误
 */
class FakeRpc : EthereumRpcApi {
    /** key: "to|dataPrefix"（小写），value: 结果十六进制字符串；null 表示返回 RPC 错误 */
    val ethCallResults = linkedMapOf<String, String?>()
    val calls = mutableListOf<JsonRpcRequest>()

    /** eth_getCode 结果：地址（小写）→ code；未设置的地址返回 RPC 错误 */
    val codeAt = mutableMapOf<String, String>()

    /** 预设 ERC20 allowance(owner, spender) */
    fun onAllowance(token: String, owner: String, spender: String, value: String) =
        onCall(token, "0xdd62ed3e" + pad(owner) + pad(spender), value)

    /** 预设 ERC1155 isApprovedForAll(owner, operator) */
    fun onApprovedForAll(token: String, owner: String, operator: String, approved: Boolean) =
        onCall(token, "0xe985e9c5" + pad(owner) + pad(operator), word(if (approved) 1 else 0))

    private fun pad(address: String) = address.removePrefix("0x").lowercase().padStart(64, '0')

    fun onCall(to: String, dataPrefix: String, result: String?) {
        ethCallResults["${to.lowercase()}|${dataPrefix.lowercase()}"] = result
    }

    override suspend fun call(request: JsonRpcRequest): Response<JsonRpcResponse> {
        calls.add(request)
        if (request.method == "eth_chainId") {
            return Response.success(JsonRpcResponse(result = JsonPrimitive("0x89")))
        }
        if (request.method == "eth_getCode") {
            val code = codeAt[(request.params[0] as String).lowercase()]
            if (code != null) {
                return Response.success(JsonRpcResponse(result = JsonPrimitive(code)))
            }
        }
        if (request.method == "eth_call") {
            @Suppress("UNCHECKED_CAST")
            val tx = request.params[0] as Map<String, String>
            val to = tx["to"]!!.lowercase()
            val data = tx["data"]!!.lowercase()
            val hit = ethCallResults.entries.firstOrNull { (k, _) ->
                val (t, prefix) = k.split("|")
                t == to && data.startsWith(prefix)
            }
            if (hit != null && hit.value != null) {
                return Response.success(JsonRpcResponse(result = JsonPrimitive(hit.value)))
            }
        }
        return Response.success(JsonRpcResponse(error = JsonRpcError(code = -32000, message = "fake rpc: no result")))
    }

    companion object {
        /** 构造使用该假节点的 RetrofitFactory / RpcNodeService / BlockchainService */
        fun blockchainService(rpc: FakeRpc): BlockchainService {
            val retrofitFactory = Mockito.mock(RetrofitFactory::class.java)
            val rpcNodeService = Mockito.mock(RpcNodeService::class.java)
            Mockito.`when`(rpcNodeService.getHttpUrl()).thenReturn("http://fake-rpc")
            Mockito.`when`(rpcNodeService.createFailoverRpcApi())
                .thenReturn(ChainIdCheckedRpcApi("http://fake-rpc", rpc))
            Mockito.`when`(retrofitFactory.createEthereumRpcApi("http://fake-rpc")).thenReturn(rpc)
            val relay = RelayClientService(retrofitFactory, Mockito.mock(SystemConfigService::class.java), rpcNodeService)
            return BlockchainService(retrofitFactory, relay, rpcNodeService, com.google.gson.Gson())
        }

        fun word(value: Long): String = "0x" + java.lang.Long.toHexString(value).padStart(64, '0')
    }
}
