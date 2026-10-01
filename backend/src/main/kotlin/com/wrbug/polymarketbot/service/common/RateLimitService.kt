package com.wrbug.polymarketbot.service.common

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 频率限制服务（使用内存缓存）
 */
@Service
class RateLimitService {

    private val logger = LoggerFactory.getLogger(RateLimitService::class.java)

    // 重置密码限速配置
    @Value("\${rate-limit.reset-password.max-attempts:3}")
    private var resetPasswordMaxAttempts: Int = 3

    @Value("\${rate-limit.reset-password.window-seconds:60}")
    private var resetPasswordWindowSeconds: Long = 60

    // 登录限速配置
    @Value("\${rate-limit.login.max-attempts:5}")
    private var loginMaxAttempts: Int = 5

    @Value("\${rate-limit.login.window-seconds:300}")
    private var loginWindowSeconds: Long = 300  // 5分钟

    @Value("\${rate-limit.login.lockout-seconds:900}")
    private var loginLockoutSeconds: Long = 900  // 15分钟

    @Value("\${rate-limit.reset-password.global-max-attempts:30}")
    private var resetPasswordGlobalMaxAttempts: Int = 30

    // 按用户名的登录失败锁定阈值（防止分布式 IP 对同一账户暴力破解）
    @Value("\${rate-limit.login.username-max-attempts:10}")
    private var loginUsernameMaxAttempts: Int = 10

    // 全局尝试记录列表（时间戳），所有请求共享
    private val resetPasswordAttempts = AtomicReference<MutableList<Long>>(mutableListOf())

    // 重置密码按IP尝试记录（IP -> 时间戳列表）
    private val resetPasswordIpAttempts = ConcurrentHashMap<String, MutableList<Long>>()

    // 登录失败尝试记录（用户名 -> 时间戳列表）
    private val usernameFailedAttempts = ConcurrentHashMap<String, MutableList<Long>>()

    // 登录锁定记录（用户名 -> 锁定结束时间）
    private val usernameLockouts = ConcurrentHashMap<String, Long>()

    // 上次清理过期记录的时间
    @Volatile
    private var lastCleanupAt: Long = 0

    // 登录失败尝试记录（IP -> 时间戳列表）
    private val loginFailedAttempts = ConcurrentHashMap<String, MutableList<Long>>()

    // 登录锁定记录（IP -> 锁定结束时间）
    private val loginLockouts = ConcurrentHashMap<String, Long>()

    /**
     * 检查重置密码频率限制
     * - 按 IP：每个 IP 在时间窗口内最多 resetPasswordMaxAttempts 次
     * - 全局：所有请求在时间窗口内最多 resetPasswordGlobalMaxAttempts 次（较宽上限，防止匿名请求耗尽后管理员永远无法重置）
     * @param ipAddress 客户端IP地址（为空时只做全局限制）
     * @return Result，如果超过限制则返回失败
     */
    fun checkResetPasswordRateLimit(ipAddress: String? = null): Result<Unit> {
        val now = System.currentTimeMillis()
        val windowStart = now - (resetPasswordWindowSeconds * 1000)

        if (!ipAddress.isNullOrBlank()) {
            val ipAttempts = resetPasswordIpAttempts.computeIfAbsent(ipAddress) { mutableListOf() }
            synchronized(ipAttempts) {
                ipAttempts.removeIf { it < windowStart }
                if (ipAttempts.size >= resetPasswordMaxAttempts) {
                    logger.warn("重置密码IP频率限制触发: ip=$ipAddress, attempts=${ipAttempts.size}/$resetPasswordMaxAttempts")
                    return Result.failure(IllegalStateException("频率限制：1分钟内最多尝试${resetPasswordMaxAttempts}次，请稍后再试"))
                }
            }
        }

        synchronized(resetPasswordAttempts) {
            // 清理过期记录（超过时间窗口的记录）
            val validAttempts = resetPasswordAttempts.get().filter { it >= windowStart }.toMutableList()
            if (validAttempts.size >= resetPasswordGlobalMaxAttempts) {
                logger.warn("重置密码全局频率限制触发: attempts=${validAttempts.size}/$resetPasswordGlobalMaxAttempts")
                return Result.failure(IllegalStateException("频率限制：请稍后再试"))
            }
            // 记录本次尝试
            validAttempts.add(now)
            resetPasswordAttempts.set(validAttempts)
        }

        if (!ipAddress.isNullOrBlank()) {
            val ipAttempts = resetPasswordIpAttempts.computeIfAbsent(ipAddress) { mutableListOf() }
            synchronized(ipAttempts) {
                ipAttempts.add(now)
            }
        }

        cleanupIfNeeded(now)
        return Result.success(Unit)
    }

    /**
     * 检查登录频率限制（按IP，以及可选的按用户名）
     * @param ipAddress 客户端IP地址
     * @param username 登录用户名（为空时只检查IP）
     * @return Result，如果被锁定或超过限制则返回失败
     */
    fun checkLoginRateLimit(ipAddress: String, username: String? = null): Result<Unit> {
        val now = System.currentTimeMillis()
        remainingLockSeconds(loginLockouts, loginFailedAttempts, ipAddress, now)?.let { remainingSeconds ->
            logger.warn("登录锁定中: ip=$ipAddress, remainingSeconds=$remainingSeconds")
            return Result.failure(IllegalStateException("账户已被锁定，请${remainingSeconds}秒后再试"))
        }
        val usernameKey = normalizeUsername(username)
        if (usernameKey != null) {
            remainingLockSeconds(usernameLockouts, usernameFailedAttempts, usernameKey, now)?.let { remainingSeconds ->
                logger.warn("登录锁定中（用户名）: username=$usernameKey, remainingSeconds=$remainingSeconds")
                return Result.failure(IllegalStateException("账户已被锁定，请${remainingSeconds}秒后再试"))
            }
        }
        return Result.success(Unit)
    }

    /**
     * 记录登录失败尝试
     * @param ipAddress 客户端IP地址
     * @param username 登录用户名（为空时只按IP计数）
     * @return 如果触发锁定返回锁定信息，否则返回 null
     */
    fun recordLoginFailure(ipAddress: String, username: String? = null): String? {
        val now = System.currentTimeMillis()
        val ipLocked = recordFailure(loginFailedAttempts, loginLockouts, ipAddress, loginMaxAttempts, now)
        val usernameKey = normalizeUsername(username)
        val usernameLocked = usernameKey != null &&
            recordFailure(usernameFailedAttempts, usernameLockouts, usernameKey, loginUsernameMaxAttempts, now)
        cleanupIfNeeded(now)
        if (ipLocked || usernameLocked) {
            logger.warn("登录锁定触发: ip=$ipAddress, username=$usernameKey, ipLocked=$ipLocked, usernameLocked=$usernameLocked, lockoutSeconds=$loginLockoutSeconds")
            return "登录失败次数过多，账户已被锁定${loginLockoutSeconds / 60}分钟"
        }
        logger.warn("登录失败: ip=$ipAddress, username=$usernameKey")
        return null
    }

    /**
     * 登录成功时清除失败记录
     * @param ipAddress 客户端IP地址
     * @param username 登录用户名
     */
    fun clearLoginFailures(ipAddress: String, username: String? = null) {
        loginFailedAttempts.remove(ipAddress)
        loginLockouts.remove(ipAddress)
        normalizeUsername(username)?.let {
            usernameFailedAttempts.remove(it)
            usernameLockouts.remove(it)
        }
    }

    private fun normalizeUsername(username: String?): String? =
        username?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * 返回剩余锁定秒数；未锁定返回 null（锁定过期时顺便清理记录）
     */
    private fun remainingLockSeconds(
        lockouts: ConcurrentHashMap<String, Long>,
        attempts: ConcurrentHashMap<String, MutableList<Long>>,
        key: String,
        now: Long
    ): Long? {
        val lockoutEndTime = lockouts[key] ?: return null
        if (now < lockoutEndTime) {
            return (lockoutEndTime - now) / 1000
        }
        lockouts.remove(key)
        attempts.remove(key)
        return null
    }

    /**
     * 记录一次失败，达到阈值时锁定
     * @return 是否触发锁定
     */
    private fun recordFailure(
        attemptsMap: ConcurrentHashMap<String, MutableList<Long>>,
        lockouts: ConcurrentHashMap<String, Long>,
        key: String,
        maxAttempts: Int,
        now: Long
    ): Boolean {
        val windowStart = now - (loginWindowSeconds * 1000)
        val attempts = attemptsMap.computeIfAbsent(key) { mutableListOf() }
        synchronized(attempts) {
            attempts.removeIf { it < windowStart }
            attempts.add(now)
            if (attempts.size >= maxAttempts) {
                lockouts[key] = now + (loginLockoutSeconds * 1000)
                return true
            }
        }
        return false
    }

    /**
     * 定期清理过期记录，避免伪造大量 IP/用户名导致内存无限增长
     */
    private fun cleanupIfNeeded(now: Long) {
        if (now - lastCleanupAt < 60_000) {
            return
        }
        lastCleanupAt = now
        val loginWindowStart = now - (loginWindowSeconds * 1000)
        val resetWindowStart = now - (resetPasswordWindowSeconds * 1000)
        for ((map, windowStart) in listOf(
            loginFailedAttempts to loginWindowStart,
            usernameFailedAttempts to loginWindowStart,
            resetPasswordIpAttempts to resetWindowStart
        )) {
            map.entries.removeIf { entry ->
                synchronized(entry.value) { entry.value.none { it >= windowStart } }
            }
        }
        loginLockouts.entries.removeIf { it.value <= now }
        usernameLockouts.entries.removeIf { it.value <= now }
    }
}
