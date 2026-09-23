package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigInteger
import java.util.Optional

class AccountServiceNegRiskTest {

    private val orderSigningService: OrderSigningService = mock()
    private val service = AccountService(
        accountRepository = mock(),
        clobService = mock(),
        retrofitFactory = mock(),
        blockchainService = mock(),
        apiKeyService = mock(),
        orderPushService = mock(),
        orderSigningService = orderSigningService,
        cryptoUtils = mock(),
        marketService = mock(),
        telegramNotificationService = mock(),
        relayClientService = mock(),
        jsonUtils = mock()
    )

    @Test
    fun `uses neg risk exchange contract for neg risk markets`() {
        Mockito.`when`(orderSigningService.getExchangeContract(true)).thenReturn("neg-risk-exchange")

        assertEquals("neg-risk-exchange", service.exchangeContractForMarket(true))
        Mockito.verify(orderSigningService).getExchangeContract(true)
    }

    @Test
    fun `uses standard exchange contract when neg risk flag is false or unknown`() {
        Mockito.`when`(orderSigningService.getExchangeContract(false)).thenReturn("standard-exchange")

        assertEquals("standard-exchange", service.exchangeContractForMarket(false))
        assertEquals("standard-exchange", service.exchangeContractForMarket(null))
        Mockito.verify(orderSigningService, Mockito.times(2)).getExchangeContract(false)
    }

    @Test
    fun `setup status does not require retired neg risk adapter allowance`(): Unit = runBlocking {
        val accountRepository: AccountRepository = mock()
        val blockchainService: BlockchainService = mock()
        val account = Account(
            id = 1L,
            privateKey = "encrypted",
            walletAddress = "0x1111111111111111111111111111111111111111",
            proxyAddress = "0x2222222222222222222222222222222222222222",
            apiKey = "api-key",
            apiSecret = "api-secret",
            apiPassphrase = "api-passphrase",
            walletType = "safe"
        )
        Mockito.`when`(accountRepository.findById(1L)).thenReturn(Optional.of(account))
        Mockito.`when`(blockchainService.isProxyDeployed(account.proxyAddress)).thenReturn(true)

        val requiredSpenders = listOf(
            "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045",
            "0xE111180000d2663C0091e4f400237545B87B996B",
            "0xe2222d279d744050d28e00520010520000310F59"
        )
        val retiredAdapter = "0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296"
        for (spender in requiredSpenders) {
            Mockito.`when`(blockchainService.getUsdcAllowance(account.proxyAddress, spender))
                .thenReturn(Result.success(BigInteger("115792089237316195423570985008687907853269984665640564039457584007913129639935")))
        }

        val service = AccountService(
            accountRepository = accountRepository,
            clobService = mock(),
            retrofitFactory = mock(),
            blockchainService = blockchainService,
            apiKeyService = mock(),
            orderPushService = mock(),
            orderSigningService = mock(),
            cryptoUtils = mock(),
            marketService = mock(),
            telegramNotificationService = mock(),
            relayClientService = mock(),
            jsonUtils = mock()
        )

        val status = service.checkAccountSetupStatus(1L).getOrThrow()

        assertTrue(status.tokensApproved, "三个有效 spender 均为无限授权时应视为已授权")
        assertFalse(status.approvalDetails!!.containsKey("NEG_RISK_ADAPTER"))
        Mockito.verify(blockchainService, Mockito.never())
            .getUsdcAllowance(account.proxyAddress, retiredAdapter)
        Unit
    }

    companion object {
        private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
    }
}
