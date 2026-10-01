package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.entity.User
import com.wrbug.polymarketbot.repository.UserRepository
import com.wrbug.polymarketbot.util.JwtUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.handler.MappedInterceptor
import org.springframework.web.util.ServletRequestPathUtils

class JwtAuthenticationInterceptorTest {

    private val jwtUtils = Mockito.mock(JwtUtils::class.java)
    private val userRepository = Mockito.mock(UserRepository::class.java)
    private val interceptor = JwtAuthenticationInterceptor(jwtUtils, userRepository)

    private fun mapped(): MappedInterceptor = MappedInterceptor(
        WebMvcConfig.AUTH_INCLUDE_PATHS.toTypedArray(),
        WebMvcConfig.AUTH_EXCLUDE_PATHS.toTypedArray(),
        interceptor
    )

    private fun request(uri: String): MockHttpServletRequest {
        val request = MockHttpServletRequest("POST", uri)
        ServletRequestPathUtils.parseAndCache(request)
        return request
    }

    @Test
    fun `health and login endpoints are excluded from jwt authentication`() {
        assertFalse(mapped().matches(request("/api/system/health")))
        assertFalse(mapped().matches(request("/api/auth/login")))
        assertFalse(mapped().matches(request("/api/auth/reset-password")))
        assertFalse(mapped().matches(request("/api/auth/check-first-use")))
    }

    @Test
    fun `protected api paths are matched by jwt interceptor`() {
        assertTrue(mapped().matches(request("/api/accounts/list")))
        assertTrue(mapped().matches(request("/api/auth/verify")))
        assertTrue(mapped().matches(request("/api/system/config/get")))
    }

    @Test
    fun `interceptor rejects request without token regardless of raw uri`() {
        for (uri in listOf("/api;/accounts/list", "/%61pi/accounts/list", "/api/accounts/list")) {
            val response = MockHttpServletResponse()
            assertFalse(interceptor.preHandle(request(uri), response, Any()), uri)
            assertTrue(response.contentAsString.contains("\"code\":2001"), uri)
        }
    }

    @Test
    fun `suspicious api paths are rejected by path security filter`() {
        val suspicious = listOf(
            "/api;/accounts/list",
            "/api;jsessionid=1/accounts/list",
            "/%61pi/accounts/list",
            "/api/./accounts/list",
            "/api/../api/accounts/list",
            "/api//accounts/list",
            "/API/accounts/list",
            "/Api/accounts/list",
            "/api%2faccounts/list",
            "/api/auth/login;/../../accounts/list",
            "/api/auth/%6cogin"
        )
        val filter = ApiPathSecurityFilter()
        for (uri in suspicious) {
            assertTrue(ApiPathSecurityFilter.isSuspiciousApiPath(uri), uri)
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(MockHttpServletRequest("POST", uri), response, chain)
            assertEquals(401, response.status, uri)
            assertEquals(null, chain.request, uri)
        }
    }

    @Test
    fun `normal paths pass path security filter`() {
        for (uri in listOf("/api/accounts/list", "/api/system/health", "/", "/assets/index.js", "/ws")) {
            assertFalse(ApiPathSecurityFilter.isSuspiciousApiPath(uri), uri)
        }
    }

    @Test
    fun `token of deleted user is rejected`() {
        Mockito.`when`(jwtUtils.validateToken("t")).thenReturn(true)
        Mockito.`when`(jwtUtils.getUsernameFromToken("t")).thenReturn("deleted")
        Mockito.`when`(jwtUtils.getTokenVersionFromToken("t")).thenReturn(0L)
        Mockito.`when`(userRepository.findByUsername("deleted")).thenReturn(null)
        val req = request("/api/accounts/list")
        req.addHeader("Authorization", "Bearer t")
        val response = MockHttpServletResponse()

        assertFalse(interceptor.preHandle(req, response, Any()))
        assertTrue(response.contentAsString.contains("\"code\":2001"))
        assertEquals(null, req.getAttribute("username"))
    }

    @Test
    fun `valid token sets username and admin flag`() {
        Mockito.`when`(jwtUtils.validateToken("t")).thenReturn(true)
        Mockito.`when`(jwtUtils.getUsernameFromToken("t")).thenReturn("admin")
        Mockito.`when`(jwtUtils.getTokenVersionFromToken("t")).thenReturn(2L)
        Mockito.`when`(userRepository.findByUsername("admin"))
            .thenReturn(User(id = 1, username = "admin", password = "x", isDefault = true, tokenVersion = 2))
        val req = request("/api/accounts/list")
        req.addHeader("Authorization", "Bearer t")

        assertTrue(interceptor.preHandle(req, MockHttpServletResponse(), Any()))
        assertEquals("admin", req.getAttribute("username"))
        assertEquals(true, req.getAttribute(JwtAuthenticationInterceptor.ATTR_IS_ADMIN))
    }
}
