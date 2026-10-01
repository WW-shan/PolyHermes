package com.wrbug.polymarketbot.service.common

import com.google.gson.JsonParser
import com.wrbug.polymarketbot.service.system.RelayClientService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * 赎回批量构建：缺少 adapter 的 CTF 授权时在同一批中先 setApprovalForAll；授权查询失败时 fail-closed
 */
class BlockchainServiceRedeemTest {

    private val proxy = "0x2222222222222222222222222222222222222222"
    private val cond1 = "0x" + "11".repeat(32)
    private val cond2 = "0x" + "22".repeat(32)
    private val isApprovedSelector = "0xe985e9c5"

    @Test
    fun `adds setApprovalForAll for adapters that are not approved`() = runBlocking {
        val rpc = FakeRpc()
        val ctf = RelayClientService.CONDITIONAL_TOKENS_ADDRESS
        // 普通 adapter 未授权，neg-risk adapter 已授权
        rpc.onCall(ctf, isApprovedSelector + "000000000000000000000000" + proxy.removePrefix("0x") +
                "000000000000000000000000" + RelayClientService.CTF_COLLATERAL_ADAPTER.removePrefix("0x").lowercase(), FakeRpc.word(0))
        rpc.onCall(ctf, isApprovedSelector + "000000000000000000000000" + proxy.removePrefix("0x") +
                "000000000000000000000000" + RelayClientService.NEG_RISK_CTF_COLLATERAL_ADAPTER.removePrefix("0x").lowercase(), FakeRpc.word(1))
        val service = FakeRpc.blockchainService(rpc)

        val calls = service.buildRedeemCalls(proxy, listOf(cond1 to false, cond2 to true, cond1 to false)).getOrThrow()

        assertEquals(3, calls.size)
        assertEquals(RelayClientService.CONDITIONAL_TOKENS_ADDRESS, calls[0].to)
        assertTrue(calls[0].data.lowercase().startsWith("0xa22cb465"))
        assertTrue(calls[0].data.lowercase().contains(RelayClientService.CTF_COLLATERAL_ADAPTER.removePrefix("0x").lowercase()))
        assertEquals(RelayClientService.CTF_COLLATERAL_ADAPTER, calls[1].to)
        assertEquals(RelayClientService.NEG_RISK_CTF_COLLATERAL_ADAPTER, calls[2].to)
    }

    @Test
    fun `approval query failure is fail-closed`() = runBlocking {
        val service = FakeRpc.blockchainService(FakeRpc())
        val result = service.buildRedeemCalls(proxy, listOf(cond1 to false))
        assertTrue(result.isFailure)
    }

    @Test
    fun `payout sums only pUSD transfers to the proxy`() {
        val service = FakeRpc.blockchainService(FakeRpc())
        val transfer = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
        val toProxy = "0x000000000000000000000000" + proxy.removePrefix("0x")
        val other = "0x000000000000000000000000" + "33".repeat(20)
        val receipt = JsonParser.parseString(
            """{"status":"0x1","logs":[
              {"address":"${RelayClientService.PUSD_ADDRESS}","topics":["$transfer","$other","$toProxy"],"data":"${FakeRpc.word(5_000_000)}"},
              {"address":"${RelayClientService.PUSD_ADDRESS}","topics":["$transfer","$proxy","$other"],"data":"${FakeRpc.word(7)}"},
              {"address":"0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174","topics":["$transfer","$other","$toProxy"],"data":"${FakeRpc.word(9)}"}
            ]}"""
        ).asJsonObject
        assertEquals(BigInteger.valueOf(5_000_000), service.sumPusdTransfersTo(receipt, proxy))
    }
}
