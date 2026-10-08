package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.dto.ActivityTradeMessage
import com.wrbug.polymarketbot.entity.ProxyConfig
import com.wrbug.polymarketbot.util.ProxyConfigProvider
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.websocket.PolymarketWebSocketClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 实盘连通性冒烟测试（默认跳过）：
 * 用生产 WebSocket 客户端（含代理）连 Polymarket 真实 activity 流，验证
 * 1) 订阅协议可用、能收到真实推送；
 * 2) 真实消息能被 ActivityTradeMessage DTO 解析；
 * 3) 真实成交消息带 transactionHash（跨来源去重依赖它）。
 *
 * 运行：POLYHERMES_LIVE_TEST=1 POLYHERMES_PROXY_HOST=127.0.0.1 POLYHERMES_PROXY_PORT=10808 ./gradlew test --tests '*LiveActivityWsSmokeTest*'
 */
class LiveActivityWsSmokeTest {

    @Test
    fun `live activity websocket delivers parseable trades with transaction hash`() {
        assumeTrue(System.getenv("POLYHERMES_LIVE_TEST") == "1", "需要显式开启实盘测试")

        val host = System.getenv("POLYHERMES_PROXY_HOST") ?: "127.0.0.1"
        val port = (System.getenv("POLYHERMES_PROXY_PORT") ?: "10808").toInt()
        ProxyConfigProvider.setProxyConfig(
            ProxyConfig(id = 1, type = "HTTP", enabled = true, host = host, port = port)
        )

        val messages = CopyOnWriteArrayList<String>()
        lateinit var client: PolymarketWebSocketClient
        client = PolymarketWebSocketClient(
            url = PolymarketConstants.ACTIVITY_WS_URL,
            sessionId = "live-smoke",
            onMessage = { messages.add(it) },
            onOpen = {
                // 与生产订阅报文一致
                client.sendSubscribe()
            }
        )
        client.connect()

        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && messages.count { it.contains("\"topic\":\"activity\"") } < 5) {
            Thread.sleep(500)
        }
        client.closeConnection()

        val activity = messages.mapNotNull { it.fromJson<ActivityTradeMessage>() }
            .filter { it.topic == "activity" }
        println("live activity messages=${messages.size}, parsed activity=${activity.size}")
        assertTrue(activity.isNotEmpty(), "未收到任何 activity 消息（检查代理/网络）")

        val trades = activity.filter { it.type == "trades" || it.type == "orders_matched" }
        assertTrue(trades.isNotEmpty(), "未收到 trades/orders_matched 消息")
        trades.take(5).forEach { m ->
            println(
                "live sample: type=${m.type} asset=${m.payload.asset.take(12)}... side=${m.payload.side} " +
                    "size=${m.payload.size} price=${m.payload.price} tx=${m.payload.transactionHash} " +
                    "trader=${m.payload.trader?.address ?: m.payload.proxyWallet}"
            )
        }
        println("live raw sample: " + messages.first { it.contains("\"topic\":\"activity\"") }.take(600))

        // 生产快速过滤（containsMonitoredAddress）必须能命中真实报文，否则会漏单
        val ws = PolymarketActivityWsService(
            copyOrderTrackingService = org.mockito.Mockito.mock(com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService::class.java),
            leaderRepository = org.mockito.Mockito.mock(com.wrbug.polymarketbot.repository.LeaderRepository::class.java),
            researchIngestionProvider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider::class.java) as org.springframework.beans.factory.ObjectProvider<com.wrbug.polymarketbot.service.copytrading.research.LeaderActivityIngestionService>,
            researchSourceHealthProvider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider::class.java) as org.springframework.beans.factory.ObjectProvider<com.wrbug.polymarketbot.service.copytrading.research.LeaderResearchSourceHealthService>,
            researchGlobalCaptureEnabled = false,
            researchGlobalCaptureMaxWritesPerMinute = 120
        )
        val addressesField = PolymarketActivityWsService::class.java.getDeclaredField("monitoredAddresses")
        addressesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val monitored = addressesField.get(ws) as java.util.concurrent.ConcurrentHashMap<String, Long>
        val contains = PolymarketActivityWsService::class.java.getDeclaredMethod("containsMonitoredAddress", String::class.java)
        contains.isAccessible = true

        var checked = 0
        var matched = 0
        for (raw in messages) {
            val parsed = raw.fromJson<ActivityTradeMessage>() ?: continue
            if (parsed.topic != "activity") continue
            val trader = (parsed.payload.trader?.address ?: parsed.payload.proxyWallet)?.lowercase() ?: continue
            monitored.clear()
            monitored[trader] = 1L
            checked++
            if (contains.invoke(ws, raw) as Boolean) matched++
        }
        println("live fast-filter: matched $matched/$checked real messages")
        assertTrue(checked > 0 && matched == checked, "生产快速过滤漏掉真实成交消息: $matched/$checked")
        ws.destroy()

        val withHash = trades.count { !it.payload.transactionHash.isNullOrBlank() }
        println("live messages with transactionHash: $withHash/${trades.size}")
        assertTrue(withHash == trades.size, "真实 activity 成交缺少 transactionHash，跨来源去重会失效")
    }

    private fun PolymarketWebSocketClient.sendSubscribe() {
        sendMessage(
            """
            {
                "action": "subscribe",
                "subscriptions": [
                    { "topic": "activity", "type": "trades" },
                    { "topic": "activity", "type": "orders_matched" }
                ]
            }
            """.trimIndent()
        )
    }

    @Test
    fun `live market context resolution works for a real token`() {
        assumeTrue(System.getenv("POLYHERMES_LIVE_TEST") == "1", "需要显式开启实盘测试")
        val host = System.getenv("POLYHERMES_PROXY_HOST") ?: "127.0.0.1"
        val port = (System.getenv("POLYHERMES_PROXY_PORT") ?: "10808").toInt()
        ProxyConfigProvider.setProxyConfig(ProxyConfig(id = 1, type = "HTTP", enabled = true, host = host, port = port))

        val gson = com.google.gson.Gson()
        val retrofitFactory = com.wrbug.polymarketbot.util.RetrofitFactory(gson)
        val clobService = com.wrbug.polymarketbot.service.common.PolymarketClobService(
            clobApi = retrofitFactory.createClobApiWithoutAuth(),
            retrofitFactory = retrofitFactory
        )
        val gamma = retrofitFactory.createGammaApi()

        // 代理/HTTP2 连接偶发抖动时重试，避免实盘冒烟测试误报
        val markets = kotlinx.coroutines.runBlocking {
            var last: retrofit2.Response<List<com.wrbug.polymarketbot.api.MarketResponse>>? = null
            for (attempt in 1..4) {
                val result = runCatching { gamma.listMarkets() }
                last = result.getOrNull()
                if (last?.isSuccessful == true && !last.body().isNullOrEmpty()) break
                println("live gamma attempt $attempt failed: ${result.exceptionOrNull()?.message ?: last?.code()}")
                kotlinx.coroutines.delay(1000)
            }
            last!!
        }
        assertTrue(markets.isSuccessful && !markets.body().isNullOrEmpty(), "Gamma 市场列表获取失败: ${markets.code()}")
        val market = markets.body()!!.first { m ->
            !m.conditionId.isNullOrBlank() &&
                (!m.clobTokenIds.isNullOrBlank() || !m.clob_token_ids.isNullOrBlank()) &&
                m.closed != true
        }
        val rawTokenIds = market.clobTokenIds ?: market.clob_token_ids!!
        val tokenIds = gson.fromJson(rawTokenIds, Array<String>::class.java).toList()
        val tokenId = tokenIds.first()
        println("live market: ${market.question} conditionId=${market.conditionId} tokenId=$tokenId")

        val book = kotlinx.coroutines.runBlocking { clobService.getOrderbookByTokenId(tokenId) }
        assertTrue(book.isSuccess, "订单簿获取失败: ${book.exceptionOrNull()?.message}")
        val orderbook = book.getOrThrow()
        println("live book: bids=${orderbook.bids.size} asks=${orderbook.asks.size} tick=${orderbook.tickSize} negRisk=${orderbook.negRisk} minOrderSize=${orderbook.minOrderSize}")

        val tickSize = orderbook.tickSize?.toBigDecimalOrNull()
            ?: kotlinx.coroutines.runBlocking { clobService.getTickSize(tokenId) }.getOrThrow()
        assertTrue(
            com.wrbug.polymarketbot.service.common.PolymarketClobService.SUPPORTED_TICK_SIZES.any { it.compareTo(tickSize) == 0 },
            "tick size 不在支持列表: $tickSize"
        )
        val negRisk = orderbook.negRisk
            ?: kotlinx.coroutines.runBlocking { clobService.getNegRisk(tokenId) }.getOrThrow()
        println("live resolved tickSize=$tickSize negRisk=$negRisk")

        // 真实 tick 下签名一笔订单：价格必须落在 tick 上，金额必须是 6 位整数
        val signing = com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService()
        val bestAsk = com.wrbug.polymarketbot.service.common.PolymarketClobService.bestAsk(orderbook)
        val price = signing.alignPriceToTick(bestAsk ?: java.math.BigDecimal("0.5"), tickSize, isBuy = true)
        val privateKey = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
        val eoa = org.web3j.crypto.Credentials.create(privateKey.removePrefix("0x")).address
        val signed = signing.createAndSignOrder(
            privateKey = privateKey,
            makerAddress = eoa,
            tokenId = tokenId,
            side = "BUY",
            price = price.toPlainString(),
            size = "5",
            signatureType = com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService.SIGNATURE_TYPE_POLY_GNOSIS_SAFE,
            exchangeContract = signing.getExchangeContract(negRisk),
            tickSize = tickSize,
            strictTick = true
        )
        assertTrue(signed.signature.length == 132, "签名长度异常: ${signed.signature.length}")
        val orderHash = signing.computeOrderHash(signed, signing.getExchangeContract(negRisk))
        assertTrue(orderHash.startsWith("0x") && orderHash.length == 66, "订单 hash 异常: $orderHash")
        println("live signed order: price=$price makerAmount=${signed.makerAmount} takerAmount=${signed.takerAmount} hash=$orderHash")
    }
}
