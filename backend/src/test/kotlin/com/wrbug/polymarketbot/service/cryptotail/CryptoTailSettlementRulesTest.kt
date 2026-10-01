package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.UserActivityResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

class CryptoTailSettlementRulesTest {

    private val conditionId = "0x7492221d7d519820168d71c5bed1931a9c1b1ae7eb733df9d7df6f0aa3163a61"

    private fun bi(v: Long) = BigInteger.valueOf(v)

    @Test
    fun `payout ratio treats incomplete or unresolved condition as unsettled`() {
        // 分母为 0：未结算
        assertNull(CryptoTailSettlementService.payoutRatio(BigInteger.ZERO, listOf(bi(1), bi(0)), 0))
        // 某个 payoutNumerator 查询失败被跳过，列表长度不等于 outcome 数
        assertNull(CryptoTailSettlementService.payoutRatio(bi(1), listOf(bi(1)), 0))
        // 分子和不等于分母
        assertNull(CryptoTailSettlementService.payoutRatio(bi(1), listOf(bi(0), bi(0)), 0))
    }

    @Test
    fun `payout ratio uses numerator over denominator including split`() {
        assertEquals(0, BigDecimal.ONE.compareTo(CryptoTailSettlementService.payoutRatio(bi(1), listOf(bi(1), bi(0)), 0)))
        assertEquals(0, BigDecimal.ZERO.compareTo(CryptoTailSettlementService.payoutRatio(bi(1), listOf(bi(1), bi(0)), 1)))
        assertEquals(0, BigDecimal("0.5").compareTo(CryptoTailSettlementService.payoutRatio(bi(2), listOf(bi(1), bi(1)), 1)))
        assertEquals(1, CryptoTailSettlementService.winnerIndex(bi(1), listOf(bi(0), bi(1))))
        assertNull(CryptoTailSettlementService.winnerIndex(bi(2), listOf(bi(1), bi(1))))
    }

    @Test
    fun `activity failure is retried and only estimated after long delay`() {
        val periodEnd = 1_000_000L
        assertNull(CryptoTailSettlementService.settlementSource(false, true, periodEnd, periodEnd + 60_000))
        assertNull(
            CryptoTailSettlementService.settlementSource(false, true, periodEnd, periodEnd + CryptoTailSettlementService.ESTIMATE_AFTER_MS - 1)
        )
        assertEquals(
            CryptoTailSettlementService.SOURCE_ESTIMATED,
            CryptoTailSettlementService.settlementSource(false, false, periodEnd, periodEnd + CryptoTailSettlementService.ESTIMATE_AFTER_MS)
        )
        assertEquals(CryptoTailSettlementService.SOURCE_TX_HASH, CryptoTailSettlementService.settlementSource(true, true, periodEnd, periodEnd))
        assertEquals(CryptoTailSettlementService.SOURCE_TIME_WINDOW, CryptoTailSettlementService.settlementSource(true, false, periodEnd, periodEnd))
    }

    private fun trade(size: Double, price: Double, usdc: Double, tx: String) = UserActivityResponse(
        proxyWallet = "0xuser",
        timestamp = 1790200200,
        conditionId = conditionId,
        type = "TRADE",
        size = size,
        usdcSize = usdc,
        transactionHash = tx,
        price = price,
        side = "BUY",
        outcomeIndex = 0
    )

    @Test
    fun `aggregation filters by order transaction hashes`() {
        val activities = listOf(
            trade(100.0, 0.95, 98.3, "0xAAA"),
            trade(10.0, 0.96, 9.9, "0xaaa"),
            // 同市场同方向的其他买单（手动买入等）不应混入
            trade(500.0, 0.50, 260.0, "0xbbb")
        )
        val fill = CryptoTailSettlementService.aggregateActivityFills(activities, conditionId, 0, setOf("0xaaa"))!!
        assertEquals(0, BigDecimal("110").compareTo(fill.size))
        assertEquals(0, BigDecimal("108.2").compareTo(fill.usdcSize))

        val all = CryptoTailSettlementService.aggregateActivityFills(activities, conditionId, 0)!!
        assertEquals(0, BigDecimal("610").compareTo(all.size))
    }

    @Test
    fun `estimated pnl uses written back price with fee inclusive cost`() {
        // 100 USDC @0.5 -> 200 份，taker 费 = 200 * 0.035 * 0.5 * 0.5 = 1.75（与 PnlCalculatorTest 同口径）
        val won = CryptoTailPnlCalculator.pnlEstimated(BigDecimal("50"), BigDecimal("0.5"), BigDecimal.ONE)!!
        assertEquals(0, BigDecimal("48.25").compareTo(won))
        val split = CryptoTailPnlCalculator.pnlEstimated(BigDecimal("50"), BigDecimal("0.5"), BigDecimal("0.5"))!!
        assertEquals(0, BigDecimal("-1.75").compareTo(split))
    }
}
