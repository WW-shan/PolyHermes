package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.*
import com.wrbug.polymarketbot.enums.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface LeaderResearchRunRepository : JpaRepository<LeaderResearchRun, Long> {
    fun findTopByOrderByStartedAtDesc(): LeaderResearchRun?
    fun findByStatus(status: LeaderResearchRunStatus): List<LeaderResearchRun>
    fun findTopByStatusOrderByStartedAtDesc(status: LeaderResearchRunStatus): LeaderResearchRun?
}

@Repository
interface LeaderResearchCandidateRepository : JpaRepository<LeaderResearchCandidate, Long> {
    fun findByNormalizedWallet(normalizedWallet: String): LeaderResearchCandidate?
    fun findByLeaderId(leaderId: Long): LeaderResearchCandidate?
    fun findByPoolId(poolId: Long): LeaderResearchCandidate?
    fun findByResearchState(researchState: LeaderResearchState): List<LeaderResearchCandidate>
    fun findByResearchStateIn(states: Collection<LeaderResearchState>): List<LeaderResearchCandidate>
    fun findByResearchStateIn(states: Collection<LeaderResearchState>, pageable: Pageable): Page<LeaderResearchCandidate>
    fun findAllByOrderByUpdatedAtDesc(pageable: Pageable): Page<LeaderResearchCandidate>
    fun countByResearchState(researchState: LeaderResearchState): Long

    @Query(
        """
        select c from LeaderResearchCandidate c
        where (:state is null or c.researchState = :state)
          and (
            :query is null
            or lower(c.normalizedWallet) like lower(concat(concat('%', :query), '%'))
            or lower(c.source) like lower(concat(concat('%', :query), '%'))
            or lower(coalesce(c.reason, '')) like lower(concat(concat('%', :query), '%'))
            or lower(coalesce(c.sourceEvidence, '')) like lower(concat(concat('%', :query), '%'))
          )
        order by c.updatedAt desc
        """
    )
    fun search(
        @Param("state") state: LeaderResearchState?,
        @Param("query") query: String?,
        pageable: Pageable
    ): Page<LeaderResearchCandidate>
}

@Repository
interface LeaderResearchScoreRepository : JpaRepository<LeaderResearchScore, Long> {
    fun findTopByCandidateIdOrderByCreatedAtDesc(candidateId: Long): LeaderResearchScore?
    fun findByCandidateIdOrderByCreatedAtDesc(candidateId: Long): List<LeaderResearchScore>
}

@Repository
interface LeaderResearchEventRepository : JpaRepository<LeaderResearchEvent, Long> {
    fun findByCandidateIdOrderByCreatedAtDesc(candidateId: Long, pageable: Pageable): Page<LeaderResearchEvent>
    fun findByRunIdOrderByCreatedAtDesc(runId: Long): List<LeaderResearchEvent>
    fun findByNotificationStatusOrderByCreatedAtAsc(status: LeaderResearchNotificationStatus, pageable: Pageable): Page<LeaderResearchEvent>
    fun findTopByDedupeKey(dedupeKey: String): LeaderResearchEvent?
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<LeaderResearchEvent>
}

@Repository
interface LeaderResearchSourceStateRepository : JpaRepository<LeaderResearchSourceState, Long> {
    fun findBySourceType(sourceType: LeaderResearchSourceType): LeaderResearchSourceState?
    fun findAllByOrderByUpdatedAtDesc(): List<LeaderResearchSourceState>
}

@Repository
interface LeaderActivityEventRepository : JpaRepository<LeaderActivityEvent, Long> {
    fun findByStableEventKey(stableEventKey: String): LeaderActivityEvent?
    fun findBySourceAndSourceEventId(source: String, sourceEventId: String): LeaderActivityEvent?
    fun findTopByOrderByEventTimeDesc(): LeaderActivityEvent?
    fun findByNormalizedWalletAndEventTimeBetweenOrderByEventTimeAsc(normalizedWallet: String, start: Long, end: Long): List<LeaderActivityEvent>
    fun findByUsableForDiscoveryTrueAndEventTimeGreaterThanEqual(eventTime: Long): List<LeaderActivityEvent>
    fun findByPaperProcessingStatusInAndUsableForPaperTrueOrderByEventTimeAsc(statuses: Collection<LeaderPaperProcessingStatus>, pageable: Pageable): Page<LeaderActivityEvent>

    /** 仅取纸跟钱包的待处理事件，避免其他钱包的 NEW 事件占满批次导致纸跟卡死 */
    fun findByPaperProcessingStatusInAndUsableForPaperTrueAndNormalizedWalletInOrderByEventTimeAsc(
        statuses: Collection<LeaderPaperProcessingStatus>,
        wallets: Collection<String>,
        pageable: Pageable
    ): Page<LeaderActivityEvent>

    /**
     * 把非纸跟钱包的待处理事件批量标记为 FILTERED（不参与纸跟）
     * @return 更新行数
     */
    @Modifying
    @Query(
        "update LeaderActivityEvent e set e.paperProcessingStatus = :filtered, e.updatedAt = :now " +
            "where e.paperProcessingStatus in :statuses and e.usableForPaper = true " +
            "and (e.normalizedWallet is null or e.normalizedWallet not in :paperWallets)"
    )
    fun markNonPaperWalletEvents(
        @Param("statuses") statuses: Collection<LeaderPaperProcessingStatus>,
        @Param("paperWallets") paperWallets: Collection<String>,
        @Param("filtered") filtered: LeaderPaperProcessingStatus,
        @Param("now") now: Long
    ): Int

    /** 无纸跟候选时把全部待处理事件标记为 FILTERED */
    @Modifying
    @Query(
        "update LeaderActivityEvent e set e.paperProcessingStatus = :filtered, e.updatedAt = :now " +
            "where e.paperProcessingStatus in :statuses and e.usableForPaper = true"
    )
    fun markAllPendingPaperEvents(
        @Param("statuses") statuses: Collection<LeaderPaperProcessingStatus>,
        @Param("filtered") filtered: LeaderPaperProcessingStatus,
        @Param("now") now: Long
    ): Int

    fun deleteByEventTimeLessThanAndPaperProcessingStatusIn(
        eventTime: Long,
        statuses: Collection<LeaderPaperProcessingStatus>
    ): Long

    /**
     * 分批删除过期活动事件（单条 SQL，自带事务，避免派生删除逐条 remove 且无事务时报 TransactionRequiredException）
     * @param statuses 状态名（LeaderPaperProcessingStatus.name）
     * @return 删除行数
     */
    @Transactional
    @Modifying
    @Query(
        value = "DELETE FROM leader_activity_event WHERE event_time < :eventTime " +
            "AND paper_processing_status IN (:statuses) LIMIT :batchSize",
        nativeQuery = true
    )
    fun deleteBatchByEventTimeLessThanAndStatusIn(
        @Param("eventTime") eventTime: Long,
        @Param("statuses") statuses: Collection<String>,
        @Param("batchSize") batchSize: Int
    ): Int

    @Modifying
    @Query(
        "update LeaderActivityEvent e set e.paperProcessingStatus = :nextStatus, e.paperProcessingStartedAt = :startedAt, e.processingAttempts = e.processingAttempts + 1, e.updatedAt = :startedAt where e.id = :id and e.paperProcessingStatus in :allowed"
    )
    fun claimForPaperProcessing(
        @Param("id") id: Long,
        @Param("allowed") allowed: Collection<LeaderPaperProcessingStatus>,
        @Param("nextStatus") nextStatus: LeaderPaperProcessingStatus,
        @Param("startedAt") startedAt: Long
    ): Int
}

@Repository
interface LeaderPaperSessionRepository : JpaRepository<LeaderPaperSession, Long> {
    fun findTopByCandidateIdAndStatusOrderByStartedAtDesc(candidateId: Long, status: LeaderPaperSessionStatus): LeaderPaperSession?
    fun findTopByCandidateIdOrderByStartedAtDesc(candidateId: Long): LeaderPaperSession?
    fun findByCandidateIdOrderByStartedAtDesc(candidateId: Long): List<LeaderPaperSession>
    fun findByUpdatedAtLessThanAndStatusIn(updatedAt: Long, statuses: Collection<LeaderPaperSessionStatus>, pageable: Pageable): Page<LeaderPaperSession>

    @Query(
        """
        select s from LeaderPaperSession s
        where s.candidateId in :candidateIds
          and s.startedAt = (
            select max(s2.startedAt) from LeaderPaperSession s2 where s2.candidateId = s.candidateId
          )
        """
    )
    fun findLatestByCandidateIds(@Param("candidateIds") candidateIds: Collection<Long>): List<LeaderPaperSession>
}

@Repository
interface LeaderPaperTradeRepository : JpaRepository<LeaderPaperTrade, Long> {
    fun existsBySessionIdAndLeaderTradeIdAndSide(sessionId: Long, leaderTradeId: String, side: String): Boolean
    fun findBySessionIdOrderByEventTimeDesc(sessionId: Long, pageable: Pageable): Page<LeaderPaperTrade>
    fun findBySessionIdOrderByEventTimeAsc(sessionId: Long): List<LeaderPaperTrade>
    fun countBySessionId(sessionId: Long): Long
    fun countBySessionIdAndFilterResult(sessionId: Long, filterResult: LeaderPaperFilterResult): Long
}

@Repository
interface LeaderPaperPositionRepository : JpaRepository<LeaderPaperPosition, Long> {
    fun findBySessionIdAndMarketIdAndOutcomeIndex(sessionId: Long, marketId: String, outcomeIndex: Int?): LeaderPaperPosition?
    fun findBySessionIdOrderByUpdatedAtDesc(sessionId: Long): List<LeaderPaperPosition>
    fun findByCandidateIdOrderByUpdatedAtDesc(candidateId: Long): List<LeaderPaperPosition>
}
