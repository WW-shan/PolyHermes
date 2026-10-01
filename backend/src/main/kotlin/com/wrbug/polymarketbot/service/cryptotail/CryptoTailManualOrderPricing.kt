package com.wrbug.polymarketbot.service.cryptotail

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 手动下单价格与金额规则。
 *
 * 价格必须严格落在市场 tick 上（不做静默取整），数量按签名器精度向下截断，
 * 金额按签名器 BUY 的 makerAmount 口径（数量 × 价格，保留 2 位向下取整）计算，
 * 保证返回给前端的金额与实际签名金额一致。
 */
object CryptoTailManualOrderPricing {

    /** 市场 tick 不可用时使用的默认 tick */
    val DEFAULT_TICK = BigDecimal("0.01")

    /** 数量小数位数（签名器 BUY 的 makerAmount 为 2 位，数量同样取 2 位保证金额可精确计算） */
    const val SIZE_SCALE = 2

    /** 金额（USDC）小数位数，与签名器 BUY makerAmount 一致 */
    const val AMOUNT_SCALE = 2

    data class Quote(
        val price: BigDecimal = BigDecimal.ZERO,
        val size: BigDecimal = BigDecimal.ZERO,
        val amountUsdc: BigDecimal = BigDecimal.ZERO,
        val tick: BigDecimal = DEFAULT_TICK
    )

    /**
     * 实际使用的 tick：市场 tick（签名器按该 tick 的精度签名），市场 tick 不可用时使用 0.01。
     */
    fun effectiveTick(marketTick: BigDecimal?): BigDecimal {
        return marketTick?.takeIf { it > BigDecimal.ZERO } ?: DEFAULT_TICK
    }

    /** 价格是否落在 tick 上且位于 [tick, 1 - tick] */
    fun isPriceOnTick(price: BigDecimal, tick: BigDecimal): Boolean {
        if (tick <= BigDecimal.ZERO) return false
        if (price < tick || price > BigDecimal.ONE.subtract(tick)) return false
        return price.remainder(tick).compareTo(BigDecimal.ZERO) == 0
    }

    /**
     * 生成报价；价格不在 tick 上返回 null。
     * 数量向下截断到 [SIZE_SCALE] 位，金额 = 数量 × 价格 向下截断到 [AMOUNT_SCALE] 位。
     */
    fun quote(price: BigDecimal, size: BigDecimal, tick: BigDecimal): Quote? {
        if (!isPriceOnTick(price, tick)) return null
        val normalizedSize = size.setScale(SIZE_SCALE, RoundingMode.DOWN)
        val amount = normalizedSize.multiply(price).setScale(AMOUNT_SCALE, RoundingMode.DOWN)
        return Quote(price = price.stripTrailingZeros(), size = normalizedSize, amountUsdc = amount, tick = tick)
    }
}

/** 手动下单参数/状态校验失败（参数错误类，Controller 映射为 PARAM_ERROR） */
class CryptoTailManualOrderException(message: String) : IllegalArgumentException(message)
