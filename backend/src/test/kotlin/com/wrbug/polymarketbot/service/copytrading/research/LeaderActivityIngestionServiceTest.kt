package com.wrbug.polymarketbot.service.copytrading.research

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.dto.ActivityTradeMessage
import com.wrbug.polymarketbot.dto.ActivityTradePayload
import com.wrbug.polymarketbot.entity.LeaderActivityEvent
import com.wrbug.polymarketbot.enums.LeaderResearchSourceType
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException

class LeaderActivityIngestionServiceTest {
    private val repository: LeaderActivityEventRepository = mock()
    private val service = LeaderActivityIngestionService(repository, Gson())

    @Test
    fun `ingests valid activity with fallback key and raw hash`() {
        Mockito.`when`(repository.findByStableEventKey(Mockito.anyString())).thenReturn(null)
        Mockito.`when`(repository.save(anyEvent())).thenAnswer { it.arguments[0] }

        val event = service.ingestUserActivity(
            UserActivityResponse(
                proxyWallet = "0x1111111111111111111111111111111111111111",
                timestamp = 1_700_000_000,
                conditionId = "condition-1",
                type = "TRADE",
                size = 10.0,
                price = 0.45,
                asset = "asset-1",
                side = "BUY"
            )
        )

        assertTrue(event.usableForDiscovery)
        assertTrue(event.usableForPaper)
        assertFalse(event.rawPayloadHash.isBlank())
        assertEquals(64, event.rawPayloadHash.length)
        assertNotNull(event.stableEventKey)
    }

    @Test
    fun `records unusable reason for incomplete activity`() {
        Mockito.`when`(repository.findByStableEventKey(Mockito.anyString())).thenReturn(null)
        Mockito.`when`(repository.save(anyEvent())).thenAnswer { it.arguments[0] }

        val event = service.ingestUserActivity(
            UserActivityResponse(
                proxyWallet = "not-a-wallet",
                timestamp = 1_700_000_000,
                conditionId = "",
                type = "TRADE"
            )
        )

        assertFalse(event.usableForDiscovery)
        assertFalse(event.usableForPaper)
        assertTrue(event.unusableReason!!.contains("wallet_missing_or_invalid"))
        assertTrue(event.unusableReason!!.contains("market_missing"))
    }

    @Test
    fun `dedupes the same fill by fill level stable key`() {
        val activity = validActivity(transactionHash = "tx-1")
        val key = service.fillStableKey(
            wallet = "0x1111111111111111111111111111111111111111", txHash = "tx-1", asset = "asset-1",
            side = "BUY", size = java.math.BigDecimal("10"), price = java.math.BigDecimal("0.45")
        )!!
        val existing = LeaderActivityEvent(
            source = "ACTIVITY_DERIVED",
            sourceEventId = "tx-1",
            stableEventKey = key,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            eventTime = 1_700_000_000_000,
            rawPayloadHash = "hash"
        )
        Mockito.`when`(repository.findByStableEventKey(key)).thenReturn(existing)

        val event = service.ingestUserActivity(activity)

        assertEquals(existing, event)
        Mockito.verify(repository, Mockito.never()).save(Mockito.any(LeaderActivityEvent::class.java))
    }

    @Test
    fun `multiple fills in the same tx get distinct stable keys`() {
        val first = service.fillStableKey("0x1", "tx-1", "asset-1", "BUY", java.math.BigDecimal("10"), java.math.BigDecimal("0.45"))
        val second = service.fillStableKey("0x1", "tx-1", "asset-1", "BUY", java.math.BigDecimal("12"), java.math.BigDecimal("0.46"))
        val sameAsFirst = service.fillStableKey("0x1", "TX-1", "asset-1", "buy", java.math.BigDecimal("10.00"), java.math.BigDecimal("0.450"))
        assertTrue(first != second)
        assertEquals(first, sameAsFirst)
    }

    @Test
    fun `dedupes after database uniqueness violation`() {
        val existing = LeaderActivityEvent(
            source = "ACTIVITY_DERIVED",
            stableEventKey = "stable-1",
            eventTime = 1_700_000_000_000,
            rawPayloadHash = "hash"
        )
        Mockito.`when`(repository.findByStableEventKey(Mockito.anyString())).thenReturn(null, existing)
        Mockito.`when`(repository.save(Mockito.any(LeaderActivityEvent::class.java))).thenThrow(DataIntegrityViolationException("duplicate"))

        val event = service.ingestUserActivity(validActivity(transactionHash = null))

        assertEquals(existing, event)
    }

    @Test
    fun `ingests websocket trade before known leader filtering`() {
        Mockito.`when`(repository.findByStableEventKey(Mockito.anyString())).thenReturn(null)
        Mockito.`when`(repository.save(anyEvent())).thenAnswer { it.arguments[0] }

        val event = service.ingestWebSocketTrade(
            ActivityTradeMessage(
                topic = "activity",
                type = "trades",
                payload = ActivityTradePayload(
                    proxyWallet = "0x9999999999999999999999999999999999999999",
                    conditionId = "condition-unknown-leader",
                    side = "BUY",
                    price = "0.42",
                    size = "2.5",
                    asset = "asset-unknown",
                    transactionHash = "ws-tx-1"
                )
            ),
            LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE
        )

        assertEquals("0x9999999999999999999999999999999999999999", event.normalizedWallet)
        assertEquals("GLOBAL_ACTIVITY_CAPTURE", event.source)
        assertTrue(event.usableForDiscovery)
        assertTrue(event.usableForPaper)
    }

    private fun validActivity(transactionHash: String?) = UserActivityResponse(
        proxyWallet = "0x1111111111111111111111111111111111111111",
        timestamp = 1_700_000_000,
        conditionId = "condition-1",
        type = "TRADE",
        size = 10.0,
        transactionHash = transactionHash,
        price = 0.45,
        asset = "asset-1",
        side = "BUY"
    )

    private fun anyEvent(): LeaderActivityEvent {
        Mockito.any(LeaderActivityEvent::class.java)
        return LeaderActivityEvent(source = "ACTIVITY_DERIVED", stableEventKey = "dummy", eventTime = 1, rawPayloadHash = "hash")
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
