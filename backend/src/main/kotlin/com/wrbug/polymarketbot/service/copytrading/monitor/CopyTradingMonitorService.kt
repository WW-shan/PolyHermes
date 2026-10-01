package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * 跟单监听服务（主服务）
 * 管理所有Leader的交易监听
 * 使用双重监听机制：
 * 1. Activity WebSocket - 低延迟（< 100ms），优先使用
 * 2. OnChain WebSocket - 高可靠性（~2-3s），作为兜底
 * 同时监听跟单账户的卖出/赎回事件（通过链上 WebSocket）
 */
@Service
class CopyTradingMonitorService(
    private val copyTradingRepository: CopyTradingRepository,
    private val leaderRepository: LeaderRepository,
    private val accountRepository: AccountRepository,
    private val activityWsService: PolymarketActivityWsService,
    private val onChainWsService: OnChainWsService,
    private val accountOnChainMonitorService: AccountOnChainMonitorService
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingMonitorService::class.java)
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * 系统启动时初始化监听
     */
    @PostConstruct
    fun init() {
        scope.launch {
            try {
                startMonitoring()
            } catch (e: Exception) {
                logger.error("启动跟单监听失败", e)
            }
        }
    }
    
    /**
     * 系统关闭时清理资源
     */
    @PreDestroy
    fun destroy() {
        scope.cancel()
        // 停止所有监听
        activityWsService.stop()
        onChainWsService.stop()
        accountOnChainMonitorService.stop()
    }
    
    /**
     * 启动监听
     * 启动双重监听机制：
     * 1. Activity WebSocket - 低延迟（< 100ms），优先使用
     * 2. OnChain WebSocket - 高可靠性（~2-3s），作为兜底
     * 同时启动跟单账户的链上 WebSocket 监听（用于检测卖出/赎回事件）
     */
    suspend fun startMonitoring() {
        val plan = buildMonitoringPlan()

        // 1. 启动 Activity WebSocket 监听（优先，低延迟）
        activityWsService.start(plan.leaders)

        // 2. 启动链上 WebSocket 监听（兜底，高可靠性）
        onChainWsService.start(plan.leaders)

        // 3. 启动跟单账户的链上 WebSocket 监听（用于检测外部卖出）
        accountOnChainMonitorService.start(plan.accounts)
    }

    /**
     * 监听计划：需要监听的 Leader（有启用的配置、且不是本系统账户地址）与账户（有任意跟单配置，含已禁用）
     */
    private data class MonitoringPlan(
        val leaders: List<Leader> = emptyList(),
        val accounts: List<Account> = emptyList()
    )

    private fun buildMonitoringPlan(): MonitoringPlan {
        val allAccounts = accountRepository.findAll()
        val systemAddresses = systemAddressesOf(allAccounts)
        val leaderIds = copyTradingRepository.findByEnabledTrue().map { it.leaderId }.distinct()
        val leaders = leaderIds.mapNotNull { leaderRepository.findById(it).orElse(null) }
            .filter { !isSystemAddress(it, systemAddresses) }
        // 禁用配置仍可能持有仓位，账户卖出需要继续记账，因此账户监听覆盖所有配置
        val accountIds = copyTradingRepository.findAll().map { it.accountId }.toSet()
        val accounts = allAccounts.filter { it.id != null && it.id in accountIds }
        return MonitoringPlan(leaders, accounts)
    }

    private fun systemAddressesOf(accounts: List<Account>): Set<String> {
        return accounts.flatMap { listOf(it.proxyAddress, it.walletAddress) }
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .toSet()
    }

    /**
     * 自跟单防护：Leader 地址等于本系统任一账户的代理地址或钱包地址时不监听其成交
     */
    private fun isSystemAddress(leader: Leader, systemAddresses: Set<String>): Boolean {
        val self = leader.leaderAddress.lowercase() in systemAddresses
        if (self) {
            logger.warn("Leader 地址属于本系统账户，跳过监听以防自跟单: leaderId=${leader.id}, address=${leader.leaderAddress}")
        }
        return self
    }

    private fun isSystemAddress(leader: Leader): Boolean {
        return isSystemAddress(leader, systemAddressesOf(accountRepository.findAll()))
    }

    /**
     * 定期按数据库刷新订阅：移除已删除/已无启用配置的 Leader 与已删除的账户，补上遗漏的订阅
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    fun refreshMonitoring() {
        try {
            val plan = buildMonitoringPlan()
            val desiredLeaderIds = plan.leaders.mapNotNull { it.id }.toSet()
            (onChainWsService.getMonitoredLeaderIds() - desiredLeaderIds).forEach { onChainWsService.removeLeader(it) }
            (activityWsService.getMonitoredLeaderIds() - desiredLeaderIds).forEach { activityWsService.removeLeader(it) }
            plan.leaders.forEach { leader ->
                activityWsService.addLeader(leader)
                onChainWsService.addLeader(leader)
            }
            val desiredAccountIds = plan.accounts.mapNotNull { it.id }.toSet()
            (accountOnChainMonitorService.getMonitoredAccountIds() - desiredAccountIds).forEach {
                accountOnChainMonitorService.removeAccount(it)
            }
            plan.accounts.forEach { accountOnChainMonitorService.addAccount(it) }
        } catch (e: Exception) {
            logger.error("刷新跟单监听订阅失败: ${e.message}", e)
        }
    }
    
    /**
     * 添加Leader监听（当创建新的跟单关系时调用）
     * 如果 Leader 已经在监听列表中，不重复添加
     */
    suspend fun addLeaderMonitoring(leaderId: Long) {
        val leader = leaderRepository.findById(leaderId).orElse(null)
            ?: return
        
        val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
        if (copyTradings.isEmpty() || isSystemAddress(leader)) {
            return
        }
        
        // 同时添加到两种监听
        activityWsService.addLeader(leader)
        onChainWsService.addLeader(leader)
    }
    
    /**
     * 移除Leader监听（当删除跟单关系或禁用时调用）
     * 检查该 Leader 是否还有其他启用的跟单配置
     */
    suspend fun removeLeaderMonitoring(leaderId: Long) {
        val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
        // 如果还有启用的跟单配置，不移除监听
        if (copyTradings.isNotEmpty()) {
            return
        }
        
        // 没有启用的跟单配置了，同时从两种监听移除
        activityWsService.removeLeader(leaderId)
        onChainWsService.removeLeader(leaderId)
    }
    
    /**
     * 更新Leader监听（当跟单配置状态改变时调用）
     * 根据当前状态决定添加或移除监听
     */
    suspend fun updateLeaderMonitoring(leaderId: Long) {
        val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
        val leader = leaderRepository.findById(leaderId).orElse(null)
        if (leader == null) {
            // Leader 已被删除：移除其订阅
            activityWsService.removeLeader(leaderId)
            onChainWsService.removeLeader(leaderId)
            return
        }
        
        if (copyTradings.isNotEmpty() && !isSystemAddress(leader)) {
            // 有启用的跟单配置，确保在监听列表中
            activityWsService.addLeader(leader)
            onChainWsService.addLeader(leader)
            
            // 更新账户监听（添加该配置关联的账户）
            val accountIds = copyTradings.map { it.accountId }.distinct()
            accountIds.forEach { accountId ->
                val account = accountRepository.findById(accountId).orElse(null)
                if (account != null) {
                    accountOnChainMonitorService.addAccount(account)
                }
            }
        } else {
            // 没有启用的跟单配置（或属于本系统账户地址），同时从两种监听移除
            activityWsService.removeLeader(leaderId)
            onChainWsService.removeLeader(leaderId)
        }
    }
    
    /**
     * 更新账户监听（当跟单配置状态改变时调用）
     * 根据当前状态决定添加或移除账户监听
     */
    suspend fun updateAccountMonitoring(accountId: Long) {
        // 禁用的配置仍可能持有仓位，外部卖出需要继续记账，因此只要账户还有任意跟单配置就保持监听
        val copyTradings = copyTradingRepository.findByAccountId(accountId)
        val account = accountRepository.findById(accountId).orElse(null)
        
        if (account != null && copyTradings.isNotEmpty()) {
            accountOnChainMonitorService.addAccount(account)
        } else {
            // 账户已删除或已无任何跟单配置，移除账户监听
            accountOnChainMonitorService.removeAccount(accountId)
        }
    }
    
    /**
     * 重新启动监听（当跟单关系状态改变时调用）
     * 注意：这个方法会停止所有监听并重新启动，建议使用 updateLeaderMonitoring 进行增量更新
     */
    suspend fun restartMonitoring() {
        // 停止所有监听
        activityWsService.stop()
        onChainWsService.stop()
        delay(1000)  // 等待1秒
        startMonitoring()
    }
}

