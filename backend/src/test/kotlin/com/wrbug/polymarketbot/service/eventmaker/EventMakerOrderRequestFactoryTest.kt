package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.api.SignedOrderObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventMakerOrderRequestFactoryTest {
    @Test
    fun `builds post only GTC request`() {
        val signed = SignedOrderObject(
            salt = 1L,
            maker = "0xmaker",
            signer = "0xsigner",
            taker = "0x0000000000000000000000000000000000000000",
            tokenId = "token",
            makerAmount = "100",
            takerAmount = "100",
            side = "BUY",
            signatureType = 2,
            timestamp = "1",
            expiration = "0",
            metadata = "0x" + "0".repeat(64),
            builder = "0x" + "0".repeat(64),
            signature = "0xsignature"
        )

        val request = EventMakerOrderRequestFactory.build(signed, "api-key")

        assertTrue(request.postOnly)
        assertEquals("GTC", request.orderType)
        assertEquals("api-key", request.owner)
        assertEquals("BUY", request.order.side)
    }
}