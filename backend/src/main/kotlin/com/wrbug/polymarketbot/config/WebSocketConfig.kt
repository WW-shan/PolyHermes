package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.websocket.UnifiedWebSocketHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * WebSocket 配置类
 * 用于配置 WebSocket 端点
 */
@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val unifiedWebSocketHandler: UnifiedWebSocketHandler,
    private val webSocketAuthInterceptor: WebSocketAuthInterceptor,
    @Value("\${websocket.allowed-origins:}") private val allowedOriginsConfig: String
) : WebSocketConfigurer {

    /**
     * 获取允许的 WebSocket 来源
     * 如果配置了 WEBSOCKET_ALLOWED_ORIGINS 环境变量，使用配置的域名
     * 否则使用 setAllowedOriginPatterns 允许同源访问
     */
    private fun getAllowedOrigins(): Array<String> {
        return if (allowedOriginsConfig.isNotBlank()) {
            allowedOriginsConfig.split(",").map { it.trim() }.toTypedArray()
        } else {
            emptyArray()
        }
    }

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        val origins = getAllowedOrigins()

        // 原 /ws/polymarket 转发端点无鉴权、前端未使用且每个连接都会开启上游 WS，已移除

        // 统一 WebSocket 端点（所有推送服务统一使用此路径，通过 channel 区分）
        // 支持的频道：position（仓位推送）、order（订单推送）等
        // 需要 JWT 鉴权
        val unifiedHandler = registry.addHandler(unifiedWebSocketHandler, "/ws")
            .addInterceptors(webSocketAuthInterceptor)
        if (origins.isNotEmpty()) {
            unifiedHandler.setAllowedOrigins(*origins)
        } else {
            unifiedHandler.setAllowedOriginPatterns("*")
        }
    }
}

