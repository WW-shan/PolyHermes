package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.dto.CryptoTailManualOrderRequest
import com.wrbug.polymarketbot.dto.CryptoTailManualOrderResponse
import com.wrbug.polymarketbot.dto.ManualOrderDetails
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.enums.SpreadMode
import com.wrbug.polymarketbot.enums.SpreadDirection
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.binance.BinanceKlineAutoSpreadService
import com.wrbug.polymarketbot.service.binance.BinanceKlineService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.div
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import okhttp3.Request
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import jakarta.annotation.PreDestroy
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/** 加密价差策略固定下单价格（最高价 0.99），不再在触发时拉取最优价 */
private const val TRIGGER_FIXED_PRICE = "0.99"

/** 最大价差模式（MAX）时，买入价格调整系数（加在触发价格上） */
private const val SPREAD_MAX_PRICE_ADJUSTMENT = "0.02"

/** 数量小数位数，与 OrderSigningService 的 roundConfig.size 一致 */
private const val SIZE_DECIMAL_SCALE = 2

/** 周期上下文缓存有效期（分钟），覆盖最长 15 分钟周期 */
private const val PERIOD_CONTEXT_EXPIRE_MINUTES = 20L

/** USDC / 条件代币的链上精度 */
private const val USDC_DECIMALS = 6

/** 单笔下单最小 USDC 金额（平台限制），RATIO 模式计算值低于此值时按此值下单 */
private val MIN_ORDER_USDC = BigDecimal("1")

/**
 * 周期内预置上下文：账户、API 凭证、签名类型、CLOB 客户端；不含预签订单，也不缓存明文私钥（下单时再解密）。
 * 触发时 FIXED/RATIO 均按 outcomeIndex 计算 size 并签名提交。
 */
private data class PeriodContext(
    val strategy: CryptoTailStrategy,
    val periodStartUnix: Long,
    val account: Account,
    val apiSecretDecrypted: String,
    val apiPassphraseDecrypted: String,
    val clobApi: PolymarketClobApi,
    val signatureType: Int,
    val tokenIds: List<String>,
    val marketTitle: String?
)

/**
 * 加密价差策略执行服务：按周期与时间窗口检查价格并下单，每周期最多触发一次。
 * 周期开始预置账户、解密、费率、签名类型、CLOB 客户端；触发时按 outcomeIndex 计算 size 并签名提交。
 */
@Service
class CryptoTailStrategyExecutionService(
    private val strategyRepository: CryptoTailStrategyRepository,
    private val triggerRepository: CryptoTailStrategyTriggerRepository,
    private val accountRepository: AccountRepository,
    private val accountService: AccountService,
    private val retrofitFactory: RetrofitFactory,
    private val clobService: PolymarketClobService,
    private val orderSigningService: OrderSigningService,
    private val cryptoUtils: CryptoUtils,
    private val binanceKlineService: BinanceKlineService,
    private val binanceKlineAutoSpreadService: BinanceKlineAutoSpreadService,
    private val triggerRecorder: CryptoTailTriggerRecorder
) {

    private val logger = LoggerFactory.getLogger(CryptoTailStrategyExecutionService::class.java)

    /** 查询市场 tick 用的 HTTP 客户端（公开只读接口） */
    private val tickSizeHttpClient by lazy { createClient().build() }

    /** 按 (strategyId, periodStartUnix) 加锁，避免同一周期被调度器与 WebSocket 等多路并发重复下单 */
    private val triggerMutexMap = ConcurrentHashMap<String, Mutex>()

    /** 过期锁 key 保留时间（秒），超过则清理，防止 map 无界增长 */
    private val triggerMutexExpireSeconds = 3600L

    private fun triggerLockKey(strategyId: Long, periodStartUnix: Long): String = "$strategyId-$periodStartUnix"

    private fun getTriggerMutex(strategyId: Long, periodStartUnix: Long): Mutex {
        cleanExpiredTriggerMutexKeys()
        return triggerMutexMap.getOrPut(triggerLockKey(strategyId, periodStartUnix)) { Mutex() }
    }

    /** 清理已过期的 (strategyId, periodStartUnix) 锁，避免内存泄漏 */
    private fun cleanExpiredTriggerMutexKeys() {
        val nowSeconds = System.currentTimeMillis() / 1000
        val expireThreshold = nowSeconds - triggerMutexExpireSeconds
        val keysToRemove = triggerMutexMap.keys.filter { key ->
            key.substringAfterLast('-').toLongOrNull()?.let { it < expireThreshold } ?: false
        }
        keysToRemove.forEach { triggerMutexMap.remove(it) }
    }

    /** 周期预置上下文缓存：(strategyId-periodStartUnix) -> PeriodContext，过期周期在读取时剔除 */
    private val periodContextCache: Cache<String, PeriodContext> = Caffeine.newBuilder()
        .expireAfterWrite(PERIOD_CONTEXT_EXPIRE_MINUTES, java.util.concurrent.TimeUnit.MINUTES)
        .maximumSize(1_000)
        .build()

    /** 告警节流：key -> 首次告警时间，1 分钟过期 */
    private val warnThrottleCache: Cache<String, Long> = Caffeine.newBuilder()
        .expireAfterWrite(1, java.util.concurrent.TimeUnit.MINUTES)
        .maximumSize(1_000)
        .build()

    /** 已打印「首次满足条件」日志的周期：LRU 容量 100，每周期只打一次 */
    private val conditionLoggedCache: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(100)
        .build()

    /**
     * 在周期内首次需要时构建并缓存预置上下文；失败返回 null，触发流程将走完整路径。
     * 预置：账户、解密、费率、签名类型、CLOB 客户端；不预签订单，触发时再签名。
     */
    private suspend fun ensurePeriodContext(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        tokenIds: List<String>,
        marketTitle: String?
    ): PeriodContext? {
        val key = triggerLockKey(strategy.id!!, periodStartUnix)
        periodContextCache.getIfPresent(key)?.let { return it }

        val account = accountRepository.findById(strategy.accountId).orElse(null) ?: return null
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) return null

        // 仅校验私钥可解密，不在缓存中保留明文
        if (decryptPrivateKey(account) == null) return null
        val apiSecret = try {
            account.apiSecret.let { cryptoUtils.decrypt(it) }
        } catch (e: Exception) {
            ""
        }
        val apiPassphrase = try {
            account.apiPassphrase.let { cryptoUtils.decrypt(it) }
        } catch (e: Exception) {
            ""
        }

        val clobApi = retrofitFactory.createClobApi(account.apiKey, apiSecret, apiPassphrase, account.walletAddress)
        val signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)

        if (strategy.amountMode.uppercase() != "RATIO" && strategy.amountValue < MIN_ORDER_USDC) return null

        val ctx = PeriodContext(
            strategy = strategy,
            periodStartUnix = periodStartUnix,
            account = account,
            apiSecretDecrypted = apiSecret,
            apiPassphraseDecrypted = apiPassphrase,
            clobApi = clobApi,
            signatureType = signatureType,
            tokenIds = tokenIds,
            marketTitle = marketTitle
        )
        periodContextCache.put(key, ctx)
        return ctx
    }

    /** 下单时再解密私钥，失败返回 null */
    private fun decryptPrivateKey(account: Account): String? {
        return try {
            cryptoUtils.decrypt(account.privateKey)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logger.warn("加密价差策略解密私钥失败: accountId=${account.id}", e)
            null
        }
    }

    /**
     * 按投入金额和价格计算可买张数：size = ceil(amountUsdc/price)，保留小数，至少 1。
     * 与 OrderSigningService 一致使用小数数量，向上取整保证不超过投入金额。
     */
    private fun computeSize(amountUsdc: BigDecimal, price: BigDecimal): String {
        val size = amountUsdc.divide(price, SIZE_DECIMAL_SCALE, RoundingMode.UP).max(BigDecimal.ONE)
        return size.toPlainString()
    }

    private fun getOrInvalidatePeriodContext(strategy: CryptoTailStrategy, periodStartUnix: Long): PeriodContext? {
        val key = triggerLockKey(strategy.id!!, periodStartUnix)
        val nowSeconds = System.currentTimeMillis() / 1000
        val ctx = periodContextCache.getIfPresent(key) ?: return null
        if (periodStartUnix + strategy.intervalSeconds <= nowSeconds) {
            periodContextCache.invalidate(key)
            return null
        }
        return ctx
    }

    /**
     * 由订单簿 WebSocket 触发：当收到某 token 的 bestBid 且满足区间时调用，若本周期未触发则下单。
     */
    suspend fun tryTriggerWithPriceFromWs(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        tokenIds: List<String>,
        outcomeIndex: Int,
        bestBid: BigDecimal
    ) {
        if (outcomeIndex < 0 || outcomeIndex >= tokenIds.size) return
        if (bestBid < strategy.minPrice || bestBid > strategy.maxPrice) return
        if (!CryptoTailTiming.isWithinExecutionWindow(strategy, periodStartUnix, System.currentTimeMillis())) return

        val mutex = getTriggerMutex(strategy.id!!, periodStartUnix)
        mutex.withLock {
            if (triggerRecorder.isTriggered(strategy.id!!, periodStartUnix)) return@withLock
            val logKey = triggerLockKey(strategy.id!!, periodStartUnix)
            if (conditionLoggedCache.getIfPresent(logKey) == null) {
                conditionLoggedCache.put(logKey, periodStartUnix + strategy.intervalSeconds)
                val oc = binanceKlineService.getCurrentOpenClose(
                    strategy.marketSlugPrefix,
                    strategy.intervalSeconds,
                    periodStartUnix
                )
                val openPrice = oc?.first?.toPlainString() ?: "-"
                val closePrice = oc?.second?.toPlainString() ?: "-"
                val strategyName = strategy.name?.takeIf { it.isNotBlank() } ?: "加密价差策略-${strategy.marketSlugPrefix}"
                val direction = if (outcomeIndex == 0) "Up" else "Down"
                val modeStr = if (strategy.spreadDirection == SpreadDirection.MAX) "最大价差" else "最小价差"
                logger.info(
                    "加密价差策略首次满足条件: strategyName=$strategyName, strategyId=${strategy.id}, " +
                            "openPrice=$openPrice, closePrice=$closePrice, marketPrice=${bestBid.toPlainString()}, " +
                            "direction=$direction, outcomeIndex=$outcomeIndex, spreadMode=$modeStr"
                )
            }
            if (!passSpreadCheck(strategy, periodStartUnix, outcomeIndex)) return@withLock
            // 下单前占位：唯一约束保证同一周期（跨进程/跨实例）只会有一次下单
            triggerRecorder.reserve(
                strategy.id!!, periodStartUnix, marketTitle, outcomeIndex, bestBid, BigDecimal.ZERO, "AUTO"
            ) ?: return@withLock
            try {
                ensurePeriodContext(strategy, periodStartUnix, tokenIds, marketTitle)
                placeOrderForTrigger(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, bestBid)
            } catch (e: Exception) {
                logger.error("加密价差策略触发下单流程异常: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix", e)
                triggerRecorder.getPending(strategy.id!!, periodStartUnix)?.let {
                    triggerRecorder.completeFail(it, "下单流程异常: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * 价差过滤：拿不到行情或有效阈值时一律不通过（fail-closed），并节流告警。
     * 最小价差按下单方向比较，最大价差比较绝对值，见 [CryptoTailSpreadRule]。
     */
    private fun passSpreadCheck(strategy: CryptoTailStrategy, periodStartUnix: Long, outcomeIndex: Int): Boolean {
        if (strategy.spreadMode == SpreadMode.NONE) return true
        val oc = binanceKlineService.getCurrentOpenClose(
            strategy.marketSlugPrefix,
            strategy.intervalSeconds,
            periodStartUnix
        ) ?: run {
            warnThrottled(
                "oc-${strategy.id}-$periodStartUnix",
                "加密价差策略价差校验缺少开盘/当前价，禁止下单: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix"
            )
            return false
        }
        val (openP, closeP) = oc

        val threshold = when (strategy.spreadMode) {
            SpreadMode.FIXED -> strategy.spreadValue
            SpreadMode.AUTO -> computeAutoEffectiveSpread(strategy, periodStartUnix, outcomeIndex)?.effectiveSpread
            SpreadMode.NONE -> return true
        }
        if (threshold == null || threshold < BigDecimal.ZERO) {
            warnThrottled(
                "th-${strategy.id}-$periodStartUnix-$outcomeIndex",
                "加密价差策略无有效价差阈值，禁止下单: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, " +
                    "spreadMode=${strategy.spreadMode}, outcomeIndex=$outcomeIndex"
            )
            return false
        }
        return CryptoTailSpreadRule.passes(strategy.spreadDirection, openP, closeP, outcomeIndex, threshold)
    }

    /** 同一 key 每分钟最多告警一次，避免 WS 高频消息刷屏 */
    private fun warnThrottled(key: String, message: String) {
        if (warnThrottleCache.asMap().putIfAbsent(key, System.currentTimeMillis()) == null) {
            logger.warn(message)
        }
    }

    /**
     * AUTO 模式：取 100% 基准价差，按窗口内毫秒进度计算动态系数（100%→50%）得到有效价差。
     */
    private data class AutoSpreadResult(
        val baseSpread: BigDecimal,
        val coefficient: BigDecimal,
        val effectiveSpread: BigDecimal
    )

    private fun computeAutoEffectiveSpread(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int
    ): AutoSpreadResult? {
        val baseSpread = binanceKlineAutoSpreadService.getAutoMinSpreadBase(
            strategy.marketSlugPrefix,
            strategy.intervalSeconds,
            periodStartUnix,
            outcomeIndex
        ) ?: return null
        if (baseSpread <= BigDecimal.ZERO) return null
        val windowStartMs = (periodStartUnix + strategy.windowStartSeconds) * 1000L
        val windowEndMs = (periodStartUnix + strategy.windowEndSeconds) * 1000L
        val windowLenMs = windowEndMs - windowStartMs
        val coefficient = if (windowLenMs <= 0) {
            BigDecimal.ONE
        } else {
            val nowMs = System.currentTimeMillis()
            val elapsedMs = (nowMs - windowStartMs).toBigDecimal()
            val progress = elapsedMs.div(windowLenMs.toBigDecimal(), 18, RoundingMode.HALF_UP)
                .let { p -> maxOf(BigDecimal.ZERO, minOf(BigDecimal.ONE, p)) }
            BigDecimal.ONE.subtract(progress.multi("0.5"))
        }
        val effectiveSpread = baseSpread.multi(coefficient).setScale(8, RoundingMode.HALF_UP)
        return AutoSpreadResult(baseSpread, coefficient, effectiveSpread)
    }

    private suspend fun placeOrderForTrigger(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        tokenIds: List<String>,
        outcomeIndex: Int,
        triggerPrice: BigDecimal
    ) {
        val ctx = getOrInvalidatePeriodContext(strategy, periodStartUnix)

        if (ctx != null) {
            var availableBalanceForRatio = BigDecimal.ZERO
            var amountUsdc = when (strategy.amountMode.uppercase()) {
                "RATIO" -> {
                    val balanceResult = accountService.getAccountBalance(ctx.account.id)
                    val availableBalance =
                        balanceResult.getOrNull()?.availableBalance?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    availableBalanceForRatio = availableBalance
                    availableBalance.multiply(strategy.amountValue).divide(BigDecimal("100"), 18, RoundingMode.DOWN)
                }

                else -> strategy.amountValue
            }
            if (amountUsdc < MIN_ORDER_USDC) {
                val amountMode = strategy.amountMode.uppercase()
                if (amountMode == "RATIO" && availableBalanceForRatio >= MIN_ORDER_USDC) {
                    amountUsdc = MIN_ORDER_USDC
                } else {
                    saveTriggerRecord(
                        strategy,
                        periodStartUnix,
                        marketTitle,
                        outcomeIndex,
                        triggerPrice,
                        amountUsdc,
                        null,
                        "fail",
                        "投入金额不足"
                    )
                    return
                }
            }

            val tokenId = tokenIds.getOrNull(outcomeIndex) ?: run {
                saveTriggerRecord(
                    strategy,
                    periodStartUnix,
                    marketTitle,
                    outcomeIndex,
                    triggerPrice,
                    amountUsdc,
                    null,
                    "fail",
                    "tokenIds 越界"
                )
                return
            }

            // 根据价差方向确定下单价格
            val price = if (strategy.spreadDirection == SpreadDirection.MAX) {
                // 最大价差模式：触发价格 + 0.02
                triggerPrice.add(BigDecimal(SPREAD_MAX_PRICE_ADJUSTMENT)).setScale(8, RoundingMode.HALF_UP)
            } else {
                // 最小价差模式：固定价格 0.99
                BigDecimal(TRIGGER_FIXED_PRICE)
            }
            val priceStr = price.toPlainString()
            val size = computeSize(amountUsdc, price)
            val privateKey = decryptPrivateKey(ctx.account) ?: run {
                saveTriggerRecord(
                    strategy,
                    periodStartUnix,
                    marketTitle,
                    outcomeIndex,
                    triggerPrice,
                    amountUsdc,
                    null,
                    "fail",
                    "解密私钥失败"
                )
                return
            }
            val signedOrder = orderSigningService.createAndSignOrder(
                privateKey = privateKey,
                makerAddress = ctx.account.proxyAddress,
                tokenId = tokenId,
                side = "BUY",
                price = priceStr,
                size = size,
                signatureType = ctx.signatureType
            )
            val orderRequest = NewOrderRequest(
                order = signedOrder,
                owner = ctx.account.apiKey!!,
                orderType = "FAK"
            )
            submitOrderAndSaveRecord(
                ctx.clobApi,
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                amountUsdc,
                orderRequest,
                triggerType = "AUTO"
            )
            return
        }

        placeOrderForTriggerSlowPath(strategy, periodStartUnix, marketTitle, tokenIds, outcomeIndex, triggerPrice)
    }

    private suspend fun submitOrderAndSaveRecord(
        clobApi: PolymarketClobApi,
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        outcomeIndex: Int,
        triggerPrice: BigDecimal,
        amountUsdc: BigDecimal,
        orderRequest: NewOrderRequest,
        triggerType: String = "AUTO"
    ) {
        var failReason: String? = null
        // 加锁、解密、签名都会耗时，提交前再校验一次窗口，避免越过窗口末端才进入撮合
        if (triggerType == "AUTO" &&
            !CryptoTailTiming.isWithinExecutionWindow(strategy, periodStartUnix, System.currentTimeMillis())
        ) {
            saveTriggerRecord(
                strategy, periodStartUnix, marketTitle, outcomeIndex, triggerPrice, amountUsdc,
                null, "fail", "提交前已超出时间窗口", triggerType = triggerType
            )
            logger.warn("加密价差策略提交前已超出时间窗口，放弃下单: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix")
            return
        }
        try {
            val response = clobApi.createOrder(orderRequest)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.orderId != null) {
                    saveTriggerRecord(
                        strategy,
                        periodStartUnix,
                        marketTitle,
                        outcomeIndex,
                        triggerPrice,
                        amountUsdc,
                        body.orderId,
                        "success",
                        null,
                        triggerType = triggerType,
                        transactionHashes = body.transactionsHashes
                    )
                    logger.info("加密价差策略下单成功: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, outcomeIndex=$outcomeIndex, orderId=${body.orderId}, triggerType=$triggerType")
                    return
                }
                failReason = body.errorMsg ?: "unknown"
            } else {
                val errorBody = response.errorBody()?.string().orEmpty()
                failReason = errorBody.ifEmpty { "请求失败" }
            }
        } catch (e: Exception) {
            failReason = e.message ?: e.toString()
            logger.error("加密价差策略下单异常: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix", e)
        }
        saveTriggerRecord(
            strategy,
            periodStartUnix,
            marketTitle,
            outcomeIndex,
            triggerPrice,
            amountUsdc,
            null,
            "fail",
            failReason,
            triggerType = triggerType
        )
        logger.error("加密价差策略下单失败: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, reason=$failReason")
    }

    /** 无预置上下文时的完整流程：固定价格 0.99，账户/解密/费率/签名在触发时执行 */
    private suspend fun placeOrderForTriggerSlowPath(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        tokenIds: List<String>,
        outcomeIndex: Int,
        triggerPrice: BigDecimal
    ) {
        val account = accountRepository.findById(strategy.accountId).orElse(null) ?: run {
            logger.warn("账户不存在: accountId=${strategy.accountId}")
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                BigDecimal.ZERO,
                null,
                "fail",
                "账户不存在"
            )
            return
        }
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
            logger.warn("账户未配置 API 凭证: accountId=${account.id}")
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                BigDecimal.ZERO,
                null,
                "fail",
                "账户未配置API凭证"
            )
            return
        }

        val balanceResult = accountService.getAccountBalance(account.id)
        val availableBalance = balanceResult.getOrNull()?.availableBalance?.toSafeBigDecimal() ?: BigDecimal.ZERO
        var amountUsdc = when (strategy.amountMode.uppercase()) {
            "RATIO" -> availableBalance.multiply(strategy.amountValue).divide(BigDecimal("100"), 18, RoundingMode.DOWN)
            else -> strategy.amountValue
        }
        if (amountUsdc < MIN_ORDER_USDC) {
            val amountMode = strategy.amountMode.uppercase()
            if (amountMode == "RATIO" && availableBalance >= MIN_ORDER_USDC) {
                amountUsdc = MIN_ORDER_USDC
            } else {
                saveTriggerRecord(
                    strategy,
                    periodStartUnix,
                    marketTitle,
                    outcomeIndex,
                    triggerPrice,
                    amountUsdc,
                    null,
                    "fail",
                    "投入金额不足"
                )
                return
            }
        }

        val tokenId = tokenIds.getOrNull(outcomeIndex) ?: run {
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                amountUsdc,
                null,
                "fail",
                "tokenIds 越界"
            )
            return
        }

        // 根据价差方向确定下单价格
        val price = if (strategy.spreadDirection == SpreadDirection.MAX) {
            // 最大价差模式：触发价格 + 0.02
            triggerPrice.add(BigDecimal(SPREAD_MAX_PRICE_ADJUSTMENT)).setScale(8, RoundingMode.HALF_UP)
        } else {
            // 最小价差模式：固定价格 0.99
            BigDecimal(TRIGGER_FIXED_PRICE)
        }
        val priceStr = price.toPlainString()
        val size = computeSize(amountUsdc, price)

        val decryptedKey = decryptPrivateKey(account) ?: run {
            saveTriggerRecord(
                strategy,
                periodStartUnix,
                marketTitle,
                outcomeIndex,
                triggerPrice,
                amountUsdc,
                null,
                "fail",
                "解密私钥失败"
            )
            return
        }
        val apiSecret = try {
            account.apiSecret.let { cryptoUtils.decrypt(it) }
        } catch (e: Exception) {
            ""
        }
        val apiPassphrase = try {
            account.apiPassphrase.let { cryptoUtils.decrypt(it) }
        } catch (e: Exception) {
            ""
        }
        val clobApi = retrofitFactory.createClobApi(account.apiKey, apiSecret, apiPassphrase, account.walletAddress)
        val signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)

        val signedOrder = orderSigningService.createAndSignOrder(
            privateKey = decryptedKey,
            makerAddress = account.proxyAddress,
            tokenId = tokenId,
            side = "BUY",
            price = priceStr,
            size = size,
            signatureType = signatureType
        )
        val orderRequest = NewOrderRequest(
            order = signedOrder,
            owner = account.apiKey!!,
            orderType = "FAK"
        )
        submitOrderAndSaveRecord(
            clobApi,
            strategy,
            periodStartUnix,
            marketTitle,
            outcomeIndex,
            triggerPrice,
            amountUsdc,
            orderRequest
        )
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

    private fun parseClobTokenIds(clobTokenIds: String?): List<String> {
        if (clobTokenIds.isNullOrBlank()) return emptyList()
        val parsed = clobTokenIds.fromJson<List<String>>()
        return parsed ?: emptyList()
    }

    /**
     * 回写本周期的占位记录（下单前已由 [CryptoTailTriggerRecorder.reserve] 插入 pending）。
     * 成功时先打内存已下单标记再写库，写库失败不会改写成 fail。
     */
    private fun saveTriggerRecord(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        outcomeIndex: Int,
        triggerPrice: BigDecimal,
        amountUsdc: BigDecimal,
        orderId: String?,
        status: String,
        failReason: String?,
        triggerType: String = "AUTO",
        transactionHashes: List<String>? = null
    ) {
        val reserved = triggerRecorder.getPending(strategy.id!!, periodStartUnix)
        if (reserved == null) {
            logger.error(
                "加密价差策略回写触发记录时找不到占位记录: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, " +
                    "status=$status, orderId=$orderId, triggerType=$triggerType"
            )
            return
        }
        if (status == CryptoTailTriggerRecorder.STATUS_SUCCESS && orderId != null) {
            triggerRecorder.completeSuccess(reserved, orderId, transactionHashes, triggerPrice, amountUsdc)
        } else {
            triggerRecorder.completeFail(reserved, failReason, triggerPrice, amountUsdc)
        }
    }

    /**
     * 手动下单：用户主动触发，不检查价格/价差条件。
     * 服务端自行按策略 slug 前缀 + 当前周期解析市场 tokenIds，拒绝非当前周期或已收盘周期的下单；
     * 前端传入的 tokenIds 只做一致性校验。价格必须落在市场 tick 上，数量/金额按签名口径计算并返回实际签名值。
     */
    suspend fun manualOrder(request: CryptoTailManualOrderRequest): Result<CryptoTailManualOrderResponse> {
        return try {
            val strategy = strategyRepository.findById(request.strategyId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("策略不存在"))

            val direction = request.direction.uppercase()
            if (direction != "UP" && direction != "DOWN") {
                return Result.failure(CryptoTailManualOrderException("方向仅支持 UP 或 DOWN"))
            }
            val outcomeIndex = if (direction == "UP") 0 else 1

            val nowMs = System.currentTimeMillis()
            val periodStartUnix = CryptoTailTiming.currentPeriodStart(strategy.intervalSeconds, nowMs)
            if (request.periodStartUnix != periodStartUnix) {
                return Result.failure(CryptoTailManualOrderException("非当前周期，不能下单"))
            }
            if (!CryptoTailTiming.isBeforeMarketClose(strategy.intervalSeconds, periodStartUnix, nowMs)) {
                return Result.failure(CryptoTailManualOrderException("当前周期即将结束，不能下单"))
            }

            val slug = "${strategy.marketSlugPrefix}-$periodStartUnix"
            val event = fetchEventBySlug(slug).getOrElse {
                return Result.failure(CryptoTailManualOrderException("获取当前周期市场失败: ${it.message}"))
            }
            val tokenIds = parseClobTokenIds(event.markets?.firstOrNull()?.clobTokenIds)
            if (tokenIds.size < 2) {
                return Result.failure(CryptoTailManualOrderException("当前周期市场 token 不可用"))
            }
            if (request.tokenIds.isNotEmpty() && request.tokenIds != tokenIds) {
                return Result.failure(CryptoTailManualOrderException("tokenIds 与当前周期市场不一致"))
            }
            val tokenId = tokenIds[outcomeIndex]
            val marketTitle = event.title?.takeIf { it.isNotBlank() } ?: request.marketTitle.ifBlank { null }

            val price = request.price.trim().toBigDecimalOrNull()
                ?: return Result.failure(CryptoTailManualOrderException("价格格式错误"))
            val sizeInput = request.size.trim().toBigDecimalOrNull()
                ?: return Result.failure(CryptoTailManualOrderException("数量格式错误"))
            val tick = CryptoTailManualOrderPricing.effectiveTick(fetchMarketTickSize(tokenId))
            val quote = CryptoTailManualOrderPricing.quote(price, sizeInput, tick)
                ?: return Result.failure(
                    CryptoTailManualOrderException("价格必须是 ${tick.toPlainString()} 的整数倍，且在 ${tick.toPlainString()}~${BigDecimal.ONE.subtract(tick).toPlainString()} 之间")
                )
            if (quote.size < BigDecimal.ONE) {
                return Result.failure(CryptoTailManualOrderException("数量不能少于 1"))
            }
            if (quote.amountUsdc < MIN_ORDER_USDC) {
                return Result.failure(CryptoTailManualOrderException("总金额不能少于 \$1"))
            }

            val mutex = getTriggerMutex(strategy.id!!, periodStartUnix)
            mutex.withLock {
                val reserved = triggerRecorder.reserve(
                    strategy.id!!, periodStartUnix, marketTitle, outcomeIndex, quote.price, quote.amountUsdc, "MANUAL"
                ) ?: return@withLock Result.failure(CryptoTailManualOrderException("当前周期已下单"))
                try {
                    placeManualOrder(strategy, periodStartUnix, marketTitle, tokenIds, tokenId, outcomeIndex, direction, quote, reserved)
                } catch (e: Exception) {
                    logger.error("手动下单流程异常: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix", e)
                    triggerRecorder.completeFail(reserved, "手动下单异常: ${e.message ?: e.javaClass.simpleName}")
                    Result.failure(e)
                }
            }
        } catch (e: Exception) {
            logger.error("手动下单异常: strategyId=${request.strategyId}, ${e.message}", e)
            Result.failure(e)
        }
    }

    /** 手动下单：签名并提交，校验签名金额与报价一致，回写占位记录 */
    private suspend fun placeManualOrder(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        marketTitle: String?,
        tokenIds: List<String>,
        tokenId: String,
        outcomeIndex: Int,
        direction: String,
        quote: CryptoTailManualOrderPricing.Quote,
        reserved: CryptoTailStrategyTrigger
    ): Result<CryptoTailManualOrderResponse> {
        val ctx = getOrInvalidatePeriodContext(strategy, periodStartUnix)
            ?: ensurePeriodContext(strategy, periodStartUnix, tokenIds, marketTitle)
        if (ctx == null) {
            triggerRecorder.release(reserved)
            return Result.failure(IllegalArgumentException("账户未配置或凭证不足"))
        }
        val privateKey = decryptPrivateKey(ctx.account)
        if (privateKey == null) {
            triggerRecorder.release(reserved)
            return Result.failure(IllegalArgumentException("解密私钥失败"))
        }
        val priceStr = quote.price.toPlainString()
        val sizeStr = quote.size.toPlainString()
        val signedOrder = orderSigningService.createAndSignOrder(
            privateKey = privateKey,
            makerAddress = ctx.account.proxyAddress,
            tokenId = tokenId,
            side = "BUY",
            price = priceStr,
            size = sizeStr,
            signatureType = ctx.signatureType,
            tickSize = quote.tick,
            strictTick = true
        )
        // 签名器会按自身精度处理价格/数量，这里用签名结果反算，确保展示给用户的金额就是实际签名金额
        val signedAmount = BigDecimal(signedOrder.makerAmount).movePointLeft(USDC_DECIMALS)
        val signedShares = BigDecimal(signedOrder.takerAmount).movePointLeft(USDC_DECIMALS)
        if (signedAmount.compareTo(quote.amountUsdc) != 0 || signedShares.compareTo(quote.size) != 0) {
            logger.error(
                "手动下单签名金额与报价不一致，拒绝提交: strategyId=${strategy.id}, quote=$quote, " +
                    "signedAmount=${signedAmount.toPlainString()}, signedShares=${signedShares.toPlainString()}"
            )
            triggerRecorder.release(reserved)
            return Result.failure(CryptoTailManualOrderException("签名金额与报价不一致，已拒绝下单"))
        }
        if (!CryptoTailTiming.isBeforeMarketClose(strategy.intervalSeconds, periodStartUnix, System.currentTimeMillis())) {
            triggerRecorder.release(reserved)
            return Result.failure(CryptoTailManualOrderException("当前周期即将结束，不能下单"))
        }
        val orderRequest = NewOrderRequest(
            order = signedOrder,
            owner = ctx.account.apiKey!!,
            orderType = "FAK"
        )
        return submitOrderForManualOrder(ctx.clobApi, strategy, periodStartUnix, outcomeIndex, quote, orderRequest, reserved)
            .map { orderId ->
                CryptoTailManualOrderResponse(
                    success = true,
                    orderId = orderId,
                    message = "下单成功",
                    orderDetails = ManualOrderDetails(
                        strategyId = strategy.id!!,
                        direction = direction,
                        price = priceStr,
                        size = signedShares.stripTrailingZeros().toPlainString(),
                        totalAmount = signedAmount.setScale(CryptoTailManualOrderPricing.AMOUNT_SCALE).toPlainString()
                    )
                )
            }
    }

    /**
     * 提交手动订单。CLOB 明确拒绝（2xx 且 success=false，或 4xx）时释放占位允许重试；
     * 结果未知（5xx、异常、无 orderId）时记为 fail 并保留占位，避免重复下单。
     */
    private suspend fun submitOrderForManualOrder(
        clobApi: PolymarketClobApi,
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        outcomeIndex: Int,
        quote: CryptoTailManualOrderPricing.Quote,
        orderRequest: NewOrderRequest,
        reserved: CryptoTailStrategyTrigger
    ): Result<String> {
        return try {
            val response = clobApi.createOrder(orderRequest)
            val body = response.body()
            if (response.isSuccessful && body != null && body.success && body.orderId != null) {
                triggerRecorder.completeSuccess(reserved, body.orderId, body.transactionsHashes, quote.price, quote.amountUsdc)
                logger.info("手动下单成功: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, outcomeIndex=$outcomeIndex, orderId=${body.orderId}")
                return Result.success(body.orderId)
            }
            val reason = if (body != null) body.getErrorMessage() else response.errorBody()?.string().orEmpty().ifEmpty { "请求失败 code=${response.code()}" }
            val safeReason = CryptoTailTriggerRecorder.truncate(reason, CryptoTailTriggerRecorder.FAIL_REASON_MAX_LENGTH)
            val definitelyRejected = (response.isSuccessful && body != null && !body.success) || response.code() in 400..499
            if (definitelyRejected) {
                triggerRecorder.release(reserved)
            } else {
                triggerRecorder.completeFail(reserved, safeReason)
            }
            logger.error("手动下单失败: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix, code=${response.code()}, reason=$safeReason")
            Result.failure(Exception(safeReason))
        } catch (e: Exception) {
            logger.error("手动下单异常: strategyId=${strategy.id}, periodStartUnix=$periodStartUnix", e)
            triggerRecorder.completeFail(reserved, e.message ?: e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    /** 查询市场 tick（CLOB /tick-size 公开接口）；失败返回 null，由调用方使用默认 0.01 */
    private suspend fun fetchMarketTickSize(tokenId: String): BigDecimal? = withContext(Dispatchers.IO) {
        try {
            val url = "${PolymarketConstants.CLOB_BASE_URL}/tick-size?token_id=$tokenId"
            tickSizeHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = resp.body?.string()?.fromJson<JsonObject>() ?: return@withContext null
                json.get("minimum_tick_size")?.takeIf { !it.isJsonNull }?.asString?.toBigDecimalOrNull()
                    ?.takeIf { it > BigDecimal.ZERO }
            }
        } catch (e: Exception) {
            logger.warn("查询市场 tick 失败，使用默认 0.01: tokenId=$tokenId, ${e.message}")
            null
        }
    }

    @PreDestroy
    fun destroy() {
        // 清理所有周期上下文缓存，避免敏感信息（明文私钥、API Secret）在内存中保留
        periodContextCache.invalidateAll()
        // 清理所有锁，避免内存泄漏
        triggerMutexMap.clear()
        logger.debug("加密价差策略执行服务已清理缓存和锁")
    }
}
