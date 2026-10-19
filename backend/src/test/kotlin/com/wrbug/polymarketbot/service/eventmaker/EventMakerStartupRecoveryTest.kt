package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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
        markets = listOf(market)
    )

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

    private class RecoveryExecutionPort : EventMakerExecutionPort {
        var cancelCount: Int = 0
            private set
        var getOrderCount: Int = 0
            private set
        var lastStatus: String? = null
        var matchedShares: BigDecimal = BigDecimal.ZERO
        var statusAfterCancel: String = "CANCELLED"
        var cancelFailure: Throwable? = null

        override suspend fun validateAccount(accountId: Long): Result<Unit> = Result.success(Unit)

        override suspend fun listOpenOrderIds(accountId: Long): Result<Set<String>> = Result.success(emptySet())

        override suspend fun listOpenPositions(accountId: Long): Result<Map<String, BigDecimal>> =
            Result.success(emptyMap())

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
                    side = EventMakerSide.SELL,
                    price = BigDecimal("0.97"),
                    originalShares = BigDecimal("5.00"),
                    matchedShares = matchedShares,
                    status = statusAfterCancel
                )
            )
        }

        override suspend fun cancel(accountId: Long, orderId: String): Result<Unit> {
            cancelCount++
            return Result.success(Unit)
        }
    }
}
