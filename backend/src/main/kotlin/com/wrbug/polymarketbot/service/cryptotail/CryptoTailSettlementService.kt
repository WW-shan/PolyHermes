package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import jakarta.annotation.PreDestroy
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * 加密价差策略结算轮询服务
 * 定时扫描「状态成功但未结算」的触发记录，通过 Gamma 获取 conditionId、链上查询结算结果，计算收益并回写。
 * 实际成交价与成交量使用 Data API 的 activity 接口获取（getUserActivity），按下单时保存的交易哈希过滤；
 * activity 失败时本轮不落库，周期结束超过 6 小时仍失败才按估算值落库并标记 ESTIMATED。
 */
@Service
class CryptoTailSettlementService(
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val strategyRepository: CryptoTailStrategyRepository,
    private val accountRepository: AccountRepository,
    private val retrofitFactory: RetrofitFactory,
    private val blockchainService: BlockchainService
) {

    private val logger = LoggerFactory.getLogger(CryptoTailSettlementService::class.java)

    private val triggerFixedPrice = BigDecimal("0.99")
    private val pnlScale = 8

    private val settlementScopeJob = SupervisorJob()
    private val settlementScope = CoroutineScope(Dispatchers.IO + settlementScopeJob)

    /** 跟踪上一轮结算任务的 Job，防止并发执行（与 OrderStatusUpdateService 一致） */
    @Volatile
    private var settlementJob: Job? = null

    /**
     * 定时轮询：每 10 秒执行一次。
     * 若上一轮任务仍在执行则跳过本次，避免并发重叠。
     */
    @Scheduled(fixedDelay = 10_000)
    fun scheduledPollAndSettle() {
        val previousJob = settlementJob
        if (previousJob != null && previousJob.isActive) {
            logger.debug("上一轮加密价差策略结算任务仍在执行，跳过本次调度")
            return
        }
        settlementJob = settlementScope.launch {
            try {
                doPollAndSettle()
            } catch (e: Exception) {
                logger.error("加密价差策略结算定时任务异常: ${e.message}", e)
            } finally {
                settlementJob = null
            }
        }
    }

    /**
     * 轮询入口：拉取所有 status=success 且 resolved=false 的触发记录，逐条尝试结算并更新。
     * Controller/定时任务调用此方法（内部对 suspend 使用 runBlocking）。
     */
    @Transactional
    fun pollAndSettle(): Int = runBlocking {
        doPollAndSettle()
    }

    private suspend fun doPollAndSettle(): Int {
        val pending = triggerRepository.findByStatusAndResolvedAndOrderIdIsNotNullOrderByCreatedAtAsc("success", false)
        if (pending.isEmpty()) return 0
        var settledCount = 0
        for (trigger in pending) {
            try {
                if (settleOne(trigger)) settledCount++
            } catch (e: Exception) {
                logger.warn("加密价差策略结算单条失败: triggerId=${trigger.id}, ${e.message}", e)
            }
        }
        if (settledCount > 0) {
            logger.info("加密价差策略结算轮询完成: 处理=${pending.size}, 新结算=$settledCount")
        }
        return settledCount
    }

    /**
     * 处理单条触发记录：解析 conditionId -> 拉取 activity 成交 -> 查链上结算 -> 若已结算则计算 pnl 并更新。
     * activity 失败或无匹配成交时本轮不落库（下轮重试）；周期结束超过 [ESTIMATE_AFTER_MS] 仍拿不到才按估算值落库并标记 ESTIMATED。
     * @return true 表示本条已结算并更新
     */
    private suspend fun settleOne(trigger: CryptoTailStrategyTrigger): Boolean {
        if (trigger.resolved) return false
        val strategy = strategyRepository.findById(trigger.strategyId).orElse(null) ?: return false
        val conditionId = resolveConditionId(strategy, trigger) ?: return false
        val fill = fetchActivityFill(trigger, strategy, conditionId)
        val hasFill = fill != null && fill.price.gt(BigDecimal.ZERO) && fill.size.gt(BigDecimal.ZERO)
        val newTriggerPrice = if (hasFill) fill!!.price else trigger.triggerPrice
        val newAmountUsdc = if (hasFill) {
            fill!!.usdcSize?.takeIf { it.gt(BigDecimal.ZERO) }
                ?: fill.price.multi(fill.size).setScale(pnlScale, RoundingMode.HALF_UP)
        } else {
            trigger.amountUsdc
        }

        val (denominator, payouts) = blockchainService.getCondition(conditionId).getOrNull() ?: run {
            saveFillIfChanged(trigger, hasFill, newTriggerPrice, newAmountUsdc, conditionId)
            return false
        }
        val payoutRatio = payoutRatio(denominator, payouts, trigger.outcomeIndex)
        if (payoutRatio == null) {
            // 未结算、或链上数据不完整（长度不符 / 分母为 0 / 分子和不等于分母），下轮重试
            saveFillIfChanged(trigger, hasFill, newTriggerPrice, newAmountUsdc, conditionId)
            return false
        }
        val winnerIndex = winnerIndex(denominator, payouts)

        val periodEndMs = (trigger.periodStartUnix + strategy.intervalSeconds) * 1000L
        val source = settlementSource(hasFill, !trigger.transactionHashes.isNullOrBlank(), periodEndMs, System.currentTimeMillis())
        if (source == null) {
            logger.debug("加密价差策略结算暂无 activity 成交，下轮重试: triggerId=${trigger.id}")
            return false
        }
        val pnl = if (hasFill) {
            CryptoTailPnlCalculator.pnlFromFillWithPayout(fill!!.price, fill.size, fill.usdcSize, payoutRatio)
        } else {
            val estimated = CryptoTailPnlCalculator.pnlEstimated(trigger.amountUsdc, trigger.triggerPrice, payoutRatio)
                ?: return false
            logger.warn(
                "加密价差策略结算长期拿不到 activity 成交，按估算值落库: triggerId=${trigger.id}, " +
                    "triggerPrice=${trigger.triggerPrice}, amountUsdc=${trigger.amountUsdc}, pnl=$estimated"
            )
            estimated
        }
        if (source == SOURCE_TIME_WINDOW) {
            logger.warn("加密价差策略结算按时间窗聚合成交（无交易哈希，可能不精确）: triggerId=${trigger.id}, orderId=${trigger.orderId}")
        }

        val updated = trigger.copy(
            triggerPrice = newTriggerPrice,
            amountUsdc = newAmountUsdc,
            conditionId = conditionId,
            resolved = true,
            winnerOutcomeIndex = winnerIndex,
            realizedPnl = pnl,
            settledAt = System.currentTimeMillis(),
            settlementSource = source
        )
        triggerRepository.save(updated)
        logger.debug("加密价差策略结算已更新: triggerId=${trigger.id}, winnerOutcomeIndex=$winnerIndex, payoutRatio=$payoutRatio, pnl=$pnl, source=$source")
        return true
    }

    /** 未结算时仅回写 activity 实际成交价/金额（用于展示），没有新数据则不写库 */
    private fun saveFillIfChanged(
        trigger: CryptoTailStrategyTrigger,
        hasFill: Boolean,
        price: BigDecimal,
        amountUsdc: BigDecimal,
        conditionId: String
    ) {
        if (!hasFill) return
        triggerRepository.save(trigger.copy(triggerPrice = price, amountUsdc = amountUsdc, conditionId = conditionId))
    }

    private suspend fun resolveConditionId(strategy: CryptoTailStrategy, trigger: CryptoTailStrategyTrigger): String? {
        if (!trigger.conditionId.isNullOrBlank()) return trigger.conditionId
        val slug = "${strategy.marketSlugPrefix}-${trigger.periodStartUnix}"
        val event = fetchEventBySlug(slug).getOrNull() ?: return null
        val markets = event.markets ?: return null
        val first = markets.firstOrNull() ?: return null
        return first.conditionId?.takeIf { it.isNotBlank() }
    }

    private suspend fun fetchEventBySlug(slug: String): Result<GammaEventBySlugResponse> {
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.getEventBySlug(slug)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val msg = if (response.code() == 404) "404" else "code=${response.code()}"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 通过 Data API activity 接口获取该触发对应的实际成交价、成交量与投入金额（比 CLOB getOrder 更准确）。
     * 只有此接口返回匹配的 TRADE 且 price/size 有效时，结算才会更新 triggerPrice、amountUsdc（表现）；投入金额优先用 activity 的 usdcSize。
     */
    private suspend fun fetchActivityFill(
        trigger: CryptoTailStrategyTrigger,
        strategy: CryptoTailStrategy,
        conditionId: String
    ): ActivityFill? {
        val account = accountRepository.findById(strategy.accountId).orElse(null) ?: run {
            logger.warn("加密价差策略结算未拉取 activity: 账户不存在, triggerId=${trigger.id}, accountId=${strategy.accountId}")
            return null
        }
        val user = account.proxyAddress
        val triggerTimeSeconds = trigger.createdAt / 1000
        val start = triggerTimeSeconds - 120
        val end = triggerTimeSeconds + 600
        return try {
            val dataApi = retrofitFactory.createDataApi()
            val response = dataApi.getUserActivity(
                user = user,
                market = listOf(conditionId),
                type = listOf("TRADE"),
                side = "BUY",
                start = start,
                end = end,
                limit = ACTIVITY_LIMIT,
                sortBy = "TIMESTAMP",
                sortDirection = "ASC"
            )
            if (!response.isSuccessful || response.body() == null) {
                logger.warn("加密价差策略结算拉取 activity 失败: triggerId=${trigger.id}, code=${response.code()}")
                return null
            }
            val activities = response.body()!!
            val txHashes = CryptoTailTriggerRecorder.splitTransactionHashes(trigger.transactionHashes)
            val fill = aggregateActivityFills(activities, conditionId, trigger.outcomeIndex, txHashes)
            if (fill == null) {
                logger.debug("加密价差策略结算 activity 无匹配成交: triggerId=${trigger.id}, conditionId=$conditionId, outcomeIndex=${trigger.outcomeIndex}, 条数=${activities.size}")
            }
            fill
        } catch (e: Exception) {
            logger.warn("加密价差策略结算拉取 activity 异常，触发价/投入金额不会更新: triggerId=${trigger.id}, error=${e.message}")
            null
        }
    }

    companion object {
        /** 单次 activity 查询上限（接口最大 500），保证扫到同一笔 FAK 订单的全部成交 */
        private const val ACTIVITY_LIMIT = 500

        /** 周期结束后超过该时长仍拿不到 activity 成交，才按估算值落库 */
        internal const val ESTIMATE_AFTER_MS = 6 * 60 * 60 * 1000L

        const val SOURCE_TX_HASH = "TX_HASH"
        const val SOURCE_TIME_WINDOW = "TIME_WINDOW"
        const val SOURCE_ESTIMATED = "ESTIMATED"

        /** 二元市场 outcome 数 */
        private const val BINARY_OUTCOME_COUNT = 2

        /**
         * 链上结算赔付比例 payoutNumerator / payoutDenominator（赢 1、输 0、平局 0.5）。
         * 防御式校验：分母为 0、payouts 长度不等于 outcome 数、分子和不等于分母（有查询失败被跳过）时视为未结算，返回 null。
         */
        internal fun payoutRatio(denominator: BigInteger, payouts: List<BigInteger>, outcomeIndex: Int): BigDecimal? {
            if (denominator.signum() <= 0) return null
            if (payouts.size != BINARY_OUTCOME_COUNT) return null
            if (outcomeIndex !in payouts.indices) return null
            if (payouts.any { it.signum() < 0 }) return null
            if (payouts.fold(BigInteger.ZERO) { a, b -> a.add(b) } != denominator) return null
            return BigDecimal(payouts[outcomeIndex]).divide(BigDecimal(denominator), 18, RoundingMode.HALF_UP)
        }

        /**
         * 结算数据来源；返回 null 表示本轮不落库、下轮重试。
         * 有成交：有交易哈希为 TX_HASH，否则 TIME_WINDOW；无成交：周期结束超过 [ESTIMATE_AFTER_MS] 才允许 ESTIMATED。
         */
        internal fun settlementSource(hasFill: Boolean, hasTxHashes: Boolean, periodEndMs: Long, nowMs: Long): String? = when {
            hasFill && hasTxHashes -> SOURCE_TX_HASH
            hasFill -> SOURCE_TIME_WINDOW
            nowMs - periodEndMs >= ESTIMATE_AFTER_MS -> SOURCE_ESTIMATED
            else -> null
        }

        /** 全额赔付的 outcome 索引；平局（如 50/50）没有单一赢家，返回 null */
        internal fun winnerIndex(denominator: BigInteger, payouts: List<BigInteger>): Int? =
            payouts.indexOfFirst { it == denominator }.takeIf { it >= 0 }

        /**
         * 聚合同一笔订单的全部成交：FAK 订单可能扫过多档挂单，产生多条 TRADE，
         * 只取其中一条会低估成交量与成本。价格取成交量加权均价。
         * 传入下单响应的交易哈希时只聚合这些交易，避免混入同市场同方向的其他买单；
         * 旧数据没有哈希时按条件聚合（可能不精确）。
         */
        internal fun aggregateActivityFills(
            activities: List<com.wrbug.polymarketbot.api.UserActivityResponse>,
            conditionId: String,
            outcomeIndex: Int?,
            transactionHashes: Set<String> = emptySet()
        ): ActivityFill? {
            val matches = activities.filter { a ->
                (transactionHashes.isEmpty() || a.transactionHash?.lowercase() in transactionHashes) &&
                a.type == "TRADE" &&
                    a.conditionId == conditionId &&
                    a.outcomeIndex != null && a.outcomeIndex in 0..1 &&
                    a.outcomeIndex == outcomeIndex &&
                    a.side?.uppercase() == "BUY" &&
                    a.price != null && a.price > 0 &&
                    a.size != null && a.size > 0
            }
            if (matches.isEmpty()) return null
            var totalSize = BigDecimal.ZERO
            var notional = BigDecimal.ZERO
            var totalUsdc = BigDecimal.ZERO
            var usdcComplete = true
            for (m in matches) {
                val size = m.size!!.toSafeBigDecimal()
                val price = m.price!!.toSafeBigDecimal()
                if (size <= BigDecimal.ZERO || price <= BigDecimal.ZERO) continue
                totalSize = totalSize.add(size)
                notional = notional.add(price.multiply(size))
                val usdc = m.usdcSize?.toSafeBigDecimal()
                if (usdc != null && usdc.gt(BigDecimal.ZERO)) {
                    totalUsdc = totalUsdc.add(usdc)
                } else {
                    usdcComplete = false
                }
            }
            if (totalSize <= BigDecimal.ZERO) return null
            val averagePrice = notional.divide(totalSize, 8, RoundingMode.DOWN)
            return ActivityFill(
                price = averagePrice,
                size = totalSize,
                usdcSize = if (usdcComplete && totalUsdc.gt(BigDecimal.ZERO)) totalUsdc else null
            )
        }
    }

    @PreDestroy
    fun destroy() {
        settlementJob?.cancel()
        settlementJob = null
        settlementScopeJob.cancel()
    }
}

/**
 * Activity 匹配到的一笔成交聚合结果：成交量加权均价、总成交量、实际投入 USDC（含 taker 手续费）。
 */
internal data class ActivityFill(
    val price: BigDecimal,
    val size: BigDecimal,
    val usdcSize: BigDecimal?
)
