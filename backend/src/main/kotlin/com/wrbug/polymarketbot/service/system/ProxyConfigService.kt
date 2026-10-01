package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.ProxyConfig
import com.wrbug.polymarketbot.repository.ProxyConfigRepository
import com.wrbug.polymarketbot.service.copytrading.monitor.CopyTradingWebSocketService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderPushService
import com.wrbug.polymarketbot.util.ProxyConfigProvider
import com.wrbug.polymarketbot.event.ProxyConfigChangedEvent
import okhttp3.*
import org.slf4j.LoggerFactory
import org.springframework.beans.BeansException
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 代理配置服务
 */
@Service
class ProxyConfigService(
    private val proxyConfigRepository: ProxyConfigRepository
) : ApplicationContextAware {
    
    private var applicationContext: ApplicationContext? = null
    
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }
    
    private val logger = LoggerFactory.getLogger(ProxyConfigService::class.java)
    
    /**
     * 获取当前代理配置
     * 返回 HTTP 类型的代理配置（无论是否启用），以便前端可以显示和编辑配置
     */
    fun getProxyConfig(): ProxyConfigDto? {
        // 优先查找 HTTP 类型的代理配置（无论是否启用）
        val config = proxyConfigRepository.findByType("HTTP")
            ?: return null
        
        // 如果配置是启用的，更新 ProxyConfigProvider
        if (config.enabled) {
            ProxyConfigProvider.setProxyConfig(config)
        } else {
            // 如果配置是禁用的，清除 ProxyConfigProvider（不使用代理）
            ProxyConfigProvider.setProxyConfig(null)
        }
        
        return toDto(config)
    }
    
    /**
     * 初始化代理配置（应用启动时调用）
     */
    fun initProxyConfig() {
        val config = proxyConfigRepository.findByEnabledTrue()
        ProxyConfigProvider.setProxyConfig(config)
        if (config != null) {
            logger.info("初始化代理配置：type=${config.type}, host=${config.host}, port=${config.port}, enabled=${config.enabled}")
        } else {
            logger.info("未找到启用的代理配置")
        }
    }
    
    /**
     * 获取所有代理配置（用于管理）
     */
    fun getAllProxyConfigs(): List<ProxyConfigDto> {
        return proxyConfigRepository.findAll().map { toDto(it) }
    }
    
    /**
     * 创建或更新 HTTP 代理配置
     */
    @Transactional
    fun saveHttpProxyConfig(request: HttpProxyConfigRequest): Result<ProxyConfigDto> {
        return try {
            // 验证参数
            if (request.host.isBlank()) {
                return Result.failure(IllegalArgumentException("代理主机不能为空"))
            }
            if (request.port <= 0 || request.port > 65535) {
                return Result.failure(IllegalArgumentException("代理端口必须在 1-65535 之间"))
            }
            
            // 查找现有的 HTTP 代理配置（无论是否启用）
            val existing = proxyConfigRepository.findByType("HTTP")
            
            val config = if (existing != null) {
                // 更新现有配置
                val password = if (request.password != null && request.password.isNotBlank()) {
                    request.password  // 明文存储（与 Account 实体保持一致）
                } else {
                    existing.password  // 如果密码为空，保持原密码
                }
                
                existing.copy(
                    enabled = request.enabled,
                    host = request.host,
                    port = request.port,
                    username = request.username?.takeIf { it.isNotBlank() },
                    password = password,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                // 创建新配置
                val password = if (request.password != null && request.password.isNotBlank()) {
                    request.password  // 明文存储（与 Account 实体保持一致）
                } else {
                    null
                }
                
                ProxyConfig(
                    type = "HTTP",
                    enabled = request.enabled,
                    host = request.host,
                    port = request.port,
                    username = request.username?.takeIf { it.isNotBlank() },
                    password = password
                )
            }
            
            val saved = proxyConfigRepository.save(config)
            logger.info("保存 HTTP 代理配置成功：host=${saved.host}, port=${saved.port}, enabled=${saved.enabled}")
            
            // 更新 ProxyConfigProvider
            if (saved.enabled) {
                ProxyConfigProvider.setProxyConfig(saved)
            } else {
                // 如果禁用了代理，清除配置
                ProxyConfigProvider.setProxyConfig(null)
            }
            
            // 通知 HTTP/WS 客户端按新代理重建连接，并触发 WebSocket 重连
            publishProxyConfigChanged()
            triggerWebSocketReconnect()
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("保存 HTTP 代理配置失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 检查代理是否可用
     * 使用配置的代理请求 Polymarket 健康检查接口
     */
    fun checkProxy(): ProxyCheckResponse {
        return try {
            val config = proxyConfigRepository.findByEnabledTrue()
                ?: return ProxyCheckResponse.create(
                    success = false,
                    message = "未配置代理或代理未启用"
                )
            
            if (config.type != "HTTP") {
                return ProxyCheckResponse.create(
                    success = false,
                    message = "当前仅支持检查 HTTP 代理（订阅代理检查功能待实现）"
                )
            }
            
            if (config.host == null || config.port == null) {
                return ProxyCheckResponse.create(
                    success = false,
                    message = "代理配置不完整：缺少主机或端口"
                )
            }
            
            // 创建代理
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(config.host, config.port))
            
            // 创建 OkHttpClient
            val clientBuilder = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
            
            // HTTP CONNECT 代理只转发 TLS 隧道，保持默认证书与主机名校验
            
            // 如果配置了用户名和密码，添加代理认证
            if (config.username != null && config.password != null) {
                clientBuilder.proxyAuthenticator { _, response ->
                    val credential = okhttp3.Credentials.basic(config.username, config.password)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
            
            val client = clientBuilder.build()
            
            // 请求 Polymarket 健康检查接口
            val request = Request.Builder()
                .url("https://data-api.polymarket.com/")
                .get()
                .build()
            
            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val responseTime = System.currentTimeMillis() - startTime
            
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && responseBody != null) {
                // 检查响应内容是否为 {"data": "OK"}
                if (responseBody.contains("\"data\"") && responseBody.contains("OK")) {
                    logger.info("代理检查成功：host=${config.host}, port=${config.port}, responseTime=${responseTime}ms")
                    ProxyCheckResponse.create(
                        success = true,
                        message = "代理连接成功",
                        responseTime = responseTime
                    )
                } else {
                    ProxyCheckResponse.create(
                        success = false,
                        message = "代理连接成功，但响应格式不正确：$responseBody",
                        responseTime = responseTime
                    )
                }
            } else {
                ProxyCheckResponse.create(
                    success = false,
                    message = "代理连接失败：HTTP ${response.code} ${response.message}",
                    responseTime = responseTime
                )
            }
        } catch (e: Exception) {
            logger.error("代理检查异常", e)
            ProxyCheckResponse.create(
                success = false,
                message = "代理检查失败：${e.message}"
            )
        }
    }
    
    /**
     * 删除代理配置
     */
    @Transactional
    fun deleteProxyConfig(id: Long): Result<Unit> {
        return try {
            val config = proxyConfigRepository.findById(id)
                .orElse(null) ?: return Result.failure(IllegalArgumentException("代理配置不存在"))
            
            val wasEnabled = config.enabled
            proxyConfigRepository.delete(config)
            logger.info("删除代理配置成功：id=$id, type=${config.type}")
            
            // 如果删除的是启用的代理配置，清除 ProxyConfigProvider
            if (wasEnabled) {
                ProxyConfigProvider.setProxyConfig(null)
                publishProxyConfigChanged()
                // 触发 WebSocket 重连（使用新配置，即无代理）
                triggerWebSocketReconnect()
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除代理配置失败：id=$id", e)
            Result.failure(e)
        }
    }
    
    /**
     * 发布代理配置变更事件（RetrofitFactory 清理缓存客户端，WS 服务监听后重连）
     */
    private fun publishProxyConfigChanged() {
        try {
            applicationContext?.publishEvent(ProxyConfigChangedEvent())
        } catch (e: Exception) {
            logger.error("发布代理配置变更事件失败", e)
        }
    }

    /**
     * 触发所有 WebSocket 重连（使用新代理配置）
     */
    private fun triggerWebSocketReconnect() {
        try {
            val context = applicationContext ?: return
            
            // 重连订单推送服务的 WebSocket 连接
            try {
                val orderPushService = context.getBean(OrderPushService::class.java)
                kotlinx.coroutines.runBlocking {
                    try {
                        orderPushService.reconnectAllAccounts()
                        logger.info("已触发订单推送服务 WebSocket 重连")
                    } catch (e: Exception) {
                        logger.error("触发订单推送服务重连失败", e)
                    }
                }
            } catch (e: BeansException) {
                logger.debug("订单推送服务未找到，跳过重连", e)
            }
            
            // 重连跟单 WebSocket 服务的连接
            try {
                val copyTradingWebSocketService = context.getBean(CopyTradingWebSocketService::class.java)
                try {
                    copyTradingWebSocketService.reconnectAll()
                    logger.info("已触发跟单 WebSocket 服务重连")
                } catch (e: Exception) {
                    logger.error("触发跟单 WebSocket 服务重连失败", e)
                }
            } catch (e: BeansException) {
                logger.debug("跟单 WebSocket 服务未找到，跳过重连", e)
            }
        } catch (e: Exception) {
            logger.error("触发 WebSocket 重连失败", e)
        }
    }
    
    /**
     * 转换为 DTO（不包含密码）
     */
    private fun toDto(config: ProxyConfig): ProxyConfigDto {
        return ProxyConfigDto(
            id = config.id,
            type = config.type,
            enabled = config.enabled,
            host = config.host,
            port = config.port,
            username = config.username,
            subscriptionUrl = config.subscriptionUrl,
            lastSubscriptionUpdate = config.lastSubscriptionUpdate,
            createdAt = config.createdAt,
            updatedAt = config.updatedAt
        )
    }
}

