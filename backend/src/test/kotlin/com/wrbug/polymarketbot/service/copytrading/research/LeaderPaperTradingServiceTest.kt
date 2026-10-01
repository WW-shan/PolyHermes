package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderActivityEvent
import com.wrbug.polymarketbot.entity.LeaderPaperPosition
import com.wrbug.polymarketbot.entity.LeaderPaperSession
import com.wrbug.polymarketbot.entity.LeaderPaperTrade
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.enums.LeaderPaperFilterResult
import com.wrbug.polymarketbot.enums.LeaderPaperProcessingStatus
import com.wrbug.polymarketbot.enums.LeaderPaperSessionStatus
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.enums.LeaderResearchValuationStatus
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import com.wrbug.polymarketbot.repository.LeaderPaperPositionRepository
import com.wrbug.polymarketbot.repository.LeaderPaperSessionRepository
import com.wrbug.polymarketbot.repository.LeaderPaperTradeRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.service.common.MarketPriceService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.math.BigDecimal

class LeaderPaperTradingServiceTest {
    private val candidateRepository: LeaderResearchCandidateRepository = mock()
    private val activityEventRepository: LeaderActivityEventRepository = mock()
    private val paperSessionRepository: LeaderPaperSessionRepository = mock()
    private val paperTradeRepository: LeaderPaperTradeRepository = mock()
    private val paperPositionRepository: LeaderPaperPositionRepository = mock()
    private val marketPriceService: MarketPriceService = mock()
    private val eventService: LeaderResearchEventService = mock()
    private val service = LeaderPaperTradingService(
        candidateRepository = candidateRepository,
        activityEventRepository = activityEventRepository,
        paperSessionRepository = paperSessionRepository,
        paperTradeRepository = paperTradeRepository,
        paperPositionRepository = paperPositionRepository,
        marketPriceService = marketPriceService,
        eventService = eventService
    )

    @Test
    fun `trial ready requires enough age trades positive pnl and bounded unknown exposure`() {
        val session = LeaderPaperSession(
            id = 1L,
            candidateId = 1L,
            startedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000,
            tradeCount = 10,
            filteredCount = 1,
            openExposure = BigDecimal("10"),
            copyablePnl = BigDecimal("1"),
            maxDrawdown = BigDecimal("-5"),
            unknownValuationExposure = BigDecimal("1"),
            filteredRatio = BigDecimal("0.09")
        )

        assertTrue(service.isEligibleForTrialReady(session))
    }

    @Test
    fun `trial ready rejects confirmed stale unknown quote exposure`() {
        val session = LeaderPaperSession(
            id = 1L,
            candidateId = 1L,
            startedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000,
            tradeCount = 10,
            openExposure = BigDecimal("10"),
            copyablePnl = BigDecimal("1"),
            maxDrawdown = BigDecimal("-5"),
            unknownValuationExposure = BigDecimal("3"),
            filteredRatio = BigDecimal("0.09")
        )

        assertFalse(service.isEligibleForTrialReady(session))
    }

    @Test
    fun `process paper candidates records buy sell pnl and confirmed zero exposure separately`() {
        val candidate = paperCandidate()
        val session = LeaderPaperSession(id = 10L, candidateId = candidate.id!!)
        val events = listOf(
            paperEvent(id = 100L, stableKey = "buy-1", side = "BUY", price = "0.50", size = "10"),
            paperEvent(id = 101L, stableKey = "sell-1", side = "SELL", price = "0.70", size = "1")
        )
        val savedTrades = mutableListOf<LeaderPaperTrade>()
        val savedPositions = mutableListOf<LeaderPaperPosition>()
        val savedSessions = mutableListOf<LeaderPaperSession>()
        stubPaperPipeline(candidate, session, events, savedTrades, savedPositions, savedSessions)
        runBlocking {
            Mockito.`when`(marketPriceService.getCurrentMarketPrice("market-1", 0))
                .thenReturn(BigDecimal("0.60"), BigDecimal.ZERO)
        }

        val result = service.processPaperCandidates(runId = 9L, batchSize = 10)

        assertEquals(2, result.processed)
        assertEquals(0, result.filtered)
        assertEquals(0, result.failed)
        assertEquals(listOf("BUY", "SELL"), savedTrades.map { it.side })
        assertEquals(0, BigDecimal("0.20").compareTo(savedTrades.last().realizedPnl))
        assertEquals(LeaderResearchValuationStatus.CONFIRMED_ZERO, savedTrades.last().valuationStatus)
        val finalPosition = savedPositions.last()
        assertEquals(0, BigDecimal("1.00000000").compareTo(finalPosition.quantity))
        assertEquals(0, BigDecimal("0.20").compareTo(finalPosition.realizedPnl))
        val finalSummary = savedSessions.last()
        assertEquals(2, finalSummary.tradeCount)
        assertEquals(0, BigDecimal("0.500000000").compareTo(finalSummary.confirmedZeroExposure))
        assertEquals(0, BigDecimal("0.20").compareTo(finalSummary.totalRealizedPnl))
    }

    @Test
    fun `sell without paper position is filtered and sell price band does not apply`() {
        val candidate = paperCandidate()
        val session = LeaderPaperSession(id = 10L, candidateId = candidate.id!!)
        val events = listOf(
            paperEvent(id = 100L, stableKey = "sell-orphan", side = "SELL", price = "0.50", size = "1"),
            paperEvent(id = 101L, stableKey = "buy-1", side = "BUY", price = "0.50", size = "10"),
            paperEvent(id = 102L, stableKey = "sell-high", side = "SELL", price = "0.95", size = "1")
        )
        val savedTrades = mutableListOf<LeaderPaperTrade>()
        stubPaperPipeline(candidate, session, events, savedTrades, mutableListOf(), mutableListOf())
        runBlocking {
            Mockito.`when`(marketPriceService.getCurrentMarketPrice("market-1", 0)).thenReturn(BigDecimal("0.60"))
        }

        val result = service.processPaperCandidates(runId = 9L, batchSize = 10)

        assertEquals(1, result.filtered)
        assertEquals(2, result.processed)
        assertEquals("sell_without_paper_position", savedTrades.first().filterReason)
        assertEquals(LeaderPaperFilterResult.PASSED, savedTrades.last().filterResult)
    }

    @Test
    fun `events of non paper wallets are skipped and only paper wallet events are queried`() {
        val candidate = paperCandidate()
        val session = LeaderPaperSession(id = 10L, candidateId = candidate.id!!)
        stubPaperPipeline(candidate, session, emptyList(), mutableListOf(), mutableListOf(), mutableListOf())

        service.processPaperCandidates(runId = 9L, batchSize = 10)

        Mockito.verify(activityEventRepository).markNonPaperWalletEvents(
            eqArg(listOf(LeaderPaperProcessingStatus.NEW, LeaderPaperProcessingStatus.RETRYABLE)),
            eqArg(setOf(candidate.normalizedWallet)),
            eqArg(LeaderPaperProcessingStatus.FILTERED),
            anyLong()
        )
        Mockito.verify(activityEventRepository, Mockito.never())
            .findByPaperProcessingStatusInAndUsableForPaperTrueOrderByEventTimeAsc(anyProcessingStatuses(), anyPageable())
    }

    private fun <T> eqArg(value: T): T {
        Mockito.eq(value)
        return value
    }

    private fun anyLong(): Long {
        Mockito.anyLong()
        return 0L
    }

    private fun anyPageable(): org.springframework.data.domain.Pageable {
        Mockito.any(org.springframework.data.domain.Pageable::class.java)
        return PageRequest.of(0, 1)
    }

    @Test
    fun `process paper candidates records filtered trade without position mutation`() {
        val candidate = paperCandidate()
        val session = LeaderPaperSession(id = 10L, candidateId = candidate.id!!)
        val savedTrades = mutableListOf<LeaderPaperTrade>()
        val savedPositions = mutableListOf<LeaderPaperPosition>()
        val savedSessions = mutableListOf<LeaderPaperSession>()
        stubPaperPipeline(
            candidate = candidate,
            session = session,
            events = listOf(paperEvent(id = 100L, stableKey = "bad-price", side = "BUY", price = "0.05", size = "10")),
            savedTrades = savedTrades,
            savedPositions = savedPositions,
            savedSessions = savedSessions
        )

        val result = service.processPaperCandidates(runId = 9L, batchSize = 10)

        assertEquals(0, result.processed)
        assertEquals(1, result.filtered)
        assertEquals(0, result.failed)
        assertEquals(LeaderPaperFilterResult.FILTERED, savedTrades.single().filterResult)
        assertEquals("price_outside_safe_band", savedTrades.single().filterReason)
        assertTrue(savedPositions.isEmpty())
    }

    @Test
    fun `duplicate leader trade does not create another paper trade or mutate position`() {
        val candidate = paperCandidate()
        val session = LeaderPaperSession(id = 10L, candidateId = candidate.id!!)
        val existingTrade = LeaderPaperTrade(
            id = 99L,
            sessionId = session.id!!,
            candidateId = candidate.id!!,
            leaderTradeId = "duplicate-1",
            marketId = "market-1",
            side = "BUY",
            eventTime = 1_700_000_000_000
        )
        val savedTrades = mutableListOf(existingTrade)
        val savedPositions = mutableListOf<LeaderPaperPosition>()
        val savedSessions = mutableListOf<LeaderPaperSession>()
        stubPaperPipeline(
            candidate = candidate,
            session = session,
            events = listOf(paperEvent(id = 100L, stableKey = "duplicate-1", side = "BUY", price = "0.50", size = "10")),
            savedTrades = savedTrades,
            savedPositions = savedPositions,
            savedSessions = savedSessions
        )
        Mockito.`when`(paperTradeRepository.existsBySessionIdAndLeaderTradeIdAndSide(session.id!!, "duplicate-1", "BUY"))
            .thenReturn(true)

        val result = service.processPaperCandidates(runId = 9L, batchSize = 10)

        assertEquals(1, result.processed)
        assertEquals(1, savedTrades.size)
        assertTrue(savedPositions.isEmpty())
    }

    @Test
    fun `claim miss isolates concurrent paper processing`() {
        val candidate = paperCandidate()
        val session = LeaderPaperSession(id = 10L, candidateId = candidate.id!!)
        val savedTrades = mutableListOf<LeaderPaperTrade>()
        val savedPositions = mutableListOf<LeaderPaperPosition>()
        val savedSessions = mutableListOf<LeaderPaperSession>()
        stubPaperPipeline(
            candidate = candidate,
            session = session,
            events = listOf(paperEvent(id = 100L, stableKey = "claimed-elsewhere", side = "BUY", price = "0.50", size = "10")),
            savedTrades = savedTrades,
            savedPositions = savedPositions,
            savedSessions = savedSessions,
            claimResult = 0
        )

        val result = service.processPaperCandidates(runId = 9L, batchSize = 10)

        assertEquals(0, result.processed)
        assertEquals(0, result.filtered)
        assertEquals(0, result.failed)
        assertTrue(savedTrades.isEmpty())
        assertTrue(savedPositions.isEmpty())
    }

    @Test
    fun `processing failure becomes failed after max attempts and does not block batch`() {
        val candidate = paperCandidate()
        val session = LeaderPaperSession(id = 10L, candidateId = candidate.id!!)
        val failedEvents = mutableListOf<LeaderActivityEvent>()
        stubPaperPipeline(
            candidate = candidate,
            session = session,
            events = listOf(
                paperEvent(
                    id = 100L,
                    stableKey = "save-fails",
                    side = "BUY",
                    price = "0.50",
                    size = "10",
                    processingAttempts = 2
                )
            ),
            savedTrades = mutableListOf(),
            savedPositions = mutableListOf(),
            savedSessions = mutableListOf()
        )
        runBlocking {
            Mockito.`when`(marketPriceService.getCurrentMarketPrice("market-1", 0)).thenReturn(BigDecimal("0.60"))
        }
        Mockito.`when`(paperTradeRepository.save(anyTrade())).thenThrow(IllegalStateException("db down"))
        Mockito.`when`(activityEventRepository.save(anyActivityEvent())).thenAnswer {
            val event = it.arguments[0] as LeaderActivityEvent
            failedEvents += event
            event
        }

        val result = service.processPaperCandidates(runId = 9L, batchSize = 10)

        assertEquals(0, result.processed)
        assertEquals(0, result.filtered)
        assertEquals(1, result.failed)
        assertEquals(LeaderPaperProcessingStatus.FAILED, failedEvents.last().paperProcessingStatus)
        assertTrue(failedEvents.last().lastProcessingError!!.contains("db down"))
    }

    private fun stubPaperPipeline(
        candidate: LeaderResearchCandidate,
        session: LeaderPaperSession,
        events: List<LeaderActivityEvent>,
        savedTrades: MutableList<LeaderPaperTrade>,
        savedPositions: MutableList<LeaderPaperPosition>,
        savedSessions: MutableList<LeaderPaperSession>,
        claimResult: Int = 1
    ) {
        Mockito.`when`(candidateRepository.findByResearchStateIn(listOf(LeaderResearchState.PAPER, LeaderResearchState.TRIAL_READY)))
            .thenReturn(listOf(candidate))
        Mockito.`when`(candidateRepository.save(anyCandidate())).thenAnswer { it.arguments[0] }
        Mockito.`when`(paperSessionRepository.findTopByCandidateIdAndStatusOrderByStartedAtDesc(candidate.id!!, LeaderPaperSessionStatus.ACTIVE))
            .thenReturn(null, session, session, session, session)
        Mockito.`when`(paperSessionRepository.save(anySession())).thenAnswer {
            val incoming = it.arguments[0] as LeaderPaperSession
            val saved = if (incoming.id == null) incoming.copy(id = session.id) else incoming
            savedSessions += saved
            saved
        }
        Mockito.`when`(
            activityEventRepository.findByPaperProcessingStatusInAndUsableForPaperTrueAndNormalizedWalletInOrderByEventTimeAsc(
                listOf(LeaderPaperProcessingStatus.NEW, LeaderPaperProcessingStatus.RETRYABLE),
                setOf(candidate.normalizedWallet),
                PageRequest.of(0, 10)
            )
        ).thenReturn(PageImpl(events))
        Mockito.`when`(
            activityEventRepository.claimForPaperProcessing(
                Mockito.anyLong(),
                anyProcessingStatuses(),
                anyProcessingStatus(),
                Mockito.anyLong()
            )
        ).thenReturn(claimResult)
        Mockito.`when`(activityEventRepository.save(anyActivityEvent())).thenAnswer { it.arguments[0] }
        Mockito.`when`(paperPositionRepository.findBySessionIdAndMarketIdAndOutcomeIndex(session.id!!, "market-1", 0))
            .thenAnswer { savedPositions.lastOrNull { it.marketId == "market-1" && it.outcomeIndex == 0 } }
        Mockito.`when`(paperPositionRepository.findBySessionIdOrderByUpdatedAtDesc(session.id!!))
            .thenAnswer { savedPositions.toList().asReversed() }
        Mockito.`when`(paperPositionRepository.save(anyPosition())).thenAnswer {
            val position = it.arguments[0] as LeaderPaperPosition
            val existingIndex = savedPositions.indexOfFirst { saved ->
                saved.sessionId == position.sessionId &&
                    saved.marketId == position.marketId &&
                    saved.outcomeIndex == position.outcomeIndex
            }
            if (existingIndex >= 0) {
                savedPositions[existingIndex] = position
            } else {
                savedPositions += position
            }
            position
        }
        Mockito.`when`(paperTradeRepository.existsBySessionIdAndLeaderTradeIdAndSide(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString()))
            .thenReturn(false)
        Mockito.`when`(paperTradeRepository.findBySessionIdOrderByEventTimeAsc(session.id!!))
            .thenAnswer { savedTrades.sortedBy { it.eventTime } }
        Mockito.`when`(paperTradeRepository.save(anyTrade())).thenAnswer {
            val trade = it.arguments[0] as LeaderPaperTrade
            savedTrades += trade
            trade
        }
    }

    private fun paperCandidate() = LeaderResearchCandidate(
        id = 1L,
        normalizedWallet = "0x1111111111111111111111111111111111111111",
        researchState = LeaderResearchState.PAPER
    )

    private fun paperEvent(
        id: Long,
        stableKey: String,
        side: String,
        price: String,
        size: String,
        processingAttempts: Int = 0
    ) = LeaderActivityEvent(
        id = id,
        source = "ACTIVITY_DERIVED",
        sourceEventId = stableKey,
        stableEventKey = stableKey,
        normalizedWallet = "0x1111111111111111111111111111111111111111",
        marketId = "market-1",
        side = side,
        outcomeIndex = 0,
        price = BigDecimal(price),
        size = BigDecimal(size),
        amount = BigDecimal(price).multiply(BigDecimal(size)),
        eventTime = 1_700_000_000_000 + id,
        rawPayloadHash = "hash-$stableKey",
        usableForPaper = true,
        paperProcessingStatus = if (processingAttempts > 0) LeaderPaperProcessingStatus.RETRYABLE else LeaderPaperProcessingStatus.NEW,
        processingAttempts = processingAttempts
    )

    private fun anyCandidate(): LeaderResearchCandidate {
        Mockito.any(LeaderResearchCandidate::class.java)
        return paperCandidate()
    }

    private fun anySession(): LeaderPaperSession {
        Mockito.any(LeaderPaperSession::class.java)
        return LeaderPaperSession(candidateId = 1)
    }

    private fun anyActivityEvent(): LeaderActivityEvent {
        Mockito.any(LeaderActivityEvent::class.java)
        return paperEvent(id = 1, stableKey = "dummy", side = "BUY", price = "0.50", size = "1")
    }

    private fun anyPosition(): LeaderPaperPosition {
        Mockito.any(LeaderPaperPosition::class.java)
        return LeaderPaperPosition(sessionId = 10, candidateId = 1, marketId = "market-1")
    }

    private fun anyTrade(): LeaderPaperTrade {
        Mockito.any(LeaderPaperTrade::class.java)
        return LeaderPaperTrade(sessionId = 10, candidateId = 1, leaderTradeId = "dummy", marketId = "market-1", side = "BUY", eventTime = 1)
    }

    private fun anyProcessingStatuses(): Collection<LeaderPaperProcessingStatus> {
        Mockito.anyCollection<LeaderPaperProcessingStatus>()
        return emptyList()
    }

    private fun anyProcessingStatus(): LeaderPaperProcessingStatus {
        Mockito.any(LeaderPaperProcessingStatus::class.java)
        return LeaderPaperProcessingStatus.PROCESSING
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = org.mockito.Mockito.mock(T::class.java)
}
