package com.wrbug.polymarketbot.research.eventmaker

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.wrbug.polymarketbot.research.livepaper.LivePaperMarket
import com.wrbug.polymarketbot.service.eventmaker.EventMakerPersistedMarket
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class EventMakerShadowRecoveryState(
    val version: Int = EventMakerShadowRecoveryStore.CURRENT_VERSION,
    val savedAt: Long = 0L,
    val markets: List<LivePaperMarket> = emptyList()
)

/**
 * Sidecar for [com.wrbug.polymarketbot.service.eventmaker.EventMakerPersistedState].
 * The ledger file intentionally stores only trading state; this file preserves the
 * full market metadata needed to resume an orphaned position after the original
 * discovery window has passed.
 */
internal object EventMakerShadowRecoveryStore {
    const val CURRENT_VERSION = 1
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun load(path: Path): EventMakerShadowRecoveryState? {
        if (!Files.exists(path)) return null
        val raw = try {
            Files.readString(path, StandardCharsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalStateException("shadow_recovery_unreadable", error)
        }
        val parsed = try {
            gson.fromJson(raw, EventMakerShadowRecoveryState::class.java)
        } catch (error: Exception) {
            throw IllegalStateException("shadow_recovery_corrupt", error)
        } ?: throw IllegalStateException("shadow_recovery_empty")
        if (parsed.version != CURRENT_VERSION) {
            throw IllegalStateException("shadow_recovery_version_unsupported:${parsed.version}")
        }
        val duplicateConditions = parsed.markets.groupingBy { it.conditionId }.eachCount()
            .filterValues { it > 1 }.keys
        if (duplicateConditions.isNotEmpty()) {
            throw IllegalStateException("shadow_recovery_duplicate_market:${duplicateConditions.joinToString(",")}")
        }
        parsed.markets.forEach { market ->
            if (market.conditionId.isBlank() || market.tokenIds.isEmpty() ||
                market.favoriteIndex !in market.tokenIds.indices || market.endTimestamp <= 0L ||
                market.tickSize <= 0.0
            ) {
                throw IllegalStateException("shadow_recovery_market_invalid:${market.conditionId}")
            }
        }
        return parsed
    }

    fun save(path: Path, markets: List<LivePaperMarket>, savedAt: Long = System.currentTimeMillis()) {
        val parent = path.parent ?: throw IllegalStateException("shadow_recovery_path_has_no_parent")
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "${path.fileName}.", ".tmp")
        try {
            Files.writeString(
                temp,
                gson.toJson(EventMakerShadowRecoveryState(savedAt = savedAt, markets = markets)),
                StandardCharsets.UTF_8
            )
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * A market discovered in the current 3-7 day window takes precedence over
     * the cached copy because its tick size and metadata may have been refreshed.
     */
    fun merge(discovered: List<LivePaperMarket>, recovered: List<LivePaperMarket>): List<LivePaperMarket> {
        val merged = LinkedHashMap<String, LivePaperMarket>()
        discovered.forEach { merged[it.conditionId] = it }
        recovered.forEach { market -> merged.putIfAbsent(market.conditionId, market) }
        return merged.values.toList()
    }

    /**
     * Converts the ledger's minimal metadata into a usable market when the old
     * sidecar is absent. The non-favourite token id is a tombstone: Shadow never
     * needs to trade the other side, and settlement is keyed by condition id.
     */
    fun reconstructMarket(persisted: EventMakerPersistedMarket): LivePaperMarket? {
        val favoriteTokenId = persisted.favoriteTokenId?.takeIf { it.isNotBlank() } ?: return null
        val favoriteIndex = persisted.favoriteIndex?.takeIf { it in 0..1 } ?: 0
        val hasOpenInventory =
            (persisted.inventoryShares.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO
        val tickSize = persisted.tickSize?.takeIf { it > 0.0 }
            ?: if (hasOpenInventory) return null else 0.01
        val endTimestamp = persisted.endTimestamp?.takeIf { it > 0L }
            ?: if (hasOpenInventory) return null else Long.MAX_VALUE
        val tokenIds = MutableList(2) { index -> "recovered-unavailable:${persisted.conditionId}:$index" }
        tokenIds[favoriteIndex] = favoriteTokenId
        return LivePaperMarket(
            conditionId = persisted.conditionId,
            question = persisted.conditionId,
            tag = "Recovered",
            volume24hr = 0.0,
            endTimestamp = endTimestamp,
            tickSize = tickSize,
            outcomes = listOf("Yes", "No"),
            tokenIds = tokenIds,
            favoriteIndex = favoriteIndex,
            negRisk = persisted.negativeRisk ?: false
        )
    }
}