package com.wrbug.polymarketbot.service.copytrading.monitor

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.JsonNull
import com.wrbug.polymarketbot.api.*
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import com.wrbug.polymarketbot.util.RetrofitFactory
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 链上 WebSocket 监听服务
 * 通过统一服务订阅 Leader 的链上交易
 */
@Service
class OnChainWsService(
    private val unifiedOnChainWsService: UnifiedOnChainWsService,
    private val retrofitFactory: RetrofitFactory,
    private val copyOrderTrackingService: CopyOrderTrackingService,
    private val leaderRepository: LeaderRepository
) {

    private val logger = LoggerFactory.getLogger(OnChainWsService::class.java)

    // 存储需要监听的Leader：leaderId -> Leader
    private val monitoredLeaders = ConcurrentHashMap<Long, Leader>()

    // 已完整处理的交易：key = "leaderId:txHash"（按 Leader 区分，同一 tx 的多个 Leader 互不影响）
    private val processedTxHashes: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .build()

    // 已交给下游的成交：key = "leaderId:tradeId"，避免部分成功后重试时重复交付
    private val deliveredTradeKeys: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .build()

    // 按 hash 分片锁住同一 (leader, tx) 的并发回调
    private val transactionLocks = Array(64) { Mutex() }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 按 Leader 串行交付，保证同一 Leader 的 BUY/SELL 顺序
    private val dispatcher = LeaderTradeDispatcher(scope) { leaderId, trade, source ->
        copyOrderTrackingService.processTrade(leaderId = leaderId, trade = trade, source = source)
    }

    // 临时失败时的重试参数（测试中可调小）
    internal var maxProcessAttempts = 3
    internal var retryDelayMs = 2000L

    /**
     * 启动链上 WebSocket 监听
     * 通过统一服务订阅所有 Leader
     */
    fun start(leaders: List<Leader>) {
        // 如果没有 Leader，取消所有订阅
        if (leaders.isEmpty()) {
            logger.info("没有需要监听的 Leader，取消所有订阅")
            stop()
            return
        }

        // 更新 Leader 列表
        monitoredLeaders.clear()
        leaders.forEach { leader ->
            addLeader(leader)
        }
    }

    /**
     * 添加Leader监听
     * 通过统一服务订阅该 Leader 的地址
     */
    fun addLeader(leader: Leader) {
        if (leader.id == null) {
            logger.warn("Leader ID为空，跳过: ${leader.leaderAddress}")
            return
        }

        val leaderId = leader.id!!

        // 如果已经在监听列表中，不重复添加
        if (monitoredLeaders.containsKey(leaderId)) {
            logger.debug("Leader 已在监听列表中: ${leader.leaderName} (${leader.leaderAddress})")
            return
        }

        monitoredLeaders[leaderId] = leader

        // 通过统一服务订阅
        val subscriptionId = "LEADER_$leaderId"
        unifiedOnChainWsService.subscribe(
            subscriptionId = subscriptionId,
            address = leader.leaderAddress,
            entityType = "LEADER",
            entityId = leaderId,
            callback = { txHash, httpClient, rpcApi ->
                handleLeaderTransaction(leaderId, txHash, httpClient, rpcApi)
            }
        )

        logger.info("添加 Leader 监听: ${leader.leaderName} (${leader.leaderAddress})")
    }

    /**
     * 处理 Leader 的交易
     * 同一 (leaderId, txHash) 的并发通知串行处理；只有全部成交都成功交给下游后才写入去重缓存，
     * 临时失败（receipt 未就绪、Gamma 查询失败）会在短暂等待后重试，不会永久丢单。
     */
    internal suspend fun handleLeaderTransaction(
        leaderId: Long,
        txHash: String,
        httpClient: OkHttpClient,
        rpcApi: EthereumRpcApi
    ): Boolean {
        val leader = monitoredLeaders[leaderId] ?: return true
        val txKey = "$leaderId:${OnChainWsUtils.normalizeHash(txHash)}"
        val lock = transactionLocks[(txKey.hashCode() and Int.MAX_VALUE) % transactionLocks.size]

        return lock.withLock {
            if (processedTxHashes.getIfPresent(txKey) != null) {
                logger.debug("交易已处理过，跳过: leaderId=$leaderId, txHash=$txHash")
                return@withLock true
            }
            var attempt = 0
            var handled = false
            while (attempt < maxProcessAttempts) {
                val done = try {
                    processLeaderTransaction(leaderId, leader, txHash, rpcApi)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("处理 Leader 交易失败: leaderId=$leaderId, txHash=$txHash, ${e.message}", e)
                    false
                }
                if (done) {
                    processedTxHashes.put(txKey, System.currentTimeMillis())
                    handled = true
                    break
                }
                attempt++
                if (attempt < maxProcessAttempts) {
                    delay(retryDelayMs)
                }
            }
            if (!handled) logger.warn("Leader 交易暂时无法处理，等待后续通知/补数据重试: leaderId=$leaderId, txHash=$txHash")
            handled
        }
    }

    /**
     * 返回 true 表示已处理完成（或确认不是交易）；false 表示暂时失败，不应写入去重缓存
     */
    private suspend fun processLeaderTransaction(
        leaderId: Long,
        leader: Leader,
        txHash: String,
        rpcApi: EthereumRpcApi
    ): Boolean {
        val receiptResponse = rpcApi.call(JsonRpcRequest(method = "eth_getTransactionReceipt", params = listOf(txHash)))
        if (!receiptResponse.isSuccessful || receiptResponse.body() == null) {
            logger.warn("获取交易 receipt 失败: leaderId=$leaderId, txHash=$txHash, code=${receiptResponse.code()}")
            return false
        }
        val receiptRpcResponse = receiptResponse.body()!!
        if (receiptRpcResponse.error != null || receiptRpcResponse.result == null || receiptRpcResponse.result is JsonNull) {
            logger.warn("交易 receipt 暂无结果: leaderId=$leaderId, txHash=$txHash, error=${receiptRpcResponse.error}")
            return false
        }
        val receiptJson = receiptRpcResponse.result.asJsonObject
        val logs = receiptJson.getAsJsonArray("logs") ?: return false

        // 只以 V2 交易所发出的 OrderFilled 为准；split/merge/redeem/普通转账没有 OrderFilled，不是交易
        val events = OnChainWsUtils.parseOrderFilledEvents(logs, txHash)
        val groups = OnChainWsUtils.aggregateWalletFills(events, leader.leaderAddress)
        if (groups.isEmpty()) {
            logger.debug("交易中没有 Leader 的订单成交，忽略: leaderId=$leaderId, txHash=$txHash")
            return true
        }

        val blockTimestamp = receiptJson.get("blockNumber")?.asString?.let { OnChainWsUtils.getBlockTimestamp(it, rpcApi) }
        var allDelivered = true
        for (group in groups) {
            val tradeId = OnChainWsUtils.buildTradeId(txHash, group.tokenId.toString(), group.side)
            val deliveredKey = "$leaderId:$tradeId"
            if (deliveredTradeKeys.getIfPresent(deliveredKey) != null) continue

            // 缺 market/outcome 时不交给下游、不标记，允许下次重试
            val marketInfo = OnChainWsUtils.fetchMarketByTokenId(group.tokenId.toString(), retrofitFactory)
            if (marketInfo == null || marketInfo.outcomeIndex == null) {
                logger.warn("按 tokenId 查询市场失败，稍后重试: leaderId=$leaderId, txHash=$txHash, tokenId=${group.tokenId}")
                allDelivered = false
                continue
            }
            val trade = OnChainWsUtils.toTradeResponse(group, blockTimestamp, leader.leaderAddress, marketInfo)
            logger.info("成功解析交易: leaderId=$leaderId, txHash=$txHash, side=${trade.side}, market=${trade.market}, size=${trade.size}, price=${trade.price}, fee=${group.fee}")
            if (dispatcher.deliver(leaderId, trade, "onchain-ws")) {
                deliveredTradeKeys.put(deliveredKey, System.currentTimeMillis())
            } else {
                allDelivered = false
            }
        }
        return allDelivered
    }

    /**
     * 移除Leader监听
     * 取消该 Leader 的订阅
     */
    fun removeLeader(leaderId: Long) {
        monitoredLeaders.remove(leaderId)
        dispatcher.remove(leaderId)

        // 通过统一服务取消订阅
        val subscriptionId = "LEADER_$leaderId"
        unifiedOnChainWsService.unsubscribe(subscriptionId)

        logger.info("移除 Leader 监听: leaderId=$leaderId")
    }

    /**
     * 当前监听中的 Leader ID
     */
    fun getMonitoredLeaderIds(): Set<Long> = monitoredLeaders.keys.toSet()

    /**
     * 停止监听
     */
    fun stop() {
        // 取消所有 Leader 的订阅
        val leaderIds = monitoredLeaders.keys.toList()
        for (leaderId in leaderIds) {
            removeLeader(leaderId)
        }
        monitoredLeaders.clear()
    }

    @PreDestroy
    fun destroy() {
        stop()
        scope.cancel()
    }
}
