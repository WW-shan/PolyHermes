package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket

interface EventMakerExecutionPort {
    suspend fun validateAccount(accountId: Long): Result<Unit>

    suspend fun place(
        accountId: Long,
        market: LivePaperMarket,
        intent: EventMakerQuoteIntent
    ): Result<EventMakerLiveOrder>

    suspend fun getOrder(accountId: Long, orderId: String): Result<EventMakerLiveOrder>

    suspend fun cancel(accountId: Long, orderId: String): Result<Unit>
}