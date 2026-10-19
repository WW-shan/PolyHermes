package com.wrbug.polymarketbot.service.eventmaker

/**
 * Single source of truth for CLOB order status values.
 *
 * The documented status set changed with CLOB V2. Keeping every status check in
 * one place avoids treating a valid terminal state (for example
 * CANCELED_MARKET_RESOLVED) as an unknown order state and halting LIVE trading.
 */
object EventMakerOrderStatuses {
    private val ACTIVE = setOf("LIVE", "OPEN", "DELAYED", "UNMATCHED")

    private val TERMINAL = setOf(
        "MATCHED",
        "FILLED",
        "CANCELED",
        "CANCELLED",
        "EXPIRED",
        "REJECTED",
        "INVALID",
        "CANCELED_MARKET_RESOLVED"
    )

    private val MATCHED_TERMINAL = setOf("MATCHED", "FILLED")

    private val CANCELED_TERMINAL = setOf(
        "CANCELED",
        "CANCELLED",
        "EXPIRED",
        "REJECTED",
        "INVALID",
        "CANCELED_MARKET_RESOLVED"
    )

    val KNOWN: Set<String> = ACTIVE + TERMINAL

    fun normalize(status: String): String = status.trim().uppercase()

    fun isKnown(status: String): Boolean = normalize(status) in KNOWN

    fun isTerminal(status: String): Boolean = normalize(status) in TERMINAL

    fun isMatchedTerminal(status: String): Boolean = normalize(status) in MATCHED_TERMINAL

    fun isCanceledTerminal(status: String): Boolean = normalize(status) in CANCELED_TERMINAL
}