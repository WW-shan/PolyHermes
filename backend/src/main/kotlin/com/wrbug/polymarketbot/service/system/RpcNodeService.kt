package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.ChainIdCheckedRpcApi
import com.wrbug.polymarketbot.api.FailoverEthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.entity.NodeHealthStatus
import com.wrbug.polymarketbot.entity.RpcNodeConfig
import com.wrbug.polymarketbot.entity.RpcProviderType
import com.wrbug.polymarketbot.repository.RpcNodeConfigRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * RPC 节点管理服务
 * 负责管理用户配置的 Polygon RPC 节点
 */
@Service
class RpcNodeService(
    private val rpcNodeConfigRepository: RpcNodeConfigRepository,
    private val cryptoUtils: CryptoUtils,
    private val retrofitFactory: RetrofitFactory
) {
    
    private val logger = LoggerFactory.getLogger(RpcNodeService::class.java)
    
    companion object {
        // 默认公共节点
        private const val DEFAULT_RPC_URL = "https://polygon.publicnode.com"
        private const val DEFAULT_WS_URL = "wss://polygon.publicnode.com"
        
        // 主流服务商 URL 模板
        private val PROVIDER_HTTP_TEMPLATES = mapOf(
            RpcProviderType.ALCHEMY to "https://polygon-mainnet.g.alchemy.com/v2/{apiKey}",
            RpcProviderType.INFURA to "https://polygon-mainnet.infura.io/v3/{apiKey}",
            RpcProviderType.QUICKNODE to "https://your-endpoint.quiknode.pro/{apiKey}/",
            RpcProviderType.CHAINSTACK to "https://polygon-mainnet.core.chainstack.com/{apiKey}",
            RpcProviderType.GETBLOCK to "https://go.getblock.io/{apiKey}/"
        )
        
        private val PROVIDER_WS_TEMPLATES = mapOf(
            RpcProviderType.ALCHEMY to "wss://polygon-mainnet.g.alchemy.com/v2/{apiKey}",
            RpcProviderType.INFURA to "wss://polygon-mainnet.infura.io/ws/v3/{apiKey}",
            RpcProviderType.QUICKNODE to "wss://your-endpoint.quiknode.pro/{apiKey}/",
            RpcProviderType.CHAINSTACK to "wss://ws-polygon-mainnet.core.chainstack.com/{apiKey}",
            RpcProviderType.GETBLOCK to "wss://go.getblock.io/{apiKey}/"
        )
    }
    
    /**
     * 获取所有节点配置（不包含默认节点）
     * 默认节点作为兜底，不应该返回给前端
     */
    fun getAllNodes(): List<RpcNodeConfig> {
        val allNodes = rpcNodeConfigRepository.findAllByOrderByPriorityAsc()
        
        // 过滤掉默认节点，只返回用户配置的节点
        return allNodes.filterNot { isDefaultNode(it) }
    }
    
    /**
     * 获取所有节点配置（包含默认节点，用于内部使用）
     * 只返回启用的节点，禁用的节点会被忽略
     * 默认节点始终排在最后
     */
    fun getAllNodesWithDefault(): List<RpcNodeConfig> {
        // 只查询启用的节点
        val allNodes = rpcNodeConfigRepository.findAllByEnabledTrueOrderByPriorityAsc()
        
        // 分离默认节点和用户配置的节点
        val (defaultNodes, userNodes) = allNodes.partition { isDefaultNode(it) }
        
        // 返回用户配置的节点，默认节点排在最后（如果存在）
        return userNodes + defaultNodes
    }
    
    /**
     * 判断是否是默认节点
     */
    private fun isDefaultNode(node: RpcNodeConfig): Boolean {
        return node.httpUrl == DEFAULT_RPC_URL || 
               node.httpUrl == DEFAULT_RPC_URL.removeSuffix("/") ||
               (node.providerType == RpcProviderType.PUBLIC.name && 
                (node.httpUrl.contains("polygon.publicnode.com") || 
                 node.httpUrl.contains("publicnode.com")))
    }
    
    /**
     * 获取第一个可用的节点
     * 按优先级顺序遍历所有启用的节点，找到第一个真正可用的节点
     * 如果所有节点都不可用，返回默认节点
     * @return  可用节点的配置,如果没有可用节点则返回失败
     */
    fun getAvailableNode(excludedHttpUrls: Set<String> = emptySet()): Result<RpcNodeConfig> {
        return try {
            val excluded = excludedHttpUrls.map(::normalizeRpcUrl).toSet()
            val nodes = rpcNodeConfigRepository.findAllByEnabledTrueOrderByPriorityAsc()
                .filterNot { isDefaultNode(it) || normalizeRpcUrl(it.httpUrl) in excluded }
            
            if (nodes.isEmpty()) {
                if (normalizeRpcUrl(DEFAULT_RPC_URL) in excluded) {
                    return Result.failure(IllegalStateException("没有其他可用的 RPC 节点"))
                }
                logger.warn("没有其他启用的 RPC 节点，将使用默认节点")
                return Result.success(createDefaultNodeConfig())
            }
            
            // 优先使用最近检查状态为 HEALTHY 的节点
            val healthyNodes = nodes.filter { 
                it.lastCheckStatus == NodeHealthStatus.HEALTHY.name 
            }
            
            // 先尝试使用健康的节点（按优先级排序）
            for (node in healthyNodes) {
                try {
                    // 快速验证节点是否仍然可用（使用较短的超时时间）
                    val checkResult = validateNode(node.httpUrl, node.wsUrl).getOrNull()
                    if (checkResult != null && checkResult.status == NodeHealthStatus.HEALTHY) {
                        logger.debug("使用健康的 RPC 节点: ${node.name} (${node.httpUrl})")
                        return Result.success(node)
                    }
                } catch (e: Exception) {
                    logger.debug("节点 ${node.name} 验证失败，尝试下一个节点: ${e.message}")
                    // 继续尝试下一个节点
                }
            }
            
            // 如果没有健康的节点，尝试验证所有节点（按优先级）
            for (node in nodes) {
                try {
                    val checkResult = validateNode(node.httpUrl, node.wsUrl).getOrNull()
                    if (checkResult != null && checkResult.status == NodeHealthStatus.HEALTHY) {
                        logger.info("找到可用的 RPC 节点: ${node.name} (${node.httpUrl})")
                        return Result.success(node)
                    }
                } catch (e: Exception) {
                    logger.debug("节点 ${node.name} 验证失败，尝试下一个节点: ${e.message}")
                    // 继续尝试下一个节点
                }
            }
            
            // 所有节点都不可用，返回默认节点
            if (normalizeRpcUrl(DEFAULT_RPC_URL) in excluded) {
                return Result.failure(IllegalStateException("没有其他可用的 RPC 节点"))
            }
            logger.warn("所有启用的 RPC 节点都不可用，将使用默认节点: $DEFAULT_RPC_URL")
            Result.success(createDefaultNodeConfig())
        } catch (e: Exception) {
            logger.error("获取可用节点失败: ${e.message}", e)
            // 即使失败也返回默认节点，确保系统可用
            if (normalizeRpcUrl(DEFAULT_RPC_URL) in excludedHttpUrls.map(::normalizeRpcUrl)) {
                return Result.failure(e)
            }
            logger.warn("获取可用节点出现异常，使用默认节点作为兜底")
            Result.success(createDefaultNodeConfig())
        }
    }

    /**
     * 构造按请求执行故障切换的 RPC 客户端。写请求只会在发送前的链 ID 校验失败时换节点，
     * 避免超时后重发交易造成不确定副作用。
     */
    fun createFailoverRpcApi(): EthereumRpcApi {
        fun createCheckedApi(url: String): EthereumRpcApi =
            ChainIdCheckedRpcApi(url, retrofitFactory.createEthereumRpcApi(url))

        val firstUrl = getHttpUrl()
        return FailoverEthereumRpcApi(
            initialEndpoint = firstUrl to createCheckedApi(firstUrl),
            nextEndpoint = { excluded ->
                getAvailableNode(excluded).getOrNull()?.httpUrl?.let { url -> url to createCheckedApi(url) }
            }
        )
    }

    private fun normalizeRpcUrl(url: String): String {
        val trimmed = url.trim()
        val suffixIndex = listOf(trimmed.indexOf('?'), trimmed.indexOf('#'))
            .filter { it >= 0 }
            .minOrNull() ?: trimmed.length
        return trimmed.substring(0, suffixIndex).trimEnd('/') + trimmed.substring(suffixIndex)
    }
    
    /**
     * 创建默认节点配置
     * 用于兜底，确保系统始终有可用的 RPC 节点
     */
    private fun createDefaultNodeConfig(): RpcNodeConfig {
        return RpcNodeConfig(
            id = 0L,
            providerType = RpcProviderType.PUBLIC.name,
            name = "默认节点",
            httpUrl = DEFAULT_RPC_URL,
            wsUrl = DEFAULT_WS_URL,
            apiKey = null,
            enabled = true,
            priority = 9999,
            lastCheckTime = System.currentTimeMillis(),
            lastCheckStatus = NodeHealthStatus.HEALTHY.name,
            responseTimeMs = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 获取节点的 HTTP URL (如果没有配置,使用默认节点)
     */
    fun getHttpUrl(): String {
        val node = getAvailableNode().getOrNull()
        return node?.httpUrl ?: DEFAULT_RPC_URL
    }
    
    /**
     * 获取节点的 WebSocket URL (如果没有配置,使用默认节点)
     */
    fun getWsUrl(): String {
        val node = getAvailableNode().getOrNull()
        return node?.wsUrl ?: DEFAULT_WS_URL
    }

    /** Select a websocket endpoint while skipping nodes whose websocket transport failed recently. */
    fun getWebSocketEndpoint(excludedHttpUrls: Set<String> = emptySet()): Result<WebSocketEndpoint> =
        getAvailableNode(excludedHttpUrls).map { node ->
            WebSocketEndpoint(
                httpUrl = node.httpUrl,
                wsUrl = node.wsUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_WS_URL
            )
        }

    data class WebSocketEndpoint(val httpUrl: String, val wsUrl: String)

    /**
     * 节点校验结果（不落库）
     */
    data class ValidatedNode(
        val providerType: RpcProviderType = RpcProviderType.CUSTOM,
        val httpUrl: String = "",
        val wsUrl: String? = null,
        val checkResult: NodeCheckResult = NodeCheckResult(NodeHealthStatus.UNHEALTHY, "", 0L, null)
    )

    /**
     * 校验节点（纯校验，不写数据库）
     * 包括：URL 安全校验（拒绝内网/回环/元数据地址）、可用性校验、链 ID 必须为 Polygon 主网（0x89）
     */
    fun validateNodeRequest(request: AddRpcNodeRequest): Result<ValidatedNode> {
        return try {
            // 1. 验证请求
            val providerType = try {
                RpcProviderType.valueOf(request.providerType.uppercase())
            } catch (e: IllegalArgumentException) {
                return Result.failure(IllegalArgumentException("不支持的服务商类型: ${request.providerType}"))
            }
            
            // 2. 构建 HTTP 和 WS URL
            val (httpUrl, wsUrl) = if (providerType == RpcProviderType.CUSTOM) {
                // 自定义节点,使用用户提供的 URL
                if (request.httpUrl.isNullOrBlank()) {
                    return Result.failure(IllegalArgumentException("自定义节点必须提供 HTTP URL"))
                }
                Pair(request.httpUrl, request.wsUrl)
            } else {
                // 主流服务商,使用模板生成 URL
                if (request.apiKey.isNullOrBlank()) {
                    return Result.failure(IllegalArgumentException("${request.providerType} 节点必须提供 API Key"))
                }
                val httpTemplate = PROVIDER_HTTP_TEMPLATES[providerType]
                    ?: return Result.failure(IllegalArgumentException("未找到 ${request.providerType} 的 HTTP URL 模板"))
                val wsTemplate = PROVIDER_WS_TEMPLATES[providerType]
                Pair(
                    httpTemplate.replace("{apiKey}", request.apiKey),
                    wsTemplate?.replace("{apiKey}", request.apiKey)
                )
            }
            
            // 3. URL 安全校验（防止 SSRF：只允许公网 http(s)/ws(s) 地址）
            RpcUrlSafety.checkPublicUrl(httpUrl, allowedSchemes = setOf("http", "https"))
            if (!wsUrl.isNullOrBlank()) {
                RpcUrlSafety.checkPublicUrl(wsUrl, allowedSchemes = setOf("ws", "wss"))
            }

            // 4. 校验节点可用性与链 ID
            val validationResult = validateNode(httpUrl, wsUrl, verifyChainId = true)
            if (validationResult.isFailure) {
                return Result.failure(validationResult.exceptionOrNull() ?: Exception("节点验证失败"))
            }
            
            val checkResult = validationResult.getOrNull()!!
            
            // 检查节点是否健康，如果不健康则不允许添加
            if (checkResult.status != NodeHealthStatus.HEALTHY) {
                return Result.failure(IllegalArgumentException("节点不可用: ${checkResult.message}"))
            }
            Result.success(ValidatedNode(providerType, httpUrl, wsUrl, checkResult))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("校验节点失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 添加节点
     */
    @Transactional
    fun addNode(request: AddRpcNodeRequest): Result<RpcNodeConfig> {
        return try {
            val validated = validateNodeRequest(request).getOrElse { return Result.failure(it) }
            val providerType = validated.providerType
            val httpUrl = validated.httpUrl
            val wsUrl = validated.wsUrl
            val checkResult = validated.checkResult
            
            // 1. 加密 API Key (如果有)
            val encryptedApiKey = request.apiKey?.let { cryptoUtils.encrypt(it) }
            
            // 2. 获取当前最大优先级
            val maxPriority = rpcNodeConfigRepository.findAllByOrderByPriorityAsc()
                .maxOfOrNull { it.priority } ?: 0
            
            // 3. 创建节点配置
            val node = RpcNodeConfig(
                providerType = providerType.name,
                name = request.name,
                httpUrl = httpUrl,
                wsUrl = wsUrl,
                apiKey = encryptedApiKey,
                enabled = true,
                priority = maxPriority + 1,  // 新节点放到最后
                lastCheckTime = checkResult.checkTime,
                lastCheckStatus = checkResult.status.name,
                responseTimeMs = checkResult.responseTimeMs
            )
            
            val savedNode = rpcNodeConfigRepository.save(node)
            logger.info("成功添加 RPC 节点: ${savedNode.name} (${savedNode.httpUrl})")
            Result.success(savedNode)
        } catch (e: Exception) {
            logger.error("添加节点失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新节点
     * 默认节点不允许更新（作为兜底，不应该返回给前端）
     */
    @Transactional
    fun updateNode(request: UpdateRpcNodeRequest): Result<RpcNodeConfig> {
        return try {
            val node = rpcNodeConfigRepository.findById(request.id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("节点不存在: ${request.id}"))
            
            // 如果是默认节点，不允许更新
            if (isDefaultNode(node)) {
                return Result.failure(IllegalArgumentException("默认节点不允许更新"))
            }
            
            // 检查是否禁用节点，如果是则清理缓存
            val isDisabling = request.enabled == false && node.enabled == true
            if (isDisabling) {
                logger.info("节点被禁用，清理 RPC 缓存: ${node.httpUrl}")
                retrofitFactory.clearRpcApiCache(node.httpUrl)
            }
            
            // 更新字段
            val updatedNode = node.copy(
                name = request.name ?: node.name,
                enabled = request.enabled ?: node.enabled,
                priority = request.priority ?: node.priority,
                updatedAt = System.currentTimeMillis()
            )
            
            val savedNode = rpcNodeConfigRepository.save(updatedNode)
            logger.info("成功更新 RPC 节点: ${savedNode.name}, enabled=${savedNode.enabled}")
            Result.success(savedNode)
        } catch (e: Exception) {
            logger.error("更新节点失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 删除节点
     * 默认节点不允许删除（作为兜底，不应该返回给前端）
     */
    @Transactional
    fun deleteNode(id: Long): Result<Unit> {
        return try {
            val node = rpcNodeConfigRepository.findById(id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("节点不存在: $id"))
            
            // 如果是默认节点，不允许删除
            if (isDefaultNode(node)) {
                return Result.failure(IllegalArgumentException("默认节点不允许删除"))
            }
            
            // 清理 RPC 缓存
            logger.info("删除节点，清理 RPC 缓存: ${node.httpUrl}")
            retrofitFactory.clearRpcApiCache(node.httpUrl)
            
            rpcNodeConfigRepository.delete(node)
            logger.info("成功删除 RPC 节点: ${node.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除节点失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新节点优先级
     * 默认节点不允许更新优先级（作为兜底，始终排在最后）
     */
    @Transactional
    fun updatePriority(id: Long, priority: Int): Result<Unit> {
        return try {
            val node = rpcNodeConfigRepository.findById(id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("节点不存在: $id"))
            
            // 如果是默认节点，不允许更新优先级
            if (isDefaultNode(node)) {
                return Result.failure(IllegalArgumentException("默认节点不允许更新优先级"))
            }
            
            val updatedNode = node.copy(
                priority = priority,
                updatedAt = System.currentTimeMillis()
            )
            
            rpcNodeConfigRepository.save(updatedNode)
            logger.info("成功更新节点优先级: ${node.name} -> $priority")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("更新节点优先级失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 检查单个节点健康状态
     * 默认节点不应该被检查（作为兜底，不应该返回给前端）
     */
    @Transactional
    fun checkNodeHealth(nodeId: Long): Result<NodeCheckResult> {
        return try {
            val node = rpcNodeConfigRepository.findById(nodeId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("节点不存在: $nodeId"))
            
            // 如果是默认节点，不允许检查
            if (isDefaultNode(node)) {
                return Result.failure(IllegalArgumentException("默认节点不允许检查"))
            }
            
            val checkResult = validateNode(node.httpUrl, node.wsUrl, verifyChainId = true).getOrThrow()
            
            // 更新节点健康状态
            val updatedNode = node.copy(
                lastCheckTime = checkResult.checkTime,
                lastCheckStatus = checkResult.status.name,
                responseTimeMs = checkResult.responseTimeMs,
                updatedAt = System.currentTimeMillis()
            )
            
            rpcNodeConfigRepository.save(updatedNode)
            logger.info("检查节点健康状态: ${node.name} -> ${checkResult.status}")
            Result.success(checkResult)
        } catch (e: Exception) {
            logger.error("检查节点健康状态失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 批量检查所有节点健康状态（不包含默认节点和禁用的节点）
     * 默认节点作为兜底，不应该返回给前端
     * 禁用的节点不应该被检查
     */
    @Transactional
    fun checkAllNodesHealth(): Result<Map<Long, NodeCheckResult>> {
        return try {
            // 只查询启用的节点，过滤掉默认节点和禁用的节点
            val allNodes = rpcNodeConfigRepository.findAllByEnabledTrueOrderByPriorityAsc()
            // 过滤掉默认节点，只检查用户配置的启用节点
            val nodes = allNodes.filterNot { isDefaultNode(it) }
            val results = mutableMapOf<Long, NodeCheckResult>()
            
            for (node in nodes) {
                try {
                    val checkResult = validateNode(node.httpUrl, node.wsUrl, verifyChainId = true).getOrNull()
                    if (checkResult != null) {
                        results[node.id!!] = checkResult
                        
                        // 更新节点状态
                        val updatedNode = node.copy(
                            lastCheckTime = checkResult.checkTime,
                            lastCheckStatus = checkResult.status.name,
                            responseTimeMs = checkResult.responseTimeMs,
                            updatedAt = System.currentTimeMillis()
                        )
                        rpcNodeConfigRepository.save(updatedNode)
                    }
                } catch (e: Exception) {
                    logger.error("检查节点 ${node.name} 失败: ${e.message}", e)
                }
            }
            
            Result.success(results)
        } catch (e: Exception) {
            logger.error("批量检查节点健康状态失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 校验节点可用性
     * 调用 eth_blockNumber 验证节点是否可用
     */
    private fun validateNode(httpUrl: String, wsUrl: String?, verifyChainId: Boolean = false): Result<NodeCheckResult> {
        return try {
            logger.debug("开始验证节点: $httpUrl")
            
            // 创建临时 RPC API
            val rpcApi = retrofitFactory.createEthereumRpcApi(httpUrl)
            
            // 调用 eth_blockNumber
            val startTime = System.currentTimeMillis()
            val rpcRequest = JsonRpcRequest(
                method = "eth_blockNumber",
                params = emptyList()
            )
            
            val response = kotlinx.coroutines.runBlocking { rpcApi.call(rpcRequest) }
            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            
            if (!response.isSuccessful || response.body() == null) {
                logger.warn("节点验证失败: HTTP ${response.code()}")
                return Result.success(NodeCheckResult(
                    status = NodeHealthStatus.UNHEALTHY,
                    message = "HTTP 请求失败: ${response.code()}",
                    checkTime = System.currentTimeMillis(),
                    responseTimeMs = responseTime
                ))
            }
            
            val rpcResponse = response.body()!!
            val rpcError = rpcResponse.error
            if (rpcError != null) {
                logger.warn("节点验证失败: RPC 错误 ${rpcError.message}")
                return Result.success(NodeCheckResult(
                    status = NodeHealthStatus.UNHEALTHY,
                    message = "RPC 错误: ${rpcError.message}",
                    checkTime = System.currentTimeMillis(),
                    responseTimeMs = responseTime
                ))
            }
            
            val blockNumber = rpcResponse.result?.asString
            if (blockNumber.isNullOrBlank()) {
                return Result.success(NodeCheckResult(
                    status = NodeHealthStatus.UNHEALTHY,
                    message = "区块号为空",
                    checkTime = System.currentTimeMillis(),
                    responseTimeMs = responseTime
                ))
            }

            // 只允许 Polygon 主网节点（eth_chainId == 0x89），误加其他链会导致代理地址等计算错误
            // 添加/校验/健康检查时执行；获取可用节点的热路径不重复调用
            val chainIdResponse = if (!verifyChainId) null else kotlinx.coroutines.runBlocking {
                rpcApi.call(JsonRpcRequest(method = "eth_chainId", params = emptyList()))
            }
            val chainId = chainIdResponse?.body()?.takeIf { chainIdResponse.isSuccessful && it.error == null }
                ?.result?.takeIf { it.isJsonPrimitive }?.asString
            if (verifyChainId && !RpcUrlSafety.isPolygonMainnetChainId(chainId)) {
                logger.warn("节点链 ID 不是 Polygon 主网: $httpUrl, chainId=$chainId")
                return Result.success(NodeCheckResult(
                    status = NodeHealthStatus.UNHEALTHY,
                    message = "节点不是 Polygon 主网（chainId=${chainId ?: "未知"}，需要 0x89）",
                    checkTime = System.currentTimeMillis(),
                    responseTimeMs = responseTime
                ))
            }
            
            logger.info("节点验证成功: $httpUrl, 区块号: $blockNumber, 响应时间: ${responseTime}ms")
            Result.success(NodeCheckResult(
                status = NodeHealthStatus.HEALTHY,
                message = "节点可用, 当前区块: $blockNumber",
                checkTime = System.currentTimeMillis(),
                responseTimeMs = responseTime,
                blockNumber = blockNumber
            ))
        } catch (e: Exception) {
            logger.error("验证节点失败: ${e.message}", e)
            Result.success(NodeCheckResult(
                status = NodeHealthStatus.UNHEALTHY,
                message = "验证失败: ${e.message}",
                checkTime = System.currentTimeMillis(),
                responseTimeMs = null
            ))
        }
    }
}

/**
 * 添加节点请求
 */
data class AddRpcNodeRequest(
    val providerType: String,  // ALCHEMY, INFURA, QUICKNODE, CHAINSTACK, GETBLOCK, CUSTOM, PUBLIC
    val name: String,
    val apiKey: String? = null,  // 主流服务商需要
    val httpUrl: String? = null,  // CUSTOM 需要
    val wsUrl: String? = null
)

/**
 * 更新节点请求
 */
data class UpdateRpcNodeRequest(
    val id: Long,
    val name: String? = null,
    val enabled: Boolean? = null,
    val priority: Int? = null
)

/**
 * 节点检查结果
 */
data class NodeCheckResult(
    val status: NodeHealthStatus,
    val message: String,
    val checkTime: Long,
    val responseTimeMs: Int?,
    val blockNumber: String? = null
)

/**
 * RPC 节点 URL 安全校验（防止 SSRF）
 */
object RpcUrlSafety {

    /** Polygon 主网链 ID */
    const val POLYGON_MAINNET_CHAIN_ID = 137L

    /**
     * 判断 eth_chainId 返回值是否为 Polygon 主网（0x89）
     */
    fun isPolygonMainnetChainId(chainIdHex: String?): Boolean {
        val value = chainIdHex?.trim()?.lowercase() ?: return false
        if (!value.startsWith("0x")) return false
        return value.removePrefix("0x").toLongOrNull(16) == POLYGON_MAINNET_CHAIN_ID
    }

    /**
     * 校验 URL 只指向公网地址
     * 拒绝：非允许协议、缺少主机、带用户信息、解析到回环/私网/链路本地/组播/CGNAT/ULA(fc00::/7)/云元数据地址
     * @throws IllegalArgumentException 校验失败
     */
    fun checkPublicUrl(url: String, allowedSchemes: Set<String>) {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("无效的节点 URL")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in allowedSchemes) {
            throw IllegalArgumentException("节点 URL 协议不支持，仅允许 ${allowedSchemes.joinToString("/")}")
        }
        if (uri.rawUserInfo != null) {
            throw IllegalArgumentException("节点 URL 不允许包含用户信息")
        }
        val host = uri.host?.trim('[', ']')
        if (host.isNullOrBlank()) {
            throw IllegalArgumentException("节点 URL 缺少主机名")
        }
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            throw IllegalArgumentException("无法解析节点主机名")
        }
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            throw IllegalArgumentException("节点地址不允许指向内网、回环或元数据地址")
        }
    }

    /**
     * 是否为公网地址
     */
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address
        if (address is Inet4Address) {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            val b2 = bytes[2].toInt() and 0xff
            return when {
                b0 == 0 -> false                              // 0.0.0.0/8
                b0 == 100 && b1 in 64..127 -> false          // 100.64.0.0/10 CGNAT
                b0 == 169 && b1 == 254 -> false              // 169.254.0.0/16 链路本地/云元数据
                b0 == 192 && b1 == 0 && b2 == 0 -> false     // 192.0.0.0/24
                b0 == 198 && (b1 == 18 || b1 == 19) -> false // 198.18.0.0/15
                b0 >= 224 -> false                            // 组播与保留地址
                else -> true
            }
        }
        if (address is Inet6Address) {
            val b0 = bytes[0].toInt() and 0xff
            // fc00::/7 ULA（包括 fd00:ec2::254 等云元数据地址）
            if ((b0 and 0xfe) == 0xfc) return false
            // IPv4 映射地址按内嵌 IPv4 判断
            val isMapped = bytes.copyOfRange(0, 10).all { it.toInt() == 0 } &&
                (bytes[10].toInt() and 0xff) == 0xff && (bytes[11].toInt() and 0xff) == 0xff
            if (isMapped) {
                return isPublicAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
            }
            return true
        }
        return false
    }
}
