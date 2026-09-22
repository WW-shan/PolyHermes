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
    internal suspend fun handleAccountTransaction(accountId: Long, txHash: String, httpClient: OkHttpClient, rpcApi: EthereumRpcApi) {
        val account = monitoredAccounts[accountId] ?: return

        val dedupKey = "$accountId:${txHash.lowercase()}"
        val lock = transactionLocks[(dedupKey.hashCode() and Int.MAX_VALUE) % transactionLocks.size]

        lock.withLock {
            if (processedTxHashes.getIfPresent(dedupKey) != null) {
                logger.debug("链上交易已处理过，跳过重复通知: accountId=$accountId, txHash=$txHash")
                return
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
        val blockNumber = receiptJson.get("blockNumber")?.asString
        val blockTimestamp = if (blockNumber != null) {
            OnChainWsUtils.getBlockTimestamp(blockNumber, rpcApi)
        } else {
            null
        }

        val logs = receiptJson.getAsJsonArray("logs") ?: run {
            logger.warn("账户交易 receipt 缺少 logs，稍后允许重试: txHash=$txHash")
            return false
        }
        val (erc20Transfers, erc1155Transfers) = OnChainWsUtils.parseReceiptTransfers(logs)
        val trade = OnChainWsUtils.parseTradeFromTransfers(
            txHash = txHash,
            timestamp = blockTimestamp,
            walletAddress = account.proxyAddress,
            erc20Transfers = erc20Transfers,
            erc1155Transfers = erc1155Transfers,
            retrofitFactory = retrofitFactory
        )

        if (trade == null) {
            // 没有可识别的成交（例如普通转账/买入）时无需记账；但存在账户发出的
            // ERC1155 转移却无法解析时可能是暂时缺少价格数据，应允许后续通知重试。
            val wallet = account.proxyAddress.lowercase()
            return erc1155Transfers.none { it.from.lowercase() == wallet }
        }
        if (trade.side != "SELL") {
            return true
        }
        if (trade.market.isBlank() || trade.outcomeIndex == null) {
            logger.warn("账户卖出交易缺少市场或 outcome 信息，稍后允许重试: txHash=$txHash, market=${trade.market}, outcomeIndex=${trade.outcomeIndex}")
            return false
        }

        getSelf().handleAccountSellOrRedeem(account, trade, txHash)
        return true
    }
    
    /**
     * 处理账户的卖出或赎回事件
     * 更新对应的订单状态
     */
    @Transactional
    suspend fun handleAccountSellOrRedeem(account: Account, trade: TradeResponse, txHash: String) {
        // 获取该账户的所有启用的跟单配置
        val copyTradings = copyTradingRepository.findByAccountId(account.id!!)
            .filter { it.enabled }

        if (copyTradings.isEmpty()) {
            return
        }

        val marketId = trade.market // conditionId
        val outcomeIndex = trade.outcomeIndex ?: return
        val sellPrice = trade.price.toSafeBigDecimal()

        // 为每个跟单配置更新订单状态
        for (copyTrading in copyTradings) {
            // 查找该跟单配置下所有未卖出的订单（remaining_quantity > 0）
            val unmatchedOrders = copyOrderTrackingRepository.findByCopyTradingId(copyTrading.id!!)
                .filter {
                    it.remainingQuantity > BigDecimal.ZERO &&
                    it.marketId == marketId &&
                    it.outcomeIndex == outcomeIndex
                }
                .sortedBy { it.createdAt } // 按创建时间排序（FIFO）

            if (unmatchedOrders.isEmpty()) {
                continue
            }

            // 数据库幂等兜底：同一跟单关系 + 同一笔链上交易 + 同一市场已记账则跳过
            // （内存去重只在单进程有效，这里保证重启后也不会重复计入盈亏）
            if (sellMatchRecordRepository.existsByCopyTradingIdAndSourceTxHashAndMarketId(
                    copyTrading.id!!, txHash, marketId
                )
            ) {
                logger.debug("链上交易已记账，跳过重复处理: copyTradingId=${copyTrading.id}, txHash=$txHash, marketId=$marketId")
                continue
            }

            // 卖出数量就是交易的 size
            val soldQuantity = trade.size.toSafeBigDecimal()

            // 更新订单状态为已卖出
            updateOrdersAsSoldByFIFO(
                unmatchedOrders,
                soldQuantity,
                sellPrice,
                copyTrading.id!!,
                marketId,
                outcomeIndex,
                txHash
            )

            logger.info("跟单账户卖出/赎回事件处理完成: accountId=${account.id}, copyTradingId=${copyTrading.id}, txHash=${trade.id}, soldQuantity=$soldQuantity, sellPrice=$sellPrice")
        }
    }
    
    /**
     * 按 FIFO 顺序更新订单为已卖出
     */
    private suspend fun updateOrdersAsSoldByFIFO(
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
        
        for (order in orders) {
            if (remainingSoldQuantity <= BigDecimal.ZERO) {
                break
            }
            
            val currentOrderRemaining = order.remainingQuantity.toSafeBigDecimal()
            val matchedQty = minOf(currentOrderRemaining, remainingSoldQuantity)
            
            if (matchedQty <= BigDecimal.ZERO) {
                continue
            }
            
            // 计算盈亏
            val buyPrice = order.price.toSafeBigDecimal()
            val realizedPnl = sellPrice.subtract(buyPrice).multi(matchedQty)
            
            // 创建匹配明细
            val detail = SellMatchDetail(
                matchRecordId = 0, // 稍后设置
                trackingId = order.id!!,
                buyOrderId = order.buyOrderId,
                matchedQuantity = matchedQty,
                buyPrice = buyPrice,
                sellPrice = sellPrice,
                realizedPnl = realizedPnl
            )
            matchDetails.add(detail)
            
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
            val sellOrderId = "AUTO_WS_${timestamp}_${copyTradingId}" // 区分 WS 自动卖出
            val leaderSellTradeId = "AUTO_WS_${txHash}"
            
            val matchRecord = SellMatchRecord(
                copyTradingId = copyTradingId,
                sellOrderId = sellOrderId,
                leaderSellTradeId = leaderSellTradeId,
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
            
            // 保存匹配明细
            for (detail in matchDetails) {
                val savedDetail = detail.copy(matchRecordId = savedRecord.id!!)
                sellMatchDetailRepository.save(savedDetail)
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
}
