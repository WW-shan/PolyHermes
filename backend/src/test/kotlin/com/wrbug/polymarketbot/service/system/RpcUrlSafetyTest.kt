package com.wrbug.polymarketbot.service.system

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class RpcUrlSafetyTest {

    private val http = setOf("http", "https")

    @Test
    fun `internal and metadata addresses are rejected`() {
        val bad = listOf(
            "http://127.0.0.1:8545",
            "http://localhost:8000/api",
            "http://10.0.0.5",
            "http://172.16.1.1",
            "http://192.168.1.1",
            "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1",
            "http://0.0.0.0",
            "http://[::1]:8545",
            "http://[fd00:ec2::254]",
            "http://[fe80::1]",
            "http://[::ffff:127.0.0.1]",
            "http://user:pass@8.8.8.8",
            "file:///etc/passwd",
            "gopher://8.8.8.8",
            "ws://8.8.8.8"
        )
        for (url in bad) {
            assertThrows(IllegalArgumentException::class.java, { RpcUrlSafety.checkPublicUrl(url, http) }, url)
        }
    }

    @Test
    fun `public ip literals are allowed`() {
        assertDoesNotThrow { RpcUrlSafety.checkPublicUrl("https://8.8.8.8/", http) }
        assertDoesNotThrow { RpcUrlSafety.checkPublicUrl("wss://1.1.1.1/ws", setOf("ws", "wss")) }
        assertTrue(RpcUrlSafety.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
        assertFalse(RpcUrlSafety.isPublicAddress(InetAddress.getByName("fc00::1")))
    }

    @Test
    fun `only polygon mainnet chain id is accepted`() {
        assertTrue(RpcUrlSafety.isPolygonMainnetChainId("0x89"))
        assertTrue(RpcUrlSafety.isPolygonMainnetChainId("0x0089"))
        assertFalse(RpcUrlSafety.isPolygonMainnetChainId("0x1"))
        assertFalse(RpcUrlSafety.isPolygonMainnetChainId("0x13882"))
        assertFalse(RpcUrlSafety.isPolygonMainnetChainId("137"))
        assertFalse(RpcUrlSafety.isPolygonMainnetChainId(null))
    }
}
