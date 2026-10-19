package com.wrbug.polymarketbot.service.eventmaker

import com.wrbug.polymarketbot.research.livepaper.LiveTradePrint

/**
 * Tracks short-horizon taker flow on the quoted token.
 *
 * The guard is deliberately causal: it only uses prints that have already been
 * observed. A print that arrives while a BUY is resting can fill that order;
 * the guard can cancel the remaining order immediately afterwards, but it
 * cannot retroactively prevent that first match.
 */
internal class EventMakerAdverseFlowGuard(private val config: EventMakerConfig) {
    private data class RetainedPrint(
        val timestamp: Long,
        val side: String,
        val price: Double,
        val size: Double
    )

    private val lock = Any()
    private val printsByToken = mutableMapOf<String, MutableList<RetainedPrint>>()
    private val seen = linkedMapOf<String, Long>()

    val enabled: Boolean
        get() = config.flowGuardWindowSeconds > 0

    fun reset() = synchronized(lock) {
        printsByToken.clear()
        seen.clear()
    }

    fun record(trade: LiveTradePrint) {
        if (!enabled || trade.price <= 0.0 || trade.price >= 1.0 || trade.size <= 0.0) return
        val windowMs = config.flowGuardWindowSeconds * 1_000L
        synchronized(lock) {
            if (seen.put(trade.key, trade.timestamp) != null) return
            val cutoff = trade.timestamp - windowMs
            while (seen.isNotEmpty()) {
                val oldest = seen.entries.first()
                if (oldest.value >= cutoff) break
                seen.remove(oldest.key)
            }
            if (trade.timestamp < cutoff) return
            val retained = printsByToken.getOrPut(trade.tokenId) { mutableListOf() }
            retained.removeAll { it.timestamp < cutoff }
            retained += RetainedPrint(
                timestamp = trade.timestamp,
                side = trade.side.uppercase(),
                price = trade.price,
                size = trade.size
            )
            retained.sortBy { it.timestamp }
        }
    }

    fun blocks(tokenId: String, now: Long): Boolean {
        val windowMs = config.flowGuardWindowSeconds * 1_000L
        if (windowMs <= 0L) return false
        synchronized(lock) {
            val retained = printsByToken[tokenId] ?: return false
            val cutoff = now - windowMs
            retained.removeAll { it.timestamp < cutoff }
            var firstPrice = Double.NaN
            var lastPrice = Double.NaN
            var buyVolume = 0.0
            var sellVolume = 0.0
            for (trade in retained) {
                if (trade.timestamp < cutoff || trade.timestamp > now) continue
                if (firstPrice.isNaN()) firstPrice = trade.price
                lastPrice = trade.price
                when {
                    trade.side.equals("BUY", ignoreCase = true) -> buyVolume += trade.size
                    trade.side.equals("SELL", ignoreCase = true) -> sellVolume += trade.size
                }
            }
            val knownVolume = buyVolume + sellVolume
            if (knownVolume <= 1e-12 || firstPrice.isNaN() || lastPrice.isNaN()) return false
            val signedFlow = (buyVolume - sellVolume) / knownVolume
            val priceDropCents = (firstPrice - lastPrice) * 100.0
            return signedFlow <= config.flowGuardMaxSignedFlow + 1e-9 &&
                priceDropCents + 1e-9 >= config.flowGuardMinPriceDropCents
        }
    }
}
