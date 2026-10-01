package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.entity.CryptoTailStrategy

/**
 * Crypto Tail 下单时序规则。
 *
 * 加密市场市价单有 250ms 的 taker delay（与策略文档一致），delay 期间订单不可取消。
 * 窗口结束前必须留出完整 delay 加网络往返余量，否则订单可能越过市场可交易边界才进入匹配。
 */
object CryptoTailTiming {
    /** 官方 taker delay */
    const val TAKER_DELAY_MS = 250L

    /** 下单请求的网络往返余量 */
    const val NETWORK_MARGIN_MS = 150L

    /** 窗口结束前需要预留的总时长 */
    const val END_GUARD_MS = TAKER_DELAY_MS + NETWORK_MARGIN_MS

    fun isWithinExecutionWindow(
        strategy: CryptoTailStrategy,
        periodStartUnix: Long,
        nowMs: Long
    ): Boolean {
        val windowStartMs = (periodStartUnix + strategy.windowStartSeconds) * 1000L
        val windowEndMs = (periodStartUnix + strategy.windowEndSeconds) * 1000L
        return nowMs >= windowStartMs && nowMs < windowEndMs - END_GUARD_MS
    }

    /** 按策略周期计算当前周期起点（Unix 秒） */
    fun currentPeriodStart(intervalSeconds: Int, nowMs: Long): Long {
        val nowSeconds = nowMs / 1000
        return (nowSeconds / intervalSeconds) * intervalSeconds
    }

    /** 当前时间是否仍在该周期市场可交易范围内（周期结束前预留 taker delay 与网络余量） */
    fun isBeforeMarketClose(intervalSeconds: Int, periodStartUnix: Long, nowMs: Long): Boolean {
        val periodEndMs = (periodStartUnix + intervalSeconds) * 1000L
        return nowMs >= periodStartUnix * 1000L && nowMs < periodEndMs - END_GUARD_MS
    }
}
