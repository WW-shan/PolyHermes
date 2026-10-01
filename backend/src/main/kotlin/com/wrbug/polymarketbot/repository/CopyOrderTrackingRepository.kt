package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopyOrderTracking
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.math.BigDecimal

/**
 * 订单跟踪Repository
 */
@Repository
interface CopyOrderTrackingRepository : JpaRepository<CopyOrderTracking, Long> {
    
    /**
     * 根据跟单关系ID查询所有买入订单
     */
    fun findByCopyTradingId(copyTradingId: Long): List<CopyOrderTracking>
    
    /**
     * 根据跟单关系ID、市场ID和方向查询未匹配的买入订单（FIFO顺序）
     * @deprecated 使用 findUnmatchedBuyOrdersByOutcomeIndex 替代
     */
    @Query("SELECT t FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.marketId = :marketId AND t.side = :side AND t.remainingQuantity > 0 ORDER BY t.createdAt ASC")
    fun findUnmatchedBuyOrders(copyTradingId: Long, marketId: String, side: String): List<CopyOrderTracking>
    
    /**
     * 根据跟单关系ID、市场ID和outcomeIndex查询未匹配的买入订单（FIFO顺序）
     * 支持多元市场（不限于YES/NO）
     */
    @Query("SELECT t FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.marketId = :marketId AND t.outcomeIndex = :outcomeIndex AND t.remainingQuantity > 0 ORDER BY t.createdAt ASC")
    fun findUnmatchedBuyOrdersByOutcomeIndex(copyTradingId: Long, marketId: String, outcomeIndex: Int): List<CopyOrderTracking>
    
    /**
     * 根据跟单关系ID和状态查询订单
     */
    fun findByCopyTradingIdAndStatus(copyTradingId: Long, status: String): List<CopyOrderTracking>
    
    /**
     * 根据跟单关系ID和市场ID查询订单
     */
    fun findByCopyTradingIdAndMarketId(copyTradingId: Long, marketId: String): List<CopyOrderTracking>
    
    /**
     * 根据Leader交易ID查询订单
     */
    fun findByLeaderBuyTradeId(leaderBuyTradeId: String): CopyOrderTracking?
    
    /**
     * 根据买入订单ID查询订单跟踪记录
     */
    fun findByBuyOrderId(buyOrderId: String): List<CopyOrderTracking>
    
    /**
     * 查询未发送通知的买入订单（用于轮询更新）
     */
    fun findByNotificationSentFalse(): List<CopyOrderTracking>
    
    /**
     * 查询指定时间之前创建的订单（用于检查30秒后未成交的订单）
     */
    @Query("SELECT t FROM CopyOrderTracking t WHERE t.createdAt <= :beforeTime")
    fun findByCreatedAtBefore(beforeTime: Long): List<CopyOrderTracking>

    /**
     * 查询指定时间之前创建且状态不为指定状态的订单
     */
    fun findByCreatedAtBeforeAndStatusNot(beforeTime: Long, status: String): List<CopyOrderTracking>

    /**
     * 查询指定跟单配置下的活跃仓位数量
     * 活跃仓位定义为 remainingQuantity > 0 的不同 (marketId, outcomeIndex) 组合
     */
    @Query("SELECT COUNT(DISTINCT CONCAT(t.marketId, '_', COALESCE(t.outcomeIndex, -1))) FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.remainingQuantity > 0")
    fun countActivePositions(copyTradingId: Long): Int

    /**
     * 计算指定跟单配置、市场和方向下的当前持仓总价值 (成本价计算)
     * 按市场+方向（outcomeIndex）分别统计
     */
    @Query("SELECT SUM(t.remainingQuantity * t.price) FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.marketId = :marketId AND t.outcomeIndex = :outcomeIndex AND t.remainingQuantity > 0")
    fun sumCurrentPositionValueByMarketAndOutcomeIndex(copyTradingId: Long, marketId: String, outcomeIndex: Int): BigDecimal?

    /**
     * 查询指定跟单配置下，创建时间超过指定时间点的未匹配订单（FIFO顺序）
     * 用于避免刚创建的订单被误判为已卖出
     *
     * @param copyTradingId 跟单配置ID
     * @param marketId 市场ID
     * @param outcomeIndex 结果索引
     * @param thresholdTime 时间阈值（毫秒时间戳），只查询创建时间小于该值的订单
     * @return 未匹配订单列表（按创建时间升序排列）
     */
    @Query("SELECT t FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.marketId = :marketId AND t.outcomeIndex = :outcomeIndex AND t.remainingQuantity > 0 AND t.createdAt < :thresholdTime ORDER BY t.createdAt ASC")
    fun findUnmatchedBuyOrdersByOutcomeIndexOlderThan(
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int,
        thresholdTime: Long
    ): List<CopyOrderTracking>

    /**
     * 幂等判断：该跟单配置是否已为该 Leader 买入交易创建过买入记录（重试处理同一笔交易时避免重复下单）
     */
    fun existsByCopyTradingIdAndLeaderBuyTradeId(copyTradingId: Long, leaderBuyTradeId: String): Boolean

    /**
     * 查询指定配置在某市场+方向的全部买入记录（用于计算累计跟单比例）
     */
    fun findByCopyTradingIdAndMarketIdAndOutcomeIndex(
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int
    ): List<CopyOrderTracking>

    /**
     * 统计指定配置在某市场+方向的某状态记录数（卖出前检查是否有待确认买入）
     */
    fun countByCopyTradingIdAndMarketIdAndOutcomeIndexAndStatus(
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int,
        status: String
    ): Long

    /**
     * 待确认买入的额度预占：Σ(requestedQuantity × 下单限价)
     */
    @Query("SELECT SUM(COALESCE(t.requestedQuantity, 0) * t.price) FROM CopyOrderTracking t WHERE t.copyTradingId = :copyTradingId AND t.marketId = :marketId AND t.outcomeIndex = :outcomeIndex AND t.status = 'pending'")
    fun sumPendingReservedValueByMarketAndOutcomeIndex(copyTradingId: Long, marketId: String, outcomeIndex: Int): BigDecimal?

    /**
     * 统计配置在某时间之后创建的买入记录数（每日订单数风控）
     */
    fun countByCopyTradingIdAndCreatedAtGreaterThanEqual(copyTradingId: Long, createdAt: Long): Long

    /**
     * 有界查询指定状态、创建时间早于阈值的记录（待确认订单轮询，避免全表扫描）
     */
    fun findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(status: String, createdAt: Long): List<CopyOrderTracking>

    /**
     * 有界查询未发送通知且状态不是指定值的记录（排除待确认订单）
     */
    fun findTop200ByNotificationSentFalseAndStatusNotInOrderByIdAsc(statuses: Collection<String>): List<CopyOrderTracking>

    /**
     * 查询所有出现过的市场ID（去重，避免为补齐市场信息加载整张表）
     */
    @Query("SELECT DISTINCT t.marketId FROM CopyOrderTracking t")
    fun findDistinctMarketIds(): List<String>
}

