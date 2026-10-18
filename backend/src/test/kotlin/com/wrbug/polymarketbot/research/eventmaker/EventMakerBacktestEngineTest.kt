package com.wrbug.polymarketbot.research.eventmaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventMakerBacktestEngineTest {
    private val engine = EventMakerBacktestEngine()

    @Test
    fun `fills buy and sell through conservative queue`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_100, 0, 0.975, 300.0),
                trade(9_200, 0, 0.985, 300.0)
            )
        )
        val config = baseConfig()

        val result = engine.simulateMarket(market, config)

        assertTrue(result.eligible)
        assertEquals(1, result.cycles.size)
        assertEquals(MakerExitType.SELL, result.cycles.single().exitType)
        assertEquals(0.975, result.cycles.single().entryPrice, 0.000001)
        assertEquals(0.985, result.cycles.single().exitPrice!!, 0.000001)
        assertEquals(100.0 / 0.975 * 0.01, result.pnl, 0.0001)
        assertTrue(result.pnl > 0.0)
    }

    @Test
    fun `maker fees reduce round trip pnl`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_100, 0, 0.975, 300.0),
                trade(9_200, 0, 0.985, 300.0)
            )
        )
        val result = engine.simulateMarket(market, baseConfig().copy(makerFeeRate = 0.01))

        assertEquals(1, result.cycles.size)
        assertTrue(result.cycles.single().fee > 2.0)
        assertTrue(result.pnl < 0.0)
    }

    @Test
    fun `does not fill when queue volume is insufficient`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_100, 0, 0.975, 50.0),
                trade(9_200, 0, 0.985, 50.0)
            )
        )

        val result = engine.simulateMarket(market, baseConfig())

        assertTrue(result.eligible)
        assertFalse(result.filled)
        assertEquals(0.0, result.pnl, 0.000001)
    }

    @Test
    fun `holds unsold inventory to winning settlement`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_100, 0, 0.975, 300.0)
            )
        )

        val result = engine.simulateMarket(market, baseConfig())

        assertEquals(MakerExitType.SETTLE, result.cycles.single().exitType)
        assertEquals(1.0, result.cycles.single().exitPrice!!, 0.000001)
        assertEquals((1.0 - 0.975) * (100.0 / 0.975), result.pnl, 0.0001)
    }

    @Test
    fun `settles losing inventory with full notional loss`() {
        val market = market(
            winnerIndex = 1,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_100, 0, 0.975, 300.0)
            )
        )

        val result = engine.simulateMarket(market, baseConfig())

        assertEquals(MakerExitType.SETTLE, result.cycles.single().exitType)
        assertEquals(-100.0, result.pnl, 0.0001)
    }

    @Test
    fun `skips market when favorite is outside configured price band`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.950, 10.0),
                trade(9_100, 0, 0.945, 300.0)
            )
        )

        val result = engine.simulateMarket(market, baseConfig())

        assertFalse(result.eligible)
        assertFalse(result.filled)
    }

    @Test
    fun `summary splits filled markets into train and test windows`() {
        val trainMarket = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_100, 0, 0.975, 300.0),
                trade(9_200, 0, 0.985, 300.0)
            )
        ).copy(conditionId = "train", closedTimestamp = 10_000)
        val testMarket = market(
            winnerIndex = 0,
            trades = listOf(
                trade(18_990, 0, 0.980, 10.0),
                trade(19_100, 0, 0.975, 300.0),
                trade(19_200, 0, 0.985, 300.0)
            )
        ).copy(conditionId = "test", closedTimestamp = 20_000)

        val (summary, _) = EventMakerBacktestEngine(trainEndTimestamp = 15_000)
            .run(listOf(trainMarket, testMarket), baseConfig())

        assertEquals(2, summary.filledMarkets)
        assertEquals(1, summary.trainFilledMarkets)
        assertEquals(1, summary.testFilledMarkets)
        assertEquals(1, summary.trainCycles)
        assertEquals(1, summary.testCycles)
        assertEquals(2, summary.profitableFilledMarkets)
        assertEquals(0.0, summary.totalFees, 0.000001)
    }

    @Test
    fun `can rotate inventory more than once`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_100, 0, 0.975, 300.0),
                trade(9_200, 0, 0.986, 300.0),
                trade(9_300, 0, 0.976, 300.0),
                trade(9_400, 0, 0.992, 300.0)
            )
        )
        val config = baseConfig(maxCycles = 2)

        val result = engine.simulateMarket(market, config)

        assertEquals(2, result.cycles.size)
        assertTrue(result.cycles.all { it.exitType == MakerExitType.SELL })
        assertTrue(result.pnl > 0.0)
    }

    @Test
    fun `summary reports settlement tail risk and realized drawdown`() {
        val winner = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_100, 0, 0.975, 300.0),
                trade(9_200, 0, 0.985, 300.0)
            )
        ).copy(conditionId = "winner", closedTimestamp = 10_000)
        val loser = market(
            winnerIndex = 1,
            trades = listOf(
                trade(18_990, 0, 0.980, 10.0),
                trade(19_100, 0, 0.975, 300.0)
            )
        ).copy(conditionId = "loser", closedTimestamp = 20_000)

        val (summary, _) = EventMakerBacktestEngine(trainEndTimestamp = 15_000)
            .run(listOf(winner, loser), baseConfig())

        assertEquals(2, summary.totalCycles)
        assertEquals(1, summary.settledLosses)
        assertTrue(summary.maxDrawdown > 90.0)
        assertTrue(summary.maxAdverseExcursion < -99.0)
        assertEquals(100.0, summary.averageTimeToFillSeconds, 0.0001)
        assertTrue(summary.breakEvenLossRate in 0.009..0.011)
    }

    private fun baseConfig(maxCycles: Int = 1) = EventMakerConfig(
        name = "test",
        entryHorizonSeconds = 1_000,
        minPrice = 0.97,
        maxPrice = 0.99,
        entryOffsetCents = 0.5,
        exitSpreadCents = 1.0,
        queueFactor = 2.0,
        requoteSeconds = 300,
        orderNotional = 100.0,
        maxCycles = maxCycles
    )

    private fun market(winnerIndex: Int, trades: List<EventMakerTrade>) = EventMakerMarket(
        conditionId = "condition",
        question = "question",
        tag = "Politics",
        volume = 1_000_000.0,
        closedTimestamp = 10_000,
        winnerIndex = winnerIndex,
        tickSize = 0.001,
        trades = trades
    )

    private fun trade(timestamp: Long, outcomeIndex: Int, price: Double, size: Double) =
        EventMakerTrade(timestamp, outcomeIndex, price, size)
}
