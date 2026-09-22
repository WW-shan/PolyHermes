package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

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

    companion object {
        private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
    }
}
