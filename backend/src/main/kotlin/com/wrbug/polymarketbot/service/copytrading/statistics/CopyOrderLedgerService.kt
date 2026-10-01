package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.entity.SellMatchRecord
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.util.PolymarketTradingFee
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 跟单订单记账服务
 * 所有纯数据库写入集中在这里的非 suspend @Transactional 方法中（suspend 函数上的 @Transactional 不生效），
 * 由其他 bean 通过 Spring 代理调用，保证多表写入的原子性；
 * CopyOrderTracking 带 @Version 乐观锁，核销前重新读取并校验剩余数量，避免读-改-写覆盖。
 */
@Service
class CopyOrderLedgerService(
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository
) {

    private val logger = LoggerFactory.getLogger(CopyOrderLedgerService::class.java)

    /**
     * 下单前落 PENDING 买入记录（buyOrderId 为本地计算的订单 hash）
     * quantity/remainingQuantity 为 0，不计入可卖数量；requestedQuantity 用于额度预占
     */
    @Transactional
    fun createPendingBuy(tracking: CopyOrderTracking): CopyOrderTracking {
        return copyOrderTrackingRepository.save(tracking)
    }

    /**
     * 按实际成交确认买入记录
     * @param filledQuantity 实际成交 shares（BUY 的 takingAmount 或订单 size_matched）
     * @param avgPrice 成交均价（金额/数量）；null 时保留原价格（下单限价）
     * @return 更新后的记录；0 成交时删除记录并返回 null（0 成交的 FAK 不生成持仓）
     */
    @Transactional
    fun confirmBuyFill(trackingId: Long, filledQuantity: BigDecimal, avgPrice: BigDecimal?): CopyOrderTracking? {
        val current = copyOrderTrackingRepository.findById(trackingId).orElse(null) ?: return null
        if (filledQuantity.signum() <= 0) {
            if (current.matchedQuantity.signum() > 0) {
                logger.error("买入订单确认 0 成交但已有卖出核销，保留记录待人工核对: trackingId=$trackingId, matched=${current.matchedQuantity}")
                return current
            }
            copyOrderTrackingRepository.delete(current)
            logger.info("买入订单 0 成交，删除跟踪记录: trackingId=$trackingId, orderId=${current.buyOrderId}")
            return null
        }
        val remaining = filledQuantity.subtract(current.matchedQuantity).max(BigDecimal.ZERO)
        val updated = current.copy(
            quantity = filledQuantity,
            price = avgPrice ?: current.price,
            remainingQuantity = remaining,
            status = resolveMatchStatus(current.matchedQuantity, remaining),
            updatedAt = System.currentTimeMillis()
        )
        return copyOrderTrackingRepository.save(updated)
    }

    /** 修正买入均价，并同步重算已匹配卖出明细及其汇总盈亏。 */
    @Transactional
    fun updateBuyPrice(trackingId: Long, avgPrice: BigDecimal, feeRate: BigDecimal? = null): CopyOrderTracking? {
        val current = copyOrderTrackingRepository.findById(trackingId).orElse(null) ?: return null
        if (avgPrice.signum() <= 0) return current
        val affectedDetails = sellMatchDetailRepository.findByTrackingId(trackingId)
        val detailsNeedUpdate = affectedDetails.filter { it.buyPrice.compareTo(avgPrice) != 0 }
        val updated = copyOrderTrackingRepository.save(
            current.copy(
                price = avgPrice,
                updatedAt = System.currentTimeMillis()
            )
        )

        val affectedRecordIds = detailsNeedUpdate.map { detail ->
            val pnl = PolymarketTradingFee.netRealizedPnl(avgPrice, detail.sellPrice, detail.matchedQuantity, feeRate)
            sellMatchDetailRepository.save(detail.copy(buyPrice = avgPrice, realizedPnl = pnl))
            detail.matchRecordId
        }.toSet()
        for (recordId in affectedRecordIds) {
            val record = sellMatchRecordRepository.findById(recordId).orElse(null) ?: continue
            val totalPnl = sellMatchDetailRepository.findByMatchRecordId(recordId)
                .fold(BigDecimal.ZERO) { total, detail -> total.add(detail.realizedPnl) }
            sellMatchRecordRepository.save(record.copy(totalRealizedPnl = totalPnl))
        }
        return updated
    }

    /** 标记待确认买入为无法确认（长时间查询不到订单），停止轮询 */
    @Transactional
    fun markBuyUnconfirmed(trackingId: Long) {
        val current = copyOrderTrackingRepository.findById(trackingId).orElse(null) ?: return
        if (current.status != CopyOrderTracking.STATUS_PENDING) return
        current.status = CopyOrderTracking.STATUS_UNCONFIRMED
        current.updatedAt = System.currentTimeMillis()
        copyOrderTrackingRepository.save(current)
    }

    /** 只更新通知标记（及可选的创建时间），不重建实体，避免覆盖其他字段 */
    @Transactional
    fun markBuyNotificationSent(trackingId: Long): CopyOrderTracking? {
        val current = copyOrderTrackingRepository.findById(trackingId).orElse(null) ?: return null
        current.notificationSent = true
        current.updatedAt = System.currentTimeMillis()
        return copyOrderTrackingRepository.save(current)
    }

    /** 根据已核销数量与剩余数量计算状态 */
    fun resolveMatchStatus(matched: BigDecimal, remaining: BigDecimal): String = when {
        remaining.signum() <= 0 -> CopyOrderTracking.STATUS_FULLY_MATCHED
        matched.signum() > 0 -> CopyOrderTracking.STATUS_PARTIALLY_MATCHED
        else -> CopyOrderTracking.STATUS_FILLED
    }

    /**
     * 卖出下单前预占 tracking 数量并写入 PENDING 卖出记录
     * 每个 tracking 重新读取并校验 remaining >= 预占量（@Version 乐观锁兜底），任何一条不足则整体回滚
     */
    @Transactional
    fun reserveSell(record: SellMatchRecord, details: List<SellMatchDetail>): SellMatchRecord {
        for (detail in details) {
            val tracking = copyOrderTrackingRepository.findById(detail.trackingId).orElse(null)
                ?: throw InsufficientRemainingException("买入记录不存在: trackingId=${detail.trackingId}")
            if (tracking.remainingQuantity < detail.matchedQuantity) {
                throw InsufficientRemainingException(
                    "买入记录剩余数量不足: trackingId=${detail.trackingId}, remaining=${tracking.remainingQuantity}, need=${detail.matchedQuantity}"
                )
            }
            applyMatchDelta(tracking, detail.matchedQuantity)
        }
        val saved = sellMatchRecordRepository.save(record.copy(fillStatus = SellMatchRecord.FILL_STATUS_PENDING))
        for (detail in details) {
            sellMatchDetailRepository.save(detail.copy(matchRecordId = saved.id!!))
        }
        return saved
    }

    /**
     * 按实际成交量结算卖出记录：未成交部分按 FIFO 逆序退回 tracking，明细与盈亏按实际成交重算
     * @param filledQuantity 实际卖出 shares（SELL 的 makingAmount 或订单 size_matched）
     * @param avgPrice 成交均价；null 时保留下单价格
     * @return 结算后的记录；0 成交时删除记录并返回 null
     */
    @Transactional
    fun settleSell(recordId: Long, filledQuantity: BigDecimal, avgPrice: BigDecimal?, feeRate: BigDecimal?): SellMatchRecord? {
        val record = sellMatchRecordRepository.findById(recordId).orElse(null) ?: return null
        if (record.fillStatus == SellMatchRecord.FILL_STATUS_FILLED) return record
        val details = sellMatchDetailRepository.findByMatchRecordId(recordId).sortedBy { it.id ?: 0L }
        val reserved = details.fold(BigDecimal.ZERO) { acc, d -> acc.add(d.matchedQuantity) }
        val filled = filledQuantity.max(BigDecimal.ZERO).min(reserved)
        var toRelease = reserved.subtract(filled)
        val sellPrice = avgPrice ?: record.sellPrice
        var totalPnl = BigDecimal.ZERO
        for (detail in details.asReversed()) {
            val release = detail.matchedQuantity.min(toRelease)
            if (release.signum() > 0) {
                copyOrderTrackingRepository.findById(detail.trackingId).orElse(null)
                    ?.let { applyMatchDelta(it, release.negate()) }
                toRelease = toRelease.subtract(release)
            }
            val kept = detail.matchedQuantity.subtract(release)
            if (kept.signum() <= 0) {
                sellMatchDetailRepository.delete(detail)
                continue
            }
            val pnl = PolymarketTradingFee.netRealizedPnl(detail.buyPrice, sellPrice, kept, feeRate)
            sellMatchDetailRepository.save(detail.copy(matchedQuantity = kept, sellPrice = sellPrice, realizedPnl = pnl))
            totalPnl = totalPnl.add(pnl)
        }
        if (filled.signum() <= 0) {
            sellMatchRecordRepository.delete(record)
            logger.info("卖出订单 0 成交，已退回预占数量并删除记录: recordId=$recordId, orderId=${record.sellOrderId}")
            return null
        }
        return sellMatchRecordRepository.save(
            record.copy(
                totalMatchedQuantity = filled,
                sellPrice = sellPrice,
                totalRealizedPnl = totalPnl,
                fillStatus = SellMatchRecord.FILL_STATUS_FILLED
            )
        )
    }

    /** 用实际成交均价更新卖出记录及明细盈亏，并标记 priceUpdated */
    @Transactional
    fun updateSellPrice(recordId: Long, actualPrice: BigDecimal, feeRate: BigDecimal?): SellMatchRecord? {
        val record = sellMatchRecordRepository.findById(recordId).orElse(null) ?: return null
        var totalPnl = BigDecimal.ZERO
        for (detail in sellMatchDetailRepository.findByMatchRecordId(recordId)) {
            val pnl = PolymarketTradingFee.netRealizedPnl(detail.buyPrice, actualPrice, detail.matchedQuantity, feeRate)
            sellMatchDetailRepository.save(detail.copy(sellPrice = actualPrice, realizedPnl = pnl))
            totalPnl = totalPnl.add(pnl)
        }
        return sellMatchRecordRepository.save(
            record.copy(sellPrice = actualPrice, totalRealizedPnl = totalPnl, priceUpdated = true)
        )
    }

    /** 只更新卖出记录的若干状态字段（重新读取后 copy，避免旧快照覆盖） */
    @Transactional
    fun updateSellRecordState(
        recordId: Long,
        priceUpdated: Boolean? = null,
        fillStatus: String? = null,
        incrementPriceQueryAttempts: Boolean = false
    ): SellMatchRecord? {
        val record = sellMatchRecordRepository.findById(recordId).orElse(null) ?: return null
        return sellMatchRecordRepository.save(
            record.copy(
                priceUpdated = priceUpdated ?: record.priceUpdated,
                fillStatus = fillStatus ?: record.fillStatus,
                priceQueryAttempts = if (incrementPriceQueryAttempts) record.priceQueryAttempts + 1 else record.priceQueryAttempts,
                lastPriceQueryAt = if (incrementPriceQueryAttempts) System.currentTimeMillis() else record.lastPriceQueryAt
            )
        )
    }

    /** 调整 tracking 的已核销/剩余数量（delta>0 为核销，<0 为退回） */
    private fun applyMatchDelta(tracking: CopyOrderTracking, delta: BigDecimal) {
        tracking.matchedQuantity = tracking.matchedQuantity.add(delta).max(BigDecimal.ZERO)
        tracking.remainingQuantity = tracking.remainingQuantity.subtract(delta).max(BigDecimal.ZERO)
        tracking.status = resolveMatchStatus(tracking.matchedQuantity, tracking.remainingQuantity)
        tracking.updatedAt = System.currentTimeMillis()
        copyOrderTrackingRepository.save(tracking)
    }

    /** 卖出预占时 tracking 剩余数量不足（并发核销或数据已变化） */
    class InsufficientRemainingException(message: String) : IllegalStateException(message)
}
