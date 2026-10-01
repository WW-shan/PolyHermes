package com.wrbug.polymarketbot.service.copytrading.monitor

/** Tracks the last replayable block separately from live heads so a failed callback is not skipped. */
internal class OnChainBackfillCursor {
    private var latestSeenBlock: Long? = null
    private var lastSuccessfulBackfillBlock: Long? = null
    private var retryFromBlock: Long? = null

    @Synchronized
    fun observe(block: Long) {
        if (latestSeenBlock == null || block > latestSeenBlock!!) {
            latestSeenBlock = block
        }
    }

    @Synchronized
    fun nextBackfillBlock(): Long? = retryFromBlock ?: lastSuccessfulBackfillBlock ?: latestSeenBlock

    @Synchronized
    fun markCallbackFailure(block: Long) {
        retryFromBlock = retryFromBlock?.let { minOf(it, block) } ?: block
    }

    @Synchronized
    fun finishBackfill(fromBlock: Long, latestBlock: Long, allCallbacksSucceeded: Boolean) {
        observe(latestBlock)
        if (allCallbacksSucceeded) {
            if (lastSuccessfulBackfillBlock == null || latestBlock > lastSuccessfulBackfillBlock!!) {
                lastSuccessfulBackfillBlock = latestBlock
            }
            retryFromBlock?.takeIf { it in fromBlock..latestBlock }?.let { retryFromBlock = null }
        } else {
            retryFromBlock = retryFromBlock?.let { minOf(it, fromBlock) } ?: fromBlock
        }
    }
}
