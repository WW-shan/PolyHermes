package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.entity.User
import com.wrbug.polymarketbot.repository.UserRepository
import com.wrbug.polymarketbot.service.auth.WebSocketTicketService
import com.wrbug.polymarketbot.util.JwtUtils
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.socket.WebSocketHandler

class WebSocketAuthInterceptorTest {

    private val jwtUtils = Mockito.mock(JwtUtils::class.java)
    private val userRepository = Mockito.mock(UserRepository::class.java)
    private val ticketService = Mockito.mock(WebSocketTicketService::class.java)
    private val interceptor = WebSocketAuthInterceptor(jwtUtils, userRepository, ticketService)
    private val handler = Mockito.mock(WebSocketHandler::class.java)

    private fun handshake(query: String): Boolean {
        val servletRequest = MockHttpServletRequest("GET", "/ws")
        servletRequest.queryString = query
        return interceptor.beforeHandshake(
            ServletServerHttpRequest(servletRequest),
            ServletServerHttpResponse(MockHttpServletResponse()),
            handler,
            mutableMapOf()
        )
    }

    @Test
    fun `jwt of deleted user is rejected`() {
        Mockito.`when`(jwtUtils.validateToken("t")).thenReturn(true)
        Mockito.`when`(jwtUtils.getUsernameFromToken("t")).thenReturn("deleted")
        Mockito.`when`(jwtUtils.getTokenVersionFromToken("t")).thenReturn(0L)
        Mockito.`when`(userRepository.findByUsername("deleted")).thenReturn(null)

        assertFalse(handshake("token=t"))
    }

    @Test
    fun `ticket of deleted user is rejected`() {
        Mockito.`when`(ticketService.validateAndConsumeTicket("k")).thenReturn("deleted")
        Mockito.`when`(userRepository.findByUsername("deleted")).thenReturn(null)

        assertFalse(handshake("ticket=k"))
    }

    @Test
    fun `jwt of existing user with matching version is accepted`() {
        Mockito.`when`(jwtUtils.validateToken("t")).thenReturn(true)
        Mockito.`when`(jwtUtils.getUsernameFromToken("t")).thenReturn("u")
        Mockito.`when`(jwtUtils.getTokenVersionFromToken("t")).thenReturn(1L)
        Mockito.`when`(userRepository.findByUsername("u"))
            .thenReturn(User(id = 2, username = "u", password = "x", tokenVersion = 1))

        assertTrue(handshake("token=t"))
    }
}
