package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.UserActivityResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 回归测试：一笔 FAK 订单扫过多个价位会产生多条 TRADE，结算必须聚合全部成交，
 * 否则成交量/成本被低估、盈亏算错。
 */
class CryptoTailActivityFillAggregationTest {

    private val conditionId = "0x7492221d7d519820168d71c5bed1931a9c1b1ae7eb733df9d7df6f0aa3163a61"

    private fun trade(
        size: Double,
        price: Double,
        usdcSize: Double?,
        outcomeIndex: Int = 1,
        side: String = "BUY",
        type: String = "TRADE",
        condition: String = conditionId
    ) = UserActivityResponse(
        proxyWallet = "0xuser",
        timestamp = 1790200200,
        conditionId = condition,
        type = type,
        size = size,
        usdcSize = usdcSize,
        price = price,
        side = side,
        outcomeIndex = outcomeIndex
    )

    @Test
    fun `aggregates multiple fills into weighted average price and total size`() {
        val fills = listOf(
            trade(size = 100.0, price = 0.90, usdcSize = 90.630),
            trade(size = 50.0, price = 0.95, usdcSize = 48.3175),
            // 同市场的卖出/其它 outcome/其它市场都应被排除
            trade(size = 10.0, price = 0.90, usdcSize = 9.0, side = "SELL"),
            trade(size = 10.0, price = 0.90, usdcSize = 9.0, outcomeIndex = 0),
            trade(size = 10.0, price = 0.90, usdcSize = 9.0, condition = "0xother"),
            trade(size = 10.0, price = 0.90, usdcSize = 9.0, type = "REDEEM")
        )

        val fill = CryptoTailSettlementService.aggregateActivityFills(fills, conditionId, 1)!!

        assertEquals(0, BigDecimal("150").compareTo(fill.size))
        // (100*0.90 + 50*0.95) / 150 = 0.916666...
        assertEquals(0, BigDecimal("0.91666666").compareTo(fill.price))
        assertEquals(0, BigDecimal("138.9475").compareTo(fill.usdcSize))
    }

    @Test
    fun `returns null when no fill matches`() {
        assertNull(
            CryptoTailSettlementService.aggregateActivityFills(
                listOf(trade(size = 1.0, price = 0.5, usdcSize = 0.5, side = "SELL")),
                conditionId,
                1
            )
        )
    }

    @Test
    fun `usdcSize is dropped when any fill is missing it so fee is estimated instead`() {
        val fill = CryptoTailSettlementService.aggregateActivityFills(
            listOf(
                trade(size = 10.0, price = 0.5, usdcSize = 5.175),
                trade(size = 10.0, price = 0.5, usdcSize = null)
            ),
            conditionId,
            1
        )!!
        assertEquals(0, BigDecimal("20").compareTo(fill.size))
        assertNull(fill.usdcSize)
    }
}
