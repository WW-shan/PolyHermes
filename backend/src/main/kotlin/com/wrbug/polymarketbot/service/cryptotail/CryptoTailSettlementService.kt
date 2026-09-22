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
import java.math.RoundingMode

/**
 * 加密价差策略结算轮询服务
 * 定时扫描「状态成功但未结算」的触发记录，通过 Gamma 获取 conditionId、链上查询结算结果，计算收益并回写。
 * 实际成交价与成交量使用 Data API 的 activity 接口获取（getUserActivity），比 CLOB getOrder 更准确；失败时回退为触发时的 amountUsdc + 固定价 0.99。
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
     * 处理单条触发记录：解析 conditionId -> 查链上结算 -> 若已结算则计算 pnl 并更新。
     * 通过 copy() 生成新实体再 save，不直接修改原实体；实际成交价与投入金额从 Data API activity 获取并更新 triggerPrice、amountUsdc。
     * @return true 表示本条已结算并更新
     */
    private suspend fun settleOne(trigger: CryptoTailStrategyTrigger): Boolean {
        if (trigger.resolved) return false
        val strategy = strategyRepository.findById(trigger.strategyId).orElse(null) ?: return false
        val conditionId = resolveConditionId(strategy, trigger) ?: return false
        val fill = fetchActivityFill(trigger, strategy, conditionId)
        val (newTriggerPrice, newAmountUsdc) = if (fill != null && fill.price.gt(BigDecimal.ZERO) && fill.size.gt(BigDecimal.ZERO)) {
            val amountUsdc = fill.usdcSize?.takeIf { it.gt(BigDecimal.ZERO) }
                ?: fill.price.multi(fill.size).setScale(pnlScale, RoundingMode.HALF_UP)
            Pair(fill.price, amountUsdc)
        } else {
            Pair(trigger.triggerPrice, trigger.amountUsdc)
        }

        val (_, payouts) = blockchainService.getCondition(conditionId).getOrNull() ?: run {
            if (fill != null) {
                val updated = trigger.copy(triggerPrice = newTriggerPrice, amountUsdc = newAmountUsdc)
                triggerRepository.save(updated)
            }
            return false
        }
        if (payouts.isEmpty()) {
            if (fill != null) {
                val updated = trigger.copy(triggerPrice = newTriggerPrice, amountUsdc = newAmountUsdc)
                triggerRepository.save(updated)
            }
            return false
        }
        val winnerIndex = payouts.indexOfFirst { it == java.math.BigInteger.ONE }
        if (winnerIndex < 0) return false

        val won = trigger.outcomeIndex == winnerIndex
        val pnl = if (fill != null && fill.price.gt(BigDecimal.ZERO) && fill.size.gt(BigDecimal.ZERO)) {
            CryptoTailPnlCalculator.pnlFromFill(fill.price, fill.size, fill.usdcSize, won)
        } else {
            CryptoTailPnlCalculator.pnlFallback(trigger.amountUsdc, won)
        }
        val now = System.currentTimeMillis()

        val updated = trigger.copy(
            triggerPrice = newTriggerPrice,
            amountUsdc = newAmountUsdc,
            conditionId = conditionId,
            resolved = true,
            winnerOutcomeIndex = winnerIndex,
            realizedPnl = pnl,
            settledAt = now
        )
        triggerRepository.save(updated)
        logger.debug("加密价差策略结算已更新: triggerId=${trigger.id}, winnerOutcomeIndex=$winnerIndex, won=$won, pnl=$pnl")
        return true
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
            val fill = aggregateActivityFills(activities, conditionId, trigger.outcomeIndex)
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

        /**
         * 聚合同一笔订单的全部成交：FAK 订单可能扫过多档挂单，产生多条 TRADE，
         * 只取其中一条会低估成交量与成本。价格取成交量加权均价。
         */
        internal fun aggregateActivityFills(
            activities: List<com.wrbug.polymarketbot.api.UserActivityResponse>,
            conditionId: String,
            outcomeIndex: Int?
        ): ActivityFill? {
            val matches = activities.filter { a ->
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
