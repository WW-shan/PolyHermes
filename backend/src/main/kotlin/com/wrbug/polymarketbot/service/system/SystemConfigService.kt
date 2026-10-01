package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.SystemConfigDto
import com.wrbug.polymarketbot.dto.SystemConfigUpdateRequest
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 系统配置服务
 */
@Service
class SystemConfigService(
    private val systemConfigRepository: SystemConfigRepository,
    private val cryptoUtils: CryptoUtils
) {

    private val logger = LoggerFactory.getLogger(SystemConfigService::class.java)

    companion object {
        /** 掩码标记：前端回传包含该标记的值视为"未修改"，保持原值 */
        const val MASK = "****"

        /**
         * 掩码敏感值
         * @param visibleChars 前后各保留的明文字符数（值太短时全部掩码）
         */
        fun maskSecret(value: String, visibleChars: Int = 4): String {
            if (visibleChars <= 0 || value.length <= visibleChars * 2 + 4) {
                return MASK + MASK
            }
            return value.take(visibleChars) + MASK + value.takeLast(visibleChars)
        }

        /**
         * 是否为掩码值（前端把掩码显示值原样回传）
         */
        fun isMaskedValue(value: String?): Boolean = value != null && value.contains(MASK)

        const val CONFIG_KEY_BUILDER_API_KEY = "builder.api_key"
        const val CONFIG_KEY_BUILDER_SECRET = "builder.secret"
        const val CONFIG_KEY_BUILDER_PASSPHRASE = "builder.passphrase"
        const val CONFIG_KEY_AUTO_REDEEM = "auto_redeem"
    }

    /**
     * 获取系统配置
     */
    fun getSystemConfig(): SystemConfigDto {
        val builderApiKey = getConfigValue(CONFIG_KEY_BUILDER_API_KEY)
        val builderSecret = getConfigValue(CONFIG_KEY_BUILDER_SECRET)
        val builderPassphrase = getConfigValue(CONFIG_KEY_BUILDER_PASSPHRASE)
        val autoRedeem = isAutoRedeemEnabled()

        // 显示值只返回掩码，不再返回解密后的明文（API Key 保留前后若干位便于辨认，Secret/Passphrase 完全掩码）
        val builderApiKeyDisplay = builderApiKey?.let { decryptOrNull(it) }?.let { maskSecret(it, visibleChars = 4) }
        val builderSecretDisplay = builderSecret?.let { decryptOrNull(it) }?.let { maskSecret(it, visibleChars = 0) }
        val builderPassphraseDisplay = builderPassphrase?.let { decryptOrNull(it) }?.let { maskSecret(it, visibleChars = 0) }

        return SystemConfigDto(
            builderApiKeyConfigured = builderApiKey != null,
            builderSecretConfigured = builderSecret != null,
            builderPassphraseConfigured = builderPassphrase != null,
            builderApiKeyDisplay = builderApiKeyDisplay,
            builderSecretDisplay = builderSecretDisplay,
            builderPassphraseDisplay = builderPassphraseDisplay,
            autoRedeemEnabled = autoRedeem
        )
    }

    /**
     * 更新 Builder API Key 配置
     */
    @Transactional
    fun updateBuilderApiKey(request: SystemConfigUpdateRequest): Result<SystemConfigDto> {
        return try {
            // 更新 Builder API Key
            // 回传的掩码值表示未修改，保持原值
            if (request.builderApiKey != null && !isMaskedValue(request.builderApiKey)) {
                updateConfigValue(
                    CONFIG_KEY_BUILDER_API_KEY,
                    if (request.builderApiKey.isNotBlank()) {
                        cryptoUtils.encrypt(request.builderApiKey)
                    } else {
                        null  // 清空配置
                    }
                )
            }

            // 更新 Builder Secret
            // 回传的掩码值表示未修改，保持原值
            if (request.builderSecret != null && !isMaskedValue(request.builderSecret)) {
                updateConfigValue(
                    CONFIG_KEY_BUILDER_SECRET,
                    if (request.builderSecret.isNotBlank()) {
                        cryptoUtils.encrypt(request.builderSecret)
                    } else {
                        null  // 清空配置
                    }
                )
            }

            // 更新 Builder Passphrase
            // 回传的掩码值表示未修改，保持原值
            if (request.builderPassphrase != null && !isMaskedValue(request.builderPassphrase)) {
                updateConfigValue(
                    CONFIG_KEY_BUILDER_PASSPHRASE,
                    if (request.builderPassphrase.isNotBlank()) {
                        cryptoUtils.encrypt(request.builderPassphrase)
                    } else {
                        null  // 清空配置
                    }
                )
            }

            // 更新自动赎回配置
            if (request.autoRedeem != null) {
                updateConfigValue(
                    CONFIG_KEY_AUTO_REDEEM,
                    request.autoRedeem.toString()
                )
            }

            Result.success(getSystemConfig())
        } catch (e: Exception) {
            logger.error("更新系统配置失败", e)
            Result.failure(e)
        }
    }

    /**
     * 获取配置值（解密）
     */
    fun getBuilderApiKey(): String? {
        return getConfigValue(CONFIG_KEY_BUILDER_API_KEY)?.let { cryptoUtils.decrypt(it) }
    }

    fun getBuilderSecret(): String? {
        return getConfigValue(CONFIG_KEY_BUILDER_SECRET)?.let { cryptoUtils.decrypt(it) }
    }

    fun getBuilderPassphrase(): String? {
        return getConfigValue(CONFIG_KEY_BUILDER_PASSPHRASE)?.let { cryptoUtils.decrypt(it) }
    }

    /**
     * 检查 Builder API Key 是否已配置
     */
    fun isBuilderApiKeyConfigured(): Boolean {
        val apiKey = getConfigValue(CONFIG_KEY_BUILDER_API_KEY)
        val secret = getConfigValue(CONFIG_KEY_BUILDER_SECRET)
        val passphrase = getConfigValue(CONFIG_KEY_BUILDER_PASSPHRASE)
        return apiKey != null && secret != null && passphrase != null
    }

    /**
     * 检查自动赎回是否启用
     */
    fun isAutoRedeemEnabled(): Boolean {
        val autoRedeemValue = getConfigValue(CONFIG_KEY_AUTO_REDEEM)
        return when (autoRedeemValue?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> false  // 默认开启
        }
    }

    /**
     * 更新自动赎回配置
     */
    @Transactional
    fun updateAutoRedeem(enabled: Boolean): Result<SystemConfigDto> {
        return try {
            updateConfigValue(CONFIG_KEY_AUTO_REDEEM, enabled.toString())
            Result.success(getSystemConfig())
        } catch (e: Exception) {
            logger.error("更新自动赎回配置失败", e)
            Result.failure(e)
        }
    }

    private fun decryptOrNull(encrypted: String): String? {
        return try {
            cryptoUtils.decrypt(encrypted)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取配置值（原始值，加密存储）
     */
    private fun getConfigValue(configKey: String): String? {
        return systemConfigRepository.findByConfigKey(configKey)?.configValue
    }

    /**
     * 更新配置值
     */
    private fun updateConfigValue(configKey: String, configValue: String?) {
        val existing = systemConfigRepository.findByConfigKey(configKey)
        if (existing != null) {
            val updated = existing.copy(
                configValue = configValue,
                updatedAt = System.currentTimeMillis()
            )
            systemConfigRepository.save(updated)
        } else {
            val newConfig = SystemConfig(
                configKey = configKey,
                configValue = configValue,
                description = when (configKey) {
                    CONFIG_KEY_BUILDER_API_KEY -> "Builder API Key（用于 Gasless 交易）"
                    CONFIG_KEY_BUILDER_SECRET -> "Builder Secret（用于 Gasless 交易）"
                    CONFIG_KEY_BUILDER_PASSPHRASE -> "Builder Passphrase（用于 Gasless 交易）"
                    CONFIG_KEY_AUTO_REDEEM -> "自动赎回（系统级别配置，默认开启）"
                    else -> null
                }
            )
            systemConfigRepository.save(newConfig)
        }
    }
}

