package com.wrbug.polymarketbot.util

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密工具类
 * 用于加密/解密敏感数据（如私钥）
 * 使用 AES-256 加密算法
 */
@Component
class CryptoUtils {
    
    @Value("\${encryption.key:\${jwt.secret}}")
    private lateinit var encryptionKey: String

    /**
     * 旧候选密钥（逗号分隔），用于兼容历史部署：
     * 旧 Docker 部署实际使用 JWT_SECRET 加密（当时 CRYPTO_SECRET_KEY 未被读取），也可能使用过 CRYPTO_SECRET_KEY
     */
    @Value("\${encryption.legacy-keys:}")
    private var legacyKeysConfig: String = ""

    @Value("\${jwt.secret:}")
    private var jwtSecret: String = ""

    /**
     * 解密结果
     * @param plainText 明文
     * @param usedLegacyKey 是否由旧候选密钥解密成功（需要用当前密钥重新加密）
     */
    data class DecryptResult(
        val plainText: String = "",
        val usedLegacyKey: Boolean = false
    )

    /**
     * 设置密钥（用于单元测试或非 Spring 环境）
     */
    fun configureKeys(currentKey: String, legacyKeys: List<String> = emptyList()) {
        encryptionKey = currentKey
        legacyKeysConfig = legacyKeys.joinToString(",")
        jwtSecret = ""
    }

    /**
     * 旧候选密钥列表（去重、去空、排除当前密钥），按 CRYPTO_SECRET_KEY → JWT_SECRET 顺序尝试
     */
    fun legacyKeys(): List<String> {
        val candidates = legacyKeysConfig.split(",").map { it.trim() } + jwtSecret.trim()
        return candidates.filter { it.isNotEmpty() && it != encryptionKey }.distinct()
    }
    
    private val ALGORITHM = "AES"
    // 使用 AES/CBC/PKCS5Padding 模式，明确支持 AES-256
    // 注意：如果 JVM 不支持 256 位密钥，可能需要安装 JCE 无限强度策略文件
    private val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    
    /**
     * 获取加密密钥（从配置的密钥派生 32 字节密钥）
     * 
     * 支持任意长度的密钥：
     * - 如果密钥是十六进制字符串（64 字符或更长），会解析为字节数组
     * - 如果是普通字符串，会使用 UTF-8 编码转换为字节数组
     * - 无论输入多长，都会通过 SHA-256 哈希成固定的 32 字节（256 位）
     * 
     * 这样设计的好处：
     * 1. 支持任意长度的密钥（短密钥、长密钥都可以）
     * 2. 确保密钥长度固定为 32 字节，满足 AES-256 要求
     * 3. 即使密钥很短，通过哈希后也能提供足够的安全性
     */
    private fun getSecretKey(): SecretKeySpec = deriveSecretKey(encryptionKey)

    /**
     * 从任意密钥字符串派生 AES-256 密钥
     */
    private fun deriveSecretKey(key: String): SecretKeySpec {
        val keyBytes = if (key.length >= 64 && key.matches(Regex("^[0-9a-fA-F]+$"))) {
            // 十六进制字符串，解析为字节数组
            key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            // 普通字符串，使用 UTF-8 编码
            key.toByteArray(StandardCharsets.UTF_8)
        }
        
        // 使用 SHA-256 哈希确保密钥长度为 32 字节（256 位）
        // 无论输入密钥多长，都会哈希成固定的 32 字节
        val messageDigest = MessageDigest.getInstance("SHA-256")
        messageDigest.update(keyBytes)
        val hash = messageDigest.digest()
        
        return SecretKeySpec(hash, ALGORITHM)
    }
    
    /**
     * 加密数据
     * 使用 AES-256/CBC/PKCS5Padding 模式
     * 
     * @param plainText 明文
     * @return Base64 编码的密文（包含 IV）
     */
    fun encrypt(plainText: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = getSecretKey()
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            // 获取 IV（初始化向量）
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            
            // 将 IV 和加密数据组合：IV (16 字节) + 加密数据
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            
            Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            throw RuntimeException("加密失败: ${e.message}", e)
        }
    }
    
    /**
     * 解密数据
     * 使用 AES-256/CBC/PKCS5Padding 模式
     * 
     * @param encryptedText Base64 编码的密文（包含 IV）
     * @return 明文
     */
    fun decrypt(encryptedText: String): String {
        return decryptWithKeyInfo(encryptedText).plainText
    }

    /**
     * 解密数据，并返回是否使用了旧候选密钥
     * 依次尝试：当前密钥 → CRYPTO_SECRET_KEY → JWT_SECRET（去重）
     * 为降低错误密钥恰好通过填充校验而返回乱码的概率，解密结果必须是合法 UTF-8
     *
     * @param encryptedText Base64 编码的密文（包含 IV）
     * @return 解密结果
     */
    fun decryptWithKeyInfo(encryptedText: String): DecryptResult {
        val currentError = try {
            return DecryptResult(decryptWithKey(encryptedText, encryptionKey), usedLegacyKey = false)
        } catch (e: Exception) {
            e
        }
        for (legacyKey in legacyKeys()) {
            try {
                val plainText = decryptWithKey(encryptedText, legacyKey)
                return DecryptResult(plainText, usedLegacyKey = true)
            } catch (e: Exception) {
                // 继续尝试其他候选密钥
            }
        }
        throw RuntimeException("解密失败: ${currentError.message}", currentError)
    }

    /**
     * 如果密文是由旧候选密钥加密的，返回用当前密钥重新加密后的密文；否则返回 null
     * 解密失败时抛出异常，调用方不应修改原数据
     */
    fun reEncryptIfLegacy(encryptedText: String): String? {
        val result = decryptWithKeyInfo(encryptedText)
        return if (result.usedLegacyKey) encrypt(result.plainText) else null
    }

    /**
     * 使用指定密钥解密
     */
    private fun decryptWithKey(encryptedText: String, key: String): String {
        val combined = Base64.getDecoder().decode(encryptedText)
        require(combined.size > 16) { "密文长度不足" }

        // 提取 IV（前 16 字节）和加密数据
        val iv = ByteArray(16)
        System.arraycopy(combined, 0, iv, 0, 16)
        val encryptedBytes = ByteArray(combined.size - 16)
        System.arraycopy(combined, 16, encryptedBytes, 0, encryptedBytes.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, deriveSecretKey(key), IvParameterSpec(iv))
        val decryptedBytes = cipher.doFinal(encryptedBytes)

        // 严格 UTF-8 解码，非法字节直接视为解密失败
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(decryptedBytes))
            .toString()
    }
    
    /**
     * 检查字符串是否为加密后的数据（Base64 格式）
     * 注意：这不是完全可靠的检测方法，仅用于向后兼容
     */
    fun isEncrypted(text: String): Boolean {
        return try {
            // 尝试 Base64 解码，如果成功且长度合理，可能是加密数据
            val decoded = Base64.getDecoder().decode(text)
            // 加密后的数据长度应该是 16 字节的倍数（AES 块大小）
            decoded.size % 16 == 0 && decoded.size >= 16
        } catch (e: Exception) {
            false
        }
    }
}

