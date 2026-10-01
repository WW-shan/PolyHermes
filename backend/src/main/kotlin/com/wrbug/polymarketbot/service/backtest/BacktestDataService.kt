package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.dto.TradeData
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 基于 start 游标的一批历史交易结果
 * @param trades 本批交易列表（已按时间升序）
 * @param nextCursorSeconds 下一页游标（API 的 start 参数，秒级）；原始返回不足 limit 条时为 null 表示最后一页
 */
data class LeaderTradesBatchResult(
    val trades: List<TradeData>,
    val nextCursorSeconds: Long?
)

/**
 * 回测数据服务
 * 直接从 Polymarket Data API 获取 Leader 历史交易，使用 start 游标分页（避免 offset 过大报错）
 */
@Service
class BacktestDataService(
    private val leaderRepository: LeaderRepository,
    private val retrofitFactory: RetrofitFactory
) {
    private val logger = LoggerFactory.getLogger(BacktestDataService::class.java)

    /**
     * 按 start 游标获取一批 Leader 历史交易
     * 规则：
     * - API 的 start 按秒且包含起点那一秒；是否有下一页按**原始返回条数 == limit** 判断（不能用过滤后的条数）
     * - 有下一页时，本批丢弃时间戳等于原始最大秒的交易，下一页从该秒重新拉取，保证同一秒（同一 tx 的多笔成交）完整落在一批内，避免跨批重复/拆分
     * - 若整批都在同一秒（无法前进），用 offset 在该秒内继续翻页取全，下一页游标为该秒 + 1，避免死循环
     * - 同一 tx 同 asset 同 side 的多笔成交聚合为一笔（数量求和、价格按数量加权）
     *
     * @param leaderId Leader ID
     * @param startTime 回测开始时间（毫秒）
     * @param endTime 回测结束时间（毫秒）
     * @param cursorStartSeconds 本页游标（API 的 start，秒）；首次传 startTime/1000
     * @param limit 每批条数，建议 500
     * @return 本批交易与下一页游标（null 表示没有下一页）
     */
    suspend fun getLeaderHistoricalTradesBatch(
        leaderId: Long,
        startTime: Long,
        endTime: Long,
        cursorStartSeconds: Long,
        limit: Int
    ): LeaderTradesBatchResult {
        logger.info("获取 Leader 历史交易批次: leaderId=$leaderId, cursorStart=$cursorStartSeconds, limit=$limit")

        val leader = leaderRepository.findById(leaderId).orElse(null)
            ?: throw IllegalArgumentException("Leader 不存在: $leaderId")

        val endSeconds = endTime / 1000
        val activities = fetchActivitiesWithRetry(leader.leaderAddress, cursorStartSeconds, endSeconds, limit, offset = null)
        logger.info("本批获取 ${activities.size} 条原始活动")

        if (activities.size < limit) {
            return LeaderTradesBatchResult(trades = toTrades(activities, startTime, endTime), nextCursorSeconds = null)
        }

        val maxSeconds = activities.maxOf { it.timestamp }
        if (maxSeconds > cursorStartSeconds) {
            // 最大那一秒可能被 limit 截断，留给下一页完整拉取
            val complete = activities.filter { it.timestamp < maxSeconds }
            return LeaderTradesBatchResult(trades = toTrades(complete, startTime, endTime), nextCursorSeconds = maxSeconds)
        }

        // 整批都在同一秒：在该秒内按 offset 翻页取全，然后越过该秒
        val sameSecond = activities.toMutableList()
        var offset = limit
        var page = 1
        while (page < MAX_SAME_SECOND_PAGES) {
            val more = fetchActivitiesWithRetry(leader.leaderAddress, maxSeconds, maxSeconds, limit, offset)
            sameSecond.addAll(more.filter { it.timestamp == maxSeconds })
            if (more.size < limit) break
            offset += limit
            page++
        }
        if (page >= MAX_SAME_SECOND_PAGES) {
            logger.warn("同一秒内活动数量超过上限，超出部分可能缺失: leaderId=$leaderId, second=$maxSeconds, fetched=${sameSecond.size}")
        }
        return LeaderTradesBatchResult(trades = toTrades(sameSecond, startTime, endTime), nextCursorSeconds = maxSeconds + 1)
    }

    /**
     * 请求一页用户活动（失败重试，最终失败抛异常，不把失败当作空数据）
     */
    private suspend fun fetchActivitiesWithRetry(
        user: String,
        startSeconds: Long,
        endSeconds: Long,
        limit: Int,
        offset: Int?
    ): List<UserActivityResponse> {
        val dataApi = retrofitFactory.createDataApi()
        val maxRetries = 5
        val retryDelay = 1000L

        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                val response = dataApi.getUserActivity(
                    user = user,
                    type = listOf("TRADE"),
                    start = startSeconds,
                    end = endSeconds,
                    limit = limit,
                    offset = offset,
                    sortBy = "TIMESTAMP",
                    sortDirection = "ASC"
                )

                if (!response.isSuccessful || response.body() == null) {
                    throw Exception("从 Data API 获取用户活动失败: code=${response.code()}, message=${response.message()}")
                }
                return response.body()!!
            } catch (e: Exception) {
                lastException = e
                logger.warn("第 $attempt/$maxRetries 次获取批次失败: ${e.message}")
                if (attempt < maxRetries) {
                    logger.info("等待 $retryDelay 毫秒后重试...")
                    delay(retryDelay)
                }
            }
        }
        val errorMsg = "重试 $maxRetries 次后仍然失败，cursorStart=$startSeconds, offset=$offset"
        logger.error(errorMsg, lastException)
        throw Exception(errorMsg, lastException)
    }

    /**
     * 原始活动 -> 回测交易：过滤非法/超出时间范围的数据，并把同一 tx 同 asset 同 side 的多笔成交聚合
     */
    private fun toTrades(activities: List<UserActivityResponse>, startTime: Long, endTime: Long): List<TradeData> {
        val valid = activities.filter { activity ->
            if (activity.type != "TRADE") return@filter false
            if (activity.side == null || activity.price == null || activity.size == null || activity.usdcSize == null) {
                logger.warn("活动数据缺少必要字段，跳过: activity=$activity")
                return@filter false
            }
            val tradeTimestamp = activity.timestamp * 1000
            if (tradeTimestamp < startTime || tradeTimestamp > endTime) {
                logger.debug("交易时间超出范围，跳过: timestamp=$tradeTimestamp")
                return@filter false
            }
            true
        }
        return aggregateFills(valid)
    }

    /**
     * 聚合同一 tx 同 asset 同 side 的成交（数量、金额求和，价格按数量加权），保持时间升序
     * tradeId：tx 内只有一组成交时为 txHash，否则追加 outcomeIndex/side 区分
     */
    fun aggregateFills(activities: List<UserActivityResponse>): List<TradeData> {
        data class FillKey(val tx: String, val conditionId: String, val asset: String, val side: String)

        val groups = LinkedHashMap<FillKey, MutableList<UserActivityResponse>>()
        for (activity in activities) {
            val side = activity.side!!.uppercase()
            val tx = activity.transactionHash ?: "${activity.timestamp}_${activity.conditionId}_$side"
            val asset = activity.asset ?: activity.outcomeIndex?.toString() ?: ""
            groups.getOrPut(FillKey(tx, activity.conditionId, asset, side)) { mutableListOf() }.add(activity)
        }
        val groupsPerTx = groups.keys.groupingBy { it.tx }.eachCount()

        return groups.mapNotNull { (key, fills) ->
            try {
                val first = fills.first()
                var totalSize = BigDecimal.ZERO
                var totalAmount = BigDecimal.ZERO
                var weightedPrice = BigDecimal.ZERO
                for (fill in fills) {
                    val size = fill.size!!.toSafeBigDecimal()
                    totalSize = totalSize.add(size)
                    totalAmount = totalAmount.add(fill.usdcSize!!.toSafeBigDecimal())
                    weightedPrice = weightedPrice.add(fill.price!!.toSafeBigDecimal().multiply(size))
                }
                val price = if (totalSize > BigDecimal.ZERO) {
                    weightedPrice.divide(totalSize, 8, RoundingMode.HALF_UP).stripTrailingZeros()
                } else {
                    first.price!!.toSafeBigDecimal()
                }
                val tradeId = if ((groupsPerTx[key.tx] ?: 1) > 1) {
                    "${key.tx}:${first.outcomeIndex ?: key.asset.takeLast(8)}:${key.side}"
                } else {
                    key.tx
                }
                TradeData(
                    tradeId = tradeId,
                    marketId = first.conditionId,
                    marketTitle = first.title,
                    marketSlug = first.slug,
                    side = key.side,
                    outcome = first.outcome ?: first.outcomeIndex?.toString() ?: "",
                    outcomeIndex = first.outcomeIndex,
                    price = price,
                    size = totalSize,
                    amount = totalAmount,
                    timestamp = fills.minOf { it.timestamp } * 1000
                )
            } catch (e: Exception) {
                logger.warn("转换活动数据失败: fills=$fills, error=${e.message}", e)
                null
            }
        }.sortedBy { it.timestamp }
    }

    companion object {
        /** 同一秒内 offset 翻页的最大页数（防止异常数据导致死循环） */
        private const val MAX_SAME_SECOND_PAGES = 20
    }
}
