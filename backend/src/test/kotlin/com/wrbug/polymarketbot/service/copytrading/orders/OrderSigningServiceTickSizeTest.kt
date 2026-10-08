package com.wrbug.polymarketbot.service.copytrading.orders

import com.wrbug.polymarketbot.api.OrderbookEntry
import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OrderSigningServiceTickSizeTest {

    private val service = OrderSigningService()

    @Test
    fun `tick 0_01 buy amounts match official sdk rounding`() {
        // 官方 @polymarket/clob-client-v2 getOrderRawAmounts("BUY","10.123456","0.57",tick 0.01)
        // => rawTakerAmt=10.12（shares 2 位），rawMakerAmt=5.7684（USDC 4 位）
        val cfg = service.roundConfigForTickSize(BigDecimal("0.01"))
        val amounts = service.calculateOrderAmounts("BUY", "10.123456", "0.57", cfg)
        assertEquals("10120000", amounts.takerAmount)
        assertEquals("5768400", amounts.makerAmount)
        assertOnTickAmounts(amounts, BigDecimal("0.57"))
    }

    @Test
    fun `tick 0_001 buy amounts match official sdk rounding`() {
        // 官方 getOrderRawAmounts("BUY","10.1234567","0.957",tick 0.001)
        // => rawTakerAmt=10.12，rawMakerAmt=9.68484（USDC 5 位）
        val cfg = service.roundConfigForTickSize(BigDecimal("0.001"))
        val amounts = service.calculateOrderAmounts("BUY", "10.1234567", "0.957", cfg)
        assertEquals("10120000", amounts.takerAmount)
        assertEquals("9684840", amounts.makerAmount)
        assertOnTickAmounts(amounts, BigDecimal("0.957"))
    }

    /** makerAmount / takerAmount 的隐含价格必须正好等于下单价格（否则交易所会按不在 tick 上拒单） */
    private fun assertOnTickAmounts(amounts: OrderSigningService.OrderAmounts, expectedPrice: BigDecimal) {
        val implied = BigDecimal(amounts.makerAmount)
            .divide(BigDecimal(amounts.takerAmount), 8, java.math.RoundingMode.HALF_UP)
        assertEquals(0, expectedPrice.compareTo(implied), "隐含价格 $implied 与下单价格 $expectedPrice 不一致")
    }

    @Test
    fun `tick 0_001 sell uses 2 decimal shares and 5 decimal usdc`() {
        val cfg = service.roundConfigForTickSize(BigDecimal("0.001"))
        val amounts = service.calculateOrderAmounts("SELL", "10.129", "0.957", cfg)
        assertEquals("10120000", amounts.makerAmount)
        // 10.12*0.957=9.68484 → 5 位内，保持
        assertEquals("9684840", amounts.takerAmount)
    }

    @Test
    fun `non tick price is aligned down for buy and up for sell`() {
        val cfg = service.roundConfigForTickSize(BigDecimal("0.01"))
        val buy = service.calculateOrderAmounts("BUY", "10", "0.555", cfg)
        assertEquals("5500000", buy.makerAmount)  // 0.55 * 10
        val sell = service.calculateOrderAmounts("SELL", "10", "0.551", cfg)
        assertEquals("5600000", sell.takerAmount)  // 0.56 * 10
    }

    @Test
    fun `price out of range is clamped to tick bounds`() {
        assertEquals(BigDecimal("0.999"), service.alignPriceToTick(BigDecimal("1.2"), BigDecimal("0.001"), true))
        assertEquals(BigDecimal("0.001"), service.alignPriceToTick(BigDecimal("0.0001"), BigDecimal("0.001"), false))
        assertEquals(BigDecimal("0.99"), service.alignPriceToTick(BigDecimal("0.995"), BigDecimal("0.01"), false))
    }

    @Test
    fun `strict tick mode rejects price not on tick`() {
        val cfg = service.roundConfigForTickSize(BigDecimal("0.01"))
        assertThrows(OrderPriceNotOnTickException::class.java) {
            service.calculateOrderAmounts("BUY", "10", "0.555", cfg, strictTick = true)
        }
        assertThrows(OrderPriceNotOnTickException::class.java) {
            service.calculateOrderAmounts("SELL", "10", "0.995", cfg, strictTick = true)
        }
        // 在 tick 上的价格正常通过
        service.calculateOrderAmounts("BUY", "10", "0.55", cfg, strictTick = true)
    }

    @Test
    fun `valid tick price check`() {
        assertTrue(service.isValidTickPrice(BigDecimal("0.957"), BigDecimal("0.001")))
        assertFalse(service.isValidTickPrice(BigDecimal("0.957"), BigDecimal("0.01")))
        assertFalse(service.isValidTickPrice(BigDecimal("1.0"), BigDecimal("0.01")))
    }

    @Test
    fun `order hash is stable for same signed order`() {
        val pk = "0x" + "1".repeat(64)
        val maker = org.web3j.crypto.Credentials.create("1".repeat(64)).address
        val order = service.createAndSignOrder(
            privateKey = pk, makerAddress = maker, tokenId = "123", side = "BUY",
            price = "0.5", size = "10", signatureType = 0
        )
        val h1 = service.computeOrderHash(order)
        val h2 = service.computeOrderHash(order)
        assertEquals(h1, h2)
        assertEquals(66, h1.length)
    }

    @Test
    fun `best bid and ask do not depend on book ordering`() {
        // /book：bids 升序、asks 降序
        val book = OrderbookResponse(
            bids = listOf(OrderbookEntry("0.01", "100"), OrderbookEntry("0.40", "5"), OrderbookEntry("0.45", "3")),
            asks = listOf(OrderbookEntry("0.99", "100"), OrderbookEntry("0.60", "5"), OrderbookEntry("0.48", "2"))
        )
        assertEquals(BigDecimal("0.45"), PolymarketClobService.bestBid(book))
        assertEquals(BigDecimal("0.48"), PolymarketClobService.bestAsk(book))
    }
}
