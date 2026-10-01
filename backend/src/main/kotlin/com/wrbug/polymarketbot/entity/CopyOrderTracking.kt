package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 订单跟踪实体
 * 用于跟踪每笔买入订单的匹配状态
 */
@Entity
@Table(name = "copy_order_tracking")
data class CopyOrderTracking(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,
    
    @Column(name = "account_id", nullable = false)
    val accountId: Long,
    
    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,
    
    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,
    
    @Column(name = "side", nullable = false, length = 10)
    val side: String,  // 兼容字段：YES/NO 或 outcomeIndex（字符串）
    
    @Column(name = "outcome_index", nullable = true)
    val outcomeIndex: Int? = null,  // 结果索引（0, 1, 2, ...），支持多元市场
    
    @Column(name = "buy_order_id", nullable = false, length = 100)
    val buyOrderId: String,  // 跟单买入订单ID
    
    @Column(name = "leader_buy_trade_id", nullable = false, length = 100)
    val leaderBuyTradeId: String,  // Leader 买入交易ID
    
    @Column(name = "leader_buy_quantity", nullable = true, precision = 20, scale = 8)
    val leaderBuyQuantity: BigDecimal? = null,  // Leader 买入数量（用于固定金额模式计算卖出比例）
    
    @Column(name = "quantity", nullable = false, precision = 20, scale = 8)
    val quantity: BigDecimal,  // 买入数量
    
    @Column(name = "price", nullable = false, precision = 20, scale = 8)
    val price: BigDecimal,  // 买入价格
    
    @Column(name = "matched_quantity", nullable = false, precision = 20, scale = 8)
    var matchedQuantity: BigDecimal = BigDecimal.ZERO,  // 已匹配卖出数量
    
    @Column(name = "remaining_quantity", nullable = false, precision = 20, scale = 8)
    var remainingQuantity: BigDecimal,  // 剩余未匹配数量
    
    @Column(name = "status", nullable = false, length = 20)
    var status: String = "filled",  // pending（成交待确认）, filled, fully_matched, partially_matched, unconfirmed（无法确认）
    
    @Column(name = "notification_sent", nullable = false)
    var notificationSent: Boolean = false,  // 是否已发送通知（从订单详情获取实际数据后发送）

    @Column(name = "source", nullable = false, length = 20)
    val source: String,  // 订单来源：activity-ws（Polymarket WebSocket）、onchain-ws（OnChain WebSocket）

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis(),

    @Column(name = "requested_quantity", nullable = true, precision = 20, scale = 8)
    val requestedQuantity: BigDecimal? = null,  // 下单请求数量（待确认订单用于仓位额度预占）

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0  // 乐观锁版本号，防止读-改-写覆盖并发核销
) {
    companion object {
        /** 已下单但成交未确认：quantity/remainingQuantity 为 0，不计入可卖数量 */
        const val STATUS_PENDING = "pending"
        const val STATUS_FILLED = "filled"
        const val STATUS_PARTIALLY_MATCHED = "partially_matched"
        const val STATUS_FULLY_MATCHED = "fully_matched"
        /** 长时间无法查询到订单结果，停止轮询等待人工核对 */
        const val STATUS_UNCONFIRMED = "unconfirmed"
    }
}

