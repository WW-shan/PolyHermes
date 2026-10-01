package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.dto.TradeData
import com.wrbug.polymarketbot.dto.BacktestStatisticsDto
import com.wrbug.polymarketbot.entity.BacktestTask
import com.wrbug.polymarketbot.entity.BacktestTrade
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.BacktestTradeRepository
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.service.common.MarketPriceService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.util.PolymarketTradingFee
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

@Service
class BacktestExecutionService(
    private val backtestTaskRepository: BacktestTaskRepository,
    private val backtestTradeRepository: BacktestTradeRepository,
    private val backtestDataService: BacktestDataService,
    private val marketPriceService: MarketPriceService,
    private val marketService: MarketService,
    private val copyTradingFilterService: CopyTradingFilterService
) {
    private val logger = LoggerFactory.getLogger(BacktestExecutionService::class.java)

    /**
     * 持仓数据结构
     * @param marketEndDate 市场结束时间（毫秒），用于到期结算判断，null 表示未知
     */
    data class Position(
        val marketId: String,
        val outcome: String,
        val outcomeIndex: Int?,
        var quantity: BigDecimal,
        val avgPrice: BigDecimal,
        val leaderBuyQuantity: BigDecimal?,
        val marketEndDate: Long? = null,
        /** 已支付但尚未通过卖出/结算结转的买入手续费 */
        var feesPaid: BigDecimal = BigDecimal.ZERO,
        /** 累计跟单买入数量（FIXED 模式卖出比例的分子） */
        val totalCopyBuyQuantity: BigDecimal = BigDecimal.ZERO,
        /** 累计 Leader 买入数量（FIXED 模式卖出比例的分母） */
        val totalLeaderBuyQuantity: BigDecimal = BigDecimal.ZERO
    )

    /**
     * 将回测任务转换为虚拟的 CopyTrading 配置用于执行
     * 注意：回测场景使用历史数据，不需要实时跟单的相关配置
     */
    private fun taskToCopyTrading(task: BacktestTask): CopyTrading {
        return CopyTrading(
            id = task.id,
            accountId = 0L,
            leaderId = task.leaderId,
            enabled = true,
            copyMode = task.copyMode,
            copyRatio = task.copyRatio,
            fixedAmount = null,
            maxOrderSize = task.maxOrderSize,
            minOrderSize = task.minOrderSize,
            maxDailyLoss = task.maxDailyLoss,
            maxDailyOrders = task.maxDailyOrders,
            priceTolerance = BigDecimal.ZERO,  // 回测使用历史价格，不需要容忍度
            delaySeconds = 0,  // 回测按时间线执行，无需延迟
            pollIntervalSeconds = 5,
            useWebSocket = false,
            websocketReconnectInterval = 5000,
            websocketMaxRetries = 10,
            supportSell = task.supportSell,
            minOrderDepth = null,  // 回测无实时订单簿数据
            maxSpread = null,  // 回测无实时价差数据
            maxPositionValue = task.maxPositionValue,
            minPrice = task.minPrice,  // 最低价格
            maxPrice = task.maxPrice,  // 最高价格
            keywordFilterMode = task.keywordFilterMode,
            keywords = task.keywords,
            configName = null,
            pushFailedOrders = false,
            pushFilteredOrders = false,
            createdAt = task.createdAt,
            updatedAt = task.updatedAt
        )
    }

    /**
     * 执行回测任务（支持分页和恢复）
     * 自动处理所有页面的数据，支持中断恢复
     */
    companion object {
        /** 回测结束时未结算持仓按市价估值 */
        const val OUTCOME_MARK_TO_MARKET = "MARK_TO_MARKET"

        /** 回测结束时未结算持仓取不到价格，按成本估值 */
        const val OUTCOME_UNPRICED = "UNPRICED"
    }

    /** 每批请求 API 的条数（基于 start 游标分页，避免 offset 过大） */
    private val backtestBatchLimit = 500

    /**
     * 注意：不能加 @Transactional —— 整个回测在一个事务里时，循环中读到的任务状态来自一级缓存，
     * 用户点击停止（另一个事务写 STOPPED）永远不可见，且进度/交易直到结束才提交。
     * 各仓储调用自带事务。
     */
    suspend fun executeBacktest(task: BacktestTask, page: Int = 1, size: Int = 100) {
        try {
            logger.info("开始执行回测任务: taskId=${task.id}, taskName=${task.taskName}, batchLimit=$backtestBatchLimit")

            // 1. 续跑/重试一律从头执行：清空旧交易与断点，保证余额、持仓和统计与落库交易一致
            val deleted = backtestTradeRepository.deleteAllByBacktestTaskId(task.id!!)
            if (deleted > 0 || task.lastProcessedTradeTime != null) {
                logger.info("回测任务从头重跑，清空旧交易 $deleted 笔: taskId=${task.id}")
            }
            task.lastProcessedTradeTime = null
            task.lastProcessedTradeIndex = null
            task.processedTradeCount = 0
            task.progress = 0
            task.finalBalance = null

            // 2. 更新任务状态为 RUNNING
            task.status = "RUNNING"
            task.executionStartedAt = System.currentTimeMillis()
            task.updatedAt = System.currentTimeMillis()
            backtestTaskRepository.save(task)

            // 3. 初始化
            var currentBalance = task.initialBalance
            val positions = mutableMapOf<String, Position>()
            val trades = mutableListOf<BacktestTrade>()
            val dailyOrderCountCache = mutableMapOf<String, Int>()
            val dailyLossCache = mutableMapOf<String, BigDecimal>()
            val seenTradeIds = mutableSetOf<String>()
            val feeRateCache = mutableMapOf<String, BigDecimal>()

            // 4. 回测时间范围：以当前时间为基准取最近 backtestDays 天
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (task.backtestDays * 24L * 3600 * 1000)

            logger.info("回测时间范围: ${formatTimestamp(startTime)} - ${formatTimestamp(endTime)} (${task.backtestDays} 天), " +
                "初始余额: ${task.initialBalance.toPlainString()}")

            var cursorSeconds = startTime / 1000
            var stopped = false

            while (true) {
                val currentTaskStatus = backtestTaskRepository.findById(task.id!!).orElse(null)
                if (currentTaskStatus == null || currentTaskStatus.status != "RUNNING") {
                    logger.info("回测任务状态已变更: ${currentTaskStatus?.status}，停止执行")
                    stopped = true
                    break
                }

                logger.info("正在获取批次数据 cursorStart=$cursorSeconds (${formatTimestamp(cursorSeconds * 1000)}) ...")

                val currentPageTrades = mutableListOf<BacktestTrade>()

                try {
                    val batch = backtestDataService.getLeaderHistoricalTradesBatch(
                        task.leaderId,
                        startTime,
                        endTime,
                        cursorSeconds,
                        backtestBatchLimit
                    )
                    val pageTrades = batch.trades

                    if (pageTrades.isEmpty() && batch.nextCursorSeconds == null) {
                        logger.info("本批无数据，所有数据处理完成")
                        break
                    }

                    logger.info("本批获取 ${pageTrades.size} 条交易，是否有下一页: ${batch.nextCursorSeconds != null}")

                    val countAtBatchStart = task.processedTradeCount
                    var lastProcessedIndexInPage: Int? = null
                    var processedInBatch = 0
                    for (localIndex in pageTrades.indices) {
                        val leaderTrade = pageTrades[localIndex]
                        if (leaderTrade.tradeId in seenTradeIds) {
                            logger.debug("跳过重复交易: ${leaderTrade.tradeId}")
                            continue
                        }
                        seenTradeIds.add(leaderTrade.tradeId)

                        val index = countAtBatchStart + processedInBatch
                        lastProcessedIndexInPage = index
                        processedInBatch++

                        // 进度按时间比例：(当前订单时间 - 开始时间) / (结束时间 - 开始时间) * 100，运行中上限 99
                        val timeRange = endTime - startTime
                        val progress = if (timeRange > 0) {
                            val elapsed = (leaderTrade.timestamp - startTime).coerceIn(0L, timeRange)
                            min(99, ((elapsed * 100) / timeRange).toInt())
                        } else {
                            0
                        }
                        if (progress > task.progress) {
                            task.progress = progress
                            task.processedTradeCount = index + 1
                            // 条件更新：任务已被停止时返回 0，立即退出且不覆盖状态
                            if (backtestTaskRepository.updateProgressIfRunning(task.id!!, progress, index + 1) == 0) {
                                logger.info("回测任务已不在运行状态，停止处理: taskId=${task.id}")
                                stopped = true
                                break
                            }
                        }

                        try {
                            // 5.1 实时检查并结算已到期的市场
                            currentBalance = settleExpiredPositions(task, positions, currentBalance, leaderTrade.timestamp, currentPageTrades)

                            // 5.2 余额不足不终止回测：买入在下方按可用余额跳过，后续卖出/结算仍需处理
                            val tradeDate = formatDate(leaderTrade.timestamp)

                            if (leaderTrade.side == "BUY") {
                            // 5.3 应用过滤规则（价格区间、关键字等只作用于买入）
                            val copyTrading = taskToCopyTrading(task)
                            val filterResult = copyTradingFilterService.checkFilters(
                                copyTrading,
                                tokenId = "",
                                tradePrice = leaderTrade.price,
                                copyOrderAmount = null,
                                marketId = leaderTrade.marketId,
                                marketTitle = leaderTrade.marketTitle,
                                marketEndDate = null,
                                outcomeIndex = leaderTrade.outcomeIndex
                            )

                            if (!filterResult.isPassed) {
                                logger.debug("交易被过滤: ${leaderTrade.tradeId}")
                                continue
                            }

                            // 5.4 每日订单数检查 - 使用缓存，只统计 BUY 订单
                            val dailyOrderCount = dailyOrderCountCache.getOrDefault(tradeDate, 0)

                            if (dailyOrderCount >= task.maxDailyOrders) {
                                logger.info("已达到每日最大 BUY 订单数限制: $dailyOrderCount / ${task.maxDailyOrders}")
                                continue
                            }


                            // 5.6 计算跟单金额
                            val followAmount = calculateFollowAmount(task, leaderTrade)

                            // 5.6.1 检查订单大小限制
                            val finalFollowAmount = if (followAmount > task.maxOrderSize) {
                                logger.info("跟单金额超过最大限制: $followAmount > ${task.maxOrderSize}，调整为最大值")
                                task.maxOrderSize
                            } else if (followAmount < task.minOrderSize) {
                                logger.info("跟单金额低于最小限制: $followAmount < ${task.minOrderSize}，调整为最小值")
                                task.minOrderSize
                            } else {
                                followAmount
                            }

                            // 5.6.2 检查每日最大亏损（买入订单）- 使用缓存
                            val dailyLoss = dailyLossCache.getOrDefault(tradeDate, BigDecimal.ZERO)
                            if (dailyLoss > task.maxDailyLoss) {
                                logger.info("已达到每日最大亏损限制: $dailyLoss / ${task.maxDailyLoss}，跳过买入订单")
                                continue
                            }

                                // 5.7 买入：余额不足时按最大可用余额交易（预留手续费），仍须满足最小订单金额
                                val price = leaderTrade.price.toSafeBigDecimal()
                                if (price <= BigDecimal.ZERO || price >= BigDecimal.ONE) {
                                    logger.debug("价格不在 (0,1) 区间跳过: price=$price, tradeId=${leaderTrade.tradeId}")
                                    continue
                                }
                                val feeRate = feeRateCache.getOrPut(leaderTrade.marketId) {
                                    marketService.getTakerFeeRate(leaderTrade.marketId)
                                }
                                val actualBuyAmount = affordableBuyAmount(finalFollowAmount, currentBalance, price, feeRate)
                                if (actualBuyAmount < finalFollowAmount) {
                                    logger.debug("余额不足，按可用余额（已预留手续费）买入: balance=$currentBalance, 原需=$finalFollowAmount, 实际=$actualBuyAmount, marketId=${leaderTrade.marketId}")
                                }
                                if (actualBuyAmount < task.minOrderSize || actualBuyAmount <= BigDecimal.ZERO) {
                                    logger.debug("可用金额低于最小订单限制跳过: actual=$actualBuyAmount, minOrderSize=${task.minOrderSize}")
                                    continue
                                }
                                val quantity = actualBuyAmount.divide(price, 8, java.math.RoundingMode.DOWN)
                                if (quantity <= BigDecimal.ZERO) {
                                    logger.debug("计算数量为0跳过: actualBuyAmount=$actualBuyAmount, price=$price")
                                    continue
                                }
                                val market = marketService.getMarket(leaderTrade.marketId)
                                val buyFee = PolymarketTradingFee.takerFee(quantity, price, feeRate)
                                val totalCost = actualBuyAmount.add(buyFee)
                                if (totalCost > currentBalance) {
                                    logger.debug("买入总成本超过余额跳过: totalCost=$totalCost, balance=$currentBalance")
                                    continue
                                }

                                // 5.6.3 检查最大仓位限制（如果配置了）
                                if (task.maxPositionValue != null) {
                                    val positionKey = "${leaderTrade.marketId}:${leaderTrade.outcomeIndex ?: 0}"
                                    val currentPosition = positions[positionKey]
                                    val currentPositionValue = if (currentPosition != null) {
                                        currentPosition.quantity.multiply(currentPosition.avgPrice)
                                    } else {
                                        BigDecimal.ZERO
                                    }
                                    val totalValueAfterOrder = currentPositionValue.add(actualBuyAmount)
                                    
                                    if (totalValueAfterOrder.gt(task.maxPositionValue)) {
                                        val currentPositionValueStr = currentPositionValue.stripTrailingZeros().toPlainString()
                                        val totalValueStr = totalValueAfterOrder.stripTrailingZeros().toPlainString()
                                        val maxValueStr = task.maxPositionValue.stripTrailingZeros().toPlainString()
                                        logger.info("超过最大仓位金额限制: 市场=${leaderTrade.marketId}, 方向=${leaderTrade.outcomeIndex}, 当前仓位=${currentPositionValueStr} USDC, 买入金额=${actualBuyAmount} USDC, 总计=${totalValueStr} USDC > 最大限制=${maxValueStr} USDC")
                                        continue
                                    }
                                }

                                // 更新余额和持仓（同市场同 outcome 多次买入合并：数量相加、加权均价、leaderBuyQuantity 相加）
                                currentBalance -= totalCost
                                val positionKey = "${leaderTrade.marketId}:${leaderTrade.outcomeIndex ?: 0}"
                                val leaderSize = leaderTrade.size.toSafeBigDecimal()
                                val existing = positions[positionKey]
                                positions[positionKey] = if (existing != null) {
                                    val newQuantity = existing.quantity.add(quantity)
                                    val newAvgPrice = if (newQuantity > BigDecimal.ZERO) {
                                        existing.quantity.multiply(existing.avgPrice).add(quantity.multiply(price))
                                            .divide(newQuantity, 8, java.math.RoundingMode.HALF_UP)
                                    } else {
                                        price
                                    }
                                    val newLeaderBuyQuantity = (existing.leaderBuyQuantity ?: BigDecimal.ZERO).add(leaderSize)
                                    Position(
                                        marketId = leaderTrade.marketId,
                                        outcome = leaderTrade.outcome ?: "",
                                        outcomeIndex = leaderTrade.outcomeIndex,
                                        quantity = newQuantity,
                                        avgPrice = newAvgPrice,
                                        leaderBuyQuantity = newLeaderBuyQuantity,
                                        marketEndDate = existing.marketEndDate ?: market?.endDate,
                                        feesPaid = existing.feesPaid.add(buyFee),
                                        totalCopyBuyQuantity = existing.totalCopyBuyQuantity.add(quantity),
                                        totalLeaderBuyQuantity = existing.totalLeaderBuyQuantity.add(leaderSize)
                                    )
                                } else {
                                    Position(
                                        marketId = leaderTrade.marketId,
                                        outcome = leaderTrade.outcome ?: "",
                                        outcomeIndex = leaderTrade.outcomeIndex,
                                        quantity = quantity,
                                        avgPrice = price,
                                        leaderBuyQuantity = leaderSize,
                                        marketEndDate = market?.endDate,
                                        feesPaid = buyFee,
                                        totalCopyBuyQuantity = quantity,
                                        totalLeaderBuyQuantity = leaderSize
                                    )
                                }

                                // 记录交易到当前页列表
                                currentPageTrades.add(BacktestTrade(
                                    backtestTaskId = task.id!!,
                                    tradeTime = leaderTrade.timestamp,
                                    marketId = leaderTrade.marketId,
                                    marketTitle = leaderTrade.marketTitle,
                                    side = "BUY",
                                    outcome = leaderTrade.outcome ?: leaderTrade.outcomeIndex.toString(),
                                    outcomeIndex = leaderTrade.outcomeIndex,
                                    quantity = quantity,
                                    price = price,
                                    amount = actualBuyAmount,
                                    fee = buyFee,
                                    profitLoss = null,
                                    balanceAfter = currentBalance,
                                    leaderTradeId = leaderTrade.tradeId
                                ))

                                // 更新每日订单数缓存
                                dailyOrderCountCache[tradeDate] = dailyOrderCount + 1

                            } else {
                                // SELL 逻辑（价格区间、每日单数、每日亏损只作用于买入，卖出不过滤）
                                if (!task.supportSell) {
                                    continue
                                }

                                val positionKey = "${leaderTrade.marketId}:${leaderTrade.outcomeIndex ?: 0}"
                                val position = positions[positionKey] ?: continue
                                val leaderSellSize = leaderTrade.size.toSafeBigDecimal()

                                // 计算卖出数量（与实盘一致）
                                val actualSellQuantity = calculateSellQuantity(task, position, leaderSellSize)
                                if (actualSellQuantity <= BigDecimal.ZERO) {
                                    continue
                                }

                                // 卖出金额 = 数量 × 价格（不按 min/maxOrderSize 夹逼，否则金额与数量脱钩）
                                val sellPrice = leaderTrade.price.toSafeBigDecimal()
                                val sellAmount = actualSellQuantity.multiply(sellPrice)
                                val sellFee = PolymarketTradingFee.takerFee(
                                    actualSellQuantity, sellPrice,
                                    feeRateCache.getOrPut(leaderTrade.marketId) { marketService.getTakerFeeRate(leaderTrade.marketId) }
                                )
                                val netAmount = sellAmount.subtract(sellFee)

                                // 买入手续费按卖出数量比例结转，避免盈利被低估。
                                val allocatedBuyFee = if (position.quantity > BigDecimal.ZERO) {
                                    position.feesPaid.multiply(actualSellQuantity)
                                        .divide(position.quantity, 8, java.math.RoundingMode.HALF_UP)
                                } else {
                                    BigDecimal.ZERO
                                }
                                val cost = actualSellQuantity.multiply(position.avgPrice).add(allocatedBuyFee)
                                val profitLoss = netAmount.subtract(cost)

                                currentBalance += netAmount
                                val remainingQuantity = position.quantity - actualSellQuantity
                                // 剩余 Leader 持仓 = 原剩余 - Leader 实际卖出量
                                val remainingLeaderBuyQuantity = position.leaderBuyQuantity
                                    ?.subtract(leaderSellSize)
                                    ?.coerceAtLeast(BigDecimal.ZERO)
                                if (remainingQuantity <= BigDecimal.ZERO) {
                                    positions.remove(positionKey)
                                } else {
                                    positions[positionKey] = position.copy(
                                        quantity = remainingQuantity,
                                        leaderBuyQuantity = remainingLeaderBuyQuantity,
                                        feesPaid = position.feesPaid.subtract(allocatedBuyFee).coerceAtLeast(BigDecimal.ZERO)
                                    )
                                }

                                // 记录交易到当前页列表
                                currentPageTrades.add(BacktestTrade(
                                    backtestTaskId = task.id!!,
                                    tradeTime = leaderTrade.timestamp,
                                    marketId = leaderTrade.marketId,
                                    marketTitle = leaderTrade.marketTitle,
                                    side = "SELL",
                                    outcome = leaderTrade.outcome ?: leaderTrade.outcomeIndex.toString(),
                                    outcomeIndex = leaderTrade.outcomeIndex,
                                    quantity = actualSellQuantity,
                                    price = sellPrice,
                                    amount = sellAmount,
                                    fee = sellFee,
                                    profitLoss = profitLoss,
                                    balanceAfter = currentBalance,
                                    leaderTradeId = leaderTrade.tradeId
                                ))
                                // SELL 订单不计入每日订单数限制
                                
                                // 更新每日亏损缓存（只累加亏损，不累加盈利）
                                if (profitLoss < BigDecimal.ZERO) {
                                    val currentDailyLoss = dailyLossCache.getOrDefault(tradeDate, BigDecimal.ZERO)
                                    dailyLossCache[tradeDate] = currentDailyLoss + profitLoss.negate()
                                }
                            }

                        } catch (e: Exception) {
                            logger.error("处理交易失败: tradeId=${leaderTrade.tradeId}", e)
                        }
                    }

                    // 保存本批交易
                    if (currentPageTrades.isNotEmpty()) {
                        logger.info("保存本批交易，共 ${currentPageTrades.size} 笔")
                        backtestTradeRepository.saveAll(currentPageTrades)

                        val lastTradeInPage = currentPageTrades.lastOrNull()
                        if (lastTradeInPage != null && lastProcessedIndexInPage != null) {
                            task.lastProcessedTradeTime = lastTradeInPage.tradeTime
                            task.lastProcessedTradeIndex = lastProcessedIndexInPage
                            task.processedTradeCount = lastProcessedIndexInPage + 1
                            task.finalBalance = currentBalance
                            val updated = backtestTaskRepository.updateCheckpointIfRunning(
                                task.id!!, lastTradeInPage.tradeTime, lastProcessedIndexInPage,
                                lastProcessedIndexInPage + 1, currentBalance
                            )
                            if (updated == 0) {
                                stopped = true
                            }
                            logger.info("本批处理完成，lastProcessedTradeIndex=${task.lastProcessedTradeIndex}, 总处理数=${task.processedTradeCount}")
                        }
                    } else {
                        logger.info("本批没有交易需要保存")
                    }

                    trades.addAll(currentPageTrades)

                    if (stopped) {
                        logger.info("回测任务已被停止，退出循环: taskId=${task.id}")
                        break
                    }
                    val nextCursor = batch.nextCursorSeconds
                    if (nextCursor == null) {
                        logger.info("本批原始数据不足 $backtestBatchLimit 条，已是最后一页")
                        break
                    }
                    // 死循环防护：游标必须前进
                    cursorSeconds = if (nextCursor > cursorSeconds) nextCursor else cursorSeconds + 1
                    if (cursorSeconds > endTime / 1000) {
                        break
                    }

                } catch (e: Exception) {
                    logger.error("获取或处理本批数据失败: ${e.message}", e)
                    // 重试失败，标记任务为 FAILED
                    throw e
                }
            }

            // 6. 以数据库当前状态为准：已被停止/删除则不再结算、不覆盖状态
            val dbStatus = backtestTaskRepository.findById(task.id!!).orElse(null)?.status
            if (dbStatus == null) {
                logger.info("回测任务已被删除，结束执行: taskId=${task.id}")
                return
            }
            val finalStatus = if (stopped || dbStatus != "RUNNING") dbStatus else "COMPLETED"

            if (finalStatus == "COMPLETED") {
                // 6.1 先按结束时间结算已到期市场（链上结算结果优先）
                val endSettlements = mutableListOf<BacktestTrade>()
                currentBalance = settleExpiredPositions(task, positions, currentBalance, endTime, endSettlements)
                // 6.2 剩余持仓按结束时刻价格估值
                currentBalance = settleRemainingPositions(task, positions, currentBalance, endTime, endSettlements)
                if (endSettlements.isNotEmpty()) {
                    backtestTradeRepository.saveAll(endSettlements)
                    trades.addAll(endSettlements)
                    logger.info("回测结束结算/估值持仓，持久化 ${endSettlements.size} 笔 SETTLEMENT")
                }
            }

            // 7. 计算最终统计数据
            val statistics = calculateStatistics(trades)

            // 8. 更新任务状态
            val profitAmount = currentBalance.subtract(task.initialBalance)
            val profitRate = if (task.initialBalance > BigDecimal.ZERO) {
                profitAmount.divide(task.initialBalance, 4, java.math.RoundingMode.HALF_UP).multiply(BigDecimal("100"))
            } else {
                BigDecimal.ZERO
            }

            task.finalBalance = currentBalance
            task.profitAmount = profitAmount
            task.profitRate = profitRate
            task.endTime = endTime
            task.status = finalStatus
            if (finalStatus == "COMPLETED") {
                task.progress = 100
            }
            task.totalTrades = trades.size
            task.buyTrades = trades.count { it.side == "BUY" }
            task.sellTrades = trades.count { it.side == "SELL" }
            task.winTrades = statistics.winTrades
            task.lossTrades = statistics.lossTrades
            task.winRate = statistics.winRate.toSafeBigDecimal()
            task.maxProfit = statistics.maxProfit.toSafeBigDecimal()
            task.maxLoss = statistics.maxLoss.toSafeBigDecimal()
            task.maxDrawdown = statistics.maxDrawdown.toSafeBigDecimal()
            task.avgHoldingTime = statistics.avgHoldingTime
            task.executionFinishedAt = System.currentTimeMillis()
            task.updatedAt = System.currentTimeMillis()

            backtestTaskRepository.save(task)

            logger.info("回测任务执行完成: taskId=${task.id}, " +
                "最终余额=${currentBalance.toPlainString()}, " +
                "收益额=${task.profitAmount?.toPlainString()}, " +
                "收益率=${task.profitRate?.toPlainString()}%, " +
                "总交易数=${trades.size}, " +
                "盈利率=${task.winRate?.toPlainString()}%")

        } catch (e: Exception) {
            logger.error("回测任务执行失败: taskId=${task.id}", e)
            task.status = "FAILED"
            task.errorMessage = e.message
            task.executionFinishedAt = System.currentTimeMillis()
            task.updatedAt = System.currentTimeMillis()
            backtestTaskRepository.save(task)
            throw e
        }
    }

    /**
     * 结算已到期的市场
     * @param batchTradesToSave 本批要持久化的交易列表，到期结算（赎回/输）会追加到此列表并随本批一起落库
     */
    private suspend fun settleExpiredPositions(
        task: BacktestTask,
        positions: MutableMap<String, Position>,
        currentBalance: BigDecimal,
        currentTime: Long,
        batchTradesToSave: MutableList<BacktestTrade>
    ): BigDecimal {
        var balance = currentBalance

        for ((positionKey, position) in positions.toList()) {
            try {
                // 仅当市场已到期（结束时间 <= 当前回测时间）时才结算，避免未到期持仓被误结算
                if (position.marketEndDate == null || position.marketEndDate!! > currentTime) {
                    logger.debug("持仓未到期跳过结算: marketId=${position.marketId}, endDate=${position.marketEndDate}, currentTime=$currentTime")
                    continue
                }
                // 获取市场当前价格
                val marketPrice = marketPriceService.getCurrentMarketPrice(
                    position.marketId,
                    position.outcomeIndex ?: 0
                )

                val price = marketPrice.toSafeBigDecimal()

                // 通过市场价格判断结算价格（链上已结算时为 1/0）；无法判定输赢的保留持仓，留待回测结束时按市价估值
                val settlementPrice = when {
                    price >= BigDecimal("0.95") -> BigDecimal.ONE
                    price <= BigDecimal("0.05") -> BigDecimal.ZERO
                    else -> {
                        logger.debug("市场已到期但结果未定，暂不结算: marketId=${position.marketId}, price=$price")
                        continue
                    }
                }

                val settlementValue = position.quantity.multiply(settlementPrice)
                val profitLoss = settlementValue
                    .subtract(position.quantity.multiply(position.avgPrice))
                    .subtract(position.feesPaid)

                balance += settlementValue

                val marketTitle = marketService.getMarket(position.marketId)?.title ?: ""
                val settlementTrade = BacktestTrade(
                    backtestTaskId = task.id!!,
                    tradeTime = currentTime,
                    marketId = position.marketId,
                    marketTitle = marketTitle,
                    side = "SETTLEMENT",
                    outcome = if (settlementPrice.compareTo(BigDecimal.ONE) == 0) "WIN" else "LOSE",
                    outcomeIndex = position.outcomeIndex,
                    quantity = position.quantity,
                    price = settlementPrice,
                    amount = settlementValue,
                    fee = BigDecimal.ZERO,
                    profitLoss = profitLoss,
                    balanceAfter = balance,
                    leaderTradeId = null
                )
                batchTradesToSave.add(settlementTrade)

                // 移除已结算的持仓
                positions.remove(positionKey)
            } catch (e: Exception) {
                logger.error("结算市场失败: marketId=${position.marketId}, outcomeIndex=${position.outcomeIndex}", e)
            }
        }

        return balance
    }

    /**
     * 回测结束时仍未结算的持仓：按结束时刻可得的市场价格估值（不是按成本价平仓），
     * outcome 标注为 MARK_TO_MARKET；价格取不到时按成本估值并标注 UNPRICED（盈亏仅含已付买入手续费）。
     * @param settlementsToSave 估值记录会追加到此列表，调用方需落库
     */
    private suspend fun settleRemainingPositions(
        task: BacktestTask,
        positions: MutableMap<String, Position>,
        currentBalance: BigDecimal,
        currentTime: Long,
        settlementsToSave: MutableList<BacktestTrade>
    ): BigDecimal {
        var balance = currentBalance

        for ((_, position) in positions.toList()) {
            val quantity = position.quantity
            val markPrice = try {
                marketPriceService.getCurrentMarketPrice(position.marketId, position.outcomeIndex ?: 0).toSafeBigDecimal()
            } catch (e: Exception) {
                logger.warn("回测结束估值取价失败，按成本估值并标注 UNPRICED: marketId=${position.marketId}, error=${e.message}")
                null
            }
            val valuationPrice = markPrice ?: position.avgPrice
            val settlementValue = quantity.multiply(valuationPrice)
            val profitLoss = settlementValue.subtract(quantity.multiply(position.avgPrice)).subtract(position.feesPaid)

            balance += settlementValue

            val marketTitle = marketService.getMarket(position.marketId)?.title ?: ""
            settlementsToSave.add(
                BacktestTrade(
                    backtestTaskId = task.id!!,
                    tradeTime = currentTime,
                    marketId = position.marketId,
                    marketTitle = marketTitle,
                    side = "SETTLEMENT",
                    outcome = if (markPrice != null) OUTCOME_MARK_TO_MARKET else OUTCOME_UNPRICED,
                    outcomeIndex = position.outcomeIndex,
                    quantity = quantity,
                    price = valuationPrice,
                    amount = settlementValue,
                    fee = BigDecimal.ZERO,
                    profitLoss = profitLoss,
                    balanceAfter = balance,
                    leaderTradeId = null
                )
            )
        }

        positions.clear()
        return balance
    }

    /**
     * 计算统计数据
     */
    private fun calculateStatistics(trades: List<BacktestTrade>): BacktestStatisticsDto {
        val buyTrades = trades.count { it.side == "BUY" }
        val sellTrades = trades.count { it.side == "SELL" }
        val winTrades = trades.count { it.profitLoss != null && it.profitLoss > BigDecimal.ZERO }
        val lossTrades = trades.count { it.profitLoss != null && it.profitLoss < BigDecimal.ZERO }

        var totalProfit = BigDecimal.ZERO
        var totalLoss = BigDecimal.ZERO
        var maxProfit = BigDecimal.ZERO
        var maxLoss = BigDecimal.ZERO

        // 计算最大回撤
        var runningBalance = if (trades.isNotEmpty()) {
            trades[0].balanceAfter?.toSafeBigDecimal() ?: BigDecimal.ZERO
        } else {
            BigDecimal.ZERO
        }
        var peakBalance = runningBalance
        var maxDrawdown = BigDecimal.ZERO

        for (i in trades.indices) {
            val trade = trades[i]
            val balance = trade.balanceAfter?.toSafeBigDecimal() ?: continue

            if (trade.profitLoss != null) {
                val pnl = trade.profitLoss.toSafeBigDecimal()
                if (pnl > BigDecimal.ZERO) {
                    totalProfit += pnl
                    if (pnl > maxProfit) maxProfit = pnl
                } else {
                    totalLoss += pnl
                    if (pnl < maxLoss) maxLoss = pnl
                }
            }

            if (balance > peakBalance) {
                peakBalance = balance
            }
            // Bug #39 Fix: use current balance, not runningBalance from previous iteration
            val drawdown = peakBalance - balance
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown
            }

            runningBalance = balance
        }

        // 计算平均持仓时间
        var avgHoldingTime: Long? = null
        if (trades.size > 1) {
            var totalHoldingTime = 0L
            var count = 0
            for (i in 0 until trades.size - 1) {
                val currentTrade = trades[i]
                val nextTrade = trades[i + 1]

                if (currentTrade.side == "BUY" && nextTrade.side == "SELL") {
                    val holdingTime = nextTrade.tradeTime - currentTrade.tradeTime
                    totalHoldingTime += holdingTime
                    count++
                }
            }

            if (count > 0) {
                avgHoldingTime = totalHoldingTime / count
            }
        }

        return BacktestStatisticsDto(
            totalTrades = trades.size,
            buyTrades = buyTrades,
            sellTrades = sellTrades,
            winTrades = winTrades,
            lossTrades = lossTrades,
            winRate = if (buyTrades + sellTrades > 0) {
                (winTrades.toBigDecimal().divide((buyTrades + sellTrades).toBigDecimal(), 4, java.math.RoundingMode.HALF_UP))
                    .multiply(BigDecimal("100"))
                    .toPlainString()
            } else {
                BigDecimal.ZERO.toPlainString()
            },
            maxProfit = maxProfit.toPlainString(),
            maxLoss = maxLoss.toPlainString(),
            maxDrawdown = maxDrawdown.toPlainString(),
            avgHoldingTime = avgHoldingTime
        )
    }

    /**
     * 计算跟单卖出数量（与实盘 CopyOrderTrackingService 一致）
     * - FIXED：Leader 卖出量 × (Σ跟单买入 / ΣLeader 买入)
     * - RATIO：按 Leader 卖出量占其剩余持仓的比例卖出跟单持仓
     * Leader 卖出量 >= 其剩余持仓（已全部离场）时清仓；结果不超过当前持仓
     */
    internal fun calculateSellQuantity(task: BacktestTask, position: Position, leaderSellSize: BigDecimal): BigDecimal {
        if (leaderSellSize <= BigDecimal.ZERO || position.quantity <= BigDecimal.ZERO) return BigDecimal.ZERO
        val leaderExitsAll = position.leaderBuyQuantity?.let { it > BigDecimal.ZERO && leaderSellSize >= it } ?: false
        if (leaderExitsAll) return position.quantity
        val raw = if (task.copyMode == "FIXED" && position.totalLeaderBuyQuantity > BigDecimal.ZERO) {
            leaderSellSize.multiply(position.totalCopyBuyQuantity)
                .divide(position.totalLeaderBuyQuantity, 8, java.math.RoundingMode.DOWN)
        } else {
            val leaderRemaining = position.leaderBuyQuantity
            if (leaderRemaining != null && leaderRemaining > BigDecimal.ZERO && leaderSellSize < leaderRemaining) {
                position.quantity.multiply(leaderSellSize)
                    .divide(leaderRemaining, 8, java.math.RoundingMode.DOWN)
            } else {
                position.quantity
            }
        }
        return raw.min(position.quantity)
    }

    /**
     * 计算余额约束下的实际买入金额（预留 taker 手续费，保证 金额 + 手续费 <= 余额）
     * 手续费 = 数量 × 费率 × p × (1-p) = 金额 × 费率 × (1-p)
     */
    internal fun affordableBuyAmount(
        desiredAmount: BigDecimal,
        balance: BigDecimal,
        price: BigDecimal,
        feeRate: BigDecimal
    ): BigDecimal {
        if (balance <= BigDecimal.ZERO) return BigDecimal.ZERO
        val feeFactor = BigDecimal.ONE.add(feeRate.max(BigDecimal.ZERO).multiply(BigDecimal.ONE.subtract(price)))
        val maxAffordable = balance.divide(feeFactor, 2, java.math.RoundingMode.DOWN)
        return desiredAmount.min(maxAffordable).max(BigDecimal.ZERO)
    }

    /**
     * 计算跟单金额
     */
    private fun calculateFollowAmount(task: BacktestTask, leaderTrade: TradeData): BigDecimal {
        return if (task.copyMode == "RATIO") {
            // 比例模式：Leader 成交金额 × 跟单比例
            leaderTrade.amount.toSafeBigDecimal().multiply(task.copyRatio)
        } else {
            // 固定金额模式：使用配置的固定金额
            task.fixedAmount ?: leaderTrade.amount.toSafeBigDecimal()
        }
    }

    /**
     * 判断是否同一天
     */
    private fun isSameDay(timestamp1: Long, timestamp2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = timestamp1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = timestamp2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * 格式化时间戳
     */
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        return sdf.format(Date(timestamp))
    }

    /**
     * 格式化日期（用于缓存key）
     */
    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd")
        return sdf.format(Date(timestamp))
    }
}
