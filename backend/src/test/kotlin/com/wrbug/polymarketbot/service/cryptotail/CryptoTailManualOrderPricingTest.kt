package com.wrbug.polymarketbot.service.cryptotail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CryptoTailManualOrderPricingTest {

    @Test
    fun `effective tick uses market tick and falls back to 0_01`() {
        assertEquals(0, BigDecimal("0.01").compareTo(CryptoTailManualOrderPricing.effectiveTick(null)))
        assertEquals(0, BigDecimal("0.001").compareTo(CryptoTailManualOrderPricing.effectiveTick(BigDecimal("0.001"))))
        assertEquals(0, BigDecimal("0.1").compareTo(CryptoTailManualOrderPricing.effectiveTick(BigDecimal("0.1"))))
    }

    @Test
    fun `price not on tick is rejected instead of silently rounded`() {
        val tick = BigDecimal("0.01")
        assertNull(CryptoTailManualOrderPricing.quote(BigDecimal("0.9555"), BigDecimal("10"), tick))
        assertNull(CryptoTailManualOrderPricing.quote(BigDecimal("0.005"), BigDecimal("10"), tick))
        assertNull(CryptoTailManualOrderPricing.quote(BigDecimal("0.995"), BigDecimal("10"), tick))
        assertNull(CryptoTailManualOrderPricing.quote(BigDecimal("1"), BigDecimal("10"), tick))
    }

    @Test
    fun `amount follows signer maker amount rounding`() {
        val quote = CryptoTailManualOrderPricing.quote(BigDecimal("0.950"), BigDecimal("10.555"), BigDecimal("0.01"))!!
        assertEquals(0, BigDecimal("0.95").compareTo(quote.price))
        assertEquals(0, BigDecimal("10.55").compareTo(quote.size))
        // 10.55 * 0.95 = 10.0225，tick 0.01 的金额精度为 4 位，因此保持 10.0225（隐含价格正好 0.95）
        assertEquals(0, BigDecimal("10.0225").compareTo(quote.amountUsdc))
    }

    @Test
    fun `quote matches signed amounts for fine tick markets`() {
        val signer = OrderSigningService()
        for ((tick, price) in listOf("0.01" to "0.95", "0.001" to "0.953")) {
            val tickSize = BigDecimal(tick)
            val quote = CryptoTailManualOrderPricing.quote(BigDecimal(price), BigDecimal("10.555"), tickSize)!!
            val amounts = signer.calculateOrderAmounts(
                "BUY", quote.size.toPlainString(), quote.price.toPlainString(),
                signer.roundConfigForTickSize(tickSize), strictTick = true
            )
            assertEquals(0, quote.amountUsdc.compareTo(BigDecimal(amounts.makerAmount).movePointLeft(6)))
            assertEquals(0, quote.size.compareTo(BigDecimal(amounts.takerAmount).movePointLeft(6)))
        }
    }
}
