package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.SellMatchRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 卖出匹配记录Repository
 */
@Repository
interface SellMatchRecordRepository : JpaRepository<SellMatchRecord, Long> {
    
    /**
     * 根据跟单关系ID查询所有卖出记录
     */
    fun findByCopyTradingId(copyTradingId: Long): List<SellMatchRecord>
    
    /**
     * 根据卖出订单ID查询记录
     */
    fun findBySellOrderId(sellOrderId: String): SellMatchRecord?
    
    /**
     * 根据Leader卖出交易ID查询记录
     */
    fun findByLeaderSellTradeId(leaderSellTradeId: String): SellMatchRecord?
    
    /**
     * 幂等判断：同一跟单关系 + 同一笔链上交易 + 同一市场 + 同一 outcome 是否已存在卖出记录。
     * 用于账户链上卖出/赎回回调的去重，避免同一笔交易被重复计入盈亏；
     * outcomeIndex 必须参与判断，否则同一交易的多个 outcome 会被误判为重复。
     */
    fun existsByCopyTradingIdAndSourceTxHashAndMarketIdAndOutcomeIndex(
        copyTradingId: Long,
        sourceTxHash: String,
        marketId: String,
        outcomeIndex: Int
    ): Boolean

    /**
     * 查询所有价格未更新的卖出记录
     * 注意：priceUpdated 现在同时表示价格已更新和通知已发送（共用字段）
     */
    fun findByPriceUpdatedFalse(): List<SellMatchRecord>

    /**
     * 幂等判断：该跟单配置是否已为该 Leader 卖出交易创建过卖出记录（重试处理同一笔交易时避免重复卖出）
     */
    fun existsByCopyTradingIdAndLeaderSellTradeId(copyTradingId: Long, leaderSellTradeId: String): Boolean

    /**
     * 有界查询待处理（未更新价格/未通知）且查询失败次数未达上限的卖出记录
     */
    fun findTop200ByPriceUpdatedFalseAndPriceQueryAttemptsLessThanOrderByIdAsc(maxAttempts: Int): List<SellMatchRecord>

    /**
     * 有界查询指定成交状态、创建时间早于阈值的卖出记录（待确认卖单轮询）
     */
    fun findTop200ByFillStatusAndCreatedAtBeforeOrderByIdAsc(fillStatus: String, createdAt: Long): List<SellMatchRecord>

    /**
     * 查询关联跟单配置已不存在的卖出记录（清理用，避免逐条 findById）
     */
    @org.springframework.data.jpa.repository.Query("SELECT r FROM SellMatchRecord r WHERE NOT EXISTS (SELECT c.id FROM CopyTrading c WHERE c.id = r.copyTradingId)")
    fun findOrphanRecords(): List<SellMatchRecord>

    /**
     * 批量按卖出订单ID查询（链上 OrderFilled 的 orderHash 即 CLOB orderID），
     * 用于识别账户链上成交是否为本系统下的跟单卖单
     */
    fun findBySellOrderIdIn(sellOrderIds: Collection<String>): List<SellMatchRecord>
}
