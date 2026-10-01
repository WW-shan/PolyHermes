package com.wrbug.polymarketbot.service.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.InetAddress

/**
 * 客户端 IP 识别
 * 只有请求直接来自回环地址或配置的可信代理（security.trusted-proxies）时才信任 X-Real-IP / X-Forwarded-For，
 * 否则一律使用 remoteAddr，防止客户端伪造转发头绕过限速
 */
@Component
class ClientIpResolver(
    @Value("\${security.trusted-proxies:}") trustedProxiesConfig: String = ""
) {

    private val trustedProxies: Set<String> = trustedProxiesConfig.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun resolve(request: HttpServletRequest): String {
        val remoteAddr = request.remoteAddr ?: "unknown"
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr
        }
        // nginx 使用 X-Real-IP $remote_addr 覆盖客户端传入的值
        val realIp = request.getHeader("X-Real-IP")?.trim()
        if (!realIp.isNullOrEmpty() && !realIp.equals("unknown", ignoreCase = true)) {
            return realIp
        }
        // 兼容其他反向代理：取最右侧（离后端最近的代理追加的）地址，最左侧可被客户端伪造
        val forwarded = request.getHeader("X-Forwarded-For")
            ?.split(",")
            ?.map { it.trim() }
            ?.lastOrNull { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
        return forwarded ?: remoteAddr
    }

    private fun isTrustedProxy(address: String): Boolean {
        if (address in trustedProxies) {
            return true
        }
        return try {
            InetAddress.getByName(address).isLoopbackAddress
        } catch (e: Exception) {
            false
        }
    }
}
