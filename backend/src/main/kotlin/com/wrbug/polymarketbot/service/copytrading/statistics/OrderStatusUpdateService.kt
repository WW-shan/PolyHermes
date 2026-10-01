package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.entity.*
import com.wrbug.polymarketbot.repository.*
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.PolymarketTradingFee
import com.wrbug.polymarketbot.util.div
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.event.EventListener
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * 订单状态更新服务
 * 定时轮询更新卖出订单的实际成交价，并更新买入订单的实际数据并发送通知
 */
@Service
class OrderStatusUpdateService(
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val accountRepository: AccountRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val leaderRepository: LeaderRepository,
    private val retrofitFactory: RetrofitFactory,
    private val cryptoUtils: CryptoUtils,
    private val trackingService: CopyOrderTrackingService,
    private val marketService: MarketService,  // 市场信息服务
    private val telegramNotificationService: TelegramNotificationService?,
    private val blockchainService: com.wrbug.polymarketbot.service.common.BlockchainService,
    private val ledger: CopyOrderLedgerService
) : ApplicationContextAware {

    private val logger = LoggerFactory.getLogger(OrderStatusUpdateService::class.java)

    private val updateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 跟踪上一次更新任务的 Job，防止并发执行
    @Volatile
    private var updateJob: Job? = null
    
    private var applicationContext: ApplicationContext? = null
    
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }
    
    /**
     * 获取代理对象，用于解决 @Transactional 自调用问题
     */
    private fun getSelf(): OrderStatusUpdateService {
        return applicationContext?.getBean(OrderStatusUpdateService::class.java)
            ?: throw IllegalStateException("ApplicationContext not initialized")
    }

    // 最近一次清理孤儿记录的时间（清理为全表操作，限频执行）
    @Volatile
    private var lastCleanupAt: Long = 0L

    companion object {
        /** 下单后等待多久再核对待确认订单（毫秒） */
        private const val PENDING_CHECK_DELAY_MS = 5_000L
        /** 待确认订单查询不到（404/null）超过该时长后停止轮询并标记无法确认 */
        private const val PENDING_NOT_FOUND_MAX_AGE_MS = 10 * 60_000L
        /** 已成交买单查询订单详情持续失败超过该时长后输出人工核对告警 */
        private const val NOTIFY_MAX_AGE_MS = 10 * 60_000L
        /** 卖出成交价查询最大失败次数 */
        internal const val MAX_PRICE_QUERY_ATTEMPTS = 12
        /** 卖出成交价查询退避基准与上限（毫秒） */
        private const val PRICE_QUERY_BACKOFF_BASE_MS = 5_000L
        private const val PRICE_QUERY_BACKOFF_MAX_MS = 10 * 60_000L
        /** 孤儿记录清理间隔 */
        private const val CLEANUP_INTERVAL_MS = 10 * 60_000L

        /** 第 attempts 次失败后需要等待的退避时间（指数退避，封顶） */
        internal fun priceQueryBackoffMs(attempts: Int): Long {
            if (attempts <= 0) return 0L
            val shift = (attempts - 1).coerceAtMost(20)
            return (PRICE_QUERY_BACKOFF_BASE_MS shl shift).coerceAtMost(PRICE_QUERY_BACKOFF_MAX_MS)
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        logger.info("订单状态更新服务已启动，将每5秒轮询一次")
    }

    /**
     * 定时更新订单状态
     * 每5秒执行一次
     * 如果上一次任务还在执行，则跳过本次执行，避免并发问题
     * 所有数据库写入通过 [CopyOrderLedgerService] 的事务方法完成（suspend 函数上的 @Transactional 不生效）
     */
    @Scheduled(fixedDelay = 5000)
    fun updateOrderStatus() {
        // 检查上一次任务是否还在执行
        val previousJob = updateJob
        if (previousJob != null && previousJob.isActive) {
            logger.debug("上一次订单状态更新任务还在执行，跳过本次执行")
            return
        }

        // 启动新任务并记录 Job
        updateJob = updateScope.launch {
            try {
                // 1. 清理已删除账户/配置的卖出记录（限频）
                cleanupDeletedAccountOrders()

                // 2. 核对待确认的买入订单（按 size_matched 回填或删除 0 成交）
                reconcilePendingBuyOrders()

                // 3. 核对待确认的卖出订单（按 size_matched 核销，未成交部分退回）
                reconcilePendingSellOrders()

                // 4. 更新卖出订单的实际成交价并发送通知（priceUpdated 共用字段）
                updatePendingSellOrderPrices()

                // 5. 更新买入订单的实际数据并发送通知
                updatePendingBuyOrders()
            } catch (e: Exception) {
                logger.error("订单状态更新异常: ${e.message}", e)
            } finally {
                // 任务完成后清除 Job 引用
                updateJob = null
            }
        }
    }

    /**
     * 验证订单ID格式
     * 订单ID必须以 0x 开头，且是有效的 16 进制字符串
     *
     * @param orderId 订单ID
     * @return 如果格式有效返回 true，否则返回 false
     */
    private fun isValidOrderId(orderId: String): Boolean {
        if (!orderId.startsWith("0x", ignoreCase = true)) {
            return false
        }
        // 验证是否为有效的 16 进制字符串（去除 0x 前缀后）
        val hexPart = orderId.substring(2)
        if (hexPart.isEmpty()) {
            return false
        }
        // 检查是否只包含 0-9, a-f, A-F
        return hexPart.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    /** 已认证客户端及解密后的凭证 */
    private data class AccountClient(
        val account: Account,
        val clobApi: PolymarketClobApi,
        val apiSecret: String = "",
        val apiPassphrase: String = ""
    )

    /**
     * 为账户创建 L2 认证客户端（同一轮任务内按账户缓存）；账户不存在/未配置凭证/解密失败返回 null
     */
    private fun accountClient(accountId: Long, cache: MutableMap<Long, AccountClient?>): AccountClient? {
        return cache.getOrPut(accountId) {
            val account = accountRepository.findById(accountId).orElse(null)
            if (account?.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                null
            } else {
                try {
                    val secret = cryptoUtils.decrypt(account.apiSecret!!)
                    val passphrase = cryptoUtils.decrypt(account.apiPassphrase!!)
                    AccountClient(
                        account = account,
                        clobApi = retrofitFactory.createClobApi(account.apiKey!!, secret, passphrase, account.walletAddress),
                        apiSecret = secret,
                        apiPassphrase = passphrase
                    )
                } catch (e: Exception) {
                    logger.warn("解密 API 凭证失败: accountId=$accountId, error=${e.message}")
                    null
                }
            }
        }
    }

    /**
     * 清理关联跟单配置已删除的卖出记录（限频，单条 SQL 查询孤儿记录，避免 N+1）
     */
    suspend fun cleanupDeletedAccountOrders() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupAt < CLEANUP_INTERVAL_MS) return
        lastCleanupAt = now
        try {
            val orphans = sellMatchRecordRepository.findOrphanRecords()
            if (orphans.isEmpty()) return
            logger.info("清理已删除配置的卖出记录: ${orphans.size} 条")
            for (record in orphans) {
                sellMatchDetailRepository.deleteAll(sellMatchDetailRepository.findByMatchRecordId(record.id!!))
                sellMatchRecordRepository.delete(record)
            }
        } catch (e: Exception) {
            logger.error("清理已删除账户订单异常: ${e.message}", e)
        }
    }

    /**
     * 核对待确认买入（PENDING）：
     * - 订单终态：按 size_matched 回填实际成交（0 成交删除记录）
     * - 仍在进行中（LIVE 等）：继续等待
     * - 查询不到（404/null）：超过 [PENDING_NOT_FOUND_MAX_AGE_MS] 后标记无法确认并停止轮询
     */
    suspend fun reconcilePendingBuyOrders() {
        val now = System.currentTimeMillis()
        val pendingOrders = copyOrderTrackingRepository.findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            CopyOrderTracking.STATUS_PENDING, now - PENDING_CHECK_DELAY_MS
        )
        if (pendingOrders.isEmpty()) return
        val clients = mutableMapOf<Long, AccountClient?>()
        for (order in pendingOrders) {
            try {
                val client = accountClient(order.accountId, clients) ?: continue
                val response = client.clobApi.getOrder(order.buyOrderId)
                val detail = if (response.isSuccessful) response.body() else null
                if (detail == null) {
                    if (response.code() in 500..599) continue
                    if (now - order.createdAt >= PENDING_NOT_FOUND_MAX_AGE_MS) {
                        logger.error("待确认买入长时间查询不到订单，标记为无法确认并停止轮询，请人工核对: orderId=${order.buyOrderId}, trackingId=${order.id}, code=${response.code()}")
                        ledger.markBuyUnconfirmed(order.id!!)
                    }
                    continue
                }
                if (!CopyOrderPlacementExecutor.isTerminal(detail)) {
                    logger.debug("待确认买入仍未终结: orderId=${order.buyOrderId}, status=${detail.status}, sizeMatched=${detail.sizeMatched}")
                    continue
                }
                val filled = detail.sizeMatched.toBigDecimalOrNull()
                if (filled == null || filled.signum() < 0) {
                    logger.error("待确认买入的 size_matched 无效，保留记录并标记为无法确认，请人工核对: orderId=${order.buyOrderId}, sizeMatched=${detail.sizeMatched}")
                    ledger.markBuyUnconfirmed(order.id!!)
                    continue
                }
                // 订单详情的 price 是下单价；必须优先用成交明细回查真实加权均价，查询失败时才回退到下单价。
                val actualPrice = trackingService.queryExecutionPrice(
                    order.buyOrderId,
                    client.clobApi,
                    client.account.proxyAddress
                ) ?: detail.price.toBigDecimalOrNull()
                ledger.confirmBuyFill(order.id!!, filled, actualPrice)
                logger.info("待确认买入已回填: orderId=${order.buyOrderId}, status=${detail.status}, sizeMatched=$filled, avgPrice=$actualPrice")
            } catch (e: Exception) {
                logger.warn("核对待确认买入失败: orderId=${order.buyOrderId}, error=${e.message}", e)
            }
        }
    }

    /**
     * 核对待确认卖出（fill_status = PENDING）：终态按 size_matched 核销（未成交部分退回 tracking），
     * 查询不到超过阈值后标记 UNCONFIRMED（保留预占，等待人工核对）
     */
    suspend fun reconcilePendingSellOrders() {
        val now = System.currentTimeMillis()
        val records = sellMatchRecordRepository.findTop200ByFillStatusAndCreatedAtBeforeOrderByIdAsc(
            SellMatchRecord.FILL_STATUS_PENDING, now - PENDING_CHECK_DELAY_MS
        )
        if (records.isEmpty()) return
        val clients = mutableMapOf<Long, AccountClient?>()
        for (record in records) {
            try {
                val copyTrading = copyTradingRepository.findById(record.copyTradingId).orElse(null) ?: continue
                val client = accountClient(copyTrading.accountId, clients) ?: continue
                val response = client.clobApi.getOrder(record.sellOrderId)
                val detail = if (response.isSuccessful) response.body() else null
                if (detail == null) {
                    if (response.code() in 500..599) continue
                    if (now - record.createdAt >= PENDING_NOT_FOUND_MAX_AGE_MS) {
                        logger.error("待确认卖出长时间查询不到订单，标记为无法确认（保留预占），请人工核对: orderId=${record.sellOrderId}, recordId=${record.id}, code=${response.code()}")
                        ledger.updateSellRecordState(record.id!!, fillStatus = SellMatchRecord.FILL_STATUS_UNCONFIRMED)
                    }
                    continue
                }
                if (!CopyOrderPlacementExecutor.isTerminal(detail)) continue
                val filled = detail.sizeMatched.toBigDecimalOrNull()
                if (filled == null || filled.signum() < 0) {
                    logger.error("待确认卖出的 size_matched 无效，保留预占并标记为无法确认，请人工核对: orderId=${record.sellOrderId}, sizeMatched=${detail.sizeMatched}")
                    ledger.updateSellRecordState(
                        record.id!!,
                        fillStatus = SellMatchRecord.FILL_STATUS_UNCONFIRMED
                    )
                    continue
                }
                ledger.settleSell(record.id!!, filled, null, marketService.getTakerFeeRate(record.marketId))
                logger.info("待确认卖出已核销: orderId=${record.sellOrderId}, status=${detail.status}, sizeMatched=$filled")
            } catch (e: Exception) {
                logger.warn("核对待确认卖出失败: orderId=${record.sellOrderId}, error=${e.message}", e)
            }
        }
    }

    /**
     * 更新已成交卖单的实际成交价并发送通知（priceUpdated 同时表示价格已更新和通知已发送）
     * - 成交价查询失败：保持 priceUpdated=false，失败次数+1，按指数退避重试；达到上限后停止查询并用下单价发送通知
     * - 非 0x 订单ID（自动生成记录）：直接标记已处理
     */
    suspend fun updatePendingSellOrderPrices() {
        val records = sellMatchRecordRepository.findTop200ByPriceUpdatedFalseAndPriceQueryAttemptsLessThanOrderByIdAsc(MAX_PRICE_QUERY_ATTEMPTS)
            .filter { it.fillStatus == SellMatchRecord.FILL_STATUS_FILLED }
        if (records.isEmpty()) return
        val now = System.currentTimeMillis()
        val clients = mutableMapOf<Long, AccountClient?>()
        for (record in records) {
            try {
                val lastQueryAt = record.lastPriceQueryAt
                if (lastQueryAt != null && now - lastQueryAt < priceQueryBackoffMs(record.priceQueryAttempts)) continue
                val copyTrading = copyTradingRepository.findById(record.copyTradingId).orElse(null) ?: continue
                val client = accountClient(copyTrading.accountId, clients) ?: continue

                if (!record.sellOrderId.startsWith("0x", ignoreCase = true)) {
                    val isAutoOrder = record.sellOrderId.startsWith("AUTO_", ignoreCase = true)
                    if (!isAutoOrder) {
                        sendSellOrderNotification(
                            record = record, useTemporaryData = true, account = client.account, copyTrading = copyTrading,
                            clobApi = client.clobApi, apiSecret = client.apiSecret, apiPassphrase = client.apiPassphrase,
                            orderCreatedAt = record.createdAt
                        )
                    }
                    ledger.updateSellRecordState(record.id!!, priceUpdated = true)
                    continue
                }

                val actualPrice = trackingService.queryExecutionPrice(record.sellOrderId, client.clobApi, client.account.proxyAddress)
                if (actualPrice == null) {
                    val updated = ledger.updateSellRecordState(record.id!!, incrementPriceQueryAttempts = true)
                    val attempts = updated?.priceQueryAttempts ?: (record.priceQueryAttempts + 1)
                    if (attempts >= MAX_PRICE_QUERY_ATTEMPTS) {
                        logger.error("卖出成交价查询达到上限，停止查询（价格保持为下单价，priceUpdated=false）: orderId=${record.sellOrderId}, attempts=$attempts")
                        sendSellOrderNotification(
                            record = record, useTemporaryData = true, account = client.account, copyTrading = copyTrading,
                            clobApi = client.clobApi, apiSecret = client.apiSecret, apiPassphrase = client.apiPassphrase,
                            orderCreatedAt = record.createdAt
                        )
                    } else {
                        logger.warn("卖出成交价查询失败，稍后重试: orderId=${record.sellOrderId}, attempts=$attempts")
                    }
                    continue
                }

                val updatedRecord = ledger.updateSellPrice(record.id!!, actualPrice, marketService.getTakerFeeRate(record.marketId)) ?: continue
                logger.info("更新卖出订单价格成功: orderId=${record.sellOrderId}, 原价格=${record.sellPrice}, 新价格=$actualPrice")
                sendSellOrderNotification(
                    record = updatedRecord,
                    actualPrice = actualPrice.toPlainString(),
                    actualSize = updatedRecord.totalMatchedQuantity.toPlainString(),
                    avgFilledPrice = actualPrice.toPlainString(),
                    filled = updatedRecord.totalMatchedQuantity.toPlainString(),
                    account = client.account,
                    copyTrading = copyTrading,
                    clobApi = client.clobApi,
                    apiSecret = client.apiSecret,
                    apiPassphrase = client.apiPassphrase,
                    orderCreatedAt = record.createdAt
                )
            } catch (e: Exception) {
                logger.warn("更新卖出订单价格失败: orderId=${record.sellOrderId}, error=${e.message}", e)
            }
        }
    }

    /**
     * 更新已确认买单的通知（排除待确认/无法确认记录）
     * - 只更新需要的字段（notificationSent，必要时按 size_matched 校正数量），保存前重新读取，不重建实体（保留 leaderBuyQuantity 等字段）
     * - 订单详情/实际成交均价持续查询不到时保留记录并继续核对，避免限价被永久记作成本价
     */
    suspend fun updatePendingBuyOrders() {
        val pendingOrders = copyOrderTrackingRepository.findTop200ByNotificationSentFalseAndStatusNotInOrderByIdAsc(
            listOf(CopyOrderTracking.STATUS_PENDING, CopyOrderTracking.STATUS_UNCONFIRMED)
        )
        if (pendingOrders.isEmpty()) return
        val now = System.currentTimeMillis()
        val clients = mutableMapOf<Long, AccountClient?>()
        for (order in pendingOrders) {
            try {
                if (!isValidOrderId(order.buyOrderId)) {
                    val updated = ledger.markBuyNotificationSent(order.id!!) ?: continue
                    sendBuyOrderNotification(updated, useTemporaryData = true, orderCreatedAt = order.createdAt)
                    continue
                }
                val copyTrading = copyTradingRepository.findById(order.copyTradingId).orElse(null) ?: continue
                val client = accountClient(order.accountId, clients) ?: continue
                val response = client.clobApi.getOrder(order.buyOrderId)
                val detail = if (response.isSuccessful) response.body() else null
                if (detail == null) {
                    if (now - order.createdAt >= NOTIFY_MAX_AGE_MS) {
                        logger.warn("买入订单详情长时间查询不到，保留记录继续等待核实成交价格: orderId=${order.buyOrderId}, code=${response.code()}")
                    }
                    continue
                }

                // 订单终态时优先回查成交明细，修正可能仍为下单限价的买入均价。
                val actualPrice = if (CopyOrderPlacementExecutor.isTerminal(detail)) {
                    trackingService.queryExecutionPrice(order.buyOrderId, client.clobApi, client.account.proxyAddress)
                } else {
                    null
                }
                // 订单终态且 size_matched 与本地数量不一致、尚未被卖出核销时，按 size_matched 校正（对账用实际成交量而非 original_size）
                val sizeMatched = detail.sizeMatched.toBigDecimalOrNull()
                if (CopyOrderPlacementExecutor.isTerminal(detail) && sizeMatched != null &&
                    sizeMatched.compareTo(order.quantity) != 0 && order.matchedQuantity.signum() == 0
                ) {
                    logger.warn("买入订单实际成交量与本地记录不一致，按 size_matched 校正: orderId=${order.buyOrderId}, local=${order.quantity}, sizeMatched=$sizeMatched, avgPrice=$actualPrice")
                    if (ledger.confirmBuyFill(order.id!!, sizeMatched, actualPrice) == null) continue
                } else if (actualPrice != null && actualPrice.compareTo(order.price) != 0) {
                    logger.info("买入订单实际成交均价与本地记录不一致，修正: orderId=${order.buyOrderId}, local=${order.price}, actual=$actualPrice")
                }
                if (actualPrice != null &&
                    ledger.updateBuyPrice(order.id!!, actualPrice, marketService.getTakerFeeRate(order.marketId)) == null
                ) continue
                if (CopyOrderPlacementExecutor.isTerminal(detail) && actualPrice == null &&
                    (sizeMatched ?: order.quantity).signum() > 0
                ) {
                    logger.warn("买入订单已有成交，但实际均价暂不可查，保留通知与价格核对重试: orderId=${order.buyOrderId}, sizeMatched=${sizeMatched ?: order.quantity}")
                    continue
                }
                val updated = ledger.markBuyNotificationSent(order.id!!) ?: continue
                sendBuyOrderNotification(
                    order = updated,
                    actualPrice = updated.price.toPlainString(),
                    actualSize = updated.quantity.toPlainString(),
                    actualOutcome = detail.outcome,
                    avgFilledPrice = updated.price.toPlainString(),
                    filled = updated.quantity.toPlainString(),
                    account = client.account,
                    copyTrading = copyTrading,
                    clobApi = client.clobApi,
                    apiSecret = client.apiSecret,
                    apiPassphrase = client.apiPassphrase,
                    orderCreatedAt = order.createdAt
                )
            } catch (e: Exception) {
                logger.warn("更新买入订单失败: orderId=${order.buyOrderId}, error=${e.message}", e)
            }
        }
    }

    /**
     * 发送买入订单通知
     */
    private suspend fun sendBuyOrderNotification(
        order: CopyOrderTracking,
        useTemporaryData: Boolean = false,
        actualPrice: String? = null,
        actualSize: String? = null,
        actualOutcome: String? = null,
        avgFilledPrice: String? = null,  // 平均成交价（有成交时用于 TG 展示）
        filled: String? = null,  // 已成交数量（与 avgFilledPrice 一起用于金额计算）
        account: Account? = null,
        copyTrading: CopyTrading? = null,
        clobApi: PolymarketClobApi? = null,
        apiSecret: String? = null,
        apiPassphrase: String? = null,
        orderCreatedAt: Long? = null  // 订单创建时间（毫秒时间戳）
    ) {
        if (telegramNotificationService == null) {
            return
        }

        try {
            // 获取跟单关系和账户信息（如果未提供）
            val finalCopyTrading = copyTrading ?: copyTradingRepository.findById(order.copyTradingId).orElse(null)
            if (finalCopyTrading == null) {
                logger.warn("跟单关系不存在，跳过发送通知: copyTradingId=${order.copyTradingId}")
                return
            }

            val finalAccount = account ?: accountRepository.findById(order.accountId).orElse(null)
            if (finalAccount == null) {
                logger.warn("账户不存在，跳过发送通知: accountId=${order.accountId}")
                return
            }

            // 获取市场信息
            val market = marketService.getMarket(order.marketId)
            val marketTitle = market?.title ?: order.marketId

            // 获取 Leader 和跟单配置信息
            val leader = leaderRepository.findById(order.leaderId).orElse(null)
            val leaderName = leader?.leaderName
            val configName = finalCopyTrading.configName

            // 获取当前语言设置
            val locale = try {
                LocaleContextHolder.getLocale()
            } catch (e: Exception) {
                java.util.Locale("zh", "CN")  // 默认简体中文
            }

            // 创建 CLOB API 客户端（如果未提供）
            val finalClobApi =
                clobApi ?: if (finalAccount.apiKey != null && apiSecret != null && apiPassphrase != null) {
                    retrofitFactory.createClobApi(
                        finalAccount.apiKey!!,
                        apiSecret,
                        apiPassphrase,
                        finalAccount.walletAddress
                    )
                } else {
                    null
                }

            // 查询可用余额
            val availableBalance = try {
                blockchainService.getUsdcBalance(finalAccount.walletAddress, finalAccount.proxyAddress).getOrNull()
            } catch (e: Exception) {
                logger.warn("查询可用余额失败: accountId=${finalAccount.id}, ${e.message}")
                null
            }

            // 发送通知（优先使用平均成交价展示）
            telegramNotificationService.sendOrderSuccessNotification(
                orderId = order.buyOrderId,
                marketTitle = marketTitle,
                marketId = order.marketId,
                marketSlug = market?.eventSlug,  // 跳转用的 slug
                side = "BUY",
                price = actualPrice ?: order.price.toString(),  // 限价，无 avgFilledPrice 时展示
                avgFilledPrice = avgFilledPrice,
                filled = filled,
                size = actualSize ?: order.quantity.toString(),  // 使用实际数量或临时数量
                outcome = actualOutcome,  // 使用实际 outcome
                accountName = finalAccount.accountName,
                walletAddress = finalAccount.walletAddress,
                clobApi = finalClobApi,
                apiKey = finalAccount.apiKey,
                apiSecret = apiSecret,
                apiPassphrase = apiPassphrase,
                walletAddressForApi = finalAccount.walletAddress,
                locale = locale,
                leaderName = leaderName,
                configName = configName,
                orderTime = orderCreatedAt,  // 使用订单创建时间
                availableBalance = availableBalance
            )

            logger.info("买入订单通知已发送: orderId=${order.buyOrderId}, copyTradingId=${order.copyTradingId}")
        } catch (e: Exception) {
            logger.warn("发送买入订单通知失败: orderId=${order.buyOrderId}, error=${e.message}", e)
        }
    }


    /**
     * 发送卖出订单通知
     */
    private suspend fun sendSellOrderNotification(
        record: SellMatchRecord,
        useTemporaryData: Boolean = false,
        actualPrice: String? = null,
        actualSize: String? = null,
        actualOutcome: String? = null,
        avgFilledPrice: String? = null,  // 平均成交价（有成交时用于 TG 展示）
        filled: String? = null,  // 已成交数量（与 avgFilledPrice 一起用于金额计算）
        account: Account? = null,
        copyTrading: CopyTrading? = null,
        clobApi: PolymarketClobApi? = null,
        apiSecret: String? = null,
        apiPassphrase: String? = null,
        orderCreatedAt: Long? = null  // 订单创建时间（毫秒时间戳）
    ) {
        if (telegramNotificationService == null) {
            return
        }

        try {
            // 获取跟单关系和账户信息（如果未提供）
            val finalCopyTrading = copyTrading ?: copyTradingRepository.findById(record.copyTradingId).orElse(null)
            if (finalCopyTrading == null) {
                logger.warn("跟单关系不存在，跳过发送通知: copyTradingId=${record.copyTradingId}")
                return
            }

            val finalAccount = account ?: accountRepository.findById(finalCopyTrading.accountId).orElse(null)
            if (finalAccount == null) {
                logger.warn("账户不存在，跳过发送通知: accountId=${finalCopyTrading.accountId}")
                return
            }

            // 获取市场信息
            val market = marketService.getMarket(record.marketId)
            val marketTitle = market?.title ?: record.marketId

            // 获取 Leader 和跟单配置信息
            val leader = leaderRepository.findById(finalCopyTrading.leaderId).orElse(null)
            val leaderName = leader?.leaderName
            val configName = finalCopyTrading.configName

            // 获取当前语言设置
            val locale = try {
                LocaleContextHolder.getLocale()
            } catch (e: Exception) {
                java.util.Locale("zh", "CN")  // 默认简体中文
            }

            // 创建 CLOB API 客户端（如果未提供）
            val finalClobApi =
                clobApi ?: if (finalAccount.apiKey != null && apiSecret != null && apiPassphrase != null) {
                    retrofitFactory.createClobApi(
                        finalAccount.apiKey!!,
                        apiSecret,
                        apiPassphrase,
                        finalAccount.walletAddress
                    )
                } else {
                    null
                }

            // 查询可用余额
            val availableBalance = try {
                blockchainService.getUsdcBalance(finalAccount.walletAddress, finalAccount.proxyAddress).getOrNull()
            } catch (e: Exception) {
                logger.warn("查询可用余额失败: accountId=${finalAccount.id}, ${e.message}")
                null
            }

            // 发送通知（优先使用平均成交价展示）
            telegramNotificationService.sendOrderSuccessNotification(
                orderId = record.sellOrderId,
                marketTitle = marketTitle,
                marketId = record.marketId,
                marketSlug = market?.eventSlug,  // 跳转用的 slug
                side = "SELL",
                price = actualPrice ?: record.sellPrice.toString(),  // 限价，无 avgFilledPrice 时展示
                avgFilledPrice = avgFilledPrice,
                filled = filled,
                size = actualSize ?: record.totalMatchedQuantity.toString(),  // 使用实际数量或临时数量
                outcome = actualOutcome,  // 使用实际 outcome
                accountName = finalAccount.accountName,
                walletAddress = finalAccount.walletAddress,
                clobApi = finalClobApi,
                apiKey = finalAccount.apiKey,
                apiSecret = apiSecret,
                apiPassphrase = apiPassphrase,
                walletAddressForApi = finalAccount.walletAddress,
                locale = locale,
                leaderName = leaderName,
                configName = configName,
                orderTime = orderCreatedAt,  // 使用订单创建时间
                availableBalance = availableBalance
            )

            logger.info("卖出订单通知已发送: orderId=${record.sellOrderId}, copyTradingId=${record.copyTradingId}")
        } catch (e: Exception) {
            logger.warn("发送卖出订单通知失败: orderId=${record.sellOrderId}, error=${e.message}", e)
        }
    }
}
