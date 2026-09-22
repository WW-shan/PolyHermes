package com.wrbug.polymarketbot.service.binance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ChainlinkTwapServiceTest {

    @Test
    fun `parses RTDS snapshot and incremental sixty second TWAP`() {
        val service = ChainlinkTwapService { 1_790_102_125_000L }
        service.ingestMessage(
            """
            {
              "topic": "crypto_prices_twap_sixty",
              "type": "update",
              "timestamp": 1790102065000,
              "payload": {
                "symbol": "btc/usd",
                "window_s": 60,
                "data": [
                  {"timestamp": 1790102000000, "value": 65000.0, "full_accuracy_value": "65000000000000000000000"},
                  {"timestamp": 1790102060000, "value": 65100.0, "full_accuracy_value": "65100000000000000000000"}
                ]
              }
            }
            """.trimIndent()
        )
        service.ingestMessage(
            """
            {
              "topic": "crypto_prices_twap_sixty",
              "type": "update",
              "payload": {
                "symbol": "btc/usd",
                "timestamp": 1790102120000,
                "value": 65200.0,
                "full_accuracy_value": "65200000000000000000000",
                "window_s": 60
              }
            }
            """.trimIndent()
        )

        val (open, current) = service.getOpenClose("btc-updown-5m", 1_790_102_060L)!!
        assertEquals(0, BigDecimal("65100").compareTo(open))
        assertEquals(0, BigDecimal("65200").compareTo(current))
    }

    @Test
    fun `rejects stale TWAP data`() {
        val service = ChainlinkTwapService { 1_790_102_400_000L }
        service.ingestMessage(
            """
            {
              "topic": "crypto_prices_twap_sixty",
              "type": "update",
              "payload": {
                "symbol": "btc/usd",
                "timestamp": 1790102120000,
                "full_accuracy_value": "65200000000000000000000",
                "window_s": 60
              }
            }
            """.trimIndent()
        )

        assertNull(service.getOpenClose("btc-updown-5m", 1_790_102_120L))
    }

    @Test
    fun `open price uses nearest observation instead of always preferring floor`() {
        val service = ChainlinkTwapService { 1_790_102_200_000L }
        service.ingestMessage(
            """
            {
              "topic": "crypto_prices_twap_sixty",
              "type": "subscribe",
              "payload": {
                "symbol": "btc/usd",
                "data": [
                  {"timestamp": 1790102185000, "full_accuracy_value": "65000000000000000000000"},
                  {"timestamp": 1790102199000, "full_accuracy_value": "65100000000000000000000"},
                  {"timestamp": 1790102200000, "full_accuracy_value": "65200000000000000000000"}
                ]
              }
            }
            """.trimIndent()
        )

        val (open, _) = service.getOpenClose("btc-updown-5m", 1_790_102_200L)!!
        assertEquals(0, BigDecimal("65200").compareTo(open))
    }

    @Test
    fun `maps supported market slugs to Chainlink symbols`() {
        val service = ChainlinkTwapService()
        assertEquals("btc/usd", service.marketToSymbol("btc-updown-5m"))
        assertEquals("eth/usd", service.marketToSymbol("eth-updown-15m"))
        assertNull(service.marketToSymbol("unknown-updown-5m"))
    }
}
