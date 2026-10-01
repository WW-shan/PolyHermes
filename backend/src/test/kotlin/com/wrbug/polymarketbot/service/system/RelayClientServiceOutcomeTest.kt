package com.wrbug.polymarketbot.service.system

import com.google.gson.JsonParser
import com.wrbug.polymarketbot.api.BuilderRelayerApi
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import retrofit2.Response
import java.lang.reflect.Proxy

/**
 * Relayer 终态判定（参考 ts-sdk GaslessTransactionHandle.wait）与回执核验（含 Magic RelayHub 内层状态）
 */
class RelayClientServiceOutcomeTest {

    private val service = RelayClientService(
        retrofitFactory = Mockito.mock(RetrofitFactory::class.java),
        systemConfigService = Mockito.mock(SystemConfigService::class.java),
        rpcNodeService = Mockito.mock(RpcNodeService::class.java)
    ).apply {
        relayerHashPollIntervalMs = 1
        relayerHashPollAttempts = 3
    }

    /** 按顺序返回给定状态的假 Relayer（getTransactionById） */
    private fun relayer(vararg states: Pair<String, String?>): BuilderRelayerApi {
        var i = 0
        return Proxy.newProxyInstance(BuilderRelayerApi::class.java.classLoader, arrayOf(BuilderRelayerApi::class.java)) { _, method, _ ->
            require(method.name == "getTransactionById")
            val (state, hash) = states[minOf(i++, states.size - 1)]
            Response.success(BuilderRelayerApi.RelayerTransactionStatus("tx-1", state, hash, "boom"))
        } as BuilderRelayerApi
    }

    @Test
    fun `confirmed state returns hash only after reaching terminal state`() = runBlocking {
        val api = relayer("STATE_NEW" to null, "STATE_MINED" to "0xabc", "STATE_CONFIRMED" to "0xabc")
        assertEquals("0xabc", service.waitForRelayerTerminalState(api, "tx-1").getOrThrow())
    }

    @Test
    fun `failed state returns failed exception even when hash exists`() = runBlocking {
        val api = relayer("STATE_MINED" to "0xabc", "STATE_FAILED" to "0xabc")
        val e = service.waitForRelayerTerminalState(api, "tx-1").exceptionOrNull()
        assertTrue(e is RelayClientService.RelayerTransactionFailedException)
    }

    @Test
    fun `timeout returns pending exception meaning unknown result`() = runBlocking {
        val api = relayer("STATE_MINED" to "0xabc")
        val e = service.waitForRelayerTerminalState(api, "tx-1").exceptionOrNull()
        assertTrue(e is RelayClientService.RelayerTransactionPendingException)
        assertEquals("0xabc", (e as RelayClientService.RelayerTransactionPendingException).transactionHash)
    }

    private fun receipt(status: String, relayStatus: Int?): com.google.gson.JsonObject {
        val logs = if (relayStatus == null) "[]" else """[{"address":"0xD216153c06E857cD7f72665E0aF1d7D82172F494",
            "topics":["${RelayClientService.TRANSACTION_RELAYED_TOPIC}","0x01","0x02","0x03"],
            "data":"0x34ee979100000000000000000000000000000000000000000000000000000000${relayStatus.toString(16).padStart(64, '0')}${"0".repeat(64)}"}]"""
        return JsonParser.parseString("""{"status":"$status","logs":$logs}""").asJsonObject
    }

    @Test
    fun `reverted receipt is failure`() {
        assertTrue(service.checkReceipt("0x1", receipt("0x0", null), false).isFailure)
        assertTrue(service.checkReceipt("0x1", receipt("0x1", null), false).isSuccess)
    }

    @Test
    fun `magic proxy inner failure is detected from RelayHub TransactionRelayed status`() {
        assertTrue(service.checkReceipt("0x1", receipt("0x1", 0), true).isSuccess)
        assertTrue(service.checkReceipt("0x1", receipt("0x1", 1), true).isFailure)
        // 缺少事件时无法确认内层结果，按失败处理
        assertTrue(service.checkReceipt("0x1", receipt("0x1", null), true).isFailure)
    }
}
