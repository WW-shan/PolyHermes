package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderPaperSession
import com.wrbug.polymarketbot.enums.LeaderPaperProcessingStatus
import com.wrbug.polymarketbot.enums.LeaderPaperSessionStatus
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import com.wrbug.polymarketbot.repository.LeaderPaperSessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest

class LeaderResearchRetentionServiceTest {
    private val activityRepository: LeaderActivityEventRepository = mock()
    private val sessionRepository: LeaderPaperSessionRepository = mock()

    @Test
    fun `cleanup deletes expired activity events in batches including stale NEW and terminal paper sessions`() {
        val staleSessions = listOf(
            LeaderPaperSession(id = 1, candidateId = 1, status = LeaderPaperSessionStatus.COMPLETED),
            LeaderPaperSession(id = 2, candidateId = 2, status = LeaderPaperSessionStatus.FAILED)
        )
        val activityStatuses = listOf(
            LeaderPaperProcessingStatus.PROCESSED,
            LeaderPaperProcessingStatus.FILTERED,
            LeaderPaperProcessingStatus.FAILED,
            LeaderPaperProcessingStatus.NEW,
            LeaderPaperProcessingStatus.RETRYABLE
        ).map { it.name }
        val terminalSessionStatuses = listOf(
            LeaderPaperSessionStatus.COMPLETED,
            LeaderPaperSessionStatus.FAILED
        )
        val now = 1_000_000_000L
        val activityCutoff = -6_776_000_000L
        val paperCutoff = -14_552_000_000L
        val paperPage = PageRequest.of(0, 100)
        val service = LeaderResearchRetentionService(
            activityEventRepository = activityRepository,
            paperSessionRepository = sessionRepository,
            enabled = true,
            activityRetentionDays = 90,
            paperSessionRetentionDays = 180,
            maxPaperSessionsPerRun = 100
        )
        Mockito.`when`(
            activityRepository.deleteBatchByEventTimeLessThanAndStatusIn(
                activityCutoff,
                activityStatuses,
                LeaderResearchRetentionService.ACTIVITY_DELETE_BATCH_SIZE
            )
        ).thenReturn(LeaderResearchRetentionService.ACTIVITY_DELETE_BATCH_SIZE, 7)
        Mockito.`when`(
            sessionRepository.findByUpdatedAtLessThanAndStatusIn(
                paperCutoff,
                terminalSessionStatuses,
                paperPage
            )
        ).thenReturn(PageImpl(staleSessions))

        val result = service.cleanup(now = now)

        assertEquals(LeaderResearchRetentionService.ACTIVITY_DELETE_BATCH_SIZE + 7L, result.deletedActivityEvents)
        assertEquals(2, result.deletedPaperSessions)
        Mockito.verify(activityRepository, Mockito.times(2)).deleteBatchByEventTimeLessThanAndStatusIn(
            activityCutoff,
            activityStatuses,
            LeaderResearchRetentionService.ACTIVITY_DELETE_BATCH_SIZE
        )
        Mockito.verify(sessionRepository).deleteAll(staleSessions)
    }

    @Test
    fun `disabled cleanup does nothing`() {
        val service = LeaderResearchRetentionService(
            activityEventRepository = activityRepository,
            paperSessionRepository = sessionRepository,
            enabled = false,
            activityRetentionDays = 90,
            paperSessionRetentionDays = 180,
            maxPaperSessionsPerRun = 100
        )

        val result = service.cleanup()

        assertEquals(0, result.deletedActivityEvents)
        assertEquals(0, result.deletedPaperSessions)
        Mockito.verifyNoInteractions(activityRepository, sessionRepository)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
