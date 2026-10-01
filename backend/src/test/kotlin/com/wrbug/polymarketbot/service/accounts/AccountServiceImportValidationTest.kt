package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.AccountImportRequest
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.enums.WalletType
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.PolymarketApiKeyService
import com.wrbug.polymarketbot.service.common.PolymarketApiKeyService.ApiKeyCreds
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.DepositWalletVectors
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * 账户导入校验回归测试：
 * - 私钥必须与传入的钱包地址一致（不能只依赖前端校验）
 * - walletType 必须规范化后入库
 * - Deposit Wallet 已部署但 owner 查询失败时不允许放行（fail-closed）
 */
class AccountServiceImportValidationTest {

    private val accountRepository: AccountRepository = mock()
    private val apiKeyService: PolymarketApiKeyService = mock()
    private val blockchainService: BlockchainService = mock()
    private val cryptoUtils: CryptoUtils = mock()
    private val orderPushService = mock<com.wrbug.polymarketbot.service.copytrading.orders.OrderPushService>()

    private val service = AccountService(
        accountRepository = accountRepository,
        clobService = mock(),
        retrofitFactory = mock(),
        blockchainService = blockchainService,
        apiKeyService = apiKeyService,
        orderPushService = orderPushService,
        orderSigningService = mock(),
        cryptoUtils = cryptoUtils,
        marketService = mock(),
        telegramNotificationService = mock(),
        relayClientService = mock(),
        jsonUtils = mock()
    )

    private val privateKey = DepositWalletVectors.testPrivateKey
    private val eoa = DepositWalletVectors.signer
    private val otherEoa = "0xec61677883418ab16ecc0ce35113635cf5a753f9"
    private val depositWallet = DepositWalletVectors.derivation(eoa)["beaconDepositWallet"].asString

    /** 注册 Account 的 any 匹配器并返回非空占位值（Mockito.any 返回 null 会触发 Kotlin 非空检查） */
    private fun anyAccount(): Account {
        Mockito.any(Account::class.java)
        return Account(
            privateKey = "placeholder",
            walletAddress = "0x0000000000000000000000000000000000000000",
            proxyAddress = "0x0000000000000000000000000000000000000001"
        )
    }

    /** 注册 WalletType 的 eq 匹配器并返回非空占位值 */
    private fun eqWalletType(type: WalletType): WalletType {
        Mockito.eq(type)
        return type
    }

    private fun stubHappyPath(proxy: String, walletType: WalletType) {
        Mockito.`when`(
            apiKeyService.createOrDeriveApiKey(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong())
        ).thenReturn(Result.success(ApiKeyCreds("api-key", "api-secret", "api-passphrase")))
        runBlocking {
            Mockito.`when`(blockchainService.getProxyAddress(Mockito.anyString(), eqWalletType(walletType)))
                .thenReturn(Result.success(proxy))
        }
        Mockito.`when`(accountRepository.existsByProxyAddress(Mockito.anyString())).thenReturn(false)
        Mockito.`when`(cryptoUtils.encrypt(Mockito.anyString())).thenReturn("encrypted")
        Mockito.`when`(cryptoUtils.decrypt(Mockito.anyString())).thenReturn("decrypted")
        Mockito.`when`(accountRepository.save(anyAccount())).thenAnswer { invocation ->
            invocation.getArgument<Account>(0).copy(id = 1L)
        }
    }

    @Test
    fun `rejects import when private key does not match wallet address and produces no side effects`() {
        val result = service.importAccount(
            AccountImportRequest(
                privateKey = privateKey,
                walletAddress = otherEoa,
                accountName = "wrong",
                walletType = "safe"
            )
        )

        assertTrue(result.isFailure, "私钥与地址不匹配时必须导入失败")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("私钥") == true,
            "错误信息应说明私钥与地址不一致，实际: ${result.exceptionOrNull()?.message}"
        )
        Mockito.verifyNoInteractions(apiKeyService)
        Mockito.verifyNoInteractions(accountRepository)
    }

    @Test
    fun `accepts import when private key matches wallet address ignoring case`() {
        stubHappyPath(depositWallet, WalletType.DEPOSIT)
        runBlocking {
            Mockito.`when`(blockchainService.checkProxyDeployed(depositWallet)).thenReturn(Result.success(false))
        }

        val result = service.importAccount(
            AccountImportRequest(
                privateKey = privateKey.uppercase().replace("0X", "0x"),
                walletAddress = eoa.uppercase().replace("0X", "0x"),
                accountName = "deposit",
                walletType = "DEPOSIT"
            )
        )

        assertTrue(result.isSuccess, "大小写不同但地址一致时应允许导入: ${result.exceptionOrNull()?.message}")
        assertEquals("deposit", result.getOrThrow().walletType, "walletType 应规范化后入库")
    }

    @Test
    fun `rejects deposit wallet import when deployed owner cannot be read`() {
        stubHappyPath(depositWallet, WalletType.DEPOSIT)
        runBlocking {
            Mockito.`when`(blockchainService.checkProxyDeployed(depositWallet)).thenReturn(Result.success(true))
            Mockito.`when`(blockchainService.getDepositWalletOwner(depositWallet)).thenReturn(null)
        }

        val result = service.importAccount(
            AccountImportRequest(
                privateKey = privateKey,
                walletAddress = eoa,
                accountName = "deposit",
                walletType = WalletType.DEPOSIT.value
            )
        )

        assertFalse(result.isSuccess, "已部署 Deposit Wallet 读取 owner 失败时不能放行导入")
        Mockito.verify(accountRepository, Mockito.never()).save(anyAccount())
    }

    @Test
    fun `rejects deposit wallet import when owner differs from imported eoa`() {
        stubHappyPath(depositWallet, WalletType.DEPOSIT)
        runBlocking {
            Mockito.`when`(blockchainService.checkProxyDeployed(depositWallet)).thenReturn(Result.success(true))
            Mockito.`when`(blockchainService.getDepositWalletOwner(depositWallet)).thenReturn(otherEoa)
        }

        val result = service.importAccount(
            AccountImportRequest(
                privateKey = privateKey,
                walletAddress = eoa,
                accountName = "deposit",
                walletType = WalletType.DEPOSIT.value
            )
        )

        assertTrue(result.isFailure, "owner 与导入 EOA 不一致时必须导入失败")
        Mockito.verify(accountRepository, Mockito.never()).save(anyAccount())
    }

    companion object {
        private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
    }
}
