package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.api.ValueResponse
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import retrofit2.Response
import java.util.Optional

class CopyTradingPollingFallbackServiceTest {

    private val copyTradingRepository: CopyTradingRepository = mock()
    private val leaderRepository: LeaderRepository = mock()
    private val accountRepository: AccountRepository = mock()
    private val copyOrderTrackingService: CopyOrderTrackingService = mock()
    private val retrofitFactory: RetrofitFactory = mock()
    private val activityWsService: PolymarketActivityWsService = mock()
    private val onChainWsService: OnChainWsService = mock()

    @Test
    fun `polling is skipped while websocket is healthy and configs prefer websocket`() {
        val service = service()
        val configs = listOf(copyTrading(useWebSocket = true))

        assertFalse(service.shouldPollLeader(configs, wsHealthy = true))
        assertTrue(service.shouldPollLeader(configs, wsHealthy = false))
    }

    @Test
    fun `polling stays active for configs that opted out of websocket`() {
        val service = service()

        assertTrue(service.shouldPollLeader(listOf(copyTrading(useWebSocket = false)), wsHealthy = true))
    }

    @Test
    fun `leader poll interval uses the smallest configured interval`() {
        val service = service()

        assertEquals(
            3_000L,
            service.pollIntervalMs(listOf(copyTrading(pollIntervalSeconds = 10), copyTrading(pollIntervalSeconds = 3)))
        )
        assertEquals(2_000L, service.pollIntervalMs(listOf(copyTrading(pollIntervalSeconds = 0))))
    }

    @Test
    fun `trade id matches the websocket trade id format`() {
        val service = service()

        assertEquals(
            OnChainWsUtils.buildTradeId("0xabc", "1234567890123456", "BUY"),
            service.tradeIdOf(activity(txHash = "0xABC", asset = "1234567890123456", side = "BUY"))
        )
    }

    @Test
    fun `non trade and incomplete activities are ignored`() {
        val service = service()

        assertNull(service.tradeIdOf(activity(type = "REDEEM")))
        assertNull(service.tradeIdOf(activity(txHash = null)))
        assertNull(service.tradeIdOf(activity(asset = null)))
        assertNull(service.tradeIdOf(activity(side = null)))
    }

    @Test
    fun `fills of the same transaction asset and side are aggregated into one trade`() {
        val service = service()
        val fills = listOf(
            activity(txHash = "0xabc", asset = "token-1", side = "BUY", size = 2.0, price = 0.4, timestamp = 1_700_000_000L),
            activity(txHash = "0xABC", asset = "token-1", side = "BUY", size = 3.0, price = 0.5, timestamp = 1_700_000_010L)
        )

        val grouped = service.aggregateByTradeId(fills)

        assertEquals(1, grouped.size)
        val tradeId = grouped.keys.single()
        val trade = service.buildAggregatedTrade(tradeId, grouped.getValue(tradeId))
        assertEquals("5", trade?.size)
        assertEquals(0, "0.46".toBigDecimal().compareTo(trade!!.price.toBigDecimal()))
        assertEquals("1700000000000", trade.timestamp)
        assertEquals("token-1", trade.tokenId)
        assertEquals("market-1", trade.market)
    }

    @Test
    fun `fallback polls data api and delivers when both websockets are down`() = runBlocking {
        val dataApi = FakeDataApi(
            listOf(activity(txHash = "0xaaa", asset = "token-1", side = "BUY", size = 4.0, price = 0.25))
        )
        val delivered = mutableListOf<TradeResponse>()
        val sources = mutableListOf<String>()
        Mockito.doAnswer { invocation ->
            delivered.add(invocation.arguments[1] as TradeResponse)
            sources.add(invocation.arguments[2] as String)
            Result.success(Unit)
        }.`when`(copyOrderTrackingService).processTrade(anyLong(), anyTrade(), anyString())
        Mockito.`when`(copyTradingRepository.findByEnabledTrue()).thenReturn(listOf(copyTrading(useWebSocket = true)))
        Mockito.`when`(leaderRepository.findById(ourLeaderId)).thenReturn(Optional.of(leader(ourLeaderId)))
        Mockito.`when`(accountRepository.findAll()).thenReturn(emptyList())
        Mockito.`when`(activityWsService.isConnected()).thenReturn(false)
        Mockito.`when`(onChainWsService.isConnected()).thenReturn(false)

        val service = service(dataApi = dataApi)
        service.clock = { FIXED_NOW }
        service.pollOnce()

        assertEquals(1, dataApi.callCount)
        assertEquals(listOf(OnChainWsUtils.buildTradeId("0xaaa", "token-1", "BUY")), delivered.map { it.id })
        assertEquals(listOf(CopyTradingPollingFallbackService.SOURCE), sources)
        assertEquals((FIXED_NOW - 3_000L) / 1000, dataApi.lastEnd)
        assertTrue(service.getPollingStats()["fallbackActive"] as Boolean)
    }

    @Test
    fun `no data api call while websocket is healthy and config prefers websocket`() = runBlocking {
        val dataApi = FakeDataApi(listOf(activity()))
        Mockito.`when`(copyTradingRepository.findByEnabledTrue()).thenReturn(listOf(copyTrading(useWebSocket = true)))
        Mockito.`when`(activityWsService.isConnected()).thenReturn(true)
        Mockito.`when`(onChainWsService.isConnected()).thenReturn(false)
        Mockito.`when`(accountRepository.findAll()).thenReturn(emptyList())

        val service = service(dataApi = dataApi)
        service.clock = { FIXED_NOW }
        service.pollOnce()

        assertEquals(0, dataApi.callCount)
        assertFalse(service.getPollingStats()["fallbackActive"] as Boolean)
    }

    @Test
    fun `config that opted out of websocket is polled even while websocket is healthy`() = runBlocking {
        val dataApi = FakeDataApi(listOf(activity(txHash = "0xbbb", asset = "token-2", side = "SELL")))
        Mockito.doReturn(Result.success(Unit)).`when`(copyOrderTrackingService)
            .processTrade(anyLong(), anyTrade(), anyString())
        Mockito.`when`(copyTradingRepository.findByEnabledTrue()).thenReturn(listOf(copyTrading(useWebSocket = false)))
        Mockito.`when`(leaderRepository.findById(ourLeaderId)).thenReturn(Optional.of(leader(ourLeaderId)))
        Mockito.`when`(accountRepository.findAll()).thenReturn(emptyList())
        Mockito.`when`(activityWsService.isConnected()).thenReturn(true)
        Mockito.`when`(onChainWsService.isConnected()).thenReturn(true)

        val service = service(dataApi = dataApi)
        service.clock = { FIXED_NOW }
        service.pollOnce()

        assertEquals(1, dataApi.callCount)
        assertFalse(service.getPollingStats()["fallbackActive"] as Boolean)
    }

    @Test
    fun `cursor is not advanced when delivery fails so the window is retried`() = runBlocking {
        val dataApi = FakeDataApi(listOf(activity(txHash = "0xccc", asset = "token-3", side = "BUY")))
        Mockito.doReturn(Result.failure<Unit>(IllegalStateException("market context unavailable")))
            .`when`(copyOrderTrackingService).processTrade(anyLong(), anyTrade(), anyString())
        Mockito.`when`(copyTradingRepository.findByEnabledTrue()).thenReturn(listOf(copyTrading(useWebSocket = true)))
        Mockito.`when`(leaderRepository.findById(ourLeaderId)).thenReturn(Optional.of(leader(ourLeaderId)))
        Mockito.`when`(accountRepository.findAll()).thenReturn(emptyList())
        Mockito.`when`(activityWsService.isConnected()).thenReturn(false)
        Mockito.`when`(onChainWsService.isConnected()).thenReturn(false)

        var now = FIXED_NOW
        val service = service(dataApi = dataApi)
        service.clock = { now }
        service.pollOnce()
        val firstStart = dataApi.lastStart
        now += 60_000L
        service.pollOnce()

        assertEquals(2, dataApi.callCount)
        assertTrue(
            dataApi.lastStart!! <= firstStart!!,
            "交付失败时下一轮窗口必须仍覆盖失败成交，实际 lastStart=${dataApi.lastStart}, firstStart=$firstStart"
        )
        // 两轮轮询都重试了同一笔失败成交，因此累计 2 次失败交付
        assertEquals(2L, service.getPollingStats()["failedTrades"])
    }

    @Test
    fun `system account addresses are never polled`() = runBlocking {
        val dataApi = FakeDataApi(listOf(activity()))
        Mockito.`when`(copyTradingRepository.findByEnabledTrue()).thenReturn(listOf(copyTrading(useWebSocket = true)))
        Mockito.`when`(leaderRepository.findById(ourLeaderId)).thenReturn(Optional.of(leader(ourLeaderId)))
        Mockito.`when`(accountRepository.findAll()).thenReturn(listOf(account(proxyAddress = LEADER_ADDRESS)))
        Mockito.`when`(activityWsService.isConnected()).thenReturn(false)
        Mockito.`when`(onChainWsService.isConnected()).thenReturn(false)

        val service = service(dataApi = dataApi)
        service.clock = { FIXED_NOW }
        service.pollOnce()

        assertEquals(0, dataApi.callCount)
    }

    @Test
    fun `leader poll cadence honours pollIntervalSeconds`() = runBlocking {
        val dataApi = FakeDataApi(emptyList())
        Mockito.`when`(copyTradingRepository.findByEnabledTrue())
            .thenReturn(listOf(copyTrading(useWebSocket = true, pollIntervalSeconds = 30)))
        Mockito.`when`(leaderRepository.findById(ourLeaderId)).thenReturn(Optional.of(leader(ourLeaderId)))
        Mockito.`when`(accountRepository.findAll()).thenReturn(emptyList())
        Mockito.`when`(activityWsService.isConnected()).thenReturn(false)
        Mockito.`when`(onChainWsService.isConnected()).thenReturn(false)

        var now = FIXED_NOW
        val service = service(dataApi = dataApi)
        service.clock = { now }
        service.pollOnce()
        now += 5_000L
        service.pollOnce()
        assertEquals(1, dataApi.callCount, "5s later than the first poll is below the 30s cadence")

        now += 30_000L
        service.pollOnce()
        assertEquals(2, dataApi.callCount)
    }

    private fun service(
        dataApi: PolymarketDataApi = FakeDataApi(emptyList())
    ): CopyTradingPollingFallbackService {
        Mockito.`when`(retrofitFactory.createDataApi()).thenReturn(dataApi)
        return CopyTradingPollingFallbackService(
            copyTradingRepository = copyTradingRepository,
            leaderRepository = leaderRepository,
            accountRepository = accountRepository,
            copyOrderTrackingService = copyOrderTrackingService,
            retrofitFactory = retrofitFactory,
            activityWsService = activityWsService,
            onChainWsService = onChainWsService,
            pollingEnabled = true,
            defaultIntervalMs = 2_000L,
            settleDelayMs = 3_000L,
            lookbackSeconds = 900L,
            overlapSeconds = 30L,
            batchLimit = 200,
            alertIntervalMs = 300_000L
        )
    }

    private fun copyTrading(
        useWebSocket: Boolean = true,
        pollIntervalSeconds: Int = 5
    ) = CopyTrading(
        accountId = 1L,
        leaderId = ourLeaderId,
        useWebSocket = useWebSocket,
        pollIntervalSeconds = pollIntervalSeconds
    )

    private fun leader(id: Long) = Leader(id = id, leaderAddress = LEADER_ADDRESS)

    private fun account(proxyAddress: String) = Account(
        id = 1L,
        privateKey = "encrypted",
        walletAddress = "0x0000000000000000000000000000000000000001",
        proxyAddress = proxyAddress
    )

    private fun activity(
        type: String = "TRADE",
        txHash: String? = "0xtx",
        asset: String? = "token-1",
        side: String? = "BUY",
        size: Double? = 1.0,
        price: Double? = 0.5,
        timestamp: Long = 1_700_000_000L
    ) = UserActivityResponse(
        proxyWallet = LEADER_ADDRESS,
        timestamp = timestamp,
        conditionId = "market-1",
        type = type,
        size = size,
        transactionHash = txHash,
        price = price,
        asset = asset,
        side = side,
        outcomeIndex = 0,
        outcome = "Yes"
    )

    private class FakeDataApi(
        private val activities: List<UserActivityResponse>
    ) : PolymarketDataApi {
        var callCount = 0
            private set
        var lastStart: Long? = null
            private set
        var lastEnd: Long? = null
            private set

        override suspend fun getPositions(
            user: String,
            market: String?,
            eventId: String?,
            sizeThreshold: Double?,
            redeemable: Boolean?,
            mergeable: Boolean?,
            limit: Int?,
            offset: Int?,
            sortBy: String?,
            sortDirection: String?,
            title: String?
        ): Response<List<PositionResponse>> = Response.success(emptyList())

        override suspend fun getTotalValue(
            user: String,
            market: List<String>?
        ): Response<List<ValueResponse>> = Response.success(emptyList())

        override suspend fun getUserActivity(
            user: String,
            limit: Int?,
            offset: Int?,
            market: List<String>?,
            eventId: List<Int>?,
            type: List<String>?,
            start: Long?,
            end: Long?,
            sortBy: String?,
            sortDirection: String?,
            side: String?
        ): Response<List<UserActivityResponse>> {
            callCount += 1
            lastStart = start
            lastEnd = end
            return Response.success(activities)
        }
    }

    companion object {
        private const val LEADER_ADDRESS = "0x9999999999999999999999999999999999999999"
        private const val ourLeaderId = 7L
        private const val FIXED_NOW = 1_700_000_100_000L
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)

    private fun anyLong(): Long = Mockito.anyLong()
    private fun anyString(): String = Mockito.anyString()
    private fun anyTrade(): TradeResponse =
        Mockito.any(TradeResponse::class.java) ?: TradeResponse(
            id = "matcher",
            market = "matcher",
            side = "BUY",
            price = "0",
            size = "0",
            timestamp = "0",
            user = null
        )
}
