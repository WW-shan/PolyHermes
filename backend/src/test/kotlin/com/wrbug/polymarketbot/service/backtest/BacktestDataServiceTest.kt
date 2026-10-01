package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.api.ValueResponse
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import retrofit2.Response
import java.math.BigDecimal
import java.util.Optional

/**
 * 回测分页与成交聚合回归测试
 */
class BacktestDataServiceTest {

    /** 模拟 Data API：start/end 按秒且包含边界，按时间升序，支持 offset */
    private class FakeApi(private val rows: List<UserActivityResponse>) : PolymarketDataApi {
        override suspend fun getPositions(user: String, market: String?, eventId: String?, sizeThreshold: Double?, redeemable: Boolean?, mergeable: Boolean?, limit: Int?, offset: Int?, sortBy: String?, sortDirection: String?, title: String?): Response<List<PositionResponse>> = Response.success(emptyList())
        override suspend fun getTotalValue(user: String, market: List<String>?): Response<List<ValueResponse>> = Response.success(emptyList())
        override suspend fun getUserActivity(user: String, limit: Int?, offset: Int?, market: List<String>?, eventId: List<Int>?, type: List<String>?, start: Long?, end: Long?, sortBy: String?, sortDirection: String?, side: String?): Response<List<UserActivityResponse>> =
            Response.success(
                rows.filter { it.timestamp >= (start ?: 0) && it.timestamp <= (end ?: Long.MAX_VALUE) }
                    .drop(offset ?: 0)
                    .take(limit ?: 500)
            )
    }

    private fun row(ts: Long, tx: String, size: Double = 10.0, price: Double = 0.5) = UserActivityResponse(
        proxyWallet = "0x1", timestamp = ts, conditionId = "c", type = "TRADE", size = size,
        usdcSize = size * price, transactionHash = tx, price = price, asset = "a0", side = "BUY", outcomeIndex = 0
    )

    private fun service(rows: List<UserActivityResponse>): BacktestDataService {
        val leaderRepo = Mockito.mock(LeaderRepository::class.java)
        Mockito.`when`(leaderRepo.findById(Mockito.anyLong())).thenReturn(Optional.of(Leader(id = 1, leaderAddress = "0x1")))
        val rf = Mockito.mock(RetrofitFactory::class.java)
        Mockito.`when`(rf.createDataApi()).thenReturn(FakeApi(rows))
        return BacktestDataService(leaderRepo, rf)
    }

    /** 按执行服务的方式翻页，返回全部交易 */
    private fun fetchAll(svc: BacktestDataService, startTime: Long, endTime: Long, limit: Int) = runBlocking {
        val all = mutableListOf<com.wrbug.polymarketbot.dto.TradeData>()
        var cursor = startTime / 1000
        var guard = 0
        while (guard++ < 1000) {
            val batch = svc.getLeaderHistoricalTradesBatch(1, startTime, endTime, cursor, limit)
            all += batch.trades
            cursor = batch.nextCursorSeconds ?: break
        }
        all
    }

    @Test
    fun `first page is not treated as last when first-second trades are filtered out`() {
        // startTime 毫秒部分非 0：起点那一秒的交易会被毫秒过滤掉，旧逻辑因此把第一页当成最后一页
        val startTime = 1_790_000_000_500L
        val startSec = startTime / 1000
        val rows = (0 until 2000).map { row(startSec + it, "0xtx$it") }
        val trades = fetchAll(service(rows), startTime, startTime + 86_400_000L, 500)
        assertEquals(1999, trades.size)
        assertEquals(1999, trades.map { it.tradeId }.toSet().size)
    }

    @Test
    fun `trades sharing a second across page boundary are neither lost nor duplicated`() {
        val startTime = 1_790_000_000_000L
        val s = startTime / 1000
        // 每秒 3 笔，limit=4，页边界总会切开某一秒
        val rows = (0 until 30).map { row(s + it / 3, "0xtx$it") }
        val trades = fetchAll(service(rows), startTime, startTime + 86_400_000L, 4)
        assertEquals(30, trades.size)
    }

    @Test
    fun `a full page within one second is paged by offset without looping`() {
        val startTime = 1_790_000_000_000L
        val s = startTime / 1000
        val rows = (0 until 12).map { row(s, "0xtx$it") } + row(s + 5, "0xlast")
        val trades = fetchAll(service(rows), startTime, startTime + 86_400_000L, 5)
        assertEquals(13, trades.size)
    }

    @Test
    fun `fills of the same tx asset and side are aggregated with weighted price`() {
        val startTime = 1_790_000_000_000L
        val s = startTime / 1000
        val rows = listOf(
            row(s, "0xsame", size = 1487.0, price = 0.946),
            row(s, "0xsame", size = 3112.0, price = 0.946),
            row(s, "0xsame", size = 1487.0, price = 0.931)
        )
        val trades = fetchAll(service(rows), startTime, startTime + 86_400_000L, 500)
        val trade = trades.single()
        assertEquals(0, BigDecimal("6086").compareTo(trade.size))
        // (1487*0.946 + 3112*0.946 + 1487*0.931) / 6086
        val expected = BigDecimal("5735.051").divide(BigDecimal("6086"), 8, java.math.RoundingMode.HALF_UP)
        assertEquals(0, expected.compareTo(trade.price))
        assertEquals("0xsame", trade.tradeId)
    }
}
