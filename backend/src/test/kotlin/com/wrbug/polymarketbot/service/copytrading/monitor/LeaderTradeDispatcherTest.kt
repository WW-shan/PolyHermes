package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.api.TradeResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class LeaderTradeDispatcherTest {

    @Test
    fun `delivery returns false when trade processing fails`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dispatcher = LeaderTradeDispatcher(scope) { _, _, _ ->
            Result.failure(IllegalStateException("temporary processing failure"))
        }

        try {
            assertFalse(dispatcher.deliver(1L, trade(), "test"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `delivery returns only after trade processing succeeds`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val completed = AtomicBoolean(false)
        val dispatcher = LeaderTradeDispatcher(scope) { _, _, _ ->
            delay(20)
            completed.set(true)
            Result.success(Unit)
        }

        try {
            assertTrue(dispatcher.deliver(1L, trade(), "test"))
            assertTrue(completed.get())
        } finally {
            scope.cancel()
        }
    }

    private fun trade() = TradeResponse(
        id = "trade-1", market = "market-1", side = "BUY", price = "0.5", size = "1", timestamp = "1", user = null
    )
}
