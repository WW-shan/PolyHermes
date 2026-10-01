package com.wrbug.polymarketbot.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.enums.ErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets

/**
 * API 路径安全过滤器
 * 对指向 /api 的请求，默认拒绝包含 ";"、任何百分号编码、"\"、"//"、"/./"、"/../" 等可疑写法，
 * 以及大小写或百分号编码变体（如 "/API/x"、"/%61pi/x"），防止绕过基于路径的鉴权
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiPathSecurityFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(ApiPathSecurityFilter::class.java)
    private val objectMapper = ObjectMapper()

    companion object {
        // 所有 API 路径都是纯 ASCII，路径部分出现任何百分号编码都直接拒绝（查询参数不在 requestURI 中，不受影响）
        private val SUSPICIOUS_TOKENS = listOf(";", "%", "\\", "//", "/./", "/../")

        /**
         * 判断原始 requestURI 是否为可疑的 /api 请求
         * @return true 表示应拒绝
         */
        fun isSuspiciousApiPath(rawUri: String): Boolean {
            val normalized = normalize(rawUri)
            val isApi = normalized == "/api" || normalized.startsWith("/api/")
            if (!isApi) {
                return false
            }
            val lowerRaw = rawUri.lowercase()
            if (SUSPICIOUS_TOKENS.any { lowerRaw.contains(it) }) {
                return true
            }
            if (lowerRaw.endsWith("/.") || lowerRaw.endsWith("/..")) {
                return true
            }
            // 原始路径必须是字面量 "/api/" 开头（区分大小写、未编码）
            return !rawUri.startsWith("/api/")
        }

        /**
         * 宽松归一化：解码、去除 ";" 参数、合并多余斜杠、转小写，只用于判断是否指向 /api
         */
        private fun normalize(rawUri: String): String {
            var decoded = rawUri
            // 多次解码以识别双重编码
            repeat(3) {
                decoded = try {
                    UriUtils.decode(decoded, StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    decoded
                }
            }
            return decoded
                .replace('\\', '/')
                .replace(Regex(";[^/]*"), "")
                .replace(Regex("/+"), "/")
                .replace("/./", "/")
                .lowercase()
        }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val rawUri = request.requestURI ?: ""
        if (isSuspiciousApiPath(rawUri)) {
            log.warn("拒绝可疑的 API 路径: uri=$rawUri, remoteAddr=${request.remoteAddr}")
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            val apiResponse: ApiResponse<Unit> = ApiResponse.error(ErrorCode.AUTH_ERROR.code, ErrorCode.AUTH_ERROR.message)
            response.writer.write(objectMapper.writeValueAsString(apiResponse))
            response.writer.flush()
            return
        }
        filterChain.doFilter(request, response)
    }
}
