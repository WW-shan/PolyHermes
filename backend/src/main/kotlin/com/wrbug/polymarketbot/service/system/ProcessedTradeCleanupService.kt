package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.repository.ProcessedTradeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 已处理交易清理服务
 * 定期清理过期的去重记录
 */
@Service
class ProcessedTradeCleanupService(
    private val processedTradeRepository: ProcessedTradeRepository
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ProcessedTradeCleanupService::class.java)

        /**
         * 去重记录保留时间：24 小时。
         *
         * 这里必须长于“同一笔成交被再次投递”的最大可能窗口，否则重复下单：
         * - 跨来源去重（activity-ws 与 onchain-ws 双路推送同一笔成交）完全依赖本表；
         * - 链上 WebSocket 断线重连后会按 [com.wrbug.polymarketbot.service.copytrading.monitor.UnifiedOnChainWsService]
         *   的补数据逻辑回溯最多 1800 个区块（Polygon 约 1 小时）重新回调历史交易；
         * - 两个来源各自的内存去重缓存只覆盖自身路径（10/30 分钟）。
         * 因此保留窗口需要覆盖补数据窗口并留出余量（原先的 10 分钟会在这类场景下失效）。
         */
        internal const val RETENTION_MS = 24 * 60 * 60 * 1000L

        // 定时清理间隔：10分钟（600000毫秒）
        private const val CLEANUP_INTERVAL_MS = 600_000L
    }

    /**
     * 定时清理过期记录
     * 每10分钟执行一次
     */
    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS)
    @Transactional
    fun cleanupExpiredProcessedTrades() {
        try {
            val expireTime = System.currentTimeMillis() - RETENTION_MS
            val deletedCount = processedTradeRepository.deleteByProcessedAtBefore(expireTime)

            if (deletedCount > 0) {
                logger.info("清理过期已处理交易记录: deletedCount=$deletedCount, expireTime=$expireTime")
            }
        } catch (e: Exception) {
            logger.error("清理过期已处理交易记录失败", e)
        }
    }
}

