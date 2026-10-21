package com.wrbug.polymarketbot.service.eventmaker

import com.google.gson.Gson
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@Service
class EventMakerStateStore(
    @Value("\${event-maker.state-file:data/event-maker-state.json}")
    private val stateFile: String = "data/event-maker-state.json"
) {
    private val gson = Gson()
    private val lock = Any()

    fun save(state: EventMakerPersistedState) = synchronized(lock) {
        validate(state)
        val path = targetPath()
        val parent = path.parent ?: throw IllegalStateException("state_file_has_no_parent")
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "${path.fileName}.", ".tmp")
        try {
            Files.writeString(
                temp,
                gson.toJson(state),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
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

    fun load(): EventMakerPersistedState? = synchronized(lock) {
        val path = targetPath()
        if (!Files.exists(path)) return@synchronized null
        val raw = try {
            Files.readString(path, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("event_maker_state_unreadable", e)
        }
        val parsed = try {
            gson.fromJson(raw, EventMakerPersistedState::class.java)
        } catch (e: Exception) {
            throw IllegalStateException("event_maker_state_corrupt", e)
        } ?: throw IllegalStateException("event_maker_state_empty")
        if (parsed.version != CURRENT_VERSION) {
            throw IllegalStateException("event_maker_state_version_unsupported:${parsed.version}")
        }
        validate(parsed)
        parsed
    }

    fun clear() = synchronized(lock) {
        Files.deleteIfExists(targetPath())
    }

    private fun validate(state: EventMakerPersistedState) {
        try {
            if (state.mode !in MODES) invalid("unknown_mode:${state.mode}")
            if (state.mode == EventMakerMode.LIVE.name && (state.accountId == null || state.accountId <= 0L)) {
                invalid("live_account_missing")
            }
            if (state.cumulativeRealizedPnl.isNaN() || state.cumulativeRealizedPnl.isInfinite()) {
                invalid("cumulative_realized_pnl_invalid")
            }
            if (state.dailyPnlDay == null || state.dailyPnlDay < 0L) {
                invalid("daily_pnl_day_invalid")
            }
            if (state.dailyPnlBaseline == null ||
                state.dailyPnlBaseline.isNaN() ||
                state.dailyPnlBaseline.isInfinite()
            ) {
                invalid("daily_pnl_baseline_invalid")
            }
            val markets = state.markets
            val duplicateLegs = markets
                .groupingBy { it.conditionId.lowercase() to it.legOffsetCents }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            if (duplicateLegs.isNotEmpty()) {
                invalid("duplicate_condition_leg:${duplicateLegs.joinToString(",")}")
            }
            markets.groupBy { it.conditionId.lowercase() }
                .filterValues { legs -> legs.size > 1 && legs.any { it.legOffsetCents == null } }
                .keys
                .takeIf { it.isNotEmpty() }
                ?.let { invalid("legacy_and_ladder_mixed:${it.joinToString(",")}") }
            markets.forEach(::validateMarket)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("event_maker_state_invalid", e)
        }
    }

    private fun validateMarket(market: EventMakerPersistedMarket) {
        if (market.conditionId.isBlank()) invalid("condition_id_blank")
        if (market.legOffsetCents != null &&
            (!market.legOffsetCents.isFinite() || market.legOffsetCents < 0.0)
        ) {
            invalid("leg_offset_invalid:${market.conditionId}:${market.legOffsetCents}")
        }
        if (market.favoriteTokenId.isNullOrBlank()) invalid("favorite_token_missing:${market.conditionId}")
        if (market.phase !in PHASES) invalid("phase_invalid:${market.conditionId}:${market.phase}")
        val inventory = market.inventoryShares.toBigDecimalOrNull()
            ?.takeIf { it >= java.math.BigDecimal.ZERO }
            ?: invalid("inventory_invalid:${market.conditionId}")
        val entryPrice = market.entryPrice.toBigDecimalOrNull()
            ?.takeIf { it >= java.math.BigDecimal.ZERO && it <= java.math.BigDecimal.ONE }
            ?: invalid("entry_price_invalid:${market.conditionId}")
        if (inventory > java.math.BigDecimal.ZERO && entryPrice <= java.math.BigDecimal.ZERO) {
            invalid("inventory_without_entry_price:${market.conditionId}")
        }
        if (market.completedCycles < 0) invalid("completed_cycles_invalid:${market.conditionId}")
        if (market.realizedPnl.isNaN() || market.realizedPnl.isInfinite()) {
            invalid("realized_pnl_invalid:${market.conditionId}")
        }
        val settlements = market.settlements.orEmpty()
        val duplicateSettlements = settlements.groupingBy { it.cycleExitOrderId }.eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateSettlements.isNotEmpty()) {
            invalid("duplicate_settlement:${market.conditionId}:${duplicateSettlements.joinToString(",")}")
        }
        settlements.forEach { settlement ->
            if (settlement.marketId != market.conditionId ||
                settlement.favoriteTokenId != market.favoriteTokenId ||
                settlement.entryOrderId.isBlank() ||
                settlement.cycleExitOrderId.isBlank()
            ) {
                invalid("settlement_identity_invalid:${market.conditionId}")
            }
            if (settlement.entryPrice <= java.math.BigDecimal.ZERO ||
                settlement.entryPrice > java.math.BigDecimal.ONE ||
                settlement.exitPrice < java.math.BigDecimal.ZERO ||
                settlement.exitPrice > java.math.BigDecimal.ONE ||
                settlement.shares <= java.math.BigDecimal.ZERO ||
                settlement.entryAt <= 0L ||
                settlement.settledAt < settlement.entryAt ||
                settlement.outcomeIndex < 0 ||
                settlement.pnl.isNaN() || settlement.pnl.isInfinite() ||
                settlement.redemptionStatus !in EventMakerRedemptionStatus.entries ||
                (settlement.redemptionStatus == EventMakerRedemptionStatus.REDEEMED &&
                    settlement.transactionHash.isNullOrBlank())
            ) {
                invalid("settlement_values_invalid:${market.conditionId}:${settlement.cycleExitOrderId}")
            }
        }
        if (settlements.isNotEmpty() && inventory > java.math.BigDecimal.ZERO) {
            invalid("settlement_with_inventory:${market.conditionId}")
        }
        val orderId = market.activeOrderId
        if (orderId.isNullOrBlank()) {
            if (market.activeOrderSide != null || market.activeOrderPrice != null ||
                market.activeOrderOriginalShares != null || market.activeOrderMatchedShares != null
            ) {
                invalid("active_order_fragmented:${market.conditionId}")
            }
            return
        }
        if (market.activeOrderSide !in ORDER_SIDES) invalid("active_order_side_invalid:${market.conditionId}")
        val price = market.activeOrderPrice?.toBigDecimalOrNull()
            ?.takeIf { it > java.math.BigDecimal.ZERO && it < java.math.BigDecimal.ONE }
            ?: invalid("active_order_price_invalid:${market.conditionId}")
        val original = market.activeOrderOriginalShares?.toBigDecimalOrNull()
            ?.takeIf { it > java.math.BigDecimal.ZERO }
            ?: invalid("active_order_size_invalid:${market.conditionId}")
        val matched = market.activeOrderMatchedShares?.toBigDecimalOrNull()
            ?.takeIf { it >= java.math.BigDecimal.ZERO && it <= original }
            ?: invalid("active_order_matched_invalid:${market.conditionId}")
        if (!EventMakerOrderStatuses.isKnown(market.activeOrderStatus.orEmpty())) {
            invalid("active_order_status_invalid:${market.conditionId}:${market.activeOrderStatus}")
        }
        if (price <= java.math.BigDecimal.ZERO) invalid("active_order_price_invalid:${market.conditionId}")
        if (matched > original) invalid("active_order_matched_invalid:${market.conditionId}")
    }

    private fun invalid(reason: String): Nothing =
        throw IllegalStateException("event_maker_state_invalid:$reason")

    private fun targetPath(): Path = Paths.get(stateFile).toAbsolutePath().normalize()

    companion object {
        const val CURRENT_VERSION = 4
        private val MODES = setOf(EventMakerMode.SHADOW.name, EventMakerMode.LIVE.name)
        private val PHASES = EventMakerPhase.entries.map { it.name }.toSet()
        private val ORDER_SIDES = EventMakerSide.entries.map { it.name }.toSet()
    }
}
