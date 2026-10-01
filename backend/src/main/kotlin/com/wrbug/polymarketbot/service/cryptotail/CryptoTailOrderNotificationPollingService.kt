package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.div
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import jakarta.annotation.PreDestroy
import java.math.BigDecimal

/**
 * 加密价差策略订单 TG 通知轮询服务（与跟单一致）
 * 定时查询「下单成功且未发 TG」的触发记录，通过 CLOB getOrder 获取订单详情后发送 TG 并标记已发。
 */
@Service
class CryptoTailOrderNotificationPollingService(
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val strategyRepository: CryptoTailStrategyRepository,
    private val accountRepository: AccountRepository,
    private val retrofitFactory: RetrofitFactory,
    private val cryptoUtils: CryptoUtils,
    private val marketService: MarketService,
    private val telegramNotificationService: TelegramNotificationService
) : ApplicationContextAware {

    private val logger = LoggerFactory.getLogger(CryptoTailOrderNotificationPollingService::class.java)
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)

    private var applicationContext: ApplicationContext? = null

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }

    private fun getSelf(): CryptoTailOrderNotificationPollingService {
        return applicationContext?.getBean(CryptoTailOrderNotificationPollingService::class.java)
            ?: throw IllegalStateException("ApplicationContext not initialized")
    }

    @Volatile
    private var notificationJob: Job? = null

    @Scheduled(fixedDelay = 5000)
    fun scheduledSendPendingNotifications() {
        if (notificationJob != null && notificationJob!!.isActive) {
            logger.debug("上一轮加密价差策略 TG 通知任务仍在执行，跳过本次")
            return
        }
        notificationJob = scope.launch {
            try {
                getSelf().sendPendingNotifications()
            } catch (e: Exception) {
                logger.error("加密价差策略 TG 通知轮询异常: ${e.message}", e)
            } finally {
                notificationJob = null
            }
        }
    }

    @Transactional
    suspend fun sendPendingNotifications() {
        val pending = triggerRepository.findByStatusAndOrderIdIsNotNullAndNotificationSentFalseOrderByCreatedAtAsc("success")
        if (pending.isEmpty()) return
        for (trigger in pending) {
            try {
                if (trigger.resolved) {
                    trigger.notificationSent = true
                    triggerRepository.save(trigger)
                    logger.debug("触发已结算，跳过请求并标记已通知: triggerId=${trigger.id}, orderId=${trigger.orderId}")
                    continue
                }
                if (sendNotificationForTrigger(trigger)) {
                    trigger.notificationSent = true
                    triggerRepository.save(trigger)
                }
            } catch (e: Exception) {
                logger.warn("加密价差策略 TG 通知单条失败: triggerId=${trigger.id}, orderId=${trigger.orderId}, ${e.message}", e)
            }
        }
    }

    private suspend fun sendNotificationForTrigger(trigger: CryptoTailStrategyTrigger): Boolean {
        val strategy = strategyRepository.findById(trigger.strategyId).orElse(null) ?: return false
        val account = accountRepository.findById(strategy.accountId).orElse(null) ?: return false
        val orderId = trigger.orderId ?: return false
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
            logger.debug("账户未配置 API 凭证，跳过 TG: accountId=${account.id}")
            return false
        }
        val apiSecret = try {
            cryptoUtils.decrypt(account.apiSecret)
        } catch (e: Exception) {
            logger.warn("解密 API Secret 失败: accountId=${account.id}", e)
            return false
        }
        val apiPassphrase = try {
            cryptoUtils.decrypt(account.apiPassphrase)
        } catch (e: Exception) { "" }
        val clobApi = retrofitFactory.createClobApi(
            account.apiKey,
            apiSecret,
            apiPassphrase,
            account.walletAddress
        )
        val orderResponse = clobApi.getOrder(orderId)
        if (!orderResponse.isSuccessful) {
            logger.debug("查询订单详情失败，等待下次轮询: orderId=$orderId, code=${orderResponse.code()}")
            return false
        }
        val order = orderResponse.body() ?: run {
            logger.debug("订单详情为空，等待下次轮询: orderId=$orderId")
            return false
        }
        val market = marketService.getMarket(order.market)
        val marketTitle = trigger.marketTitle?.takeIf { it.isNotBlank() } ?: market?.title ?: order.market
        val orderTimeMs = if (order.createdAt < 1_000_000_000_000L) order.createdAt * 1000 else order.createdAt
        // 成交均价：CLOB 订单里的 price 是限价而非成交价，这里用下单时保存的交易哈希到 Data API 聚合实际成交；
        // 拿不到精确成交明细时不展示均价，避免推送错误数值
        val avgFilledPriceStr = fetchAvgFilledPrice(trigger, account.proxyAddress, order.market)?.toPlainString()
        val filledSize = order.sizeMatched
        telegramNotificationService.sendCryptoTailOrderSuccessNotification(
            orderId = orderId,
            marketTitle = marketTitle,
            marketId = order.market,
            marketSlug = market?.eventSlug ?: market?.slug,
            side = order.side,
            outcome = order.outcome,
            price = order.price,
            size = order.originalSize,
            avgFilledPrice = avgFilledPriceStr,
            filled = filledSize,
            strategyName = strategy.name,
            accountName = account.accountName,
            walletAddress = account.walletAddress,
            orderTime = orderTimeMs
        )
        logger.info("加密价差策略订单 TG 通知已发送: orderId=$orderId, strategyId=${strategy.id}, triggerId=${trigger.id}")
        return true
    }

    /**
     * 按交易哈希从 Data API activity 聚合本单成交，返回成交量加权均价；无哈希或查询失败返回 null。
     */
    private suspend fun fetchAvgFilledPrice(
        trigger: CryptoTailStrategyTrigger,
        proxyAddress: String,
        conditionId: String
    ): BigDecimal? {
        val txHashes = CryptoTailTriggerRecorder.splitTransactionHashes(trigger.transactionHashes)
        if (txHashes.isEmpty()) return null
        return try {
            val triggerTimeSeconds = trigger.createdAt / 1000
            val response = retrofitFactory.createDataApi().getUserActivity(
                user = proxyAddress,
                market = listOf(conditionId),
                type = listOf("TRADE"),
                side = "BUY",
                start = triggerTimeSeconds - 120,
                end = triggerTimeSeconds + 600,
                limit = 500,
                sortBy = "TIMESTAMP",
                sortDirection = "ASC"
            )
            val activities = response.body()
            if (!response.isSuccessful || activities == null) return null
            CryptoTailSettlementService.aggregateActivityFills(activities, conditionId, trigger.outcomeIndex, txHashes)?.price
        } catch (e: Exception) {
            logger.debug("查询成交明细失败，TG 不展示成交均价: triggerId=${trigger.id}, ${e.message}")
            null
        }
    }

    @PreDestroy
    fun destroy() {
        notificationJob?.cancel()
        notificationJob = null
        scopeJob.cancel()
    }
}
