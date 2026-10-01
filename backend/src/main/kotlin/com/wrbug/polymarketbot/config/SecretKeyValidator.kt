package com.wrbug.polymarketbot.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * 启动时密钥安全校验
 * 拒绝缺失、使用公开默认值或长度小于 32 字节的 ENCRYPTION_KEY、JWT_SECRET、ADMIN_RESET_PASSWORD_KEY
 * - prod profile：校验失败拒绝启动
 * - 其他 profile（如 dev）：只打印 ERROR 日志
 * - 可通过 security.secret-validation.enforce=true/false 显式覆盖
 */
@Component
class SecretKeyValidator(
    private val environment: Environment
) {

    private val logger = LoggerFactory.getLogger(SecretKeyValidator::class.java)

    companion object {
        /** 最小密钥长度（字节） */
        const val MIN_SECRET_BYTES = 32

        /** 公开的默认值/示例值黑名单（不区分大小写） */
        val BLACKLIST = setOf(
            "change-me-in-production",
            "your-secret-key-change-in-production",
            "your-jwt-secret-key-change-in-production",
            "your-encryption-key-change-in-production",
            "your-admin-reset-password-key-change-in-production",
            "your-secret-key",
            "changeme",
            "change-me",
            "secret",
            "password"
        )

        /**
         * 校验单个密钥
         * @return 错误描述，通过时返回 null
         */
        fun validateSecret(name: String, value: String?): String? {
            val trimmed = value?.trim()
            if (trimmed.isNullOrEmpty()) {
                return "$name 未配置"
            }
            val lower = trimmed.lowercase()
            if (lower in BLACKLIST || lower.contains("change-me") || lower.contains("change-in-production")) {
                return "$name 使用了公开的默认值，请更换为随机生成的密钥"
            }
            if (trimmed.toByteArray(StandardCharsets.UTF_8).size < MIN_SECRET_BYTES) {
                return "$name 长度不足 $MIN_SECRET_BYTES 字节，请使用 openssl rand -hex 32 生成"
            }
            return null
        }
    }

    /**
     * 收集所有密钥的校验错误
     */
    fun collectErrors(): List<String> {
        return listOfNotNull(
            validateSecret("ENCRYPTION_KEY(encryption.key)", environment.getProperty("encryption.key")),
            validateSecret("JWT_SECRET(jwt.secret)", environment.getProperty("jwt.secret")),
            validateSecret("ADMIN_RESET_PASSWORD_KEY(admin.reset-password.key)", environment.getProperty("admin.reset-password.key"))
        )
    }

    /**
     * 是否强制校验（校验失败拒绝启动）
     */
    fun isEnforced(): Boolean {
        val explicit = environment.getProperty("security.secret-validation.enforce")?.trim()
        if (!explicit.isNullOrEmpty()) {
            return explicit.equals("true", ignoreCase = true)
        }
        return environment.activeProfiles.any { it.equals("prod", ignoreCase = true) }
    }

    @PostConstruct
    fun validate() {
        val errors = collectErrors()
        if (errors.isEmpty()) {
            return
        }
        errors.forEach { logger.error("密钥安全校验失败: $it") }
        if (isEnforced()) {
            throw IllegalStateException("密钥安全校验失败，拒绝启动: ${errors.joinToString("; ")}")
        }
        logger.error("当前非 prod 环境，密钥校验失败仅告警；生产环境请务必配置安全的随机密钥")
    }
}
