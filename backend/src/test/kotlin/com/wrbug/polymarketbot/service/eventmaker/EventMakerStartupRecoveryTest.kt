package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class EventMakerStartupRecoveryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `unfilled orphan order is canceled and terminal state confirmed`() = runTest {
        val port = RecoveryExecutionPort()
        val service = service(port)
        val persisted = state(
            market(
                conditionId = "orphan",
                inventory = "0",
                activeOrderId = "order-1",
                matched = "0"
            )
        )

        service.cancelOrphanedOrders(persisted, emptySet(), 7L)

        assertEquals(1, port.cancelCount)
        assertEquals(1, port.getOrderCount)
        assertEquals("CANCELLED", port.lastStatus)
    }

    @Test
    fun `already terminal orphan is accepted even if cancel reports not confirmed`() = runTest {
        val port = RecoveryExecutionPort().apply {
            cancelFailure = IllegalStateException("cancel_not_confirmed:already canceled")
            statusAfterCancel = "CANCELED"
        }
        val service = service(port)
        val persisted = state(
            market(
                conditionId = "orphan",
                inventory = "0",
                activeOrderId = "order-1",
                matched = "0"
            )
        )

        service.cancelOrphanedOrders(persisted, emptySet(), 7L)

        assertEquals(1, port.cancelCount)
        assertEquals(1, port.getOrderCount)
        assertEquals("CANCELED", port.lastStatus)
    }


    @Test
    fun `orphan inventory fails closed`() = runTest {
        val port = RecoveryExecutionPort()
        val service = service(port)
        val persisted = state(
            market(
                conditionId = "orphan",
                inventory = "5.00",
                activeOrderId = null,
                matched = null
            )
        )

        val error = runCatching {
            service.cancelOrphanedOrders(persisted, emptySet(), 7L)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.startsWith("orphan_inventory_requires_reconciliation:orphan"))
        assertEquals(0, port.cancelCount)
    }

    @Test
    fun `orphan order with any fill fails closed even after cancel`() = runTest {
        val port = RecoveryExecutionPort().apply {
            matchedShares = BigDecimal("0.50")
            statusAfterCancel = "CANCELLED"
        }
        val service = service(port)
        val persisted = state(
            market(
                conditionId = "orphan",
                inventory = "0",
                activeOrderId = "order-1",
                matched = "0"
            )
        )

        val error = runCatching {
            service.cancelOrphanedOrders(persisted, emptySet(), 7L)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.startsWith("orphan_order_has_fill:order-1"))
    }

    @Test
    fun `orphan order without terminal confirmation fails closed`() = runTest {
        val port = RecoveryExecutionPort().apply {
            statusAfterCancel = "LIVE"
        }
        val service = service(port)
        val persisted = state(
            market(
                conditionId = "orphan",
                inventory = "0",
                activeOrderId = "order-1",
                matched = "0"
            )
        )

        val error = runCatching {
            service.cancelOrphanedOrders(persisted, emptySet(), 7L)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.startsWith("orphan_order_state_unknown:order-1"))
        assertEquals(3, port.getOrderCount)
    }

    @Test
    fun `orphan inventory rebuilds settlement-only market metadata`() {
        val service = service(RecoveryExecutionPort())
        val saved = market(
            conditionId = "orphan",
            inventory = "5.00",
            activeOrderId = null,
            matched = null
        ).copy(
            favoriteIndex = 1,
            negativeRisk = true,
            endTimestamp = 1_900_000_000L,
            tickSize = 0.01
        )

        val recovered = service.recoveryMarket(saved)

        assertEquals(1, recovered.favoriteIndex)
        assertEquals("token", recovered.favoriteTokenId)
        assertEquals(true, recovered.negRisk)
        assertEquals(1_900_000_000L, recovered.endTimestamp)
    }

    @Test
    fun `startup reconciles a known buy that filled while process was down`() = runTest {
        val port = RecoveryExecutionPort().apply {
            positionSizes = mapOf("filled" to BigDecimal("5.00"))
            orderSide = EventMakerSide.BUY
            orderPrice = BigDecimal("0.96")
            orderConditionId = "filled"
            orderMatchedShares = BigDecimal("5.00")
            statusAfterCancel = "FILLED"
        }
        val service = service(port)
        val persisted = state(buyPendingMarket())

        val reconciled = service.reconcilePersistedOrdersBeforeDiscovery(
            persisted = persisted,
            config = EventMakerConfig(),
            accountId = 7L,
            now = 2_000L
        )
        service.ensureNoUnmanagedPositions(7L, reconciled)

        val saved = reconciled!!.markets.single()
        assertEquals("5.00", saved.inventoryShares)
        assertNull(saved.activeOrderId)
        assertEquals(1, saved.fills.orEmpty().size)
        assertEquals(0, port.cancelCount)
    }
    @Test
    fun `startup sell fill updates cumulative realized pnl`() = runTest {
        val port = RecoveryExecutionPort().apply {
            orderSide = EventMakerSide.SELL
            orderPrice = BigDecimal("0.97")
            orderConditionId = "filled-sell"
            orderMatchedShares = BigDecimal("5.00")
            statusAfterCancel = "FILLED"
        }
        val service = service(port)
        val persisted = state(sellPendingMarket())

        val reconciled = service.reconcilePersistedOrdersBeforeDiscovery(
            persisted = persisted,
            config = EventMakerConfig(),
            accountId = 7L,
            now = 2_000L
        )
        service.ensureNoUnmanagedPositions(7L, reconciled)

        val saved = reconciled!!.markets.single()
        assertEquals(0.05, reconciled.cumulativeRealizedPnl, 1e-9)
        assertEquals(0.05, saved.realizedPnl, 1e-9)
        assertEquals("0", saved.inventoryShares)
        assertEquals(1, saved.completedCycles)
        assertNull(saved.activeOrderId)
    }
    @Test
    fun `multi leg config creates one state per offset and round trips through persistence`() {
        val service = service(RecoveryExecutionPort())
        val config = EventMakerConfig(ladderOffsetsCents = listOf(0.0, 0.5, 1.0, 2.0))
        val states = service.liveStatesForMarket(liveMarket(), config, emptyList())

        assertEquals(listOf(0.0, 0.5, 1.0, 2.0), states.map { it.legOffsetCents })

        val persisted = EventMakerPersistedState(
            mode = EventMakerMode.SHADOW.name,
            accountId = null,
            startedAt = 1_000L,
            dailyPnlDay = 20_000L,
            dailyPnlBaseline = 0.0,
            markets = states.map { it.toPersisted() }
        )
        val projected = service.projectPersistedState(persisted, states)!!

        assertEquals(4, projected.markets.size)
        states.forEach { state ->
            val saved = projected.markets.first { it.legOffsetCents == state.legOffsetCents }
            assertTrue(service.stateForPersisted(saved, states) === state)
        }
    }

    @Test
    fun `position check sums ladder legs per condition`() = runTest {
        val port = RecoveryExecutionPort().apply {
            positionSizes = mapOf("ladder" to BigDecimal("5.00"))
        }
        val service = service(port)
        val persisted = EventMakerPersistedState(
            mode = EventMakerMode.LIVE.name,
            accountId = 7L,
            startedAt = 1_000L,
            dailyPnlDay = 20_000L,
            dailyPnlBaseline = 0.0,
            markets = listOf(
                persistedLeg("ladder", 0.0, "2.00"),
                persistedLeg("ladder", 1.0, "3.00")
            )
        )

        service.ensureNoUnmanagedPositions(7L, persisted)
    }
    @Test
    fun `pending redemption with absent position is marked complete on startup`() = runTest {
        val port = RecoveryExecutionPort().apply {
            settlementPosition = EventMakerSettlementPosition(
                tokenId = "token",
                shares = BigDecimal.ZERO,
                redeemable = false,
                outcomeIndex = 0,
                negativeRisk = false
            )
        }
        val service = service(port)
        val persisted = settledState()

        val recovered = service.recoverPendingRedemptions(persisted, 7L)

        val record = recovered!!.markets.single().settlements!!.single()
        assertEquals(EventMakerRedemptionStatus.ALREADY_ABSENT, record.redemptionStatus)
        assertEquals(0, port.redeemSettlementCount)
    }

    @Test
    fun `pending redemption recovery aggregates ladder legs into one redeem call`() = runTest {
        val port = RecoveryExecutionPort().apply {
            settlementPosition = EventMakerSettlementPosition(
                tokenId = "token",
                shares = BigDecimal("10.00"),
                redeemable = true,
                outcomeIndex = 0,
                negativeRisk = false
            )
        }
        val service = service(port)
        val persisted = EventMakerPersistedState(
            mode = EventMakerMode.LIVE.name,
            accountId = 7L,
            startedAt = 1_000L,
            dailyPnlDay = 20_000L,
            dailyPnlBaseline = 0.0,
            markets = listOf(
                persistedLeg("ladder", 0.0, "0").copy(
                    settlements = listOf(pendingSettlement("buy-0", "settlement:ladder:buy-0:2000", "5.00"))
                ),
                persistedLeg("ladder", 1.0, "0").copy(
                    settlements = listOf(pendingSettlement("buy-1", "settlement:ladder:buy-1:2000", "5.00"))
                )
            )
        )

        val recovered = service.recoverPendingRedemptions(persisted, 7L)!!

        assertEquals(1, port.redeemSettlementCount)
        val records = recovered.markets.flatMap { it.settlements.orEmpty() }
        assertEquals(2, records.size)
        assertTrue(records.all { it.redemptionStatus == EventMakerRedemptionStatus.REDEEMED })
        assertTrue(records.all { it.transactionHash == "0xtx" })
    }
    @Test
    fun `pending redemption retries and persists transaction hash on startup`() = runTest {
        val port = RecoveryExecutionPort().apply {
            settlementPosition = EventMakerSettlementPosition(
                tokenId = "token",
                shares = BigDecimal("10.00"),
                redeemable = true,
                outcomeIndex = 0,
                negativeRisk = false
            )
        }
        val service = service(port)
        val persisted = settledState()

        val recovered = service.recoverPendingRedemptions(persisted, 7L)

        val record = recovered!!.markets.single().settlements!!.single()
        assertEquals(EventMakerRedemptionStatus.REDEEMED, record.redemptionStatus)
        assertEquals("0xtx", record.transactionHash)
        assertEquals(1, port.redeemSettlementCount)
    }
    private fun service(port: RecoveryExecutionPort) = EventMakerStrategyService(
        proxyUrl = "",
        riskPolicy = EventMakerRiskPolicy(),
        quotePlanner = EventMakerQuotePlanner(),
        executionPort = port,
        shadowExecutionPort = EventMakerShadowExecutionPort(),
        stateStore = EventMakerStateStore(tempDir.resolve("state.json").toString())
    )

    private fun state(market: EventMakerPersistedMarket) = EventMakerPersistedState(
        mode = EventMakerMode.LIVE.name,
        accountId = 7L,
        startedAt = 1_000L,
        dailyPnlDay = 20_000L,
        dailyPnlBaseline = 0.0,
        markets = listOf(market)
    )

    private fun settledState(): EventMakerPersistedState {
        val settlement = EventMakerSettlementRecord(
            marketId = "condition",
            favoriteTokenId = "token",
            entryOrderId = "buy-1",
            cycleExitOrderId = "settlement:condition:2000",
            settledAt = 2_000L,
            entryAt = 1_000L,
            entryPrice = BigDecimal("0.96"),
            exitPrice = BigDecimal.ONE,
            shares = BigDecimal("10.00"),
            pnl = 0.4,
            outcomeIndex = 0,
            negativeRisk = false,
            redemptionStatus = EventMakerRedemptionStatus.PENDING
        )
        return EventMakerPersistedState(
            mode = EventMakerMode.LIVE.name,
            accountId = 7L,
            startedAt = 1_000L,
            cumulativeRealizedPnl = 0.4,
            dailyPnlDay = 20_000L,
            dailyPnlBaseline = 0.0,
            markets = listOf(
                EventMakerPersistedMarket(
                    conditionId = "condition",
                    phase = "FLAT",
                    inventoryShares = "0",
                    entryPrice = "0",
                    entryAt = 0L,
                    realizedPnl = 0.4,
                    completedCycles = 1,
                    activeOrderId = null,
                    activeOrderSide = null,
                    activeOrderPrice = null,
                    activeOrderOriginalShares = null,
                    activeOrderMatchedShares = null,
                    activeOrderStatus = null,
                    currentEntryOrderId = null,
                    currentEntryShares = "0",
                    fills = listOf(
                        EventMakerFillRecord(
                            marketId = "condition",
                            orderId = "buy-1",
                            side = EventMakerSide.BUY,
                            price = BigDecimal("0.96"),
                            shares = BigDecimal("10.00"),
                            timestamp = 1_000L
                        )
                    ),
                    cycles = listOf(
                        EventMakerCycleRecord(
                            marketId = "condition",
                            entryOrderId = "buy-1",
                            exitOrderId = "settlement:condition:2000",
                            entryAt = 1_000L,
                            exitAt = 2_000L,
                            entryPrice = BigDecimal("0.96"),
                            exitPrice = BigDecimal.ONE,
                            shares = BigDecimal("10.00"),
                            pnl = 0.4,
                            holdingSeconds = 1_000L,
                            exitType = "SETTLE"
                        )
                    ),
                    favoriteTokenId = "token",
                    settlements = listOf(settlement)
                )
            )
        )
    }

    private fun market(
        conditionId: String,
        inventory: String,
        activeOrderId: String?,
        matched: String?
    ) = EventMakerPersistedMarket(
        conditionId = conditionId,
        phase = if (activeOrderId == null) "LONG" else "SELL_PENDING",
        inventoryShares = inventory,
        entryPrice = "0.96",
        entryAt = 1_000L,
        realizedPnl = 0.0,
        completedCycles = 0,
        activeOrderId = activeOrderId,
        activeOrderSide = activeOrderId?.let { "SELL" },
        activeOrderPrice = activeOrderId?.let { "0.97" },
        activeOrderOriginalShares = activeOrderId?.let { "5.00" },
        activeOrderMatchedShares = matched,
        activeOrderStatus = activeOrderId?.let { "LIVE" },
        activeOrderCreatedAt = activeOrderId?.let { 1_100L } ?: 0L,
        currentEntryOrderId = "buy-1",
        currentEntryShares = inventory,
        fills = emptyList(),
        cycles = emptyList(),
        favoriteTokenId = "token"
    )

    private fun buyPendingMarket() = EventMakerPersistedMarket(
        conditionId = "filled",
        phase = "BUY_PENDING",
        inventoryShares = "0",
        entryPrice = "0",
        entryAt = 0L,
        realizedPnl = 0.0,
        completedCycles = 0,
        activeOrderId = "buy-1",
        activeOrderSide = "BUY",
        activeOrderPrice = "0.96",
        activeOrderOriginalShares = "5.00",
        activeOrderMatchedShares = "0",
        activeOrderStatus = "LIVE",
        activeOrderCreatedAt = 1_100L,
        currentEntryOrderId = null,
        currentEntryShares = "0",
        fills = emptyList(),
        cycles = emptyList(),
        favoriteTokenId = "token",
        favoriteIndex = 0,
        negativeRisk = false,
        endTimestamp = 1_900_000_000L,
        tickSize = 0.001
    )
    private fun sellPendingMarket() = EventMakerPersistedMarket(
        conditionId = "filled-sell",
        phase = "SELL_PENDING",
        inventoryShares = "5.00",
        entryPrice = "0.96",
        entryAt = 1_000L,
        realizedPnl = 0.0,
        completedCycles = 0,
        activeOrderId = "sell-1",
        activeOrderSide = "SELL",
        activeOrderPrice = "0.97",
        activeOrderOriginalShares = "5.00",
        activeOrderMatchedShares = "0",
        activeOrderStatus = "LIVE",
        activeOrderCreatedAt = 1_100L,
        totalBuyNotional = 4.8,
        currentEntryOrderId = "buy-1",
        currentEntryShares = "5.00",
        fills = listOf(
            EventMakerFillRecord(
                marketId = "filled-sell",
                orderId = "buy-1",
                side = EventMakerSide.BUY,
                price = BigDecimal("0.96"),
                shares = BigDecimal("5.00"),
                timestamp = 1_000L
            )
        ),
        cycles = emptyList(),
        favoriteTokenId = "token",
        favoriteIndex = 0,
        negativeRisk = false,
        endTimestamp = 1_900_000_000L,
        tickSize = 0.001
    )
    private fun pendingSettlement(
        entryOrderId: String,
        cycleExitOrderId: String,
        shares: String
    ) = EventMakerSettlementRecord(
        marketId = "ladder",
        favoriteTokenId = "token",
        entryOrderId = entryOrderId,
        cycleExitOrderId = cycleExitOrderId,
        settledAt = 2_000L,
        entryAt = 1_000L,
        entryPrice = BigDecimal("0.96"),
        exitPrice = BigDecimal.ONE,
        shares = BigDecimal(shares),
        pnl = 0.2,
        outcomeIndex = 0,
        negativeRisk = false,
        redemptionStatus = EventMakerRedemptionStatus.PENDING
    )
    private fun liveMarket() = LivePaperMarket(
        conditionId = "ladder",
        question = "Ladder",
        tag = "Geopolitics",
        volume24hr = 100_000.0,
        endTimestamp = 1_900_000_000L,
        tickSize = 0.001,
        outcomes = listOf("Yes", "No"),
        tokenIds = listOf("token", "other"),
        favoriteIndex = 0,
        negRisk = false
    )

    private fun persistedLeg(conditionId: String, offsetCents: Double, inventory: String) =
        EventMakerPersistedMarket(
            conditionId = conditionId,
            phase = "LONG",
            inventoryShares = inventory,
            entryPrice = "0.96",
            entryAt = 1_000L,
            realizedPnl = 0.0,
            completedCycles = 0,
            activeOrderId = null,
            activeOrderSide = null,
            activeOrderPrice = null,
            activeOrderOriginalShares = null,
            activeOrderMatchedShares = null,
            activeOrderStatus = null,
            currentEntryOrderId = "buy-$offsetCents",
            currentEntryShares = inventory,
            fills = emptyList(),
            cycles = emptyList(),
            favoriteTokenId = "token",
            favoriteIndex = 0,
            negativeRisk = false,
            endTimestamp = 1_900_000_000L,
            tickSize = 0.001,
            legOffsetCents = offsetCents
        )
    private class RecoveryExecutionPort : EventMakerExecutionPort {
        var cancelCount: Int = 0
            private set
        var getOrderCount: Int = 0
            private set
        var lastStatus: String? = null
        var matchedShares: BigDecimal = BigDecimal.ZERO
        var orderMatchedShares: BigDecimal? = null
        var orderSide: EventMakerSide = EventMakerSide.SELL
        var orderPrice: BigDecimal = BigDecimal("0.97")
        var orderOriginalShares: BigDecimal = BigDecimal("5.00")
        var orderConditionId: String? = null
        var positionSizes: Map<String, BigDecimal> = emptyMap()
        var statusAfterCancel: String = "CANCELLED"
        var cancelFailure: Throwable? = null
        var settlementPosition: EventMakerSettlementPosition? = null
        var redeemSettlementCount: Int = 0
            private set

        override suspend fun validateAccount(accountId: Long): Result<Unit> = Result.success(Unit)

        override suspend fun listOpenOrderIds(accountId: Long): Result<Set<String>> = Result.success(emptySet())

        override suspend fun listOpenPositions(accountId: Long): Result<Map<String, BigDecimal>> =
            Result.success(positionSizes)

        override suspend fun getSettlementPosition(
            accountId: Long,
            settlement: EventMakerSettlementRecord
        ): Result<EventMakerSettlementPosition> =
            settlementPosition?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("settlement_position_missing"))

        override suspend fun redeemSettlement(
            accountId: Long,
            settlement: EventMakerSettlementRecord
        ): Result<String> {
            redeemSettlementCount++
            return Result.success("0xtx")
        }
        override suspend fun place(
            accountId: Long,
            market: LivePaperMarket,
            intent: EventMakerQuoteIntent
        ): Result<EventMakerLiveOrder> = Result.failure(UnsupportedOperationException())

        override suspend fun getOrder(accountId: Long, orderId: String): Result<EventMakerLiveOrder> {
            getOrderCount++
            lastStatus = statusAfterCancel
            return Result.success(
                EventMakerLiveOrder(
                    orderId = orderId,
                    side = orderSide,
                    price = orderPrice,
                    originalShares = orderOriginalShares,
                    matchedShares = orderMatchedShares ?: matchedShares,
                    status = statusAfterCancel,
                    tokenId = "token",
                    conditionId = orderConditionId,
                    makerAddress = "0xproxy"
                )
            )
        }

        override suspend fun cancel(accountId: Long, orderId: String): Result<Unit> {
            cancelCount++
            return Result.success(Unit)
        }
    }
}
