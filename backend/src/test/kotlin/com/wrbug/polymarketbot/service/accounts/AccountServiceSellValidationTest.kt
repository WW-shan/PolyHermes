package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.CheckProxyOptionsRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

/**
 * 手动卖出参数校验（tick、市价、数量取整、expectedQuantity）与 check-proxy-options 导入方式解析
 */
class AccountServiceSellValidationTest {

    private val service = AccountService(
        accountRepository = mock(),
        clobService = mock(),
        retrofitFactory = mock(),
        blockchainService = mock(),
        apiKeyService = mock(),
        orderPushService = mock(),
        orderSigningService = mock(),
        cryptoUtils = mock(),
        marketService = mock(),
        telegramNotificationService = null,
        relayClientService = mock(),
        jsonUtils = mock()
    )

    @Test
    fun `limit price must be on tick and within tick to one minus tick`() {
        val tick = BigDecimal("0.01")
        assertNull(service.validateLimitPrice(BigDecimal("0.55"), tick))
        assertNull(service.validateLimitPrice(BigDecimal("0.01"), tick))
        assertNull(service.validateLimitPrice(BigDecimal("0.99"), tick))
        assertNotNull(service.validateLimitPrice(BigDecimal("0.555"), tick))
        assertNotNull(service.validateLimitPrice(BigDecimal("0"), tick))
        assertNotNull(service.validateLimitPrice(BigDecimal("1"), tick))
        assertNull(service.validateLimitPrice(BigDecimal("0.555"), BigDecimal("0.001")))
    }

    @Test
    fun `market sell price is best bid minus slippage floored to tick and at least tick`() {
        val tick = BigDecimal("0.01")
        assertEquals(0, BigDecimal("0.48").compareTo(service.marketSellPrice(BigDecimal("0.50"), BigDecimal("0.02"), tick)))
        assertEquals(0, BigDecimal("0.01").compareTo(service.marketSellPrice(BigDecimal("0.02"), BigDecimal("0.02"), tick)))
        assertEquals(0, BigDecimal("0.483").compareTo(service.marketSellPrice(BigDecimal("0.5035"), BigDecimal("0.02"), BigDecimal("0.001"))))
    }

    @Test
    fun `sell quantity is floored to two decimals`() {
        assertEquals(BigDecimal("12.34"), service.normalizeSellQuantity(BigDecimal("12.349999")))
        assertEquals(BigDecimal("0.00"), service.normalizeSellQuantity(BigDecimal("0.009")))
    }

    @Test
    fun `expected quantity tolerance is max of one percent and one cent`() {
        assertTrue(service.isExpectedQuantityConsistent(BigDecimal("100"), BigDecimal("100.9")))
        assertFalse(service.isExpectedQuantityConsistent(BigDecimal("100"), BigDecimal("102")))
        assertTrue(service.isExpectedQuantityConsistent(BigDecimal("0.5"), BigDecimal("0.505")))
        assertFalse(service.isExpectedQuantityConsistent(BigDecimal("0.5"), BigDecimal("0.52")))
    }

    @Test
    fun `check proxy options does not need private key or mnemonic`() {
        val wallet = "0x1111111111111111111111111111111111111111"
        assertTrue(service.resolveIsPrivateKeyImport(CheckProxyOptionsRequest(walletAddress = wallet, importMethod = "PRIVATE_KEY")).getOrThrow())
        assertFalse(service.resolveIsPrivateKeyImport(CheckProxyOptionsRequest(walletAddress = wallet, importMethod = "MNEMONIC")).getOrThrow())
        // 旧前端兼容：只看是否非空
        assertFalse(service.resolveIsPrivateKeyImport(CheckProxyOptionsRequest(walletAddress = wallet, mnemonic = "x")).getOrThrow())
        assertTrue(service.resolveIsPrivateKeyImport(CheckProxyOptionsRequest(walletAddress = wallet)).getOrThrow())
        assertTrue(service.resolveIsPrivateKeyImport(CheckProxyOptionsRequest(walletAddress = wallet, importMethod = "BAD")).isFailure)
    }

    companion object {
        private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
    }
}
