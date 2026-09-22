package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy

/**
 * Crypto Tail 下单时序规则。
 *
 * 官方自 2026-09-04 起将加密市场 taker delay 从 50ms 提高到 150ms。
 * 市价单在 delay 期间不可取消，因此窗口结束前必须留出完整 delay，否则订单
 * 可能已经越过市场可交易边界才进入匹配。
 */
object CryptoTailTiming {
    const val TAKER_DELAY_MS = 150L

    fun isWithinExecutionWindow(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        nowMs: Long
    ): Boolean {
        val windowStartMs = (periodStartUnix + strategy.windowStartSeconds) * 1000L
        val windowEndMs = (periodStartUnix + strategy.windowEndSeconds) * 1000L
        return nowMs >= windowStartMs && nowMs < windowEndMs - TAKER_DELAY_MS
    }
}
