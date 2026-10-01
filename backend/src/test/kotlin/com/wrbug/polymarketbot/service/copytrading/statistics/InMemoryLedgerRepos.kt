package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.entity.SellMatchRecord
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import org.mockito.Mockito
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong

/**
 * 基于内存 Map 的仓储桩（只实现记账服务用到的方法）
 */
class InMemoryLedgerRepos {
    val trackings = linkedMapOf<Long, CopyOrderTracking>()
    val records = linkedMapOf<Long, SellMatchRecord>()
    val details = linkedMapOf<Long, SellMatchDetail>()
    private val ids = AtomicLong(1)

    val trackingRepo: CopyOrderTrackingRepository = Mockito.mock(CopyOrderTrackingRepository::class.java)
    val recordRepo: SellMatchRecordRepository = Mockito.mock(SellMatchRecordRepository::class.java)
    val detailRepo: SellMatchDetailRepository = Mockito.mock(SellMatchDetailRepository::class.java)

    init {
        Mockito.`when`(trackingRepo.findById(Mockito.anyLong())).thenAnswer { inv ->
            // 返回副本，模拟每次从数据库重新读取
            Optional.ofNullable(trackings[inv.getArgument<Long>(0)]?.copy())
        }
        Mockito.`when`(trackingRepo.save(Mockito.any(CopyOrderTracking::class.java))).thenAnswer { inv ->
            val entity = inv.getArgument<CopyOrderTracking>(0)
            val saved = if (entity.id == null) entity.copy(id = ids.getAndIncrement()) else entity
            trackings[saved.id!!] = saved.copy()
            saved
        }
        Mockito.doAnswer { inv -> trackings.remove(inv.getArgument<CopyOrderTracking>(0).id); null }
            .`when`(trackingRepo).delete(Mockito.any(CopyOrderTracking::class.java))

        Mockito.`when`(recordRepo.findById(Mockito.anyLong())).thenAnswer { inv ->
            Optional.ofNullable(records[inv.getArgument<Long>(0)])
        }
        Mockito.`when`(recordRepo.save(Mockito.any(SellMatchRecord::class.java))).thenAnswer { inv ->
            val entity = inv.getArgument<SellMatchRecord>(0)
            val saved = if (entity.id == null) entity.copy(id = ids.getAndIncrement()) else entity
            records[saved.id!!] = saved
            saved
        }
        Mockito.doAnswer { inv -> records.remove(inv.getArgument<SellMatchRecord>(0).id); null }
            .`when`(recordRepo).delete(Mockito.any(SellMatchRecord::class.java))

        Mockito.`when`(detailRepo.findByMatchRecordId(Mockito.anyLong())).thenAnswer { inv ->
            details.values.filter { it.matchRecordId == inv.getArgument<Long>(0) }
        }
        Mockito.`when`(detailRepo.findByTrackingId(Mockito.anyLong())).thenAnswer { inv ->
            details.values.filter { it.trackingId == inv.getArgument<Long>(0) }
        }
        Mockito.`when`(detailRepo.save(Mockito.any(SellMatchDetail::class.java))).thenAnswer { inv ->
            val entity = inv.getArgument<SellMatchDetail>(0)
            val saved = if (entity.id == null) entity.copy(id = ids.getAndIncrement()) else entity
            details[saved.id!!] = saved
            saved
        }
        Mockito.doAnswer { inv -> details.remove(inv.getArgument<SellMatchDetail>(0).id); null }
            .`when`(detailRepo).delete(Mockito.any(SellMatchDetail::class.java))
    }

    fun ledger() = CopyOrderLedgerService(trackingRepo, recordRepo, detailRepo)
}
