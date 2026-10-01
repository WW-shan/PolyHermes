package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.util.RetrofitFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigInteger

/**
 * 赎回 calldata：必须走官方 CollateralAdapter，collateral=pUSD、indexSets=[1,2]
 * 期望值由 ethers.Interface.encodeFunctionData 生成
 */
class RelayClientServiceRedeemTest {

    private val service = RelayClientService(
        retrofitFactory = Mockito.mock(RetrofitFactory::class.java),
        systemConfigService = Mockito.mock(SystemConfigService::class.java),
        rpcNodeService = Mockito.mock(RpcNodeService::class.java)
    )

    private val conditionId = "0x6cf2b09a1aafa7c3c4fc3fae5ea8ddc6ce7aac38cfd4de7fe1a82e1d3aed6bd1"

    private val expectedRedeemData = "0x01b7037c" +
            "000000000000000000000000c011a7e12a19f7b1f670d46f03b03f3342e82dfb" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "6cf2b09a1aafa7c3c4fc3fae5ea8ddc6ce7aac38cfd4de7fe1a82e1d3aed6bd1" +
            "0000000000000000000000000000000000000000000000000000000000000080" +
            "0000000000000000000000000000000000000000000000000000000000000002" +
            "0000000000000000000000000000000000000000000000000000000000000001" +
            "0000000000000000000000000000000000000000000000000000000000000002"

    @Test
    fun `normal market redeems via CtfCollateralAdapter with pUSD and index sets 1 2`() {
        val tx = service.createRedeemTx(conditionId, listOf(BigInteger.ONE), isNegRisk = false)
        assertEquals(RelayClientService.CTF_COLLATERAL_ADAPTER, tx.to)
        assertEquals(0, tx.operation)
        assertEquals(expectedRedeemData, tx.data.lowercase())
    }

    @Test
    fun `neg risk market redeems via NegRiskCtfCollateralAdapter with same calldata`() {
        val tx = service.createRedeemTx(conditionId, listOf(BigInteger.TWO), isNegRisk = true)
        assertEquals(RelayClientService.NEG_RISK_CTF_COLLATERAL_ADAPTER, tx.to)
        assertEquals(expectedRedeemData, tx.data.lowercase())
    }

    @Test
    fun `setApprovalForAll targets CTF with operator and true`() {
        val tx = service.createCtfSetApprovalForAllTx(RelayClientService.CTF_COLLATERAL_ADAPTER)
        assertEquals(RelayClientService.CONDITIONAL_TOKENS_ADDRESS, tx.to)
        assertEquals(
            "0xa22cb465000000000000000000000000ada100db00ca00073811820692005400218fce1f" +
                    "0000000000000000000000000000000000000000000000000000000000000001",
            tx.data.lowercase()
        )
    }
}
