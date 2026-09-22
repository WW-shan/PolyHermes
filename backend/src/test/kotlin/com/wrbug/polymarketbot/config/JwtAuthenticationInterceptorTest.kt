package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.repository.UserRepository
import com.wrbug.polymarketbot.util.JwtUtils
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class JwtAuthenticationInterceptorTest {

    @Test
    fun `health endpoint bypasses jwt authentication`() {
        val jwtUtils = Mockito.mock(JwtUtils::class.java)
        val userRepository = Mockito.mock(UserRepository::class.java)
        val request = Mockito.mock(HttpServletRequest::class.java)
        val response = Mockito.mock(HttpServletResponse::class.java)
        Mockito.`when`(request.requestURI).thenReturn("/api/system/health")

        val interceptor = JwtAuthenticationInterceptor(jwtUtils, userRepository)

        assertTrue(interceptor.preHandle(request, response, Any()))
        Mockito.verifyNoInteractions(jwtUtils, userRepository, response)
    }
}
