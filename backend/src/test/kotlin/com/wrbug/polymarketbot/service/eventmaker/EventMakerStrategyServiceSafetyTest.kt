package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventMakerStrategyServiceSafetyTest {
    private val service = EventMakerStrategyService(
        proxyUrl = "",
        riskPolicy = EventMakerRiskPolicy(),
        quotePlanner = EventMakerQuotePlanner(),
        executionPort = NoopExecutionPort(),
        shadowExecutionPort = EventMakerShadowExecutionPort(),
        stateStore = EventMakerStateStore()
    )

    @Test
    fun `live mode is rejected unless explicitly enabled`() {
        val result = service.start(EventMakerConfig(mode = EventMakerMode.LIVE, accountId = 1L))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message == "live_trading_disabled")
        assertFalse(service.currentStatus().running)
    }

    @Test
    fun `live mode requires account`() {
        val result = service.start(
            EventMakerConfig(mode = EventMakerMode.LIVE, liveTradingEnabled = true)
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message == "live_account_required")
        assertFalse(service.currentStatus().running)
    }

    private class NoopExecutionPort : EventMakerExecutionPort {
        override suspend fun validateAccount(accountId: Long): Result<Unit> = Result.success(Unit)

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