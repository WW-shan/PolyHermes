package com.wrbug.polymarketbot.api

import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Ethereum RPC API 接口定义
 * 用于调用 Ethereum JSON-RPC 接口
 */
interface EthereumRpcApi {
    
    /**
     * 调用 Ethereum JSON-RPC 方法
     */
    @POST("/")
    suspend fun call(@Body request: JsonRpcRequest): Response<JsonRpcResponse>
}

/**
 * JSON-RPC 请求
 */
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: List<Any>,
    val id: Int = 1
)

/**
 * JSON-RPC 响应
 * 使用 JsonElement 类型处理 result 字段，可以灵活处理字符串、对象、数组等类型
 */
data class JsonRpcResponse(
    val jsonrpc: String? = null,
    val result: JsonElement? = null,  // 使用 JsonElement 类型，可以处理任意 JSON 类型
    val error: JsonRpcError? = null,
    val id: Int? = null
)

/**
 * JSON-RPC 错误
 */
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)

/** Internal signal that an endpoint failed its preflight before the requested RPC call was sent. */
internal class ChainIdCheckRejectedException(message: String) : Exception(message)

/**
 * 校验 chainId 的 RPC 包装：首次使用某节点 URL 时调用 eth_chainId，必须为 Polygon 主网 0x89（按 URL 缓存结果）。
 * 校验拒绝通过内部异常标记，确保故障切换层知道原始请求尚未发送；eth_chainId 查询失败不缓存。
 */
class ChainIdCheckedRpcApi(
    private val rpcUrl: String,
    private val delegate: EthereumRpcApi
) : EthereumRpcApi {

    override suspend fun call(request: JsonRpcRequest): Response<JsonRpcResponse> {
        val verified = verifiedUrls[rpcUrl] ?: run {
            val chainIdResponse = try {
                delegate.call(JsonRpcRequest(method = "eth_chainId", params = emptyList()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                throw ChainIdCheckRejectedException("RPC 节点 chainId 查询失败")
            }
            val chainId = try {
                chainIdResponse.body()?.result?.takeIf { !it.isJsonNull }?.asString
            } catch (e: Exception) {
                throw ChainIdCheckRejectedException("RPC 节点 chainId 响应格式无效")
            }
            if (!chainIdResponse.isSuccessful || chainId == null) {
                throw ChainIdCheckRejectedException("RPC 节点 chainId 查询失败")
            }
            val ok = chainId.equals(POLYGON_CHAIN_ID_HEX, ignoreCase = true)
            verifiedUrls[rpcUrl] = ok
            ok
        }
        if (!verified) {
            throw ChainIdCheckRejectedException("RPC 节点 chainId 不是 Polygon 主网(0x89)，已拒绝使用")
        }
        return delegate.call(request)
    }

    companion object {
        const val POLYGON_CHAIN_ID_HEX = "0x89"
        /** Error returned when all candidate endpoints are rejected during the preflight check. */
        const val CHAIN_ID_CHECK_FAILURE_CODE = -32089

        /** 节点 URL → chainId 是否为 Polygon 主网 */
        private val verifiedUrls = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    }
}

/**
 * Request-scoped RPC failover. Only known read methods are replayed after an endpoint failure;
 * transaction submission methods are never replayed after an ambiguous network failure.
 */
class FailoverEthereumRpcApi(
    private val initialEndpoint: Pair<String, EthereumRpcApi>,
    private val nextEndpoint: (Set<String>) -> Pair<String, EthereumRpcApi>?
) : EthereumRpcApi {

    override suspend fun call(request: JsonRpcRequest): Response<JsonRpcResponse> {
        val excludedUrls = linkedSetOf<String>()
        val replayableRead = request.method in REPLAYABLE_READ_METHODS
        var endpoint: Pair<String, EthereumRpcApi>? = initialEndpoint
        var lastResponse: Response<JsonRpcResponse>? = null
        var lastFailure: Exception? = null

        while (endpoint != null) {
            val (url, api) = endpoint
            if (!excludedUrls.add(url)) break

            try {
                val response = api.call(request)
                lastResponse = response
                val readFailed = !response.isSuccessful || response.body() == null || response.body()?.error != null
                if (!replayableRead || !readFailed) return response
            } catch (e: ChainIdCheckRejectedException) {
                lastFailure = e
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                if (!replayableRead) throw e
                lastFailure = e
            }

            endpoint = nextEndpoint(excludedUrls.toSet())
        }

        return lastResponse
            ?: when (val failure = lastFailure) {
                is ChainIdCheckRejectedException -> Response.success(
                    JsonRpcResponse(
                        error = JsonRpcError(
                            ChainIdCheckedRpcApi.CHAIN_ID_CHECK_FAILURE_CODE,
                            failure.message ?: "RPC 节点 chainId 校验失败"
                        )
                    )
                )
                null -> Response.success(JsonRpcResponse(error = JsonRpcError(-32000, "没有可用的 Polygon RPC 节点")))
                else -> throw failure
            }
    }

    companion object {
        private val REPLAYABLE_READ_METHODS = setOf(
            "eth_chainId",
            "eth_blockNumber",
            "eth_call",
            "eth_estimateGas",
            "eth_gasPrice",
            "eth_getBalance",
            "eth_getBlockByNumber",
            "eth_getCode",
            "eth_getLogs",
            "eth_getTransactionByHash",
            "eth_getTransactionCount",
            "eth_getTransactionReceipt"
        )
    }
}
