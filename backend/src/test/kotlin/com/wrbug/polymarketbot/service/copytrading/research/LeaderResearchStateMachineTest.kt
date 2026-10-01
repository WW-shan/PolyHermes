package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.LeaderPaperSessionRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class LeaderResearchStateMachineTest {
    private val candidateRepository: LeaderResearchCandidateRepository = mock()
    private val paperSessionRepository: LeaderPaperSessionRepository = mock()
    private val paperTradingService: LeaderPaperTradingService = mock()
    private val poolMappingService: LeaderResearchPoolMappingService = mock()
    private val eventService: LeaderResearchEventService = mock()
    private val stateMachine = LeaderResearchStateMachine(
        candidateRepository,
        paperSessionRepository,
        paperTradingService,
        poolMappingService,
        eventService
    )

    @Test
    fun `fresh discovered agent candidate can bootstrap into candidate for paper observation`() {
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            researchState = LeaderResearchState.DISCOVERED,
            lastSourceSeenAt = System.currentTimeMillis(),
            agentOwned = true
        )
        Mockito.`when`(paperSessionRepository.findTopByCandidateIdOrderByStartedAtDesc(1L)).thenReturn(null)
        Mockito.`when`(candidateRepository.save(anyCandidate())).thenAnswer { it.arguments[0] }
        Mockito.`when`(poolMappingService.syncCandidate(anyCandidate())).thenAnswer { it.arguments[0] }

        val result = stateMachine.advance(candidate, runId = 99L)

        assertEquals(LeaderResearchState.CANDIDATE, result.researchState)
    }

    @Test
    fun `locked candidate is not automatically advanced`() {
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            researchState = LeaderResearchState.DISCOVERED,
            lastSourceSeenAt = System.currentTimeMillis(),
            locked = true
        )
        val result = stateMachine.advance(candidate, runId = 99L)

        assertEquals(LeaderResearchState.DISCOVERED, result.researchState)
        Mockito.verify(candidateRepository, Mockito.never()).save(anyCandidate())
        Mockito.verify(poolMappingService, Mockito.never()).syncCandidate(anyCandidate())
    }

    @Test
    fun `unchanged discovered candidate does not sync into leader pool`() {
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            researchState = LeaderResearchState.DISCOVERED,
            lastSourceSeenAt = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000,
            agentOwned = false
        )
        Mockito.`when`(paperSessionRepository.findTopByCandidateIdOrderByStartedAtDesc(1L)).thenReturn(null)

        val result = stateMachine.advance(candidate, runId = 99L)

        assertEquals(LeaderResearchState.DISCOVERED, result.researchState)
        Mockito.verify(poolMappingService, Mockito.never()).syncCandidate(anyCandidate())
    }

    @Test
    fun `entering cooldown ends the active paper session`() {
        val session = com.wrbug.polymarketbot.entity.LeaderPaperSession(id = 5L, candidateId = 1L)
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            researchState = LeaderResearchState.PAPER,
            lastSourceSeenAt = System.currentTimeMillis()
        )
        Mockito.`when`(
            paperSessionRepository.findTopByCandidateIdAndStatusOrderByStartedAtDesc(
                1L, com.wrbug.polymarketbot.enums.LeaderPaperSessionStatus.ACTIVE
            )
        ).thenReturn(session)
        Mockito.`when`(paperTradingService.shouldEnterCooldown(session, true)).thenReturn("drawdown")
        Mockito.`when`(candidateRepository.save(anyCandidate())).thenAnswer { it.arguments[0] }
        Mockito.`when`(poolMappingService.syncCandidate(anyCandidate())).thenAnswer { it.arguments[0] }

        val result = stateMachine.advance(candidate, runId = 99L)

        assertEquals(LeaderResearchState.COOLDOWN, result.researchState)
        Mockito.verify(paperTradingService).endActiveSession(Mockito.eq(1L), Mockito.anyLong())
    }

    @Test
    fun `candidate whose pool item is locked is not advanced`() {
        val candidate = LeaderResearchCandidate(
            id = 1L,
            normalizedWallet = "0x1111111111111111111111111111111111111111",
            researchState = LeaderResearchState.DISCOVERED,
            lastSourceSeenAt = System.currentTimeMillis(),
            agentOwned = true
        )
        Mockito.`when`(poolMappingService.isPoolLocked(anyCandidate())).thenReturn(true)

        val result = stateMachine.advance(candidate, runId = 99L)

        assertEquals(LeaderResearchState.DISCOVERED, result.researchState)
        Mockito.verify(candidateRepository, Mockito.never()).save(anyCandidate())
    }

    private fun anyCandidate(): LeaderResearchCandidate {
        Mockito.any(LeaderResearchCandidate::class.java)
        return LeaderResearchCandidate(normalizedWallet = "0x1111111111111111111111111111111111111111")
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
