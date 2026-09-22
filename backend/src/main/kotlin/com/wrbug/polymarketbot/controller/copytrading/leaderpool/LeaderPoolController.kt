package com.wrbug.polymarketbot.controller.copytrading.leaderpool

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.copytrading.leaderpool.LeaderPoolAlreadyExistsException
import com.wrbug.polymarketbot.service.copytrading.leaderpool.LeaderPoolConfirmRequiredException
import com.wrbug.polymarketbot.service.copytrading.leaderpool.LeaderPoolDuplicateTrialConfigException
import com.wrbug.polymarketbot.service.copytrading.leaderpool.LeaderPoolNotFoundException
import com.wrbug.polymarketbot.service.copytrading.leaderpool.LeaderPoolResearchCandidateNotReadyException
import com.wrbug.polymarketbot.service.copytrading.leaderpool.LeaderPoolOptimizationService
import com.wrbug.polymarketbot.service.copytrading.leaderpool.LeaderPoolService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/copy-trading/leader-pool")
class LeaderPoolController(
    private val leaderPoolService: LeaderPoolService,
    private val optimizationService: LeaderPoolOptimizationService,
    private val messageSource: MessageSource
) {
    private val logger = LoggerFactory.getLogger(LeaderPoolController::class.java)

    @PostMapping("/list")
    fun list(@RequestBody request: LeaderPoolListRequest): ResponseEntity<ApiResponse<LeaderPoolListResponse>> {
        return try {
            leaderPoolService.getPoolList(request).fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("查询 Leader 池失败: ${e.message}", e)
                    errorResponse(e, ErrorCode.SERVER_LEADER_POOL_LIST_FETCH_FAILED)
                }
            )
        } catch (e: Exception) {
            logger.error("查询 Leader 池异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_POOL_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/optimization")
    fun optimization(@RequestBody(required = false) request: LeaderPoolOptimizationRequest?): ResponseEntity<ApiResponse<LeaderPoolOptimizationResponse>> {
        return try {
            val limit = request?.limit ?: LeaderPoolOptimizationService.DEFAULT_LIMIT
            ResponseEntity.ok(ApiResponse.success(optimizationService.getOptimization(limit)))
        } catch (e: Exception) {
            logger.error("查询 Leader 池优化排名异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_POOL_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/add")
    fun add(@RequestBody request: LeaderPoolAddRequest): ResponseEntity<ApiResponse<LeaderPoolItemDto>> {
        return try {
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ID_INVALID, messageSource = messageSource))
            }
            leaderPoolService.addToPool(request).fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("加入 Leader 池失败: ${e.message}", e)
                    errorResponse(e, ErrorCode.SERVER_LEADER_POOL_SAVE_FAILED)
                }
            )
        } catch (e: Exception) {
            logger.error("加入 Leader 池异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_POOL_SAVE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/update-status")
    fun updateStatus(@RequestBody request: LeaderPoolUpdateStatusRequest): ResponseEntity<ApiResponse<LeaderPoolItemDto>> {
        return try {
            if (request.poolId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INVALID, "poolId 无效", messageSource))
            }
            if (request.status.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_EMPTY, "status 不能为空", messageSource))
            }
            leaderPoolService.updateStatus(request).fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("更新 Leader 池状态失败: ${e.message}", e)
                    errorResponse(e, ErrorCode.SERVER_LEADER_POOL_SAVE_FAILED)
                }
            )
        } catch (e: Exception) {
            logger.error("更新 Leader 池状态异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_POOL_SAVE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/update-plan")
    fun updatePlan(@RequestBody request: LeaderPoolUpdatePlanRequest): ResponseEntity<ApiResponse<LeaderPoolItemDto>> {
        return try {
            if (request.poolId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INVALID, "poolId 无效", messageSource))
            }
            leaderPoolService.updatePlan(request).fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("更新 Leader 池建议配置失败: ${e.message}", e)
                    errorResponse(e, ErrorCode.SERVER_LEADER_POOL_SAVE_FAILED)
                }
            )
        } catch (e: Exception) {
            logger.error("更新 Leader 池建议配置异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_POOL_SAVE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/create-trial-config")
    fun createTrialConfig(@RequestBody request: LeaderPoolCreateTrialConfigRequest): ResponseEntity<ApiResponse<CopyTradingDto>> {
        return try {
            if (request.poolId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INVALID, "poolId 无效", messageSource))
            }
            if (request.accountId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
            }
            leaderPoolService.createTrialConfig(request).fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
                onFailure = { e ->
                    logger.error("创建 Leader 池试跟配置失败: ${e.message}", e)
                    errorResponse(e, ErrorCode.SERVER_LEADER_POOL_CREATE_TRIAL_FAILED)
                }
            )
        } catch (e: Exception) {
            logger.error("创建 Leader 池试跟配置异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_POOL_CREATE_TRIAL_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/remove")
    fun remove(@RequestBody request: LeaderPoolRemoveRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            if (request.poolId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INVALID, "poolId 无效", messageSource))
            }
            leaderPoolService.remove(request).fold(
                onSuccess = { ResponseEntity.ok(ApiResponse.success(Unit)) },
                onFailure = { e ->
                    logger.error("移除 Leader 池项失败: ${e.message}", e)
                    errorResponse(e, ErrorCode.SERVER_LEADER_POOL_SAVE_FAILED)
                }
            )
        } catch (e: Exception) {
            logger.error("移除 Leader 池项异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_POOL_SAVE_FAILED, e.message, messageSource))
        }
    }

    private fun mapErrorCode(e: Throwable, fallback: ErrorCode): ErrorCode {
        return when (e) {
            is LeaderPoolNotFoundException -> ErrorCode.LEADER_POOL_NOT_FOUND
            is LeaderPoolAlreadyExistsException -> ErrorCode.LEADER_POOL_ALREADY_EXISTS
            is LeaderPoolDuplicateTrialConfigException -> ErrorCode.LEADER_POOL_DUPLICATE_TRIAL_CONFIG
            is LeaderPoolConfirmRequiredException -> ErrorCode.LEADER_POOL_CONFIRM_REQUIRED
            is LeaderPoolResearchCandidateNotReadyException -> ErrorCode.LEADER_RESEARCH_CANDIDATE_NOT_READY
            is IllegalArgumentException -> when (e.message) {
                "账户不存在" -> ErrorCode.ACCOUNT_NOT_FOUND
                "Leader 不存在" -> ErrorCode.LEADER_NOT_FOUND
                else -> ErrorCode.PARAM_ERROR
            }
            else -> fallback
        }
    }

    private fun <T> errorResponse(e: Throwable, fallback: ErrorCode): ResponseEntity<ApiResponse<T>> {
        val errorCode = mapErrorCode(e, fallback)
        val customMsg = if (usesI18nMessage(errorCode)) null else e.message
        return ResponseEntity.ok(ApiResponse.error(errorCode, customMsg, messageSource))
    }

    private fun usesI18nMessage(errorCode: ErrorCode): Boolean {
        return errorCode == ErrorCode.LEADER_POOL_NOT_FOUND ||
            errorCode == ErrorCode.LEADER_POOL_ALREADY_EXISTS ||
            errorCode == ErrorCode.LEADER_POOL_DUPLICATE_TRIAL_CONFIG ||
            errorCode == ErrorCode.LEADER_POOL_CONFIRM_REQUIRED ||
            errorCode == ErrorCode.LEADER_RESEARCH_CANDIDATE_NOT_READY ||
            errorCode == ErrorCode.ACCOUNT_NOT_FOUND ||
            errorCode == ErrorCode.LEADER_NOT_FOUND
    }
}
