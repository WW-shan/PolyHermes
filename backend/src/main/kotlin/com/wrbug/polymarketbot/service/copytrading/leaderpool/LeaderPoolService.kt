package com.wrbug.polymarketbot.service.copytrading.leaderpool

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.entity.LeaderPool
import com.wrbug.polymarketbot.enums.LeaderPoolStatus
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingService
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchEventService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class LeaderPoolService(
    private val leaderPoolRepository: LeaderPoolRepository,
    private val leaderRepository: LeaderRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val accountRepository: AccountRepository,
    private val copyTradingService: CopyTradingService,
    private val researchEventService: LeaderResearchEventService? = null
) {
    private val logger = LoggerFactory.getLogger(LeaderPoolService::class.java)

    @Transactional
    open fun addToPool(request: LeaderPoolAddRequest): Result<LeaderPoolItemDto> {
        return try {
            val leader = leaderRepository.findById(request.leaderId).orElse(null)
            if (leader == null) {
                logger.warn("拒绝加入 Leader 池，Leader 不存在: leaderId={}", request.leaderId)
                return Result.failure(IllegalArgumentException("Leader 不存在"))
            }

            leaderPoolRepository.findByLeaderId(request.leaderId)?.let {
                logger.warn("Leader 已在池子中: leaderId={}, poolId={}", request.leaderId, it.id)
                return Result.failure(LeaderPoolAlreadyExistsException())
            }

            val now = System.currentTimeMillis()
            val pool = LeaderPool(
                leaderId = request.leaderId,
                source = request.source?.trim().takeUnless { it.isNullOrBlank() } ?: "MANUAL",
                reason = request.reason?.trim().takeUnless { it.isNullOrBlank() },
                notes = request.notes?.trim().takeUnless { it.isNullOrBlank() },
                createdAt = now,
                updatedAt = now
            )

            val saved = try {
                leaderPoolRepository.saveAndFlush(pool)
            } catch (e: DataIntegrityViolationException) {
                logger.warn("并发重复加入 Leader 池: leaderId={}", request.leaderId, e)
                return Result.failure(LeaderPoolAlreadyExistsException())
            }

            logger.info("Leader 加入池子: leaderId={}, poolId={}, status={}", saved.leaderId, saved.id, saved.status)
            Result.success(toDto(saved, leader, emptyList()))
        } catch (e: Exception) {
            logger.error("加入 Leader 池失败: leaderId=${request.leaderId}", e)
            Result.failure(e)
        }
    }

    open fun getPoolList(request: LeaderPoolListRequest): Result<LeaderPoolListResponse> {
        return try {
            val status = request.status?.trim().takeUnless { it.isNullOrBlank() }?.let { parseStatus(it) }
            val pools = if (status != null) {
                leaderPoolRepository.findByStatus(status).sortedByDescending { it.createdAt }
            } else {
                leaderPoolRepository.findAllByOrderByCreatedAtDesc()
            }

            val leaderIds = pools.map { it.leaderId }.distinct()
            val leaders = if (leaderIds.isEmpty()) {
                emptyMap()
            } else {
                leaderRepository.findAllById(leaderIds).associateBy { it.id!! }
            }
            val copyTradingsByLeader = if (leaderIds.isEmpty()) {
                emptyMap()
            } else {
                copyTradingRepository.findByLeaderIdIn(leaderIds).groupBy { it.leaderId }
            }

            val items = pools.mapNotNull { pool ->
                val leader = leaders[pool.leaderId]
                if (leader == null) {
                    logger.warn("Leader 池项缺少 leader 记录: poolId={}, leaderId={}", pool.id, pool.leaderId)
                    null
                } else {
                    toDto(pool, leader, copyTradingsByLeader[pool.leaderId].orEmpty())
                }
            }

            val estimatedWorstExposure = items.fold(BigDecimal.ZERO) { acc, item ->
                acc + BigDecimal(item.estimatedWorstExposure)
            }
            val summary = LeaderPoolSummaryDto(
                totalCount = items.size,
                trialCount = items.count { it.status == LeaderPoolStatus.TRIAL.name || it.status == LeaderPoolStatus.ACTIVE.name },
                estimatedWorstExposure = estimatedWorstExposure.strip(),
                pendingRiskCount = items.count { it.status == LeaderPoolStatus.COOLDOWN.name || it.hasEnabledCopyTrading },
            )

            Result.success(
                LeaderPoolListResponse(
                    summary = summary,
                    list = items,
                    total = items.size
                )
            )
        } catch (e: Exception) {
            logger.error("查询 Leader 池列表失败", e)
            Result.failure(e)
        }
    }

    @Transactional
    open fun updateStatus(request: LeaderPoolUpdateStatusRequest): Result<LeaderPoolItemDto> {
        return try {
            val pool = findPool(request.poolId)
            val leader = findLeader(pool.leaderId)
            val newStatus = parseStatus(request.status)
            val now = System.currentTimeMillis()
            val updated = pool.copy(
                status = newStatus,
                cooldownUntil = if (newStatus == LeaderPoolStatus.COOLDOWN) request.cooldownUntil else request.cooldownUntil ?: pool.cooldownUntil,
                locked = request.locked ?: pool.locked,
                lastReviewedAt = now,
                updatedAt = now
            )

            val saved = leaderPoolRepository.save(updated)
            logger.info(
                "Leader 池状态变化: poolId={}, leaderId={}, status={}, cooldownUntil={}",
                saved.id,
                saved.leaderId,
                saved.status,
                saved.cooldownUntil
            )
            Result.success(toDto(saved, leader, copyTradingRepository.findByLeaderId(saved.leaderId)))
        } catch (e: Exception) {
            logger.error("更新 Leader 池状态失败: poolId=${request.poolId}", e)
            Result.failure(e)
        }
    }

    @Transactional
    open fun updatePlan(request: LeaderPoolUpdatePlanRequest): Result<LeaderPoolItemDto> {
        return try {
            val pool = findPool(request.poolId)
            val leader = findLeader(pool.leaderId)
            val nextFixedAmount = parseDecimal("suggestedFixedAmount", request.suggestedFixedAmount) ?: pool.suggestedFixedAmount
            val nextMaxDailyOrders = request.suggestedMaxDailyOrders ?: pool.suggestedMaxDailyOrders
            val nextMaxDailyLoss = parseDecimal("suggestedMaxDailyLoss", request.suggestedMaxDailyLoss) ?: pool.suggestedMaxDailyLoss
            val nextMinPrice = parseNullableDecimal("suggestedMinPrice", request.suggestedMinPrice, pool.suggestedMinPrice)
            val nextMaxPrice = parseNullableDecimal("suggestedMaxPrice", request.suggestedMaxPrice, pool.suggestedMaxPrice)
            val nextMaxPositionValue = parseNullableDecimal(
                "suggestedMaxPositionValue",
                request.suggestedMaxPositionValue,
                pool.suggestedMaxPositionValue
            )

            validateSuggestedPlan(
                fixedAmount = nextFixedAmount,
                maxDailyOrders = nextMaxDailyOrders,
                maxDailyLoss = nextMaxDailyLoss,
                minPrice = nextMinPrice,
                maxPrice = nextMaxPrice,
                maxPositionValue = nextMaxPositionValue
            )

            val now = System.currentTimeMillis()
            val updated = pool.copy(
                suggestedFixedAmount = nextFixedAmount,
                suggestedMaxDailyOrders = nextMaxDailyOrders,
                suggestedMaxDailyLoss = nextMaxDailyLoss,
                suggestedMinPrice = nextMinPrice,
                suggestedMaxPrice = nextMaxPrice,
                suggestedMaxPositionValue = nextMaxPositionValue,
                reason = request.reason?.trim().takeUnless { it.isNullOrBlank() } ?: pool.reason,
                notes = request.notes?.trim().takeUnless { it.isNullOrBlank() } ?: pool.notes,
                lastReviewedAt = now,
                updatedAt = now
            )

            val saved = leaderPoolRepository.save(updated)
            logger.info("Leader 池建议配置更新: poolId={}, leaderId={}", saved.id, saved.leaderId)
            Result.success(toDto(saved, leader, copyTradingRepository.findByLeaderId(saved.leaderId)))
        } catch (e: Exception) {
            logger.error("更新 Leader 池建议配置失败: poolId=${request.poolId}", e)
            Result.failure(e)
        }
    }

    @Transactional
    open fun createTrialConfig(request: LeaderPoolCreateTrialConfigRequest): Result<CopyTradingDto> {
        val pool = try {
            findPool(request.poolId)
        } catch (e: Exception) {
            logger.error("创建 Leader 池试跟配置失败: poolId=${request.poolId}", e)
            return Result.failure(e)
        }

        return try {
            if (pool.researchCandidateId != null && pool.researchState != LeaderResearchState.TRIAL_READY) {
                logger.warn(
                    "拒绝从未试跟建议的研究候选创建 Leader 池试跟配置: poolId={}, leaderId={}, researchState={}",
                    pool.id,
                    pool.leaderId,
                    pool.researchState
                )
                return Result.failure(LeaderPoolResearchCandidateNotReadyException())
            }
            if (request.enableImmediately && !request.confirm) {
                logger.warn("拒绝未确认的立即启用试跟配置: poolId={}, leaderId={}", pool.id, pool.leaderId)
                return Result.failure(LeaderPoolConfirmRequiredException())
            }
            val account = accountRepository.findById(request.accountId).orElse(null)
            if (account == null) {
                logger.warn(
                    "拒绝创建 Leader 池试跟配置，账户不存在: poolId={}, leaderId={}, accountId={}",
                    pool.id,
                    pool.leaderId,
                    request.accountId
                )
                return Result.failure(IllegalArgumentException("账户不存在"))
            }
            val leader = findLeader(pool.leaderId)

            validateSuggestedPlan(
                fixedAmount = pool.suggestedFixedAmount,
                maxDailyOrders = pool.suggestedMaxDailyOrders,
                maxDailyLoss = pool.suggestedMaxDailyLoss,
                minPrice = pool.suggestedMinPrice,
                maxPrice = pool.suggestedMaxPrice,
                maxPositionValue = pool.suggestedMaxPositionValue
            )

            if (copyTradingRepository.findByAccountIdAndLeaderId(request.accountId, pool.leaderId).isNotEmpty()) {
                logger.warn(
                    "拒绝重复创建 Leader 池试跟配置: poolId={}, leaderId={}, accountId={}",
                    pool.id,
                    pool.leaderId,
                    request.accountId
                )
                return Result.failure(LeaderPoolDuplicateTrialConfigException())
            }

            // 研究候选只能建出禁用的配置：真钱启用必须由用户在跟单配置里手动完成
            val enableRequested = request.enableImmediately && request.confirm
            val enableNow = enableRequested && pool.researchCandidateId == null
            if (enableRequested && !enableNow) {
                logger.warn(
                    "研究候选禁止立即启用真钱试跟配置，已改为禁用: poolId={}, leaderId={}, researchCandidateId={}",
                    pool.id,
                    pool.leaderId,
                    pool.researchCandidateId
                )
                researchEventService?.record(
                    type = LeaderResearchEventType.REAL_MONEY_ACTIVATION_FORBIDDEN,
                    candidateId = pool.researchCandidateId,
                    reason = "Leader pool trial config for research candidate was forced to disabled",
                    dedupeKey = "pool-enable-forbidden:${pool.id}:${request.accountId}:${System.currentTimeMillis()}"
                )
            }

            val copyTradingRequest = CopyTradingCreateRequest(
                accountId = request.accountId,
                leaderId = pool.leaderId,
                enabled = enableNow,
                copyMode = "FIXED",
                copyRatio = "1",
                fixedAmount = pool.suggestedFixedAmount.strip(),
                maxOrderSize = pool.suggestedFixedAmount.strip(),
                minOrderSize = "1",
                maxDailyLoss = pool.suggestedMaxDailyLoss.strip(),
                maxDailyOrders = pool.suggestedMaxDailyOrders,
                priceTolerance = "1",
                delaySeconds = 0,
                pollIntervalSeconds = 5,
                useWebSocket = true,
                websocketReconnectInterval = 5000,
                websocketMaxRetries = 10,
                supportSell = true,
                minPrice = pool.suggestedMinPrice?.strip(),
                maxPrice = pool.suggestedMaxPrice?.strip(),
                maxPositionValue = pool.suggestedMaxPositionValue?.strip(),
                keywordFilterMode = "DISABLED",
                keywords = null,
                configName = buildTrialConfigName(leader),
                pushFailedOrders = true,
                pushFilteredOrders = true
            )

            val result = copyTradingService.createCopyTrading(copyTradingRequest)
            result.onSuccess {
                val now = System.currentTimeMillis()
                leaderPoolRepository.save(
                    pool.copy(
                        status = LeaderPoolStatus.TRIAL,
                        lastPromotedAt = now,
                        lastReviewedAt = now,
                        updatedAt = now
                    )
                )
                logger.info(
                    "Leader 池试跟配置创建成功: poolId={}, leaderId={}, accountId={}, copyTradingId={}",
                    pool.id,
                    pool.leaderId,
                    request.accountId,
                    it.id
                )
            }.onFailure {
                logger.error(
                    "Leader 池试跟配置创建失败: poolId=${pool.id}, leaderId=${pool.leaderId}, accountId=${request.accountId}, error=${it.message}",
                    it
                )
            }
        } catch (e: Exception) {
            logger.error(
                "创建 Leader 池试跟配置异常: poolId=${pool.id}, leaderId=${pool.leaderId}, accountId=${request.accountId}",
                e
            )
            Result.failure(e)
        }
    }

    @Transactional
    open fun remove(request: LeaderPoolRemoveRequest): Result<Unit> {
        return try {
            val pool = findPool(request.poolId)
            leaderPoolRepository.delete(pool)
            logger.info("Leader 池项移除: poolId={}, leaderId={}", pool.id, pool.leaderId)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("移除 Leader 池项失败: poolId=${request.poolId}", e)
            Result.failure(e)
        }
    }

    private fun findPool(poolId: Long): LeaderPool {
        return leaderPoolRepository.findById(poolId).orElse(null) ?: throw LeaderPoolNotFoundException()
    }

    private fun findLeader(leaderId: Long): Leader {
        return leaderRepository.findById(leaderId).orElse(null) ?: throw IllegalArgumentException("Leader 不存在")
    }

    private fun parseStatus(status: String): LeaderPoolStatus {
        return try {
            LeaderPoolStatus.valueOf(status.trim().uppercase())
        } catch (e: Exception) {
            throw IllegalArgumentException("Leader 池状态无效")
        }
    }

    private fun parseDecimal(fieldName: String, value: String?): BigDecimal? {
        if (value == null) return null
        return value.trim().takeUnless { it.isBlank() }?.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("$fieldName 必须是有效数字")
    }

    private fun parseNullableDecimal(fieldName: String, value: String?, current: BigDecimal?): BigDecimal? {
        if (value == null) return current
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        return trimmed.toBigDecimalOrNull() ?: throw IllegalArgumentException("$fieldName 必须是有效数字")
    }

    private fun validateSuggestedPlan(
        fixedAmount: BigDecimal,
        maxDailyOrders: Int,
        maxDailyLoss: BigDecimal,
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
        maxPositionValue: BigDecimal?
    ) {
        if (fixedAmount <= BigDecimal.ZERO) {
            throw IllegalArgumentException("suggestedFixedAmount 必须大于 0")
        }
        if (maxDailyOrders !in 1..100) {
            throw IllegalArgumentException("suggestedMaxDailyOrders 必须在 1 到 100 之间")
        }
        if (maxDailyLoss <= BigDecimal.ZERO) {
            throw IllegalArgumentException("suggestedMaxDailyLoss 必须大于 0")
        }
        minPrice?.let {
            if (it < BigDecimal.ZERO || it > BigDecimal.ONE) {
                throw IllegalArgumentException("suggestedMinPrice 必须在 0 到 1 之间")
            }
        }
        maxPrice?.let {
            if (it < BigDecimal.ZERO || it > BigDecimal.ONE) {
                throw IllegalArgumentException("suggestedMaxPrice 必须在 0 到 1 之间")
            }
        }
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw IllegalArgumentException("suggestedMinPrice 不能大于 suggestedMaxPrice")
        }
        maxPositionValue?.let {
            if (it <= BigDecimal.ZERO) {
                throw IllegalArgumentException("suggestedMaxPositionValue 必须大于 0")
            }
        }
    }

    private fun toDto(pool: LeaderPool, leader: Leader, copyTradings: List<CopyTrading>): LeaderPoolItemDto {
        val isTrialOrActive = pool.status == LeaderPoolStatus.TRIAL || pool.status == LeaderPoolStatus.ACTIVE
        val estimatedWorstExposure = if (isTrialOrActive) {
            pool.suggestedMaxPositionValue ?: DEFAULT_MAX_POSITION_VALUE
        } else {
            BigDecimal.ZERO
        }
        val leaderAddress = leader.leaderAddress
        return LeaderPoolItemDto(
            id = pool.id!!,
            leaderId = pool.leaderId,
            leaderName = leader.leaderName,
            leaderAddress = leaderAddress,
            category = leader.category,
            profileUrl = "https://polymarket.com/profile/$leaderAddress",
            status = pool.status.name,
            source = pool.source,
            sourceRank = pool.sourceRank,
            score = pool.score?.strip(),
            reason = pool.reason,
            notes = pool.notes,
            suggestedFixedAmount = pool.suggestedFixedAmount.strip(),
            suggestedMaxDailyOrders = pool.suggestedMaxDailyOrders,
            suggestedMaxDailyLoss = pool.suggestedMaxDailyLoss.strip(),
            suggestedMinPrice = pool.suggestedMinPrice?.strip(),
            suggestedMaxPrice = pool.suggestedMaxPrice?.strip(),
            suggestedMaxPositionValue = pool.suggestedMaxPositionValue?.strip(),
            copyTradingCount = copyTradings.size,
            hasEnabledCopyTrading = copyTradings.any { it.enabled },
            estimatedWorstExposure = estimatedWorstExposure.strip(),
            lastReviewedAt = pool.lastReviewedAt,
            lastPromotedAt = pool.lastPromotedAt,
            cooldownUntil = pool.cooldownUntil,
            locked = pool.locked,
            researchCandidateId = pool.researchCandidateId,
            researchState = pool.researchState?.name,
            researchBadge = pool.researchBadge,
            researchSummary = pool.researchSummary,
            researchScore = pool.researchScore?.strip(),
            researchUpdatedAt = pool.researchUpdatedAt,
            createdAt = pool.createdAt,
            updatedAt = pool.updatedAt
        )
    }

    private fun buildTrialConfigName(leader: Leader): String {
        val baseName = leader.leaderName?.trim().takeUnless { it.isNullOrBlank() }
            ?: leader.leaderAddress.takeLast(6)
        return "Leader池-$baseName"
    }

    private fun BigDecimal.strip(): String = stripTrailingZeros().toPlainString()

    companion object {
        private val DEFAULT_MAX_POSITION_VALUE = BigDecimal("5")
    }
}
