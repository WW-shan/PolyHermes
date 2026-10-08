package com.wrbug.polymarketbot.service.cryptotail

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 手动下单价格与金额规则。
 *
 * 价格必须严格落在市场 tick 上（不做静默取整），数量按签名器精度向下截断，
 * 金额按签名器 BUY 的 makerAmount 口径计算（与官方 SDK getOrderRawAmounts 一致：
 * 数量取 2 位小数，金额按 tick 对应的 amount 精度：0.1→3、0.01→4、0.001→5、0.0001→6），
 * 保证返回给前端的金额与实际签名金额完全一致，同时隐含价格正好落在 tick 上。
 */
object CryptoTailManualOrderPricing {

    /** 市场 tick 不可用时使用的默认 tick */
    val DEFAULT_TICK = BigDecimal("0.01")

    /** 数量小数位数（签名器 BUY 的 takerAmount 为 2 位） */
    const val SIZE_SCALE = 2

    /** 金额（USDC）默认小数位数（tick 0.01 时的签名器 makerAmount 精度） */
    const val AMOUNT_SCALE = 4

    /** tick → 金额（USDC）小数位数，与官方 SDK ROUNDING_CONFIG.amount 一致 */
    fun amountScaleForTick(tick: BigDecimal): Int = when (tick.stripTrailingZeros().toPlainString()) {
        "0.1" -> 3
        "0.01" -> 4
        "0.001" -> 5
        "0.0001" -> 6
        else -> AMOUNT_SCALE
    }

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
     * 数量向下截断到 [SIZE_SCALE] 位，金额按签名器 BUY makerAmount 的规则舍入（见 [amountScaleForTick]）。
     */
    fun quote(price: BigDecimal, size: BigDecimal, tick: BigDecimal): Quote? {
        if (!isPriceOnTick(price, tick)) return null
        val normalizedSize = size.setScale(SIZE_SCALE, RoundingMode.DOWN)
        val amount = roundBuyMakerAmount(normalizedSize.multiply(price), amountScaleForTick(tick))
        return Quote(price = price.stripTrailingZeros(), size = normalizedSize, amountUsdc = amount, tick = tick)
    }

    /**
     * 与 OrderSigningService.calculateOrderAmounts 的 BUY makerAmount 完全一致的舍入：
     * 小数位超过 amount 时先向上舍入到 amount+4 位，若仍超过则向下截断到 amount 位。
     */
    private fun roundBuyMakerAmount(value: BigDecimal, amountScale: Int): BigDecimal {
        if (decimalPlaces(value) <= amountScale) return value
        val roundedUp = value.setScale(amountScale + 4, RoundingMode.UP)
        return if (decimalPlaces(roundedUp) > amountScale) {
            roundedUp.setScale(amountScale, RoundingMode.DOWN)
        } else {
            roundedUp
        }
    }

    private fun decimalPlaces(value: BigDecimal): Int {
        if (value.scale() <= 0) return 0
        return value.stripTrailingZeros().scale()
    }
}

/** 手动下单参数/状态校验失败（参数错误类，Controller 映射为 PARAM_ERROR） */
class CryptoTailManualOrderException(message: String) : IllegalArgumentException(message)
