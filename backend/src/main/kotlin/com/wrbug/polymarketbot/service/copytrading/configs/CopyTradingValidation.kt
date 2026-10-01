package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.enums.ErrorCode
import java.math.BigDecimal

/**
 * 跟单配置参数校验失败（继承 IllegalArgumentException，未识别 errorCode 的调用方仍按参数错误处理）
 */
class CopyTradingValidationException(
    val errorCode: ErrorCode,
    message: String = errorCode.message
) : IllegalArgumentException(message)

/**
 * 跟单配置校验工具
 */
object CopyTradingValidation {

    /**
     * Leader 地址是否为自己的账户地址（代理钱包或 EOA，忽略大小写）
     * 代理钱包才是 Polymarket 上的交易地址，只比较 EOA 会漏掉自跟单
     */
    fun isOwnAccountAddress(address: String?, accounts: Iterable<Account>): Boolean {
        val normalized = address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return accounts.any {
            it.proxyAddress.trim().lowercase() == normalized || it.walletAddress.trim().lowercase() == normalized
        }
    }

    /**
     * 自跟单检查：Leader 地址等于该账户的代理钱包或 EOA 时拒绝
     */
    fun requireNotSelfFollow(leaderAddress: String, account: Account) {
        if (isOwnAccountAddress(leaderAddress, listOf(account))) {
            throw CopyTradingValidationException(ErrorCode.LEADER_ADDRESS_SAME_AS_ACCOUNT)
        }
    }

    /**
     * 跟单参数校验（创建与更新共用）
     */
    fun validateParams(
        copyMode: String,
        copyRatio: BigDecimal?,
        fixedAmount: BigDecimal?,
        maxOrderSize: BigDecimal?,
        minOrderSize: BigDecimal?,
        maxDailyLoss: BigDecimal?,
        maxPositionValue: BigDecimal?,
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
        maxDailyOrders: Int?
    ) {
        if (copyMode == "RATIO" && (copyRatio == null || copyRatio <= BigDecimal.ZERO)) {
            throw CopyTradingValidationException(ErrorCode.COPY_TRADING_RATIO_INVALID)
        }
        if (copyMode == "FIXED" && (fixedAmount == null || fixedAmount <= BigDecimal.ZERO)) {
            throw CopyTradingValidationException(ErrorCode.COPY_TRADING_FIXED_AMOUNT_REQUIRED)
        }
        listOf(maxOrderSize, minOrderSize, maxDailyLoss, maxPositionValue).forEach {
            if (it != null && it <= BigDecimal.ZERO) {
                throw CopyTradingValidationException(ErrorCode.COPY_TRADING_AMOUNT_INVALID)
            }
        }
        if (maxDailyOrders != null && maxDailyOrders <= 0) {
            throw CopyTradingValidationException(ErrorCode.COPY_TRADING_AMOUNT_INVALID)
        }
        if (maxOrderSize != null && minOrderSize != null && maxOrderSize < minOrderSize) {
            throw CopyTradingValidationException(ErrorCode.COPY_TRADING_ORDER_SIZE_RANGE_INVALID)
        }
        listOf(minPrice, maxPrice).forEach {
            if (it != null && (it <= BigDecimal.ZERO || it >= BigDecimal.ONE)) {
                throw CopyTradingValidationException(ErrorCode.COPY_TRADING_PRICE_RANGE_INVALID)
            }
        }
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw CopyTradingValidationException(ErrorCode.COPY_TRADING_PRICE_RANGE_INVALID)
        }
    }
}
