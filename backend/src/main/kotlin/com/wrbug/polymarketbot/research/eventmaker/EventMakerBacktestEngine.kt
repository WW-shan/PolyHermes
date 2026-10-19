package com.wrbug.polymarketbot.research.eventmaker

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Replays public trade prints for a leader-style passive event maker.
 *
 * The engine deliberately models a conservative queue: a resting order only fills after
 * cumulative volume at the limit reaches [EventMakerConfig.queueFactor] times the order
 * size. This is not a full order-book simulation, but it prevents the common backtest
 * mistake of assuming that every touched limit price produces a fill.
 */
class EventMakerBacktestEngine(
    private val trainEndTimestamp: Long = 1_767_225_600L // 2026-01-01T00:00:00Z
) {
    private data class Inventory(
        val entryTimestamp: Long,
        val entryPrice: Double,
        val shares: Double,
        val entryFee: Double,
        val timeToFillSeconds: Long
    )

    private data class BuyOrder(
        val limitPrice: Double,
        val shares: Double,
        val placedTimestamp: Long,
        var eligibleVolume: Double = 0.0
    )

    fun run(markets: List<EventMakerMarket>, config: EventMakerConfig): Pair<EventMakerSummary, List<EventMakerMarketResult>> {
        val results = markets.map { simulateMarket(it, config) }
        return summarize(config, results) to results
    }

    fun runGrid(
        markets: List<EventMakerMarket>,
        configs: List<EventMakerConfig>
    ): List<EventMakerSummary> = configs.map { run(markets, it).first }

    internal fun simulateMarket(market: EventMakerMarket, config: EventMakerConfig): EventMakerMarketResult {
        val entryTimestamp = market.closedTimestamp - config.entryHorizonSeconds
        val reference = referencePrice(market, entryTimestamp)

        if (reference == null) {
            return emptyResult(market, null, null, null, eligible = false)
        }

        val (favoriteIndex, referencePrice) = reference
        if (referencePrice < config.minPrice || referencePrice >= config.maxPrice) {
            return emptyResult(market, referencePrice, favoriteIndex, favoriteIndex == market.winnerIndex, eligible = false)
        }

        val won = favoriteIndex == market.winnerIndex
        val tick = market.tickSize.takeIf { it > 0.0 } ?: 0.001
        val entryOffset = config.entryOffsetCents / 100.0
        val exitSpread = config.exitSpreadCents / 100.0
        val trades = market.trades
            .asSequence()
            .filter { it.outcomeIndex == favoriteIndex }
            .filter { it.timestamp > entryTimestamp && it.timestamp < market.closedTimestamp }
            .filter { it.price > 0.0 && it.price < 1.0 && it.size > 0.0 }
            .sortedBy { it.timestamp }
            .toList()

        if (trades.isEmpty()) {
            return emptyResult(market, referencePrice, favoriteIndex, won, eligible = true)
        }

        val cycles = mutableListOf<EventMakerCycle>()
        var inventory: Inventory? = null
        var buyOrder: BuyOrder? = null
        var sellPrice: Double? = null
        var sellEligibleVolume = 0.0

        fun placeBuy(atPrice: Double, timestamp: Long) {
            // Mirror the live selector: only quote when the trailing window on the
            // quoted token shows enough two-sided activity. Without this the
            // backtest fills in markets the Shadow/Live path would never select.
            if (!passesActivityGate(config, trades, timestamp)) return
            val limit = roundDownToTick(max(0.001, atPrice - entryOffset), tick)
            buyOrder = BuyOrder(
                limitPrice = limit,
                shares = config.orderNotional / limit,
                placedTimestamp = timestamp
            )
        }

        // The leader quotes continuously. Start the simulated order at the entry timestamp,
        // using the last observable trade rather than waiting for the first trade in the window.
        placeBuy(referencePrice, entryTimestamp)

        for (trade in trades) {
            if (cycles.size >= config.maxCycles && inventory == null && buyOrder == null) break

            val currentInventory = inventory
            if (currentInventory == null) {
                val currentOrder = buyOrder
                if (currentOrder == null) {
                    if (trade.price >= config.minPrice && trade.price < config.maxPrice) placeBuy(trade.price, trade.timestamp)
                    continue
                }

                if (config.requoteSeconds > 0 && trade.timestamp - currentOrder.placedTimestamp >= config.requoteSeconds) {
                    if (trade.price >= config.minPrice && trade.price < config.maxPrice) {
                        placeBuy(trade.price, trade.timestamp)
                        continue
                    }
                }

                // A resting BUY is only filled by taker SELL flow. Prints without a
                // known side are ignored so the simulation cannot claim a fill that
                // the live engine would never see.
                if (trade.takerIsSell && trade.price <= currentOrder.limitPrice) {
                    currentOrder.eligibleVolume += trade.size
                    if (currentOrder.eligibleVolume + 1e-9 >= config.queueFactor * currentOrder.shares) {
                        val entryFee = currentOrder.shares * currentOrder.limitPrice * config.makerFeeRate
                        inventory = Inventory(
                            entryTimestamp = trade.timestamp,
                            entryPrice = currentOrder.limitPrice,
                            shares = currentOrder.shares,
                            entryFee = entryFee,
                            timeToFillSeconds = max(0L, trade.timestamp - currentOrder.placedTimestamp)
                        )
                        sellPrice = roundUpToTick(min(0.999, currentOrder.limitPrice + exitSpread), tick)
                        sellEligibleVolume = 0.0
                        buyOrder = null
                    }
                }
            } else {
                val targetSell = sellPrice ?: roundUpToTick(min(0.999, currentInventory.entryPrice + exitSpread), tick)
                // A resting SELL is only filled by taker BUY flow.
                if (trade.takerIsBuy && trade.price >= targetSell) {
                    sellEligibleVolume += trade.size
                    if (sellEligibleVolume + 1e-9 >= config.queueFactor * currentInventory.shares) {
                        val exitFee = currentInventory.shares * targetSell * config.makerFeeRate
                        val pnl = (targetSell - currentInventory.entryPrice) * currentInventory.shares -
                            currentInventory.entryFee - exitFee
                        cycles += EventMakerCycle(
                            entryTimestamp = currentInventory.entryTimestamp,
                            entryPrice = currentInventory.entryPrice,
                            shares = currentInventory.shares,
                            exitTimestamp = trade.timestamp,
                            exitPrice = targetSell,
                            exitType = MakerExitType.SELL,
                            holdingSeconds = max(0L, trade.timestamp - currentInventory.entryTimestamp),
                            pnl = pnl,
                            fee = currentInventory.entryFee + exitFee,
                            timeToFillSeconds = currentInventory.timeToFillSeconds
                        )
                        inventory = null
                        sellPrice = null
                        sellEligibleVolume = 0.0
                        if (cycles.size < config.maxCycles && trade.price >= config.minPrice && trade.price < config.maxPrice) {
                            placeBuy(trade.price, trade.timestamp)
                        }
                    }
                }
            }
        }

        val openInventory = inventory
        if (openInventory != null) {
            val payoff = if (won) 1.0 else 0.0
            val pnl = (payoff - openInventory.entryPrice) * openInventory.shares - openInventory.entryFee
            cycles += EventMakerCycle(
                entryTimestamp = openInventory.entryTimestamp,
                entryPrice = openInventory.entryPrice,
                shares = openInventory.shares,
                exitTimestamp = market.closedTimestamp,
                exitPrice = payoff,
                exitType = MakerExitType.SETTLE,
                holdingSeconds = max(0L, market.closedTimestamp - openInventory.entryTimestamp),
                pnl = pnl,
                fee = openInventory.entryFee,
                timeToFillSeconds = openInventory.timeToFillSeconds
            )
        }

        val enrichedCycles = cycles.map { cycle ->
            val pathEnd = cycle.exitTimestamp ?: market.closedTimestamp
            val markedPrices = buildList {
                add(cycle.entryPrice)
                trades.forEach { trade ->
                    if (trade.timestamp >= cycle.entryTimestamp && trade.timestamp <= pathEnd) add(trade.price)
                }
                if (cycle.exitType == MakerExitType.SETTLE) cycle.exitPrice?.let(::add)
            }
            val worstMarkPrice = markedPrices.minOrNull() ?: cycle.entryPrice
            val entryFee = cycle.entryPrice * cycle.shares * config.makerFeeRate
            val markoutTarget = cycle.entryTimestamp + 60_000L
            val markoutTrade = trades.firstOrNull { it.timestamp >= markoutTarget }
                ?: trades.lastOrNull { it.timestamp in cycle.entryTimestamp..markoutTarget }
            cycle.copy(
                maxAdverseExcursion = (worstMarkPrice - cycle.entryPrice) * cycle.shares - entryFee,
                markout60sPnl = markoutTrade?.let { (it.price - cycle.entryPrice) * cycle.shares - entryFee }
            )
        }
        val deployed = enrichedCycles.sumOf { it.entryPrice * it.shares }
        val pnl = enrichedCycles.sumOf { it.pnl }
        val baselineCost = min(0.999, referencePrice + tick)
        val baselineShares = config.orderNotional / baselineCost
        val baselinePayoff = if (won) 1.0 else 0.0
        val baselinePnl = (baselinePayoff - baselineCost) * baselineShares
        return EventMakerMarketResult(
            conditionId = market.conditionId,
            question = market.question,
            tag = market.tag,
            volume = market.volume,
            closedTimestamp = market.closedTimestamp,
            referencePrice = referencePrice,
            favoriteOutcomeIndex = favoriteIndex,
            favoriteWon = won,
            eligible = true,
            cycles = enrichedCycles,
            deployedNotional = deployed,
            pnl = pnl,
            roi = if (deployed > 0.0) pnl / deployed else 0.0,
            holdBaselinePnl = baselinePnl,
            holdBaselineRoi = baselinePnl / config.orderNotional,
            holdBaselineDeployed = config.orderNotional,
            maxAdverseExcursion = enrichedCycles.minOfOrNull { it.maxAdverseExcursion } ?: 0.0
        )
    }

    private fun passesActivityGate(
        config: EventMakerConfig,
        trades: List<EventMakerTrade>,
        timestamp: Long
    ): Boolean {
        val gateApplied = config.minRecentTrades30m > 0 ||
            config.minRecentNotional30m > 0.0 ||
            config.minRecentTradesPerSide30m > 0 ||
            config.minRecentTrades2h > 0 ||
            config.minRecentNotional2h > 0.0 ||
            config.minRecentTradesPerSide2h > 0
        if (!gateApplied) return true
        val window30m = trades.filter { it.timestamp in (timestamp - 1_800L)..timestamp }
        val window2h = trades.filter { it.timestamp in (timestamp - 7_200L)..timestamp }
        val notional30m = window30m.sumOf { it.price * it.size }
        val notional2h = window2h.sumOf { it.price * it.size }
        val buy30m = window30m.count { it.takerIsBuy }
        val sell30m = window30m.count { it.takerIsSell }
        val buy2h = window2h.count { it.takerIsBuy }
        val sell2h = window2h.count { it.takerIsSell }
        return window30m.size >= config.minRecentTrades30m &&
            notional30m >= config.minRecentNotional30m &&
            buy30m >= config.minRecentTradesPerSide30m &&
            sell30m >= config.minRecentTradesPerSide30m &&
            window2h.size >= config.minRecentTrades2h &&
            notional2h >= config.minRecentNotional2h &&
            buy2h >= config.minRecentTradesPerSide2h &&
            sell2h >= config.minRecentTradesPerSide2h
    }

    private fun referencePrice(market: EventMakerMarket, entryTimestamp: Long): Pair<Int, Double>? {
        val before = market.trades.filter { it.timestamp <= entryTimestamp && it.price > 0.0 && it.price < 1.0 }
        if (before.isEmpty()) return null
        val last0 = before.filter { it.outcomeIndex == 0 }.maxByOrNull { it.timestamp }
        val last1 = before.filter { it.outcomeIndex == 1 }.maxByOrNull { it.timestamp }
        val p0 = last0?.price ?: last1?.let { 1.0 - it.price }
        val p1 = last1?.price ?: last0?.let { 1.0 - it.price }
        if (p0 == null || p1 == null) return null
        return if (p0 >= p1) 0 to p0 else 1 to p1
    }

    private fun emptyResult(
        market: EventMakerMarket,
        referencePrice: Double?,
        favoriteOutcomeIndex: Int?,
        favoriteWon: Boolean?,
        eligible: Boolean
    ): EventMakerMarketResult = EventMakerMarketResult(
        conditionId = market.conditionId,
        question = market.question,
        tag = market.tag,
        volume = market.volume,
        closedTimestamp = market.closedTimestamp,
        referencePrice = referencePrice,
        favoriteOutcomeIndex = favoriteOutcomeIndex,
        favoriteWon = favoriteWon,
        eligible = eligible,
        cycles = emptyList(),
        deployedNotional = 0.0,
        pnl = 0.0,
        roi = 0.0,
        holdBaselinePnl = 0.0,
        holdBaselineRoi = 0.0,
        holdBaselineDeployed = 0.0
    )

    private fun summarize(config: EventMakerConfig, results: List<EventMakerMarketResult>): EventMakerSummary {
        val eligible = results.filter { it.eligible }
        val filled = eligible.filter { it.filled }
        val cycles = filled.flatMap { it.cycles }
        val sold = cycles.count { it.exitType == MakerExitType.SELL }
        val settled = cycles.count { it.exitType == MakerExitType.SETTLE }
        val wins = cycles.count { it.pnl > 0.0 }
        val losses = cycles.count { it.pnl < 0.0 }
        val settledLosses = cycles.count { it.exitType == MakerExitType.SETTLE && it.pnl < 0.0 }
        val averageWin = if (wins == 0) 0.0 else cycles.filter { it.pnl > 0.0 }.sumOf { it.pnl } / wins
        val breakEvenLossRate = if (averageWin <= 0.0) 0.0 else averageWin / (averageWin + config.orderNotional)
        val deployed = filled.sumOf { it.deployedNotional }
        val pnl = filled.sumOf { it.pnl }
        val holdDeployed = eligible.sumOf { it.holdBaselineDeployed }
        val holdPnl = eligible.sumOf { it.holdBaselinePnl }
        var cumulative = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        for (cycle in cycles.sortedWith(compareBy<EventMakerCycle> { it.exitTimestamp ?: Long.MAX_VALUE }.thenBy { it.entryTimestamp })) {
            cumulative += cycle.pnl
            peak = max(peak, cumulative)
            maxDrawdown = max(maxDrawdown, peak - cumulative)
        }
        val train = filled.filter { it.closedTimestamp < trainEndTimestamp }
        val test = filled.filter { it.closedTimestamp >= trainEndTimestamp }
        val trainDeployed = train.sumOf { it.deployedNotional }
        val testDeployed = test.sumOf { it.deployedNotional }
        val trainPnl = train.sumOf { it.pnl }
        val testPnl = test.sumOf { it.pnl }
        val trainCycles = train.sumOf { it.cycles.size }
        val testCycles = test.sumOf { it.cycles.size }
        return EventMakerSummary(
            config = config,
            totalMarkets = results.size,
            eligibleMarkets = eligible.size,
            filledMarkets = filled.size,
            fillRate = if (eligible.isEmpty()) 0.0 else filled.size.toDouble() / eligible.size,
            totalCycles = cycles.size,
            soldCycles = sold,
            settledCycles = settled,
            winningCycles = wins,
            losingCycles = losses,
            winRate = if (cycles.isEmpty()) 0.0 else wins.toDouble() / cycles.size,
            deployedNotional = deployed,
            totalPnl = pnl,
            totalRoi = if (deployed > 0.0) pnl / deployed else 0.0,
            averagePnlPerFilledMarket = if (filled.isEmpty()) 0.0 else pnl / filled.size,
            worstMarketPnl = filled.minOfOrNull { it.pnl } ?: 0.0,
            maxDrawdown = maxDrawdown,
            averageHoldingSeconds = if (cycles.isEmpty()) 0.0 else cycles.sumOf { it.holdingSeconds }.toDouble() / cycles.size,
            holdBaselineDeployed = holdDeployed,
            holdBaselinePnl = holdPnl,
            holdBaselineRoi = if (holdDeployed > 0.0) holdPnl / holdDeployed else 0.0,
            profitableFilledMarkets = filled.count { it.pnl > 0.0 },
            totalFees = cycles.sumOf { it.fee },
            trainPnl = trainPnl,
            trainRoi = if (trainDeployed > 0.0) trainPnl / trainDeployed else 0.0,
            trainFilledMarkets = train.size,
            trainCycles = trainCycles,
            testPnl = testPnl,
            testRoi = if (testDeployed > 0.0) testPnl / testDeployed else 0.0,
            testFilledMarkets = test.size,
            testCycles = testCycles,
            splitTimestamp = trainEndTimestamp,
            maxAdverseExcursion = cycles.minOfOrNull { it.maxAdverseExcursion } ?: 0.0,
            averageTimeToFillSeconds = if (cycles.isEmpty()) 0.0 else cycles.sumOf { it.timeToFillSeconds }.toDouble() / cycles.size,
            settledLosses = settledLosses,
            breakEvenLossRate = breakEvenLossRate
        )
    }

    private fun roundDownToTick(value: Double, tick: Double): Double {
        val units = floor(value / tick)
        return max(tick, units * tick)
    }

    private fun roundUpToTick(value: Double, tick: Double): Double {
        val units = ceil(value / tick)
        return min(0.999, max(tick, units * tick))
    }
}
