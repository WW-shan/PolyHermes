package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.service.common.FakeRpc
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.Optional

/**
 * 设置状态：按官方清单同时检查 ERC20 allowance 与 ERC1155 isApprovedForAll，任一缺失/查询失败都未完成
 */
class AccountServiceSetupStatusTest {

    private val proxy = "0x2222222222222222222222222222222222222222"
    private val max = "0x" + "f".repeat(64)
    private val account = Account(
        id = 1L,
        privateKey = "encrypted",
        walletAddress = "0x1111111111111111111111111111111111111111",
        proxyAddress = proxy,
        apiKey = "api-key",
        apiSecret = "api-secret",
        apiPassphrase = "api-passphrase",
        walletType = "safe"
    )

    private fun service(rpc: FakeRpc): AccountService {
        val accountRepository = Mockito.mock(AccountRepository::class.java)
        Mockito.`when`(accountRepository.findById(1L)).thenReturn(Optional.of(account))
        return AccountService(
            accountRepository = accountRepository,
            clobService = Mockito.mock(com.wrbug.polymarketbot.service.common.PolymarketClobService::class.java),
            retrofitFactory = Mockito.mock(com.wrbug.polymarketbot.util.RetrofitFactory::class.java),
            blockchainService = FakeRpc.blockchainService(rpc),
            apiKeyService = Mockito.mock(com.wrbug.polymarketbot.service.common.PolymarketApiKeyService::class.java),
            orderPushService = Mockito.mock(com.wrbug.polymarketbot.service.copytrading.orders.OrderPushService::class.java),
            orderSigningService = Mockito.mock(com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService::class.java),
            cryptoUtils = Mockito.mock(com.wrbug.polymarketbot.util.CryptoUtils::class.java),
            marketService = Mockito.mock(com.wrbug.polymarketbot.service.common.MarketService::class.java),
            telegramNotificationService = null,
            relayClientService = Mockito.mock(com.wrbug.polymarketbot.service.system.RelayClientService::class.java),
            jsonUtils = Mockito.mock(com.wrbug.polymarketbot.util.JsonUtils::class.java)
        )
    }

    /** 按官方清单预设全部授权；except 中的 key 设为未授权 */
    private fun fullyApproved(svc: AccountService, rpc: FakeRpc, except: Set<String> = emptySet()) {
        for (a in svc.requiredTradingApprovals) {
            if (a.erc1155) rpc.onApprovedForAll(a.token, proxy, a.spender, a.key !in except)
            else rpc.onAllowance(a.token, proxy, a.spender, if (a.key in except) FakeRpc.word(0) else max)
        }
        rpc.codeAt[proxy] = "0x6080"
    }

    @Test
    fun `official list includes collateral adapters for both ERC20 and ERC1155 and excludes retired adapter`() {
        val svc = service(FakeRpc())
        val keys = svc.requiredTradingApprovals.map { it.key }
        assertEquals(17, keys.size)
        assertTrue(keys.containsAll(listOf("COLLATERAL_ADAPTER", "NEG_RISK_COLLATERAL_ADAPTER",
            "CTF_APPROVAL_COLLATERAL_ADAPTER", "CTF_APPROVAL_NEG_RISK_COLLATERAL_ADAPTER")))
        assertFalse(svc.requiredTradingApprovals.any { it.spender.equals("0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296", true) })
    }

    @Test
    fun `all approvals present means approved`() = runBlocking {
        val rpc = FakeRpc()
        val svc = service(rpc)
        fullyApproved(svc, rpc)
        val status = svc.checkAccountSetupStatus(1L).getOrThrow()
        assertTrue(status.proxyDeployed)
        assertTrue(status.tokensApproved)
        assertEquals("approved", status.approvalDetails!!["CTF_APPROVAL_COLLATERAL_ADAPTER"])
        assertEquals("unlimited", status.approvalDetails!!["CTF_EXCHANGE"])
    }

    @Test
    fun `missing ERC1155 approval means not approved`() = runBlocking {
        val rpc = FakeRpc()
        val svc = service(rpc)
        fullyApproved(svc, rpc, except = setOf("CTF_APPROVAL_NEG_RISK_COLLATERAL_ADAPTER"))
        val status = svc.checkAccountSetupStatus(1L).getOrThrow()
        assertFalse(status.tokensApproved)
        assertEquals("0", status.approvalDetails!!["CTF_APPROVAL_NEG_RISK_COLLATERAL_ADAPTER"])
    }

    @Test
    fun `query failure is shown as queryFailed not as not approved`() = runBlocking {
        val rpc = FakeRpc()
        val svc = service(rpc)
        fullyApproved(svc, rpc)
        // 让 EXCHANGE_V3 的 allowance 查询返回 RPC 错误（覆盖为 null 结果）
        val broken = svc.requiredTradingApprovals.first { it.key == "EXCHANGE_V3" }
        rpc.onAllowance(broken.token, proxy, broken.spender, "")
        rpc.ethCallResults.entries.first { it.value == "" }.setValue(null)
        val status = svc.checkAccountSetupStatus(1L).getOrThrow()
        assertFalse(status.tokensApproved)
        assertEquals("queryFailed", status.approvalDetails!!["EXCHANGE_V3"])
        assertTrue(status.error!!.contains("查询失败"))
    }
}
