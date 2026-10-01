package com.wrbug.polymarketbot.service.copytrading.configs

import com.google.gson.Gson
import com.wrbug.polymarketbot.dto.CopyTradingCreateRequest
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.util.Optional

/**
 * 自跟单检查与跟单参数校验回归测试
 */
class CopyTradingValidationTest {

    private val account = Account(
        id = 1L,
        privateKey = "enc",
        walletAddress = "0xEoA0000000000000000000000000000000000001",
        proxyAddress = "0xPrOxY000000000000000000000000000000000002"
    )

    @Test
    fun `proxy address and EOA are both treated as own address ignoring case`() {
        assertTrue(CopyTradingValidation.isOwnAccountAddress("0xproxy000000000000000000000000000000000002", listOf(account)))
        assertTrue(CopyTradingValidation.isOwnAccountAddress("0xEOA0000000000000000000000000000000000001", listOf(account)))
        assertFalse(CopyTradingValidation.isOwnAccountAddress("0x9999999999999999999999999999999999999999", listOf(account)))
    }

    private fun serviceFor(leaderAddress: String): Pair<CopyTradingService, CopyTradingRepository> {
        val copyTradingRepository = Mockito.mock(CopyTradingRepository::class.java)
        val accountRepository = Mockito.mock(AccountRepository::class.java)
        val leaderRepository = Mockito.mock(LeaderRepository::class.java)
        Mockito.`when`(accountRepository.findById(1L)).thenReturn(Optional.of(account))
        Mockito.`when`(leaderRepository.findById(2L)).thenReturn(Optional.of(Leader(id = 2L, leaderAddress = leaderAddress)))
        val service = CopyTradingService(
            copyTradingRepository = copyTradingRepository,
            accountRepository = accountRepository,
            templateRepository = Mockito.mock(com.wrbug.polymarketbot.repository.CopyTradingTemplateRepository::class.java),
            leaderRepository = leaderRepository,
            monitorService = Mockito.mock(com.wrbug.polymarketbot.service.copytrading.monitor.CopyTradingMonitorService::class.java),
            jsonUtils = Mockito.mock(com.wrbug.polymarketbot.util.JsonUtils::class.java),
            gson = Gson()
        )
        return service to copyTradingRepository
    }

    private fun request(
        copyRatio: String? = "1", minPrice: String? = null, maxPrice: String? = null,
        minOrderSize: String? = null, maxOrderSize: String? = null
    ) = CopyTradingCreateRequest(
        accountId = 1L, leaderId = 2L, enabled = false, copyMode = "RATIO", copyRatio = copyRatio,
        configName = "cfg", minPrice = minPrice, maxPrice = maxPrice,
        minOrderSize = minOrderSize, maxOrderSize = maxOrderSize
    )

    private fun errorCodeOf(result: Result<*>): ErrorCode? =
        (result.exceptionOrNull() as? CopyTradingValidationException)?.errorCode

    @Test
    fun `creating a config that follows own proxy wallet is rejected`() {
        val (service, repo) = serviceFor("0xproxy000000000000000000000000000000000002")
        val result = service.createCopyTrading(request())
        assertEquals(ErrorCode.LEADER_ADDRESS_SAME_AS_ACCOUNT, errorCodeOf(result))
        Mockito.verify(repo, Mockito.never()).save(Mockito.any(CopyTrading::class.java))
    }

    @Test
    fun `invalid params are rejected with specific error codes`() {
        val (service, _) = serviceFor("0x9999999999999999999999999999999999999999")
        assertEquals(ErrorCode.COPY_TRADING_RATIO_INVALID, errorCodeOf(service.createCopyTrading(request(copyRatio = "0"))))
        assertEquals(ErrorCode.COPY_TRADING_PRICE_RANGE_INVALID, errorCodeOf(service.createCopyTrading(request(minPrice = "0.8", maxPrice = "0.2"))))
        assertEquals(ErrorCode.COPY_TRADING_PRICE_RANGE_INVALID, errorCodeOf(service.createCopyTrading(request(maxPrice = "1.5"))))
        assertEquals(
            ErrorCode.COPY_TRADING_ORDER_SIZE_RANGE_INVALID,
            errorCodeOf(service.createCopyTrading(request(minOrderSize = "10", maxOrderSize = "5")))
        )
        assertEquals(ErrorCode.COPY_TRADING_AMOUNT_INVALID, errorCodeOf(service.createCopyTrading(request(maxOrderSize = "-1"))))
    }

    @Test
    fun `valid params pass validation`() {
        CopyTradingValidation.validateParams(
            copyMode = "FIXED", copyRatio = null, fixedAmount = BigDecimal("5"), maxOrderSize = BigDecimal("10"),
            minOrderSize = BigDecimal("1"), maxDailyLoss = BigDecimal("50"), maxPositionValue = null,
            minPrice = BigDecimal("0.1"), maxPrice = BigDecimal("0.9"), maxDailyOrders = 10
        )
    }
}
