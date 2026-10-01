package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.api.TradeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 Leader 串行交付交易给下游
 * 每个 Leader 一个无界 Channel + 单消费者协程，保证同一 Leader 的 BUY/SELL 按到达顺序处理，
 * 不同 Leader 之间仍然并行。
 */
class LeaderTradeDispatcher(
    private val scope: CoroutineScope,
    private val handler: suspend (leaderId: Long, trade: TradeResponse, source: String) -> Result<Unit>
) {

    private val logger = LoggerFactory.getLogger(LeaderTradeDispatcher::class.java)

    private data class Item(
        val trade: TradeResponse,
        val source: String = "",
        val completion: CompletableDeferred<Boolean>
    )

    private val channels = ConcurrentHashMap<Long, Channel<Item>>()

    /** Queue a trade and wait for the downstream processing result. */
    suspend fun deliver(leaderId: Long, trade: TradeResponse, source: String): Boolean {
        val channel = channels.computeIfAbsent(leaderId) { id -> createChannel(id) }
        val item = Item(trade, source, CompletableDeferred())
        if (!channel.trySend(item).isSuccess) return false
        return item.completion.await()
    }

    private fun createChannel(leaderId: Long): Channel<Item> {
        val channel = Channel<Item>(Channel.UNLIMITED)
        val worker = scope.launch {
            for (item in channel) {
                val delivered = try {
                    val result = handler(leaderId, item.trade, item.source)
                    if (result.isFailure) {
                        logger.error(
                            "交付 Leader 交易失败: leaderId=$leaderId, tradeId=${item.trade.id}, source=${item.source}",
                            result.exceptionOrNull()
                        )
                    }
                    result.isSuccess
                } catch (e: CancellationException) {
                    item.completion.complete(false)
                    throw e
                } catch (e: Exception) {
                    logger.error("交付 Leader 交易失败: leaderId=$leaderId, tradeId=${item.trade.id}, source=${item.source}", e)
                    false
                }
                item.completion.complete(delivered)
            }
        }
        worker.invokeOnCompletion { cause ->
            if (cause != null) {
                channels.remove(leaderId, channel)
                channel.close()
                while (true) {
                    val queued = channel.tryReceive().getOrNull() ?: break
                    queued.completion.complete(false)
                }
            }
        }
        return channel
    }

    /**
     * 移除某个 Leader 的队列（已入队的交易仍会被处理完）
     */
    fun remove(leaderId: Long) {
        channels.remove(leaderId)?.close()
    }

    fun clear() {
        val ids = channels.keys.toList()
        ids.forEach { remove(it) }
    }
}
