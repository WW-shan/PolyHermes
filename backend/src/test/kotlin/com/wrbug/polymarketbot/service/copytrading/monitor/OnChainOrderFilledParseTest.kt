package com.wrbug.polymarketbot.service.copytrading.monitor

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * 用真实链上回执（backend/src/test/resources/onchain）验证 OrderFilled 解析：
 * 只接受 V2 交易所发出的 OrderFilled；split/merge/redeem/伪造事件都不是交易。
 */
class OnChainOrderFilledParseTest {

    private val tokenUp = BigInteger("77073345465811379192787177281719442316070301560733975710653689726674114112036")
    private val tokenDown = BigInteger("82320564380274369670394295556788957448871625488159004567616219313132556758615")
    private val tokenNegRisk = BigInteger("33339798372916037220786136133406478491116150628220441951753426683441228279665")

    private fun logsOf(name: String): JsonArray {
        val text = this::class.java.getResource("/onchain/$name")!!.readText()
        return JsonParser.parseString(text).asJsonObject.getAsJsonObject("result").getAsJsonArray("logs")
    }

    private fun fills(name: String, wallet: String): List<OnChainWsUtils.WalletFillGroup> {
        val events = OnChainWsUtils.parseOrderFilledEvents(logsOf(name))
        return OnChainWsUtils.aggregateWalletFills(events, wallet)
    }

    @Test
    fun `taker buy aggregates own taker order fill and ignores counterparty maker fills`() {
        val groups = fills("trade_taker_multi_fill.json", "0x1D1AdE627D0bB0205758580B20D808573E720DF6")

        assertEquals(1, groups.size)
        val g = groups.single()
        assertEquals("BUY", g.side)
        assertEquals(tokenUp, g.tokenId)
        assertEquals(0, BigDecimal("66130.09").compareTo(g.size))
        assertEquals(0, BigDecimal("36371.5495").compareTo(g.usdcAmount))
        assertEquals(0, BigDecimal("0.55").compareTo(g.price))
        assertEquals(0, BigDecimal("818.35986").compareTo(g.fee), "fee 单独给出，不计入价格")
        assertEquals(listOf("0x7559b055fa834b98949d1e7948eb45953d27ea3b733811a4f0eb69b26828c2fc"), g.orderHashes)
    }

    @Test
    fun `maker with multiple fills in one tx is aggregated per token and side`() {
        val groups = fills("trade_taker_multi_fill.json", "0xf1404010a21a61c1f5693beee65285273be47cd8")

        assertEquals(2, groups.size)
        val sell = groups.single { it.side == "SELL" }
        assertEquals(tokenUp, sell.tokenId)
        assertEquals(0, BigDecimal("200").compareTo(sell.size))
        assertEquals(0, BigDecimal("0.55").compareTo(sell.price))
        val buy = groups.single { it.side == "BUY" }
        assertEquals(tokenDown, buy.tokenId)
        assertEquals(0, BigDecimal("400").compareTo(buy.size), "两笔 200 份的 fill 应聚合")
        assertEquals(0, BigDecimal("0.45").compareTo(buy.price))
        assertEquals(2, buy.orderHashes.size)
    }

    @Test
    fun `neg risk exchange fills are parsed for maker sell and taker buy`() {
        val makerSell = fills("trade_negrisk.json", "0x3e9ff2dbf2a6356ee47049e9f3c43a70cb55d57f").single()
        assertEquals("SELL", makerSell.side)
        assertEquals(tokenNegRisk, makerSell.tokenId)
        assertEquals(0, BigDecimal("5").compareTo(makerSell.size))
        assertEquals(0, BigDecimal("0.002").compareTo(makerSell.price))

        val takerBuy = fills("trade_negrisk.json", "0xc69bd5567b40ef4d11922eaa57e1f9be1c642076").single()
        assertEquals("BUY", takerBuy.side)
        assertEquals(0, BigDecimal("5").compareTo(takerBuy.size))
        assertEquals(0, BigDecimal("0.002").compareTo(takerBuy.price))
        assertEquals(0, BigDecimal("0.00029").compareTo(takerBuy.fee))
    }

    @Test
    fun `split merge and redeem receipts produce no trades`() {
        assertTrue(fills("split.json", "0x3f29973cfbfe15ec8e01e905b2dc36c1b69507a3").isEmpty())
        assertTrue(fills("merge.json", "0x1461da5f64f3aae303d4ce6c613f6a25363fb97e").isEmpty())
        assertTrue(fills("redeem_ctf.json", "0x904ed7c7820a434ea9b11e7333ed69190d850bec").isEmpty())
        assertTrue(OnChainWsUtils.parseOrderFilledEvents(logsOf("split.json")).isEmpty())
        assertTrue(OnChainWsUtils.parseOrderFilledEvents(logsOf("merge.json")).isEmpty())
        assertTrue(OnChainWsUtils.parseOrderFilledEvents(logsOf("redeem_ctf.json")).isEmpty())
        assertTrue(OnChainWsUtils.parseOrderFilledEvents(logsOf("redeem_adapter.json")).isEmpty())
    }

    @Test
    fun `forged transfer and order filled events from arbitrary contracts are ignored`() {
        val leader = "0x1111111111111111111111111111111111111111"
        val fake = "0x000000000000000000000000000000000000dead"
        fun topic(addr: String) = "0x" + addr.removePrefix("0x").padStart(64, '0')
        fun word(v: BigInteger) = v.toString(16).padStart(64, '0')
        val forged = """
            [
              {"address":"$fake",
               "topics":["0xc3d58168c5ae7397731d063d5bbf3d657854427343f4c083240f7aacaa2d0f62","${topic(fake)}","${topic(leader)}","${topic(fake)}"],
               "data":"0x${word(tokenUp)}${word(BigInteger("1000000000000"))}"},
              {"address":"$fake",
               "topics":["${OnChainWsUtils.ORDER_FILLED_TOPIC}","0x${"ab".repeat(32)}","${topic(leader)}","${topic(fake)}"],
               "data":"0x${word(BigInteger.ONE)}${word(tokenUp)}${word(BigInteger("1000000000"))}${word(BigInteger("990000000"))}${word(BigInteger.ZERO)}${word(BigInteger.ZERO)}${word(BigInteger.ZERO)}"}
            ]
        """.trimIndent()
        val events = OnChainWsUtils.parseOrderFilledEvents(JsonParser.parseString(forged).asJsonArray)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `trade id is identical for activity and onchain paths`() {
        val tx = "0xF4D0FDF3C11A8DD661F1F802955BE7BAC93CE9E44D6054F7035BB46D73DB94B0"
        val g = fills("trade_taker_multi_fill.json", "0x1d1ade627d0bb0205758580b20d808573e720df6").single()
        val trade = OnChainWsUtils.toTradeResponse(g, 1000L, "0x1d1ade627d0bb0205758580b20d808573e720df6", null)
        assertEquals(OnChainWsUtils.buildTradeId(tx, tokenUp.toString(), "buy"), trade.id)
        assertTrue(trade.id.length <= 100)
        assertEquals("0.55", trade.price)
        assertEquals("66130.09", trade.size)
    }
}
