package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class EventMakerStrategyServiceSafetyTest {
    @TempDir
    lateinit var tempDir: Path

    private val executionPort = NoopExecutionPort()

    @Test
    fun `live mode is rejected unless explicitly enabled`() {
        val service = service()

        val result = service.start(EventMakerConfig(mode = EventMakerMode.LIVE, accountId = 1L))

        assertTrue(result.isFailure)
        assertEquals("live_trading_disabled", result.exceptionOrNull()?.message)
        assertFalse(service.currentStatus().running)
    }

    @Test
    fun `live mode requires account`() {
        val service = service()

        val result = service.start(
            EventMakerConfig(mode = EventMakerMode.LIVE, liveTradingEnabled = true)
        )

        assertTrue(result.isFailure)
        assertEquals("live_account_required", result.exceptionOrNull()?.message)
        assertFalse(service.currentStatus().running)
    }

    @Test
    fun `live mode rejects persisted state owned by another account`() {
        val stateFile = tempDir.resolve("state.json")
        EventMakerStateStore(stateFile.toString()).save(
            EventMakerPersistedState(
                mode = EventMakerMode.LIVE.name,
                accountId = 99L,
                startedAt = 1_000L,
                markets = emptyList()
            )
        )
        val service = service(stateFile)

        val result = service.start(
            EventMakerConfig(mode = EventMakerMode.LIVE, accountId = 1L, liveTradingEnabled = true)
        )

        assertTrue(result.isFailure)
        assertEquals("persisted_state_account_mismatch:99!=1", result.exceptionOrNull()?.message)
        assertEquals(0, executionPort.validationCount)
        assertFalse(service.currentStatus().running)
    }

    @Test
    fun `live mode rejects corrupt persisted state`() {
        val stateFile = tempDir.resolve("state.json")
        Files.writeString(stateFile, "not-json")
        val service = service(stateFile)

        val result = service.start(
            EventMakerConfig(mode = EventMakerMode.LIVE, accountId = 1L, liveTradingEnabled = true)
        )

        assertTrue(result.isFailure)
        assertEquals("persisted_state_rejected:event_maker_state_corrupt", result.exceptionOrNull()?.message)
        assertEquals(0, executionPort.validationCount)
        assertFalse(service.currentStatus().running)
    }

    @Test
    fun `live mode rejects active orders that are not in persisted state`() {
        val service = service()
        executionPort.openOrderIds = setOf("orphan-1")

        val result = service.start(
            EventMakerConfig(mode = EventMakerMode.LIVE, accountId = 1L, liveTradingEnabled = true)
        )

        assertTrue(result.isFailure)
        assertEquals("unmanaged_open_orders:orphan-1", result.exceptionOrNull()?.message)
        assertEquals(1, executionPort.validationCount)
        assertFalse(service.currentStatus().running)
    }

    @Test
    fun `live mode rejects positions that are not in persisted state`() {
        val service = service()
        executionPort.positionSizes = mapOf("orphan-position" to BigDecimal.ONE)

        val result = service.start(
            EventMakerConfig(mode = EventMakerMode.LIVE, accountId = 1L, liveTradingEnabled = true)
        )

        assertTrue(result.isFailure)
        assertEquals(
            "open_position_state_mismatch:unmanaged=orphan-position:missing=:size_mismatch=",
            result.exceptionOrNull()?.message
        )
        assertEquals(1, executionPort.validationCount)
        assertFalse(service.currentStatus().running)
    }

    @Test
    fun `live mode rejects position size mismatch`() {
        val stateFile = tempDir.resolve("state.json")
        val persistedMarket = EventMakerPersistedMarket(
            conditionId = "condition-1",
            phase = "LONG",
            inventoryShares = "5.00",
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
            currentEntryOrderId = "buy-1",
            currentEntryShares = "5.00",
            fills = emptyList(),
            cycles = emptyList(),
            favoriteTokenId = "token"
        )
        EventMakerStateStore(stateFile.toString()).save(
            EventMakerPersistedState(
                mode = EventMakerMode.LIVE.name,
                accountId = 1L,
                startedAt = 1_000L,
                markets = listOf(persistedMarket)
            )
        )
        val service = service(stateFile)
        executionPort.positionSizes = mapOf("condition-1" to BigDecimal("3.00"))

        val result = service.start(
            EventMakerConfig(mode = EventMakerMode.LIVE, accountId = 1L, liveTradingEnabled = true)
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message!!.contains("size_mismatch=condition-1:3.00!=5.00"))
        assertFalse(service.currentStatus().running)
    }

    @Test
    fun `realized pnl baseline carries prior profit without double counting restored markets`() {
        val service = service()
        val persisted = EventMakerPersistedState(
            mode = EventMakerMode.LIVE.name,
            accountId = 1L,
            startedAt = 1_000L,
            cumulativeRealizedPnl = 1.25,
            markets = emptyList()
        )

        val baseline = service.restoredRealizedBaseline(persisted, restoredRealizedPnl = 0.75)

        assertEquals(0.50, baseline, 1e-9)
    }

    private fun service(stateFile: Path = tempDir.resolve("unused-state.json")) = EventMakerStrategyService(
        proxyUrl = "",
        riskPolicy = EventMakerRiskPolicy(),
        quotePlanner = EventMakerQuotePlanner(),
        executionPort = executionPort,
        shadowExecutionPort = EventMakerShadowExecutionPort(),
        stateStore = EventMakerStateStore(stateFile.toString())
    )

    private class NoopExecutionPort : EventMakerExecutionPort {
        var validationCount: Int = 0
            private set
        var openOrderIds: Set<String> = emptySet()
        var positionSizes: Map<String, BigDecimal> = emptyMap()

        override suspend fun validateAccount(accountId: Long): Result<Unit> {
            validationCount++
            return Result.success(Unit)
        }

        override suspend fun listOpenOrderIds(accountId: Long): Result<Set<String>> =
            Result.success(openOrderIds)

        override suspend fun listOpenPositions(accountId: Long): Result<Map<String, BigDecimal>> =
            Result.success(positionSizes)

        override suspend fun place(
            accountId: Long,
            market: LivePaperMarket,
            intent: EventMakerQuoteIntent
        ): Result<EventMakerLiveOrder> = Result.failure(UnsupportedOperationException())

        override suspend fun getOrder(accountId: Long, orderId: String): Result<EventMakerLiveOrder> =
            Result.failure(UnsupportedOperationException())

        override suspend fun cancel(accountId: Long, orderId: String): Result<Unit> =
            Result.failure(UnsupportedOperationException())
    }
}