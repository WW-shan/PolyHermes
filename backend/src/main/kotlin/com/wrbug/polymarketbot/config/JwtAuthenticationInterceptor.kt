package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.repository.UserRepository
import com.wrbug.polymarketbot.util.JwtUtils
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * JWT认证拦截器
 */
@Component
class JwtAuthenticationInterceptor(
    private val jwtUtils: JwtUtils,
    private val userRepository: UserRepository
) : HandlerInterceptor {
    
    companion object {
        /** Request 属性：当前用户是否为管理员（默认账户） */
        const val ATTR_IS_ADMIN = "isAdmin"
    }

    private val logger = LoggerFactory.getLogger(JwtAuthenticationInterceptor::class.java)
    private val objectMapper = ObjectMapper()
    
    /**
     * 注意：拦截范围与免认证白名单统一由 WebMvcConfig 的 addPathPatterns/excludePathPatterns 配置
     * （Spring 使用解码并去除 ";" 参数后的路径匹配），这里不再按原始 requestURI 做前缀判断或放行，
     * 否则 "/api;/xxx"、"/%61pi/xxx" 等写法可以绕过鉴权
     */
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val path = request.requestURI

        // 允许 OPTIONS 请求（CORS 预检请求）
        if (request.method == "OPTIONS") {
            return true
        }
        
        // 从请求头获取token
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendAuthError(response, "缺少认证令牌")
            return false
        }
        
        val token = authHeader.substring(7) // 移除 "Bearer " 前缀
        
        // 验证token
        if (!jwtUtils.validateToken(token)) {
            logger.warn("Token验证失败: path=$path")
            sendAuthError(response, "认证令牌无效或已过期")
            return false
        }
        
        // 验证tokenVersion（检查token是否因密码修改而失效）
        val username = jwtUtils.getUsernameFromToken(token)
        if (username == null) {
            logger.warn("Token中缺少用户名: path=$path")
            sendAuthError(response, "认证令牌无效或已过期")
            return false
        }
        // 用户不存在（例如已被删除）时直接鉴权失败，避免被删除用户的旧 token 继续可用
        val user = userRepository.findByUsername(username)
        if (user == null) {
            logger.warn("Token对应的用户不存在，token已失效: username=$username, path=$path")
            sendAuthError(response, "认证令牌已失效，请重新登录")
            return false
        }
        val tokenVersion = jwtUtils.getTokenVersionFromToken(token)
        if (tokenVersion == null || tokenVersion != user.tokenVersion) {
            logger.warn("Token版本不匹配，token已失效: username=$username, tokenVersion=$tokenVersion, userTokenVersion=${user.tokenVersion}, path=$path")
            sendAuthError(response, "认证令牌已失效，请重新登录")
            return false
        }

        // 检查是否需要刷新token（使用超过1天但未过期）
        if (jwtUtils.isTokenExpiring(token)) {
            val newToken = jwtUtils.generateToken(username, user.tokenVersion)
            // 在响应头中返回新token
            response.setHeader("X-New-Token", newToken)
            logger.debug("Token自动刷新: username=$username, path=$path")
        }

        // 将用户名与管理员标记存入Request属性，供后续使用
        request.setAttribute("username", username)
        request.setAttribute(ATTR_IS_ADMIN, user.isDefault)

        return true
    }
    
    /**
     * 发送认证错误响应
     */
    private fun sendAuthError(response: HttpServletResponse, message: String) {
        response.status = HttpServletResponse.SC_OK
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        
        val apiResponse: ApiResponse<Unit> = ApiResponse.authError(message)
        val json = objectMapper.writeValueAsString(apiResponse)
        response.writer.write(json)
        response.writer.flush()
    }
}
