package com.wrbug.polymarketbot.service.copytrading.research

import com.wrbug.polymarketbot.entity.LeaderActivityEvent
import com.wrbug.polymarketbot.entity.LeaderPaperPosition
import com.wrbug.polymarketbot.entity.LeaderPaperSession
import com.wrbug.polymarketbot.entity.LeaderPaperTrade
import com.wrbug.polymarketbot.entity.LeaderResearchCandidate
import com.wrbug.polymarketbot.enums.LeaderPaperFillAssumption
import com.wrbug.polymarketbot.enums.LeaderPaperFilterResult
import com.wrbug.polymarketbot.enums.LeaderPaperProcessingStatus
import com.wrbug.polymarketbot.enums.LeaderPaperSessionStatus
import com.wrbug.polymarketbot.enums.LeaderResearchEventType
import com.wrbug.polymarketbot.enums.LeaderResearchQuoteConfidence
import com.wrbug.polymarketbot.enums.LeaderResearchState
import com.wrbug.polymarketbot.enums.LeaderResearchValuationStatus
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import com.wrbug.polymarketbot.repository.LeaderPaperPositionRepository
import com.wrbug.polymarketbot.repository.LeaderPaperSessionRepository
import com.wrbug.polymarketbot.repository.LeaderPaperTradeRepository
import com.wrbug.polymarketbot.repository.LeaderResearchCandidateRepository
import com.wrbug.polymarketbot.service.common.MarketPriceService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

data class LeaderPaperProcessingResult(
    val processed: Int,
    val filtered: Int,
    val failed: Int
)

@Service
class LeaderPaperTradingService(
    private val candidateRepository: LeaderResearchCandidateRepository,
    private val activityEventRepository: LeaderActivityEventRepository,
    private val paperSessionRepository: LeaderPaperSessionRepository,
    private val paperTradeRepository: LeaderPaperTradeRepository,
    private val paperPositionRepository: LeaderPaperPositionRepository,
    private val marketPriceService: MarketPriceService,
    private val eventService: LeaderResearchEventService
) {
    private val logger = LoggerFactory.getLogger(LeaderPaperTradingService::class.java)

    @Transactional
    fun ensureSession(candidate: LeaderResearchCandidate, runId: Long? = null): LeaderPaperSession {
        val candidateId = candidate.id ?: throw IllegalArgumentException("candidate id missing")
        paperSessionRepository.findTopByCandidateIdAndStatusOrderByStartedAtDesc(candidateId, LeaderPaperSessionStatus.ACTIVE)
            ?.let { return it }
        val now = System.currentTimeMillis()
        val session = paperSessionRepository.save(
            LeaderPaperSession(
                candidateId = candidateId,
                status = LeaderPaperSessionStatus.ACTIVE,
                startedAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
        candidateRepository.save(
            candidate.copy(
                lastPaperSessionId = session.id,
                updatedAt = now
            )
        )
        eventService.record(
            type = LeaderResearchEventType.PAPER_STARTED,
            candidateId = candidateId,
            runId = runId,
            reason = "Paper session started",
            dedupeKey = "paper-started:$candidateId:${session.id}"
        )
        return session
    }

    /**
     * 结束候选当前的活跃纸跟会话（进入冷却/退役时调用），恢复纸跟时由 [ensureSession] 新建会话，
     * 避免复用旧会话的回撤/亏损指标导致一恢复就再次冷却
     */
    @Transactional
    fun endActiveSession(candidateId: Long, now: Long = System.currentTimeMillis()): LeaderPaperSession? {
        val active = paperSessionRepository.findTopByCandidateIdAndStatusOrderByStartedAtDesc(
            candidateId, LeaderPaperSessionStatus.ACTIVE
        ) ?: return null
        return paperSessionRepository.save(
            active.copy(
                status = LeaderPaperSessionStatus.COMPLETED,
                endedAt = now,
                updatedAt = now
            )
        )
    }

    @Transactional
    fun processPaperCandidates(runId: Long? = null, batchSize: Int = 200): LeaderPaperProcessingResult {
        val paperCandidates = candidateRepository.findByResearchStateIn(
            listOf(LeaderResearchState.PAPER, LeaderResearchState.TRIAL_READY)
        )
        val pendingStatuses = listOf(LeaderPaperProcessingStatus.NEW, LeaderPaperProcessingStatus.RETRYABLE)
        if (paperCandidates.isEmpty()) {
            // 没有纸跟候选：待处理事件都不参与纸跟，标记为 FILTERED，避免堆积
            val skipped = activityEventRepository.markAllPendingPaperEvents(
                pendingStatuses, LeaderPaperProcessingStatus.FILTERED, System.currentTimeMillis()
            )
            if (skipped > 0) {
                logger.debug("No paper candidates, skipped pending activity events: count={}", skipped)
            }
            return LeaderPaperProcessingResult(processed = 0, filtered = 0, failed = 0)
        }

        val candidatesByWallet = paperCandidates.associateBy { it.normalizedWallet }
        paperCandidates.forEach { ensureSession(it, runId) }

        // 非纸跟钱包的待处理事件标记为 FILTERED；查询只取纸跟钱包的事件，避免被其他钱包的 NEW 事件卡死
        val skipped = activityEventRepository.markNonPaperWalletEvents(
            pendingStatuses, candidatesByWallet.keys, LeaderPaperProcessingStatus.FILTERED, System.currentTimeMillis()
        )
        val page = activityEventRepository.findByPaperProcessingStatusInAndUsableForPaperTrueAndNormalizedWalletInOrderByEventTimeAsc(
            pendingStatuses,
            candidatesByWallet.keys,
            PageRequest.of(0, batchSize)
        )
        if (skipped > 0) {
            logger.debug("Skipped activity events of non-paper wallets: count={}", skipped)
        }

        var processed = 0
        var filtered = 0
        var failed = 0
        val now = System.currentTimeMillis()

        page.content.forEach { event ->
            val wallet = event.normalizedWallet ?: return@forEach
            val candidate = candidatesByWallet[wallet] ?: return@forEach
            val eventId = event.id ?: return@forEach
            val claimed = activityEventRepository.claimForPaperProcessing(
                id = eventId,
                allowed = listOf(LeaderPaperProcessingStatus.NEW, LeaderPaperProcessingStatus.RETRYABLE),
                nextStatus = LeaderPaperProcessingStatus.PROCESSING,
                startedAt = now
            )
            if (claimed != 1) return@forEach

            val claimedEvent = event.copy(
                paperProcessingStatus = LeaderPaperProcessingStatus.PROCESSING,
                processingAttempts = event.processingAttempts + 1,
                paperProcessingStartedAt = now,
                updatedAt = now
            )
            try {
                val session = ensureSession(candidate, runId)
                val outcome = processEvent(candidate, session, claimedEvent)
                when (outcome) {
                    LeaderPaperFilterResult.PASSED -> processed += 1
                    LeaderPaperFilterResult.FILTERED -> filtered += 1
                }
            } catch (e: Exception) {
                failed += 1
                val nextAttempts = claimedEvent.processingAttempts
                val nextStatus = if (nextAttempts >= MAX_PROCESSING_ATTEMPTS) {
                    LeaderPaperProcessingStatus.FAILED
                } else {
                    LeaderPaperProcessingStatus.RETRYABLE
                }
                logger.warn("Paper event processing failed: eventId={}, error={}", eventId, e.message, e)
                activityEventRepository.save(
                    claimedEvent.copy(
                        paperProcessingStatus = nextStatus,
                        paperProcessedAt = System.currentTimeMillis(),
                        lastProcessingError = e.message,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                eventService.record(
                    type = LeaderResearchEventType.PAPER_PROCESSING_FAILED,
                    candidateId = candidate.id,
                    runId = runId,
                    reason = "Paper event processing failed with status=$nextStatus: ${e.message}",
                    payloadSummary = claimedEvent.payloadSummary,
                    dedupeKey = "paper-processing-failed:$eventId:$nextAttempts"
                )
            }
        }

        // 定时重估：每轮对活跃会话的未平仓持仓重新取价并刷新汇总（回撤/可复制盈亏不只在有新事件时更新）
        paperCandidates.forEach { candidate ->
            try {
                revalueActiveSession(candidate)
            } catch (e: Exception) {
                logger.warn("Paper session revaluation failed: candidateId={}, error={}", candidate.id, e.message)
            }
        }

        return LeaderPaperProcessingResult(processed = processed, filtered = filtered, failed = failed)
    }

    /**
     * 对候选活跃会话的未平仓持仓按当前行情重估并刷新会话汇总
     */
    fun revalueActiveSession(candidate: LeaderResearchCandidate): LeaderPaperSession? {
        val candidateId = candidate.id ?: return null
        val session = paperSessionRepository.findTopByCandidateIdAndStatusOrderByStartedAtDesc(
            candidateId, LeaderPaperSessionStatus.ACTIVE
        ) ?: return null
        val sessionId = session.id ?: return null
        val now = System.currentTimeMillis()
        paperPositionRepository.findBySessionIdOrderByUpdatedAtDesc(sessionId)
            .filter { it.quantity > BigDecimal.ZERO }
            .forEach { position ->
                val valuation = quoteMarket(position.marketId, position.outcomeIndex ?: 0)
                val currentValue = valuation.price?.multiply(position.quantity) ?: BigDecimal.ZERO
                paperPositionRepository.save(
                    position.copy(
                        currentPrice = valuation.price,
                        currentValue = currentValue,
                        unrealizedPnl = currentValue.subtract(position.cost),
                        valuationStatus = valuation.status,
                        quoteConfidence = valuation.confidence,
                        quoteSource = valuation.source,
                        quoteTimestamp = valuation.timestamp,
                        updatedAt = now
                    )
                )
            }
        return saveSessionSummary(session)
    }

    fun isEligibleForTrialReady(session: LeaderPaperSession, now: Long = System.currentTimeMillis()): Boolean {
        val ageMs = now - session.startedAt
        val totalTrades = session.tradeCount + session.filteredCount
        val unknownRatio = if (session.openExposure > BigDecimal.ZERO) {
            session.unknownValuationExposure.safeDivide(session.openExposure)
        } else {
            BigDecimal.ZERO
        }
        return ageMs >= PAPER_MIN_AGE_MS &&
            session.tradeCount >= PAPER_MIN_TRADES &&
            session.copyablePnl > BigDecimal.ZERO &&
            session.maxDrawdown >= BigDecimal("-15") &&
            unknownRatio <= BigDecimal("0.20") &&
            session.filteredRatio < BigDecimal("0.50") &&
            totalTrades >= PAPER_MIN_TRADES
    }

    fun shouldEnterCooldown(session: LeaderPaperSession, sourceFresh: Boolean): String? {
        if (session.maxDrawdown < BigDecimal("-20")) return "paper_drawdown_below_-20"
        if (session.tradeCount >= 10 && session.copyablePnl < BigDecimal("-5")) return "copyable_pnl_below_-5_after_10_trades"
        if (!sourceFresh) return "source_stale_over_72h"
        if ((session.openExposure > BigDecimal.ZERO) &&
            session.unknownValuationExposure.safeDivide(session.openExposure) > BigDecimal("0.50")
        ) {
            return "thin_liquidity_exit_risk"
        }
        return null
    }

    @Transactional
    fun refreshSessionSummary(sessionId: Long): LeaderPaperSession {
        val session = paperSessionRepository.findById(sessionId).orElseThrow { IllegalArgumentException("Paper session not found") }
        return saveSessionSummary(session)
    }

    private fun processEvent(
        candidate: LeaderResearchCandidate,
        session: LeaderPaperSession,
        event: LeaderActivityEvent
    ): LeaderPaperFilterResult {
        val candidateId = candidate.id ?: throw IllegalArgumentException("candidate id missing")
        val sessionId = session.id ?: throw IllegalArgumentException("session id missing")
        val filterReason = filterReason(event) ?: sellWithoutPositionReason(sessionId, event)
        if (filterReason != null) {
            val trade = buildTrade(
                candidateId = candidateId,
                sessionId = sessionId,
                event = event,
                filterResult = LeaderPaperFilterResult.FILTERED,
                filterReason = filterReason,
                simulatedPrice = null,
                simulatedSize = null,
                simulatedAmount = null,
                valuationStatus = LeaderResearchValuationStatus.UNKNOWN
            )
            saveTradeIfAbsent(trade)
            markEvent(event, LeaderPaperProcessingStatus.FILTERED, null)
            saveSessionSummary(session)
            eventService.record(
                type = LeaderResearchEventType.PAPER_TRADE_FILTERED,
                candidateId = candidateId,
                reason = filterReason,
                payloadSummary = event.payloadSummary,
                dedupeKey = "paper-filtered:${sessionId}:${event.stableEventKey}"
            )
            return LeaderPaperFilterResult.FILTERED
        }

        val side = event.side!!.uppercase()
        val price = event.price!!
        val marketId = event.marketId!!
        val outcomeIndex = event.outcomeIndex ?: 0
        if (paperTradeRepository.existsBySessionIdAndLeaderTradeIdAndSide(sessionId, event.stableEventKey, side)) {
            markEvent(event, LeaderPaperProcessingStatus.PROCESSED, null)
            return LeaderPaperFilterResult.PASSED
        }
        val existingPosition = paperPositionRepository.findBySessionIdAndMarketIdAndOutcomeIndex(sessionId, marketId, outcomeIndex)
        val simulatedAmount = when (side) {
            "BUY" -> minDecimal(event.amount ?: price.multiply(event.size ?: BigDecimal.ZERO), PAPER_FIXED_AMOUNT)
            "SELL" -> {
                val maxSell = existingPosition?.quantity ?: BigDecimal.ZERO
                val eventSellSize = event.size ?: maxSell
                minDecimal(maxSell, eventSellSize).multiply(price)
            }
            else -> BigDecimal.ZERO
        }.atLeast(BigDecimal.ZERO)
        val simulatedSize = if (price > BigDecimal.ZERO) simulatedAmount.safeDivide(price) else BigDecimal.ZERO

        val valuation = quoteMarket(marketId, outcomeIndex)
        val realizedPnl = applyPosition(
            session = session,
            event = event,
            side = side,
            simulatedPrice = price,
            simulatedSize = simulatedSize,
            simulatedAmount = simulatedAmount,
            valuation = valuation
        )
        val trade = buildTrade(
            candidateId = candidateId,
            sessionId = sessionId,
            event = event,
            filterResult = LeaderPaperFilterResult.PASSED,
            filterReason = null,
            simulatedPrice = price,
            simulatedSize = simulatedSize,
            simulatedAmount = simulatedAmount,
            valuationStatus = valuation.status,
            realizedPnl = realizedPnl
        )
        saveTradeIfAbsent(trade)
        markEvent(event, LeaderPaperProcessingStatus.PROCESSED, null)
        saveSessionSummary(session)
        eventService.record(
            type = LeaderResearchEventType.PAPER_TRADE_RECORDED,
            candidateId = candidateId,
            reason = "${side} paper trade recorded",
            payloadSummary = event.payloadSummary,
            dedupeKey = "paper-trade:${sessionId}:${event.stableEventKey}:$side"
        )
        if (valuation.status == LeaderResearchValuationStatus.UNKNOWN || valuation.status == LeaderResearchValuationStatus.UNAVAILABLE) {
            eventService.record(
                type = LeaderResearchEventType.VALUATION_STALE,
                candidateId = candidateId,
                reason = "Valuation unavailable for $marketId/$outcomeIndex",
                dedupeKey = "paper-valuation:${sessionId}:${event.stableEventKey}"
            )
        }
        return LeaderPaperFilterResult.PASSED
    }

    private fun applyPosition(
        session: LeaderPaperSession,
        event: LeaderActivityEvent,
        side: String,
        simulatedPrice: BigDecimal,
        simulatedSize: BigDecimal,
        simulatedAmount: BigDecimal,
        valuation: PaperQuote
    ): BigDecimal {
        val sessionId = session.id ?: throw IllegalArgumentException("session id missing")
        val candidateId = session.candidateId
        val marketId = event.marketId!!
        val outcomeIndex = event.outcomeIndex ?: 0
        val now = System.currentTimeMillis()
        val existing = paperPositionRepository.findBySessionIdAndMarketIdAndOutcomeIndex(sessionId, marketId, outcomeIndex)
        val updated = if (side == "SELL") {
            val position = existing ?: LeaderPaperPosition(
                sessionId = sessionId,
                candidateId = candidateId,
                marketId = marketId,
                outcome = event.outcome,
                outcomeIndex = outcomeIndex,
                createdAt = now,
                updatedAt = now
            )
            val sellSize = minDecimal(position.quantity, simulatedSize)
            val costPortion = if (position.quantity > BigDecimal.ZERO) {
                position.cost.multiply(sellSize).safeDivide(position.quantity)
            } else {
                BigDecimal.ZERO
            }
            val realized = simulatedPrice.multiply(sellSize).subtract(costPortion)
            val remainingQuantity = position.quantity.subtract(sellSize).atLeast(BigDecimal.ZERO)
            val remainingCost = position.cost.subtract(costPortion).atLeast(BigDecimal.ZERO)
            val currentValue = valuation.price?.multiply(remainingQuantity) ?: BigDecimal.ZERO
            position.copy(
                quantity = remainingQuantity,
                cost = remainingCost,
                avgPrice = if (remainingQuantity > BigDecimal.ZERO) remainingCost.safeDivide(remainingQuantity) else BigDecimal.ZERO,
                currentPrice = valuation.price,
                currentValue = currentValue,
                realizedPnl = position.realizedPnl.add(realized),
                unrealizedPnl = currentValue.subtract(remainingCost),
                valuationStatus = valuation.status,
                quoteConfidence = valuation.confidence,
                quoteSource = valuation.source,
                quoteTimestamp = valuation.timestamp,
                updatedAt = now
            )
        } else {
            val position = existing ?: LeaderPaperPosition(
                sessionId = sessionId,
                candidateId = candidateId,
                marketId = marketId,
                outcome = event.outcome,
                outcomeIndex = outcomeIndex,
                createdAt = now,
                updatedAt = now
            )
            val newQuantity = position.quantity.add(simulatedSize)
            val newCost = position.cost.add(simulatedAmount)
            val currentValue = valuation.price?.multiply(newQuantity) ?: BigDecimal.ZERO
            position.copy(
                quantity = newQuantity,
                cost = newCost,
                avgPrice = if (newQuantity > BigDecimal.ZERO) newCost.safeDivide(newQuantity) else BigDecimal.ZERO,
                currentPrice = valuation.price,
                currentValue = currentValue,
                unrealizedPnl = currentValue.subtract(newCost),
                valuationStatus = valuation.status,
                quoteConfidence = valuation.confidence,
                quoteSource = valuation.source,
                quoteTimestamp = valuation.timestamp,
                updatedAt = now
            )
        }
        val saved = paperPositionRepository.save(updated)
        return if (side == "SELL") saved.realizedPnl.subtract(existing?.realizedPnl ?: BigDecimal.ZERO) else BigDecimal.ZERO
    }

    private fun saveSessionSummary(session: LeaderPaperSession): LeaderPaperSession {
        val sessionId = session.id ?: throw IllegalArgumentException("session id missing")
        val positions = paperPositionRepository.findBySessionIdOrderByUpdatedAtDesc(sessionId)
        val trades = paperTradeRepository.findBySessionIdOrderByEventTimeAsc(sessionId)
        val tradeCount = trades.count { it.filterResult == LeaderPaperFilterResult.PASSED }
        val filteredCount = trades.count { it.filterResult == LeaderPaperFilterResult.FILTERED }
        val totalEvents = tradeCount + filteredCount
        val realized = positions.fold(BigDecimal.ZERO) { acc, position -> acc + position.realizedPnl }
        val availableUnrealized = positions
            .filter { it.valuationStatus == LeaderResearchValuationStatus.AVAILABLE || it.valuationStatus == LeaderResearchValuationStatus.CONFIRMED_ZERO }
            .fold(BigDecimal.ZERO) { acc, position -> acc + position.unrealizedPnl }
        val unknownExposure = positions
            .filter { it.valuationStatus == LeaderResearchValuationStatus.UNKNOWN || it.valuationStatus == LeaderResearchValuationStatus.UNAVAILABLE || it.valuationStatus == LeaderResearchValuationStatus.NO_MATCH }
            .fold(BigDecimal.ZERO) { acc, position -> acc + position.cost }
        val confirmedZeroExposure = positions
            .filter { it.valuationStatus == LeaderResearchValuationStatus.CONFIRMED_ZERO }
            .fold(BigDecimal.ZERO) { acc, position -> acc + position.cost }
        val openExposure = positions.fold(BigDecimal.ZERO) { acc, position -> acc + position.cost }
        val copyablePnl = realized.add(availableUnrealized)
        // maxDrawdown 以百分比记录（与 -15%/-20% 阈值同口径）：累计可复制盈亏 / 累计模拟买入金额 × 100 的历史最小值
        val totalBuyCost = trades
            .filter { it.filterResult == LeaderPaperFilterResult.PASSED && it.side.equals("BUY", ignoreCase = true) }
            .fold(BigDecimal.ZERO) { acc, trade -> acc + (trade.simulatedAmount ?: BigDecimal.ZERO) }
        val currentReturnPct = if (totalBuyCost > BigDecimal.ZERO) {
            copyablePnl.multiply(BigDecimal("100")).divide(totalBuyCost, 4, java.math.RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
        val maxDrawdown = minDecimal(session.maxDrawdown, currentReturnPct)
        return paperSessionRepository.save(
            session.copy(
                tradeCount = tradeCount,
                filteredCount = filteredCount,
                openExposure = openExposure,
                totalRealizedPnl = realized,
                totalUnrealizedPnl = positions.fold(BigDecimal.ZERO) { acc, position -> acc + position.unrealizedPnl },
                copyablePnl = copyablePnl,
                maxDrawdown = maxDrawdown,
                unknownValuationExposure = unknownExposure,
                confirmedZeroExposure = confirmedZeroExposure,
                filteredRatio = if (totalEvents > 0) BigDecimal(filteredCount).safeDivide(BigDecimal(totalEvents)) else BigDecimal.ZERO,
                lastProcessedEventTime = trades.maxOfOrNull { it.eventTime } ?: session.lastProcessedEventTime,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun buildTrade(
        candidateId: Long,
        sessionId: Long,
        event: LeaderActivityEvent,
        filterResult: LeaderPaperFilterResult,
        filterReason: String?,
        simulatedPrice: BigDecimal?,
        simulatedSize: BigDecimal?,
        simulatedAmount: BigDecimal?,
        valuationStatus: LeaderResearchValuationStatus,
        realizedPnl: BigDecimal? = null
    ): LeaderPaperTrade {
        return LeaderPaperTrade(
            sessionId = sessionId,
            candidateId = candidateId,
            activityEventId = event.id,
            leaderTradeId = event.stableEventKey,
            marketId = event.marketId ?: "unknown",
            marketTitle = event.marketTitle,
            marketSlug = event.marketSlug,
            side = event.side?.uppercase() ?: "UNKNOWN",
            outcome = event.outcome,
            outcomeIndex = event.outcomeIndex,
            leaderPrice = event.price,
            leaderSize = event.size,
            simulatedPrice = simulatedPrice,
            simulatedSize = simulatedSize,
            simulatedAmount = simulatedAmount,
            fillAssumption = if (simulatedPrice != null) LeaderPaperFillAssumption.LEADER_PRICE else LeaderPaperFillAssumption.UNKNOWN,
            quoteConfidence = if (valuationStatus == LeaderResearchValuationStatus.AVAILABLE || valuationStatus == LeaderResearchValuationStatus.CONFIRMED_ZERO) {
                LeaderResearchQuoteConfidence.MEDIUM
            } else {
                LeaderResearchQuoteConfidence.UNKNOWN
            },
            quoteSource = "paper_v1",
            quoteTimestamp = System.currentTimeMillis(),
            filterResult = filterResult,
            filterReason = filterReason,
            valuationStatus = valuationStatus,
            realizedPnl = realizedPnl,
            eventTime = event.eventTime,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun saveTradeIfAbsent(trade: LeaderPaperTrade): LeaderPaperTrade {
        if (paperTradeRepository.existsBySessionIdAndLeaderTradeIdAndSide(trade.sessionId, trade.leaderTradeId, trade.side)) {
            return paperTradeRepository.findBySessionIdOrderByEventTimeAsc(trade.sessionId)
                .first { it.leaderTradeId == trade.leaderTradeId && it.side == trade.side }
        }
        return paperTradeRepository.save(trade)
    }

    private fun markEvent(event: LeaderActivityEvent, status: LeaderPaperProcessingStatus, error: String?) {
        activityEventRepository.save(
            event.copy(
                paperProcessingStatus = status,
                processingAttempts = event.processingAttempts,
                paperProcessingStartedAt = event.paperProcessingStartedAt,
                paperProcessedAt = System.currentTimeMillis(),
                lastProcessingError = error,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 纸跟无持仓时的卖出无法复制，记为 FILTERED（不能计为 PASSED 抬高可复制交易数）
     */
    private fun sellWithoutPositionReason(sessionId: Long, event: LeaderActivityEvent): String? {
        if (event.side?.uppercase() != "SELL") return null
        val marketId = event.marketId ?: return null
        val position = paperPositionRepository.findBySessionIdAndMarketIdAndOutcomeIndex(sessionId, marketId, event.outcomeIndex ?: 0)
        return if (position == null || position.quantity <= BigDecimal.ZERO) "sell_without_paper_position" else null
    }

    private fun filterReason(event: LeaderActivityEvent): String? {
        if (event.marketId.isNullOrBlank()) return "market_missing"
        if (event.side.isNullOrBlank()) return "side_missing"
        if (event.side.uppercase() !in setOf("BUY", "SELL")) return "unsupported_side:${event.side}"
        if (event.price == null || event.price <= BigDecimal.ZERO) return "price_missing_or_invalid"
        // 价格带只限制买入；卖出是平仓，不能因价格带被跳过
        if (event.side.uppercase() == "BUY" && (event.price < MIN_PRICE || event.price > MAX_PRICE)) return "price_outside_safe_band"
        if (event.size == null || event.size <= BigDecimal.ZERO) return "size_missing_or_invalid"
        if (event.side.uppercase() == "BUY" && (event.amount ?: event.price.multiply(event.size)) <= BigDecimal.ZERO) return "amount_missing_or_invalid"
        return null
    }

    private fun quoteMarket(marketId: String, outcomeIndex: Int): PaperQuote {
        return try {
            val price = runBlocking { marketPriceService.getCurrentMarketPrice(marketId, outcomeIndex) }
            PaperQuote(
                price = price,
                status = if (price.compareTo(BigDecimal.ZERO) == 0) LeaderResearchValuationStatus.CONFIRMED_ZERO else LeaderResearchValuationStatus.AVAILABLE,
                confidence = LeaderResearchQuoteConfidence.MEDIUM,
                source = "MarketPriceService",
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            logger.debug("Paper valuation unavailable: marketId={}, outcomeIndex={}, error={}", marketId, outcomeIndex, e.message)
            PaperQuote(
                price = null,
                status = LeaderResearchValuationStatus.UNKNOWN,
                confidence = LeaderResearchQuoteConfidence.UNKNOWN,
                source = "MarketPriceService",
                timestamp = System.currentTimeMillis()
            )
        }
    }

    private fun BigDecimal.safeDivide(other: BigDecimal): BigDecimal {
        if (other.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO
        return divide(other, 8, RoundingMode.HALF_UP)
    }

    private fun BigDecimal.atLeast(other: BigDecimal): BigDecimal = if (this >= other) this else other

    private fun minDecimal(left: BigDecimal, right: BigDecimal): BigDecimal = if (left <= right) left else right

    private data class PaperQuote(
        val price: BigDecimal?,
        val status: LeaderResearchValuationStatus,
        val confidence: LeaderResearchQuoteConfidence,
        val source: String,
        val timestamp: Long
    )

    companion object {
        private val PAPER_FIXED_AMOUNT = BigDecimal("1.00000000")
        private val MIN_PRICE = BigDecimal("0.10000000")
        private val MAX_PRICE = BigDecimal("0.80000000")
        private const val PAPER_MIN_TRADES = 10
        private const val PAPER_MIN_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_PROCESSING_ATTEMPTS = 3
    }
}
