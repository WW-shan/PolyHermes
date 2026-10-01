package com.wrbug.polymarketbot.service.system

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigRequest
import com.wrbug.polymarketbot.dto.TelegramConfigData
import com.wrbug.polymarketbot.entity.NotificationConfig
import com.wrbug.polymarketbot.repository.NotificationConfigRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import java.util.Optional

class NotificationConfigMaskingTest {

    private val token = "123456789:AAEexampleTelegramBotTokenValue"
    private val repository = Mockito.mock(NotificationConfigRepository::class.java)
    private val objectMapper = ObjectMapper()
    private val service = NotificationConfigService(repository, objectMapper)
    private val entity = NotificationConfig(
        id = 1L,
        type = "telegram",
        name = "tg",
        enabled = true,
        configJson = objectMapper.writeValueAsString(mapOf("botToken" to token, "chatIds" to listOf("1")))
    )

    @Test
    fun `bot token is masked for display`() {
        Mockito.`when`(repository.findById(1L)).thenReturn(Optional.of(entity))
        val dto = runBlocking { service.getConfigById(1L) }!!
        val masked = service.maskForDisplay(dto)
        val maskedToken = (masked.config as NotificationConfigData.Telegram).data.botToken
        assertFalse(maskedToken.contains("AAEexample"))
        assertEquals(token, runBlocking { service.resolveMaskedBotToken(maskedToken, 1L) })
    }

    @Test
    fun `update with masked bot token keeps original token`() {
        Mockito.`when`(repository.findById(1L)).thenReturn(Optional.of(entity))
        Mockito.`when`(repository.save(any(NotificationConfig::class.java))).thenAnswer { it.arguments[0] }
        val maskedToken = SystemConfigService.maskSecret(token, visibleChars = 4)

        val result = runBlocking {
            service.updateConfig(
                1L,
                NotificationConfigRequest(
                    type = "telegram",
                    name = "tg",
                    config = mapOf("botToken" to maskedToken, "chatIds" to listOf("2"))
                )
            )
        }
        val saved = (result.getOrThrow().config as NotificationConfigData.Telegram).data
        assertEquals(TelegramConfigData(token, listOf("2")), saved)
    }
}
