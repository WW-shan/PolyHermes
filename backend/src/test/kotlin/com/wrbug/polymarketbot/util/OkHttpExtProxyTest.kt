package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.entity.ProxyConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

class OkHttpExtProxyTest {

    @AfterEach
    fun reset() {
        ProxyConfigProvider.setProxyConfig(null)
    }

    private fun defaultProxy(uri: URI): Proxy =
        java.net.ProxySelector.getDefault()?.select(uri)?.firstOrNull() ?: Proxy.NO_PROXY

    @Test
    fun `client built before proxy change follows current proxy config`() {
        val client = createClient().build()
        val uri = URI("https://clob.polymarket.com/")
        assertEquals(defaultProxy(uri), client.proxySelector.select(uri).first())

        ProxyConfigProvider.setProxyConfig(ProxyConfig(type = "HTTP", enabled = true, host = "127.0.0.1", port = 7890))
        val proxy = client.proxySelector.select(uri).first()
        assertEquals(Proxy.Type.HTTP, proxy.type())
        assertEquals(7890, (proxy.address() as InetSocketAddress).port)

        ProxyConfigProvider.setProxyConfig(null)
        assertEquals(defaultProxy(uri), client.proxySelector.select(uri).first())
    }

    @Test
    fun `proxy enabled client keeps default certificate and hostname verification`() {
        ProxyConfigProvider.setProxyConfig(ProxyConfig(type = "HTTP", enabled = true, host = "127.0.0.1", port = 7890))
        val client = createClient().build()
        assertFalse(client.x509TrustManager?.javaClass?.name.orEmpty().contains("TrustAll"))
        assertSame(okhttp3.internal.tls.OkHostnameVerifier, client.hostnameVerifier)
    }
}
