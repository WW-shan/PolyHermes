package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.entity.SellMatchRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CopyOrderLedgerServiceTest {

    private val repos = InMemoryLedgerRepos()
    private val ledger = repos.ledger()

    private fun bd(v: String) = BigDecimal(v)

    private fun pendingBuy(leaderQty: String = "20") = ledger.createPendingBuy(
        CopyOrderTracking(
            copyTradingId = 1, accountId = 1, leaderId = 1, marketId = "m", side = "0", outcomeIndex = 0,
            buyOrderId = "0xbuy", leaderBuyTradeId = "lt", leaderBuyQuantity = bd(leaderQty),
            quantity = BigDecimal.ZERO, price = bd("0.50"), remainingQuantity = BigDecimal.ZERO,
            status = CopyOrderTracking.STATUS_PENDING, source = "test", requestedQuantity = bd("10")
        )
    )

    @Test
    fun `partial fill records actual shares and average price`() {
        val pending = pendingBuy()
        val confirmed = ledger.confirmBuyFill(pending.id!!, bd("6"), bd("0.48"))!!
        assertEquals(0, bd("6").compareTo(confirmed.quantity))
        assertEquals(0, bd("6").compareTo(confirmed.remainingQuantity))
        assertEquals(0, bd("0.48").compareTo(confirmed.price))
        assertEquals(CopyOrderTracking.STATUS_FILLED, confirmed.status)
        // leaderBuyQuantity 在状态更新后保留
        assertEquals(0, bd("20").compareTo(repos.trackings[pending.id]!!.leaderBuyQuantity))
    }

    @Test
    fun `buy price correction preserves quantity and matching state`() {
        val pending = pendingBuy()
        val buy = ledger.confirmBuyFill(pending.id!!, bd("10"), bd("0.50"))!!
        ledger.reserveSell(record("0xs1"), listOf(detail(buy.id!!, "3")))
        val updated = ledger.updateBuyPrice(buy.id!!, bd("0.47"))!!

        assertEquals(0, bd("0.47").compareTo(updated.price))
        assertEquals(0, bd("10").compareTo(updated.quantity))
        assertEquals(0, bd("3").compareTo(updated.matchedQuantity))
        assertEquals(0, bd("7").compareTo(updated.remainingQuantity))
        assertEquals(CopyOrderTracking.STATUS_PARTIALLY_MATCHED, updated.status)
    }

    @Test
    fun `buy price correction updates matched sell details and realized pnl`() {
        val pending = pendingBuy()
        val buy = ledger.confirmBuyFill(pending.id!!, bd("10"), bd("0.50"))!!
        val reserved = ledger.reserveSell(record("0xs1"), listOf(detail(buy.id!!, "3")))
        ledger.settleSell(reserved.id!!, bd("3"), bd("0.60"), BigDecimal.ZERO)

        ledger.updateBuyPrice(buy.id!!, bd("0.55"))

        val updatedDetail = repos.details.values.single()
        assertEquals(0, bd("0.55").compareTo(updatedDetail.buyPrice))
        assertEquals(0, bd("0.15").compareTo(updatedDetail.realizedPnl))
        assertEquals(0, bd("0.15").compareTo(repos.records[reserved.id]!!.totalRealizedPnl))
    }

    @Test
    fun `zero fill fak does not create position`() {
        val pending = pendingBuy()
        assertNull(ledger.confirmBuyFill(pending.id!!, BigDecimal.ZERO, null))
        assertTrue(repos.trackings.isEmpty())
    }

    @Test
    fun `notification update keeps leader buy quantity`() {
        val pending = pendingBuy("33")
        ledger.confirmBuyFill(pending.id!!, bd("10"), null)
        val updated = ledger.markBuyNotificationSent(pending.id!!)!!
        assertEquals(true, updated.notificationSent)
        assertEquals(0, bd("33").compareTo(updated.leaderBuyQuantity))
        assertEquals(0, bd("10").compareTo(updated.remainingQuantity))
    }

    @Test
    fun `second sell reservation cannot oversell`() {
        val buy = pendingBuy()
        ledger.confirmBuyFill(buy.id!!, bd("10"), null)
        ledger.reserveSell(record("0xs1"), listOf(detail(buy.id!!, "8")))
        assertEquals(0, bd("2").compareTo(repos.trackings[buy.id]!!.remainingQuantity))
        assertThrows(CopyOrderLedgerService.InsufficientRemainingException::class.java) {
            ledger.reserveSell(record("0xs2"), listOf(detail(buy.id!!, "8")))
        }
        assertEquals(0, bd("2").compareTo(repos.trackings[buy.id]!!.remainingQuantity))
    }

    @Test
    fun `partial sell fill returns unfilled quantity to tracking`() {
        val buy = pendingBuy()
        ledger.confirmBuyFill(buy.id!!, bd("10"), null)
        val record = ledger.reserveSell(record("0xs1"), listOf(detail(buy.id!!, "8")))
        val settled = ledger.settleSell(record.id!!, bd("5"), bd("0.60"), BigDecimal.ZERO)!!
        assertEquals(SellMatchRecord.FILL_STATUS_FILLED, settled.fillStatus)
        assertEquals(0, bd("5").compareTo(settled.totalMatchedQuantity))
        // 买 0.50 卖 0.60 × 5 = 0.5
        assertEquals(0, bd("0.5").compareTo(settled.totalRealizedPnl))
        val tracking = repos.trackings[buy.id]!!
        assertEquals(0, bd("5").compareTo(tracking.remainingQuantity))
        assertEquals(0, bd("5").compareTo(tracking.matchedQuantity))
        assertEquals(CopyOrderTracking.STATUS_PARTIALLY_MATCHED, tracking.status)
    }

    @Test
    fun `rejected sell releases whole reservation`() {
        val buy = pendingBuy()
        ledger.confirmBuyFill(buy.id!!, bd("10"), null)
        val record = ledger.reserveSell(record("0xs1"), listOf(detail(buy.id!!, "10")))
        assertEquals(CopyOrderTracking.STATUS_FULLY_MATCHED, repos.trackings[buy.id]!!.status)
        assertNull(ledger.settleSell(record.id!!, BigDecimal.ZERO, null, BigDecimal.ZERO))
        assertTrue(repos.records.isEmpty())
        assertTrue(repos.details.isEmpty())
        assertEquals(0, bd("10").compareTo(repos.trackings[buy.id]!!.remainingQuantity))
        assertEquals(CopyOrderTracking.STATUS_FILLED, repos.trackings[buy.id]!!.status)
    }

    private fun record(orderId: String) = SellMatchRecord(
        copyTradingId = 1, sellOrderId = orderId, leaderSellTradeId = "ls-$orderId", marketId = "m", side = "0",
        outcomeIndex = 0, totalMatchedQuantity = bd("8"), sellPrice = bd("0.60"), totalRealizedPnl = BigDecimal.ZERO
    )

    private fun detail(trackingId: Long, qty: String) = SellMatchDetail(
        matchRecordId = 0, trackingId = trackingId, buyOrderId = "0xbuy", matchedQuantity = bd(qty),
        buyPrice = bd("0.50"), sellPrice = bd("0.60"), realizedPnl = BigDecimal.ZERO
    )
}
