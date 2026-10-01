package com.wrbug.polymarketbot.service.copytrading.monitor

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.wrbug.polymarketbot.api.*
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.entity.SellMatchRecord
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 跟单账户链上 WebSocket 监听服务
 * 通过统一服务订阅跟单账户的卖出和赎回事件
 * 用于更新订单状态，不再依赖轮询
 */
@Service
class AccountOnChainMonitorService(
    private val unifiedOnChainWsService: UnifiedOnChainWsService,
    private val retrofitFactory: RetrofitFactory,
    private val accountRepository: AccountRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository
) : ApplicationContextAware {
    
    private val logger = LoggerFactory.getLogger(AccountOnChainMonitorService::class.java)
    
    // 存储需要监听的账户：accountId -> Account
    private val monitoredAccounts = ConcurrentHashMap<Long, Account>()

    private var applicationContext: ApplicationContext? = null

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }

    /**
     * 获取代理对象，用于解决 @Transactional 自调用问题。
     * 链上 RPC 请求不应包在数据库事务中，因此只在确认存在可记账的 SELL 后开启事务。
     */
    private fun getSelf(): AccountOnChainMonitorService {
        return applicationContext?.getBean(AccountOnChainMonitorService::class.java)
            ?: throw IllegalStateException("ApplicationContext not initialized")
    }

    /**
     * 已成功处理的链上交易哈希（幂等去重）：key = "accountId:txHash"。
     * UnifiedOnChainWsService 对同一笔交易的每一条 log 都会触发一次回调（并发），
     * 不去重会导致同一笔卖出/赎回被重复记账（issue #61）。
     *
     * 只在处理完成或确认交易与跟单无关后写入；RPC 暂时失败时不能缓存失败结果，
     * 否则同一交易的后续 log 通知会被跳过，造成漏记。
     */
    private val processedTxHashes: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .build()

    // 按 hash 分片锁住同一 tx 的并发回调；固定数量的锁避免为每笔交易长期保存 Mutex。
    private val transactionLocks = Array(128) { Mutex() }

    // 发现未知订单 hash 的卖出时，等待多久再确认一次是否为本系统订单（测试中可置 0）
    internal var selfOrderRecheckDelayMs = 15_000L
    
    /**
     * 启动链上 WebSocket 监听
     * 通过统一服务订阅所有跟单账户
     */
    fun start(accounts: List<Account>) {
        // 如果没有账户，取消所有订阅
        if (accounts.isEmpty()) {
            logger.info("没有需要监听的跟单账户，取消所有订阅")
            stop()
            return
        }
        
        // 更新账户列表
        monitoredAccounts.clear()
        accounts.forEach { account ->
            addAccount(account)
        }
    }
    
    /**
     * 添加账户监听
     * 通过统一服务订阅该账户的地址
     */
    fun addAccount(account: Account) {
        if (account.id == null) {
            logger.warn("账户 ID 为空，跳过: ${account.proxyAddress}")
            return
        }
        
        val accountId = account.id!!
        
        // 如果已经在监听列表中，不重复添加
        if (monitoredAccounts.containsKey(accountId)) {
            return
        }
        
        monitoredAccounts[accountId] = account
        
        // 通过统一服务订阅
        val subscriptionId = "ACCOUNT_$accountId"
        unifiedOnChainWsService.subscribe(
            subscriptionId = subscriptionId,
            address = account.proxyAddress,
            entityType = "ACCOUNT",
            entityId = accountId,
            callback = { txHash, httpClient, rpcApi ->
                handleAccountTransaction(accountId, txHash, httpClient, rpcApi)
            }
        )
        
        logger.info("已添加跟单账户进行链上监听: accountId=${accountId}, address=${account.proxyAddress}")
    }
    
    /**
     * 处理账户的交易
     */
    internal suspend fun handleAccountTransaction(accountId: Long, txHash: String, httpClient: OkHttpClient, rpcApi: EthereumRpcApi): Boolean {
        val account = monitoredAccounts[accountId] ?: return true

        val dedupKey = "$accountId:${txHash.lowercase()}"
        val lock = transactionLocks[(dedupKey.hashCode() and Int.MAX_VALUE) % transactionLocks.size]

        return lock.withLock {
            if (processedTxHashes.getIfPresent(dedupKey) != null) {
                logger.debug("链上交易已处理过，跳过重复通知: accountId=$accountId, txHash=$txHash")
                return@withLock true
            }

            val handled = try {
                processAccountTransaction(account, txHash, rpcApi)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("处理账户交易失败: accountId=$accountId, txHash=$txHash, ${e.message}", e)
                false
            }

            if (handled) {
                processedTxHashes.put(dedupKey, System.currentTimeMillis())
            }
            handled
        }
    }

    /**
     * 返回 true 表示交易已处理完成，或已确认与当前跟单监听无关。
     * 返回 false 表示数据暂时不可用，不能写入幂等缓存，应允许后续通知重试。
     */
    private suspend fun processAccountTransaction(
        account: Account,
        txHash: String,
        rpcApi: EthereumRpcApi
    ): Boolean {
        val receiptRequest = JsonRpcRequest(
            method = "eth_getTransactionReceipt",
            params = listOf(txHash)
        )

        val receiptResponse = rpcApi.call(receiptRequest)
        if (!receiptResponse.isSuccessful || receiptResponse.body() == null) {
            logger.warn("获取账户交易 receipt 失败，稍后允许重试: txHash=$txHash, code=${receiptResponse.code()}")
            return false
        }

        val receiptRpcResponse = receiptResponse.body()!!
        if (receiptRpcResponse.error != null || receiptRpcResponse.result == null || receiptRpcResponse.result.isJsonNull) {
            logger.warn("账户交易 receipt 暂无结果，稍后允许重试: txHash=$txHash, rpcError=${receiptRpcResponse.error?.message}")
            return false
        }

        val receiptJson = receiptRpcResponse.result.asJsonObject
        val logs = receiptJson.getAsJsonArray("logs") ?: run {
            logger.warn("账户交易 receipt 缺少 logs，稍后允许重试: txHash=$txHash")
            return false
        }

        // 只以 V2 交易所发出的 OrderFilled 为准；只关心账户自己订单（maker=账户）的卖出成交
        val wallet = account.proxyAddress.lowercase()
        val sellEvents = OnChainWsUtils.parseOrderFilledEvents(logs, txHash)
            .filter { it.maker.lowercase() == wallet && it.side == 1 }
        if (sellEvents.isEmpty()) {
            return true
        }

        // 本系统下的跟单卖单已由下单流程记账（SellMatchRecord.sellOrderId = CLOB orderID = 订单 hash），直接跳过
        var externalEvents = excludeSystemOrders(sellEvents)
        if (externalEvents.isNotEmpty() && selfOrderRecheckDelayMs > 0) {
            // 下单流程可能尚未写入 SellMatchRecord，等待后再确认一次，避免把自己的卖单当成外部卖出
            delay(selfOrderRecheckDelayMs)
            externalEvents = excludeSystemOrders(externalEvents)
        }
        if (externalEvents.isEmpty()) {
            logger.debug("账户链上卖出均为本系统跟单订单，跳过: accountId=${account.id}, txHash=$txHash")
            return true
        }

        val blockTimestamp = receiptJson.get("blockNumber")?.asString?.let { OnChainWsUtils.getBlockTimestamp(it, rpcApi) }
        // 按 tokenId 分别处理（同一 tx 可能卖出多个 token）
        val groups = OnChainWsUtils.aggregateWalletFills(externalEvents, wallet)
        for (group in groups) {
            val marketInfo = OnChainWsUtils.fetchMarketByTokenId(group.tokenId.toString(), retrofitFactory)
            if (marketInfo == null || marketInfo.outcomeIndex == null) {
                logger.warn("账户卖出交易缺少市场或 outcome 信息，稍后允许重试: txHash=$txHash, tokenId=${group.tokenId}")
                return false
            }
            val trade = OnChainWsUtils.toTradeResponse(group, blockTimestamp, account.proxyAddress, marketInfo)
            getSelf().handleAccountSellOrRedeem(account, trade, txHash)
        }
        return true
    }

    /**
     * 过滤掉本系统下的卖单成交（orderHash 与 SellMatchRecord.sellOrderId 比对，统一小写 + 0x 前缀）
     */
    private fun excludeSystemOrders(events: List<OnChainWsUtils.OrderFilledEvent>): List<OnChainWsUtils.OrderFilledEvent> {
        val hashes = events.map { OnChainWsUtils.normalizeHash(it.orderHash) }.distinct()
        if (hashes.isEmpty()) return events
        val queryIds = (hashes + hashes.map { it.removePrefix("0x") }).distinct()
        val systemOrderIds = sellMatchRecordRepository.findBySellOrderIdIn(queryIds)
            .map { OnChainWsUtils.normalizeHash(it.sellOrderId) }
            .toSet()
        return events.filter { OnChainWsUtils.normalizeHash(it.orderHash) !in systemOrderIds }

    }
    
    /**
     * 处理账户的卖出或赎回事件
     * 更新对应的订单状态
     *
     * 该方法内部只有阻塞式数据库操作，保持为普通方法，避免 Spring 事务绑定到
     * suspend 协程恢复后的不同线程而失效。
     */
    @Transactional
    fun handleAccountSellOrRedeem(account: Account, trade: TradeResponse, txHash: String) {
        // 账户下所有跟单配置（包括已禁用的：禁用只代表不再跟新单，持仓减少仍需记账结算）
        val copyTradings = copyTradingRepository.findByAccountId(account.id!!)
        if (copyTradings.isEmpty()) {
            return
        }

        val marketId = trade.market // conditionId
        val outcomeIndex = trade.outcomeIndex ?: return
        val sellPrice = trade.price.toSafeBigDecimal()
        val soldQuantity = trade.size.toSafeBigDecimal()
        if (soldQuantity <= BigDecimal.ZERO) {
            return
        }

        // 收集每个配置在该 token 上可卖的持仓（pending/unconfirmed 不计入）
        val sellableByConfig = LinkedHashMap<Long, List<CopyOrderTracking>>()
        for (copyTrading in copyTradings) {
            val copyTradingId = copyTrading.id ?: continue
            // 数据库幂等兜底：同一跟单关系 + 同一笔链上交易 + 同一市场/outcome 已记账则跳过
            if (sellMatchRecordRepository.existsByCopyTradingIdAndSourceTxHashAndMarketIdAndOutcomeIndex(
                    copyTradingId, txHash, marketId, outcomeIndex
                )
            ) {
                logger.debug("链上交易已记账，跳过重复处理: copyTradingId=$copyTradingId, txHash=$txHash, marketId=$marketId, outcomeIndex=$outcomeIndex")
                continue
            }
            val orders = copyOrderTrackingRepository.findByCopyTradingId(copyTradingId)
                .filter { isSellable(it) && it.marketId == marketId && it.outcomeIndex == outcomeIndex }
                .sortedBy { it.createdAt } // FIFO
            if (orders.isNotEmpty()) {
                sellableByConfig[copyTradingId] = orders
            }
        }
        if (sellableByConfig.isEmpty()) {
            return
        }

        // 外部卖出在所有配置间按剩余量分摊一次（而不是每个配置各扣全额）
        val allocations = allocateByRemaining(
            sellableByConfig.mapValues { (_, orders) -> orders.fold(BigDecimal.ZERO) { acc, o -> acc.add(o.remainingQuantity) } },
            soldQuantity
        )
        for ((copyTradingId, allocated) in allocations) {
            if (allocated <= BigDecimal.ZERO) continue
            updateOrdersAsSoldByFIFO(
                sellableByConfig[copyTradingId] ?: continue,
                allocated,
                sellPrice,
                copyTradingId,
                marketId,
                outcomeIndex,
                txHash
            )
            logger.info("跟单账户外部卖出事件处理完成: accountId=${account.id}, copyTradingId=$copyTradingId, txHash=$txHash, allocated=$allocated, totalSold=$soldQuantity, sellPrice=$sellPrice")
        }
    }

    /**
     * 可被链上卖出扣减的跟单持仓：剩余数量 > 0，且不是 pending/unconfirmed（未确认成交的行数量为 0，不能卖）
     */
    private fun isSellable(order: CopyOrderTracking): Boolean {
        return order.remainingQuantity > BigDecimal.ZERO &&
            !order.status.equals(STATUS_PENDING, ignoreCase = true) &&
            !order.status.equals(STATUS_UNCONFIRMED, ignoreCase = true)
    }

    /**
     * 按各配置剩余量比例分摊卖出数量；卖出量不小于总剩余时各配置全部扣完。
     * 比例分摊向下取整到 8 位，尾差给最后一个配置（不超过其剩余量）。
     */
    internal fun allocateByRemaining(remainingByConfig: Map<Long, BigDecimal>, soldQuantity: BigDecimal): Map<Long, BigDecimal> {
        val total = remainingByConfig.values.fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
        if (total <= BigDecimal.ZERO) return emptyMap()
        if (soldQuantity >= total) return remainingByConfig
        val result = LinkedHashMap<Long, BigDecimal>()
        var allocatedSum = BigDecimal.ZERO
        val entries = remainingByConfig.entries.toList()
        entries.forEachIndexed { index, (copyTradingId, remaining) ->
            val share = if (index == entries.lastIndex) {
                soldQuantity.subtract(allocatedSum).min(remaining)
            } else {
                soldQuantity.multiply(remaining).divide(total, 8, java.math.RoundingMode.DOWN).min(remaining)
            }
            result[copyTradingId] = share.max(BigDecimal.ZERO)
            allocatedSum = allocatedSum.add(share)
        }
        return result
    }

    /**
     * 按 FIFO 顺序更新订单为已卖出
     * 每次保存前重新读取 tracking（并发的跟单卖出/确认流程可能已修改），只改动数量与状态字段
     */
    private fun updateOrdersAsSoldByFIFO(
        orders: List<CopyOrderTracking>,
        soldQuantity: BigDecimal,
        sellPrice: BigDecimal,
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int,
        txHash: String
    ) {
        var remainingSoldQuantity = soldQuantity
        val matchDetails = mutableListOf<SellMatchDetail>()
        var totalMatchedQuantity = BigDecimal.ZERO
        var totalRealizedPnl = BigDecimal.ZERO

        for (candidate in orders) {
            if (remainingSoldQuantity <= BigDecimal.ZERO) {
                break
            }
            val order = candidate.id?.let { copyOrderTrackingRepository.findById(it).orElse(null) } ?: continue
            if (!isSellable(order)) {
                continue
            }

            val currentOrderRemaining = order.remainingQuantity.toSafeBigDecimal()
            val matchedQty = minOf(currentOrderRemaining, remainingSoldQuantity)
            if (matchedQty <= BigDecimal.ZERO) {
                continue
            }

            // 计算盈亏
            val buyPrice = order.price.toSafeBigDecimal()
            val realizedPnl = sellPrice.subtract(buyPrice).multi(matchedQty)

            matchDetails.add(
                SellMatchDetail(
                    matchRecordId = 0, // 稍后设置
                    trackingId = order.id!!,
                    buyOrderId = order.buyOrderId,
                    matchedQuantity = matchedQty,
                    buyPrice = buyPrice,
                    sellPrice = sellPrice,
                    realizedPnl = realizedPnl
                )
            )
            totalMatchedQuantity = totalMatchedQuantity.add(matchedQty)
            totalRealizedPnl = totalRealizedPnl.add(realizedPnl)

            // 更新订单状态
            order.matchedQuantity = order.matchedQuantity.add(matchedQty)
            order.remainingQuantity = currentOrderRemaining.subtract(matchedQty)
            order.status = if (order.remainingQuantity <= BigDecimal.ZERO) "fully_matched" else "partially_matched"
            order.updatedAt = System.currentTimeMillis()
            copyOrderTrackingRepository.save(order)

            remainingSoldQuantity = remainingSoldQuantity.subtract(matchedQty)
        }

        // 如果有匹配的订单，创建卖出记录
        if (totalMatchedQuantity > BigDecimal.ZERO && matchDetails.isNotEmpty()) {
            val timestamp = System.currentTimeMillis()
            val matchRecord = SellMatchRecord(
                copyTradingId = copyTradingId,
                sellOrderId = "AUTO_WS_${timestamp}_${copyTradingId}", // 区分 WS 自动卖出
                leaderSellTradeId = "AUTO_WS_${txHash}",
                sourceTxHash = txHash,
                marketId = marketId,
                side = outcomeIndex.toString(),
                outcomeIndex = outcomeIndex,
                totalMatchedQuantity = totalMatchedQuantity,
                sellPrice = sellPrice,
                totalRealizedPnl = totalRealizedPnl,
                priceUpdated = true // WS 实时获取，直接标记为已更新
            )
            val savedRecord = sellMatchRecordRepository.save(matchRecord)
            for (detail in matchDetails) {
                sellMatchDetailRepository.save(detail.copy(matchRecordId = savedRecord.id!!))
            }
            logger.info("创建跟单账户链上自动卖出记录: copyTradingId=$copyTradingId, marketId=$marketId, totalMatched=$totalMatchedQuantity, totalPnl=$totalRealizedPnl")
        }
    }

    /**
     * 移除账户监听
     * 取消该账户的订阅
     */
    fun removeAccount(accountId: Long) {
        monitoredAccounts.remove(accountId)
        
        // 通过统一服务取消订阅
        val subscriptionId = "ACCOUNT_$accountId"
        unifiedOnChainWsService.unsubscribe(subscriptionId)
        
        logger.info("已移除跟单账户的链上监听: accountId=$accountId")
    }
    
    /**
     * 更新账户监听状态
     */
    fun updateAccountMonitoring(accountId: Long) {
        val account = accountRepository.findById(accountId).orElse(null)
        if (account != null && account.isEnabled) {
            addAccount(account)
        } else {
            removeAccount(accountId)
        }
    }
    
    /**
     * 当前监听中的账户 ID
     */
    fun getMonitoredAccountIds(): Set<Long> = monitoredAccounts.keys.toSet()

    /**
     * 停止监听
     */
    fun stop() {
        // 取消所有账户的订阅
        val accountIds = monitoredAccounts.keys.toList()
        for (accountId in accountIds) {
            removeAccount(accountId)
        }
        monitoredAccounts.clear()
    }
    
    @PreDestroy
    fun destroy() {
        stop()
    }

    companion object {
        // 与 CopyOrderTracking 状态约定一致：pending/unconfirmed 表示成交未确认，数量为 0，不可卖
        private const val STATUS_PENDING = "pending"
        private const val STATUS_UNCONFIRMED = "unconfirmed"
    }
}
