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
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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
) {
    
    private val logger = LoggerFactory.getLogger(AccountOnChainMonitorService::class.java)
    
    // 存储需要监听的账户：accountId -> Account
    private val monitoredAccounts = ConcurrentHashMap<Long, Account>()

    /**
     * 已处理的链上交易哈希（幂等去重）：key = "accountId:txHash"。
     * UnifiedOnChainWsService 对同一笔交易的每一条 log 都会触发一次回调（并发），
     * 不去重会导致同一笔卖出/赎回被重复记账（issue #61）。
     */
    private val processedTxHashes: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .build()
    
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

        // 幂等去重：同一笔链上交易会产生多条 log 通知（每条通知在独立协程中并发处理），
        // 只允许处理一次，否则 sell_match_record 与 matchedQuantity 会被重复累加（issue #61）。
        val dedupKey = "$accountId:${txHash.lowercase()}"
        if (processedTxHashes.asMap().putIfAbsent(dedupKey, System.currentTimeMillis()) != null) {
            logger.debug("链上交易已处理过，跳过重复通知: accountId=$accountId, txHash=$txHash")
            return
        }
        
        try {
            // 获取交易 receipt
            val receiptRequest = JsonRpcRequest(
                method = "eth_getTransactionReceipt",
                params = listOf(txHash)
            )
            
            val receiptResponse = rpcApi.call(receiptRequest)
            if (!receiptResponse.isSuccessful || receiptResponse.body() == null) {
                return
            }
            
            val receiptRpcResponse = receiptResponse.body()!!
            if (receiptRpcResponse.error != null || receiptRpcResponse.result == null || receiptRpcResponse.result.isJsonNull) {
                return
            }

            // 使用 Gson 解析 receipt JSON
            val receiptJson = receiptRpcResponse.result.asJsonObject
            
            // 获取区块号和时间戳
            val blockNumber = receiptJson.get("blockNumber")?.asString
            val blockTimestamp = if (blockNumber != null) {
                OnChainWsUtils.getBlockTimestamp(blockNumber, rpcApi)
            } else {
                null
            }
            
            // 解析 receipt 中的 Transfer 日志
            val logs = receiptJson.getAsJsonArray("logs") ?: return
            val (erc20Transfers, erc1155Transfers) = OnChainWsUtils.parseReceiptTransfers(logs)
            
            // 解析交易信息
            val trade = OnChainWsUtils.parseTradeFromTransfers(
                txHash = txHash,
                timestamp = blockTimestamp,
                walletAddress = account.proxyAddress,
                erc20Transfers = erc20Transfers,
                erc1155Transfers = erc1155Transfers,
                retrofitFactory = retrofitFactory
            )
            
            if (trade != null && trade.side == "SELL") {
                // 检测到卖出或赎回事件，更新订单状态
                handleAccountSellOrRedeem(account, trade, txHash)
            }
        } catch (e: Exception) {
            // 处理失败时释放幂等标记，允许链上重连补发时重试
            processedTxHashes.invalidate(dedupKey)
            logger.error("处理账户交易失败: accountId=$accountId, txHash=$txHash, ${e.message}", e)
        }
    }
    
    /**
     * 处理账户的卖出或赎回事件
     * 更新对应的订单状态
     */
    private suspend fun handleAccountSellOrRedeem(account: Account, trade: TradeResponse, txHash: String) {
        try {
            // 获取该账户的所有启用的跟单配置
            val copyTradings = copyTradingRepository.findByAccountId(account.id!!)
                .filter { it.enabled }
            
            if (copyTradings.isEmpty()) {
                return
            }
            
            // 使用 trade 中已有的市场信息
            val marketId = trade.market  // conditionId
            val outcomeIndex = trade.outcomeIndex ?: 0
            
            // 计算卖出价格
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
                    .sortedBy { it.createdAt }  // 按创建时间排序（FIFO）
                
                if (unmatchedOrders.isEmpty()) {
                    continue
                }

                // 数据库幂等兜底：同一跟单关系 + 同一笔链上交易 + 同一市场已记账则跳过
                // （内存去重只在单进程有效，这里保证重启/多实例下也不会重复计入盈亏）
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
        } catch (e: Exception) {
            logger.error("处理账户卖出/赎回事件失败: accountId=${account.id}, txHash=${trade.id}, error=${e.message}", e)
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
