package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.BacktestTask
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 回测任务Repository
 */
@Repository
interface BacktestTaskRepository : JpaRepository<BacktestTask, Long> {

    /**
     * 根据 Leader ID 查询回测任务
     */
    fun findByLeaderId(leaderId: Long): List<BacktestTask>

    /**
     * 根据状态查询回测任务
     */
    fun findByStatus(status: String): List<BacktestTask>

    /**
     * 根据 Leader ID 和状态查询回测任务
     */
    fun findByLeaderIdAndStatus(leaderId: Long, status: String): List<BacktestTask>

    /**
     * 根据 Leader ID、收益率排序查询
     */
    @Query("SELECT t FROM BacktestTask t WHERE t.leaderId = :leaderId AND t.status = :status ORDER BY t.profitRate DESC")
    fun findByLeaderIdAndStatusOrderByProfitRateDesc(leaderId: Long, status: String): List<BacktestTask>

    /**
     * 根据状态和创建时间倒序查询
     */
    @Query("SELECT t FROM BacktestTask t WHERE t.status = :status ORDER BY t.createdAt DESC")
    fun findByStatusOrderByCreatedAtDesc(status: String): List<BacktestTask>

    /**
     * 更新回测任务状态
     */
    @Transactional
    @Modifying
    @Query("UPDATE BacktestTask t SET t.status = :status, t.updatedAt = :updatedAt WHERE t.id = :id")
    fun updateStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis())

    /**
     * 更新回测任务状态和错误信息
     */
    @Transactional
    @Modifying
    @Query("UPDATE BacktestTask t SET t.status = :status, t.errorMessage = :errorMessage, t.updatedAt = :updatedAt WHERE t.id = :id")
    fun updateStatusAndError(id: Long, status: String, errorMessage: String?, updatedAt: Long = System.currentTimeMillis())

    /**
     * 更新回测任务进度
     */
    @Transactional
    @Modifying
    @Query("UPDATE BacktestTask t SET t.progress = :progress, t.updatedAt = :updatedAt WHERE t.id = :id")
    fun updateProgress(id: Long, progress: Int, updatedAt: Long = System.currentTimeMillis())

    /**
     * 仅当任务仍为 RUNNING 时更新进度（避免覆盖用户的 STOPPED）
     * @return 更新行数，0 表示任务已不在运行
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE BacktestTask t SET t.progress = :progress, t.processedTradeCount = :processedTradeCount, " +
            "t.updatedAt = :updatedAt WHERE t.id = :id AND t.status = 'RUNNING'"
    )
    fun updateProgressIfRunning(
        id: Long,
        progress: Int,
        processedTradeCount: Int,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * 仅当任务仍为 RUNNING 时更新断点信息
     * @return 更新行数，0 表示任务已不在运行
     */
    @Transactional
    @Modifying
    @Query(
        "UPDATE BacktestTask t SET t.lastProcessedTradeTime = :lastProcessedTradeTime, " +
            "t.lastProcessedTradeIndex = :lastProcessedTradeIndex, t.processedTradeCount = :processedTradeCount, " +
            "t.finalBalance = :finalBalance, t.updatedAt = :updatedAt WHERE t.id = :id AND t.status = 'RUNNING'"
    )
    fun updateCheckpointIfRunning(
        id: Long,
        lastProcessedTradeTime: Long,
        lastProcessedTradeIndex: Int,
        processedTradeCount: Int,
        finalBalance: BigDecimal,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * 按当前状态条件更新状态（如 RUNNING -> STOPPED），避免覆盖其他字段
     * @return 更新行数
     */
    @Transactional
    @Modifying
    @Query("UPDATE BacktestTask t SET t.status = :newStatus, t.updatedAt = :updatedAt WHERE t.id = :id AND t.status = :expectedStatus")
    fun updateStatusIfCurrent(
        id: Long,
        expectedStatus: String,
        newStatus: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int
}

