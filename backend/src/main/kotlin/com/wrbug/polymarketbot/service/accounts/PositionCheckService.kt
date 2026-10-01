package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.entity.SellMatchRecord
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.multi
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import com.wrbug.polymarketbot.service.system.SystemConfigService
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.service.common.MarketPriceService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.util.PolymarketTradingFee
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仓位检查服务
 * 负责检查待赎回仓位和未卖出订单，并执行相应的处理逻辑
 * 订阅 PositionPollingService 的事件，处理仓位检查逻辑
 */
@Service
class PositionCheckService(
    private val positionPollingService: PositionPollingService,
    private val accountService: AccountService,
    private val copyTradingRepository: CopyTradingRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository,
    private val systemConfigService: SystemConfigService,
    private val relayClientService: RelayClientService,
    private val telegramNotificationService: TelegramNotificationService?,
    private val accountRepository: AccountRepository,
    private val messageSource: MessageSource,
    private val marketPriceService: MarketPriceService,
    private val marketService: MarketService,
    private val blockchainService: BlockchainService
) {
    
    private val logger = LoggerFactory.getLogger(PositionCheckService::class.java)
    
    // 协程作用域，用于订阅事件和缓存清理任务
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var subscriptionJob: Job? = null
    
    // 记录已发送通知的仓位（避免重复推送）
    private val notifiedRedeemablePositions = ConcurrentHashMap<String, Long>()  // "accountId_marketId_outcomeIndex" -> lastNotificationTime
    
    // 记录已处理的赎回仓位（避免重复赎回）
    private val processedRedeemablePositions = ConcurrentHashMap<String, Long>()  // "accountId_marketId_outcomeIndex" -> lastProcessTime
    
    // 记录已发送提示的配置（避免重复推送）
    private val notifiedConfigs = ConcurrentHashMap<Long, Long>()  // accountId/copyTradingId -> lastNotificationTime
    
    // 待检查的仓位记录（延迟检测机制）
    // key: "accountId_marketId_outcomeIndex_copyTradingId"
    // value: PendingPositionCheck（包含订单列表和首次检测时间）
    private data class PendingPositionCheck(
        val accountId: Long,
        val marketId: String,
        val outcomeIndex: Int,
        val copyTradingId: Long,
        val orders: List<CopyOrderTracking>,
        val firstDetectedTime: Long  // 首次检测到仓位不存在的时间
    )
    private val pendingPositionChecks = ConcurrentHashMap<String, PendingPositionCheck>()

    // 自动赎回失败退避：positionKey -> (连续失败次数, 下次允许重试时间)
    private val redeemFailures = ConcurrentHashMap<String, Pair<Int, Long>>()

    /** 返回该仓位下次允许重试赎回的时间；无失败记录返回 null */
    private fun redeemRetryAt(positionKey: String): Long? = redeemFailures[positionKey]?.second

    /**
     * 记录赎回失败并计算指数退避：5 分钟 × 2^(n-1)，上限 6 小时；
     * 结果未知（Relayer 未到终态）按至少 10 分钟退避，避免重复提交在途交易
     */
    internal fun recordRedeemFailure(positionKey: String, error: Throwable, now: Long = System.currentTimeMillis()): Long {
        val failures = (redeemFailures[positionKey]?.first ?: 0) + 1
        val base = REDEEM_BACKOFF_BASE_MS shl minOf(failures - 1, 16)
        var backoff = minOf(base, REDEEM_BACKOFF_MAX_MS)
        if (error is RelayClientService.RelayerTransactionPendingException) {
            backoff = maxOf(backoff, 600_000L)
        }
        val retryAt = now + backoff
        redeemFailures[positionKey] = failures to retryAt
        return retryAt
    }

    // 持仓缩量确认计数：key 同 pendingPositionChecks，连续达到 SHRINK_CONFIRMATIONS 轮才处理
    private val shrinkConfirmations = ConcurrentHashMap<String, Int>()

    companion object {
        /** 最小卖出差额（份），小于该值视为精度误差 */
        val MIN_SOLD_DIFF: BigDecimal = BigDecimal("0.01")

        /** 缩量分支需要连续确认的轮数 */
        const val SHRINK_CONFIRMATIONS = 3

        /** 不参与核对的 tracking 状态（F3 新增：数量为 0，尚未确认成交） */
        val NON_SELLABLE_STATUSES = setOf("pending", "unconfirmed")

        /** 自动赎回失败退避基数与上限 */
        const val REDEEM_BACKOFF_BASE_MS = 300_000L
        const val REDEEM_BACKOFF_MAX_MS = 21_600_000L
    }
    
    // 同步锁，确保订阅任务的启动和停止是线程安全的
    private val lock = Any()

    // 防止 checkRedeemablePositions 重入：上一轮检查未完成时，新一轮轮询直接跳过
    private val redeemCheckInProgress = AtomicBoolean(false)

    // 整体互斥：checkPositions / checkPendingPositions 同一时刻只运行一轮，上一轮未完成则跳过本轮
    private val positionCheckInProgress = AtomicBoolean(false)

    /**
     * 初始化服务（订阅 PositionPollingService 的事件，启动缓存清理任务）
     */
    @PostConstruct
    fun init() {
        logger.info("PositionCheckService 初始化，订阅仓位轮训事件")
        startSubscription()
        startCacheCleanup()
        startPendingPositionCheckTask()
    }
    
    /**
     * 清理资源
     */
    @PreDestroy
    fun destroy() {
        synchronized(lock) {
            subscriptionJob?.cancel()
            subscriptionJob = null
        }
        scope.cancel()
    }
    
    /**
     * 启动订阅任务（订阅 PositionPollingService 的事件）
     */
    private fun startSubscription() {
        synchronized(lock) {
            // 如果已经有订阅任务在运行，先取消
            subscriptionJob?.cancel()
            
            // 启动新的订阅任务（使用专门的线程，避免阻塞）
            subscriptionJob = scope.launch(Dispatchers.IO) {
                try {
                    // 订阅仓位轮训事件
                    positionPollingService.subscribe { positions ->
                        // 在协程中处理仓位检查逻辑，避免阻塞
                        scope.launch(Dispatchers.IO) {
                            try {
                                checkPositions(positions.currentPositions, positions.failedAccountIds.toSet())
                            } catch (e: Exception) {
                                logger.error("处理仓位检查事件失败: ${e.message}", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("订阅仓位轮训事件失败: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * 启动缓存清理任务（定期清理过期的通知记录）
     */
    private fun startCacheCleanup() {
        scope.launch {
            while (isActive) {
                try {
                    delay(7200000)  // 每2小时清理一次
                    cleanupExpiredCache()
                } catch (e: Exception) {
                    logger.error("清理缓存异常: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * 启动待检查仓位的定期检查任务
     * 每30秒检查一次，如果超过3分钟且确实不存在，则标记为已卖出
     */
    private fun startPendingPositionCheckTask() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(30000)  // 每30秒检查一次
                    checkPendingPositions()
                } catch (e: Exception) {
                    logger.error("检查待检查仓位异常: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * 检查待检查的仓位
     * 如果超过3分钟且确实不存在，则标记为已卖出
     * 如果存在，则删除记录
     */
    private suspend fun checkPendingPositions() {
        if (pendingPositionChecks.isEmpty()) {
            return
        }
        // 与 checkPositions 共用互斥，避免两个任务同时改写同一批 tracking
        if (!positionCheckInProgress.compareAndSet(false, true)) {
            logger.debug("跳过本次待检查仓位验证：仓位检查正在进行")
            return
        }
        try {
            // 获取最新的仓位数据
            val result = accountService.getAllPositions()
            if (result.isFailure) {
                logger.warn("获取仓位数据失败，跳过待检查仓位验证: ${result.exceptionOrNull()?.message}")
                return
            }
            
            val positionListResponse = result.getOrNull() ?: return
            val currentPositions = positionListResponse.currentPositions
            val failedAccountIds = positionListResponse.failedAccountIds.toSet()
            
            // 按账户和市场分组当前仓位
            val positionsByAccountAndMarket = currentPositions.groupBy { 
                "${it.accountId}_${it.marketId}_${it.outcomeIndex ?: 0}"
            }
            
            val now = System.currentTimeMillis()
            val checkDelay = 180000L  // 3分钟 = 180000毫秒
            val toRemove = mutableListOf<String>()
            val toMarkAsSold = mutableListOf<PendingPositionCheck>()
            
            // 遍历所有待检查的仓位
            for ((key, pendingCheck) in pendingPositionChecks) {
                // 该账户本轮仓位获取失败：保留记录，下轮再判断
                if (pendingCheck.accountId in failedAccountIds) {
                    continue
                }
                // 重新从数据库读取订单，后续使用最新快照（而不是首次检测时的旧快照）
                val validOrders = pendingCheck.orders.mapNotNull { order ->
                    copyOrderTrackingRepository.findById(order.id!!).orElse(null)?.takeIf { isSellable(it) }
                }
                
                // 如果没有有效订单了，删除记录
                if (validOrders.isEmpty()) {
                    toRemove.add(key)
                    logger.info("待检查仓位的订单已全部处理，删除记录: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, accountId=${pendingCheck.accountId}, copyTradingId=${pendingCheck.copyTradingId}")
                    continue
                }
                
                val positionKey = "${pendingCheck.accountId}_${pendingCheck.marketId}_${pendingCheck.outcomeIndex}"
                val position = positionsByAccountAndMarket[positionKey]?.firstOrNull()
                
                if (position != null) {
                    // 仓位存在，删除记录
                    toRemove.add(key)
                    logger.info("待检查仓位已恢复，删除记录: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, accountId=${pendingCheck.accountId}, copyTradingId=${pendingCheck.copyTradingId}, elapsedTime=${now - pendingCheck.firstDetectedTime}ms")
                } else {
                    // 仓位不存在，检查是否超过3分钟
                    val elapsedTime = now - pendingCheck.firstDetectedTime
                    if (elapsedTime >= checkDelay) {
                        // 超过3分钟且确实不存在，标记为已卖出（使用有效订单）
                        toMarkAsSold.add(pendingCheck.copy(orders = validOrders))
                        toRemove.add(key)
                        logger.info("待检查仓位超过3分钟仍不存在，标记为已卖出: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, accountId=${pendingCheck.accountId}, copyTradingId=${pendingCheck.copyTradingId}, elapsedTime=${elapsedTime}ms, validOrderCount=${validOrders.size}, originalOrderCount=${pendingCheck.orders.size}")
                    } else {
                        // 未超过3分钟，更新订单列表（移除已处理的订单）
                        if (validOrders.size < pendingCheck.orders.size) {
                            pendingPositionChecks[key] = pendingCheck.copy(orders = validOrders)
                            logger.debug("更新待检查仓位记录，移除已处理的订单: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, validOrderCount=${validOrders.size}, originalOrderCount=${pendingCheck.orders.size}")
                        }
                        logger.debug("待检查仓位仍不存在，继续等待: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, accountId=${pendingCheck.accountId}, copyTradingId=${pendingCheck.copyTradingId}, elapsedTime=${elapsedTime}ms, remainingTime=${checkDelay - elapsedTime}ms")
                    }
                }
            }
            
            // 删除已恢复或已处理的记录
            toRemove.forEach { key ->
                pendingPositionChecks.remove(key)
            }
            
            // 标记为已卖出（先用链上 CTF 余额复核：RPC 失败保留记录下轮重试，链上仍有持仓则放弃标记）
            for (pendingCheck in toMarkAsSold) {
                val chainQuantity = getChainPositionQuantity(pendingCheck.accountId, pendingCheck.marketId, pendingCheck.outcomeIndex)
                if (chainQuantity == null) {
                    pendingPositionChecks.putIfAbsent(
                        "${pendingCheck.accountId}_${pendingCheck.marketId}_${pendingCheck.outcomeIndex}_${pendingCheck.copyTradingId}",
                        pendingCheck
                    )
                    continue
                }
                if (chainQuantity >= MIN_SOLD_DIFF) {
                    logger.info("链上仍有持仓，不标记为已卖出: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, accountId=${pendingCheck.accountId}, chainQuantity=$chainQuantity")
                    continue
                }
                try {
                    // 仓位消失的原因：市场已结算 → 视为赎回（含系统外手动赎回），按链上结算价（赢 1 / 输 0 / 50-50 为 0.5）记账；
                    // 未结算 → 视为卖出，按当前价记账；结算状态查询失败 → 无法区分，本轮不记账
                    val conditionResult = blockchainService.getCondition(pendingCheck.marketId)
                    if (conditionResult.isFailure) {
                        logger.warn("查询市场结算状态失败，无法区分赎回/卖出，本轮不记账: marketId=${pendingCheck.marketId}, error=${conditionResult.exceptionOrNull()?.message}")
                        pendingPositionChecks.putIfAbsent(
                            "${pendingCheck.accountId}_${pendingCheck.marketId}_${pendingCheck.outcomeIndex}_${pendingCheck.copyTradingId}",
                            pendingCheck
                        )
                        continue
                    }
                    val (denominator, payouts) = conditionResult.getOrThrow()
                    val resolved = denominator > java.math.BigInteger.ZERO && pendingCheck.outcomeIndex < payouts.size
                    val price = if (resolved) {
                        java.math.BigDecimal(payouts[pendingCheck.outcomeIndex])
                            .divide(java.math.BigDecimal(denominator), 8, java.math.RoundingMode.DOWN)
                    } else {
                        getCurrentMarketPrice(pendingCheck.marketId, pendingCheck.outcomeIndex)
                    }
                    updateOrdersAsSold(
                        pendingCheck.orders,
                        price,
                        pendingCheck.copyTradingId,
                        pendingCheck.marketId,
                        pendingCheck.outcomeIndex,
                        isRedeem = resolved
                    )
                } catch (e: Exception) {
                    logger.error("标记待检查仓位为已卖出失败: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, error=${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("检查待检查仓位异常: ${e.message}", e)
        } finally {
            positionCheckInProgress.set(false)
        }
    }

    /**
     * 链上查询账户代理钱包在某 outcome 上的 CTF 持仓（份数）；任何查询失败返回 null（本轮跳过）
     */
    private suspend fun getChainPositionQuantity(accountId: Long, marketId: String, outcomeIndex: Int): BigDecimal? {
        val account = accountRepository.findById(accountId).orElse(null) ?: return null
        val tokenId = blockchainService.getTokenId(marketId, outcomeIndex).getOrElse {
            logger.warn("推导 tokenId 失败，跳过链上复核: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${it.message}")
            return null
        }
        return blockchainService.getCtfBalance(account.proxyAddress, tokenId).getOrElse {
            logger.warn("查询链上持仓失败，跳过本轮: accountId=$accountId, marketId=$marketId, error=${it.message}")
            null
        }
    }
    
    /**
     * 清理过期的缓存条目（超过2小时的记录）
     */
    private fun cleanupExpiredCache() {
        val now = System.currentTimeMillis()
        val expireTime = 7200000  // 2小时
        
        // 清理过期的仓位通知记录
        val expiredPositions = notifiedRedeemablePositions.entries.filter { (_, timestamp) ->
            (now - timestamp) > expireTime
        }
        expiredPositions.forEach { (key, _) ->
            notifiedRedeemablePositions.remove(key)
        }
        
        // 清理过期的已处理赎回仓位记录
        val expiredProcessed = processedRedeemablePositions.entries.filter { (_, timestamp) ->
            (now - timestamp) > expireTime
        }
        expiredProcessed.forEach { (key, _) ->
            processedRedeemablePositions.remove(key)
        }
        
        // 清理过期的配置通知记录
        val expiredConfigs = notifiedConfigs.entries.filter { (_, timestamp) ->
            (now - timestamp) > expireTime
        }
        expiredConfigs.forEach { (key, _) ->
            notifiedConfigs.remove(key)
        }
        
        // 清理过期的待检查仓位记录（超过1小时的记录，正常情况下应该在3分钟内处理完）
        val expiredPendingChecks = pendingPositionChecks.entries.filter { (_, check) ->
            (now - check.firstDetectedTime) > 3600000  // 1小时
        }
        expiredPendingChecks.forEach { (key, _) ->
            pendingPositionChecks.remove(key)
        }
        
        if (expiredPositions.isNotEmpty() || expiredProcessed.isNotEmpty() || expiredConfigs.isNotEmpty() || expiredPendingChecks.isNotEmpty()) {
            logger.debug("清理过期缓存: positions=${expiredPositions.size}, processed=${expiredProcessed.size}, configs=${expiredConfigs.size}, pendingChecks=${expiredPendingChecks.size}")
        }
    }
    
    /**
     * 检查仓位（主入口）
     * 根据 positionloop.md 文档要求：
     * 1. 处理待赎回仓位
     * 2. 处理未卖出订单
     */
    suspend fun checkPositions(
        currentPositions: List<AccountPositionDto>,
        failedAccountIds: Set<Long> = emptySet()
    ) {
        if (!positionCheckInProgress.compareAndSet(false, true)) {
            logger.debug("跳过本次仓位检查：上一轮检查尚未完成")
            return
        }
        try {
            // 逻辑1：处理待赎回仓位
            val redeemablePositions = currentPositions.filter { it.redeemable }
            if (redeemablePositions.isNotEmpty()) {
                checkRedeemablePositions(redeemablePositions)
            }
            
            // 逻辑2：处理未卖出订单（仓位获取失败的账户跳过，不能把“拉不到”当成“已卖出”）
            checkUnmatchedOrders(currentPositions, failedAccountIds)
        } catch (e: Exception) {
            logger.error("仓位检查异常: ${e.message}", e)
        } finally {
            positionCheckInProgress.set(false)
        }
    }
    
    /**
     * 逻辑1：处理待赎回仓位
     * 按照以下逻辑处理：
     * 1. 无待赎回仓位：跳过
     * 2. (未配置apikey || autoredeem==false) && 有待赎回的仓位：发送通知事件
     * 3. (已配置) && 有待赎回的仓位：处理订单逻辑
     * 防重入：上一轮检查未完成时，本轮直接跳过，避免并发赎回。
     */
    private suspend fun checkRedeemablePositions(redeemablePositions: List<AccountPositionDto>) {
        if (!redeemCheckInProgress.compareAndSet(false, true)) {
            logger.debug("跳过本次待赎回仓位检查：上一次检查尚未完成")
            return
        }
        try {
            // 1. 无待赎回仓位：跳过
            if (redeemablePositions.isEmpty()) {
                return
            }

            // 检查系统级别的自动赎回配置
            val autoRedeemEnabled = systemConfigService.isAutoRedeemEnabled()
            val apiKeyConfigured = relayClientService.isBuilderApiKeyConfigured()
            
            // 2. (未配置apikey || autoredeem==false) && 有待赎回的仓位：发送通知事件
            if (!autoRedeemEnabled || !apiKeyConfigured) {
                // 按账户分组发送通知
                val positionsByAccount = redeemablePositions.groupBy { it.accountId }
                
                for ((accountId, positions) in positionsByAccount) {
                    for (position in positions) {
                        val positionKey = "${accountId}_${position.marketId}_${position.outcomeIndex ?: 0}"
                        // 检查是否在最近2小时内已发送过提示（避免频繁推送）
                        val lastNotification = notifiedRedeemablePositions[positionKey]
                        val now = System.currentTimeMillis()
                        if (lastNotification == null || (now - lastNotification) >= 7200000) {  // 2小时
                            if (!autoRedeemEnabled) {
                                // 自动赎回未开启：直接发送通知，不需要查找跟单配置
                                checkAndNotifyAutoRedeemDisabled(accountId, listOf(position))
                            } else {
                                // API Key 未配置：需要查找跟单配置来发送通知
                                val copyTradings = copyTradingRepository.findByAccountId(accountId)
                                    .filter { it.enabled }
                                for (copyTrading in copyTradings) {
                                    checkAndNotifyBuilderApiKeyNotConfigured(copyTrading, listOf(position))
                                }
                            }
                            notifiedRedeemablePositions[positionKey] = now
                        }
                    }
                }
                return  // 未配置时直接返回，不进行后续处理
            }

            // Builder Relayer 配额冷却期内不再发起赎回（如 API 返回 quota exceeded, resets in N seconds）
            if (relayClientService.isBuilderRelayerQuotaBlocked()) {
                val remaining = relayClientService.getBuilderRelayerQuotaBlockedRemainingSeconds()
                logger.info("Builder Relayer 配额冷却中，跳过本次自动赎回，约 ${remaining} 秒后恢复")
                return
            }

            // 3. (已配置) && 有待赎回的仓位：处理订单逻辑
            // 自动赎回已开启且已配置 API Key，按账户分组进行赎回处理
            // 先执行赎回，赎回成功后再查找订单并更新订单状态
            val positionsByAccount = redeemablePositions.groupBy { it.accountId }
            
            for ((accountId, positions) in positionsByAccount) {
                // 查找该账户下所有跟单配置（含停用：停用只表示不再跟新单，赎回后的记账仍需完成；此处不会下单）
                val copyTradings = copyTradingRepository.findByAccountId(accountId)
                
                // 过滤掉已处理 / 处于失败退避期的仓位（避免重复赎回）
                val now = System.currentTimeMillis()
                val positionsToRedeem = positions.filter { position ->
                    val positionKey = "${accountId}_${position.marketId}_${position.outcomeIndex ?: 0}"
                    val lastProcessed = processedRedeemablePositions[positionKey]
                    val retryAt = redeemRetryAt(positionKey)
                    if (lastProcessed != null && (now - lastProcessed) < 1800000) {  // 成功后 30 分钟内不再处理
                        logger.debug("跳过已处理的赎回仓位: $positionKey (上次处理时间: ${lastProcessed})")
                        false
                    } else if (retryAt != null && now < retryAt) {
                        logger.debug("赎回失败退避中，跳过: $positionKey, 下次重试时间: $retryAt")
                        false
                    } else {
                        true
                    }
                }
                
                if (positionsToRedeem.isEmpty()) {
                    logger.debug("所有仓位都已处理过，跳过赎回: accountId=$accountId")
                    continue
                }
                
                // 先执行赎回（不查找订单）
                val redeemRequest = com.wrbug.polymarketbot.dto.PositionRedeemRequest(
                    positions = positionsToRedeem.map { position ->
                        com.wrbug.polymarketbot.dto.AccountRedeemPositionItem(
                            accountId = accountId,
                            marketId = position.marketId,
                            outcomeIndex = position.outcomeIndex ?: 0,
                            side = position.side
                        )
                    }
                )
                
                val redeemResult = accountService.redeemPositions(redeemRequest)
                redeemResult.fold(
                    onSuccess = { response ->
                        logger.info("自动赎回成功: accountId=$accountId, redeemedCount=${positionsToRedeem.size}, totalValue=${response.totalRedeemedValue}")
                        
                        // 记录已处理的仓位（避免重复赎回），清除失败退避
                        for (position in positionsToRedeem) {
                            val positionKey = "${accountId}_${position.marketId}_${position.outcomeIndex ?: 0}"
                            processedRedeemablePositions[positionKey] = now
                            redeemFailures.remove(positionKey)
                        }
                        
                        // 赎回成功后，按每个跟单配置分别查找未卖出订单并更新状态
                        // 同一账户同一市场可能同时跟多个 Leader，需按 copyTradingId 分别生成自动卖出记录（如 leader1 对应 20 share，leader2 对应 16 share）
                        for (position in positionsToRedeem) {
                            if (position.outcomeIndex == null) {
                                continue
                            }
                            for (copyTrading in copyTradings) {
                                val orders = copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndex(
                                    copyTrading.id!!,
                                    position.marketId,
                                    position.outcomeIndex
                                )
                                if (orders.isNotEmpty()) {
                                    updateOrdersAsSoldAfterRedeem(orders.filter { isSellable(it) }, position, copyTrading.id!!)
                                }
                            }
                        }
                    },
                    onFailure = { e ->
                        // 失败（含结果未知）进入指数退避，避免每轮重复提交；payout=0 等异常同样告警
                        for (position in positionsToRedeem) {
                            recordRedeemFailure("${accountId}_${position.marketId}_${position.outcomeIndex ?: 0}", e)
                        }
                        logger.error("自动赎回失败: accountId=$accountId, error=${e.message}", e)
                    }
                )
            }
        } catch (e: Exception) {
            logger.error("处理待赎回仓位异常: ${e.message}", e)
        } finally {
            redeemCheckInProgress.set(false)
        }
    }

    /**
     * 逻辑2：处理未卖出订单
     * 检查所有未卖出的订单，匹配仓位
     * 如果仓位不存在，则更新订单状态为已卖出，卖出价为当前最新价
     * 如果发现有仓位，并且仓位数量小于所有未卖出订单数量总和，则按照订单下单顺序更新状态，卖出价价格为最新价
     */
    private suspend fun checkUnmatchedOrders(currentPositions: List<AccountPositionDto>, failedAccountIds: Set<Long>) {
        try {
            // 记账覆盖停用配置（停用只表示不再跟新单）；本方法只记账，不会下卖单
            val allCopyTradings = copyTradingRepository.findAll()
            
            // 按账户和市场分组当前仓位
            val positionsByAccountAndMarket = currentPositions.groupBy { 
                "${it.accountId}_${it.marketId}_${it.outcomeIndex ?: 0}"
            }
            
            // 遍历所有跟单配置
            for (copyTrading in allCopyTradings) {
                // 仓位获取失败的账户本轮跳过，避免把“看不到的仓位”当成已卖出
                if (copyTrading.accountId in failedAccountIds) {
                    logger.debug("账户仓位获取失败，跳过持仓核对: accountId=${copyTrading.accountId}, copyTradingId=${copyTrading.id}")
                    continue
                }
                // 查找该跟单配置下所有未卖出的订单（remaining_quantity > 0，排除 pending/unconfirmed）
                val unmatchedOrders = copyOrderTrackingRepository.findByCopyTradingId(copyTrading.id!!)
                    .filter { isSellable(it) }
                    .sortedBy { it.createdAt }  // 按创建时间排序（FIFO）
                
                if (unmatchedOrders.isEmpty()) {
                    continue
                }
                
                // 按市场分组订单
                val ordersByMarket = unmatchedOrders.groupBy { 
                    "${it.marketId}_${it.outcomeIndex ?: 0}"
                }
                
                for ((marketKey, orders) in ordersByMarket) {
                    // 从订单中获取市场信息
                    val firstOrder = orders.firstOrNull() ?: continue
                    val marketId = firstOrder.marketId
                    val outcomeIndex = firstOrder.outcomeIndex ?: 0
                    
                    // 查找对应的仓位
                    val positionKey = "${copyTrading.accountId}_$marketKey"
                    val position = positionsByAccountAndMarket[positionKey]?.firstOrNull()
                    
                    if (position == null) {
                        // 仓位不存在，使用延迟检测机制
                        // 先查询创建时间超过2分钟的未匹配订单（SQL层过滤，避免刚创建的订单被误判）
                        val now = System.currentTimeMillis()
                        val thresholdTime = now - 120000  // 2分钟 = 120000毫秒

                        val ordersToCheck = copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndexOlderThan(
                            copyTradingId = copyTrading.id!!,
                            marketId = marketId,
                            outcomeIndex = outcomeIndex,
                            thresholdTime = thresholdTime
                        ).filter { isSellable(it) }

                        if (ordersToCheck.isNotEmpty()) {
                            // 有订单创建时间超过2分钟，记录到待检查列表
                            val checkKey = "${copyTrading.accountId}_${marketId}_${outcomeIndex}_${copyTrading.id}"

                            // 如果已经存在记录，更新订单列表（可能订单状态有变化）
                            val existingCheck = pendingPositionChecks[checkKey]
                            if (existingCheck == null) {
                                // 首次检测到，记录
                                pendingPositionChecks[checkKey] = PendingPositionCheck(
                                    accountId = copyTrading.accountId,
                                    marketId = marketId,
                                    outcomeIndex = outcomeIndex,
                                    copyTradingId = copyTrading.id!!,
                                    orders = ordersToCheck,
                                    firstDetectedTime = now
                                )
                                logger.info("首次检测到仓位不存在，记录待检查: marketId=$marketId, outcomeIndex=$outcomeIndex, accountId=${copyTrading.accountId}, copyTradingId=${copyTrading.id}, orderCount=${ordersToCheck.size}, positionKey=$positionKey")
                            } else {
                                // 已存在记录，更新订单列表（可能订单状态有变化）
                                pendingPositionChecks[checkKey] = existingCheck.copy(orders = ordersToCheck)
                                logger.debug("更新待检查仓位记录: marketId=$marketId, outcomeIndex=$outcomeIndex, accountId=${copyTrading.accountId}, copyTradingId=${copyTrading.id}, orderCount=${ordersToCheck.size}, elapsedTime=${now - existingCheck.firstDetectedTime}ms")
                            }
                        } else {
                            // 订单创建时间不足2分钟，可能是刚创建的订单，暂时不处理
                            logger.debug("仓位不存在但无符合条件的订单（创建时间不足2分钟），暂不标记为已卖出: marketId=$marketId, outcomeIndex=$outcomeIndex, orderCount=${orders.size}, thresholdTime=$thresholdTime, positionKey=$positionKey")
                        }
                    } else {
                        // 有仓位，先检查是否有对应的待检查记录，如果有则删除（仓位已恢复）
                        val checkKey = "${copyTrading.accountId}_${marketId}_${outcomeIndex}_${copyTrading.id}"
                        val pendingCheck = pendingPositionChecks.remove(checkKey)
                        if (pendingCheck != null) {
                            logger.info("待检查仓位已恢复，删除待检查记录: marketId=$marketId, outcomeIndex=$outcomeIndex, accountId=${copyTrading.accountId}, copyTradingId=${copyTrading.id}, elapsedTime=${System.currentTimeMillis() - pendingCheck.firstDetectedTime}ms")
                        }
                        
                        // 有仓位，按订单下单顺序（FIFO）更新状态
                        // 先查询创建时间超过2分钟的未匹配订单（SQL层过滤，避免刚创建的订单被误判）
                        val now = System.currentTimeMillis()
                        val thresholdTime = now - 120000  // 2分钟 = 120000毫秒
                        
                        val validOrders = copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndexOlderThan(
                            copyTradingId = copyTrading.id!!,
                            marketId = marketId,
                            outcomeIndex = outcomeIndex,
                            thresholdTime = thresholdTime
                        ).filter { isSellable(it) }

                        // 如果没有符合条件的订单，跳过处理
                        if (validOrders.isEmpty()) {
                            logger.debug("仓位存在但无符合条件的订单（创建时间不足2分钟），暂不进行FIFO匹配: marketId=$marketId, outcomeIndex=$outcomeIndex, thresholdTime=$thresholdTime")
                            continue
                        }

                        handleShrunkPosition(checkKey, copyTrading.id!!, marketId, outcomeIndex, position, validOrders)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("处理未卖出订单异常: ${e.message}", e)
        }
    }
    
    /**
     * 获取当前市场最新价（用于更新订单卖出价）
     * 委托给 MarketPriceService 处理
     */
    private suspend fun getCurrentMarketPrice(marketId: String, outcomeIndex: Int): BigDecimal {
        return marketPriceService.getCurrentMarketPrice(marketId, outcomeIndex)
    }

    /** 可参与持仓核对的跟单记录：有剩余数量，且不是 pending / unconfirmed（这两种状态数量为 0、不可卖） */
    internal fun isSellable(order: CopyOrderTracking): Boolean =
        order.status !in NON_SELLABLE_STATUSES && order.remainingQuantity > BigDecimal.ZERO

    /**
     * 获取市场 taker 费率；获取失败或为 0 时在日志中标注（不静默按 0 处理）
     */
    private fun resolveFeeRate(marketId: String): BigDecimal? {
        return try {
            val rate = marketService.getTakerFeeRate(marketId)
            if (rate <= BigDecimal.ZERO) {
                logger.info("市场 taker 费率为 0 或未知，盈亏未扣手续费: marketId=$marketId")
            }
            rate
        } catch (e: Exception) {
            logger.warn("获取市场 taker 费率失败，盈亏未扣手续费: marketId=$marketId, error=${e.message}")
            null
        }
    }

    /**
     * 已实现盈亏：卖出（含自动识别的外部卖出）扣买入+卖出两次 taker 费；赎回只扣买入 taker 费
     */
    internal fun realizedPnl(
        buyPrice: BigDecimal,
        sellPrice: BigDecimal,
        quantity: BigDecimal,
        feeRate: BigDecimal?,
        isRedeem: Boolean
    ): BigDecimal {
        return if (isRedeem) {
            sellPrice.subtract(buyPrice).multi(quantity)
                .subtract(PolymarketTradingFee.takerFee(quantity, buyPrice, feeRate))
                .setScale(8, java.math.RoundingMode.HALF_UP)
        } else {
            PolymarketTradingFee.netRealizedPnl(buyPrice, sellPrice, quantity, feeRate)
        }
    }

    /**
     * 持仓存在但少于 tracking 剩余总量：需连续多轮确认，并用链上 CTF balanceOf 复核后才按 FIFO 记为已卖出。
     * 使用全精度数量（originalQuantity），差额小于 [MIN_SOLD_DIFF] 视为精度误差不处理。
     */
    private suspend fun handleShrunkPosition(
        checkKey: String,
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int,
        position: AccountPositionDto,
        validOrders: List<CopyOrderTracking>
    ) {
        val totalOrderQuantity = validOrders.fold(BigDecimal.ZERO) { sum, order -> sum.add(order.remainingQuantity) }
        val apiQuantity = (position.originalQuantity ?: position.quantity).toSafeBigDecimal()
        val apiSold = totalOrderQuantity.subtract(apiQuantity)
        if (apiSold < MIN_SOLD_DIFF) {
            shrinkConfirmations.remove(checkKey)
            return
        }
        val count = shrinkConfirmations.merge(checkKey, 1, Int::plus) ?: 1
        if (count < SHRINK_CONFIRMATIONS) {
            logger.debug("持仓少于跟单剩余量，等待确认: key=$checkKey, apiSold=$apiSold, count=$count/$SHRINK_CONFIRMATIONS")
            return
        }
        // 链上复核（RPC 失败则跳过本轮，保留确认计数）
        val tokenId = position.tokenId
        if (tokenId.isNullOrBlank()) {
            logger.warn("仓位缺少 tokenId，无法链上复核，跳过: key=$checkKey")
            return
        }
        val chainQuantity = blockchainService.getCtfBalance(position.proxyAddress, tokenId).getOrElse {
            logger.warn("链上复核持仓失败，跳过本轮: key=$checkKey, error=${it.message}")
            return
        }
        val chainSold = totalOrderQuantity.subtract(chainQuantity)
        shrinkConfirmations.remove(checkKey)
        if (chainSold < MIN_SOLD_DIFF) {
            logger.info("链上持仓与跟单剩余量一致，不标记卖出: key=$checkKey, chain=$chainQuantity, total=$totalOrderQuantity")
            return
        }
        val soldQuantity = minOf(apiSold, chainSold)
        try {
            val currentPrice = getCurrentMarketPrice(marketId, outcomeIndex)
            updateOrdersAsSoldByFIFO(validOrders, soldQuantity, currentPrice, copyTradingId, marketId, outcomeIndex)
        } catch (e: Exception) {
            logger.warn("无法获取市场价格，跳过FIFO匹配: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}")
        }
    }
    
    
    /**
     * 在仓位赎回成功后，更新订单状态为已卖出
     * 使用卖出逻辑更新所有订单状态（未卖出订单的）
     */
    private suspend fun updateOrdersAsSoldAfterRedeem(
        orders: List<CopyOrderTracking>,
        position: AccountPositionDto,
        copyTradingId: Long
    ) {
        try {
            // 赎回记账只用链上结算价（numerator / denominator）；查询失败或未结算时本次不记账，
            // 仓位消失后会由待检查流程按链上结算结果补记
            val outcomeIndex = position.outcomeIndex ?: 0
            val (denominator, payouts) = blockchainService.getCondition(position.marketId).getOrElse {
                logger.warn("赎回后查询结算结果失败，暂不记账: marketId=${position.marketId}, error=${it.message}")
                return
            }
            if (denominator <= java.math.BigInteger.ZERO || outcomeIndex >= payouts.size) {
                logger.warn("赎回后市场结算结果不完整，暂不记账: marketId=${position.marketId}")
                return
            }
            val settlementPrice = java.math.BigDecimal(payouts[outcomeIndex])
                .divide(java.math.BigDecimal(denominator), 8, java.math.RoundingMode.DOWN)
            updateOrdersAsSold(orders, settlementPrice, copyTradingId, position.marketId, outcomeIndex, isRedeem = true)
        } catch (e: Exception) {
            logger.error("更新订单状态为已卖出失败: ${e.message}", e)
        }
    }
    
    /**
     * 更新订单状态为已卖出（使用当前最新价）
     * 同时创建卖出记录和匹配明细，用于统计
     */
    private suspend fun updateOrdersAsSold(
        orders: List<CopyOrderTracking>,
        sellPrice: BigDecimal,
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int,
        isRedeem: Boolean = false
    ) {
        if (orders.isEmpty()) {
            return
        }
        
        try {
            // 计算总匹配数量和总盈亏
            var totalMatchedQuantity = BigDecimal.ZERO
            var totalRealizedPnl = BigDecimal.ZERO
            val matchDetails = mutableListOf<SellMatchDetail>()
            val feeRate = resolveFeeRate(marketId)
            
            for (staleOrder in orders) {
                // 保存前重新读取实体，基于最新快照计算，避免覆盖其他流程的更新
                val order = copyOrderTrackingRepository.findById(staleOrder.id!!).orElse(null) ?: continue
                if (!isSellable(order)) {
                    continue
                }
                val remainingQty = order.remainingQuantity.toSafeBigDecimal()
                
                // 计算盈亏（扣除手续费，口径与跟卖一致；赎回无卖出手续费）
                val buyPrice = order.price.toSafeBigDecimal()
                val realizedPnl = realizedPnl(buyPrice, sellPrice, remainingQty, feeRate, isRedeem)
                
                // 创建匹配明细（稍后保存）
                val detail = SellMatchDetail(
                    matchRecordId = 0,  // 稍后设置
                    trackingId = order.id!!,
                    buyOrderId = order.buyOrderId,
                    matchedQuantity = remainingQty,
                    buyPrice = buyPrice,
                    sellPrice = sellPrice,
                    realizedPnl = realizedPnl
                )
                matchDetails.add(detail)
                
                totalMatchedQuantity = totalMatchedQuantity.add(remainingQty)
                totalRealizedPnl = totalRealizedPnl.add(realizedPnl)
                
                // 更新订单状态：将剩余数量标记为已匹配
                order.matchedQuantity = order.matchedQuantity.add(remainingQty)
                order.remainingQuantity = BigDecimal.ZERO
                order.status = "fully_matched"
                order.updatedAt = System.currentTimeMillis()
                copyOrderTrackingRepository.save(order)
            }
            
            // 如果有匹配的订单，创建卖出记录
            if (totalMatchedQuantity > BigDecimal.ZERO && matchDetails.isNotEmpty()) {
                val timestamp = System.currentTimeMillis()
                val sellOrderId = "AUTO_${timestamp}_${copyTradingId}"
                val leaderSellTradeId = "AUTO_${timestamp}"
                
                val matchRecord = SellMatchRecord(
                    copyTradingId = copyTradingId,
                    sellOrderId = sellOrderId,
                    leaderSellTradeId = leaderSellTradeId,
                    marketId = marketId,
                    side = outcomeIndex.toString(),  // 使用outcomeIndex作为side
                    outcomeIndex = outcomeIndex,
                    totalMatchedQuantity = totalMatchedQuantity,
                    sellPrice = sellPrice,
                    totalRealizedPnl = totalRealizedPnl,
                    priceUpdated = true  // 自动生成的订单，直接标记为已处理，不发送通知
                )
                
                val savedRecord = sellMatchRecordRepository.save(matchRecord)
                
                // 保存匹配明细
                for (detail in matchDetails) {
                    val savedDetail = detail.copy(matchRecordId = savedRecord.id!!)
                    sellMatchDetailRepository.save(savedDetail)
                }
                
                logger.info("创建自动卖出记录: copyTradingId=$copyTradingId, marketId=$marketId, totalMatched=$totalMatchedQuantity, totalPnl=$totalRealizedPnl")
            }
        } catch (e: Exception) {
            logger.error("更新订单状态为已卖出异常: ${e.message}", e)
        }
    }
    
    /**
     * 按 FIFO 顺序更新订单状态为已卖出
     * @param orders 订单列表（已按创建时间排序，FIFO）
     * @param soldQuantity 已成交数量（总订单数量 - 仓位数量）
     * @param sellPrice 卖出价格
     * @param copyTradingId 跟单配置ID
     * @param marketId 市场ID
     * @param outcomeIndex 结果索引
     * 
     * 逻辑说明：
     * 1. 按订单创建时间顺序（FIFO）处理
     * 2. 如果订单剩余数量 <= 已成交数量，订单完全成交
     * 3. 如果订单剩余数量 > 已成交数量，订单部分成交
     * 4. 同时创建卖出记录和匹配明细，用于统计
     */
    private suspend fun updateOrdersAsSoldByFIFO(
        orders: List<CopyOrderTracking>,
        soldQuantity: BigDecimal,
        sellPrice: BigDecimal,
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int
    ) {
        if (orders.isEmpty()) {
            return
        }
        
        try {
            // 订单已经按 createdAt ASC 排序（FIFO）
            var remaining = soldQuantity
            var totalMatchedQuantity = BigDecimal.ZERO
            var totalRealizedPnl = BigDecimal.ZERO
            val matchDetails = mutableListOf<SellMatchDetail>()
            val feeRate = resolveFeeRate(marketId)
            
            for (staleOrder in orders) {
                if (remaining <= BigDecimal.ZERO) {
                    break
                }
                // 保存前重新读取实体，基于最新快照计算
                val order = copyOrderTrackingRepository.findById(staleOrder.id!!).orElse(null) ?: continue
                if (!isSellable(order)) {
                    continue
                }
                
                val orderRemaining = order.remainingQuantity.toSafeBigDecimal()
                val toMatch = minOf(orderRemaining, remaining)
                
                if (toMatch > BigDecimal.ZERO) {
                    // 计算盈亏（扣除买入与卖出 taker 手续费，口径与跟卖一致）
                    val buyPrice = order.price.toSafeBigDecimal()
                    val realizedPnl = realizedPnl(buyPrice, sellPrice, toMatch, feeRate, isRedeem = false)
                    
                    // 创建匹配明细（稍后保存）
                    val detail = SellMatchDetail(
                        matchRecordId = 0,  // 稍后设置
                        trackingId = order.id!!,
                        buyOrderId = order.buyOrderId,
                        matchedQuantity = toMatch,
                        buyPrice = buyPrice,
                        sellPrice = sellPrice,
                        realizedPnl = realizedPnl
                    )
                    matchDetails.add(detail)
                    
                    totalMatchedQuantity = totalMatchedQuantity.add(toMatch)
                    totalRealizedPnl = totalRealizedPnl.add(realizedPnl)
                    
                    order.matchedQuantity = order.matchedQuantity.add(toMatch)
                    order.remainingQuantity = order.remainingQuantity.subtract(toMatch)
                    
                    // 更新状态
                    if (order.remainingQuantity <= BigDecimal.ZERO) {
                        order.status = "fully_matched"
                    } else {
                        order.status = "partially_matched"
                    }
                    
                    order.updatedAt = System.currentTimeMillis()
                    copyOrderTrackingRepository.save(order)
                    
                    remaining = remaining.subtract(toMatch)
                    
                    logger.info("按 FIFO 更新订单状态: orderId=${order.buyOrderId}, matched=$toMatch, remaining=${order.remainingQuantity}")
                }
            }
            
            // 如果有匹配的订单，创建卖出记录
            if (totalMatchedQuantity > BigDecimal.ZERO && matchDetails.isNotEmpty()) {
                val timestamp = System.currentTimeMillis()
                val sellOrderId = "AUTO_FIFO_${timestamp}_${copyTradingId}"
                val leaderSellTradeId = "AUTO_FIFO_${timestamp}"
                
                val matchRecord = SellMatchRecord(
                    copyTradingId = copyTradingId,
                    sellOrderId = sellOrderId,
                    leaderSellTradeId = leaderSellTradeId,
                    marketId = marketId,
                    side = outcomeIndex.toString(),  // 使用outcomeIndex作为side
                    outcomeIndex = outcomeIndex,
                    totalMatchedQuantity = totalMatchedQuantity,
                    sellPrice = sellPrice,
                    totalRealizedPnl = totalRealizedPnl,
                    priceUpdated = true  // 自动生成的订单，直接标记为已处理，不发送通知
                )
                
                val savedRecord = sellMatchRecordRepository.save(matchRecord)
                
                // 保存匹配明细
                for (detail in matchDetails) {
                    val savedDetail = detail.copy(matchRecordId = savedRecord.id!!)
                    sellMatchDetailRepository.save(savedDetail)
                }
                
                logger.info("创建FIFO自动卖出记录: copyTradingId=$copyTradingId, marketId=$marketId, totalMatched=$totalMatchedQuantity, totalPnl=$totalRealizedPnl")
            }
        } catch (e: Exception) {
            logger.error("按 FIFO 更新订单状态异常: ${e.message}", e)
        }
    }
    
    /**
     * 检查并通知自动赎回未开启
     */
    private suspend fun checkAndNotifyAutoRedeemDisabled(accountId: Long, positions: List<AccountPositionDto>) {
        if (telegramNotificationService == null) {
            return
        }
        
        // 检查是否在最近2小时内已发送过提示（避免频繁推送）
        val lastNotification = notifiedConfigs[accountId]
        val now = System.currentTimeMillis()
        if (lastNotification != null && (now - lastNotification) < 7200000) {  // 2小时
            return
        }
        
        try {
            val account = accountRepository.findById(accountId).orElse(null)
            if (account == null) {
                return
            }
            
            // 计算可赎回总价值
            val totalValue = positions.fold(BigDecimal.ZERO) { sum, pos ->
                sum.add(pos.quantity.toSafeBigDecimal())
            }
            
            val message = buildAutoRedeemDisabledMessage(
                accountName = account.accountName,
                walletAddress = account.walletAddress,
                totalValue = totalValue.toPlainString(),
                positionCount = positions.size
            )
            
            telegramNotificationService.sendMessage(message)
            notifiedConfigs[accountId] = now
        } catch (e: Exception) {
            logger.error("发送自动赎回未开启提示失败: accountId=$accountId, ${e.message}", e)
        }
    }
    
    /**
     * 检查并通知 Builder API Key 未配置
     */
    private suspend fun checkAndNotifyBuilderApiKeyNotConfigured(
        copyTrading: CopyTrading,
        positions: List<AccountPositionDto>
    ) {
        if (telegramNotificationService == null) {
            return
        }
        
        // 检查是否在最近2小时内已发送过提示（避免频繁推送）
        val copyTradingId = copyTrading.id ?: return
        val lastNotification = notifiedConfigs[copyTradingId]
        val now = System.currentTimeMillis()
        if (lastNotification != null && (now - lastNotification) < 7200000) {  // 2小时
            return
        }
        
        try {
            val account = accountRepository.findById(copyTrading.accountId).orElse(null)
            if (account == null) {
                return
            }
            
            // 计算可赎回总价值
            val totalValue = positions.fold(BigDecimal.ZERO) { sum, pos ->
                sum.add(pos.quantity.toSafeBigDecimal())
            }
            
            val message = buildBuilderApiKeyNotConfiguredMessage(
                accountName = account.accountName,
                walletAddress = account.walletAddress,
                configName = copyTrading.configName,
                totalValue = totalValue.toPlainString(),
                positionCount = positions.size
            )
            
            telegramNotificationService.sendMessage(message)
            notifiedConfigs[copyTradingId] = now
        } catch (e: Exception) {
            logger.error("发送 Builder API Key 未配置提示失败: copyTradingId=$copyTradingId, ${e.message}", e)
        }
    }
    
    /**
     * 构建自动赎回未开启消息
     */
    private fun buildAutoRedeemDisabledMessage(
        accountName: String?,
        walletAddress: String?,
        totalValue: String,
        positionCount: Int
    ): String {
        // 获取当前语言设置
        val locale = try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            java.util.Locale("zh", "CN")
        }
        
        val accountInfo = accountName ?: (walletAddress?.let { maskAddress(it) } ?: messageSource.getMessage("common.unknown", null, "未知", locale))
        val totalValueDisplay = try {
            val totalValueDecimal = totalValue.toSafeBigDecimal()
            val formatted = if (totalValueDecimal.scale() > 4) {
                totalValueDecimal.setScale(4, java.math.RoundingMode.DOWN).toPlainString()
            } else {
                totalValueDecimal.stripTrailingZeros().toPlainString()
            }
            formatted
        } catch (e: Exception) {
            totalValue
        }
        
        // 获取多语言文本
        val title = messageSource.getMessage("notification.auto_redeem.disabled.title", null, "自动赎回未开启", locale)
        val accountLabel = messageSource.getMessage("notification.auto_redeem.disabled.account", null, "账户", locale)
        val positionsLabel = messageSource.getMessage("notification.auto_redeem.disabled.redeemable_positions", null, "可赎回仓位", locale)
        val positionsUnit = messageSource.getMessage("notification.auto_redeem.disabled.positions_unit", null, "个", locale)
        val totalValueLabel = messageSource.getMessage("notification.auto_redeem.disabled.total_value", null, "总价值", locale)
        val message = messageSource.getMessage("notification.auto_redeem.disabled.message", null, "请在系统设置中开启自动赎回功能。", locale)
        
        return "⚠️ $title\n\n" +
                "$accountLabel: $accountInfo\n" +
                "$positionsLabel: $positionCount $positionsUnit\n" +
                "$totalValueLabel: $totalValueDisplay USDC\n\n" +
                message
    }
    
    /**
     * 构建 Builder API Key 未配置消息
     */
    private fun buildBuilderApiKeyNotConfiguredMessage(
        accountName: String?,
        walletAddress: String?,
        configName: String?,
        totalValue: String,
        positionCount: Int
    ): String {
        // 获取当前语言设置
        val locale = try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            java.util.Locale("zh", "CN")
        }
        
        val accountInfo = accountName ?: (walletAddress?.let { maskAddress(it) } ?: messageSource.getMessage("common.unknown", null, "未知", locale))
        val unknownConfig = messageSource.getMessage("notification.builder_api_key.not_configured.unknown_config", null, "未命名配置", locale)
        val configInfo = configName ?: unknownConfig
        val totalValueDisplay = try {
            val totalValueDecimal = totalValue.toSafeBigDecimal()
            val formatted = if (totalValueDecimal.scale() > 4) {
                totalValueDecimal.setScale(4, java.math.RoundingMode.DOWN).toPlainString()
            } else {
                totalValueDecimal.stripTrailingZeros().toPlainString()
            }
            formatted
        } catch (e: Exception) {
            totalValue
        }
        
        // 获取多语言文本
        val title = messageSource.getMessage("notification.builder_api_key.not_configured.title", null, "Builder API Key 未配置", locale)
        val accountLabel = messageSource.getMessage("notification.builder_api_key.not_configured.account", null, "账户", locale)
        val configLabel = messageSource.getMessage("notification.builder_api_key.not_configured.copy_trading_config", null, "跟单配置", locale)
        val positionsLabel = messageSource.getMessage("notification.builder_api_key.not_configured.redeemable_positions", null, "可赎回仓位", locale)
        val positionsUnit = messageSource.getMessage("notification.builder_api_key.not_configured.positions_unit", null, "个", locale)
        val totalValueLabel = messageSource.getMessage("notification.builder_api_key.not_configured.total_value", null, "总价值", locale)
        val message = messageSource.getMessage("notification.builder_api_key.not_configured.message", null, "请在系统设置中配置 Builder API Key 以启用自动赎回功能。", locale)
        
        return "⚠️ $title\n\n" +
                "$accountLabel: $accountInfo\n" +
                "$configLabel: $configInfo\n" +
                "$positionsLabel: $positionCount $positionsUnit\n" +
                "$totalValueLabel: $totalValueDisplay USDC\n\n" +
                message
    }
    
    /**
     * 掩码地址（只显示前6位和后4位）
     */
    private fun maskAddress(address: String): String {
        if (address.length <= 10) {
            return address
        }
        return "${address.take(6)}...${address.takeLast(4)}"
    }
}

