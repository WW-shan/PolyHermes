package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.enums.SpreadDirection
import java.math.BigDecimal

/**
 * 价差过滤规则（纯函数，便于单测）。
 *
 * - 最小价差（MIN）：按方向比较。买 Up 要求 close − open ≥ 阈值，买 Down 要求 open − close ≥ 阈值；
 *   反方向的大行情不能「确认」买入。
 * - 最大价差（MAX）：|close − open| ≤ 阈值（阈值为 0 时要求价差为 0）。
 * - 阈值不可用（null 或负数）时一律不通过（fail-closed）。
 */
object CryptoTailSpreadRule {

    /** 按下单方向计算有向价差：Up = close − open，Down = open − close */
    fun directionalSpread(open: BigDecimal, close: BigDecimal, outcomeIndex: Int): BigDecimal =
        if (outcomeIndex == 0) close.subtract(open) else open.subtract(close)

    fun passes(
        direction: SpreadDirection,
        open: BigDecimal,
        close: BigDecimal,
        outcomeIndex: Int,
        threshold: BigDecimal?
    ): Boolean {
        if (threshold == null || threshold < BigDecimal.ZERO) return false
        return if (direction == SpreadDirection.MAX) {
            close.subtract(open).abs() <= threshold
        } else {
            directionalSpread(open, close, outcomeIndex) >= threshold
        }
    }
}
