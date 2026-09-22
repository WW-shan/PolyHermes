package com.wrbug.polymarketbot.dto

data class LeaderPoolOptimizationRequest(
    val limit: Int = 10
)

data class LeaderPoolOptimizationItemDto(
    val candidateId: Long,
    val leaderId: Long?,
    val poolId: Long?,
    val rank: Int,
    val recommendationTier: String,
    val optimizationScore: String,
    val researchScore: String?,
    val paperTradeCount: Int,
    val paperCopyablePnl: String,
    val paperFilteredRatio: String,
    val paperMaxDrawdown: String,
    val paperUnknownRatio: String,
    val sourceFresh: String,
    val leaderName: String?,
    val leaderAddress: String,
    val researchState: String,
    val canRecommend: Boolean,
    val reasonCode: String
)

data class LeaderPoolOptimizationResponse(
    val generatedAt: Long,
    val staleReason: String?,
    val candidateCount: Int,
    val eligibleCount: Int,
    val items: List<LeaderPoolOptimizationItemDto>,
    val top3: List<LeaderPoolOptimizationItemDto>,
    val safeMode: Boolean = true
)
