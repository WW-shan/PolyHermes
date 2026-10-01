package com.wrbug.polymarketbot.service.copytrading.statistics

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketContextResolutionRetryTest {

    @Test
    fun `retries a failed buy config once before returning success`() = runBlocking {
        var attempts = 0

        val result = retryBuyConfigProcessing(
            process = {
                attempts++
                if (attempts == 1) throw IllegalStateException("temporary failure")
            },
            waitBeforeRetry = { _ -> }
        )

        assertTrue(result.isSuccess)
        assertEquals(2, attempts)
    }

    @Test
    fun `returns failure when a buy config fails twice`() = runBlocking {
        var attempts = 0

        val result = retryBuyConfigProcessing(
            process = {
                attempts++
                throw IllegalStateException("persistent failure")
            },
            waitBeforeRetry = { _ -> }
        )

        assertTrue(result.isFailure)
        assertEquals(2, attempts)
    }

    @Test
    fun `retries transient market context failures and returns the first success`() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()

        val result = retryMarketContextResolution(
            resolve = {
                attempts++
                if (attempts < 3) {
                    Result.failure(IllegalStateException("temporary market data failure"))
                } else {
                    Result.success("resolved context")
                }
            },
            waitBeforeRetry = { delays.add(it) }
        )

        assertEquals("resolved context", result.getOrThrow())
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 500L), delays)
    }

    @Test
    fun `returns failure after bounded market context retries`() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()

        val result = retryMarketContextResolution<String>(
            resolve = {
                attempts++
                Result.failure(IllegalStateException("persistent market data failure"))
            },
            waitBeforeRetry = { delays.add(it) }
        )

        assertTrue(result.isFailure)
        assertEquals("persistent market data failure", result.exceptionOrNull()?.message)
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 500L), delays)
    }
}
