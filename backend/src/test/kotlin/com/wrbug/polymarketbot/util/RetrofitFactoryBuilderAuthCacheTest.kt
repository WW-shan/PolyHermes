package com.wrbug.polymarketbot.util

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RetrofitFactoryBuilderAuthCacheTest {

    @Test
    fun `rebuilds relayer client when builder credentials change`() {
        val factory = RetrofitFactory(Gson())
        try {
            val first = factory.createBuilderRelayerApi(
                relayerUrl = "https://relayer-v2.polymarket.com",
                apiKey = "key-1",
                secret = "secret-1",
                passphrase = "pass-1"
            )
            val sameCredentials = factory.createBuilderRelayerApi(
                relayerUrl = "https://relayer-v2.polymarket.com",
                apiKey = "key-1",
                secret = "secret-1",
                passphrase = "pass-1"
            )
            val rotatedCredentials = factory.createBuilderRelayerApi(
                relayerUrl = "https://relayer-v2.polymarket.com",
                apiKey = "key-2",
                secret = "secret-2",
                passphrase = "pass-2"
            )

            assertSame(first, sameCredentials)
            assertNotSame(first, rotatedCredentials)
        } finally {
            factory.destroy()
        }
    }
}
