package com.wrbug.polymarketbot.util

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuilderAuthInterceptorTest {

    @Test
    fun `uses seconds timestamp and matches official signing sdk vector`() {
        val interceptor = BuilderAuthInterceptor(
            apiKey = "test-api-key",
            secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            passphrase = "test-passphrase",
            currentTimeMillis = { 1_000_000_000L }
        )
        val body = """{"hash": "0x123"}"""
        val request = Request.Builder()
            .url("https://relayer-v2.polymarket.com/orders")
            .method("test-sign", body.toRequestBody("application/json".toMediaType()))
            .build()
        val chain = CapturingChain(request)

        interceptor.intercept(chain)

        val signedRequest = requireNotNull(chain.proceededRequest)
        assertEquals("1000000", signedRequest.header("POLY_BUILDER_TIMESTAMP"))
        assertEquals(
            "ZwAdJKvoYRlEKDkNMwd5BuwNNtg93kNaR_oU2HrfVvc=",
            signedRequest.header("POLY_BUILDER_SIGNATURE")
        )
    }

    private class CapturingChain(private val originalRequest: Request) : Interceptor.Chain {
        var proceededRequest: Request? = null

        override fun request(): Request = originalRequest

        override fun proceed(request: Request): Response {
            proceededRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }

        override fun connection(): Connection? = null

        override fun call(): Call = throw UnsupportedOperationException("not used")

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
    }
}
