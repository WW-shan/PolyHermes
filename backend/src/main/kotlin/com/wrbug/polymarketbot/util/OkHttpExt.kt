package com.wrbug.polymarketbot.util

import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 获取代理配置（用于 WebSocket 和 HTTP 请求）
 * 从数据库读取代理配置
 * @return Proxy 对象，如果未启用代理则返回 null
 */
fun getProxyConfig(): Proxy? {
    return ProxyConfigProvider.getProxy()
}

/**
 * 所有 createClient() 创建的客户端共享的连接池
 * 代理配置变更时通过 [evictProxyAwareConnections] 清空空闲连接，避免继续复用经旧代理（或直连）建立的连接
 */
private val sharedConnectionPool = ConnectionPool()

/**
 * 动态代理选择器：每次建立新连接时读取当前代理配置，
 * 因此长期缓存的客户端（包括 Spring Bean）在代理保存/启用/禁用/删除后也会按新配置建连
 */
object DynamicProxySelector : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        val dbProxy = ProxyConfigProvider.getProxy()
        if (dbProxy != null) {
            return listOf(dbProxy)
        }
        // 未配置数据库代理时保持原行为：使用 JVM 默认代理选择器（通常为直连）
        val defaultSelector = ProxySelector.getDefault()
        if (uri != null && defaultSelector != null && defaultSelector !== this) {
            val proxies = defaultSelector.select(uri)
            if (!proxies.isNullOrEmpty()) {
                return proxies
            }
        }
        return listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
    }
}

/**
 * 清空共享连接池中的空闲连接（代理配置变更时调用）
 */
fun evictProxyAwareConnections() {
    sharedConnectionPool.evictAll()
}

/**
 * 创建OkHttpClient客户端
 * 代理配置在每次建立连接时动态读取（从数据库加载到 ProxyConfigProvider），无需重建客户端
 * @return OkHttpClient.Builder
 */
fun createClient(): OkHttpClient.Builder {
    // HTTP CONNECT 代理只转发 TLS 隧道，保持默认证书与主机名校验
    return OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionPool(sharedConnectionPool)
        .proxySelector(DynamicProxySelector)
        .proxyAuthenticator { _, response ->
            // 读取当前代理凭证；已带过凭证仍 407 时不再重试，避免死循环
            val username = ProxyConfigProvider.getProxyUsername()
            val password = ProxyConfigProvider.getProxyPassword()
            if (username == null || password == null || response.request.header("Proxy-Authorization") != null) {
                null
            } else {
                response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(username, password))
                    .build()
            }
        }
}

/**
 * 历史遗留：曾为代理连接安装"信任所有证书"的 SSL 工厂，存在中间人攻击风险。
 * HTTP CONNECT 代理只转发 TLS 隧道，不需要放宽证书校验，现改为不做任何修改（使用系统默认证书校验）
 * @return OkHttpClient.Builder
 */
@Deprecated("代理连接无需放宽证书校验，此方法不再修改 SSL 配置")
fun OkHttpClient.Builder.createSSLSocketFactory(): OkHttpClient.Builder {
    return this
}
