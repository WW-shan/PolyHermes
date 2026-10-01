package com.wrbug.polymarketbot.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web MVC 配置
 * 注册JWT认证拦截器、管理员权限拦截器和语言拦截器
 *
 * 路径匹配由 Spring 基于解码并去除 ";" 参数后的路径完成，鉴权白名单只能在这里配置
 */
@Configuration
class WebMvcConfig(
    private val jwtAuthenticationInterceptor: JwtAuthenticationInterceptor,
    private val adminAuthorizationInterceptor: AdminAuthorizationInterceptor,
    private val localeInterceptor: LocaleInterceptor
) : WebMvcConfigurer {

    companion object {
        /** 需要鉴权的路径 */
        val AUTH_INCLUDE_PATHS = listOf("/api/**")

        /** 不需要鉴权的路径（登录、重置密码、首次使用检查、健康检查） */
        val AUTH_EXCLUDE_PATHS = listOf(
            "/api/auth/login",
            "/api/auth/reset-password",
            "/api/auth/check-first-use",
            "/api/system/health"
        )

        /** 需要管理员（默认账户）权限的路径 */
        val ADMIN_INCLUDE_PATHS = listOf("/api/system/**")

        /**
         * 管理员路径中允许普通用户访问的接口：
         * - 用户管理由 UserController 自行校验（普通用户可查看自己、修改自己密码）
         * - 系统配置读取（敏感字段已掩码，普通用户不返回凭证显示值）、自动赎回状态、Builder 是否配置
         */
        val ADMIN_EXCLUDE_PATHS = listOf(
            "/api/system/users/**",
            "/api/system/health",
            "/api/system/config/get",
            "/api/system/config/auto-redeem/status",
            "/api/system/config/builder-api-key/check"
        )
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        // 先注册语言拦截器（优先级更高）
        registry.addInterceptor(localeInterceptor)
            .addPathPatterns("/api/**")
        // 再注册JWT认证拦截器
        registry.addInterceptor(jwtAuthenticationInterceptor)
            .addPathPatterns(AUTH_INCLUDE_PATHS)
            .excludePathPatterns(AUTH_EXCLUDE_PATHS)
        // 最后注册管理员权限拦截器（依赖 JWT 拦截器写入的用户属性）
        registry.addInterceptor(adminAuthorizationInterceptor)
            .addPathPatterns(ADMIN_INCLUDE_PATHS)
            .excludePathPatterns(ADMIN_EXCLUDE_PATHS)
    }
}
