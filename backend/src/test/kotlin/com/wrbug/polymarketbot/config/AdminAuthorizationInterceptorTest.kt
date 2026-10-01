package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.service.system.SystemConfigService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.MessageSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.handler.MappedInterceptor
import org.springframework.web.util.ServletRequestPathUtils

class AdminAuthorizationInterceptorTest {

    private val interceptor = AdminAuthorizationInterceptor(Mockito.mock(MessageSource::class.java))

    private fun mapped() = MappedInterceptor(
        WebMvcConfig.ADMIN_INCLUDE_PATHS.toTypedArray(),
        WebMvcConfig.ADMIN_EXCLUDE_PATHS.toTypedArray(),
        interceptor
    )

    private fun request(uri: String, isAdmin: Boolean?): MockHttpServletRequest {
        val request = MockHttpServletRequest("POST", uri)
        ServletRequestPathUtils.parseAndCache(request)
        request.setAttribute("username", "u")
        if (isAdmin != null) {
            request.setAttribute(JwtAuthenticationInterceptor.ATTR_IS_ADMIN, isAdmin)
        }
        return request
    }

    @Test
    fun `system write and sensitive endpoints require admin`() {
        val protected = listOf(
            "/api/system/config/builder-api-key/update",
            "/api/system/config/auto-redeem/update",
            "/api/system/proxy/get",
            "/api/system/proxy/http/save",
            "/api/system/proxy/api-health-check",
            "/api/system/rpc-nodes/add",
            "/api/system/rpc-nodes/validate",
            "/api/system/notifications/configs/list",
            "/api/system/notifications/configs/update"
        )
        for (uri in protected) {
            assertTrue(mapped().matches(request(uri, false)), uri)
            val response = MockHttpServletResponse()
            assertFalse(interceptor.preHandle(request(uri, false), response, Any()), uri)
            assertEquals(403, response.status, uri)
            assertTrue(response.contentAsString.contains("\"code\":2004"), uri)
            assertTrue(interceptor.preHandle(request(uri, true), MockHttpServletResponse(), Any()), uri)
        }
    }

    @Test
    fun `user management and non-sensitive reads are not covered by admin interceptor`() {
        for (uri in listOf(
            "/api/system/users/list",
            "/api/system/users/update-own-password",
            "/api/system/config/get",
            "/api/system/config/auto-redeem/status",
            "/api/system/config/builder-api-key/check",
            "/api/system/health"
        )) {
            assertFalse(mapped().matches(request(uri, false)), uri)
        }
    }

    @Test
    fun `missing admin attribute is denied`() {
        assertFalse(interceptor.preHandle(request("/api/system/proxy/get", null), MockHttpServletResponse(), Any()))
    }

    @Test
    fun `secrets are masked and masked values are recognized`() {
        val apiKey = "019a1234-abcd-7def-8888-1234567890ab"
        val masked = SystemConfigService.maskSecret(apiKey, visibleChars = 4)
        assertEquals("019a****90ab", masked)
        assertFalse(masked.contains("abcd"))
        assertEquals("********", SystemConfigService.maskSecret("short", visibleChars = 4))
        assertEquals("********", SystemConfigService.maskSecret(apiKey, visibleChars = 0))
        assertTrue(SystemConfigService.isMaskedValue(masked))
        assertFalse(SystemConfigService.isMaskedValue(apiKey))
    }
}
