package com.wrbug.polymarketbot.service.copytrading.leaderpool

import com.wrbug.polymarketbot.dto.LeaderPoolOptimizationItemDto
import com.wrbug.polymarketbot.dto.LeaderPoolOptimizationResponse
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.entity.LeaderPaperSession
import com.wrbug.polymarketbot.entity.LeaderPool
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.enums.LeaderResearchRunStatus
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.LeaderPaperSessionRepository
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.repository.LeaderResearchRunRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class LeaderPoolOptimizationService(
    private val candidateRepository: LeaderResearchCandidateRepository,
    private val paperSessionRepository: LeaderPaperSessionRepository,
    private val poolRepository: LeaderPoolRepository,
    private val leaderRepository: LeaderRepository,
    private val researchRunRepository: LeaderResearchRunRepository
) {
    open fun getOptimization(limit: Int = DEFAULT_LIMIT): LeaderPoolOptimizationResponse {
        val now = System.currentTimeMillis()
        val candidates = candidateRepository.findAll()
        val candidateIds = candidates.mapNotNull { it.id }
        val sessionsByCandidateId = if (candidateIds.isEmpty()) {
            emptyMap()
        } else {
            paperSessionRepository.findLatestByCandidateIds(candidateIds).associateBy { it.candidateId }
        }
        val poolIds = candidates.mapNotNull { it.poolId }.distinct()
        val poolsById = if (poolIds.isEmpty()) {
            emptyMap()
        } else {
            poolRepository.findByIdIn(poolIds).mapNotNull { pool -> pool.id?.let { it to pool } }.toMap()
        }
        val leaderIds = candidates.mapNotNull { candidate ->
            candidate.leaderId ?: candidate.poolId?.let { poolsById[it]?.leaderId }
        }.distinct()
        val leadersById = if (leaderIds.isEmpty()) {
            emptyMap()
        } else {
            leaderRepository.findAllById(leaderIds).mapNotNull { leader -> leader.id?.let { it to leader } }.toMap()
        }

        val scored = candidates.mapNotNull { candidate ->
            val candidateId = candidate.id ?: return@mapNotNull null
            val pool = candidate.poolId?.let { poolsById[it] }
            scoreCandidate(candidate, candidateId, sessionsByCandidateId[candidateId], pool, leadersById, now)
        }.sortedWith(
            compareByDescending<ScoredPick> { tierPriority(it.tier) }
                .thenByDescending { it.optimizationScore }
                .thenByDescending { it.researchScore }
                .thenByDescending { it.session?.tradeCount ?: 0 }
                .thenBy { it.candidateId }
        )

        val ranked = scored.mapIndexed { index, pick -> pick.toDto(index + 1) }
        val eligible = ranked.filter { it.recommendationTier == TIER_AUTO_READY }
        val safeLimit = limit.coerceIn(1, MAX_LIMIT)

        return LeaderPoolOptimizationResponse(
            generatedAt = now,
            staleReason = researchStaleReason(now),
            candidateCount = candidates.size,
            eligibleCount = eligible.size,
            items = ranked.take(safeLimit),
            top3 = eligible.take(MAX_TOP3)
        )
    }

    private fun scoreCandidate(
        candidate: LeaderResearchCandidate,
        candidateId: Long,
        session: LeaderPaperSession?,
        pool: LeaderPool?,
        leadersById: Map<Long, Leader>,
        now: Long
    ): ScoredPick {
        val leaderId = candidate.leaderId ?: pool?.leaderId
        val leader = leaderId?.let { leadersById[it] }
        val unknownRatio = unknownRatio(session)
        val sourceFresh = sourceFresh(candidate.lastSourceSeenAt ?: pool?.updatedAt ?: candidate.updatedAt, now)
        val score = optimizationScore(candidate, session, sourceFresh)
        val blockReason = autoReadyBlockReason(candidate, session, leaderId, unknownRatio, now)
        val tier = when {
            blockReason == null -> TIER_AUTO_READY
            blockReason == REASON_NOT_TRIAL_READY &&
                candidate.researchState == LeaderResearchState.PAPER &&
                session != null &&
                session.tradeCount >= MIN_AUTO_READY_TRADES &&
                session.copyablePnl > BigDecimal.ZERO &&
                session.filteredRatio < HIGH_RISK_MAX_FILTERED_RATIO -> TIER_HIGH_RISK_PILOT
            candidate.researchState in setOf(
                LeaderResearchState.PAPER,
                LeaderResearchState.CANDIDATE,
                LeaderResearchState.DISCOVERED
            ) -> TIER_PAPER_WATCH
            else -> TIER_BLOCKED
        }
        val reasonCode = when (tier) {
            TIER_AUTO_READY -> TIER_AUTO_READY
            TIER_HIGH_RISK_PILOT -> TIER_HIGH_RISK_PILOT
            TIER_PAPER_WATCH -> TIER_PAPER_WATCH
            else -> blockReason ?: TIER_BLOCKED
        }
        return ScoredPick(
            candidateId = candidateId,
            leaderId = leaderId,
            poolId = pool?.id,
            leader = leader,
            leaderAddress = leader?.leaderAddress ?: candidate.normalizedWallet,
            researchState = candidate.researchState,
            tier = tier,
            optimizationScore = score,
            researchScore = candidate.score ?: BigDecimal.ZERO,
            session = session,
            unknownRatio = unknownRatio,
            sourceFresh = sourceFresh,
            reasonCode = reasonCode
        )
    }

    private fun autoReadyBlockReason(
        candidate: LeaderResearchCandidate,
        session: LeaderPaperSession?,
        leaderId: Long?,
        unknownRatio: BigDecimal,
        now: Long
    ): String? {
        if (leaderId == null) return REASON_MISSING_LEADER
        if (candidate.locked) return REASON_LOCKED
        if (candidate.retiredAt != null || candidate.researchState == LeaderResearchState.RETIRED) return REASON_RETIRED
        if (candidate.cooldownUntil != null && candidate.cooldownUntil > now) return REASON_COOLDOWN
        if (candidate.riskFlags?.contains("BLACKLISTED", ignoreCase = true) == true) return REASON_BLACKLISTED
        if (candidate.researchState != LeaderResearchState.TRIAL_READY) return REASON_NOT_TRIAL_READY
        if (session == null) return REASON_MISSING_PAPER_SESSION
        if (unknownRatio > MAX_UNKNOWN_RATIO) return REASON_UNKNOWN_RATIO_HIGH
        if (session.filteredRatio >= MAX_AUTO_READY_FILTERED_RATIO) return REASON_FILTERED_RATIO_HIGH
        if (session.tradeCount < MIN_AUTO_READY_TRADES) return REASON_INSUFFICIENT_TRADES
        if (session.copyablePnl <= BigDecimal.ZERO) return REASON_NON_POSITIVE_PNL
        if (session.maxDrawdown < MIN_MAX_DRAWDOWN) return REASON_MAX_DRAWDOWN_HIGH
        return null
    }

    private fun optimizationScore(
        candidate: LeaderResearchCandidate,
        session: LeaderPaperSession?,
        sourceFresh: BigDecimal
    ): BigDecimal {
        val research = (candidate.score ?: BigDecimal.ZERO).coerce(BigDecimal.ZERO, BigDecimal("100"))
        val pnl = normalize(session?.copyablePnl ?: BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("10"))
        val trades = normalize(BigDecimal(session?.tradeCount ?: 0), BigDecimal.ZERO, BigDecimal(MIN_AUTO_READY_TRADES))
        val filtered = BigDecimal("100").subtract(
            (session?.filteredRatio ?: BigDecimal.ONE)
                .coerce(BigDecimal.ZERO, BigDecimal.ONE)
                .multiply(BigDecimal("100"))
        )
        val drawdown = BigDecimal("100").subtract(
            (session?.maxDrawdown ?: BigDecimal.ZERO)
                .abs()
                .coerce(BigDecimal.ZERO, BigDecimal("15"))
                .divide(BigDecimal("15"), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal("100"))
        )
        return research.multiply(BigDecimal("0.30"))
            .add(pnl.multiply(BigDecimal("0.20")))
            .add(trades.multiply(BigDecimal("0.15")))
            .add(filtered.multiply(BigDecimal("0.20")))
            .add(drawdown.multiply(BigDecimal("0.10")))
            .add(sourceFresh.multiply(BigDecimal("100")).multiply(BigDecimal("0.05")))
            .setScale(8, RoundingMode.HALF_UP)
    }

    private fun unknownRatio(session: LeaderPaperSession?): BigDecimal {
        if (session == null || session.unknownValuationExposure <= BigDecimal.ZERO) return BigDecimal.ZERO
        val denominator = listOf(
            session.openExposure.abs(),
            session.unknownValuationExposure.abs(),
            BigDecimal.ONE
        ).maxOrNull() ?: BigDecimal.ONE
        return session.unknownValuationExposure.abs().divide(denominator, 8, RoundingMode.HALF_UP)
    }

    private fun sourceFresh(lastSourceSeenAt: Long?, now: Long): BigDecimal {
        val last = lastSourceSeenAt ?: return BigDecimal.ZERO
        val ageMs = now - last
        return when {
            ageMs <= DAY_MS -> BigDecimal.ONE
            ageMs <= 3 * DAY_MS -> BigDecimal("0.70")
            ageMs <= 7 * DAY_MS -> BigDecimal("0.30")
            else -> BigDecimal.ZERO
        }
    }

    private fun researchStaleReason(now: Long): String? {
        val latest = researchRunRepository.findTopByStatusOrderByStartedAtDesc(LeaderResearchRunStatus.SUCCESS)
            ?: return STALE_NO_SUCCESSFUL_RUN
        val finishedAt = latest.finishedAt ?: latest.startedAt
        return if (now - finishedAt > DAY_MS) STALE_RESEARCH_RUN else null
    }

    private fun tierPriority(tier: String): Int = when (tier) {
        TIER_AUTO_READY -> 5
        TIER_HIGH_RISK_PILOT -> 4
        TIER_PAPER_WATCH -> 2
        else -> 1
    }

    private fun normalize(value: BigDecimal, min: BigDecimal, max: BigDecimal): BigDecimal {
        if (max <= min) return BigDecimal.ZERO
        return value.coerce(min, max)
            .subtract(min)
            .divide(max.subtract(min), 8, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
    }

    private fun BigDecimal.coerce(min: BigDecimal, max: BigDecimal): BigDecimal = when {
        this < min -> min
        this > max -> max
        else -> this
    }

    private fun ScoredPick.toDto(rank: Int) = LeaderPoolOptimizationItemDto(
        candidateId = candidateId,
        leaderId = leaderId,
        poolId = poolId,
        rank = rank,
        recommendationTier = tier,
        optimizationScore = optimizationScore.strip(),
        researchScore = researchScore.strip(),
        paperTradeCount = session?.tradeCount ?: 0,
        paperCopyablePnl = (session?.copyablePnl ?: BigDecimal.ZERO).strip(),
        paperFilteredRatio = (session?.filteredRatio ?: BigDecimal.ZERO).strip(),
        paperMaxDrawdown = (session?.maxDrawdown ?: BigDecimal.ZERO).strip(),
        paperUnknownRatio = unknownRatio.strip(),
        sourceFresh = sourceFresh.strip(),
        leaderName = leader?.leaderName,
        leaderAddress = leaderAddress,
        researchState = researchState.name,
        canRecommend = tier == TIER_AUTO_READY,
        reasonCode = reasonCode
    )

    private data class ScoredPick(
        val candidateId: Long,
        val leaderId: Long?,
        val poolId: Long?,
        val leader: Leader?,
        val leaderAddress: String,
        val researchState: LeaderResearchState,
        val tier: String,
        val optimizationScore: BigDecimal,
        val researchScore: BigDecimal,
        val session: LeaderPaperSession?,
        val unknownRatio: BigDecimal,
        val sourceFresh: BigDecimal,
        val reasonCode: String
    )

    private fun BigDecimal.strip(): String = stripTrailingZeros().toPlainString()

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50
        const val MAX_TOP3 = 3
        const val MIN_AUTO_READY_TRADES = 30
        const val DAY_MS = 24L * 60L * 60L * 1000L

        const val TIER_AUTO_READY = "AUTO_READY"
        const val TIER_HIGH_RISK_PILOT = "HIGH_RISK_PILOT"
        const val TIER_PAPER_WATCH = "PAPER_WATCH"
        const val TIER_BLOCKED = "BLOCKED"

        const val REASON_MISSING_LEADER = "MISSING_LEADER"
        const val REASON_LOCKED = "LOCKED"
        const val REASON_RETIRED = "RETIRED"
        const val REASON_COOLDOWN = "COOLDOWN"
        const val REASON_BLACKLISTED = "BLACKLISTED"
        const val REASON_NOT_TRIAL_READY = "NOT_TRIAL_READY"
        const val REASON_MISSING_PAPER_SESSION = "MISSING_PAPER_SESSION"
        const val REASON_UNKNOWN_RATIO_HIGH = "UNKNOWN_RATIO_HIGH"
        const val REASON_FILTERED_RATIO_HIGH = "FILTERED_RATIO_HIGH"
        const val REASON_INSUFFICIENT_TRADES = "INSUFFICIENT_TRADES"
        const val REASON_NON_POSITIVE_PNL = "NON_POSITIVE_PNL"
        const val REASON_MAX_DRAWDOWN_HIGH = "MAX_DRAWDOWN_HIGH"

        const val STALE_NO_SUCCESSFUL_RUN = "NO_SUCCESSFUL_RESEARCH_RUN"
        const val STALE_RESEARCH_RUN = "RESEARCH_RUN_STALE"

        private val MAX_UNKNOWN_RATIO = BigDecimal("0.20")
        private val MAX_AUTO_READY_FILTERED_RATIO = BigDecimal("0.50")
        private val HIGH_RISK_MAX_FILTERED_RATIO = BigDecimal("0.85")
        private val MIN_MAX_DRAWDOWN = BigDecimal("-15")
    }
}
