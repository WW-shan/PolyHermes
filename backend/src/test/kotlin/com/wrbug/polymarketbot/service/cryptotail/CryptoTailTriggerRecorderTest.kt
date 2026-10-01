package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategyTrigger
import com.wrbug.polymarketbot.repository.CryptoTailStrategyTriggerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class CryptoTailTriggerRecorderTest {

    private val repository: CryptoTailStrategyTriggerRepository = Mockito.mock(CryptoTailStrategyTriggerRepository::class.java)
    private val recorder = CryptoTailTriggerRecorder(repository)

    /** 用内存 map 模拟 (strategy_id, period_start_unix) 唯一约束 */
    private fun stubUniqueInsert() {
        val rows = ConcurrentHashMap<String, CryptoTailStrategyTrigger>()
        val ids = AtomicLong(0)
        Mockito.`when`(repository.saveAndFlush(Mockito.any(CryptoTailStrategyTrigger::class.java))).thenAnswer { inv ->
            val t = inv.getArgument<CryptoTailStrategyTrigger>(0)
            val saved = t.copy(id = ids.incrementAndGet())
            if (rows.putIfAbsent("${t.strategyId}-${t.periodStartUnix}", saved) != null) {
                throw DataIntegrityViolationException("Duplicate entry")
            }
            saved
        }
    }

    private fun reserve(periodStartUnix: Long = 1_800L) = recorder.reserve(
        strategyId = 1L,
        periodStartUnix = periodStartUnix,
        marketTitle = "BTC Up or Down",
        outcomeIndex = 0,
        triggerPrice = BigDecimal("0.95"),
        amountUsdc = BigDecimal.ZERO,
        triggerType = "AUTO"
    )

    @Test
    fun `concurrent reservations for same period only allow one order`() = runBlocking {
        stubUniqueInsert()
        val results = (1..8).map { async(Dispatchers.Default) { reserve() } }.awaitAll()
        assertEquals(1, results.count { it != null })
        assertEquals(CryptoTailTriggerRecorder.STATUS_PENDING, results.first { it != null }!!.status)
    }

    @Test
    fun `different periods can both reserve`() {
        stubUniqueInsert()
        assertNotNull(reserve(1_800L))
        assertNotNull(reserve(2_100L))
    }

    @Test
    fun `fail reason longer than column is truncated`() {
        stubUniqueInsert()
        val reserved = reserve()!!
        recorder.completeFail(reserved, "x".repeat(5_000))
        val captor = ArgumentCaptor.forClass(CryptoTailStrategyTrigger::class.java)
        Mockito.verify(repository).save(captor.capture())
        assertEquals(CryptoTailTriggerRecorder.STATUS_FAIL, captor.value.status)
        assertEquals(500, captor.value.failReason!!.length)
    }

    @Test
    fun `success whose db write fails keeps placed mark and is not rewritten as fail`() {
        stubUniqueInsert()
        val reserved = reserve()!!
        Mockito.`when`(repository.save(Mockito.any(CryptoTailStrategyTrigger::class.java)))
            .thenThrow(RuntimeException("db down"))
        recorder.completeSuccess(reserved, "0xorder", listOf("0xAbC"), BigDecimal("0.99"), BigDecimal("10"))

        // 只尝试写过一次 success，没有再写 fail
        val captor = ArgumentCaptor.forClass(CryptoTailStrategyTrigger::class.java)
        Mockito.verify(repository, Mockito.times(1)).save(captor.capture())
        assertEquals(CryptoTailTriggerRecorder.STATUS_SUCCESS, captor.value.status)
        assertEquals("0xabc", captor.value.transactionHashes)
        // 数据库查不到也视为已触发，且不能再次占位
        assertTrue(recorder.isTriggered(1L, 1_800L))
        assertNull(reserve())
    }

    @Test
    fun `truncate does not split surrogate pair`() {
        val text = "a".repeat(499) + "😀" + "b"
        val truncated = CryptoTailTriggerRecorder.truncate(text, 500)!!
        assertEquals(499, truncated.length)
    }

    @Test
    fun `transaction hashes round trip`() {
        val joined = CryptoTailTriggerRecorder.joinTransactionHashes(listOf("0xAA", " 0xbb ", "0xaa", ""))
        assertEquals("0xaa,0xbb", joined)
        assertEquals(setOf("0xaa", "0xbb"), CryptoTailTriggerRecorder.splitTransactionHashes(joined))
        assertNull(CryptoTailTriggerRecorder.joinTransactionHashes(emptyList()))
    }
}
