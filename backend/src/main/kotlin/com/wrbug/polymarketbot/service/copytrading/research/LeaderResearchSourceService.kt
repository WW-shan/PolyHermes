package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.enums.LeaderCandidateProvenance
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.enums.LeaderResearchSourceStatus
import com.wrbug.polymarketbot.enums.LeaderResearchSourceType
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import com.wrbug.polymarketbot.repository.LeaderPoolRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class LeaderResearchSourceRunResult(
    val sourceType: LeaderResearchSourceType,
    val candidates: List<LeaderResearchCandidate>,
    val status: LeaderResearchSourceStatus,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val limitation: String? = null,
    val expectedLimitation: Boolean = false
)

private data class SourceDiscovery(
    val candidates: List<LeaderResearchCandidate>,
    val status: LeaderResearchSourceStatus = LeaderResearchSourceStatus.SUCCESS,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val limitation: String? = null
)

private data class BackfillFailure(
    val wallet: String,
    val errorClass: String,
    val errorMessage: String?
)

private data class BackfillResult(
    val attemptedWallets: Int,
    val failures: List<BackfillFailure>
) {
    val hasFailures: Boolean = failures.isNotEmpty()

    fun status(): LeaderResearchSourceStatus =
        if (hasFailures) LeaderResearchSourceStatus.DEGRADED else LeaderResearchSourceStatus.SUCCESS

    fun errorClass(): String? = failures.firstOrNull()?.errorClass

    fun errorMessage(): String? {
        if (failures.isEmpty()) return null
        val sampled = failures.take(3).joinToString("; ") { "${it.wallet}: ${it.errorMessage ?: it.errorClass}" }
        val suffix = if (failures.size > 3) "; +${failures.size - 3} more" else ""
        return "Data API backfill failed for ${failures.size}/$attemptedWallets wallets: $sampled$suffix"
    }
}

@Service
class LeaderResearchSourceService(
    private val candidateRepository: LeaderResearchCandidateRepository,
    private val leaderRepository: LeaderRepository,
    private val leaderPoolRepository: LeaderPoolRepository,
    private val activityEventRepository: LeaderActivityEventRepository,
    private val sourceHealthService: LeaderResearchSourceHealthService,
    private val systemConfigRepository: SystemConfigRepository,
    private val retrofitFactory: RetrofitFactory,
    private val eventService: LeaderResearchEventService,
    private val ingestionService: LeaderActivityIngestionService,
    @Value("\${leader.research.data-api-backfill.limit:200}") private val backfillLimit: Int,
    @Value("\${leader.research.global-capture.enabled:false}") private val globalCaptureEnabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(LeaderResearchSourceService::class.java)

    @Transactional
    fun discoverCandidates(runId: Long?): List<LeaderResearchSourceRunResult> {
        val results = mutableListOf<LeaderResearchSourceRunResult>()
        results += captureSource(LeaderResearchSourceType.WATCHLIST, runId) { discoverWatchlist(runId) }
        results += captureSource(LeaderResearchSourceType.EXISTING_LEADER, runId) { discoverExistingLeaders(runId) }
        val activityResult = captureSource(LeaderResearchSourceType.ACTIVITY_DERIVED, runId) { discoverFromPersistedActivity(runId) }
        results += if (globalCaptureEnabled) activityResult else markActivityDerivedDegraded(activityResult)
        if (!globalCaptureEnabled) {
            results += markGlobalActivityCaptureDisabled(runId)
        }
        results += markPublicLeaderboardDisabled(runId)
        return results
    }

    fun previewCandidates(): List<LeaderResearchSourceRunResult> {
        val freshAfter = System.currentTimeMillis() - FRESH_ACTIVITY_WINDOW_MS
        val watchlist = watchlistWallets().map { transientCandidate(it, LeaderResearchSourceType.WATCHLIST) }
        val existing = leaderRepository.findAllByOrderByCreatedAtAsc().map {
            transientCandidate(it.leaderAddress, LeaderResearchSourceType.EXISTING_LEADER, it)
        }
        val activity = activityEventRepository.findByUsableForDiscoveryTrueAndEventTimeGreaterThanEqual(freshAfter)
            .mapNotNull { it.normalizedWallet }
            .distinct()
            .mapIndexed { index, wallet -> transientCandidate(wallet, LeaderResearchSourceType.ACTIVITY_DERIVED, sourceRank = index + 1) }
        val results = mutableListOf(
            LeaderResearchSourceRunResult(LeaderResearchSourceType.WATCHLIST, watchlist, LeaderResearchSourceStatus.SUCCESS),
            LeaderResearchSourceRunResult(LeaderResearchSourceType.EXISTING_LEADER, existing, LeaderResearchSourceStatus.SUCCESS),
            LeaderResearchSourceRunResult(
                LeaderResearchSourceType.ACTIVITY_DERIVED,
                activity,
                if (globalCaptureEnabled) LeaderResearchSourceStatus.SUCCESS else LeaderResearchSourceStatus.DEGRADED,
                limitation = if (globalCaptureEnabled) null else GLOBAL_CAPTURE_DISABLED_LIMITATION,
                expectedLimitation = !globalCaptureEnabled
            )
        )
        if (!globalCaptureEnabled) {
            results += LeaderResearchSourceRunResult(
                LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE,
                emptyList(),
                LeaderResearchSourceStatus.DISABLED,
                limitation = GLOBAL_CAPTURE_DISABLED_LIMITATION,
                expectedLimitation = true
            )
        }
        results += LeaderResearchSourceRunResult(
            LeaderResearchSourceType.PUBLIC_LEADERBOARD,
            emptyList(),
            LeaderResearchSourceStatus.DISABLED,
            limitation = PUBLIC_LEADERBOARD_DISABLED_LIMITATION,
            expectedLimitation = true
        )
        return results
    }

    fun watchlistWallets(): List<String> {
        val raw = systemConfigRepository.findByConfigKey(CONFIG_WATCHLIST)?.configValue ?: return emptyList()
        return raw.split(",", "\n", ";", " ", "\t")
            .mapNotNull { ingestionService.normalizeWallet(it) }
            .distinct()
    }

    private fun captureSource(
        sourceType: LeaderResearchSourceType,
        runId: Long?,
        block: () -> SourceDiscovery
    ): LeaderResearchSourceRunResult {
        val now = System.currentTimeMillis()
        return try {
            val discovery = block()
            saveSourceState(
                sourceType = sourceType,
                status = discovery.status,
                now = now,
                candidateCount = discovery.candidates.size,
                errorClass = discovery.errorClass,
                errorMessage = discovery.errorMessage
            )
            eventService.record(
                type = if (discovery.status == LeaderResearchSourceStatus.SUCCESS) {
                    LeaderResearchEventType.SOURCE_SUCCESS
                } else {
                    LeaderResearchEventType.SOURCE_FAILURE
                },
                runId = runId,
                reason = if (discovery.status == LeaderResearchSourceStatus.SUCCESS) {
                    "${sourceType.name} discovered ${discovery.candidates.size} candidates"
                } else {
                    "${sourceType.name} degraded: ${discovery.errorMessage ?: discovery.limitation ?: discovery.status.name}"
                },
                dedupeKey = "source:${sourceType.name}:$runId:${discovery.status.name.lowercase()}"
            )
            LeaderResearchSourceRunResult(
                sourceType = sourceType,
                candidates = discovery.candidates,
                status = discovery.status,
                errorClass = discovery.errorClass,
                errorMessage = discovery.errorMessage,
                limitation = discovery.limitation
            )
        } catch (e: Exception) {
            logger.warn("Leader research source failed: source={}, error={}", sourceType, e.message, e)
            saveSourceState(
                sourceType = sourceType,
                status = LeaderResearchSourceStatus.FAILURE,
                now = now,
                candidateCount = 0,
                errorClass = e::class.java.simpleName,
                errorMessage = e.message
            )
            eventService.record(
                type = LeaderResearchEventType.SOURCE_FAILURE,
                runId = runId,
                reason = "${sourceType.name} failed: ${e.message}",
                dedupeKey = "source:${sourceType.name}:$runId:failure"
            )
            LeaderResearchSourceRunResult(sourceType, emptyList(), LeaderResearchSourceStatus.FAILURE, e::class.java.simpleName, e.message)
        }
    }

    private fun discoverWatchlist(runId: Long?): SourceDiscovery {
        val wallets = watchlistWallets()
        val backfill = backfillWalletActivities(wallets, LeaderResearchSourceType.WATCHLIST, runId)
        val candidates = wallets.map { wallet ->
            upsertCandidate(
                wallet = wallet,
                sourceType = LeaderResearchSourceType.WATCHLIST,
                leader = leaderRepository.findByLeaderAddress(wallet),
                sourceRank = null,
                provenance = LeaderCandidateProvenance.AGENT_CREATED,
                sourceEvidence = "system_config:$CONFIG_WATCHLIST",
                runId = runId
            )
        }
        return SourceDiscovery(
            candidates = candidates,
            status = backfill.status(),
            errorClass = backfill.errorClass(),
            errorMessage = backfill.errorMessage()
        )
    }

    private fun discoverExistingLeaders(runId: Long?): SourceDiscovery {
        val leaders = leaderRepository.findAllByOrderByCreatedAtAsc()
        val backfill = backfillWalletActivities(leaders.map { it.leaderAddress }, LeaderResearchSourceType.EXISTING_LEADER, runId)
        val candidates = leaders.map { leader ->
            val pool = leader.id?.let { leaderPoolRepository.findByLeaderId(it) }
            upsertCandidate(
                wallet = leader.leaderAddress,
                sourceType = LeaderResearchSourceType.EXISTING_LEADER,
                leader = leader,
                poolId = pool?.id,
                sourceRank = null,
                provenance = if (pool == null) LeaderCandidateProvenance.USER_LEADER else LeaderCandidateProvenance.USER_POOL,
                sourceEvidence = "existing_leader:${leader.id}",
                runId = runId
            )
        }
        return SourceDiscovery(
            candidates = candidates,
            status = backfill.status(),
            errorClass = backfill.errorClass(),
            errorMessage = backfill.errorMessage()
        )
    }

    private fun discoverFromPersistedActivity(runId: Long?): SourceDiscovery {
        val backfill = backfillWalletActivities(activeResearchWallets(), LeaderResearchSourceType.ACTIVITY_DERIVED, runId)
        val freshAfter = System.currentTimeMillis() - FRESH_ACTIVITY_WINDOW_MS
        val events = activityEventRepository.findByUsableForDiscoveryTrueAndEventTimeGreaterThanEqual(freshAfter)
        // 按新鲜活动数量排序并限制数量，避免全局捕获的大量钱包无上限地创建候选
        val countsByWallet = events.mapNotNull { it.normalizedWallet }.groupingBy { it }.eachCount()
        val wallets = countsByWallet.entries
            .sortedByDescending { it.value }
            .take(MAX_ACTIVITY_DERIVED_CANDIDATES_PER_RUN)
            .map { it.key }
        val candidates = wallets.mapIndexed { index, wallet ->
            upsertCandidate(
                wallet = wallet,
                sourceType = LeaderResearchSourceType.ACTIVITY_DERIVED,
                leader = leaderRepository.findByLeaderAddress(wallet),
                sourceRank = index + 1,
                provenance = LeaderCandidateProvenance.AGENT_CREATED,
                sourceEvidence = "leader_activity_event:fresh_count=${countsByWallet[wallet] ?: 0}",
                runId = runId
            )
        }
        return SourceDiscovery(
            candidates = candidates,
            status = backfill.status(),
            errorClass = backfill.errorClass(),
            errorMessage = backfill.errorMessage()
        )
    }

    private fun activeResearchWallets(): List<String> {
        return candidateRepository.findByResearchStateIn(
            listOf(LeaderResearchState.DISCOVERED, LeaderResearchState.CANDIDATE, LeaderResearchState.PAPER, LeaderResearchState.TRIAL_READY)
        ).map { it.normalizedWallet }.distinct()
    }

    private fun backfillWalletActivities(wallets: List<String>, sourceType: LeaderResearchSourceType, runId: Long?): BackfillResult {
        val normalizedWallets = wallets.mapNotNull { ingestionService.normalizeWallet(it) }.distinct()
        if (normalizedWallets.isEmpty()) return BackfillResult(0, emptyList())
        val dataApi = retrofitFactory.createDataApi()
        val startSeconds = (System.currentTimeMillis() - FRESH_ACTIVITY_WINDOW_MS) / 1000
        val endSeconds = System.currentTimeMillis() / 1000
        val sampledWallets = normalizedWallets.take(MAX_BACKFILL_WALLETS_PER_RUN)
        val failures = mutableListOf<BackfillFailure>()
        sampledWallets.forEach { wallet ->
            try {
                val response = runBlocking {
                    dataApi.getUserActivity(
                        user = wallet,
                        type = listOf("TRADE"),
                        start = startSeconds,
                        end = endSeconds,
                        limit = backfillLimit.coerceIn(1, 500),
                        offset = null,
                        sortBy = "TIMESTAMP",
                        sortDirection = "ASC"
                    )
                }
                if (!response.isSuccessful || response.body() == null) {
                    throw IllegalStateException("Data API backfill failed: ${response.code()} ${response.message()}")
                }
                response.body().orEmpty().forEach { activity ->
                    ingestionService.ingestUserActivity(activity, sourceType)
                }
            } catch (e: Exception) {
                failures += BackfillFailure(wallet, e::class.java.simpleName, e.message)
                eventService.record(
                    type = LeaderResearchEventType.SOURCE_FAILURE,
                    runId = runId,
                    reason = "Data API backfill failed for $wallet: ${e.message}",
                    payloadSummary = sourceType.name,
                    dedupeKey = "data-api-backfill:${sourceType.name}:$wallet:${System.currentTimeMillis() / 3600000}"
                )
                logger.warn("Research Data API backfill failed: source={}, wallet={}, error={}", sourceType, wallet, e.message)
            }
        }
        return BackfillResult(sampledWallets.size, failures)
    }

    private fun upsertCandidate(
        wallet: String,
        sourceType: LeaderResearchSourceType,
        leader: Leader?,
        poolId: Long? = null,
        sourceRank: Int?,
        provenance: LeaderCandidateProvenance,
        sourceEvidence: String,
        runId: Long?
    ): LeaderResearchCandidate {
        val normalized = ingestionService.normalizeWallet(wallet)
            ?: throw IllegalArgumentException("Invalid wallet for research candidate: $wallet")
        val now = System.currentTimeMillis()
        val existing = candidateRepository.findByNormalizedWallet(normalized)
        val saved = if (existing == null) {
            candidateRepository.save(
                LeaderResearchCandidate(
                    normalizedWallet = normalized,
                    leaderId = leader?.id,
                    poolId = poolId,
                    researchState = LeaderResearchState.DISCOVERED,
                    source = sourceType.name,
                    sourceRank = sourceRank,
                    agentOwned = provenance == LeaderCandidateProvenance.AGENT_CREATED,
                    provenance = provenance,
                    sourceEvidence = sourceEvidence,
                    firstSeenAt = now,
                    lastSourceSeenAt = now,
                    lastTransitionAt = now,
                    createdAt = now,
                    updatedAt = now
                )
            )
        } else {
            val shouldPreserveHuman = existing.locked || existing.provenance == LeaderCandidateProvenance.MANUAL_LOCKED
            candidateRepository.save(
                existing.copy(
                    leaderId = existing.leaderId ?: leader?.id,
                    poolId = existing.poolId ?: poolId,
                    source = if (shouldPreserveHuman) existing.source else mergeSource(existing.source, sourceType.name),
                    sourceRank = existing.sourceRank ?: sourceRank,
                    provenance = if (shouldPreserveHuman) existing.provenance else strongestProvenance(existing.provenance, provenance),
                    sourceEvidence = appendEvidence(existing.sourceEvidence, sourceEvidence),
                    lastSourceSeenAt = now,
                    updatedAt = now
                )
            )
        }
        eventService.record(
            type = if (existing == null) LeaderResearchEventType.CANDIDATE_DISCOVERED else LeaderResearchEventType.CANDIDATE_UPDATED,
            candidateId = saved.id,
            runId = runId,
            reason = "Candidate seen from ${sourceType.name}",
            payloadSummary = sourceEvidence,
            dedupeKey = "candidate:${saved.normalizedWallet}:${sourceType.name}:$runId"
        )
        return saved
    }

    private fun saveSourceState(
        sourceType: LeaderResearchSourceType,
        status: LeaderResearchSourceStatus,
        now: Long,
        candidateCount: Int,
        errorClass: String? = null,
        errorMessage: String? = null,
        disabledReason: String? = null,
        stale: Boolean = false
    ) {
        sourceHealthService.record(
            sourceType = sourceType,
            status = status,
            now = now,
            candidateCount = candidateCount,
            errorClass = errorClass,
            errorMessage = errorMessage,
            disabledReason = disabledReason,
            stale = stale
        )
    }

    private fun markActivityDerivedDegraded(result: LeaderResearchSourceRunResult): LeaderResearchSourceRunResult {
        val expectedLimitation = result.status == LeaderResearchSourceStatus.SUCCESS &&
            result.errorClass == null &&
            result.errorMessage == null
        saveSourceState(
            sourceType = LeaderResearchSourceType.ACTIVITY_DERIVED,
            status = LeaderResearchSourceStatus.DEGRADED,
            now = System.currentTimeMillis(),
            candidateCount = result.candidates.size,
            errorClass = result.errorClass,
            errorMessage = result.errorMessage,
            disabledReason = GLOBAL_CAPTURE_DISABLED_LIMITATION,
            stale = false
        )
        return result.copy(
            status = LeaderResearchSourceStatus.DEGRADED,
            limitation = GLOBAL_CAPTURE_DISABLED_LIMITATION,
            expectedLimitation = expectedLimitation
        )
    }

    private fun markPublicLeaderboardDisabled(runId: Long?): LeaderResearchSourceRunResult {
        saveSourceState(
            sourceType = LeaderResearchSourceType.PUBLIC_LEADERBOARD,
            status = LeaderResearchSourceStatus.DISABLED,
            now = System.currentTimeMillis(),
            candidateCount = 0,
            disabledReason = PUBLIC_LEADERBOARD_DISABLED_LIMITATION,
            stale = false
        )
        eventService.record(
            type = LeaderResearchEventType.SOURCE_DISABLED,
            runId = runId,
            reason = PUBLIC_LEADERBOARD_DISABLED_LIMITATION,
            dedupeKey = "source:${LeaderResearchSourceType.PUBLIC_LEADERBOARD.name}:disabled"
        )
        return LeaderResearchSourceRunResult(
            sourceType = LeaderResearchSourceType.PUBLIC_LEADERBOARD,
            candidates = emptyList(),
            status = LeaderResearchSourceStatus.DISABLED,
            limitation = PUBLIC_LEADERBOARD_DISABLED_LIMITATION,
            expectedLimitation = true
        )
    }

    private fun markGlobalActivityCaptureDisabled(runId: Long?): LeaderResearchSourceRunResult {
        saveSourceState(
            sourceType = LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE,
            status = LeaderResearchSourceStatus.DISABLED,
            now = System.currentTimeMillis(),
            candidateCount = 0,
            disabledReason = GLOBAL_CAPTURE_DISABLED_LIMITATION,
            stale = false
        )
        eventService.record(
            type = LeaderResearchEventType.SOURCE_DISABLED,
            runId = runId,
            reason = GLOBAL_CAPTURE_DISABLED_LIMITATION,
            dedupeKey = "source:${LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE.name}:disabled"
        )
        return LeaderResearchSourceRunResult(
            sourceType = LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE,
            candidates = emptyList(),
            status = LeaderResearchSourceStatus.DISABLED,
            limitation = GLOBAL_CAPTURE_DISABLED_LIMITATION,
            expectedLimitation = true
        )
    }

    private fun transientCandidate(
        wallet: String,
        sourceType: LeaderResearchSourceType,
        leader: Leader? = leaderRepository.findByLeaderAddress(wallet),
        sourceRank: Int? = null
    ): LeaderResearchCandidate {
        val normalized = ingestionService.normalizeWallet(wallet)
            ?: throw IllegalArgumentException("Invalid wallet for research preview candidate: $wallet")
        return LeaderResearchCandidate(
            normalizedWallet = normalized,
            leaderId = leader?.id,
            source = sourceType.name,
            sourceRank = sourceRank,
            provenance = if (leader == null) LeaderCandidateProvenance.AGENT_CREATED else LeaderCandidateProvenance.USER_LEADER,
            sourceEvidence = "preview:${sourceType.name}",
            firstSeenAt = System.currentTimeMillis(),
            lastSourceSeenAt = System.currentTimeMillis()
        )
    }

    private fun mergeSource(existing: String, incoming: String): String {
        val sources = (existing.split(",") + incoming).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        return sources.joinToString(",")
    }

    private fun strongestProvenance(current: LeaderCandidateProvenance, incoming: LeaderCandidateProvenance): LeaderCandidateProvenance {
        val rank = mapOf(
            LeaderCandidateProvenance.MANUAL_LOCKED to 4,
            LeaderCandidateProvenance.USER_POOL to 3,
            LeaderCandidateProvenance.USER_LEADER to 2,
            LeaderCandidateProvenance.AGENT_CREATED to 1
        )
        return if ((rank[incoming] ?: 0) > (rank[current] ?: 0)) incoming else current
    }

    private fun appendEvidence(existing: String?, incoming: String): String {
        val lines = (existing?.lines().orEmpty() + incoming).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        return lines.takeLast(10).joinToString("\n")
    }

    companion object {
        const val CONFIG_WATCHLIST = "leader_research.watchlist"
        const val FRESH_ACTIVITY_WINDOW_MS = 48L * 60 * 60 * 1000
        const val MAX_BACKFILL_WALLETS_PER_RUN = 50

        /** 每轮由活动派生发现的候选数量上限 */
        const val MAX_ACTIVITY_DERIVED_CANDIDATES_PER_RUN = 50
        private const val GLOBAL_CAPTURE_DISABLED_LIMITATION =
            "Global activity capture is disabled; activity-derived discovery only uses already persisted research events."
        private const val PUBLIC_LEADERBOARD_DISABLED_LIMITATION =
            "Public leaderboard source is intentionally disabled in v1; discovery uses watchlist, existing leaders, and persisted activity only."
    }
}
