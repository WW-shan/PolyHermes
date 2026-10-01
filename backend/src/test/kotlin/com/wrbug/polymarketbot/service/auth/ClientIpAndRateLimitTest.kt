package com.wrbug.polymarketbot.service.auth

import com.wrbug.polymarketbot.service.common.RateLimitService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class ClientIpAndRateLimitTest {

    private fun request(remoteAddr: String, xff: String? = null, realIp: String? = null): MockHttpServletRequest {
        val request = MockHttpServletRequest("POST", "/api/auth/login")
        request.remoteAddr = remoteAddr
        xff?.let { request.addHeader("X-Forwarded-For", it) }
        realIp?.let { request.addHeader("X-Real-IP", it) }
        return request
    }

    @Test
    fun `forged forwarded headers from untrusted client are ignored`() {
        val resolver = ClientIpResolver()
        assertEquals("203.0.113.9", resolver.resolve(request("203.0.113.9", xff = "1.2.3.4", realIp = "5.6.7.8")))
    }

    @Test
    fun `headers from local nginx are trusted`() {
        val resolver = ClientIpResolver()
        assertEquals("198.51.100.7", resolver.resolve(request("127.0.0.1", xff = "198.51.100.7", realIp = "198.51.100.7")))
        // 没有 X-Real-IP 时取最右侧地址（最左侧可被客户端伪造）
        assertEquals("198.51.100.7", resolver.resolve(request("127.0.0.1", xff = "1.2.3.4, 198.51.100.7")))
    }

    @Test
    fun `configured trusted proxy is honored`() {
        val resolver = ClientIpResolver("10.0.0.2")
        assertEquals("198.51.100.8", resolver.resolve(request("10.0.0.2", realIp = "198.51.100.8")))
        assertEquals("10.0.0.3", resolver.resolve(request("10.0.0.3", realIp = "198.51.100.8")))
    }

    @Test
    fun `username is locked after repeated failures from different ips`() {
        val service = RateLimitService()
        var lockMsg: String? = null
        for (i in 1..10) {
            lockMsg = service.recordLoginFailure("198.51.100.$i", "Admin")
        }
        assertNotNull(lockMsg)
        assertTrue(service.checkLoginRateLimit("198.51.100.200", "admin").isFailure)
        assertTrue(service.checkLoginRateLimit("198.51.100.200", "other").isSuccess)
    }

    @Test
    fun `reset password limit is per ip with wider global limit`() {
        val service = RateLimitService()
        repeat(3) { assertTrue(service.checkResetPasswordRateLimit("198.51.100.1").isSuccess) }
        assertTrue(service.checkResetPasswordRateLimit("198.51.100.1").isFailure)
        // 另一个 IP（例如管理员）不受影响
        assertTrue(service.checkResetPasswordRateLimit("198.51.100.2").isSuccess)
    }
}
