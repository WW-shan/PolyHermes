package com.wrbug.polymarketbot.controller.eventmaker

import com.wrbug.polymarketbot.dto.EventMakerStartRequest
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.eventmaker.EventMakerConfig
import com.wrbug.polymarketbot.service.eventmaker.EventMakerMode
import com.wrbug.polymarketbot.service.eventmaker.EventMakerStatus
import com.wrbug.polymarketbot.service.eventmaker.EventMakerStrategyService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.support.StaticMessageSource

/**
 * HTTP-layer contract tests for the event-maker REST surface.
 *
 * The strategy service is faked so these tests pin the controller contract:
 * request -> config mapping, success envelope, and fail-closed error mapping.
 */
class EventMakerStrategyControllerTest {

    @Test
    fun `start maps request to config and returns success envelope`() {
        val service = StubStrategyService()
        val controller = controller(service)

        val response = controller.start(
            EventMakerStartRequest(
                mode = "SHADOW",
                maxMarkets = 4,
                minPrice = 0.97,
                maxPrice = 0.99,
                minSpreadTicks = 2,
                minRemainingMinutes = 2880,
                entryOffsetCents = 0.5,
                ladderOffsetsCents = listOf(0.0, 0.5, 1.0),
                flattenBeforeCloseMinutes = 1440,
                flattenSlippageTicks = 2,
                exitSpreadCents = 2.0,
                orderNotional = 25.0,
                shadowFillVolumeFactor = 1.0
            )
        )

        assertEquals(0, response.body!!.code)
        assertTrue(response.body!!.data!!.running)
        assertEquals(1, service.startCount)
        val config = service.lastConfig!!
        assertEquals(EventMakerMode.SHADOW, config.mode)
        assertEquals(4, config.maxMarkets)
        assertEquals(0.97, config.minPrice, 1e-9)
        assertEquals(0.99, config.maxPrice, 1e-9)
        assertEquals(2, config.minSpreadTicks)
        assertEquals(0.5, config.entryOffsetCents, 1e-9)
        assertEquals(listOf(0.0, 0.5, 1.0), config.ladderOffsetsCents)
        assertEquals(1440L, config.flattenBeforeCloseMinutes)
        assertEquals(2, config.flattenSlippageTicks)
        assertEquals(2.0, config.exitSpreadCents, 1e-9)
        assertEquals(25.0, config.orderNotional, 1e-9)
        assertEquals(1.0, config.shadowFillVolumeFactor, 1e-9)
        assertFalse(config.liveTradingEnabled)
    }

    @Test
    fun `start maps live rejection to parameter error envelope`() {
        val service = StubStrategyService(startResult = Result.failure(IllegalStateException("live_trading_disabled")))
        val controller = controller(service)

        val response = controller.start(EventMakerStartRequest(mode = "LIVE", liveTradingEnabled = false))

        assertEquals(ErrorCode.PARAM_ERROR.code, response.body!!.code)
        assertEquals("live_trading_disabled", response.body!!.msg)
        assertEquals(1, service.startCount)
    }

    @Test
    fun `status returns current service status`() {
        val service = StubStrategyService()
        service.stubStatus = runningStatus(running = true)
        val controller = controller(service)

        val response = controller.status()

        assertEquals(0, response.body!!.code)
        assertTrue(response.body!!.data!!.running)
        assertEquals(EventMakerMode.SHADOW, response.body!!.data!!.mode)
    }

    @Test
    fun `stop returns stopped status`() {
        val service = StubStrategyService()
        service.stubStatus = runningStatus(running = false)
        val controller = controller(service)

        val response = controller.stop()

        assertEquals(0, response.body!!.code)
        assertFalse(response.body!!.data!!.running)
        assertEquals(1, service.stopCount)
    }

    @Test
    fun `start maps taker entry settings`() {
        val service = StubStrategyService()
        val controller = controller(service)

        val response = controller.start(
            EventMakerStartRequest(
                mode = "SHADOW",
                takerEntry = true,
                takerEntrySlippageTicks = 2
            )
        )

        assertEquals(0, response.body!!.code)
        assertEquals(1, service.startCount)
        val config = service.lastConfig!!
        assertTrue(config.takerEntry)
        assertEquals(2, config.takerEntrySlippageTicks)
    }

    @Test
    fun `invalid ladder and taker combination maps to parameter error`() {
        val service = StubStrategyService()
        val controller = controller(service)

        val response = controller.start(
            EventMakerStartRequest(
                mode = "SHADOW",
                ladderOffsetsCents = listOf(0.0),
                takerEntry = true
            )
        )

        assertEquals(ErrorCode.PARAM_ERROR.code, response.body!!.code)
        assertEquals(0, service.startCount)
    }
    private fun controller(service: EventMakerStrategyService): EventMakerStrategyController =
        EventMakerStrategyController(service, StaticMessageSource())

    private fun runningStatus(running: Boolean) = EventMakerStatus(
        running = running,
        mode = EventMakerMode.SHADOW,
        startedAt = if (running) 1_000L else null,
        stoppedAt = if (running) null else 2_000L,
        markets = emptyList(),
        trades = 0,
        bookUpdates = 0,
        completedCycles = 0,
        realizedPnl = 0.0,
        unrealizedPnl = 0.0,
        openInventoryShares = 0.0,
        totalInventoryNotional = 0.0
    )

    private class StubStrategyService(
        private val startResult: Result<EventMakerStatus> = Result.success(
            EventMakerStatus(
                running = true,
                mode = EventMakerMode.SHADOW,
                startedAt = 1_000L,
                stoppedAt = null,
                markets = emptyList(),
                trades = 0,
                bookUpdates = 0,
                completedCycles = 0,
                realizedPnl = 0.0,
                unrealizedPnl = 0.0,
                openInventoryShares = 0.0,
                totalInventoryNotional = 0.0
            )
        )
    ) : EventMakerStrategyService(
        proxyUrl = "",
        riskPolicy = com.wrbug.polymarketbot.service.eventmaker.EventMakerRiskPolicy(),
        quotePlanner = com.wrbug.polymarketbot.service.eventmaker.EventMakerQuotePlanner(),
        executionPort = NoopExecutionPort(),
        shadowExecutionPort = com.wrbug.polymarketbot.service.eventmaker.EventMakerShadowExecutionPort(),
        stateStore = com.wrbug.polymarketbot.service.eventmaker.EventMakerStateStore("build/test-tmp/controller-state.json")
    ) {
        var startCount = 0
        var stopCount = 0
        var lastConfig: EventMakerConfig? = null
        var stubStatus: EventMakerStatus = EventMakerStatus(
            running = false,
            mode = EventMakerMode.SHADOW,
            startedAt = null,
            stoppedAt = null,
            markets = emptyList(),
            trades = 0,
            bookUpdates = 0,
            completedCycles = 0,
            realizedPnl = 0.0,
            unrealizedPnl = 0.0,
            openInventoryShares = 0.0,
            totalInventoryNotional = 0.0
        )

        override fun start(config: EventMakerConfig): Result<EventMakerStatus> {
            startCount++
            lastConfig = config
            return startResult
        }

        override fun stop(): EventMakerStatus {
            stopCount++
            return stubStatus
        }

        override fun currentStatus(): EventMakerStatus = stubStatus
    }

    private class NoopExecutionPort : com.wrbug.polymarketbot.service.eventmaker.EventMakerExecutionPort {
        override suspend fun validateAccount(accountId: Long): Result<Unit> = Result.success(Unit)

        override suspend fun listOpenOrderIds(accountId: Long): Result<Set<String>> = Result.success(emptySet())

        override suspend fun listOpenPositions(
            accountId: Long
        ): Result<Map<String, java.math.BigDecimal>> = Result.success(emptyMap())

        override suspend fun place(
            accountId: Long,
            market: com.wrbug.polymarketbot.research.livepaper.LivePaperMarket,
            intent: com.wrbug.polymarketbot.service.eventmaker.EventMakerQuoteIntent
        ): Result<com.wrbug.polymarketbot.service.eventmaker.EventMakerLiveOrder> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getOrder(
            accountId: Long,
            orderId: String
        ): Result<com.wrbug.polymarketbot.service.eventmaker.EventMakerLiveOrder> =
            Result.failure(UnsupportedOperationException())

        override suspend fun cancel(accountId: Long, orderId: String): Result<Unit> =
            Result.failure(UnsupportedOperationException())
    }
}