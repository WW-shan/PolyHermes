package com.wrbug.polymarketbot.service.common

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.wrbug.polymarketbot.api.MarketResponse
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.entity.Market
import com.wrbug.polymarketbot.repository.MarketRepository
import com.wrbug.polymarketbot.util.PolymarketTradingFee
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.getEventSlug
import com.wrbug.polymarketbot.util.parseStringArray
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import retrofit2.Response
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 市场信息服务
 * 负责缓存和管理市场信息
 */
@Service
class MarketService(
    val marketRepository: MarketRepository,  // 改为 public，供 MarketPollingService 使用
    private val retrofitFactory: RetrofitFactory
) {

    private val logger = LoggerFactory.getLogger(MarketService::class.java)

    // LRU 缓存（避免频繁查询数据库），最多缓存 200 条记录
    private val marketCache: Cache<String, Market> = Caffeine.newBuilder()
        .maximumSize(200)  // 最多缓存 200 条记录
        .build()

    /** 最近一次尝试刷新手续费率的时间（marketId -> 毫秒），用于节流重试 */
    private val feeRateRefreshAttemptedAt: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    companion object {
        /** Gamma condition_ids 单次查询数量上限 */
        private const val GAMMA_CONDITION_IDS_BATCH_SIZE = 50

        /** 费率缺失时的刷新间隔 */
        private const val FEE_RATE_REFRESH_INTERVAL_MS = 10 * 60 * 1000L
    }
    
    /**
     * 根据市场ID获取市场信息
     * 优先从缓存获取，如果不存在则从数据库查询，如果数据库也没有则从API获取并保存
     */
    fun getMarket(marketId: String): Market? {
        // 1. 从缓存获取
        marketCache.getIfPresent(marketId)?.let { return it }

        // 2. 从数据库查询
        val market = marketRepository.findByMarketId(marketId)
        if (market != null) {
            marketCache.put(marketId, market)
            return market
        }

        // 3. 从API获取（异步，不阻塞）
        runBlocking {
            try {
                fetchAndSaveMarket(marketId)
            } catch (e: Exception) {
                logger.warn("获取市场信息失败: marketId=$marketId, error=${e.message}")
            }
        }

        // 再次从数据库查询（API可能已经保存）
        return marketRepository.findByMarketId(marketId)?.also {
            marketCache.put(marketId, it)
        }
    }
    
    /**
     * 批量获取市场信息
     */
    fun getMarkets(marketIds: List<String>): Map<String, Market> {
        val result = mutableMapOf<String, Market>()
        val missingIds = mutableListOf<String>()
        
        // 1. 从缓存和数据库获取
        for (marketId in marketIds) {
            val market = getMarket(marketId)
            if (market != null) {
                result[marketId] = market
            } else {
                missingIds.add(marketId)
            }
        }
        
        // 2. 批量从API获取缺失的市场信息
        if (missingIds.isNotEmpty()) {
            runBlocking {
                try {
                    fetchAndSaveMarkets(missingIds)
                } catch (e: Exception) {
                    logger.warn("批量获取市场信息失败: marketIds=$missingIds, error=${e.message}")
                }
            }
            
            // 再次从数据库查询
            val savedMarkets = marketRepository.findByMarketIdIn(missingIds)
            for (market in savedMarkets) {
                result[market.marketId] = market
                marketCache.put(market.marketId, market)
            }
        }
        
        return result
    }
    
    /**
     * 按 conditionId 查询 Gamma 市场。
     * Gamma 对已结束市场的默认查询返回 []，因此先按默认条件查询，缺失的 id 再用 closed=true 补查一次。
     * 任一请求失败都会抛出异常，避免把接口失败当作"市场不存在"。
     *
     * @return key 为调用方传入的 conditionId（原样），不存在的市场不在结果中
     */
    private suspend fun queryMarketsByConditionIds(conditionIds: List<String>): Map<String, MarketResponse> {
        val ids = conditionIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val gammaApi = retrofitFactory.createGammaApi()
        val result = mutableMapOf<String, MarketResponse>()
        for (chunk in ids.chunked(GAMMA_CONDITION_IDS_BATCH_SIZE)) {
            collectMarkets(chunk, gammaApi.listMarkets(conditionIds = chunk), result, closed = false)
            val missing = chunk.filter { it !in result }
            if (missing.isNotEmpty()) {
                collectMarkets(missing, gammaApi.listMarkets(conditionIds = missing, closed = true), result, closed = true)
            }
        }
        return result
    }

    private fun collectMarkets(
        requestedIds: List<String>,
        response: Response<List<MarketResponse>>,
        into: MutableMap<String, MarketResponse>,
        closed: Boolean
    ) {
        if (!response.isSuccessful) {
            throw IllegalStateException("Gamma 查询市场失败: closed=$closed, code=${response.code()}")
        }
        val byLowerId = (response.body() ?: emptyList())
            .filter { !it.conditionId.isNullOrBlank() }
            .associateBy { it.conditionId!!.lowercase() }
        for (id in requestedIds) {
            byLowerId[id.lowercase()]?.let { into[id] = it }
        }
    }

    /**
     * 从API获取市场信息并保存到数据库（含已结束市场）
     */
    private suspend fun fetchAndSaveMarket(marketId: String): Market? {
        return try {
            val marketResponse = queryMarketsByConditionIds(listOf(marketId))[marketId]
            if (marketResponse != null) {
                saveMarketFromResponse(marketId, marketResponse)
            } else {
                logger.warn("Gamma 未找到市场（含已结束市场）: marketId=$marketId")
                null
            }
        } catch (e: Exception) {
            logger.error("从API获取市场信息失败: marketId=$marketId, error=${e.message}", e)
            null
        }
    }
    
    /**
     * 批量从API获取市场信息并保存到数据库（含已结束市场）
     */
    private suspend fun fetchAndSaveMarkets(marketIds: List<String>) {
        if (marketIds.isEmpty()) return
        
        try {
            val marketMap = queryMarketsByConditionIds(marketIds)
            for ((marketId, marketResponse) in marketMap) {
                saveMarketFromResponse(marketId, marketResponse)
            }
        } catch (e: Exception) {
            logger.error("批量从API获取市场信息失败: marketIds=$marketIds, error=${e.message}", e)
        }
    }
    
    /**
     * 从API响应保存市场信息到数据库
     */
    private fun saveMarketFromResponse(marketId: String, marketResponse: MarketResponse): Market? {
        return try {
            val existingMarket = marketRepository.findByMarketId(marketId)
            
            // 保存原来的 slug（用于显示）
            val slug = marketResponse.slug
            // 保存跳转用的 slug（从 events[0].slug 获取）
            val eventSlug = marketResponse.getEventSlug()
            
            val market = if (existingMarket != null) {
                // 更新现有市场信息
                existingMarket.copy(
                    title = marketResponse.question ?: existingMarket.title,
                    slug = slug ?: existingMarket.slug,
                    eventSlug = eventSlug ?: existingMarket.eventSlug,
                    category = marketResponse.category ?: existingMarket.category,
                    takerFeeRate = resolveTakerFeeRate(marketResponse) ?: existingMarket.takerFeeRate,
                    icon = marketResponse.icon ?: existingMarket.icon,
                    image = marketResponse.image ?: existingMarket.image,
                    description = marketResponse.description ?: existingMarket.description,
                    active = marketResponse.active ?: existingMarket.active,
                    closed = marketResponse.closed ?: existingMarket.closed,
                    archived = marketResponse.archived ?: existingMarket.archived,
                    endDate = parseEndDate(marketResponse.endDate),
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                // 创建新市场信息
                Market(
                    marketId = marketId,
                    title = marketResponse.question ?: marketId,
                    slug = slug,
                    eventSlug = eventSlug,
                    category = marketResponse.category,
                    takerFeeRate = resolveTakerFeeRate(marketResponse),
                    icon = marketResponse.icon,
                    image = marketResponse.image,
                    description = marketResponse.description,
                    active = marketResponse.active ?: true,
                    closed = marketResponse.closed ?: false,
                    archived = marketResponse.archived ?: false,
                    endDate = parseEndDate(marketResponse.endDate),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            }
            
            val savedMarket = marketRepository.save(market)
            marketCache.put(marketId, savedMarket)
            savedMarket
        } catch (e: Exception) {
            logger.error("保存市场信息失败: marketId=$marketId, error=${e.message}", e)
            null
        }
    }
    
    /**
     * 解析市场 taker 费率：优先 feeSchedule.rate；明确关闭手续费时为 0；否则按 feeType/category 兜底
     */
    private fun resolveTakerFeeRate(marketResponse: MarketResponse): java.math.BigDecimal? {
        if (marketResponse.feesEnabled == false) return java.math.BigDecimal.ZERO
        marketResponse.feeSchedule?.rate?.let { return it }
        val fallback = PolymarketTradingFee.fallbackRate(marketResponse.feeType, marketResponse.category)
        if (fallback > java.math.BigDecimal.ZERO) return fallback
        if (marketResponse.feesEnabled == true) {
            logger.warn(
                "市场开启了手续费但未返回 feeSchedule/feeType，费率暂按未知处理: marketId={}, feeType={}",
                marketResponse.conditionId, marketResponse.feeType
            )
        }
        // 未知：保留数据库已有费率，避免把已知费率覆盖成 0
        return null
    }

    /**
     * 查询市场 taker 费率（用于盈亏中的手续费计算），取不到时返回 null（未知），由调用方决定如何处理。
     * 数据库没有费率时会刷新市场信息（含已结束市场）；刷新失败按 [FEE_RATE_REFRESH_INTERVAL_MS] 节流后重试，
     * 仍拿不到时按已存分类兜底（仅当兜底费率 > 0）。
     */
    fun findTakerFeeRate(marketId: String): java.math.BigDecimal? {
        val cached = getMarket(marketId)
        cached?.takerFeeRate?.let { return it }

        // 没有费率（老数据或上次查询缺字段）：按时间节流刷新，避免反复请求 Gamma，同时保证失败后能重试
        val now = System.currentTimeMillis()
        val lastAttempt = feeRateRefreshAttemptedAt[marketId]
        val shouldRefresh = cached != null &&
            (lastAttempt == null || now - lastAttempt >= FEE_RATE_REFRESH_INTERVAL_MS)
        val refreshed = if (shouldRefresh) {
            feeRateRefreshAttemptedAt[marketId] = now
            runBlocking {
                try {
                    fetchAndSaveMarket(marketId)
                } catch (e: Exception) {
                    logger.warn("刷新市场费率失败: marketId=$marketId, error=${e.message}")
                    null
                }
            }
        } else {
            null
        }
        val market = refreshed ?: cached
        market?.takerFeeRate?.let { return it }
        val fallback = PolymarketTradingFee.fallbackRate(null, market?.category)
        return fallback.takeIf { it > java.math.BigDecimal.ZERO }
    }

    /**
     * 获取市场 taker 费率（兼容旧调用方，返回非空）。
     * 费率未知时返回 0 并告警；需要区分"未知"的调用方请改用 [findTakerFeeRate]。
     */
    fun getTakerFeeRate(marketId: String): java.math.BigDecimal {
        return findTakerFeeRate(marketId) ?: run {
            logger.warn("市场 taker 费率未知，本次按 0 计算手续费（结果可能偏高）: marketId=$marketId")
            java.math.BigDecimal.ZERO
        }
    }

    /**
     * 按 tokenId 从 Gamma 解析市场信息（conditionId、outcomeIndex）
     * 用于链上解析时 Gamma 失败、仅带 tokenId 的交易在 processBuyTrade 中补查市场
     */
    suspend fun getMarketInfoByTokenId(tokenId: String): MarketInfoByTokenId? {
        if (tokenId.isBlank()) return null
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            var response = gammaApi.listMarkets(
                conditionIds = null,
                clobTokenIds = listOf(tokenId),
                includeTag = null
            )
            if (!response.isSuccessful) return null
            if (response.body().isNullOrEmpty()) {
                // 已结束市场默认查询返回 []，需 closed=true 补查
                response = gammaApi.listMarkets(clobTokenIds = listOf(tokenId), closed = true)
                if (!response.isSuccessful || response.body().isNullOrEmpty()) return null
            }
            val market = response.body()!!.first()
            val conditionId = market.conditionId ?: return null
            val clobTokenIdsRaw = market.clobTokenIds ?: market.clob_token_ids
            val clobTokenIds = (clobTokenIdsRaw ?: "").parseStringArray()
            val outcomeIndex = clobTokenIds.indexOfFirst { it.equals(tokenId, ignoreCase = true) }.takeIf { it >= 0 }
                ?: return null
            val outcomes = market.outcomes.parseStringArray()
            val outcome = if (outcomeIndex < outcomes.size) outcomes[outcomeIndex] else null
            saveMarketFromResponse(conditionId, market)
            MarketInfoByTokenId(conditionId = conditionId, outcomeIndex = outcomeIndex, outcome = outcome)
        } catch (e: Exception) {
            logger.warn("按 tokenId 查询市场失败: tokenId=$tokenId, error=${e.message}")
            null
        }
    }

    /**
     * 清除缓存（用于测试或手动刷新）
     */
    fun clearCache() {
        marketCache.invalidateAll()
    }
    
    /**
     * 解析市场截止时间（ISO 8601 格式）
     */
    private fun parseEndDate(endDate: String?): Long? {
        if (endDate.isNullOrBlank()) {
            return null
        }
        
        return try {
            // ISO 8601 格式，例如：2025-03-15T12:00:00Z
            Instant.parse(endDate).toEpochMilli()
        } catch (e: Exception) {
            logger.warn("解析市场截止时间失败: endDate=$endDate, error=${e.message}")
            null
        }
    }

    /**
     * 根据 conditionId 查询该市场是否为 Neg Risk（需使用 Neg Risk Exchange 签约）
     * 用于跟单下单时选择正确的 exchange 合约，避免 invalid signature。
     * 查询失败、市场不存在或 negRisk 字段缺失时返回失败，调用方不得把失败当作 false。
     */
    suspend fun fetchNegRiskByConditionId(conditionId: String): Result<Boolean> {
        if (conditionId.isBlank()) return Result.failure(IllegalArgumentException("conditionId 为空"))
        return try {
            val marketResponse = queryMarketsByConditionIds(listOf(conditionId))[conditionId]
                ?: return Result.failure(IllegalStateException("Gamma 未找到市场: conditionId=$conditionId"))
            val fromEvent = marketResponse.events?.firstOrNull()?.negRisk
            val fromMarket = marketResponse.negRisk ?: marketResponse.negRiskOther
            val negRisk = fromEvent ?: fromMarket
                ?: return Result.failure(IllegalStateException("Gamma 市场缺少 negRisk 字段: conditionId=$conditionId"))
            Result.success(negRisk)
        } catch (e: Exception) {
            logger.warn("查询市场 negRisk 失败: conditionId=$conditionId, error=${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 兼容旧调用方：失败返回 null。
     * 注意 null 表示"未知"而不是 false，新代码请使用 [fetchNegRiskByConditionId] 并在失败时中止/重试。
     */
    suspend fun getNegRiskByConditionId(conditionId: String): Boolean? {
        return fetchNegRiskByConditionId(conditionId).getOrNull()
    }
}

/**
 * 按 tokenId 查询 Gamma 得到的市场信息（用于补全 trade.market / outcomeIndex）
 */
data class MarketInfoByTokenId(
    val conditionId: String,
    val outcomeIndex: Int,
    val outcome: String? = null
)
