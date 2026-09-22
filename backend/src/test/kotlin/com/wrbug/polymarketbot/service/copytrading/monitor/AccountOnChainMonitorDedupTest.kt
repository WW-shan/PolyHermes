package com.wrbug.polymarketbot.service.copytrading.monitor

import com.google.gson.JsonParser
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.api.JsonRpcResponse
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

/**
 * issue #61 回归测试：同一笔链上交易（txHash）被多条 log 通知重复触发时，
 * 账户监听只允许处理一次，避免卖出记录与已实现盈亏被重复计算。
 */
class AccountOnChainMonitorDedupTest {

    private val unifiedOnChainWsService = Mockito.mock(UnifiedOnChainWsService::class.java)
    private val retrofitFactory = Mockito.mock(RetrofitFactory::class.java)
    private val accountRepository = Mockito.mock(AccountRepository::class.java)
    private val copyTradingRepository = Mockito.mock(CopyTradingRepository::class.java)
    private val copyOrderTrackingRepository = Mockito.mock(CopyOrderTrackingRepository::class.java)
    private val sellMatchRecordRepository = Mockito.mock(SellMatchRecordRepository::class.java)
    private val sellMatchDetailRepository = Mockito.mock(SellMatchDetailRepository::class.java)

    private val service = AccountOnChainMonitorService(
        unifiedOnChainWsService,
        retrofitFactory,
        accountRepository,
        copyTradingRepository,
        copyOrderTrackingRepository,
        sellMatchRecordRepository,
        sellMatchDetailRepository
    )

    private val account = Account(
        id = 7L,
        privateKey = "encrypted",
        walletAddress = "0x1111111111111111111111111111111111111111",
        proxyAddress = "0x2222222222222222222222222222222222222222",
        walletType = "safe"
    )

    private val rpcCalls = AtomicInteger(0)

    /** 注册 any matcher 的同时返回一个非空占位值（Mockito.any 本身返回 null，会触发 Kotlin 非空检查） */
    private fun anyRequest(): JsonRpcRequest {
        Mockito.any(JsonRpcRequest::class.java)
        return JsonRpcRequest(method = "eth_getTransactionReceipt", params = emptyList())
    }

    private suspend fun stubRpc(
        responseProvider: () -> JsonRpcResponse = {
            // 没有可识别成交的空 receipt：处理成功后应进入幂等缓存。
            JsonRpcResponse(result = JsonParser.parseString("""{"logs":[]}"""))
        }
    ): EthereumRpcApi {
        val rpcApi = Mockito.mock(EthereumRpcApi::class.java)
        Mockito.`when`(rpcApi.call(anyRequest())).thenAnswer {
            rpcCalls.incrementAndGet()
            Response.success(responseProvider())
        }
        return rpcApi
    }

    @Test
    fun `same tx hash is processed only once even when notified repeatedly`() = runBlocking {
        service.start(listOf(account))
        val rpcApi = stubRpc()
        val client = OkHttpClient()
        val txHash = "0xabc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd"

        repeat(5) {
            service.handleAccountTransaction(account.id!!, txHash, client, rpcApi)
        }

        assertEquals(1, rpcCalls.get(), "同一 txHash 的重复通知只能触发一次处理")
    }

    @Test
    fun `different tx hashes are processed independently`() = runBlocking {
        service.start(listOf(account))
        val rpcApi = stubRpc()
        val client = OkHttpClient()

        service.handleAccountTransaction(account.id!!, "0xaaaa000000000000000000000000000000000000000000000000000000000001", client, rpcApi)
        service.handleAccountTransaction(account.id!!, "0xbbbb000000000000000000000000000000000000000000000000000000000002", client, rpcApi)

        assertEquals(2, rpcCalls.get(), "不同 txHash 应各自处理一次")
    }

    @Test
    fun `tx hash case differences are treated as the same transaction`() = runBlocking {
        service.start(listOf(account))
        val rpcApi = stubRpc()
        val client = OkHttpClient()

        service.handleAccountTransaction(account.id!!, "0xABCD000000000000000000000000000000000000000000000000000000000001", client, rpcApi)
        service.handleAccountTransaction(account.id!!, "0xabcd000000000000000000000000000000000000000000000000000000000001", client, rpcApi)

        assertEquals(1, rpcCalls.get(), "txHash 大小写不同应视为同一笔交易")
    }

    @Test
    fun `temporary receipt failure does not poison dedup cache`() = runBlocking {
        service.start(listOf(account))
        val responses = ArrayDeque(
            listOf(
                JsonRpcResponse(),
                JsonRpcResponse(result = JsonParser.parseString("""{"logs":[]}"""))
            )
        )
        val rpcApi = stubRpc { responses.removeFirst() }
        val client = OkHttpClient()
        val txHash = "0xcccc000000000000000000000000000000000000000000000000000000000003"

        service.handleAccountTransaction(account.id!!, txHash, client, rpcApi)
        service.handleAccountTransaction(account.id!!, txHash, client, rpcApi)
        service.handleAccountTransaction(account.id!!, txHash, client, rpcApi)

        assertEquals(2, rpcCalls.get(), "receipt 暂时失败不能缓存失败结果，后续通知应允许重试")
    }
}
