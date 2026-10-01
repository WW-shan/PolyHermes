package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderPaperSession
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.enums.LeaderPaperSessionStatus
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.LeaderPaperSessionRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class LeaderResearchStateMachine(
    private val candidateRepository: LeaderResearchCandidateRepository,
    private val paperSessionRepository: LeaderPaperSessionRepository,
    private val paperTradingService: LeaderPaperTradingService,
    private val poolMappingService: LeaderResearchPoolMappingService,
    private val eventService: LeaderResearchEventService
) {
    @Transactional
    fun advanceAll(runId: Long?): List<LeaderResearchCandidate> {
        return candidateRepository.findByResearchStateIn(
            listOf(
                LeaderResearchState.DISCOVERED,
                LeaderResearchState.CANDIDATE,
                LeaderResearchState.PAPER,
                LeaderResearchState.TRIAL_READY,
                LeaderResearchState.COOLDOWN
            )
        ).map { advance(it, runId) }
    }

    @Transactional
    fun advance(candidate: LeaderResearchCandidate, runId: Long?): LeaderResearchCandidate {
        if (candidate.locked) return candidate
        // 人工锁定的 Leader 池条目：自动化不推进也不同步
        if (poolMappingService.isPoolLocked(candidate)) return candidate
        val now = System.currentTimeMillis()
        // 只看活跃会话：冷却时旧会话已结束，恢复后新建会话，不能用旧会话指标判断
        val latestSession = candidate.id?.let {
            paperSessionRepository.findTopByCandidateIdAndStatusOrderByStartedAtDesc(it, LeaderPaperSessionStatus.ACTIVE)
        }
        val sourceFresh48h = candidate.lastSourceSeenAt?.let { now - it <= SOURCE_FRESH_48H_MS } == true
        val sourceFresh72h = candidate.lastSourceSeenAt?.let { now - it <= SOURCE_STALE_72H_MS } == true
        val score = candidate.score ?: BigDecimal.ZERO

        val nextState = when (candidate.researchState) {
            LeaderResearchState.DISCOVERED -> {
                if (sourceFresh48h && (score >= BigDecimal("60") || canBootstrapPaperObservation(candidate))) {
                    LeaderResearchState.CANDIDATE
                } else {
                    candidate.researchState
                }
            }
            LeaderResearchState.CANDIDATE -> {
                if (sourceFresh48h && (score >= BigDecimal("60") || latestSession == null && canBootstrapPaperObservation(candidate))) {
                    LeaderResearchState.PAPER
                } else {
                    candidate.researchState
                }
            }
            LeaderResearchState.PAPER -> {
                cooldownReason(latestSession, sourceFresh72h)?.let {
                    return transition(candidate, LeaderResearchState.COOLDOWN, runId, it)
                }
                if (latestSession != null && paperTradingService.isEligibleForTrialReady(latestSession, now)) {
                    LeaderResearchState.TRIAL_READY
                } else {
                    candidate.researchState
                }
            }
            LeaderResearchState.TRIAL_READY -> {
                cooldownReason(latestSession, sourceFresh72h)?.let {
                    return transition(candidate, LeaderResearchState.COOLDOWN, runId, it)
                }
                candidate.researchState
            }
            LeaderResearchState.COOLDOWN -> {
                val cooldownElapsed = candidate.cooldownUntil?.let { now >= it } ?: true
                when {
                    candidate.cooldownCount >= 3 || candidate.lastSourceSeenAt?.let { now - it > SOURCE_RETIRE_30D_MS } == true -> LeaderResearchState.RETIRED
                    cooldownElapsed && sourceFresh48h -> LeaderResearchState.CANDIDATE
                    else -> candidate.researchState
                }
            }
            LeaderResearchState.RETIRED -> candidate.researchState
        }

        val saved = if (nextState != candidate.researchState) {
            transition(candidate, nextState, runId, "state criteria satisfied")
        } else {
            candidate
        }
        val withSession = if (saved.researchState == LeaderResearchState.PAPER && latestSession == null) {
            val session = paperTradingService.ensureSession(saved, runId)
            candidateRepository.save(saved.copy(lastPaperSessionId = session.id, updatedAt = now))
        } else {
            saved
        }
        return if (withSession.researchState.canSyncToLeaderPool()) {
            poolMappingService.syncCandidate(withSession)
        } else {
            withSession
        }
    }

    private fun LeaderResearchState.canSyncToLeaderPool(): Boolean {
        return this != LeaderResearchState.DISCOVERED
    }

    private fun cooldownReason(session: LeaderPaperSession?, sourceFresh72h: Boolean): String? {
        if (session == null) return null
        return paperTradingService.shouldEnterCooldown(session, sourceFresh72h)
    }

    private fun canBootstrapPaperObservation(candidate: LeaderResearchCandidate): Boolean {
        return candidate.agentOwned || candidate.leaderId != null || candidate.poolId != null
    }

    private fun transition(
        candidate: LeaderResearchCandidate,
        nextState: LeaderResearchState,
        runId: Long?,
        reason: String
    ): LeaderResearchCandidate {
        val now = System.currentTimeMillis()
        // 进入冷却/退役时结束当前纸跟会话
        if ((nextState == LeaderResearchState.COOLDOWN || nextState == LeaderResearchState.RETIRED) && candidate.id != null) {
            paperTradingService.endActiveSession(candidate.id, now)
        }
        val updated = candidate.copy(
            researchState = nextState,
            cooldownUntil = if (nextState == LeaderResearchState.COOLDOWN) now + COOLDOWN_MS else candidate.cooldownUntil,
            cooldownCount = if (nextState == LeaderResearchState.COOLDOWN) candidate.cooldownCount + 1 else candidate.cooldownCount,
            trialReadyAt = if (nextState == LeaderResearchState.TRIAL_READY) now else candidate.trialReadyAt,
            retiredAt = if (nextState == LeaderResearchState.RETIRED) now else candidate.retiredAt,
            lastTransitionAt = now,
            updatedAt = now
        )
        val saved = candidateRepository.save(updated)
        eventService.record(
            type = when (nextState) {
                LeaderResearchState.TRIAL_READY -> LeaderResearchEventType.TRIAL_READY
                LeaderResearchState.COOLDOWN -> LeaderResearchEventType.COOLDOWN
                LeaderResearchState.RETIRED -> LeaderResearchEventType.RETIRED
                else -> LeaderResearchEventType.STATE_TRANSITION
            },
            candidateId = saved.id,
            runId = runId,
            reason = "${candidate.researchState.name} -> ${nextState.name}: $reason",
            dedupeKey = "state:${candidate.id}:${nextState.name}:${now / 60000}"
        )
        return saved
    }

    companion object {
        private const val SOURCE_FRESH_48H_MS = 48L * 60 * 60 * 1000
        private const val SOURCE_STALE_72H_MS = 72L * 60 * 60 * 1000
        private const val SOURCE_RETIRE_30D_MS = 30L * 24 * 60 * 60 * 1000
        private const val COOLDOWN_MS = 3L * 24 * 60 * 60 * 1000
    }
}
