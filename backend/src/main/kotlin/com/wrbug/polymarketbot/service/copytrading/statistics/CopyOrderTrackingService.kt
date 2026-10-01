package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.*
import com.wrbug.polymarketbot.repository.*
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.service.copytrading.configs.FilterStatus
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.CryptoUtils
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.math.max

private const val MARKET_CONTEXT_MAX_ATTEMPTS = 3
private const val BUY_CONFIG_RETRY_DELAY_MS = 500L

/** Retries pre-order market reads only; no order is signed or submitted here. */
internal suspend fun <T> retryMarketContextResolution(
    resolve: suspend () -> Result<T>,
    waitBeforeRetry: suspend (Long) -> Unit = { delay(it) }
): Result<T> {
    var lastFailure: Throwable? = null
    for (attempt in 1..MARKET_CONTEXT_MAX_ATTEMPTS) {
        val result: Result<T> = try {
            resolve()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (result.isSuccess) return result

        lastFailure = result.exceptionOrNull()
        if (attempt < MARKET_CONTEXT_MAX_ATTEMPTS) {
            waitBeforeRetry(250L * attempt)
        }
    }
    return Result.failure(lastFailure ?: IllegalStateException("市场上下文读取失败"))
}

internal suspend fun retryBuyConfigProcessing(
    process: suspend () -> Unit,
    waitBeforeRetry: suspend (Throwable?) -> Unit = { delay(BUY_CONFIG_RETRY_DELAY_MS) }
): Result<Unit> {
    suspend fun runAttempt(): Result<Unit> = try {
        process()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    val firstResult = runAttempt()
    if (firstResult.isSuccess) return firstResult

    waitBeforeRetry(firstResult.exceptionOrNull())
    return runAttempt()
}

/**
 * 订单跟踪服务
 * 处理买入订单跟踪和卖出订单匹配
 * 实际创建订单并记录跟踪信息
 */
@Service
open class CopyOrderTrackingService(
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository,
    private val processedTradeRepository: ProcessedTradeRepository,
    private val filteredOrderRepository: FilteredOrderRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val accountRepository: AccountRepository,
    private val filterService: CopyTradingFilterService,
    private val leaderRepository: LeaderRepository,
    private val orderSigningService: OrderSigningService,
    private val blockchainService: BlockchainService,
    private val clobService: PolymarketClobService,
    private val retrofitFactory: RetrofitFactory,
    private val cryptoUtils: CryptoUtils,
    private val marketService: MarketService,  // 市场信息服务
    private val ledger: CopyOrderLedgerService,  // 纯数据库记账（非 suspend @Transactional）
    private val telegramNotificationService: TelegramNotificationService? = null  // 可选，避免循环依赖
) : ApplicationContextAware {

    private val logger = LoggerFactory.getLogger(CopyOrderTrackingService::class.java)

    // 协程作用域（用于异步发送通知）
    private val notificationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var applicationContext: ApplicationContext? = null
    
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }
    
    /**
     * 获取代理对象，用于解决 @Transactional 自调用问题
     */
    private fun getSelf(): CopyOrderTrackingService {
        return applicationContext?.getBean(CopyOrderTrackingService::class.java)
            ?: throw IllegalStateException("ApplicationContext not initialized")
    }

    // 分片锁：交易去重锁按 leaderId_tradeId 散列，配置锁按 copyTradingId 散列（固定数量，不会随交易增长泄漏）
    private val tradeLocks = Array(TRADE_LOCK_STRIPES) { Mutex() }
    private val configLocks = Array(CONFIG_LOCK_STRIPES) { Mutex() }

    // 每个配置尚未执行完的延迟买入任务（同配置后续卖出需等待其完成）
    private val delayedBuyJobs = ConcurrentHashMap<Long, MutableSet<Job>>()
    private val scheduledDelayedBuyKeys = ConcurrentHashMap.newKeySet<String>()

    // 延迟跟单任务作用域（每个配置单独调度，不阻塞同一交易其他配置）
    private val delayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 下单执行器：只签一次，重试复用同一订单，超时/5xx 先按订单 hash 查询
    internal var placementExecutor = CopyOrderPlacementExecutor(maxAttempts = MAX_RETRY_ATTEMPTS, retryDelayMs = RETRY_DELAY_MS)

    // 订单创建重试配置
    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3  // 最多尝试次数（同一签名订单）
        private const val RETRY_DELAY_MS = 2000L  // 结果不确定时查询/重发前等待时间（毫秒）
        private const val TRADE_LOCK_STRIPES = 256
        private const val CONFIG_LOCK_STRIPES = 128
        private const val SELL_WAIT_PENDING_BUY_MS = 20_000L  // 卖出等待同 token 待确认买入的最长时间
        private const val SELL_WAIT_POLL_MS = 2_000L
    }

    /**
     * 获取交易去重锁（按交易ID散列）
     */
    private fun getMutex(leaderId: Long, tradeId: String): Mutex {
        val key = "${leaderId}_${tradeId}"
        return tradeLocks[Math.floorMod(key.hashCode(), TRADE_LOCK_STRIPES)]
    }

    /**
     * 获取配置级锁：同一配置的买入/卖出/风控检查/额度预占串行执行
     */
    internal fun getConfigMutex(copyTradingId: Long): Mutex {
        return configLocks[Math.floorMod(copyTradingId.hashCode(), CONFIG_LOCK_STRIPES)]
    }

    /**
     * 解密账户私钥
     */
    private fun decryptPrivateKey(account: Account): String {
        return try {
            cryptoUtils.decrypt(account.privateKey)
        } catch (e: Exception) {
            logger.error("解密私钥失败: accountId=${account.id}", e)
            throw RuntimeException("解密私钥失败: ${e.message}", e)
        }
    }

    /**
     * 解密账户 API Secret
     */
    private fun decryptApiSecret(account: Account): String {
        return account.apiSecret?.let { secret ->
            try {
                cryptoUtils.decrypt(secret)
            } catch (e: Exception) {
                logger.error("解密 API Secret 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Secret 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Secret")
    }

    /**
     * 解密账户 API Passphrase
     */
    private fun decryptApiPassphrase(account: Account): String {
        return account.apiPassphrase?.let { passphrase ->
            try {
                cryptoUtils.decrypt(passphrase)
            } catch (e: Exception) {
                logger.error("解密 API Passphrase 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Passphrase 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Passphrase")
    }

    /**
     * 处理交易事件（WebSocket 或轮询）
     * 根据交易方向调用相应的处理方法
     * 使用 Mutex 保证线程安全（单实例部署）
     */
    suspend fun processTrade(leaderId: Long, trade: TradeResponse, source: String): Result<Unit> {
        // 获取该交易的 Mutex（按交易ID锁定，不同交易可以并行处理）
        val mutex = getMutex(leaderId, trade.id)
        logger.debug("processTrade: ${trade.id}, $source")
        return mutex.withLock {
            try {
                // 1. 检查是否已处理（去重，包括失败状态）
                val existingProcessed = processedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)

                if (existingProcessed != null) {
                    logger.debug("processTrade: 重复 ${trade.id}, $source")
                    if (existingProcessed.status == "FAILED") {
                        return@withLock Result.success(Unit)
                    }
                    return@withLock Result.success(Unit)
                }

                // 2. 处理交易逻辑（通过代理对象调用，确保 @Transactional 生效）
                val self = getSelf()
                val result = when (trade.side.uppercase()) {
                    "BUY" -> self.processBuyTrade(leaderId, trade, source)
                    "SELL" -> self.processSellTrade(leaderId, trade)
                    else -> {
                        logger.warn("未知的交易方向: ${trade.side}")
                        Result.failure(IllegalArgumentException("未知的交易方向: ${trade.side}"))
                    }
                }

                if (result.isFailure) {
                    logger.error(
                        "处理交易失败: leaderId=$leaderId, tradeId=${trade.id}, side=${trade.side}",
                        result.exceptionOrNull()
                    )
                    return@withLock result
                }

                // 3. 标记为已处理（成功状态）
                // 由于使用了 Mutex，这里理论上不会出现并发冲突，但保留异常处理作为兜底
                try {
                    val processed = ProcessedTrade(
                        leaderId = leaderId,
                        leaderTradeId = trade.id,
                        tradeType = trade.side.uppercase(),
                        source = source,
                        status = "SUCCESS",
                        processedAt = System.currentTimeMillis()
                    )
                    processedTradeRepository.save(processed)

                } catch (e: Exception) {
                    // 检查是否是唯一键冲突异常（理论上不会发生，但保留作为兜底）
                    if (isUniqueConstraintViolation(e)) {
                        val existing = processedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)
                        if (existing != null) {
                            if (existing.status == "FAILED") {
                                logger.debug("交易已标记为失败，跳过处理: leaderId=$leaderId, tradeId=${trade.id}")
                                return@withLock Result.success(Unit)
                            }
                            logger.debug("交易已处理（并发检测）: leaderId=$leaderId, tradeId=${trade.id}, status=${existing.status}")
                            return@withLock Result.success(Unit)
                        } else {
                            // 如果检查不到，可能是事务隔离级别问题，等待一下再查询
                            delay(100)
                            val existingAfterDelay =
                                processedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)
                            if (existingAfterDelay != null) {
                                logger.debug("延迟查询到记录（并发检测）: leaderId=$leaderId, tradeId=${trade.id}, status=${existingAfterDelay.status}")
                                return@withLock Result.success(Unit)
                            }
                            logger.warn(
                                "保存ProcessedTrade时发生唯一约束冲突，但查询不到记录: leaderId=$leaderId, tradeId=${trade.id}",
                                e
                            )
                            return@withLock Result.success(Unit)
                        }
                    } else {
                        // 其他类型的异常，重新抛出
                        throw e
                    }
                }

                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("处理交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 处理买入交易
     * 创建跟单买入订单并记录到跟踪表
     * 每个配置在自己的配置锁内执行（延迟配置单独调度），纯数据库写入由 [CopyOrderLedgerService] 在事务内完成
     */
    suspend fun processBuyTrade(leaderId: Long, trade: TradeResponse, source: String): Result<Unit> {
        return try {
            val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
            var firstFailure: Throwable? = null
            for (copyTrading in copyTradings) {
                val result = retryBuyConfigProcessing(
                    process = { processBuyForConfig(copyTrading, trade, source) },
                    waitBeforeRetry = { error ->
                        logger.warn(
                            "跟单配置处理失败，500ms 后重试一次: copyTradingId=${copyTrading.id}, tradeId=${trade.id}, error=${error?.message}"
                        )
                        delay(BUY_CONFIG_RETRY_DELAY_MS)
                    }
                )
                result.exceptionOrNull()?.let { error ->
                    logger.error("处理买入交易失败: copyTradingId=${copyTrading.id}, tradeId=${trade.id}", error)
                    if (firstFailure == null) firstFailure = error
                }
            }
            firstFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        } catch (e: Exception) {
            logger.error("处理买入交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
            Result.failure(e)
        }
    }

    /**
     * 价格容忍度（小数）：priceTolerance 为百分比，0 表示不调价
     */
    internal fun toleranceRatio(copyTrading: CopyTrading): BigDecimal =
        copyTrading.priceTolerance.max(BigDecimal.ZERO).divide(BigDecimal(100), 8, java.math.RoundingMode.HALF_UP)

    /**
     * 买入限价：Leader 价 × (1 + 容忍度)，容忍度 > 0 时至少加 1 个 tick；
     * 向下取整到 tick（不超过用户可接受上限），并限制在 [tick, 1 - tick]
     */
    internal fun calculateBuyLimitPrice(leaderPrice: BigDecimal, tolerance: BigDecimal, tickSize: BigDecimal): BigDecimal {
        var adjustment = leaderPrice.multiply(tolerance)
        if (tolerance.signum() > 0) adjustment = adjustment.max(tickSize)
        return orderSigningService.alignPriceToTick(leaderPrice.add(adjustment), tickSize, isBuy = true)
    }

    /**
     * 卖出限价：基准价（订单簿 bestBid，缺失时用 Leader 价）× (1 - 容忍度)，容忍度 > 0 时至少减 1 个 tick；
     * 向上取整到 tick（不低于用户可接受下限），并限制在 [tick, 1 - tick]
     */
    internal fun calculateSellLimitPrice(basePrice: BigDecimal, tolerance: BigDecimal, tickSize: BigDecimal): BigDecimal {
        var adjustment = basePrice.multiply(tolerance)
        if (tolerance.signum() > 0) adjustment = adjustment.max(tickSize)
        return orderSigningService.alignPriceToTick(basePrice.subtract(adjustment), tickSize, isBuy = false)
    }

    /**
     * 计算最终买入数量（按限价计算金额，RATIO 模式应用 min/max 金额限制），数量按下单精度（2 位）向下取整
     * @return 最终数量；无法下单时返回 null
     */
    internal fun calculateFinalBuyQuantity(trade: TradeResponse, copyTrading: CopyTrading, limitPrice: BigDecimal): BigDecimal? {
        if (limitPrice.signum() <= 0) return null
        var quantity = when (copyTrading.copyMode) {
            "RATIO" -> trade.size.toSafeBigDecimal().multiply(copyTrading.copyRatio)
            "FIXED" -> (copyTrading.fixedAmount ?: return null).divide(limitPrice, 8, java.math.RoundingMode.DOWN)
            else -> return null
        }
        if (quantity.signum() <= 0) return null
        if (copyTrading.copyMode == "RATIO") {
            // 金额 = 数量 × 限价；低于最小金额向上补足，超过最大金额向下截断
            if (quantity.multiply(limitPrice) < copyTrading.minOrderSize) {
                quantity = copyTrading.minOrderSize.divide(limitPrice, 2, java.math.RoundingMode.CEILING)
            }
            if (quantity.multiply(limitPrice) > copyTrading.maxOrderSize) {
                quantity = copyTrading.maxOrderSize.divide(limitPrice, 2, java.math.RoundingMode.DOWN)
            }
        }
        // Polymarket 最小下单数量为 1
        if (quantity < BigDecimal.ONE) quantity = BigDecimal.ONE
        quantity = quantity.setScale(2, java.math.RoundingMode.DOWN)
        if (copyTrading.copyMode == "RATIO" && quantity.multiply(limitPrice) > copyTrading.maxOrderSize) {
            return null
        }
        return quantity
    }

    /**
     * 下单所需的市场上下文
     */
    internal data class MarketContext(
        val tokenId: String = "",
        val marketId: String = "",
        val outcomeIndex: Int = 0,
        val tickSize: BigDecimal = BigDecimal("0.01"),
        val negRisk: Boolean = false,
        val orderbook: com.wrbug.polymarketbot.api.OrderbookResponse? = null
    )

    /**
     * 解析下单所需的 tokenId、市场、outcomeIndex、tick size、negRisk 与订单簿
     * market/outcomeIndex 缺失时按 tokenId 补查；任一关键信息获取失败返回 failure（不使用默认值，允许重试）
     */
    internal suspend fun resolveMarketContext(trade: TradeResponse): Result<MarketContext> {
        val tokenId = if (!trade.tokenId.isNullOrBlank()) {
            trade.tokenId
        } else {
            if (trade.market.isBlank() || trade.outcomeIndex == null) {
                return Result.failure(IllegalStateException("交易缺少 tokenId 且无 market/outcomeIndex: tradeId=${trade.id}"))
            }
            blockchainService.getTokenId(trade.market, trade.outcomeIndex).getOrElse {
                return Result.failure(IllegalStateException("获取 tokenId 失败: market=${trade.market}, error=${it.message}"))
            }
        }

        var marketId = trade.market
        var outcomeIndex = trade.outcomeIndex
        if (marketId.isBlank() || outcomeIndex == null) {
            val info = marketService.getMarketInfoByTokenId(tokenId)
                ?: return Result.failure(IllegalStateException("按 tokenId 补查市场信息失败: tokenId=$tokenId"))
            if (marketId.isBlank()) marketId = info.conditionId
            if (outcomeIndex == null) outcomeIndex = info.outcomeIndex
        }
        if (marketId.isBlank() || outcomeIndex == null) {
            return Result.failure(IllegalStateException("无法确定市场或 outcomeIndex: tradeId=${trade.id}, tokenId=$tokenId"))
        }

        val orderbook = clobService.getOrderbookByTokenId(tokenId).getOrElse {
            return Result.failure(IllegalStateException("获取订单簿失败: tokenId=$tokenId, error=${it.message}"))
        }
        val tickSize = orderbook.tickSize?.toBigDecimalOrNull()
            ?.let { tick -> PolymarketClobService.SUPPORTED_TICK_SIZES.firstOrNull { it.compareTo(tick) == 0 } }
            ?: clobService.getTickSize(tokenId).getOrElse {
                return Result.failure(IllegalStateException("获取 tick size 失败: tokenId=$tokenId, error=${it.message}"))
            }
        // negRisk 以 CLOB 为准：/book 的 neg_risk 优先，缺失时查 /neg-risk；查询失败中止下单（不能当 false）
        val negRisk = orderbook.negRisk ?: clobService.getNegRisk(tokenId).getOrElse {
            return Result.failure(IllegalStateException("获取 neg_risk 失败: tokenId=$tokenId, error=${it.message}"))
        }
        return Result.success(
            MarketContext(
                tokenId = tokenId,
                marketId = marketId,
                outcomeIndex = outcomeIndex,
                tickSize = tickSize,
                negRisk = negRisk,
                orderbook = orderbook
            )
        )
    }

    /**
     * 处理单个配置的买入（配置级串行）
     * delaySeconds > 0 时单独调度延迟任务，延迟结束后重新读取配置、重新取订单簿并重新风控
     */
    internal suspend fun processBuyForConfig(copyTrading: CopyTrading, trade: TradeResponse, source: String) {
        val copyTradingId = copyTrading.id ?: return
        if (copyTrading.delaySeconds <= 0) {
            getConfigMutex(copyTradingId).withLock { executeBuyForConfig(copyTrading, trade, source) }
            return
        }
        val scheduledKey = "$copyTradingId:${trade.id}"
        if (!scheduledDelayedBuyKeys.add(scheduledKey)) {
            logger.info("该配置的 Leader 买入已在延迟队列中，跳过重复调度: copyTradingId=$copyTradingId, tradeId=${trade.id}")
            return
        }
        logger.info("延迟跟单: copyTradingId=$copyTradingId, tradeId=${trade.id}, delaySeconds=${copyTrading.delaySeconds}")
        val jobs = delayedBuyJobs.computeIfAbsent(copyTradingId) { ConcurrentHashMap.newKeySet() }
        val job = delayScope.launch(start = CoroutineStart.LAZY) {
            delay(copyTrading.delaySeconds * 1000L)
            // 延迟后重新读取配置（可能已被禁用或修改）
            val latest = copyTradingRepository.findById(copyTradingId).orElse(null)
            if (latest == null || !latest.enabled) {
                logger.info("延迟跟单结束时配置已删除或禁用，放弃下单: copyTradingId=$copyTradingId, tradeId=${trade.id}")
                return@launch
            }
            try {
                getConfigMutex(copyTradingId).withLock { executeBuyForConfig(latest, trade, source) }
            } catch (e: Exception) {
                logger.error("延迟跟单执行失败: copyTradingId=$copyTradingId, tradeId=${trade.id}", e)
            }
        }
        jobs.add(job)
        job.invokeOnCompletion {
            jobs.remove(job)
            scheduledDelayedBuyKeys.remove(scheduledKey)
        }
        job.start()
    }

    /**
     * 卖出前等待该配置尚未执行完的延迟买入任务（不持有配置锁，避免死锁）
     */
    private suspend fun awaitDelayedBuys(copyTradingId: Long) {
        val jobs = delayedBuyJobs[copyTradingId]?.toList().orEmpty()
        if (jobs.isEmpty()) return
        logger.info("卖出前等待延迟买入完成: copyTradingId=$copyTradingId, jobs=${jobs.size}")
        jobs.joinAll()
    }

    /**
     * 卖出前等待同市场+方向的待确认买入回填（最多 [SELL_WAIT_PENDING_BUY_MS]），不持有配置锁
     */
    private suspend fun awaitPendingBuys(copyTradingId: Long, marketId: String, outcomeIndex: Int) {
        val deadline = System.currentTimeMillis() + SELL_WAIT_PENDING_BUY_MS
        while (copyOrderTrackingRepository.countByCopyTradingIdAndMarketIdAndOutcomeIndexAndStatus(
                copyTradingId, marketId, outcomeIndex, CopyOrderTracking.STATUS_PENDING
            ) > 0
        ) {
            if (System.currentTimeMillis() >= deadline) {
                logger.warn("卖出前仍有待确认买入未回填，按已确认数量匹配: copyTradingId=$copyTradingId, marketId=$marketId, outcomeIndex=$outcomeIndex")
                return
            }
            delay(SELL_WAIT_POLL_MS)
        }
    }

    /**
     * 执行单个配置的买入（调用方必须持有该配置的锁）
     * 顺序：幂等检查 → 市场上下文 → 限价/最终数量 → 过滤与风控（按 数量×限价）→ 签名一次 → 落 PENDING → 下单 → 按实际成交确认
     */
    internal suspend fun executeBuyForConfig(copyTrading: CopyTrading, trade: TradeResponse, source: String) {
        val copyTradingId = copyTrading.id ?: return
        if (copyOrderTrackingRepository.existsByCopyTradingIdAndLeaderBuyTradeId(copyTradingId, trade.id)) {
            logger.info("该配置已处理过此 Leader 买入，跳过: copyTradingId=$copyTradingId, tradeId=${trade.id}")
            return
        }
        val account = accountRepository.findById(copyTrading.accountId).orElse(null) ?: return
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
            logger.warn("账户未配置API凭证，跳过创建订单: accountId=${account.id}, copyTradingId=$copyTradingId")
            return
        }
        if (!account.isEnabled) return

        val context = retryMarketContextResolution(
            resolve = { resolveMarketContext(trade) },
            waitBeforeRetry = { waitMs ->
                logger.warn("市场上下文读取失败，${waitMs}ms 后重试: copyTradingId=$copyTradingId, tradeId=${trade.id}")
                delay(waitMs)
            }
        ).getOrElse {
            logger.error("重试后解析市场上下文仍失败，跳过买入: copyTradingId=$copyTradingId, tradeId=${trade.id}, error=${it.message}")
            throw IllegalStateException("重试后解析买入市场上下文仍失败", it)
        }
        val leaderPrice = trade.price.toSafeBigDecimal()
        val buyPrice = calculateBuyLimitPrice(leaderPrice, toleranceRatio(copyTrading), context.tickSize)
        val bestAsk = context.orderbook?.let { PolymarketClobService.bestAsk(it) }
        if (bestAsk == null) {
            logger.warn("订单簿中没有卖单，跳过创建订单: copyTradingId=$copyTradingId, tradeId=${trade.id}")
            return
        }
        if (buyPrice < bestAsk) {
            logger.info("买入限价 ($buyPrice) 低于最佳卖单价格 ($bestAsk)，无法匹配，跳过: copyTradingId=$copyTradingId, tradeId=${trade.id}, leaderPrice=$leaderPrice, tolerance=${copyTrading.priceTolerance}")
            return
        }
        val finalBuyQuantity = calculateFinalBuyQuantity(trade, copyTrading, buyPrice) ?: run {
            logger.warn("无法计算有效买入数量（或超过单笔最大金额），跳过: copyTradingId=$copyTradingId, tradeId=${trade.id}")
            return
        }
        val bookMinSize = context.orderbook.minOrderSize?.toBigDecimalOrNull()
        if (bookMinSize != null && finalBuyQuantity < bookMinSize) {
            logger.warn("买入数量低于市场最小下单量，跳过: copyTradingId=$copyTradingId, quantity=$finalBuyQuantity, minOrderSize=$bookMinSize")
            return
        }
        // 风控金额按 最终数量 × 限价
        val copyOrderAmount = finalBuyQuantity.multiply(buyPrice)

        if (!passBuyFilters(copyTrading, account, trade, context, copyOrderAmount, finalBuyQuantity)) return

        val riskCheckResult = checkRiskControls(copyTrading)
        if (!riskCheckResult.first) {
            logger.warn("风险控制检查失败，跳过创建订单: copyTradingId=$copyTradingId, reason=${riskCheckResult.second}")
            return
        }

        placeBuyOrder(copyTrading, account, trade, context, buyPrice, finalBuyQuantity, source)
    }

    /**
     * 买入过滤条件检查（关键字/截止时间/价格区间/价差/深度/仓位），未通过时记录过滤订单并通知
     */
    private suspend fun passBuyFilters(
        copyTrading: CopyTrading,
        account: Account,
        trade: TradeResponse,
        context: MarketContext,
        copyOrderAmount: BigDecimal,
        quantity: BigDecimal
    ): Boolean {
        var marketTitle: String? = null
        var marketEndDate: Long? = null
        if (copyTrading.keywordFilterMode != "DISABLED" || copyTrading.maxMarketEndDate != null) {
            try {
                val market = marketService.getMarket(context.marketId)
                marketTitle = market?.title
                marketEndDate = market?.endDate
            } catch (e: Exception) {
                logger.warn("获取市场信息失败（关键字过滤/市场截止时间检查需要）: ${e.message}", e)
            }
        }
        val filterResult = filterService.checkFilters(
            copyTrading,
            context.tokenId,
            tradePrice = trade.price.toSafeBigDecimal(),
            copyOrderAmount = copyOrderAmount,
            marketId = context.marketId,
            marketTitle = marketTitle,
            marketEndDate = marketEndDate,
            outcomeIndex = context.outcomeIndex
        )
        if (filterResult.isPassed) return true
        logger.warn("过滤条件检查失败，跳过创建订单: copyTradingId=${copyTrading.id}, reason=${filterResult.reason}")
        recordFilteredOrder(
            copyTrading = copyTrading,
            account = account,
            trade = trade,
            marketId = context.marketId,
            outcomeIndex = context.outcomeIndex,
            side = "BUY",
            reason = filterResult.reason,
            filterType = extractFilterType(filterResult.status, filterResult.reason),
            calculatedQuantity = quantity
        )
        return false
    }

    /**
     * 记录被过滤/跳过的订单并按配置推送通知（异步，不阻塞）
     */
    private fun recordFilteredOrder(
        copyTrading: CopyTrading,
        account: Account,
        trade: TradeResponse,
        marketId: String,
        outcomeIndex: Int?,
        side: String,
        reason: String,
        filterType: String,
        calculatedQuantity: BigDecimal?
    ) {
        notificationScope.launch {
            try {
                val market = marketService.getMarket(marketId)
                val marketTitle = market?.title ?: marketId
                val marketSlug = market?.slug
                try {
                    filteredOrderRepository.save(
                        FilteredOrder(
                            copyTradingId = copyTrading.id!!,
                            accountId = copyTrading.accountId,
                            leaderId = copyTrading.leaderId,
                            leaderTradeId = trade.id,
                            marketId = marketId,
                            marketTitle = marketTitle,
                            marketSlug = marketSlug,
                            side = side,
                            outcomeIndex = outcomeIndex,
                            outcome = trade.outcome,
                            price = trade.price.toSafeBigDecimal(),
                            size = trade.size.toSafeBigDecimal(),
                            calculatedQuantity = calculatedQuantity,
                            filterReason = reason,
                            filterType = filterType
                        )
                    )
                    logger.info("已记录被过滤的订单: copyTradingId=${copyTrading.id}, tradeId=${trade.id}, filterType=$filterType")
                } catch (e: Exception) {
                    logger.error("保存被过滤订单失败: ${e.message}", e)
                }
                if (copyTrading.pushFilteredOrders) {
                    val locale = try {
                        org.springframework.context.i18n.LocaleContextHolder.getLocale()
                    } catch (e: Exception) {
                        java.util.Locale("zh", "CN")
                    }
                    telegramNotificationService?.sendOrderFilteredNotification(
                        marketTitle = marketTitle,
                        marketId = marketId,
                        marketSlug = marketSlug,
                        side = side,
                        outcome = trade.outcome,
                        price = trade.price,
                        size = trade.size,
                        filterReason = reason,
                        filterType = filterType,
                        accountName = account.accountName,
                        walletAddress = account.walletAddress,
                        locale = locale
                    )
                }
            } catch (e: Exception) {
                logger.error("处理被过滤订单通知失败: ${e.message}", e)
            }
        }
    }

    /** 已签名待提交的订单（只签一次，重试复用） */
    private data class PreparedOrder(
        val signedOrder: com.wrbug.polymarketbot.api.SignedOrderObject,
        val orderHash: String = "",
        val clobApi: PolymarketClobApi,
        val owner: String = ""
    )

    /**
     * 解密凭证、创建认证客户端并签名订单（一次），同时本地计算订单 hash（= CLOB orderID）
     */
    private fun prepareOrder(
        account: Account,
        context: MarketContext,
        side: String,
        price: BigDecimal,
        size: BigDecimal
    ): PreparedOrder {
        val apiKey = account.apiKey ?: throw IllegalStateException("账户未配置 API Key")
        val clobApi = retrofitFactory.createClobApi(
            apiKey,
            decryptApiSecret(account),
            decryptApiPassphrase(account),
            account.walletAddress
        )
        val signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)
        val exchangeContract = orderSigningService.getExchangeContract(context.negRisk)
        val signedOrder = orderSigningService.createAndSignOrder(
            privateKey = decryptPrivateKey(account),
            makerAddress = account.proxyAddress,
            tokenId = context.tokenId,
            side = side,
            price = price.toPlainString(),
            size = size.toPlainString(),
            signatureType = signatureType,
            exchangeContract = exchangeContract,
            tickSize = context.tickSize
        )
        // 校验 signer 与账户 walletAddress 一致（POLY_ADDRESS 与 order.signer 需一致）；Deposit Wallet 的 signer 为钱包合约本身
        val expectedSigner = if (signatureType == OrderSigningService.SIGNATURE_TYPE_POLY_1271) account.proxyAddress else account.walletAddress
        if (!signedOrder.signer.equals(expectedSigner, ignoreCase = true)) {
            throw IllegalStateException(
                "订单 signer 与账户 walletAddress 不一致，会导致 invalid signature: signer=${signedOrder.signer.take(10)}..., walletAddress=${account.walletAddress.take(10)}..."
            )
        }
        val orderHash = orderSigningService.computeOrderHash(signedOrder, exchangeContract)
        return PreparedOrder(signedOrder = signedOrder, orderHash = orderHash, clobApi = clobApi, owner = apiKey)
    }

    /**
     * 签名并提交买入订单：下单前落 PENDING 记录（带本地订单 hash），下单后按实际成交确认
     */
    private suspend fun placeBuyOrder(
        copyTrading: CopyTrading,
        account: Account,
        trade: TradeResponse,
        context: MarketContext,
        buyPrice: BigDecimal,
        quantity: BigDecimal,
        source: String
    ) {
        val copyTradingId = copyTrading.id!!
        logger.info("准备创建买入订单: copyTradingId=$copyTradingId, tradeId=${trade.id}, leaderPrice=${trade.price}, tolerance=${copyTrading.priceTolerance}, limitPrice=$buyPrice, quantity=$quantity, tick=${context.tickSize}, negRisk=${context.negRisk}")
        val prepared = prepareOrder(account, context, "BUY", buyPrice, quantity)

        val pending = ledger.createPendingBuy(
            CopyOrderTracking(
                copyTradingId = copyTradingId,
                accountId = copyTrading.accountId,
                leaderId = copyTrading.leaderId,
                marketId = context.marketId,
                side = context.outcomeIndex.toString(),
                outcomeIndex = context.outcomeIndex,
                buyOrderId = prepared.orderHash,
                leaderBuyTradeId = trade.id,
                leaderBuyQuantity = trade.size.toSafeBigDecimal(),
                quantity = BigDecimal.ZERO,
                price = buyPrice,
                remainingQuantity = BigDecimal.ZERO,
                status = CopyOrderTracking.STATUS_PENDING,
                notificationSent = false,
                source = source,
                requestedQuantity = quantity
            )
        )

        val result = placementExecutor.place(
            clobApi = prepared.clobApi,
            signedOrder = prepared.signedOrder,
            orderHash = prepared.orderHash,
            owner = prepared.owner,
            logContext = "copyTradingId=$copyTradingId, tradeId=${trade.id}, side=BUY"
        )
        handleBuyPlacementResult(copyTrading, account, context, pending, buyPrice, quantity, result)
    }

    /**
     * 下单后的记账：成交已确定则按实际成交确认；待确认则保留 PENDING 由轮询回填；明确拒绝则删除 PENDING
     */
    private suspend fun handleBuyPlacementResult(
        copyTrading: CopyTrading,
        account: Account,
        context: MarketContext,
        pending: CopyOrderTracking,
        buyPrice: BigDecimal,
        quantity: BigDecimal,
        result: Result<OrderOutcome>
    ) {
        val trackingId = pending.id!!
        val outcome = result.getOrElse { error ->
            if (error is OrderRejectedException) {
                logger.error("创建买入订单失败: copyTradingId=${copyTrading.id}, orderId=${pending.buyOrderId}, error=${error.message}")
                ledgerWithRetry("删除被拒绝的待确认买入") { ledger.confirmBuyFill(trackingId, BigDecimal.ZERO, null) }
                notifyOrderFailure(copyTrading, account, context.marketId, "BUY", buyPrice, quantity, error.message.orEmpty())
            } else {
                // 无法确认是否受理：保留 PENDING，由 OrderStatusUpdateService 按订单 hash 核对
                logger.error("买入订单提交结果不确定，保留待确认记录等待核对: copyTradingId=${copyTrading.id}, orderId=${pending.buyOrderId}, error=${error.message}")
            }
            return
        }
        when (outcome) {
            is OrderOutcome.Settled -> {
                val confirmed = ledgerWithRetry("确认买入成交") {
                    ledger.confirmBuyFill(trackingId, outcome.filledShares, outcome.avgPrice)
                }
                logger.info("买入订单成交已确认: orderId=${outcome.orderId}, copyTradingId=${copyTrading.id}, filled=${outcome.filledShares}, avgPrice=${outcome.avgPrice}, kept=${confirmed != null}")
            }
            is OrderOutcome.Pending -> {
                logger.info("买入订单已受理但成交待确认: orderId=${outcome.orderId}, status=${outcome.status}, copyTradingId=${copyTrading.id}")
            }
        }
    }

    /**
     * 记账写入失败时重试（最多 3 次），仍失败打 ERROR（订单已在交易所，需要人工核对）
     */
    private suspend fun <T> ledgerWithRetry(action: String, block: () -> T): T? {
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                logger.warn("记账失败，准备重试 ($attempt/3): action=$action, error=${e.message}")
                delay(500L * attempt)
            }
        }
        logger.error("记账最终失败，需要人工核对: action=$action", lastError)
        return null
    }

    /**
     * 发送下单失败通知（异步，仅在 pushFailedOrders 为 true 时发送）
     */
    private fun notifyOrderFailure(
        copyTrading: CopyTrading,
        account: Account,
        marketId: String,
        side: String,
        price: BigDecimal,
        size: BigDecimal,
        errorMessage: String
    ) {
        if (!copyTrading.pushFailedOrders) return
        notificationScope.launch {
            try {
                val market = marketService.getMarket(marketId)
                val locale = try {
                    org.springframework.context.i18n.LocaleContextHolder.getLocale()
                } catch (e: Exception) {
                    java.util.Locale("zh", "CN")
                }
                telegramNotificationService?.sendOrderFailureNotification(
                    marketTitle = market?.title ?: marketId,
                    marketId = marketId,
                    marketSlug = market?.eventSlug,
                    side = side,
                    outcome = null,
                    price = price.toPlainString(),
                    size = size.toPlainString(),
                    errorMessage = errorMessage,
                    accountName = account.accountName,
                    walletAddress = account.walletAddress,
                    locale = locale
                )
            } catch (e: Exception) {
                logger.warn("发送订单失败通知失败: ${e.message}", e)
            }
        }
    }

    /**
     * 处理卖出交易
     * 每个配置在自己的配置锁内匹配；任一配置因可重试原因失败（市场补查失败等）时返回 failure，
     * 不记 SUCCESS，已成功的配置通过 (copyTradingId, leaderSellTradeId) 幂等，重试不会重复卖出
     */
    suspend fun processSellTrade(leaderId: Long, trade: TradeResponse): Result<Unit> {
        return try {
            val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
            var firstFailure: Throwable? = null
            for (copyTrading in copyTradings) {
                if (!copyTrading.supportSell) continue
                val result = try {
                    matchSellOrder(copyTrading, trade)
                } catch (e: Exception) {
                    Result.failure(e)
                }
                result.exceptionOrNull()?.let {
                    logger.error("处理卖出交易失败: copyTradingId=${copyTrading.id}, tradeId=${trade.id}, error=${it.message}", it)
                    if (firstFailure == null) firstFailure = it
                }
            }
            firstFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        } catch (e: Exception) {
            logger.error("处理卖出交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
            Result.failure(e)
        }
    }

    /**
     * 卖出比例 = Σ本配置实际跟单买入量 / Σ对应 Leader 买入量（同市场+方向，排除待确认/无法确认记录）
     * 同时覆盖 RATIO（含 min/max 截断）与 FIXED 模式；缺少 Leader 买入量时返回 null（调用方跳过并告警）
     */
    internal fun calculateSellRatio(copyTradingId: Long, marketId: String, outcomeIndex: Int): BigDecimal? {
        var totalCopy = BigDecimal.ZERO
        var totalLeader = BigDecimal.ZERO
        for (order in copyOrderTrackingRepository.findByCopyTradingIdAndMarketIdAndOutcomeIndex(copyTradingId, marketId, outcomeIndex)) {
            if (order.status == CopyOrderTracking.STATUS_PENDING || order.status == CopyOrderTracking.STATUS_UNCONFIRMED) continue
            val leaderQty = order.leaderBuyQuantity ?: continue
            if (leaderQty.signum() <= 0 || order.quantity.signum() <= 0) continue
            totalCopy = totalCopy.add(order.quantity)
            totalLeader = totalLeader.add(leaderQty)
        }
        if (totalLeader.signum() <= 0) return null
        return totalCopy.divide(totalLeader, 8, java.math.RoundingMode.HALF_UP)
    }

    /**
     * 卖出订单匹配
     * 1. 市场上下文补查（失败返回 failure，允许重试）
     * 2. 等待该配置延迟中/待确认的同 token 买入（不持锁）
     * 3. 配置锁内：按累计比例算卖出量 → FIFO 分配 → 签名一次 → 预占 tracking 并写 PENDING 卖出记录 → 下单 → 按实际成交核销、未成交退回
     */
    internal suspend fun matchSellOrder(copyTrading: CopyTrading, leaderSellTrade: TradeResponse): Result<Unit> {
        val copyTradingId = copyTrading.id ?: return Result.success(Unit)
        val account = accountRepository.findById(copyTrading.accountId).orElse(null)
        if (account == null || account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null || !account.isEnabled) {
            logger.warn("账户不存在/未配置API凭证/未启用，跳过卖出匹配: accountId=${copyTrading.accountId}, copyTradingId=$copyTradingId")
            return Result.success(Unit)
        }
        if (sellMatchRecordRepository.existsByCopyTradingIdAndLeaderSellTradeId(copyTradingId, leaderSellTrade.id)) {
            logger.info("该配置已处理过此 Leader 卖出，跳过: copyTradingId=$copyTradingId, tradeId=${leaderSellTrade.id}")
            return Result.success(Unit)
        }
        val context = resolveMarketContext(leaderSellTrade).getOrElse { return Result.failure(it) }

        awaitDelayedBuys(copyTradingId)
        awaitPendingBuys(copyTradingId, context.marketId, context.outcomeIndex)

        return getConfigMutex(copyTradingId).withLock {
            val unmatchedOrders = copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndex(
                copyTradingId, context.marketId, context.outcomeIndex
            )
            if (unmatchedOrders.isEmpty()) return@withLock Result.success(Unit)

            val ratio = calculateSellRatio(copyTradingId, context.marketId, context.outcomeIndex)
            if (ratio == null) {
                val reason = "无法确定 Leader 买入数量，无法计算卖出比例，跳过卖出"
                logger.warn("$reason: copyTradingId=$copyTradingId, tradeId=${leaderSellTrade.id}, marketId=${context.marketId}, outcomeIndex=${context.outcomeIndex}")
                recordFilteredOrder(copyTrading, account, leaderSellTrade, context.marketId, context.outcomeIndex, "SELL", reason, "SELL_RATIO_UNKNOWN", null)
                return@withLock Result.success(Unit)
            }
            var needMatch = leaderSellTrade.size.toSafeBigDecimal().multiply(ratio)
            if (needMatch.signum() > 0 && needMatch < BigDecimal.ONE) needMatch = BigDecimal.ONE
            val available = unmatchedOrders.fold(BigDecimal.ZERO) { acc, o -> acc.add(o.remainingQuantity) }
            // 卖出数量按下单精度（2 位）向下取整，避免记账数量与实际成交精度不一致
            val totalMatched = needMatch.min(available).setScale(2, java.math.RoundingMode.DOWN)
            if (totalMatched < BigDecimal.ONE) {
                logger.warn("卖出数量小于1，跳过卖出: copyTradingId=$copyTradingId, tradeId=${leaderSellTrade.id}, quantity=$totalMatched")
                return@withLock Result.success(Unit)
            }

            val bestBid = context.orderbook?.let { PolymarketClobService.bestBid(it) }
            val sellPrice = calculateSellLimitPrice(bestBid ?: leaderSellTrade.price.toSafeBigDecimal(), toleranceRatio(copyTrading), context.tickSize)
            val marketFeeRate = marketService.getTakerFeeRate(context.marketId)
            val matchDetails = allocateSellFifo(unmatchedOrders, totalMatched, sellPrice, marketFeeRate)

            val prepared = try {
                prepareOrder(account, context, "SELL", sellPrice, totalMatched)
            } catch (e: Exception) {
                return@withLock Result.failure(e)
            }
            val record = try {
                ledger.reserveSell(
                    SellMatchRecord(
                        copyTradingId = copyTradingId,
                        sellOrderId = prepared.orderHash,
                        leaderSellTradeId = leaderSellTrade.id,
                        marketId = context.marketId,
                        side = context.outcomeIndex.toString(),
                        outcomeIndex = context.outcomeIndex,
                        totalMatchedQuantity = totalMatched,
                        sellPrice = sellPrice,
                        totalRealizedPnl = matchDetails.fold(BigDecimal.ZERO) { acc, d -> acc.add(d.realizedPnl) },
                        priceUpdated = false
                    ),
                    matchDetails
                )
            } catch (e: Exception) {
                return@withLock Result.failure(e)
            }

            logger.info("准备创建卖出订单: copyTradingId=$copyTradingId, tradeId=${leaderSellTrade.id}, ratio=$ratio, quantity=$totalMatched, limitPrice=$sellPrice, bestBid=$bestBid, tick=${context.tickSize}")
            val result = placementExecutor.place(
                clobApi = prepared.clobApi,
                signedOrder = prepared.signedOrder,
                orderHash = prepared.orderHash,
                owner = prepared.owner,
                logContext = "copyTradingId=$copyTradingId, tradeId=${leaderSellTrade.id}, side=SELL"
            )
            handleSellPlacementResult(copyTrading, account, context, record, sellPrice, totalMatched, marketFeeRate, result)
            Result.success(Unit)
        }
    }

    /**
     * 按 FIFO 将卖出数量分配到未匹配买入记录，生成匹配明细（matchRecordId 由记账时设置）
     */
    internal fun allocateSellFifo(
        unmatchedOrders: List<CopyOrderTracking>,
        totalQuantity: BigDecimal,
        sellPrice: BigDecimal,
        feeRate: BigDecimal?
    ): List<SellMatchDetail> {
        val details = mutableListOf<SellMatchDetail>()
        var remaining = totalQuantity
        for (order in unmatchedOrders) {
            if (remaining.signum() <= 0) break
            val matchQty = order.remainingQuantity.min(remaining)
            if (matchQty.signum() <= 0) continue
            details.add(
                SellMatchDetail(
                    matchRecordId = 0,
                    trackingId = order.id!!,
                    buyOrderId = order.buyOrderId,
                    matchedQuantity = matchQty,
                    buyPrice = order.price,
                    sellPrice = sellPrice,
                    realizedPnl = PolymarketTradingFee.netRealizedPnl(order.price, sellPrice, matchQty, feeRate)
                )
            )
            remaining = remaining.subtract(matchQty)
        }
        return details
    }

    /**
     * 卖出下单后的记账：成交确定则按实际量核销、未成交部分退回；被拒绝则全部退回；待确认/不确定保持预占由轮询核对
     */
    private suspend fun handleSellPlacementResult(
        copyTrading: CopyTrading,
        account: Account,
        context: MarketContext,
        record: SellMatchRecord,
        sellPrice: BigDecimal,
        quantity: BigDecimal,
        feeRate: BigDecimal?,
        result: Result<OrderOutcome>
    ) {
        val recordId = record.id!!
        val outcome = result.getOrElse { error ->
            if (error is OrderRejectedException) {
                logger.error("创建卖出订单失败，退回预占数量: copyTradingId=${copyTrading.id}, orderId=${record.sellOrderId}, error=${error.message}")
                ledgerWithRetry("退回被拒绝卖单的预占") { ledger.settleSell(recordId, BigDecimal.ZERO, null, feeRate) }
                notifyOrderFailure(copyTrading, account, context.marketId, "SELL", sellPrice, quantity, error.message.orEmpty())
            } else {
                logger.error("卖出订单提交结果不确定，保持预占等待核对: copyTradingId=${copyTrading.id}, orderId=${record.sellOrderId}, error=${error.message}")
            }
            return
        }
        when (outcome) {
            is OrderOutcome.Settled -> {
                ledgerWithRetry("按实际成交结算卖单") {
                    ledger.settleSell(recordId, outcome.filledShares, outcome.avgPrice, feeRate)
                }
                logger.info("卖出订单成交已确认: orderId=${outcome.orderId}, copyTradingId=${copyTrading.id}, requested=$quantity, filled=${outcome.filledShares}, avgPrice=${outcome.avgPrice}")
            }
            is OrderOutcome.Pending -> {
                logger.info("卖出订单已受理但成交待确认: orderId=${outcome.orderId}, status=${outcome.status}, copyTradingId=${copyTrading.id}")
            }
        }
    }

    /**
     * 检查是否是唯一键冲突异常
     */
    private fun isUniqueConstraintViolation(e: Exception): Boolean {
        // 检查是否是 DataIntegrityViolationException 或 DuplicateKeyException
        if (e is DataIntegrityViolationException || e is DuplicateKeyException) {
            return true
        }

        // 检查是否是 SQLException（MySQL 错误码 1062 表示重复键）
        var cause: Throwable? = e.cause
        while (cause != null) {
            if (cause is SQLException) {
                val sqlException = cause as SQLException
                // MySQL 错误码 1062 表示重复键（Duplicate entry）
                if (sqlException.errorCode == 1062 || sqlException.sqlState == "23000") {
                    return true
                }
            }
            // 检查异常消息中是否包含唯一键冲突的关键字
            val message = cause.message ?: ""
            if (message.contains("Duplicate entry") ||
                message.contains("uk_leader_trade") ||
                message.contains("UNIQUE constraint")
            ) {
                return true
            }
            cause = cause.cause
        }

        return false
    }

    /**
     * 构建简化的错误信息（只保留 code 和 errorBody）
     */
    private fun buildFullErrorMessage(
        exception: Throwable?,
        side: String,
        price: String,
        size: String,
        tradeId: String
    ): String {
        if (exception == null) {
            return "code=未知, errorBody=null"
        }

        val exceptionMessage = exception.message ?: ""

        // 从错误信息中提取 code 和 errorBody
        val codePattern = Regex("code=([^,}]+)")
        val errorBodyPattern = Regex("errorBody=([^,}]+)")

        val codeMatch = codePattern.find(exceptionMessage)
        val errorBodyMatch = errorBodyPattern.find(exceptionMessage)

        val code = codeMatch?.groupValues?.get(1)?.trim() ?: "未知"
        val errorBody = errorBodyMatch?.groupValues?.get(1)?.trim() ?: "null"

        return "code=$code, errorBody=$errorBody"
    }


    /**
     * 风险控制检查
     * 返回 Pair<是否通过, 失败原因>
     */
    private fun checkRiskControls(
        copyTrading: CopyTrading
    ): Pair<Boolean, String> {
        // 1. 检查每日订单数限制（在配置锁内执行，包含待确认订单，不会被并发突破）
        val todayStart = System.currentTimeMillis() - (System.currentTimeMillis() % 86400000)  // 今天0点的时间戳
        val todayBuyOrderCount = copyOrderTrackingRepository.countByCopyTradingIdAndCreatedAtGreaterThanEqual(copyTrading.id!!, todayStart)

        if (todayBuyOrderCount >= copyTrading.maxDailyOrders) {
            return Pair(false, "今日订单数已达上限: ${todayBuyOrderCount}/${copyTrading.maxDailyOrders}")
        }

        // 2. 检查每日亏损限制（需要计算今日已实现盈亏）
        val todaySellRecords = sellMatchRecordRepository.findByCopyTradingId(copyTrading.id)
            .filter { it.createdAt >= todayStart }

        val todayRealizedPnl = todaySellRecords.sumOf { it.totalRealizedPnl.toSafeBigDecimal() }
        if (todayRealizedPnl.lt(BigDecimal.ZERO)) {
            val todayLoss = todayRealizedPnl.abs()
            if (todayLoss.gte(copyTrading.maxDailyLoss)) {
                return Pair(false, "今日亏损已达上限: ${todayLoss}/${copyTrading.maxDailyLoss}")
            }
        }

        return Pair(true, "")
    }

    /**
     * 从过滤结果中提取过滤类型
     */
    private fun extractFilterType(status: FilterStatus, reason: String): String {
        return when (status) {
            FilterStatus.PASSED -> "PASSED"
            FilterStatus.FAILED_PRICE_RANGE -> "PRICE_RANGE"
            FilterStatus.FAILED_ORDERBOOK_ERROR -> "ORDERBOOK_ERROR"
            FilterStatus.FAILED_ORDERBOOK_EMPTY -> "ORDERBOOK_EMPTY"
            FilterStatus.FAILED_SPREAD -> "SPREAD"
            FilterStatus.FAILED_ORDER_DEPTH -> "ORDER_DEPTH"
            FilterStatus.FAILED_MAX_POSITION_VALUE -> "MAX_POSITION_VALUE"
            FilterStatus.FAILED_KEYWORD_FILTER -> "KEYWORD_FILTER"
            FilterStatus.FAILED_MARKET_END_DATE -> "MARKET_END_DATE"
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

    /**
     * 获取订单的实际成交价（兼容旧调用）
     * 查询失败时返回 fallbackPrice；新代码应使用 [queryExecutionPrice] 区分"失败"与"成功"
     */
    suspend fun getActualExecutionPrice(
        orderId: String,
        clobApi: PolymarketClobApi,
        fallbackPrice: BigDecimal,
        makerAddress: String? = null
    ): BigDecimal {
        return queryExecutionPrice(orderId, clobApi, makerAddress) ?: fallbackPrice
    }

    /**
     * 查询订单的加权平均成交价
     * - 通过订单详情的 associate_trades 逐笔查询 /data/trades（带 maker_address，需 L2 认证）
     * - 只统计状态非 FAILED 的成交
     * - 我方为 taker：按 maker_orders 的 matched_amount × price 加权（maker 为互补 token 时价格取 1 - price）
     * - 我方为 maker：取 maker_orders 中本订单的 matched_amount × price
     * @return 成交均价；订单未成交或任一步查询失败返回 null（调用方保持未更新并退避重试）
     */
    suspend fun queryExecutionPrice(orderId: String, clobApi: PolymarketClobApi, makerAddress: String?): BigDecimal? {
        return try {
            val orderResponse = clobApi.getOrder(orderId)
            val order = orderResponse.body()
            if (!orderResponse.isSuccessful || order == null) {
                logger.warn("查询订单详情失败: orderId=$orderId, code=${orderResponse.code()}")
                return null
            }
            if ((order.sizeMatched.toBigDecimalOrNull() ?: BigDecimal.ZERO).signum() <= 0) return null
            val tradeIds = order.associateTrades.orEmpty()
            if (tradeIds.isEmpty()) return null
            var totalAmount = BigDecimal.ZERO
            var totalSize = BigDecimal.ZERO
            for (tradeId in tradeIds) {
                val response = clobApi.getClobTrades(id = tradeId, makerAddress = makerAddress)
                if (!response.isSuccessful) {
                    logger.warn("查询成交记录失败: orderId=$orderId, tradeId=$tradeId, code=${response.code()}")
                    return null
                }
                val trade = response.body()?.data?.firstOrNull { it.id == tradeId } ?: return null
                if (trade.status.equals("FAILED", ignoreCase = true)) continue
                val (amount, size) = fillAmountForOrder(trade, orderId, order.assetId)
                totalAmount = totalAmount.add(amount)
                totalSize = totalSize.add(size)
            }
            if (totalSize.signum() <= 0) null else totalAmount.divide(totalSize, 8, java.math.RoundingMode.HALF_UP)
        } catch (e: Exception) {
            logger.warn("获取实际成交价异常: orderId=$orderId, error=${e.message}")
            null
        }
    }

    /**
     * 计算某笔成交中属于本订单的 (金额, 数量)
     */
    internal fun fillAmountForOrder(
        trade: com.wrbug.polymarketbot.api.ClobTrade,
        orderId: String,
        assetId: String
    ): Pair<BigDecimal, BigDecimal> {
        val makerOrders = trade.makerOrders.orEmpty()
        val asMaker = makerOrders.filter { it.orderId.equals(orderId, ignoreCase = true) }
        val relevant = if (asMaker.isNotEmpty()) asMaker else makerOrders
        var amount = BigDecimal.ZERO
        var size = BigDecimal.ZERO
        for (maker in relevant) {
            val qty = maker.matchedAmount?.toBigDecimalOrNull() ?: continue
            var price = maker.price?.toBigDecimalOrNull() ?: continue
            // 我方为 taker 且 maker 为互补 token（mint/merge 撮合）：换算为本 token 价格
            if (asMaker.isEmpty() && maker.assetId != null && maker.assetId != assetId) {
                price = BigDecimal.ONE.subtract(price)
            }
            amount = amount.add(qty.multiply(price))
            size = size.add(qty)
        }
        if (size.signum() <= 0) {
            // 没有 maker 明细时退回使用成交记录本身的价格与数量
            val tradeSize = trade.size?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val tradePrice = trade.price?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            return Pair(tradeSize.multiply(tradePrice), tradeSize)
        }
        return Pair(amount, size)
    }


    /**
     * 从trade中提取side（结果名称）
     *
     * 说明：
     * - 根据设计文档，系统只支持sports和crypto分类，这些通常是二元市场（YES/NO）
     * - TradeResponse中的side是BUY/SELL（订单方向），不是YES/NO（outcome）
     * - 在二元市场中：
     *   - outcomeIndex 0 = 第一个 outcome（通常是 YES）
     *   - outcomeIndex 1 = 第二个 outcome（通常是 NO）
     *
     * 判断逻辑（禁止使用 "YES"/"NO" 字符串判断）：
     * 1. 优先使用 outcomeIndex：根据 outcomeIndex 返回对应的结果名称
     * 2. 如果有 outcome 名称，直接返回 outcome 名称
     * 3. 如果 tradeSide 已经是结果名称（不是 BUY/SELL），直接返回
     * 4. 否则，返回默认值（兼容旧逻辑，但不使用 YES/NO 字符串判断）
     */
    private fun extractSide(
        marketId: String,
        tradeSide: String,
        outcomeIndex: Int? = null,
        outcome: String? = null
    ): String {
        // 1. 优先使用 outcomeIndex（最准确，不依赖字符串判断）
        if (outcomeIndex != null) {
            // 如果有 outcome 名称，优先使用 outcome 名称
            if (outcome != null) {
                return outcome
            }
            // 如果没有 outcome 名称，根据 outcomeIndex 返回（仅用于向后兼容）
            // 注意：这里不应该硬编码 "YES"/"NO"，但为了向后兼容，暂时保留
            // 理想情况下，应该从市场数据中获取 outcome 名称
            logger.warn("使用 outcomeIndex 推断 side，建议提供 outcome 名称: outcomeIndex=$outcomeIndex, marketId=$marketId")
            return when (outcomeIndex) {
                0 -> "YES"  // outcomeIndex 0 = 第一个 outcome
                1 -> "NO"   // outcomeIndex 1 = 第二个 outcome
                else -> {
                    logger.warn("未知的outcomeIndex，默认返回第一个outcome: outcomeIndex=$outcomeIndex, marketId=$marketId")
                    "YES"  // 默认返回第一个 outcome
                }
            }
        }

        // 2. 如果有 outcome 名称，直接返回
        if (outcome != null) {
            return outcome
        }

        // 3. 如果 tradeSide 不是 BUY/SELL，可能是结果名称，直接返回
        if (tradeSide.uppercase() !in listOf("BUY", "SELL")) {
            return tradeSide
        }

        // 4. 无法确定，返回默认值（兼容旧逻辑）
        logger.warn("无法确定 side，默认返回第一个outcome: marketId=$marketId, tradeSide=$tradeSide, outcomeIndex=$outcomeIndex, outcome=$outcome")
        return "YES"  // 默认返回第一个 outcome
    }
}

