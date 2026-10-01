package com.wrbug.polymarketbot.service.common

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * getTokenId 回归测试：CTF 持仓的 collateral 是 USDC.e（普通市场）/ WCOL（Neg Risk），不是 pUSD。
 * 期望值来自 Polygon 主网 CTF eth_call（condition 0xafdfc616…，由 tokenid 脚本计算）。
 */
class BlockchainServiceTokenIdTest {

    private val ctf = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045"
    private val conditionId = "0xafdfc61684a0e4af6d7fefead05e1960d937fe6244ea8b70ddc2f30bfff455f3"
    private val collection0 = "0x58a28f6beb530f7182f2dab7a48b4c8c0c83290225f9f38e84ab7a7af0e3415c"
    private val collection1 = "0x6fd5b8109c6b21fec7172ce8fa4856f9abe2651d400a048f72b9ede44b614661"
    private val usdce = "2791bca1f2de4661ed88a30c99a7a9449aa84174"
    private val pusd = "c011a7e12a19f7b1f670d46f03b03f3342e82dfb"
    private val wcol = "3a3bd7bb9528e159577f7c2e685cc81a765002e2"

    private val expected = mapOf(
        Triple(0, usdce, collection0) to "108921268766325479220801141935998587538232109978996842829408638890788709825611",
        Triple(0, pusd, collection0) to "13131989771104835767433904434861179535551185779999165095802013504732429367053",
        Triple(0, wcol, collection0) to "102635220758723477182395678506812003702776873141119853990552270295925108906547",
        Triple(1, usdce, collection1) to "77661183376443567609012245738279938181903829078283425611487245643803995519928",
        Triple(1, pusd, collection1) to "23840354549818496834236901868905254863879340834107607887570179446387773750159",
        Triple(1, wcol, collection1) to "109699174220390121238221193672040992244805362498734606932555831780883766305163"
    )

    /** 模拟链上 CTF：getCollectionId 按 indexSet 返回真实 collectionId，getPositionId 按 (collateral, collectionId) 返回真实 positionId */
    private fun fakeChain(): FakeRpc {
        val rpc = FakeRpc()
        val getCollection = "0x856296f7" + "0".repeat(64) + conditionId.removePrefix("0x")
        rpc.onCall(ctf, getCollection + "0".repeat(63) + "1", collection0)
        rpc.onCall(ctf, getCollection + "0".repeat(63) + "2", collection1)
        for ((key, positionId) in expected) {
            val (_, collateral, collection) = key
            val data = "0x39dd7530" + "0".repeat(24) + collateral + collection.removePrefix("0x")
            rpc.onCall(ctf, data, "0x" + BigInteger(positionId).toString(16).padStart(64, '0'))
        }
        return rpc
    }

    @Test
    fun `normal market derives token id with USDC_e collateral`() = runBlocking {
        val service = FakeRpc.blockchainService(fakeChain())
        assertEquals(expected[Triple(0, usdce, collection0)], service.getTokenId(conditionId, 0, negRisk = false).getOrThrow())
        assertEquals(expected[Triple(1, usdce, collection1)], service.getTokenId(conditionId, 1, negRisk = false).getOrThrow())
    }

    @Test
    fun `neg risk market derives token id with WCOL collateral`() = runBlocking {
        val service = FakeRpc.blockchainService(fakeChain())
        assertEquals(expected[Triple(0, wcol, collection0)], service.getTokenId(conditionId, 0, negRisk = true).getOrThrow())
        assertEquals(expected[Triple(1, wcol, collection1)], service.getTokenId(conditionId, 1, negRisk = true).getOrThrow())
    }
}
