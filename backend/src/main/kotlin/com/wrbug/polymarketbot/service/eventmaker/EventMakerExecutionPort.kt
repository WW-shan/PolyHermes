package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket

interface EventMakerExecutionPort {
    suspend fun validateAccount(accountId: Long): Result<Unit>

    suspend fun listOpenOrderIds(accountId: Long): Result<Set<String>>

    suspend fun listOpenPositions(accountId: Long): Result<Map<String, java.math.BigDecimal>>

    suspend fun place(
        accountId: Long,
        market: LivePaperMarket,
        intent: EventMakerQuoteIntent
    ): Result<EventMakerLiveOrder>

    suspend fun getOrder(accountId: Long, orderId: String): Result<EventMakerLiveOrder>

    suspend fun cancel(accountId: Long, orderId: String): Result<Unit>

    /**
     * Reads the resolved-market payoff and the account's remaining token position.
     * Implementations must fail closed when the position, token or resolution is ambiguous.
     */
    suspend fun getSettlementSnapshot(
        accountId: Long,
        market: LivePaperMarket
    ): Result<EventMakerSettlementSnapshot> =
        Result.failure(UnsupportedOperationException("settlement_snapshot_not_supported"))

    /**
     * Loads only the on-chain position for a previously booked settlement. Used
     * during startup recovery after a crash between redemption and persistence.
     */
    suspend fun getSettlementPosition(
        accountId: Long,
        settlement: EventMakerSettlementRecord
    ): Result<EventMakerSettlementPosition> =
        Result.failure(UnsupportedOperationException("settlement_position_not_supported"))

    /** Redeems a resolved outcome. The caller persists the pending settlement first. */
    suspend fun redeemSettlement(
        accountId: Long,
        settlement: EventMakerSettlementRecord
    ): Result<String> =
        Result.failure(UnsupportedOperationException("settlement_redeem_not_supported"))
}