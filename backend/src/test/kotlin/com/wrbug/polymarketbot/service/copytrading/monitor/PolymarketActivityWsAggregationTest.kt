package com.wrbug.polymarketbot.service.copytrading.monitor

import com.google.gson.JsonParser
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.research.LeaderActivityIngestionService
import com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchSourceHealthService
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Activity messages aggregate by leader, transaction, token, and side before delivery.
 */
class PolymarketActivityWsAggregationTest {

    private val tx = "0x${"aa".repeat(32)}"
    private val tokenUp = "1001"
    private val tokenDown = "1002"

    @Test
    fun `fills of multiple leaders in one tx are aggregated per leader token and side`() {
        val tracking = Mockito.mock(CopyOrderTrackingService::class.java)
        runBlocking {
            Mockito.doReturn(Result.success(Unit)).`when`(tracking).processTrade(
                Mockito.anyLong(),
                Mockito.any(TradeResponse::class.java) ?: trade("BUY"),
                Mockito.anyString()
            )
        }
        val service = PolymarketActivityWsService(
            copyOrderTrackingService = tracking,
            leaderRepository = Mockito.mock(LeaderRepository::class.java),
            researchIngestionProvider = provider(Mockito.mock(LeaderActivityIngestionService::class.java)),
            researchSourceHealthProvider = provider(Mockito.mock(LeaderResearchSourceHealthService::class.java)),
            researchGlobalCaptureEnabled = false,
            researchGlobalCaptureMaxWritesPerMinute = 120
        )
        service.aggregationWindowMs = 200
        val field = PolymarketActivityWsService::class.java.getDeclaredField("monitoredAddresses")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val addresses = field.get(service) as ConcurrentHashMap<String, Long>
        addresses["0x0000000000000000000000000000000000000001"] = 1L // taker: trades and orders_matched duplicate
        addresses["0x0000000000000000000000000000000000000002"] = 2L // maker: multiple fills per side
        addresses["0x0000000000000000000000000000000000000003"] = 3L // maker: one fill per side

        val handle = PolymarketActivityWsService::class.java.getDeclaredMethod("handleMessage", String::class.java)
        handle.isAccessible = true
        this::class.java.getResource("/onchain/ws_bigtx.jsonl")!!.readText().lines()
            .filter { it.isNotBlank() }
            .forEach { handle.invoke(service, it) }
        Thread.sleep(1000)

        val trades = Mockito.mockingDetails(tracking).invocations
            .filter { it.method.name.startsWith("processTrade") }
            .map { (it.arguments[0] as Long) to (it.arguments[1] as TradeResponse) }
        assertEquals(5, trades.size, "3 个 Leader 共 5 组 (token, side)：${trades.map { "${it.first}:${it.second.side}:${it.second.size}" }}")

        fun find(leaderId: Long, side: String) = trades.single { it.first == leaderId && it.second.side == side }.second
        val takerBuy = find(1L, "BUY")
        assertEquals(0, BigDecimal("100.50").compareTo(BigDecimal(takerBuy.size)), "trades must take precedence over duplicate orders_matched data")
        assertEquals(OnChainWsUtils.buildTradeId(tx, tokenUp, "BUY"), takerBuy.id)

        val makerBuy = find(2L, "BUY")
        assertEquals(0, BigDecimal("100").compareTo(BigDecimal(makerBuy.size)))
        assertEquals(0, BigDecimal("0.45").compareTo(BigDecimal(makerBuy.price)))
        assertEquals(OnChainWsUtils.buildTradeId(tx, tokenDown, "BUY"), makerBuy.id)
        assertEquals(0, BigDecimal("100").compareTo(BigDecimal(find(2L, "SELL").size)))

        assertEquals(0, BigDecimal("69").compareTo(BigDecimal(find(3L, "SELL").size)))
        assertEquals(0, BigDecimal("8").compareTo(BigDecimal(find(3L, "BUY").size)))
        service.destroy()
    }

    @Test
    fun `dispatcher delivers trades of the same leader in arrival order`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val processed = Collections.synchronizedList(mutableListOf<String>())
        val buyStarted = CompletableDeferred<Unit>()
        val dispatcher = LeaderTradeDispatcher(scope) { leaderId, trade, _ ->
            if (trade.side == "BUY") {
                buyStarted.complete(Unit)
                delay(200) // BUY 处理较慢，SELL 也必须等 BUY 完成
            }
            processed.add("$leaderId:${trade.side}")
            Result.success(Unit)
        }
        try {
            val buy = async { dispatcher.deliver(1L, trade("BUY"), "test") }
            buyStarted.await()
            val sell = async { dispatcher.deliver(1L, trade("SELL"), "test") }
            assertTrue(buy.await())
            assertTrue(sell.await())
            assertEquals(listOf("1:BUY", "1:SELL"), processed.toList())
        } finally {
            scope.cancel()
        }
    }

    private fun trade(side: String) = TradeResponse(
        id = "t-$side", market = "m", side = side, price = "0.5", size = "1", timestamp = "1", user = null
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> provider(value: T): ObjectProvider<T> {
        val p = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<T>
        Mockito.`when`(p.getIfAvailable()).thenReturn(value)
        return p
    }
}
