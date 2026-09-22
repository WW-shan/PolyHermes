package com.wrbug.polymarketbot.util

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Builder API 认证拦截器
 * 用于 Builder Relayer API 的认证
 * 
 * 参考: @polymarket/builder-signing-sdk 的 buildHmacSignature
 * 
 * 认证方式：
 * 1. 使用 HMAC-SHA256 对请求进行签名
 * 2. 在请求头中添加：
 *    - POLY_BUILDER_SIGNATURE: HMAC 签名（URL-safe base64）
 *    - POLY_BUILDER_TIMESTAMP: 时间戳（秒，字符串）
 *    - POLY_BUILDER_API_KEY: API Key
 *    - POLY_BUILDER_PASSPHRASE: Passphrase
 */
class BuilderAuthInterceptor(
    private val apiKey: String,
    private val secret: String,
    private val passphrase: String,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) : Interceptor {
    
    private val logger = LoggerFactory.getLogger(BuilderAuthInterceptor::class.java)
    
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // 获取请求体
        val requestBody = originalRequest.body
        val bodyString = if (requestBody != null) {
            val buffer = okio.Buffer()
            requestBody.writeTo(buffer)
            buffer.readUtf8()
        } else {
            ""
        }
        
        // Builder signing SDK 使用秒级 Unix 时间戳，毫秒时间戳会导致 401 invalid authorization
        val timestamp = (currentTimeMillis() / 1000L).toString()
        
        // 构建签名字符串: timestamp + method + path + body
        val method = originalRequest.method
        val path = originalRequest.url.encodedPath
        val signString = "$timestamp$method$path$bodyString"
        
        // 生成 HMAC 签名
        val signature = try {
            buildHmacSignature(signString, secret)
        } catch (e: Exception) {
            logger.error("生成 Builder HMAC 签名失败", e)
            throw IOException("生成签名失败: ${e.message}", e)
        }
        
        // 构建新的请求，添加 Builder 认证头
        val newRequest = originalRequest.newBuilder()
            .header("POLY_BUILDER_SIGNATURE", signature)
            .header("POLY_BUILDER_TIMESTAMP", timestamp)
            .header("POLY_BUILDER_API_KEY", apiKey)
            .header("POLY_BUILDER_PASSPHRASE", passphrase)
            .build()
        
        return chain.proceed(newRequest)
    }
    
    /**
     * 构建 HMAC 签名
     * 参考: @polymarket/builder-signing-sdk 的 buildHmacSignature
     */
    private fun buildHmacSignature(message: String, secret: String): String {
        // 解码 Builder Secret（base64）
        val decodedSecret = try {
            Base64.getDecoder().decode(secret)
        } catch (e: Exception) {
            try {
                Base64.getUrlDecoder().decode(secret)
            } catch (e2: Exception) {
                secret.toByteArray()
            }
        }
        
        // 使用 HMAC-SHA256 生成签名
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(decodedSecret, "HmacSHA256")
        mac.init(secretKeySpec)
        val hash = mac.doFinal(message.toByteArray())
        
        // Base64 编码
        val base64Signature = Base64.getEncoder().encodeToString(hash)
        
        // URL-safe base64 编码（+ -> -, / -> _）
        return base64Signature.replace("+", "-").replace("/", "_")
    }
}

