package com.wrbug.polymarketbot.controller.eventmaker

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.EventMakerStartRequest
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.eventmaker.EventMakerStatus
import com.wrbug.polymarketbot.service.eventmaker.EventMakerStrategyService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/event-maker")
class EventMakerStrategyController(
    private val strategyService: EventMakerStrategyService,
    private val messageSource: MessageSource
) {
    private val logger = LoggerFactory.getLogger(EventMakerStrategyController::class.java)

    @PostMapping("/start")
    fun start(@RequestBody request: EventMakerStartRequest): ResponseEntity<ApiResponse<EventMakerStatus>> {
        return try {
            val result = strategyService.start(request.toConfig())
            result.fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.warn("Event maker start rejected: ${e.message}")
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("Event maker start failed", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    @PostMapping("/stop")
    fun stop(): ResponseEntity<ApiResponse<EventMakerStatus>> =
        ResponseEntity.ok(ApiResponse.success(strategyService.stop()))

    @GetMapping("/status")
    fun status(): ResponseEntity<ApiResponse<EventMakerStatus>> =
        ResponseEntity.ok(ApiResponse.success(strategyService.currentStatus()))

    @PostMapping("/status")
    fun statusPost(): ResponseEntity<ApiResponse<EventMakerStatus>> = status()
}