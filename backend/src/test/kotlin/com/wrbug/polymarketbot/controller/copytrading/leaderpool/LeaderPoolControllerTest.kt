package com.wrbug.polymarketbot.controller.copytrading.leaderpool

import com.wrbug.polymarketbot.dto.CopyTradingDto
import com.wrbug.polymarketbot.dto.LeaderPoolAddRequest
import com.wrbug.polymarketbot.dto.LeaderPoolCreateTrialConfigRequest
import com.wrbug.polymarketbot.dto.LeaderPoolItemDto
import com.wrbug.polymarketbot.dto.LeaderPoolListRequest
import com.wrbug.polymarketbot.dto.LeaderPoolListResponse
import com.wrbug.polymarketbot.dto.LeaderPoolOptimizationItemDto
import com.wrbug.polymarketbot.dto.LeaderPoolOptimizationRequest
import com.wrbug.polymarketbot.dto.LeaderPoolOptimizationResponse
import com.wrbug.polymarketbot.dto.LeaderPoolRemoveRequest
import com.wrbug.polymarketbot.dto.LeaderPoolSummaryDto
import com.wrbug.polymarketbot.dto.LeaderPoolUpdatePlanRequest
import com.wrbug.polymarketbot.dto.LeaderPoolUpdateStatusRequest
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.copytrading.leaderpool.LeaderPoolAlreadyExistsException
import com.wrbug.polymarketbot.service.copytrading.leaderpool.LeaderPoolConfirmRequiredException
import com.wrbug.polymarketbot.service.copytrading.leaderpool.LeaderPoolDuplicateTrialConfigException
import com.wrbug.polymarketbot.service.copytrading.leaderpool.LeaderPoolNotFoundException
import com.wrbug.polymarketbot.service.copytrading.leaderpool.LeaderPoolOptimizationService
import com.wrbug.polymarketbot.service.copytrading.leaderpool.LeaderPoolService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.support.StaticMessageSource

class LeaderPoolControllerTest {

    @Test
    fun `list returns pool response`() {
        val service = StubLeaderPoolService(listResult = Result.success(sampleListResponse()))
        val controller = controller(service)

        val response = controller.list(LeaderPoolListRequest())

        assertEquals(0, response.body!!.code)
        assertEquals(1, response.body!!.data!!.total)
    }

    @Test
    fun `add maps duplicate to leader pool already exists code`() {
        val service = StubLeaderPoolService(addResult = Result.failure(LeaderPoolAlreadyExistsException()))
        val controller = controller(service)

        val response = controller.add(LeaderPoolAddRequest(leaderId = 1))

        assertEquals(ErrorCode.LEADER_POOL_ALREADY_EXISTS.code, response.body!!.code)
    }

    @Test
    fun `update status maps missing pool to not found code`() {
        val service = StubLeaderPoolService(itemResult = Result.failure(LeaderPoolNotFoundException()))
        val controller = controller(service)

        val response = controller.updateStatus(LeaderPoolUpdateStatusRequest(poolId = 1, status = "WATCH"))

        assertEquals(ErrorCode.LEADER_POOL_NOT_FOUND.code, response.body!!.code)
    }

    @Test
    fun `update plan maps validation failure to param error`() {
        val service = StubLeaderPoolService(itemResult = Result.failure(IllegalArgumentException("suggestedFixedAmount 必须大于 0")))
        val controller = controller(service)

        val response = controller.updatePlan(LeaderPoolUpdatePlanRequest(poolId = 1, suggestedFixedAmount = "-1"))

        assertEquals(ErrorCode.PARAM_ERROR.code, response.body!!.code)
    }

    @Test
    fun `create trial maps duplicate config and confirm errors`() {
        val duplicateController = controller(
            StubLeaderPoolService(trialResult = Result.failure(LeaderPoolDuplicateTrialConfigException()))
        )
        val duplicate = duplicateController.createTrialConfig(LeaderPoolCreateTrialConfigRequest(poolId = 1, accountId = 2))
        assertEquals(ErrorCode.LEADER_POOL_DUPLICATE_TRIAL_CONFIG.code, duplicate.body!!.code)

        val confirmController = controller(
            StubLeaderPoolService(trialResult = Result.failure(LeaderPoolConfirmRequiredException()))
        )
        val confirm = confirmController.createTrialConfig(
            LeaderPoolCreateTrialConfigRequest(poolId = 1, accountId = 2, enableImmediately = true, confirm = false)
        )
        assertEquals(ErrorCode.LEADER_POOL_CONFIRM_REQUIRED.code, confirm.body!!.code)
    }

    @Test
    fun `optimization returns read-only ranking response`() {
        val service = StubLeaderPoolService()
        val optimizationService = Mockito.mock(LeaderPoolOptimizationService::class.java)
        Mockito.`when`(optimizationService.getOptimization(10)).thenReturn(sampleOptimizationResponse())
        val controller = controller(service, optimizationService)

        val response = controller.optimization(LeaderPoolOptimizationRequest())

        assertEquals(0, response.body!!.code)
        assertTrue(response.body!!.data!!.safeMode)
        assertEquals(1, response.body!!.data!!.top3.size)
    }

    @Test
    fun `remove returns success response`() {
        val service = StubLeaderPoolService(removeResult = Result.success(Unit))
        val controller = controller(service)

        val response = controller.remove(LeaderPoolRemoveRequest(poolId = 1))

        assertEquals(0, response.body!!.code)
    }

    private fun controller(
        service: LeaderPoolService,
        optimizationService: LeaderPoolOptimizationService = Mockito.mock(LeaderPoolOptimizationService::class.java)
    ) = LeaderPoolController(
        leaderPoolService = service,
        optimizationService = optimizationService,
        messageSource = StaticMessageSource()
    )

    private class StubLeaderPoolService(
        private val listResult: Result<LeaderPoolListResponse> = Result.success(sampleListResponse()),
        private val addResult: Result<LeaderPoolItemDto> = Result.success(sampleItem()),
        private val itemResult: Result<LeaderPoolItemDto> = Result.success(sampleItem()),
        private val trialResult: Result<CopyTradingDto> = Result.success(sampleCopyTradingDto()),
        private val removeResult: Result<Unit> = Result.success(Unit)
    ) : LeaderPoolService(
        leaderPoolRepository = mock(),
        leaderRepository = mock(),
        copyTradingRepository = mock(),
        accountRepository = mock(),
        copyTradingService = mock()
    ) {
        override fun getPoolList(request: LeaderPoolListRequest): Result<LeaderPoolListResponse> = listResult

        override fun addToPool(request: LeaderPoolAddRequest): Result<LeaderPoolItemDto> = addResult

        override fun updateStatus(request: LeaderPoolUpdateStatusRequest): Result<LeaderPoolItemDto> = itemResult

        override fun updatePlan(request: LeaderPoolUpdatePlanRequest): Result<LeaderPoolItemDto> = itemResult

        override fun createTrialConfig(request: LeaderPoolCreateTrialConfigRequest): Result<CopyTradingDto> = trialResult

        override fun remove(request: LeaderPoolRemoveRequest): Result<Unit> = removeResult
    }

    companion object {
        private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)

        private fun sampleOptimizationResponse() = LeaderPoolOptimizationResponse(
            generatedAt = 1,
            staleReason = null,
            candidateCount = 1,
            eligibleCount = 1,
            items = listOf(sampleOptimizationItem()),
            top3 = listOf(sampleOptimizationItem()),
            safeMode = true
        )

        private fun sampleOptimizationItem() = LeaderPoolOptimizationItemDto(
            candidateId = 1,
            leaderId = 2,
            poolId = null,
            rank = 1,
            recommendationTier = "AUTO_READY",
            optimizationScore = "80",
            researchScore = "90",
            paperTradeCount = 40,
            paperCopyablePnl = "5",
            paperFilteredRatio = "0.1",
            paperMaxDrawdown = "-5",
            paperUnknownRatio = "0.05",
            sourceFresh = "1",
            leaderName = "Leader",
            leaderAddress = "0xleader",
            researchState = "TRIAL_READY",
            canRecommend = true,
            reasonCode = "AUTO_READY"
        )

        private fun sampleListResponse() = LeaderPoolListResponse(
            summary = LeaderPoolSummaryDto(
                totalCount = 1,
                trialCount = 0,
                estimatedWorstExposure = "0",
                pendingRiskCount = 0
            ),
            list = listOf(sampleItem()),
            total = 1
        )

        private fun sampleItem() = LeaderPoolItemDto(
            id = 1,
            leaderId = 2,
            leaderName = "Leader",
            leaderAddress = "0xleader",
            category = null,
            profileUrl = "https://polymarket.com/profile/0xleader",
            status = "CANDIDATE",
            source = "MANUAL",
            sourceRank = null,
            score = null,
            reason = null,
            notes = null,
            suggestedFixedAmount = "1",
            suggestedMaxDailyOrders = 10,
            suggestedMaxDailyLoss = "5",
            suggestedMinPrice = "0.1",
            suggestedMaxPrice = "0.8",
            suggestedMaxPositionValue = "5",
            copyTradingCount = 0,
            hasEnabledCopyTrading = false,
            estimatedWorstExposure = "0",
            lastReviewedAt = null,
            lastPromotedAt = null,
            cooldownUntil = null,
            locked = false,
            researchCandidateId = null,
            researchState = null,
            researchBadge = null,
            researchSummary = null,
            researchScore = null,
            researchUpdatedAt = null,
            createdAt = 1,
            updatedAt = 1
        )

        private fun sampleCopyTradingDto() = CopyTradingDto(
            id = 3,
            accountId = 2,
            accountName = "Account",
            walletAddress = "0xaccount",
            leaderId = 1,
            leaderName = "Leader",
            leaderAddress = "0xleader",
            enabled = false,
            copyMode = "FIXED",
            copyRatio = "1",
            fixedAmount = "1",
            maxOrderSize = "1",
            minOrderSize = "1",
            maxDailyLoss = "5",
            maxDailyOrders = 10,
            priceTolerance = "1",
            delaySeconds = 0,
            pollIntervalSeconds = 5,
            useWebSocket = true,
            websocketReconnectInterval = 5000,
            websocketMaxRetries = 10,
            supportSell = true,
            minOrderDepth = null,
            maxSpread = null,
            minPrice = "0.1",
            maxPrice = "0.8",
            maxPositionValue = "5",
            createdAt = 1,
            updatedAt = 1
        )
    }
}
