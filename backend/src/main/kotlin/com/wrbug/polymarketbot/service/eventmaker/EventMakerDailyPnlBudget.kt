package com.wrbug.polymarketbot.service.eventmaker

/**
 * Persistable anchor for the UTC daily loss budget.
 *
 * [baseline] is the cumulative realized PnL at the start of [day]. Subtracting it
 * from the current cumulative PnL makes `maxDailyLoss` survive restarts while the
 * same UTC trading day is active. A state written on an older day is deliberately
 * re-anchored to the current cumulative PnL, so a previous day's loss never locks
 * the strategy out of the new day.
 */
data class EventMakerDailyPnlAnchor(
    val day: Long,
    val baseline: Double
)

object EventMakerDailyPnlBudget {
    private const val MILLIS_PER_DAY = 86_400_000L

    fun currentDay(now: Long = System.currentTimeMillis()): Long = Math.floorDiv(now, MILLIS_PER_DAY)

    fun resolve(
        now: Long,
        persistedDay: Long?,
        persistedBaseline: Double?,
        totalRealizedPnl: Double
    ): EventMakerDailyPnlAnchor {
        require(totalRealizedPnl.isFinite()) { "totalRealizedPnl must be finite" }
        val today = currentDay(now)
        val reusableBaseline = persistedDay == today &&
            persistedBaseline != null &&
            persistedBaseline.isFinite()
        return EventMakerDailyPnlAnchor(
            day = today,
            baseline = if (reusableBaseline) persistedBaseline!! else totalRealizedPnl
        )
    }
}