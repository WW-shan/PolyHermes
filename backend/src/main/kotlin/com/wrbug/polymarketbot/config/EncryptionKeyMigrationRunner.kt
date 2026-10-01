package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.util.CryptoUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 加密密钥迁移：启动时把旧候选密钥（CRYPTO_SECRET_KEY / JWT_SECRET）加密的敏感字段用当前 ENCRYPTION_KEY 重新加密
 *
 * 覆盖范围：wallet_accounts 的 private_key / api_secret / api_passphrase，system_config 中的 Builder 凭证，
 * rpc_node_config 的 api_key
 *
 * 安全保证：
 * - 每个字段单独处理，解密失败（所有候选密钥都不匹配）时跳过并告警，绝不修改原数据
 * - 重新加密后先用当前密钥回读校验，UPDATE 带原值条件（WHERE col = 原密文），避免覆盖并发写入
 */
@Component
class EncryptionKeyMigrationRunner(
    private val jdbcTemplate: JdbcTemplate,
    private val cryptoUtils: CryptoUtils,
    @Value("\${encryption.migrate-legacy-on-startup:true}") private val enabled: Boolean = true
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(EncryptionKeyMigrationRunner::class.java)

    /**
     * 需要迁移的字段：表名、主键列、密文列、可选的额外条件
     */
    private data class EncryptedColumn(
        val table: String = "",
        val idColumn: String = "id",
        val column: String = "",
        val extraCondition: String = ""
    )

    private val columns = listOf(
        EncryptedColumn(table = "wallet_accounts", column = "private_key"),
        EncryptedColumn(table = "wallet_accounts", column = "api_secret"),
        EncryptedColumn(table = "wallet_accounts", column = "api_passphrase"),
        EncryptedColumn(
            table = "system_config",
            column = "config_value",
            extraCondition = "config_key IN ('builder.api_key', 'builder.secret', 'builder.passphrase')"
        ),
        EncryptedColumn(table = "rpc_node_config", column = "api_key")
    )

    override fun run(args: ApplicationArguments?) {
        if (!enabled || cryptoUtils.legacyKeys().isEmpty()) {
            return
        }
        columns.forEach { migrateColumn(it) }
    }

    private fun migrateColumn(target: EncryptedColumn) {
        val where = buildString {
            append("${target.column} IS NOT NULL AND ${target.column} <> ''")
            if (target.extraCondition.isNotBlank()) {
                append(" AND ").append(target.extraCondition)
            }
        }
        val rows = try {
            jdbcTemplate.queryForList(
                "SELECT ${target.idColumn} AS id, ${target.column} AS val FROM ${target.table} WHERE $where"
            )
        } catch (e: Exception) {
            logger.error("加密迁移读取失败，跳过: table=${target.table}, column=${target.column}, error=${e.message}")
            return
        }
        var migrated = 0
        var failed = 0
        for (row in rows) {
            val id = row["id"] ?: continue
            val oldValue = row["val"] as? String ?: continue
            try {
                val newValue = cryptoUtils.reEncryptIfLegacy(oldValue) ?: continue
                // 回读校验：新密文必须能用当前密钥解出与旧数据一致的明文
                val check = cryptoUtils.decryptWithKeyInfo(newValue)
                if (check.usedLegacyKey || check.plainText != cryptoUtils.decrypt(oldValue)) {
                    failed++
                    continue
                }
                val updated = jdbcTemplate.update(
                    "UPDATE ${target.table} SET ${target.column} = ? WHERE ${target.idColumn} = ? AND ${target.column} = ?",
                    newValue, id, oldValue
                )
                if (updated == 1) migrated++
            } catch (e: Exception) {
                // 所有候选密钥都无法解密：保持原数据不变
                failed++
                logger.error("加密迁移跳过无法解密的数据: table=${target.table}, column=${target.column}, id=$id")
            }
        }
        if (migrated > 0 || failed > 0) {
            logger.warn("加密迁移完成: table=${target.table}, column=${target.column}, migrated=$migrated, failed=$failed")
        }
    }
}
