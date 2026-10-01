package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.BacktestTrade
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * 回测交易记录Repository
 */
@Repository
interface BacktestTradeRepository : JpaRepository<BacktestTrade, Long> {

    /**
     * 根据回测任务ID查询所有交易记录
     */
    fun findByBacktestTaskIdOrderByTradeTime(backtestTaskId: Long): List<BacktestTrade>

    /**
     * 根据回测任务ID分页查询交易记录
     */
    @Query("SELECT t FROM BacktestTrade t WHERE t.backtestTaskId = :backtestTaskId ORDER BY t.tradeTime")
    fun findByBacktestTaskId(
        backtestTaskId: Long,
        pageable: org.springframework.data.domain.Pageable
    ): org.springframework.data.domain.Page<BacktestTrade>

    /**
     * 根据回测任务ID统计交易数量
     */
    fun countByBacktestTaskId(backtestTaskId: Long): Long

    /**
     * 删除回测任务的所有交易记录（由级联删除处理）
     */
    fun deleteByBacktestTaskId(backtestTaskId: Long)

    /**
     * 批量删除回测任务的所有交易记录（重跑/续跑前清空，单条 SQL，自带事务）
     * @return 删除行数
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM BacktestTrade t WHERE t.backtestTaskId = :backtestTaskId")
    fun deleteAllByBacktestTaskId(backtestTaskId: Long): Int
}

