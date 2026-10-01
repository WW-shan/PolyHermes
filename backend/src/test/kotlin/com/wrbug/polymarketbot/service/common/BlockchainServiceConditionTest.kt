package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.math.BigInteger

/**
 * getCondition：任一 payoutNumerators 查询失败/为空 → 整体失败（不能错位、不能当 0）；结算价 = numerator/denominator
 */
class BlockchainServiceConditionTest {

    private val ctf = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045"
    private val conditionId = "0x" + "ab".repeat(32)
    private val cond = conditionId.removePrefix("0x")

    private fun chain(numerators: List<Long?>, denominator: Long): FakeRpc {
        val rpc = FakeRpc()
        rpc.onCall(ctf, "0xd42dc0c2$cond", FakeRpc.word(numerators.size.toLong()))
        rpc.onCall(ctf, "0xdd34de67$cond", FakeRpc.word(denominator))
        numerators.forEachIndexed { i, n ->
            rpc.onCall(ctf, "0x0504c814$cond" + i.toString(16).padStart(64, '0'), n?.let { FakeRpc.word(it) })
        }
        return rpc
    }

    @Test
    fun `complete payouts are returned with denominator`() = runBlocking {
        val service = FakeRpc.blockchainService(chain(listOf(1, 1), 2))
        val (denominator, payouts) = service.getCondition(conditionId).getOrThrow()
        assertEquals(BigInteger.TWO, denominator)
        assertEquals(listOf(BigInteger.ONE, BigInteger.ONE), payouts)
    }

    @Test
    fun `a failed numerator query fails the whole condition instead of shifting the array`() = runBlocking {
        val service = FakeRpc.blockchainService(chain(listOf(null, 1), 1))
        assertTrue(service.getCondition(conditionId).isFailure)
    }

    @Test
    fun `settlement price is numerator over denominator`() {
        val service = MarketPriceService(
            Mockito.mock(BlockchainService::class.java),
            Mockito.mock(RetrofitFactory::class.java),
            Mockito.mock(AccountRepository::class.java),
            Mockito.mock(CryptoUtils::class.java)
        )
        assertEquals(0, BigDecimal("0.5").compareTo(service.settlementPrice(BigInteger.ONE, BigInteger.TWO)))
        assertEquals(0, BigDecimal.ONE.compareTo(service.settlementPrice(BigInteger.ONE, BigInteger.ONE)))
        assertEquals(0, BigDecimal.ZERO.compareTo(service.settlementPrice(BigInteger.ZERO, BigInteger.ONE)))
    }
}
