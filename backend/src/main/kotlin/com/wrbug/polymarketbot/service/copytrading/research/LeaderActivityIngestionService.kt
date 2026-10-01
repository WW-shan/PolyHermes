package com.wrbug.polymarketbot.service.copytrading.research

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.dto.ActivityTradeMessage
import com.wrbug.polymarketbot.entity.LeaderActivityEvent
import com.wrbug.polymarketbot.enums.LeaderPaperProcessingStatus
import com.wrbug.polymarketbot.enums.LeaderResearchSourceType
import com.wrbug.polymarketbot.repository.LeaderActivityEventRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.security.MessageDigest

@Service
class LeaderActivityIngestionService(
    private val activityEventRepository: LeaderActivityEventRepository,
    private val gson: Gson
) {
    private val logger = LoggerFactory.getLogger(LeaderActivityIngestionService::class.java)

    @Transactional
    fun ingestUserActivity(
        activity: UserActivityResponse,
        source: LeaderResearchSourceType = LeaderResearchSourceType.ACTIVITY_DERIVED
    ): LeaderActivityEvent {
        val raw = gson.toJson(activity)
        val normalizedWallet = normalizeWallet(activity.proxyWallet)
        val isTrade = activity.type.equals("TRADE", ignoreCase = true)
        val hasRequiredTradeFields = isTrade &&
            !normalizedWallet.isNullOrBlank() &&
            !activity.conditionId.isNullOrBlank() &&
            !activity.side.isNullOrBlank() &&
            activity.price != null &&
            activity.size != null
        val eventTime = normalizeTimestamp(activity.timestamp)
        val stableKey = fillStableKey(
            wallet = normalizedWallet,
            txHash = activity.transactionHash,
            asset = activity.asset,
            side = activity.side,
            size = activity.size?.let { BigDecimal.valueOf(it) },
            price = activity.price?.let { BigDecimal.valueOf(it) }
        ) ?: sha256("${source.name}:${activity.proxyWallet}:${activity.conditionId}:${activity.side}:${activity.asset}:${eventTime}:${activity.price}:${activity.size}")

        val event = LeaderActivityEvent(
            source = source.name,
            sourceEventId = activity.transactionHash,
            stableEventKey = stableKey,
            normalizedWallet = normalizedWallet,
            marketId = activity.conditionId,
            marketTitle = activity.title,
            marketSlug = activity.slug,
            asset = activity.asset,
            side = activity.side?.uppercase(),
            outcome = activity.outcome,
            outcomeIndex = activity.outcomeIndex,
            price = activity.price?.let { BigDecimal.valueOf(it) },
            size = activity.size?.let { BigDecimal.valueOf(it) },
            amount = activity.usdcSize?.let { BigDecimal.valueOf(it) }
                ?: amount(activity.price, activity.size),
            eventTime = eventTime,
            rawPayloadHash = sha256(raw),
            payloadSummary = summarize(
                wallet = normalizedWallet,
                side = activity.side,
                marketTitle = activity.title,
                price = activity.price?.toString(),
                size = activity.size?.toString()
            ),
            usableForDiscovery = !normalizedWallet.isNullOrBlank() && isTrade,
            usableForPaper = hasRequiredTradeFields,
            unusableReason = if (hasRequiredTradeFields) null else buildUnusableReason(isTrade, normalizedWallet, activity.conditionId, activity.side, activity.price, activity.size),
            paperProcessingStatus = LeaderPaperProcessingStatus.NEW,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        return saveDeduped(event)
    }

    @Transactional
    fun ingestWebSocketTrade(
        message: ActivityTradeMessage,
        source: LeaderResearchSourceType = LeaderResearchSourceType.GLOBAL_ACTIVITY_CAPTURE
    ): LeaderActivityEvent {
        val payload = message.payload
        val raw = gson.toJson(message)
        val normalizedWallet = normalizeWallet(payload.trader?.address ?: payload.proxyWallet)
        val eventTime = normalizeTimestamp(payload.timestamp ?: message.timestamp)
        val price = payload.price.toBigDecimalOrNull()
        val size = payload.size.toBigDecimalOrNull()
        val hasRequiredTradeFields = !normalizedWallet.isNullOrBlank() &&
            payload.conditionId.isNotBlank() &&
            payload.side.isNotBlank() &&
            price != null &&
            size != null
        val stableKey = fillStableKey(
            wallet = normalizedWallet,
            txHash = payload.transactionHash,
            asset = payload.asset,
            side = payload.side,
            size = size,
            price = price
        ) ?: sha256("${source.name}:$normalizedWallet:${payload.conditionId}:${payload.side}:${payload.asset}:$eventTime:$price:$size")

        val event = LeaderActivityEvent(
            source = source.name,
            sourceEventId = payload.transactionHash,
            stableEventKey = stableKey,
            normalizedWallet = normalizedWallet,
            marketId = payload.conditionId.takeIf { it.isNotBlank() },
            marketSlug = payload.slug,
            asset = payload.asset.takeIf { it.isNotBlank() },
            side = payload.side.uppercase().takeIf { it.isNotBlank() },
            outcome = payload.outcome,
            outcomeIndex = payload.outcomeIndex,
            price = price,
            size = size,
            amount = if (price != null && size != null) price.multiply(size) else null,
            eventTime = eventTime,
            rawPayloadHash = sha256(raw),
            payloadSummary = summarize(
                wallet = normalizedWallet,
                side = payload.side,
                marketTitle = payload.slug,
                price = price?.toPlainString(),
                size = size?.toPlainString()
            ),
            usableForDiscovery = !normalizedWallet.isNullOrBlank(),
            usableForPaper = hasRequiredTradeFields,
            unusableReason = if (hasRequiredTradeFields) null else buildUnusableReason(true, normalizedWallet, payload.conditionId, payload.side, price, size),
            paperProcessingStatus = LeaderPaperProcessingStatus.NEW,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        return saveDeduped(event)
    }

    fun normalizeWallet(wallet: String?): String? {
        val trimmed = wallet?.trim()?.lowercase() ?: return null
        val evm = Regex("^0x[a-f0-9]{40}$")
        return trimmed.takeIf { evm.matches(it) }
    }

    fun stableHash(raw: String): String = sha256(raw)

    /**
     * 成交粒度的去重键：hash(wallet, tx, asset, side, size, price)，与来源无关（WS 与 Data API 的同一成交可去重），
     * 同一 tx 的多笔成交不会互相覆盖。缺 txHash 时返回 null，由调用方用含时间戳的兜底键。
     */
    fun fillStableKey(
        wallet: String?,
        txHash: String?,
        asset: String?,
        side: String?,
        size: BigDecimal?,
        price: BigDecimal?
    ): String? {
        val tx = txHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val normalizedSize = size?.stripTrailingZeros()?.toPlainString() ?: ""
        val normalizedPrice = price?.stripTrailingZeros()?.toPlainString() ?: ""
        // 保留 txHash 前缀便于排查与游标展示，后缀为成交维度的哈希
        val fillHash = sha256(
            "fill:${wallet ?: ""}:$tx:${asset?.trim() ?: ""}:${side?.trim()?.uppercase() ?: ""}:$normalizedSize:$normalizedPrice"
        )
        return "$tx:$fillHash"
    }

    private fun saveDeduped(event: LeaderActivityEvent): LeaderActivityEvent {
        activityEventRepository.findByStableEventKey(event.stableEventKey)?.let { return it }
        return try {
            activityEventRepository.save(event)
        } catch (e: DataIntegrityViolationException) {
            logger.debug("Activity event deduped: stableKey={}", event.stableEventKey)
            activityEventRepository.findByStableEventKey(event.stableEventKey) ?: throw e
        }
    }

    private fun normalizeTimestamp(value: Any?): Long {
        val number = when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        } ?: return System.currentTimeMillis()
        return if (number < 10_000_000_000L) number * 1000 else number
    }

    private fun Any?.toBigDecimalOrNull(): BigDecimal? {
        return when (this) {
            is BigDecimal -> this
            is Number -> BigDecimal.valueOf(this.toDouble())
            is String -> this.trim().takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
            else -> null
        }
    }

    private fun amount(price: Double?, size: Double?): BigDecimal? {
        if (price == null || size == null) return null
        return BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(size))
    }

    private fun buildUnusableReason(
        isTrade: Boolean,
        wallet: String?,
        marketId: String?,
        side: String?,
        price: Any?,
        size: Any?
    ): String {
        val reasons = mutableListOf<String>()
        if (!isTrade) reasons += "not_trade"
        if (wallet.isNullOrBlank()) reasons += "wallet_missing_or_invalid"
        if (marketId.isNullOrBlank()) reasons += "market_missing"
        if (side.isNullOrBlank()) reasons += "side_missing"
        if (price == null) reasons += "price_missing"
        if (size == null) reasons += "size_missing"
        return reasons.joinToString(",")
    }

    private fun summarize(wallet: String?, side: String?, marketTitle: String?, price: String?, size: String?): String {
        return listOfNotNull(wallet, side?.uppercase(), marketTitle, price?.let { "price=$it" }, size?.let { "size=$it" })
            .joinToString(" | ")
            .take(1000)
    }

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
