package com.wrbug.polymarketbot.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.enums.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 管理员权限拦截器
 * 系统配置、代理、RPC 节点、通知配置、API 健康检查等接口只允许管理员（默认账户）访问
 * 依赖 JwtAuthenticationInterceptor 写入的 isAdmin 属性，拦截范围由 WebMvcConfig 配置
 */
@Component
class AdminAuthorizationInterceptor(
    private val messageSource: MessageSource
) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(AdminAuthorizationInterceptor::class.java)
    private val objectMapper = ObjectMapper()

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        // 允许 OPTIONS 请求（CORS 预检请求）
        if (request.method == "OPTIONS") {
            return true
        }
        if (request.getAttribute(JwtAuthenticationInterceptor.ATTR_IS_ADMIN) == true) {
            return true
        }
        logger.warn("非管理员访问受限接口: username=${request.getAttribute("username")}, path=${request.requestURI}")
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        val apiResponse: ApiResponse<Unit> = ApiResponse.error(ErrorCode.AUTH_PERMISSION_DENIED, messageSource = messageSource)
        response.writer.write(objectMapper.writeValueAsString(apiResponse))
        response.writer.flush()
        return false
    }
}
