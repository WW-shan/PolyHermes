package com.wrbug.polymarketbot.service.copytrading.leaderpool

import com.wrbug.polymarketbot.dto.CopyTradingDto
import com.wrbug.polymarketbot.dto.CopyTradingCreateRequest
import com.wrbug.polymarketbot.dto.LeaderPoolAddRequest
import com.wrbug.polymarketbot.dto.LeaderPoolCreateTrialConfigRequest
import com.wrbug.polymarketbot.dto.LeaderPoolListRequest
import com.wrbug.polymarketbot.dto.LeaderPoolUpdatePlanRequest
import com.wrbug.polymarketbot.dto.LeaderPoolUpdateStatusRequest
import com.wrbug.polymarketbot.entity.Account
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.util.Optional

class LeaderPoolServiceTest {

    private val leaderPoolRepository: LeaderPoolRepository = mock()
    private val leaderRepository: LeaderRepository = mock()
    private val copyTradingRepository: CopyTradingRepository = mock()
    private val accountRepository: AccountRepository = mock()
    private val copyTradingService: CopyTradingService = mock()
    private val service = LeaderPoolService(
        leaderPoolRepository = leaderPoolRepository,
        leaderRepository = leaderRepository,
        copyTradingRepository = copyTradingRepository,
        accountRepository = accountRepository,
        copyTradingService = copyTradingService
    )

    @Test
    fun `adds existing leader to pool as candidate`() {
        Mockito.`when`(leaderRepository.findById(1L)).thenReturn(Optional.of(leader()))
        Mockito.`when`(leaderPoolRepository.findByLeaderId(1L)).thenReturn(null)
        Mockito.`when`(leaderPoolRepository.saveAndFlush(anyLeaderPool())).thenAnswer {
            (it.arguments[0] as LeaderPool).copy(id = 10)
        }

        val result = service.addToPool(LeaderPoolAddRequest(leaderId = 1))

        assertTrue(result.isSuccess)
        assertEquals("CANDIDATE", result.getOrThrow().status)
        Mockito.verify(leaderPoolRepository).saveAndFlush(anyLeaderPool())
    }

    @Test
    fun `duplicate add does not create another pool item`() {
        Mockito.`when`(leaderRepository.findById(1L)).thenReturn(Optional.of(leader()))
        Mockito.`when`(leaderPoolRepository.findByLeaderId(1L)).thenReturn(pool())

        val result = service.addToPool(LeaderPoolAddRequest(leaderId = 1))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LeaderPoolAlreadyExistsException)
        Mockito.verify(leaderPoolRepository, Mockito.never()).saveAndFlush(anyLeaderPool())
    }

    @Test
    fun `missing leader returns error`() {
        Mockito.`when`(leaderRepository.findById(404L)).thenReturn(Optional.empty())

        val result = service.addToPool(LeaderPoolAddRequest(leaderId = 404))

        assertTrue(result.isFailure)
        assertEquals("Leader 不存在", result.exceptionOrNull()?.message)
    }

    @Test
    fun `unique constraint conflict is mapped to already exists`() {
        Mockito.`when`(leaderRepository.findById(1L)).thenReturn(Optional.of(leader()))
        Mockito.`when`(leaderPoolRepository.findByLeaderId(1L)).thenReturn(null)
        Mockito.`when`(leaderPoolRepository.saveAndFlush(anyLeaderPool()))
            .thenThrow(DataIntegrityViolationException("duplicate"))

        val result = service.addToPool(LeaderPoolAddRequest(leaderId = 1))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LeaderPoolAlreadyExistsException)
    }

    @Test
    fun `updates status and saves cooldown without deleting leader`() {
        Mockito.`when`(leaderPoolRepository.findById(10L)).thenReturn(Optional.of(pool()))
        Mockito.`when`(leaderRepository.findById(1L)).thenReturn(Optional.of(leader()))
        Mockito.`when`(leaderPoolRepository.save(anyLeaderPool())).thenAnswer { it.arguments[0] }
        Mockito.`when`(copyTradingRepository.findByLeaderId(1L)).thenReturn(emptyList())

        val result = service.updateStatus(
            LeaderPoolUpdateStatusRequest(
                poolId = 10,
                status = "COOLDOWN",
                cooldownUntil = 123456L
            )
        )

        assertTrue(result.isSuccess)
        assertEquals("COOLDOWN", result.getOrThrow().status)
        assertEquals(123456L, result.getOrThrow().cooldownUntil)
        Mockito.verify(leaderRepository, Mockito.never()).delete(anyLeader())
    }

    @Test
    fun `update plan does not modify existing copy trading`() {
        Mockito.`when`(leaderPoolRepository.findById(10L)).thenReturn(Optional.of(pool()))
        Mockito.`when`(leaderRepository.findById(1L)).thenReturn(Optional.of(leader()))
        Mockito.`when`(leaderPoolRepository.save(anyLeaderPool())).thenAnswer { it.arguments[0] }
        Mockito.`when`(copyTradingRepository.findByLeaderId(1L)).thenReturn(listOf(copyTrading()))

        val result = service.updatePlan(
            LeaderPoolUpdatePlanRequest(
                poolId = 10,
                suggestedFixedAmount = "2",
                suggestedMaxDailyOrders = 8,
                suggestedMaxDailyLoss = "4",
                suggestedMinPrice = "0.2",
                suggestedMaxPrice = "0.7",
                suggestedMaxPositionValue = "6"
            )
        )

        assertTrue(result.isSuccess)
        assertEquals("2", result.getOrThrow().suggestedFixedAmount)
        Mockito.verify(copyTradingRepository, Mockito.never()).save(anyCopyTrading())
    }

    @Test
    fun `invalid suggested plan is rejected without saving`() {
        Mockito.`when`(leaderPoolRepository.findById(10L)).thenReturn(Optional.of(pool()))
        Mockito.`when`(leaderRepository.findById(1L)).thenReturn(Optional.of(leader()))

        val result = service.updatePlan(
            LeaderPoolUpdatePlanRequest(
                poolId = 10,
                suggestedFixedAmount = "-1"
            )
        )

        assertTrue(result.isFailure)
        assertEquals("suggestedFixedAmount 必须大于 0", result.exceptionOrNull()?.message)
        Mockito.verify(leaderPoolRepository, Mockito.never()).save(anyLeaderPool())
    }

    @Test
    fun `creates disabled conservative trial config and promotes pool after success`() {
        Mockito.`when`(leaderPoolRepository.findById(10L)).thenReturn(Optional.of(pool()))
        Mockito.`when`(accountRepository.findById(2L)).thenReturn(Optional.of(account()))
        Mockito.`when`(leaderRepository.findById(1L)).thenReturn(Optional.of(leader()))
        Mockito.`when`(copyTradingRepository.findByAccountIdAndLeaderId(2L, 1L)).thenReturn(emptyList())
        Mockito.`when`(copyTradingService.createCopyTrading(anyCreateRequest())).thenReturn(Result.success(copyTradingDto()))
        Mockito.`when`(leaderPoolRepository.save(anyLeaderPool())).thenAnswer { it.arguments[0] }

        val result = service.createTrialConfig(LeaderPoolCreateTrialConfigRequest(poolId = 10, accountId = 2))

        assertTrue(result.isSuccess)
        val requestCaptor = ArgumentCaptor.forClass(com.wrbug.polymarketbot.dto.CopyTradingCreateRequest::class.java)
        Mockito.verify(copyTradingService).createCopyTrading(captureCreateRequest(requestCaptor))
        assertEquals(false, requestCaptor.value.enabled)
        assertEquals("FIXED", requestCaptor.value.copyMode)
        assertEquals("1", requestCaptor.value.fixedAmount)
        assertEquals(10, requestCaptor.value.maxDailyOrders)
        assertEquals("5", requestCaptor.value.maxDailyLoss)
        assertEquals("0.1", requestCaptor.value.minPrice)
        assertEquals("0.8", requestCaptor.value.maxPrice)
        assertEquals("5", requestCaptor.value.maxPositionValue)
        val poolCaptor = ArgumentCaptor.forClass(LeaderPool::class.java)
        Mockito.verify(leaderPoolRepository).save(captureLeaderPool(poolCaptor))
        assertEquals(LeaderPoolStatus.TRIAL, poolCaptor.value.status)
    }

    @Test
    fun `research candidate trial config is forced disabled even with confirmed immediate enable`() {
        Mockito.`when`(leaderPoolRepository.findById(10L)).thenReturn(
            Optional.of(pool(researchCandidateId = 99, researchState = LeaderResearchState.TRIAL_READY))
        )
        Mockito.`when`(accountRepository.findById(2L)).thenReturn(Optional.of(account()))
        Mockito.`when`(leaderRepository.findById(1L)).thenReturn(Optional.of(leader()))
        Mockito.`when`(copyTradingRepository.findByAccountIdAndLeaderId(2L, 1L)).thenReturn(emptyList())
        Mockito.`when`(copyTradingService.createCopyTrading(anyCreateRequest())).thenReturn(Result.success(copyTradingDto()))
        Mockito.`when`(leaderPoolRepository.save(anyLeaderPool())).thenAnswer { it.arguments[0] }

        val result = service.createTrialConfig(
            LeaderPoolCreateTrialConfigRequest(poolId = 10, accountId = 2, enableImmediately = true, confirm = true)
        )

        assertTrue(result.isSuccess)
        val requestCaptor = ArgumentCaptor.forClass(com.wrbug.polymarketbot.dto.CopyTradingCreateRequest::class.java)
        Mockito.verify(copyTradingService).createCopyTrading(captureCreateRequest(requestCaptor))
        assertEquals(false, requestCaptor.value.enabled)
    }

    @Test
    fun `research pool item must be trial ready before creating trial config`() {
        Mockito.`when`(leaderPoolRepository.findById(10L)).thenReturn(
            Optional.of(pool(researchCandidateId = 99, researchState = LeaderResearchState.PAPER))
        )

        val result = service.createTrialConfig(LeaderPoolCreateTrialConfigRequest(poolId = 10, accountId = 2))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LeaderPoolResearchCandidateNotReadyException)
        Mockito.verify(accountRepository, Mockito.never()).findById(2L)
        Mockito.verify(copyTradingService, Mockito.never()).createCopyTrading(anyCreateRequest())
    }

    @Test
    fun `create trial failure leaves pool status unchanged`() {
        Mockito.`when`(leaderPoolRepository.findById(10L)).thenReturn(Optional.of(pool()))
        Mockito.`when`(accountRepository.findById(2L)).thenReturn(Optional.of(account()))
        Mockito.`when`(leaderRepository.findById(1L)).thenReturn(Optional.of(leader()))
        Mockito.`when`(copyTradingRepository.findByAccountIdAndLeaderId(2L, 1L)).thenReturn(emptyList())
        Mockito.`when`(copyTradingService.createCopyTrading(anyCreateRequest()))
            .thenReturn(Result.failure(RuntimeException("create failed")))

        val result = service.createTrialConfig(LeaderPoolCreateTrialConfigRequest(poolId = 10, accountId = 2))

        assertTrue(result.isFailure)
        Mockito.verify(leaderPoolRepository, Mockito.never()).save(anyLeaderPool())
    }

    @Test
    fun `existing account leader config rejects duplicate trial creation`() {
        Mockito.`when`(leaderPoolRepository.findById(10L)).thenReturn(Optional.of(pool()))
        Mockito.`when`(accountRepository.findById(2L)).thenReturn(Optional.of(account()))
        Mockito.`when`(leaderRepository.findById(1L)).thenReturn(Optional.of(leader()))
        Mockito.`when`(copyTradingRepository.findByAccountIdAndLeaderId(2L, 1L)).thenReturn(listOf(copyTrading()))

        val result = service.createTrialConfig(LeaderPoolCreateTrialConfigRequest(poolId = 10, accountId = 2))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LeaderPoolDuplicateTrialConfigException)
        Mockito.verify(copyTradingService, Mockito.never()).createCopyTrading(anyCreateRequest())
    }

    @Test
    fun `immediate enable without confirmation rejects creation`() {
        Mockito.`when`(leaderPoolRepository.findById(10L)).thenReturn(Optional.of(pool()))

        val result = service.createTrialConfig(
            LeaderPoolCreateTrialConfigRequest(
                poolId = 10,
                accountId = 2,
                enableImmediately = true,
                confirm = false
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LeaderPoolConfirmRequiredException)
        Mockito.verify(copyTradingService, Mockito.never()).createCopyTrading(anyCreateRequest())
    }

    @Test
    fun `pool list uses bulk leader and copy trading queries`() {
        val pools = listOf(pool(id = 10, leaderId = 1), pool(id = 11, leaderId = 2, status = LeaderPoolStatus.TRIAL))
        Mockito.`when`(leaderPoolRepository.findAllByOrderByCreatedAtDesc()).thenReturn(pools)
        Mockito.`when`(leaderRepository.findAllById(listOf(1L, 2L))).thenReturn(listOf(leader(1), leader(2)))
        Mockito.`when`(copyTradingRepository.findByLeaderIdIn(listOf(1L, 2L))).thenReturn(listOf(copyTrading(leaderId = 2)))

        val result = service.getPoolList(LeaderPoolListRequest())

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().total)
        assertEquals("5", result.getOrThrow().summary.estimatedWorstExposure)
        Mockito.verify(leaderRepository).findAllById(listOf(1L, 2L))
        Mockito.verify(copyTradingRepository).findByLeaderIdIn(listOf(1L, 2L))
        Mockito.verify(copyTradingRepository, Mockito.never()).findByLeaderId(1L)
        Mockito.verify(copyTradingRepository, Mockito.never()).findByLeaderId(2L)
    }

    private fun leader(id: Long = 1) = Leader(
        id = id,
        leaderAddress = "0x${id.toString().padStart(40, '0')}",
        leaderName = "Leader $id"
    )

    private fun account() = Account(
        id = 2,
        privateKey = "encrypted",
        walletAddress = "0xaccount",
        proxyAddress = "0xproxy"
    )

    private fun pool(
        id: Long = 10,
        leaderId: Long = 1,
        status: LeaderPoolStatus = LeaderPoolStatus.CANDIDATE,
        researchCandidateId: Long? = null,
        researchState: LeaderResearchState? = null
    ) = LeaderPool(
        id = id,
        leaderId = leaderId,
        status = status,
        researchCandidateId = researchCandidateId,
        researchState = researchState,
        suggestedFixedAmount = BigDecimal("1"),
        suggestedMaxDailyOrders = 10,
        suggestedMaxDailyLoss = BigDecimal("5"),
        suggestedMinPrice = BigDecimal("0.1"),
        suggestedMaxPrice = BigDecimal("0.8"),
        suggestedMaxPositionValue = BigDecimal("5")
    )

    private fun copyTrading(leaderId: Long = 1) = CopyTrading(
        id = 3,
        accountId = 2,
        leaderId = leaderId,
        enabled = true,
        copyMode = "FIXED",
        fixedAmount = BigDecimal.ONE
    )

    private fun copyTradingDto() = CopyTradingDto(
        id = 3,
        accountId = 2,
        accountName = "Account",
        walletAddress = "0xaccount",
        leaderId = 1,
        leaderName = "Leader 1",
        leaderAddress = "0x0000000000000000000000000000000000000001",
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

    private fun anyLeader(): Leader {
        Mockito.any(Leader::class.java)
        return leader()
    }

    private fun anyLeaderPool(): LeaderPool {
        Mockito.any(LeaderPool::class.java)
        return pool()
    }

    private fun anyCopyTrading(): CopyTrading {
        Mockito.any(CopyTrading::class.java)
        return copyTrading()
    }

    private fun anyCreateRequest(): CopyTradingCreateRequest {
        Mockito.any(CopyTradingCreateRequest::class.java)
        return CopyTradingCreateRequest(accountId = 2, leaderId = 1)
    }

    private fun captureCreateRequest(captor: ArgumentCaptor<CopyTradingCreateRequest>): CopyTradingCreateRequest {
        captor.capture()
        return CopyTradingCreateRequest(accountId = 2, leaderId = 1)
    }

    private fun captureLeaderPool(captor: ArgumentCaptor<LeaderPool>): LeaderPool {
        captor.capture()
        return pool()
    }

    companion object {
        private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
    }
}
