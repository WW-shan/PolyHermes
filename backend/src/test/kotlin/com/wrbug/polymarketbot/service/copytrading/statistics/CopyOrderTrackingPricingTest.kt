package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

class CopyOrderTrackingPricingTest {

    private val trackingRepo: CopyOrderTrackingRepository = Mockito.mock(CopyOrderTrackingRepository::class.java)
    private val service = CopyOrderTrackingService(
        copyOrderTrackingRepository = trackingRepo,
        sellMatchRecordRepository = mock(),
        sellMatchDetailRepository = mock(),
        processedTradeRepository = mock(),
        filteredOrderRepository = mock(),
        copyTradingRepository = mock(),
        accountRepository = mock(),
        filterService = mock(),
        leaderRepository = mock(),
        orderSigningService = OrderSigningService(),
        blockchainService = mock(),
        clobService = mock(),
        retrofitFactory = mock(),
        cryptoUtils = mock(),
        marketService = mock(),
        ledger = mock()
    )

    private fun bd(v: String) = BigDecimal(v)
    private val tick01 = bd("0.01")
    private val tick001 = bd("0.001")

    @Test
    fun `zero tolerance means no price adjustment`() {
        val ct = CopyTrading(accountId = 1, leaderId = 1, priceTolerance = BigDecimal.ZERO)
        val tol = service.toleranceRatio(ct)
        assertEquals(0, bd("0.50").compareTo(service.calculateBuyLimitPrice(bd("0.50"), tol, tick01)))
        assertEquals(0, bd("0.50").compareTo(service.calculateSellLimitPrice(bd("0.50"), tol, tick01)))
    }

    @Test
    fun `buy limit is percentage based and floored to tick`() {
        val tol = service.toleranceRatio(CopyTrading(accountId = 1, leaderId = 1, priceTolerance = bd("5")))
        // 0.5 × 1.05 = 0.525 → tick 0.01 向下 0.52；tick 0.001 为 0.525
        assertEquals(0, bd("0.52").compareTo(service.calculateBuyLimitPrice(bd("0.5"), tol, tick01)))
        assertEquals(0, bd("0.525").compareTo(service.calculateBuyLimitPrice(bd("0.5"), tol, tick001)))
        // 高价不会超过 1 - tick
        assertEquals(0, bd("0.99").compareTo(service.calculateBuyLimitPrice(bd("0.98"), tol, tick01)))
    }

    @Test
    fun `small tolerance moves at least one tick`() {
        val tol = service.toleranceRatio(CopyTrading(accountId = 1, leaderId = 1, priceTolerance = bd("0.1")))
        assertEquals(0, bd("0.51").compareTo(service.calculateBuyLimitPrice(bd("0.50"), tol, tick01)))
        assertEquals(0, bd("0.49").compareTo(service.calculateSellLimitPrice(bd("0.50"), tol, tick01)))
    }

    @Test
    fun `sell limit uses configured tolerance and ceils to tick`() {
        val tol = service.toleranceRatio(CopyTrading(accountId = 1, leaderId = 1, priceTolerance = bd("5")))
        // 0.5 × 0.95 = 0.475 → 向上取整 0.48（不再固定按 90%）
        assertEquals(0, bd("0.48").compareTo(service.calculateSellLimitPrice(bd("0.5"), tol, tick01)))
        assertEquals(0, bd("0.475").compareTo(service.calculateSellLimitPrice(bd("0.5"), tol, tick001)))
        // 不低于 tick
        assertEquals(0, bd("0.001").compareTo(service.calculateSellLimitPrice(bd("0.001"), tol, tick001)))
    }

    @Test
    fun `ratio quantity is capped by max order size at limit price`() {
        val ct = CopyTrading(accountId = 1, leaderId = 1, copyMode = "RATIO", copyRatio = BigDecimal.ONE, maxOrderSize = bd("10"), minOrderSize = bd("1"))
        val qty = service.calculateFinalBuyQuantity(trade("100", "0.45"), ct, bd("0.50"))
        assertEquals(0, bd("20").compareTo(qty))
    }

    @Test
    fun `ratio quantity is raised to min order size at limit price`() {
        val ct = CopyTrading(accountId = 1, leaderId = 1, copyMode = "RATIO", copyRatio = bd("0.1"), maxOrderSize = bd("100"), minOrderSize = bd("2"))
        // 10 × 0.1 = 1 股 × 0.5 = 0.5 USDC < 2 → 2 / 0.5 = 4 股
        val qty = service.calculateFinalBuyQuantity(trade("10", "0.45"), ct, bd("0.50"))
        assertEquals(0, bd("4").compareTo(qty))
    }

    @Test
    fun `fixed quantity uses limit price and order precision`() {
        val ct = CopyTrading(accountId = 1, leaderId = 1, copyMode = "FIXED", fixedAmount = bd("10"))
        val qty = service.calculateFinalBuyQuantity(trade("100", "0.50"), ct, bd("0.52"))
        // 10 / 0.52 = 19.2307... → 2 位向下 19.23
        assertEquals(0, bd("19.23").compareTo(qty))
    }

    @Test
    fun `sell ratio uses cumulative copy over leader buy quantity`() {
        Mockito.`when`(trackingRepo.findByCopyTradingIdAndMarketIdAndOutcomeIndex(1L, "m", 0)).thenReturn(
            listOf(
                tracking("20", "100", CopyOrderTracking.STATUS_FILLED),
                tracking("5", "10", CopyOrderTracking.STATUS_FULLY_MATCHED),
                tracking("0", "50", CopyOrderTracking.STATUS_PENDING),
                tracking("7", null, CopyOrderTracking.STATUS_FILLED)
            )
        )
        // (20 + 5) / (100 + 10)
        val ratio = service.calculateSellRatio(1L, "m", 0)!!
        assertEquals(0, bd("0.22727273").compareTo(ratio))
    }

    @Test
    fun `sell ratio is unknown without leader buy quantity`() {
        Mockito.`when`(trackingRepo.findByCopyTradingIdAndMarketIdAndOutcomeIndex(1L, "m", 0))
            .thenReturn(listOf(tracking("7", null, CopyOrderTracking.STATUS_FILLED)))
        assertNull(service.calculateSellRatio(1L, "m", 0))
    }

    @Test
    fun `execution price uses maker orders and complements other token price`() {
        val trade = com.wrbug.polymarketbot.api.ClobTrade(
            id = "tr", takerOrderId = "0xmine", assetId = "A", size = "10", price = "0.6", status = "CONFIRMED",
            makerOrders = listOf(
                com.wrbug.polymarketbot.api.ClobMakerOrder(orderId = "0xm1", matchedAmount = "4", price = "0.55", assetId = "A"),
                // 互补 token 的 maker 价格 0.40 → 本 token 价格 0.60
                com.wrbug.polymarketbot.api.ClobMakerOrder(orderId = "0xm2", matchedAmount = "6", price = "0.40", assetId = "B")
            )
        )
        val (amount, size) = service.fillAmountForOrder(trade, "0xmine", "A")
        assertEquals(0, bd("10").compareTo(size))
        assertEquals(0, bd("5.80").compareTo(amount))
        // 我方为 maker 时只取本订单的明细
        val (makerAmount, makerSize) = service.fillAmountForOrder(trade, "0xm1", "A")
        assertEquals(0, bd("4").compareTo(makerSize))
        assertEquals(0, bd("2.20").compareTo(makerAmount))
    }

    @Test
    fun `same config always uses the same lock`() {
        assertSame(service.getConfigMutex(42L), service.getConfigMutex(42L))
    }

    private fun trade(size: String, price: String) = TradeResponse(
        id = "t", market = "m", side = "BUY", price = price, size = size, timestamp = "0", user = null, outcomeIndex = 0
    )

    private fun tracking(qty: String, leaderQty: String?, status: String) = CopyOrderTracking(
        id = 1, copyTradingId = 1, accountId = 1, leaderId = 1, marketId = "m", side = "0", outcomeIndex = 0,
        buyOrderId = "0x1", leaderBuyTradeId = "lt", leaderBuyQuantity = leaderQty?.let { bd(it) },
        quantity = bd(qty), price = bd("0.5"), remainingQuantity = bd(qty), status = status, source = "t"
    )

    companion object {
        private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
    }
}
