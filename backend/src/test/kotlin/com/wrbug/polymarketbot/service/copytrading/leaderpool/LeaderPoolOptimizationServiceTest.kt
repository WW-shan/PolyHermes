package com.wrbug.polymarketbot.service.copytrading.leaderpool

import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.entity.LeaderPaperSession
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.entity.LeaderResearchRun
import com.wrbug.polymarketbot.enums.LeaderPaperSessionStatus
import com.wrbug.polymarketbot.enums.LeaderResearchRunStatus
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.LeaderPaperSessionRepository
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.repository.LeaderResearchRunRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

class LeaderPoolOptimizationServiceTest {

    private val candidateRepository: LeaderResearchCandidateRepository = mock()
    private val paperSessionRepository: LeaderPaperSessionRepository = mock()
    private val poolRepository: LeaderPoolRepository = mock()
    private val leaderRepository: LeaderRepository = mock()
    private val researchRunRepository: LeaderResearchRunRepository = mock()
    private val service = LeaderPoolOptimizationService(
        candidateRepository = candidateRepository,
        paperSessionRepository = paperSessionRepository,
        poolRepository = poolRepository,
        leaderRepository = leaderRepository,
        researchRunRepository = researchRunRepository
    )

    @Test
    fun `ranks auto ready first and keeps blocked candidates out of top 3`() {
        val now = System.currentTimeMillis()
        val ready = candidate(id = 1, leaderId = 11, state = LeaderResearchState.TRIAL_READY, score = "80", lastSeen = now)
        val locked = candidate(id = 2, leaderId = 12, state = LeaderResearchState.TRIAL_READY, score = "90", lastSeen = now, locked = true)
        val highRisk = candidate(id = 3, leaderId = 13, state = LeaderResearchState.PAPER, score = "70", lastSeen = now)
        val healthySession = paperSession(candidateId = 1, copyablePnl = "5", tradeCount = 40, filteredRatio = "0.10")
        val lockedSession = paperSession(candidateId = 2, copyablePnl = "5", tradeCount = 40, filteredRatio = "0.10")
        val highRiskSession = paperSession(candidateId = 3, copyablePnl = "5", tradeCount = 40, filteredRatio = "0.20")

        Mockito.`when`(candidateRepository.findAll()).thenReturn(listOf(ready, locked, highRisk))
        Mockito.`when`(paperSessionRepository.findLatestByCandidateIds(listOf(1L, 2L, 3L)))
            .thenReturn(listOf(healthySession, lockedSession, highRiskSession))
        Mockito.`when`(leaderRepository.findAllById(listOf(11L, 12L, 13L))).thenReturn(
            listOf(leader(11, "ready"), leader(12, "locked"), leader(13, "high-risk"))
        )
        Mockito.`when`(researchRunRepository.findTopByStatusOrderByStartedAtDesc(LeaderResearchRunStatus.SUCCESS))
            .thenReturn(LeaderResearchRun(status = LeaderResearchRunStatus.SUCCESS, finishedAt = now))

        val result = service.getOptimization()

        assertEquals(listOf(1L, 3L, 2L), result.items.map { it.candidateId })
        assertEquals("AUTO_READY", result.items[0].recommendationTier)
        assertEquals("HIGH_RISK_PILOT", result.items[1].recommendationTier)
        assertEquals("BLOCKED", result.items[2].recommendationTier)
        assertEquals("LOCKED", result.items[2].reasonCode)
        assertEquals(1, result.eligibleCount)
        assertEquals(listOf(1L), result.top3.map { it.candidateId })
        assertNull(result.staleReason)
        assertTrue(result.safeMode)
    }

    @Test
    fun `blocks auto ready when unknown valuation ratio is above twenty percent`() {
        val now = System.currentTimeMillis()
        val candidate = candidate(id = 1, leaderId = 11, state = LeaderResearchState.TRIAL_READY, score = "90", lastSeen = now)
        val session = paperSession(
            candidateId = 1,
            copyablePnl = "5",
            tradeCount = 40,
            filteredRatio = "0.10",
            unknownExposure = "5",
            openExposure = "10"
        )

        Mockito.`when`(candidateRepository.findAll()).thenReturn(listOf(candidate))
        Mockito.`when`(paperSessionRepository.findLatestByCandidateIds(listOf(1L))).thenReturn(listOf(session))
        Mockito.`when`(leaderRepository.findAllById(listOf(11L))).thenReturn(listOf(leader(11, "unknown")))
        Mockito.`when`(researchRunRepository.findTopByStatusOrderByStartedAtDesc(LeaderResearchRunStatus.SUCCESS))
            .thenReturn(LeaderResearchRun(status = LeaderResearchRunStatus.SUCCESS, finishedAt = now))

        val result = service.getOptimization()

        assertEquals("BLOCKED", result.items.single().recommendationTier)
        assertEquals("UNKNOWN_RATIO_HIGH", result.items.single().reasonCode)
        assertEquals(0, result.eligibleCount)
        assertTrue(result.top3.isEmpty())
    }

    @Test
    fun `reports missing and stale research runs without failing ranking`() {
        Mockito.`when`(candidateRepository.findAll()).thenReturn(emptyList())
        Mockito.`when`(researchRunRepository.findTopByStatusOrderByStartedAtDesc(LeaderResearchRunStatus.SUCCESS))
            .thenReturn(null)

        val missing = service.getOptimization()
        assertEquals("NO_SUCCESSFUL_RESEARCH_RUN", missing.staleReason)

        Mockito.`when`(researchRunRepository.findTopByStatusOrderByStartedAtDesc(LeaderResearchRunStatus.SUCCESS))
            .thenReturn(
                LeaderResearchRun(
                    status = LeaderResearchRunStatus.SUCCESS,
                    finishedAt = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
                )
            )

        val stale = service.getOptimization()
        assertEquals("RESEARCH_RUN_STALE", stale.staleReason)
    }

    private fun candidate(
        id: Long,
        leaderId: Long,
        state: LeaderResearchState,
        score: String,
        lastSeen: Long,
        locked: Boolean = false
    ) = LeaderResearchCandidate(
        id = id,
        normalizedWallet = "0x" + id.toString().padStart(40, '0'),
        leaderId = leaderId,
        researchState = state,
        score = BigDecimal(score),
        lastSourceSeenAt = lastSeen,
        locked = locked
    )

    private fun paperSession(
        candidateId: Long,
        copyablePnl: String,
        tradeCount: Int,
        filteredRatio: String,
        unknownExposure: String = "1",
        openExposure: String = "20"
    ) = LeaderPaperSession(
        candidateId = candidateId,
        status = LeaderPaperSessionStatus.ACTIVE,
        tradeCount = tradeCount,
        copyablePnl = BigDecimal(copyablePnl),
        filteredRatio = BigDecimal(filteredRatio),
        maxDrawdown = BigDecimal("-5"),
        unknownValuationExposure = BigDecimal(unknownExposure),
        openExposure = BigDecimal(openExposure)
    )

    private fun leader(id: Long, name: String) = Leader(
        id = id,
        leaderAddress = "0x" + id.toString().padStart(40, '0'),
        leaderName = name,
        createdAt = 1,
        updatedAt = 1
    )

    companion object {
        private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
    }
}
