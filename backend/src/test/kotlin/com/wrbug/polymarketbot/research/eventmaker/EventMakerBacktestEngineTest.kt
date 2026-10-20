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
                trade(9_100, 0, 0.975, 300.0, "SELL"),
                trade(9_200, 0, 0.985, 300.0, "BUY")
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
                trade(9_100, 0, 0.975, 300.0, "SELL"),
                trade(9_200, 0, 0.985, 300.0, "BUY")
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
                trade(9_100, 0, 0.975, 50.0, "SELL"),
                trade(9_200, 0, 0.985, 50.0, "BUY")
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
                trade(9_100, 0, 0.975, 300.0, "SELL")
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
                trade(9_100, 0, 0.975, 300.0, "SELL")
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
                trade(9_100, 0, 0.975, 300.0, "SELL"),
                trade(9_200, 0, 0.985, 300.0, "BUY")
            )
        ).copy(conditionId = "train", closedTimestamp = 10_000)
        val testMarket = market(
            winnerIndex = 0,
            trades = listOf(
                trade(18_990, 0, 0.980, 10.0),
                trade(19_100, 0, 0.975, 300.0, "SELL"),
                trade(19_200, 0, 0.985, 300.0, "BUY")
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
                trade(9_100, 0, 0.975, 300.0, "SELL"),
                trade(9_200, 0, 0.986, 300.0, "BUY"),
                trade(9_300, 0, 0.976, 300.0, "SELL"),
                trade(9_400, 0, 0.992, 300.0, "BUY")
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
                trade(9_100, 0, 0.975, 300.0, "SELL"),
                trade(9_200, 0, 0.985, 300.0, "BUY")
            )
        ).copy(conditionId = "winner", closedTimestamp = 10_000)
        val loser = market(
            winnerIndex = 1,
            trades = listOf(
                trade(18_990, 0, 0.980, 10.0),
                trade(19_100, 0, 0.975, 300.0, "SELL")
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

    @Test
    fun `same side taker flow does not fill a resting maker order`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                // Taker BUY cannot fill our resting BUY, even below our limit.
                trade(9_100, 0, 0.975, 300.0, "BUY"),
                // Taker SELL cannot fill our resting SELL, even above our target.
                trade(9_200, 0, 0.985, 300.0, "SELL")
            )
        )

        val result = engine.simulateMarket(market, baseConfig())

        assertTrue(result.eligible)
        assertFalse(result.filled)
        assertEquals(0, result.cycles.size)
    }

    @Test
    fun `prints without a known taker side never fill`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_100, 0, 0.975, 300.0),
                trade(9_200, 0, 0.985, 300.0)
            )
        )

        val result = engine.simulateMarket(market, baseConfig())

        assertTrue(result.eligible)
        assertFalse(result.filled)
    }

    @Test
    fun `activity gate blocks quoting when the trailing window is too thin`() {
        // Entry is at 9_000 (closed 10_000 minus a 1_000s horizon). The 9_100 print
        // would normally trigger a resting buy that the 9_150 print fills, but the
        // notional requirement is unreachable, so the gate must suppress quoting.
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0, "BUY"),
                trade(9_100, 0, 0.980, 10.0, "BUY"),
                trade(9_150, 0, 0.969, 300.0, "SELL"),
                trade(9_200, 0, 0.985, 300.0, "BUY")
            )
        )
        val gated = baseConfig().copy(minRecentNotional2h = 1_000_000.0)

        val result = engine.simulateMarket(market, gated)

        assertTrue(result.eligible)
        assertFalse(result.filled, "gate must block quoting when the window is too thin")
    }

    @Test
    fun `activity gate allows quoting when the trailing window is active enough`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0, "BUY"),
                trade(9_100, 0, 0.980, 10.0, "BUY"),
                trade(9_150, 0, 0.969, 300.0, "SELL"),
                trade(9_200, 0, 0.985, 300.0, "BUY")
            )
        )
        val gated = baseConfig().copy(minRecentTrades30m = 1, minRecentTrades2h = 1)

        val result = engine.simulateMarket(market, gated)

        assertTrue(result.filled, "gate must allow quoting when the window is active")
        assertEquals(MakerExitType.SELL, result.cycles.single().exitType)
    }

    @Test
    fun `adverse sell flow withdraws a resting buy before it can fill`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0, "BUY"),
                trade(9_100, 0, 0.975, 70.0, "SELL"),
                trade(9_110, 0, 0.975, 140.0, "SELL")
            )
        )
        val config = baseConfig().copy(flowGuardWindowSeconds = 600)

        val result = engine.simulateMarket(market, config)

        assertTrue(result.eligible)
        assertFalse(result.filled, "the falling favorite must not accumulate through adverse sell flow")
        assertEquals(1, result.flowGuardTriggers)
    }

    @Test
    fun `healthy two-sided flow still allows a fill`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0, "BUY"),
                trade(9_100, 0, 0.975, 70.0, "BUY"),
                trade(9_110, 0, 0.975, 210.0, "SELL")
            )
        )
        val config = baseConfig().copy(flowGuardWindowSeconds = 600)

        val result = engine.simulateMarket(market, config)

        assertTrue(result.filled)
        assertEquals(0, result.flowGuardTriggers)
        assertEquals(0.975, result.cycles.single().entryPrice, 0.000001)
    }

    @Test
    fun `disabled flow guard preserves legacy fills`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0, "BUY"),
                trade(9_100, 0, 0.975, 70.0, "SELL"),
                trade(9_110, 0, 0.975, 140.0, "SELL")
            )
        )
        val config = baseConfig()

        val result = engine.simulateMarket(market, config)

        assertTrue(result.filled)
        assertEquals(0, result.flowGuardTriggers)
    }

    @Test
    fun `flow and price-drop thresholds are inclusive at the boundary`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_080, 0, 0.980, 30.0, "BUY"),
                trade(9_100, 0, 0.975, 70.0, "SELL"),
                trade(9_110, 0, 0.975, 140.0, "SELL")
            )
        )
        val config = baseConfig().copy(
            flowGuardWindowSeconds = 600,
            flowGuardMaxSignedFlow = -0.4,
            flowGuardMinPriceDropCents = 0.5
        )

        val result = engine.simulateMarket(market, config)

        assertFalse(result.filled)
        assertEquals(1, result.flowGuardTriggers)
    }

    @Test
    fun `flow guard ignores prints without a known taker side`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_100, 0, 0.975, 210.0)
            )
        )
        val config = baseConfig().copy(flowGuardWindowSeconds = 600)

        val result = engine.simulateMarket(market, config)

        assertFalse(result.filled)
        assertEquals(0, result.flowGuardTriggers)
    }

    @Test
    fun `flow guard requotes after adverse flow rolls out of the window`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_099, 0, 0.980, 30.0, "BUY"),
                trade(9_100, 0, 0.974, 70.0, "SELL"),
                trade(9_110, 0, 0.974, 140.0, "SELL"),
                trade(9_200, 0, 0.977, 70.0, "BUY"),
                trade(9_210, 0, 0.972, 70.0, "SELL"),
                trade(9_220, 0, 0.972, 140.0, "SELL")
            )
        )
        val config = baseConfig().copy(flowGuardWindowSeconds = 100)

        val result = engine.simulateMarket(market, config)

        assertTrue(result.filled, "a recovered flow window must allow a new quote")
        assertEquals(1, result.flowGuardTriggers)
        assertEquals(0.972, result.cycles.single().entryPrice, 0.000001)
    }

    @Test
    fun `stale buy quote is canceled before a late taker sell can fill it`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0, "BUY"),
                trade(9_300, 0, 0.975, 300.0, "SELL")
            )
        )
        val config = baseConfig().copy(requoteSeconds = 120, maxOrderAgeSeconds = 120)

        val result = engine.simulateMarket(market, config)

        assertTrue(result.eligible)
        assertFalse(result.filled, "a quote older than maxOrderAgeSeconds must not fill")
    }

    @Test
    fun `stale sell quote is reposted instead of filling on the cancel tick`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0, "BUY"),
                trade(9_100, 0, 0.975, 300.0, "SELL"),
                trade(9_500, 0, 0.985, 300.0, "BUY")
            )
        )
        val config = baseConfig().copy(requoteSeconds = 120, maxOrderAgeSeconds = 120)

        val result = engine.simulateMarket(market, config)

        assertEquals(1, result.cycles.size)
        assertEquals(MakerExitType.SETTLE, result.cycles.single().exitType)
    }

    @Test
    fun `markout uses the first print at or after 60 seconds, not 60_000 seconds`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_100, 0, 0.975, 300.0, "SELL"),
                trade(9_165, 0, 0.980, 10.0),
                trade(9_500, 0, 0.990, 300.0, "BUY")
            )
        )

        val result = engine.simulateMarket(market, baseConfig())

        val cycle = result.cycles.single()
        assertEquals(MakerExitType.SELL, cycle.exitType)
        assertEquals(9_100L, cycle.entryTimestamp)
        val shares = 100.0 / 0.975
        // The print at 9_165 is the first one at or after entry + 60 seconds.
        assertEquals(0.005 * shares, cycle.markout60sPnl!!, 0.0001)
    }

    @Test
    fun `does not fill a buy inside the configured tail window`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_600, 0, 0.975, 300.0, "SELL")
            )
        )
        val controlConfig = baseConfig().copy(requoteSeconds = 0)
        val gatedConfig = controlConfig.copy(minRemainingSeconds = 500)

        val control = engine.simulateMarket(market, controlConfig)
        val gated = engine.simulateMarket(market, gatedConfig)

        assertTrue(control.cycles.isNotEmpty(), "control run must fill outside the tail window")
        assertFalse(gated.filled, "a BUY must not fill with fewer than 500s remaining")
        assertEquals(0, gated.cycles.size)
    }

    @Test
    fun `tail window zero keeps legacy behavior`() {
        val market = market(
            winnerIndex = 0,
            trades = listOf(
                trade(8_990, 0, 0.980, 10.0),
                trade(9_600, 0, 0.975, 300.0, "SELL")
            )
        )
        val config = baseConfig().copy(requoteSeconds = 0, minRemainingSeconds = 0)

        val result = engine.simulateMarket(market, config)

        assertTrue(result.cycles.isNotEmpty())
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

    /**
     * Taker-side explicit trade. A resting BUY fills only on taker SELL prints and a
     * resting SELL only on taker BUY prints, mirroring the live Shadow engine.
     */
    private fun trade(
        timestamp: Long,
        outcomeIndex: Int,
        price: Double,
        size: Double,
        side: String? = null
    ) = EventMakerTrade(timestamp, outcomeIndex, price, size, side)
}
