package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.util.PolymarketWalletDerivation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Deposit Wallet 候选选择：已部署且 owner==EOA 的候选优先；都未部署选 beacon；RPC 失败 fail-closed
 */
class BlockchainServiceDepositWalletTest {

    private val eoa = "0xa7db21782255bc1936512897bbba0cd8c549cd74"
    private val beacon = PolymarketWalletDerivation.deriveBeaconDepositWalletAddress(eoa).lowercase()
    private val uups = PolymarketWalletDerivation.deriveUupsDepositWalletAddress(eoa).lowercase()
    private val ownerWord = "0x" + eoa.removePrefix("0x").padStart(64, '0')

    @Test
    fun `real uups sample wallet matches uups derivation`() {
        // 链上实测：0xC07d5961… 为 UUPS Deposit Wallet，owner()=0xA7dB2178…，eip712Domain 为 DepositWallet/1
        assertEquals("0xc07d5961e7a361992983c1a3a865d00dc28cb1df", uups)
    }

    @Test
    fun `picks deployed uups wallet whose owner is the eoa`() = runBlocking {
        val rpc = FakeRpc()
        rpc.codeAt[beacon] = "0x"
        rpc.codeAt[uups] = "0x6080"
        rpc.onCall(uups, "0x8da5cb5b", ownerWord)
        assertEquals(uups, FakeRpc.blockchainService(rpc).resolveDepositWalletCandidate(eoa).getOrThrow().lowercase())
    }

    @Test
    fun `defaults to beacon when neither is deployed`() = runBlocking {
        val rpc = FakeRpc()
        rpc.codeAt[beacon] = "0x"
        rpc.codeAt[uups] = "0x"
        assertEquals(beacon, FakeRpc.blockchainService(rpc).resolveDepositWalletCandidate(eoa).getOrThrow().lowercase())
    }

    @Test
    fun `rpc failure on deployment check is fail-closed`() = runBlocking {
        val rpc = FakeRpc()
        rpc.codeAt[beacon] = "0x"
        // uups 的 eth_getCode 未设置 → RPC 错误
        assertTrue(FakeRpc.blockchainService(rpc).resolveDepositWalletCandidate(eoa).isFailure)
    }

    @Test
    fun `deployed wallet with a different owner is rejected`() = runBlocking {
        val rpc = FakeRpc()
        rpc.codeAt[beacon] = "0x6080"
        rpc.codeAt[uups] = "0x"
        rpc.onCall(beacon, "0x8da5cb5b", "0x" + "11".repeat(20).padStart(64, '0'))
        assertTrue(FakeRpc.blockchainService(rpc).resolveDepositWalletCandidate(eoa).isFailure)
    }
}
