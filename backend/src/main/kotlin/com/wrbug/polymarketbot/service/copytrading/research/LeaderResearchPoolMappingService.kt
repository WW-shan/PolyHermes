package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.entity.LeaderPool
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.enums.LeaderPoolStatus
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingValidation
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class LeaderResearchPoolMappingService(
    private val leaderRepository: LeaderRepository,
    private val leaderPoolRepository: LeaderPoolRepository,
    private val candidateRepository: LeaderResearchCandidateRepository,
    private val accountRepository: AccountRepository
) {
    @Transactional
    fun syncCandidate(candidate: LeaderResearchCandidate): LeaderResearchCandidate {
        require(candidate.researchState != LeaderResearchState.DISCOVERED) {
            "DISCOVERED research candidates must not be synced to Leader Pool"
        }
        val now = System.currentTimeMillis()
        // 用户已把该研究候选移出 Leader 池（或手动退役）：尊重人工决策，候选退役而不是自动重建
        if (isRemovedByUser(candidate)) {
            return retireCandidate(candidate, now, "Removed from Leader Pool by user; automatic re-creation disabled")
        }
        // 自己的账户地址（代理钱包或 EOA）不能成为 Leader：直接退役，不抛异常中断整轮研究
        if (CopyTradingValidation.isOwnAccountAddress(candidate.normalizedWallet, accountRepository.findAll())) {
            return retireCandidate(candidate, now, "Candidate wallet is one of your own accounts; self copy trading is forbidden")
        }
        val existingPool = findPool(candidate)
        if (existingPool?.locked == true) return candidate
        val leader = ensureLeader(candidate)
        val pool = existingPool ?: ensurePool(candidate, leader)
        val badge = when (candidate.researchState) {
            LeaderResearchState.TRIAL_READY -> "RESEARCH_TRIAL_READY"
            LeaderResearchState.PAPER -> "RESEARCH_PAPER"
            LeaderResearchState.COOLDOWN -> "RESEARCH_COOLDOWN"
            else -> null
        }
        val savedPool = leaderPoolRepository.save(
            pool.copy(
                researchCandidateId = candidate.id,
                researchState = candidate.researchState,
                researchBadge = badge,
                researchSummary = candidate.reason?.take(1000),
                researchScore = candidate.score,
                researchUpdatedAt = now,
                updatedAt = now
            )
        )
        return candidateRepository.save(
            candidate.copy(
                leaderId = leader.id,
                poolId = savedPool.id,
                updatedAt = now
            )
        )
    }

    /**
     * 候选对应的 Leader 池条目是否被人工锁定（锁定时自动化不推进、不同步）
     */
    fun isPoolLocked(candidate: LeaderResearchCandidate): Boolean {
        return findPool(candidate)?.locked == true
    }

    private fun findPool(candidate: LeaderResearchCandidate): LeaderPool? {
        candidate.poolId?.let { id -> leaderPoolRepository.findById(id).orElse(null)?.let { return it } }
        val leaderId = candidate.leaderId
            ?: leaderRepository.findByLeaderAddress(candidate.normalizedWallet)?.id
            ?: return null
        return leaderPoolRepository.findByLeaderId(leaderId)
    }

    /** 曾同步过池子（有 poolId）但池条目已不存在，或池条目被人工置为 RETIRED */
    private fun isRemovedByUser(candidate: LeaderResearchCandidate): Boolean {
        val poolId = candidate.poolId ?: return false
        val pool = leaderPoolRepository.findById(poolId).orElse(null) ?: return true
        return pool.status == LeaderPoolStatus.RETIRED
    }

    private fun retireCandidate(candidate: LeaderResearchCandidate, now: Long, reason: String): LeaderResearchCandidate {
        if (candidate.researchState == LeaderResearchState.RETIRED) return candidate
        return candidateRepository.save(
            candidate.copy(
                researchState = LeaderResearchState.RETIRED,
                retiredAt = now,
                lastTransitionAt = now,
                reason = reason,
                updatedAt = now
            )
        )
    }

    private fun ensureLeader(candidate: LeaderResearchCandidate): Leader {
        candidate.leaderId?.let { id ->
            leaderRepository.findById(id).orElse(null)?.let { return it }
        }
        leaderRepository.findByLeaderAddress(candidate.normalizedWallet)?.let { return it }
        val now = System.currentTimeMillis()
        return leaderRepository.save(
            Leader(
                leaderAddress = candidate.normalizedWallet,
                leaderName = "Research ${candidate.normalizedWallet.take(6)}...${candidate.normalizedWallet.takeLast(4)}",
                remark = "Created by Leader Research Agent. Manual enable is required before real-money copy trading.",
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private fun ensurePool(candidate: LeaderResearchCandidate, leader: Leader): LeaderPool {
        leader.id?.let { leaderPoolRepository.findByLeaderId(it) }?.let { return it }
        val now = System.currentTimeMillis()
        return leaderPoolRepository.save(
            LeaderPool(
                leaderId = leader.id ?: 0,
                status = LeaderPoolStatus.WATCH,
                source = "RESEARCH_AGENT",
                score = candidate.score,
                reason = candidate.reason,
                notes = "Research agent candidate. Pool row is informational until you approve a disabled trial config.",
                suggestedFixedAmount = BigDecimal("1.00000000"),
                suggestedMaxDailyOrders = 10,
                suggestedMaxDailyLoss = BigDecimal("5.00000000"),
                suggestedMinPrice = BigDecimal("0.10000000"),
                suggestedMaxPrice = BigDecimal("0.80000000"),
                suggestedMaxPositionValue = BigDecimal("5.00000000"),
                researchCandidateId = candidate.id,
                researchState = candidate.researchState,
                researchScore = candidate.score,
                researchSummary = candidate.reason,
                researchUpdatedAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
    }
}
