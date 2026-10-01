package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal
import com.wrbug.polymarketbot.util.toSafeBigDecimal

/**
 * 加密价差策略触发记录
 */
@Entity
@Table(
    name = "crypto_tail_strategy_trigger",
    uniqueConstraints = [UniqueConstraint(name = "uk_crypto_tail_trigger_strategy_period", columnNames = ["strategy_id", "period_start_unix"])]
)
data class CryptoTailStrategyTrigger(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long = 0L,

    @Column(name = "period_start_unix", nullable = false)
    val periodStartUnix: Long = 0L,

    @Column(name = "market_title", length = 500)
    val marketTitle: String? = null,

    @Column(name = "outcome_index", nullable = false)
    val outcomeIndex: Int = 0,

    @Column(name = "trigger_price", nullable = false, precision = 20, scale = 8)
    val triggerPrice: BigDecimal = BigDecimal.ZERO,

    @Column(name = "amount_usdc", nullable = false, precision = 20, scale = 8)
    val amountUsdc: BigDecimal = BigDecimal.ZERO,

    @Column(name = "order_id", length = 128)
    val orderId: String? = null,

    /** 下单响应的成交交易哈希（逗号分隔），结算时按哈希过滤成交 */
    @Column(name = "transaction_hashes", length = 2000)
    val transactionHashes: String? = null,

    @Column(name = "condition_id", length = 66)
    val conditionId: String? = null,

    @Column(name = "resolved", nullable = false)
    val resolved: Boolean = false,

    @Column(name = "winner_outcome_index")
    val winnerOutcomeIndex: Int? = null,

    @Column(name = "realized_pnl", precision = 20, scale = 8)
    val realizedPnl: BigDecimal? = null,

    @Column(name = "settled_at")
    val settledAt: Long? = null,

    /** 结算数据来源：TX_HASH（按交易哈希精确）、TIME_WINDOW（按时间窗聚合，可能不精确）、ESTIMATED（估算） */
    @Column(name = "settlement_source", length = 20)
    val settlementSource: String? = null,

    /** 状态：pending（已占位、下单中）、success、fail */
    @Column(name = "status", nullable = false, length = 20)
    val status: String = "success",

    @Column(name = "fail_reason", length = 500)
    val failReason: String? = null,

    @Column(name = "trigger_type", nullable = false, length = 20)
    val triggerType: String = "AUTO",

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "notification_sent", nullable = false)
    var notificationSent: Boolean = false
)
