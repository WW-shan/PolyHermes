package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.dto.TradeData
import com.wrbug.polymarketbot.entity.BacktestTask
import com.wrbug.polymarketbot.entity.BacktestTrade
import com.wrbug.polymarketbot.entity.Market
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.repository.BacktestTradeRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.common.MarketPriceService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.util.Optional

/**
 * 回测执行回归测试（改写自审查阶段的 BacktestVerifyTest）
 */
class BacktestExecutionServiceTest {

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)

    private class FakeData(private val batches: List<LeaderTradesBatchResult>) :
        BacktestDataService(Mockito.mock(LeaderRepository::class.java), Mockito.mock(RetrofitFactory::class.java)) {
        var calls = 0
        override suspend fun getLeaderHistoricalTradesBatch(
            leaderId: Long, startTime: Long, endTime: Long, cursorStartSeconds: Long, limit: Int
        ): LeaderTradesBatchResult {
            val r = batches.getOrElse(calls) { LeaderTradesBatchResult(emptyList(), null) }
            calls++
            return r
        }
    }

    private class FakePrice(private val price: BigDecimal?) : MarketPriceService(
        Mockito.mock(com.wrbug.polymarketbot.service.common.BlockchainService::class.java),
        Mockito.mock(RetrofitFactory::class.java),
        Mockito.mock(com.wrbug.polymarketbot.repository.AccountRepository::class.java),
        Mockito.mock(com.wrbug.polymarketbot.util.CryptoUtils::class.java)
    ) {
        override suspend fun getCurrentMarketPrice(marketId: String, outcomeIndex: Int): BigDecimal {
            return price ?: throw IllegalStateException("no price")
        }
    }

    private data class Run(val task: BacktestTask, val trades: List<BacktestTrade>, val deletedTrades: Boolean)

    /**
     * @param stopAfterBatches 第几批断点保存后数据库状态变为 STOPPED（null 表示不停止）
     */
    private fun run(
        task: BacktestTask,
        batches: List<LeaderTradesBatchResult>,
        marketEndDate: Long? = null,
        markPrice: BigDecimal? = BigDecimal("0.5"),
        feeRate: BigDecimal = BigDecimal.ZERO,
        stopAfterBatches: Int? = null
    ): Run {
        val taskRepo: BacktestTaskRepository = mock()
        val tradeRepo: BacktestTradeRepository = mock()
        val data = FakeData(batches)
        var dbStatus = "PENDING"
        Mockito.`when`(taskRepo.save(Mockito.any(BacktestTask::class.java))).thenAnswer {
            val t = it.arguments[0] as BacktestTask
            dbStatus = t.status
            t
        }
        var checkpoints = 0
        val statusNow = {
            if (stopAfterBatches != null && checkpoints >= stopAfterBatches) "STOPPED" else dbStatus
        }
        Mockito.`when`(taskRepo.findById(Mockito.anyLong())).thenAnswer { Optional.of(task.copy(status = statusNow())) }
        Mockito.`when`(taskRepo.updateProgressIfRunning(Mockito.anyLong(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyLong()))
            .thenAnswer { if (statusNow() == "RUNNING") 1 else 0 }
        Mockito.`when`(
            taskRepo.updateCheckpointIfRunning(
                Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.any(BigDecimal::class.java) ?: BigDecimal.ZERO, Mockito.anyLong()
            )
        ).thenAnswer {
            val result = if (statusNow() == "RUNNING") 1 else 0
            checkpoints++
            result
        }
        var deleted = false
        Mockito.`when`(tradeRepo.deleteAllByBacktestTaskId(Mockito.anyLong())).thenAnswer { deleted = true; 3 }
        val saved = mutableListOf<BacktestTrade>()
        Mockito.`when`(tradeRepo.saveAll(Mockito.anyList<BacktestTrade>())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            val l = it.arguments[0] as List<BacktestTrade>
            saved += l
            l
        }
        val marketService: MarketService = mock()
        Mockito.`when`(marketService.getMarket(Mockito.anyString())).thenAnswer {
            Market(marketId = it.arguments[0] as String, title = "m", endDate = marketEndDate)
        }
        Mockito.`when`(marketService.getTakerFeeRate(Mockito.anyString())).thenReturn(feeRate)
        val filter = CopyTradingFilterService(mock(), mock(), mock(), mock())
        val svc = BacktestExecutionService(taskRepo, tradeRepo, data, FakePrice(markPrice), marketService, filter)
        runBlocking { svc.executeBacktest(task, 0, 500) }
        return Run(task, saved, deleted)
    }

    private val now = System.currentTimeMillis()

    private fun t(id: String, side: String, price: String, size: String, amount: String, minsAgo: Long, market: String = "m1") =
        TradeData(
            tradeId = id, marketId = market, marketTitle = "Test market", marketSlug = null, side = side,
            outcome = "Yes", outcomeIndex = 0, price = BigDecimal(price), size = BigDecimal(size),
            amount = BigDecimal(amount), timestamp = now - minsAgo * 60_000
        )

    private fun baseTask(
        initial: String = "1000", copyMode: String = "RATIO", ratio: String = "0.01", fixed: String? = null,
        minOrder: String = "1", maxOrder: String = "1000", minPrice: String? = null, maxPrice: String? = null,
        lastProcessed: Long? = null
    ) = BacktestTask(
        id = 1, taskName = "t", leaderId = 1, initialBalance = BigDecimal(initial), backtestDays = 3,
        startTime = now - 3 * 86_400_000L, status = "PENDING", copyMode = copyMode, copyRatio = BigDecimal(ratio),
        fixedAmount = fixed?.let { BigDecimal(it) }, maxOrderSize = BigDecimal(maxOrder), minOrderSize = BigDecimal(minOrder),
        minPrice = minPrice?.let { BigDecimal(it) }, maxPrice = maxPrice?.let { BigDecimal(it) },
        lastProcessedTradeTime = lastProcessed
    )

    private fun single(vararg trades: TradeData) = listOf(LeaderTradesBatchResult(trades.toList(), null))

    private fun assertBd(expected: String, actual: BigDecimal?) {
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")
    }

    @Test
    fun `sell amount is quantity times price and not clamped to minOrderSize`() {
        // 买入 20 USDC = 100 股；Leader 卖出 5% @0.04 -> 卖 5 股，金额 0.2（旧逻辑被夹逼成 1）
        val r = run(baseTask(), single(
            t("0xa", "BUY", "0.20", "10000", "2000", 120),
            t("0xb", "SELL", "0.04", "500", "20", 60)
        ))
        val sell = r.trades.single { it.side == "SELL" }
        assertBd("5", sell.quantity)
        assertBd("0.2", sell.amount)
    }

    @Test
    fun `sell amount is not clamped to maxOrderSize`() {
        val r = run(baseTask(ratio = "1", maxOrder = "100"), single(
            t("0xa1", "BUY", "0.5", "200", "100", 300),
            t("0xa2", "BUY", "0.5", "200", "100", 290),
            t("0xs", "SELL", "0.6", "400", "240", 200)
        ))
        val sell = r.trades.single { it.side == "SELL" }
        assertBd("400", sell.quantity)
        assertBd("240", sell.amount)
        assertBd("1040", r.task.finalBalance)
    }

    @Test
    fun `leader exiting in two sells fully closes the copy position`() {
        val r = run(baseTask(), single(
            t("0xa", "BUY", "0.5", "1000", "500", 300),
            t("0xb", "SELL", "0.5", "500", "250", 200),
            t("0xc", "SELL", "0.5", "500", "250", 100)
        ))
        val sells = r.trades.filter { it.side == "SELL" }
        assertEquals(2, sells.size)
        assertBd("10", sells.sumOf { it.quantity })
        assertTrue(r.trades.none { it.side == "SETTLEMENT" })
    }

    @Test
    fun `fixed mode sells proportionally to leader sell`() {
        // 固定 10 USDC @0.5 = 20 股，对应 Leader 1000 股；Leader 卖 100 股 -> 跟单卖 2 股
        val r = run(baseTask(copyMode = "FIXED", fixed = "10"), single(
            t("0xa", "BUY", "0.5", "1000", "500", 300),
            t("0xb", "SELL", "0.5", "100", "50", 200)
        ))
        assertBd("2", r.trades.single { it.side == "SELL" }.quantity)
    }

    @Test
    fun `price range filter only applies to buys`() {
        val r = run(baseTask(minPrice = "0.1", maxPrice = "0.8"), single(
            t("0xa", "BUY", "0.5", "1000", "500", 300),
            t("0xb", "SELL", "0.9", "1000", "900", 200)
        ))
        assertBd("9", r.trades.single { it.side == "SELL" }.amount)
    }

    @Test
    fun `insufficient cash skips buys but later sells are still processed`() {
        val r = run(baseTask(initial = "100", ratio = "1"), single(
            t("0xa", "BUY", "0.5", "120", "60", 3000),
            t("0xb", "BUY", "0.5", "120", "60", 2900, market = "m2"),
            t("0xc", "SELL", "0.9", "120", "108", 2800),
            t("0xd", "SELL", "0.9", "120", "108", 2700, market = "m2")
        ))
        assertEquals(2, r.trades.count { it.side == "SELL" })
        // 60 买 120 股，40 买 80 股，全部 0.9 卖出 -> 108 + 72
        assertBd("180", r.task.finalBalance)
        assertEquals("COMPLETED", r.task.status)
    }

    @Test
    fun `buy reserves taker fee so balance never goes negative`() {
        val r = run(baseTask(initial = "10", ratio = "1"), single(
            t("0xa", "BUY", "0.5", "100", "50", 300)
        ), feeRate = BigDecimal("0.07"))
        val buy = r.trades.single { it.side == "BUY" }
        assertTrue(buy.balanceAfter >= BigDecimal.ZERO, "balance ${buy.balanceAfter}")
        assertTrue(buy.amount.add(buy.fee) <= BigDecimal("10"))
    }

    @Test
    fun `ended market is settled at resolution at the end of the run`() {
        // 市场 1 小时前结束，Leader 唯一交易在 2 天前；链上结果为赢 -> 按 1 结算而不是按成本平仓
        val r = run(baseTask(), single(t("0xa", "BUY", "0.3", "1000", "300", 2880)),
            marketEndDate = now - 3_600_000L, markPrice = BigDecimal.ONE)
        val settlement = r.trades.single { it.side == "SETTLEMENT" }
        assertEquals("WIN", settlement.outcome)
        assertBd("1", settlement.price)
        assertBd("1007", r.task.finalBalance)
    }

    @Test
    fun `open positions are marked to market and labelled`() {
        val r = run(baseTask(), single(t("0xa", "BUY", "0.3", "1000", "300", 2880)), markPrice = BigDecimal("0.6"))
        val settlement = r.trades.single { it.side == "SETTLEMENT" }
        assertEquals(BacktestExecutionService.OUTCOME_MARK_TO_MARKET, settlement.outcome)
        assertBd("3", settlement.profitLoss)
    }

    @Test
    fun `multiple fills aggregated upstream are all simulated`() {
        val r = run(baseTask(), single(
            t("0xsame:0:BUY", "BUY", "0.94", "6086", "5735.051", 300)
        ))
        assertBd("57.35051", r.trades.single { it.side == "BUY" }.amount)
    }

    @Test
    fun `resume restarts from scratch and clears stale trades`() {
        val task = baseTask(lastProcessed = now - 500 * 60_000L).also { it.finalBalance = BigDecimal("400") }
        val r = run(task, single(t("0xz", "BUY", "0.5", "1000", "500", 100)), markPrice = BigDecimal("0.5"))
        assertTrue(r.deletedTrades)
        // 从初始资金 1000 开始，买入 5 后按 0.5 估值 -> 1000
        assertBd("1000", r.task.finalBalance)
    }

    @Test
    fun `stop request is respected and final status stays STOPPED`() {
        val r = run(
            baseTask(),
            listOf(
                LeaderTradesBatchResult(listOf(t("0xa", "BUY", "0.5", "1000", "500", 300)), 123L),
                LeaderTradesBatchResult(listOf(t("0xb", "BUY", "0.5", "1000", "500", 200)), null)
            ),
            stopAfterBatches = 1
        )
        assertEquals("STOPPED", r.task.status)
        assertEquals(1, r.trades.count { it.side == "BUY" })
        assertTrue(r.trades.none { it.side == "SETTLEMENT" })
    }
}
