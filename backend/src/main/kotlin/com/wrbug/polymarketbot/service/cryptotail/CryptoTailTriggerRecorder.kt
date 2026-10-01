package com.wrbug.polymarketbot.service.cryptotail

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * 加密价差策略触发记录的幂等占位与回写。
 *
 * 下单前先插入一条 pending 记录，依赖 (strategy_id, period_start_unix) 唯一约束保证每周期只触发一次；
 * 下单后再把该记录更新为 success/fail。成功单写库失败时保留内存「已下单」标记，绝不改写成 fail。
 */
@Component
class CryptoTailTriggerRecorder(
    private val triggerRepository: CryptoTailStrategyTriggerRepository
) {

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAIL = "fail"

        /** fail_reason 列长度 */
        const val FAIL_REASON_MAX_LENGTH = 500

        /** transaction_hashes 列长度 */
        const val TX_HASHES_MAX_LENGTH = 2000

        /** 按字符截断，且不把代理对（emoji 等）拆成半个字符 */
        fun truncate(text: String?, maxLength: Int): String? {
            if (text == null || text.length <= maxLength) return text
            var end = maxLength
            if (Character.isHighSurrogate(text[end - 1])) end--
            return text.substring(0, end)
        }

        /** 交易哈希列表序列化为逗号分隔字符串，超长时丢弃尾部完整哈希 */
        fun joinTransactionHashes(hashes: List<String>?): String? {
            val cleaned = hashes.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            if (cleaned.isEmpty()) return null
            val sb = StringBuilder()
            for (h in cleaned) {
                val extra = if (sb.isEmpty()) h.length else h.length + 1
                if (sb.length + extra > TX_HASHES_MAX_LENGTH) break
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(h)
            }
            return sb.toString()
        }

        fun splitTransactionHashes(value: String?): Set<String> {
            if (value.isNullOrBlank()) return emptySet()
            return value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        }

        private fun key(strategyId: Long, periodStartUnix: Long) = "$strategyId-$periodStartUnix"
    }

    private val logger = LoggerFactory.getLogger(CryptoTailTriggerRecorder::class.java)

    /** 已提交成功（CLOB 返回 orderId）的周期：即使写库失败也阻止同周期再次下单 */
    private val placedPeriods: Cache<String, String> = Caffeine.newBuilder()
        .expireAfterWrite(6, TimeUnit.HOURS)
        .maximumSize(10_000)
        .build()

    /** 本进程内已占位、尚未回写的记录 */
    private val pendingRecords: Cache<String, CryptoTailStrategyTrigger> = Caffeine.newBuilder()
        .expireAfterWrite(6, TimeUnit.HOURS)
        .maximumSize(10_000)
        .build()

    /** 本周期是否已触发（内存已下单标记或数据库已有记录） */
    fun isTriggered(strategyId: Long, periodStartUnix: Long): Boolean {
        if (placedPeriods.getIfPresent(key(strategyId, periodStartUnix)) != null) return true
        return triggerRepository.findByStrategyIdAndPeriodStartUnix(strategyId, periodStartUnix) != null
    }

    /**
     * 下单前占位：插入 pending 记录。唯一约束冲突（或内存已下单）说明本周期已触发，返回 null。
     * 其他数据库异常直接抛出，调用方不得在无占位的情况下下单。
     */
    fun reserve(
        strategyId: Long,
        periodStartUnix: Long,
        marketTitle: String?,
        outcomeIndex: Int,
        triggerPrice: BigDecimal,
        amountUsdc: BigDecimal,
        triggerType: String
    ): CryptoTailStrategyTrigger? {
        val k = key(strategyId, periodStartUnix)
        if (placedPeriods.getIfPresent(k) != null) return null
        val record = CryptoTailStrategyTrigger(
            strategyId = strategyId,
            periodStartUnix = periodStartUnix,
            marketTitle = truncate(marketTitle, 500),
            outcomeIndex = outcomeIndex,
            triggerPrice = triggerPrice,
            amountUsdc = amountUsdc,
            status = STATUS_PENDING,
            triggerType = triggerType
        )
        return try {
            val saved = triggerRepository.saveAndFlush(record)
            pendingRecords.put(k, saved)
            saved
        } catch (e: DataIntegrityViolationException) {
            logger.info("加密价差策略本周期已触发（唯一约束冲突），跳过: strategyId=$strategyId, periodStartUnix=$periodStartUnix")
            null
        }
    }

    /** 记录 CLOB 已接受订单：先打内存标记，再回写数据库；写库失败只打 ERROR，不改写成 fail */
    fun completeSuccess(
        reserved: CryptoTailStrategyTrigger,
        orderId: String,
        transactionHashes: List<String>?,
        triggerPrice: BigDecimal,
        amountUsdc: BigDecimal
    ) {
        val k = key(reserved.strategyId, reserved.periodStartUnix)
        placedPeriods.put(k, orderId)
        try {
            triggerRepository.save(
                reserved.copy(
                    status = STATUS_SUCCESS,
                    orderId = orderId,
                    transactionHashes = joinTransactionHashes(transactionHashes),
                    triggerPrice = triggerPrice,
                    amountUsdc = amountUsdc,
                    failReason = null
                )
            )
            pendingRecords.invalidate(k)
        } catch (e: Exception) {
            logger.error(
                "加密价差策略订单已提交成功但写库失败（记录保持 pending，需人工核对）: triggerId=${reserved.id}, " +
                    "strategyId=${reserved.strategyId}, periodStartUnix=${reserved.periodStartUnix}, orderId=$orderId",
                e
            )
        }
    }

    /** 记录失败：失败原因安全截断到列长度 */
    fun completeFail(
        reserved: CryptoTailStrategyTrigger,
        failReason: String?,
        triggerPrice: BigDecimal = reserved.triggerPrice,
        amountUsdc: BigDecimal = reserved.amountUsdc
    ) {
        val k = key(reserved.strategyId, reserved.periodStartUnix)
        try {
            triggerRepository.save(
                reserved.copy(
                    status = STATUS_FAIL,
                    failReason = truncate(failReason, FAIL_REASON_MAX_LENGTH),
                    triggerPrice = triggerPrice,
                    amountUsdc = amountUsdc
                )
            )
            pendingRecords.invalidate(k)
        } catch (e: Exception) {
            logger.error("加密价差策略失败记录写库失败: triggerId=${reserved.id}, reason=${truncate(failReason, 200)}", e)
        }
    }

    /**
     * 释放占位：仅用于 CLOB 明确拒绝（订单确定未进入撮合）的手动下单，允许用户在本周期重试。
     * 结果未知（超时、异常）时不得调用，应使用 [completeFail] 保留记录。
     */
    fun release(reserved: CryptoTailStrategyTrigger) {
        val k = key(reserved.strategyId, reserved.periodStartUnix)
        if (placedPeriods.getIfPresent(k) != null) return
        try {
            reserved.id?.let { triggerRepository.deleteById(it) }
            pendingRecords.invalidate(k)
        } catch (e: Exception) {
            logger.error("加密价差策略释放占位记录失败: triggerId=${reserved.id}", e)
        }
    }

    /** 取本进程内的占位记录（供没有显式持有占位对象的调用方回写） */
    fun getPending(strategyId: Long, periodStartUnix: Long): CryptoTailStrategyTrigger? =
        pendingRecords.getIfPresent(key(strategyId, periodStartUnix))
}
