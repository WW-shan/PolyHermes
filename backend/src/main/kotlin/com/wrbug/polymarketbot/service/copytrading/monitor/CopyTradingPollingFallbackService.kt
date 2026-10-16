package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import com.wrbug.polymarketbot.util.RetrofitFactory
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 跟单轮询兜底服务
 *
 * Activity WS 与 OnChain WS 是跟单的实时识别来源。两者同时不可用时（Polymarket 服务抖动、网络中断、
 * RPC 节点全部不可用等），跟单会静默停止。本服务通过 Polymarket Data API 的 `/activity` 接口按
 * `pollIntervalSeconds` 拉取 Leader 成交，作为第三条识别来源，保证兜底期间不漏单。
 *
 * 关键设计：
 * - 轮询只在必要时启用：两条 WS 均不可用，或配置显式设置 `useWebSocket=false`（用户主动选择轮询）。
 * - tradeId 复用 [OnChainWsUtils.buildTradeId]，与 Activity WS / OnChain WS 完全一致，跨来源去重生效。
 * - 同一 (txHash, asset, side) 的多笔成交按与 Activity WS 相同的语义聚合为一笔（数量求和、价格加权平均）。
 * - 查询窗口留 [settleDelayMs] 的沉降延迟，让 WS 先有机会交付，避免与 WS 抢单。
 * - 只有整个窗口全部交付成功才推进游标，失败时下轮重读，已成功交易由 `processed_trade` 去重。
 */
@Service
class CopyTradingPollingFallbackService(
    private val copyTradingRepository: CopyTradingRepository,
    private val leaderRepository: LeaderRepository,
    private val accountRepository: AccountRepository,
    private val copyOrderTrackingService: CopyOrderTrackingService,
    private val retrofitFactory: RetrofitFactory,
    private val activityWsService: PolymarketActivityWsService,
    private val onChainWsService: OnChainWsService,
    @Value("\${copy.trading.polling.enabled:true}") private val pollingEnabled: Boolean = true,
    @Value("\${copy.trading.polling.interval:2000}") private val defaultIntervalMs: Long = 2_000L,
    @Value("\${copy.trading.polling.settle-delay-ms:3000}") private val settleDelayMs: Long = 3_000L,
    @Value("\${copy.trading.polling.lookback-seconds:900}") private val lookbackSeconds: Long = 900L,
    @Value("\${copy.trading.polling.overlap-seconds:30}") private val overlapSeconds: Long = 30L,
    @Value("\${copy.trading.polling.batch-limit:200}") private val batchLimit: Int = 200,
    @Value("\${copy.trading.polling.alert-interval-ms:300000}") private val alertIntervalMs: Long = 300_000L
) {

    private val logger = LoggerFactory.getLogger(CopyTradingPollingFallbackService::class.java)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 每个 Leader 上次成功轮询到的窗口结束时间（毫秒），作为下次查询的游标 */
    private val leaderCursors = ConcurrentHashMap<Long, Long>()

    /** 每个 Leader 上次发起轮询的时间（毫秒），用于按配置间隔限频 */
    private val lastAttemptAt = ConcurrentHashMap<Long, Long>()

    /** 兜底模式告警节流 */
    private val lastFallbackAlertAt = AtomicLong(0L)

    private val pollCycles = AtomicLong(0L)
    private val polledLeaders = AtomicLong(0L)
    private val deliveredTrades = AtomicLong(0L)
    private val failedTrades = AtomicLong(0L)
    private val apiFailures = AtomicLong(0L)

    @Volatile
    private var pollingJob: Job? = null

    @Volatile
    private var fallbackActive = false

    /** 可注入时钟，便于测试 */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    @Scheduled(fixedDelayString = "\${copy.trading.polling.tick-ms:1000}")
    fun pollLeaderTrades() {
        if (!pollingEnabled) return
        val previous = pollingJob
        if (previous != null && previous.isActive) return
        pollingJob = scope.launch {
            try {
                pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("跟单轮询兜底执行失败: ${e.message}", e)
            } finally {
                pollingJob = null
            }
        }
    }

    /**
     * 单次轮询：按 Leader 汇总启用配置，决定是否轮询、以什么间隔轮询，并交付窗口内的成交
     */
    internal suspend fun pollOnce() {
        val now = clock()
        val enabledConfigs = copyTradingRepository.findByEnabledTrue()
        if (enabledConfigs.isEmpty()) {
            leaderCursors.clear()
            lastAttemptAt.clear()
            markWebSocketHealthy()
            return
        }
        pollCycles.incrementAndGet()
        val wsHealthy = isWebSocketHealthy()
        if (wsHealthy) {
            markWebSocketHealthy()
        } else {
            alertFallbackActive(now)
        }
        val configsByLeader = enabledConfigs.groupBy { it.leaderId }
        val systemAddresses = systemAddresses()
        for ((leaderId, configs) in configsByLeader) {
            if (!shouldPollLeader(configs, wsHealthy)) continue
            val intervalMs = pollIntervalMs(configs)
            val lastAttempt = lastAttemptAt[leaderId] ?: 0L
            if (now - lastAttempt < intervalMs) continue
            lastAttemptAt[leaderId] = now
            val leader = leaderRepository.findById(leaderId).orElse(null) ?: continue
            if (leader.leaderAddress.lowercase() in systemAddresses) {
                logger.warn("轮询兜底跳过本系统账户地址，防止自跟单: leaderId=$leaderId, address=${leader.leaderAddress}")
                continue
            }
            pollLeader(leader, now)
        }
        val activeLeaderIds = configsByLeader.keys
        leaderCursors.keys.retainAll(activeLeaderIds)
        lastAttemptAt.keys.retainAll(activeLeaderIds)
    }

    /**
     * 是否对该 Leader 启用轮询：
     * - 两条 WS 均不可用时始终轮询（兜底）
     * - 配置显式关闭 useWebSocket 时始终轮询（用户主动选择轮询）
     * - 否则交由 WS 负责，避免无谓的重复拉取
     */
    internal fun shouldPollLeader(configs: List<CopyTrading>, wsHealthy: Boolean): Boolean {
        if (!wsHealthy) return true
        return configs.any { !it.useWebSocket }
    }

    /** 该 Leader 的轮询间隔：取所有启用配置中最小的 pollIntervalSeconds（秒），无有效值时用默认值 */
    internal fun pollIntervalMs(configs: List<CopyTrading>): Long {
        val seconds = configs.map { it.pollIntervalSeconds }.filter { it > 0 }.minOrNull()
        return seconds?.times(1000L) ?: defaultIntervalMs
    }

    private suspend fun pollLeader(leader: Leader, now: Long) {
        val leaderId = leader.id ?: return
        val windowEndMs = now - settleDelayMs
        val cursor = leaderCursors[leaderId]
        val windowStartMs = cursor?.minus(overlapSeconds * 1000L) ?: (windowEndMs - lookbackSeconds * 1000L)
        if (windowEndMs <= windowStartMs) return
        polledLeaders.incrementAndGet()
        val activities = fetchActivities(leader, windowStartMs, windowEndMs) ?: return
        if (activities.isEmpty()) {
            leaderCursors[leaderId] = windowEndMs
            return
        }
        var allDelivered = true
        for ((tradeId, fills) in aggregateByTradeId(activities)) {
            val trade = buildAggregatedTrade(tradeId, fills)
            if (trade == null) {
                logger.warn("轮询兜底无法构建成交，跳过: leaderId=$leaderId, tradeId=$tradeId")
                continue
            }
            val delivered = try {
                copyOrderTrackingService.processTrade(leaderId = leaderId, trade = trade, source = SOURCE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (delivered.isSuccess) {
                deliveredTrades.incrementAndGet()
            } else {
                allDelivered = false
                failedTrades.incrementAndGet()
                logger.warn(
                    "轮询兜底交付交易失败，下轮重读该窗口: leaderId=$leaderId, tradeId=$tradeId, error=${delivered.exceptionOrNull()?.message}"
                )
            }
        }
        // 只有整个窗口全部交付成功才推进游标。失败时把窗口起点保留为重试下限，
        // 否则窗口会随时间前移，失败的成交最终滑出查询范围被永久丢弃；
        // 同时用回溯窗口限制下限，避免失败长期存在时窗口无限增长。
        if (allDelivered) {
            leaderCursors[leaderId] = windowEndMs
        } else {
            val retryFloor = leaderCursors[leaderId] ?: windowStartMs
            leaderCursors[leaderId] = retryFloor.coerceAtLeast(windowEndMs - lookbackSeconds * 1000L)
        }
    }

    private suspend fun fetchActivities(
        leader: Leader,
        windowStartMs: Long,
        windowEndMs: Long
    ): List<UserActivityResponse>? {
        return try {
            val response = retrofitFactory.createDataApi().getUserActivity(
                user = leader.leaderAddress,
                type = listOf("TRADE"),
                start = windowStartMs / 1000,
                end = windowEndMs / 1000,
                limit = batchLimit.coerceIn(1, 500),
                offset = null,
                sortBy = "TIMESTAMP",
                sortDirection = "ASC"
            )
            if (!response.isSuccessful || response.body() == null) {
                apiFailures.incrementAndGet()
                logger.warn(
                    "轮询兜底查询 Leader 活动失败: leaderId=${leader.id}, code=${response.code()}, message=${response.message()}"
                )
                null
            } else {
                response.body().orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            apiFailures.incrementAndGet()
            logger.warn("轮询兜底查询 Leader 活动异常: leaderId=${leader.id}, error=${e.message}")
            null
        }
    }

    /**
     * 按 tradeId 聚合：与 Activity WS 的聚合语义一致，同一 (txHash, asset, side) 的多笔成交合并为一笔
     */
    internal fun aggregateByTradeId(activities: List<UserActivityResponse>): Map<String, List<UserActivityResponse>> {
        val grouped = LinkedHashMap<String, MutableList<UserActivityResponse>>()
        for (activity in activities) {
            val tradeId = tradeIdOf(activity) ?: continue
            grouped.getOrPut(tradeId) { mutableListOf() }.add(activity)
        }
        return grouped
    }

    /** 与 WS 路径一致的成交 ID；非 TRADE 或缺 txHash/asset/side 时返回 null */
    internal fun tradeIdOf(activity: UserActivityResponse): String? {
        if (!activity.type.equals("TRADE", ignoreCase = true)) return null
        val txHash = activity.transactionHash?.takeIf { it.isNotBlank() } ?: return null
        val asset = activity.asset?.takeIf { it.isNotBlank() } ?: return null
        val side = activity.side?.takeIf { it.isNotBlank() } ?: return null
        return OnChainWsUtils.buildTradeId(txHash, asset, side)
    }

    /**
     * 把同一 tradeId 的多笔成交聚合成一笔 [TradeResponse]：数量求和、价格按数量加权平均、时间取最早
     */
    internal fun buildAggregatedTrade(tradeId: String, fills: List<UserActivityResponse>): TradeResponse? {
        val first = fills.firstOrNull() ?: return null
        val side = first.side?.takeIf { it.isNotBlank() }?.uppercase() ?: return null
        var totalSize = BigDecimal.ZERO
        var totalNotional = BigDecimal.ZERO
        var earliestEventTimeMs = Long.MAX_VALUE
        for (fill in fills) {
            val size = fill.size?.let { BigDecimal.valueOf(it) } ?: continue
            val price = fill.price?.let { BigDecimal.valueOf(it) } ?: continue
            if (size.signum() <= 0) continue
            totalSize = totalSize.add(size)
            totalNotional = totalNotional.add(price.multiply(size))
            val eventTimeMs = normalizeTimestamp(fill.timestamp)
            if (eventTimeMs < earliestEventTimeMs) earliestEventTimeMs = eventTimeMs
        }
        if (totalSize.signum() <= 0) return null
        val averagePrice = totalNotional.divide(totalSize, 8, RoundingMode.HALF_UP)
        val timestampMs = if (earliestEventTimeMs == Long.MAX_VALUE) clock() else earliestEventTimeMs
        return TradeResponse(
            id = tradeId,
            market = first.conditionId,
            side = side,
            price = averagePrice.stripTrailingZeros().toPlainString(),
            size = totalSize.stripTrailingZeros().toPlainString(),
            timestamp = timestampMs.toString(),
            user = null,
            outcomeIndex = first.outcomeIndex,
            outcome = first.outcome,
            tokenId = first.asset
        )
    }

    /** Data API 时间戳为秒，链上/WS 为毫秒，统一为毫秒 */
    private fun normalizeTimestamp(value: Long): Long {
        return if (value < 10_000_000_000L) value * 1000 else value
    }

    private fun isWebSocketHealthy(): Boolean {
        return activityWsService.isConnected() || onChainWsService.isConnected()
    }

    private fun systemAddresses(): Set<String> {
        return accountRepository.findAll()
            .flatMap { listOf(it.proxyAddress, it.walletAddress) }
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .toSet()
    }

    private fun alertFallbackActive(now: Long) {
        fallbackActive = true
        val last = lastFallbackAlertAt.get()
        if (now - last >= alertIntervalMs && lastFallbackAlertAt.compareAndSet(last, now)) {
            logger.warn(
                "跟单 Activity WS 与 OnChain WS 均不可用，已启用 Data API 轮询兜底。请检查网络、代理与 RPC 节点状态。"
            )
        }
    }

    private fun markWebSocketHealthy() {
        if (fallbackActive) {
            fallbackActive = false
            logger.info("跟单 WebSocket 已恢复，轮询兜底退出（仅 useWebSocket=false 的配置继续轮询）")
        }
    }

    /**
     * 轮询兜底运行状态，供运维/健康检查使用
     */
    fun getPollingStats(): Map<String, Any> {
        return mapOf(
            "enabled" to pollingEnabled,
            "fallbackActive" to fallbackActive,
            "pollCycles" to pollCycles.get(),
            "polledLeaders" to polledLeaders.get(),
            "deliveredTrades" to deliveredTrades.get(),
            "failedTrades" to failedTrades.get(),
            "apiFailures" to apiFailures.get(),
            "trackedLeaders" to leaderCursors.size
        )
    }

    @PreDestroy
    fun destroy() {
        logger.info("跟单轮询兜底统计: ${getPollingStats()}")
        scope.cancel()
    }

    companion object {
        /** 轮询来源标识，与 processed_trade.source 对应 */
        const val SOURCE = "data-api-poll"
    }
}
