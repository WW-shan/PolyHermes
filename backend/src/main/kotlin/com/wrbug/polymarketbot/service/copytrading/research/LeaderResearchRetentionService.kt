package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.enums.LeaderPaperProcessingStatus
import com.wrbug.polymarketbot.enums.LeaderPaperSessionStatus
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import com.wrbug.polymarketbot.repository.LeaderPaperSessionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

data class LeaderResearchRetentionResult(
    val deletedActivityEvents: Long,
    val deletedPaperSessions: Long
)

@Service
class LeaderResearchRetentionService(
    private val activityEventRepository: LeaderActivityEventRepository,
    private val paperSessionRepository: LeaderPaperSessionRepository,
    @Value("\${leader.research.retention.enabled:true}") private val enabled: Boolean,
    @Value("\${leader.research.retention.activity-days:90}") private val activityRetentionDays: Long,
    @Value("\${leader.research.retention.paper-session-days:180}") private val paperSessionRetentionDays: Long,
    @Value("\${leader.research.retention.max-paper-sessions-per-run:100}") private val maxPaperSessionsPerRun: Int
) {
    @Scheduled(cron = "\${leader.research.retention.cron:0 17 3 * * *}")
    fun scheduledCleanup() {
        if (!enabled) return
        cleanup()
    }

    /**
     * 清理过期研究数据。
     * 不依赖 @Transactional（调度入口自调用时代理不生效）：活动事件用自带事务的原生批量删除，
     * 会话删除走仓储 deleteAll（自带事务）。
     */
    fun cleanup(now: Long = System.currentTimeMillis()): LeaderResearchRetentionResult {
        if (!enabled) return LeaderResearchRetentionResult(0, 0)
        val activityCutoff = now - activityRetentionDays.coerceAtLeast(7) * MILLIS_PER_DAY
        val paperCutoff = now - paperSessionRetentionDays.coerceAtLeast(30) * MILLIS_PER_DAY
        // 终态事件与长期未处理的 NEW/RETRYABLE 事件一并清理（超过保留期已不会再被纸跟使用）
        val statuses = listOf(
            LeaderPaperProcessingStatus.PROCESSED,
            LeaderPaperProcessingStatus.FILTERED,
            LeaderPaperProcessingStatus.FAILED,
            LeaderPaperProcessingStatus.NEW,
            LeaderPaperProcessingStatus.RETRYABLE
        ).map { it.name }
        var deletedActivities = 0L
        var rounds = 0
        while (rounds < MAX_ACTIVITY_DELETE_ROUNDS) {
            val deleted = activityEventRepository.deleteBatchByEventTimeLessThanAndStatusIn(
                activityCutoff, statuses, ACTIVITY_DELETE_BATCH_SIZE
            )
            deletedActivities += deleted
            rounds++
            if (deleted < ACTIVITY_DELETE_BATCH_SIZE) break
        }
        val staleSessions = paperSessionRepository.findByUpdatedAtLessThanAndStatusIn(
            paperCutoff,
            listOf(LeaderPaperSessionStatus.COMPLETED, LeaderPaperSessionStatus.FAILED),
            PageRequest.of(0, maxPaperSessionsPerRun.coerceIn(1, 1000))
        )
        paperSessionRepository.deleteAll(staleSessions.content)
        return LeaderResearchRetentionResult(
            deletedActivityEvents = deletedActivities,
            deletedPaperSessions = staleSessions.content.size.toLong()
        )
    }

    companion object {
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /** 活动事件每批删除条数 */
        const val ACTIVITY_DELETE_BATCH_SIZE = 1000

        /** 单次清理最多删除批数（剩余的留到下次） */
        private const val MAX_ACTIVITY_DELETE_ROUNDS = 100
    }
}
