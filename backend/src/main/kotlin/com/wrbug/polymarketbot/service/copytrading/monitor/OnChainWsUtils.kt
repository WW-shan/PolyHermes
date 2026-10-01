package com.wrbug.polymarketbot.service.copytrading.monitor

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.wrbug.polymarketbot.api.*
import com.wrbug.polymarketbot.service.system.RpcNodeService
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * 链上 WebSocket 工具类
 * 提取公共的交易解析、工具函数等逻辑
 */
object OnChainWsUtils {
    
    private val logger = LoggerFactory.getLogger(OnChainWsUtils::class.java)
    
    // 创建 Gson 实例（与 GsonConfig 中的配置一致，使用 lenient 模式）
    private val gson: Gson = GsonBuilder()
        .setLenient()
        .create()
    
    /**
     * 解析 JSON 字符串数组
     * @param jsonString JSON 字符串，如 "[\"Yes\", \"No\"]"
     * @return 字符串列表，如果解析失败返回空列表
     */
    private fun parseStringArray(jsonString: String?): List<String> {
        if (jsonString.isNullOrBlank()) {
            return emptyList()
        }
        
        return try {
            val listType = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(jsonString, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // V2 交易所合约（标准 + NegRisk），只信任这两个合约发出的 OrderFilled 事件
    const val EXCHANGE_V2_CONTRACT = "0xe111180000d2663c0091e4f400237545b87b996b"
    const val NEG_RISK_EXCHANGE_V2_CONTRACT = "0xe2222d279d744050d28e00520010520000310f59"
    val EXCHANGE_CONTRACTS: List<String> = listOf(EXCHANGE_V2_CONTRACT, NEG_RISK_EXCHANGE_V2_CONTRACT)

    /**
     * keccak256("OrderFilled(bytes32,address,address,uint8,uint256,uint256,uint256,uint256,bytes32,bytes32)")
     * topics: [topic0, orderHash, maker, taker]；data: side, tokenId, makerAmountFilled, takerAmountFilled, fee, builder, metadata
     */
    const val ORDER_FILLED_TOPIC = "0xd543adfd945773f1a62f74f0ee55a5e3b9b1a28262980ba90b1a89f2ea84d8ee"

    private val SHARE_UNIT = BigDecimal("1000000")

    /**
     * V2 交易所 OrderFilled 事件
     * side 为 maker 订单的方向：0=BUY（maker 付出 USDC 换取份额），1=SELL（maker 付出份额换取 USDC）
     */
    data class OrderFilledEvent(
        val exchange: String = "",
        val orderHash: String = "",
        val maker: String = "",
        val taker: String = "",
        val side: Int = 0,
        val tokenId: BigInteger = BigInteger.ZERO,
        val makerAmountFilled: BigInteger = BigInteger.ZERO,
        val takerAmountFilled: BigInteger = BigInteger.ZERO,
        val fee: BigInteger = BigInteger.ZERO,
        val txHash: String = "",
        val blockNumber: Long? = null,
        val logIndex: Long? = null
    )

    /**
     * 某个钱包在一笔交易中、按 (tokenId, side) 聚合后的成交
     * sharesRaw/usdcRaw/feeRaw 均为 6 位小数的原始整数；价格 = USDC / shares，不含手续费
     */
    data class WalletFillGroup(
        val txHash: String = "",
        val tokenId: BigInteger = BigInteger.ZERO,
        val side: String = "",
        val sharesRaw: BigInteger = BigInteger.ZERO,
        val usdcRaw: BigInteger = BigInteger.ZERO,
        val feeRaw: BigInteger = BigInteger.ZERO,
        val orderHashes: List<String> = emptyList(),
        val blockNumber: Long? = null
    ) {
        val size: BigDecimal get() = sharesRaw.toBigDecimal().divide(SHARE_UNIT, 6, RoundingMode.DOWN)
        val usdcAmount: BigDecimal get() = usdcRaw.toBigDecimal().divide(SHARE_UNIT, 6, RoundingMode.DOWN)
        val fee: BigDecimal get() = feeRaw.toBigDecimal().divide(SHARE_UNIT, 6, RoundingMode.DOWN)
        val price: BigDecimal
            get() = if (sharesRaw.signum() > 0) {
                usdcRaw.toBigDecimal().divide(sharesRaw.toBigDecimal(), 8, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
    }

    /**
     * 市场信息数据类
     */
    data class MarketInfo(
        val conditionId: String,
        val outcomeIndex: Int?,  // 可空，因为可能找不到对应的 tokenId
        val outcome: String?
    )
    
    /**
     * 统一 hash（txHash / orderHash）格式：小写并带 0x 前缀
     */
    fun normalizeHash(hash: String?): String {
        val clean = hash?.trim()?.lowercase()?.removePrefix("0x") ?: return ""
        return if (clean.isEmpty()) "" else "0x$clean"
    }

    /**
     * 生成下游使用的 trade.id：activity 路径与链上路径对同一笔 (tx, tokenId, side) 必须一致。
     * 只用 txHash 会让同一 Leader 在同一 tx 内不同 token/方向的成交互相覆盖，因此附加方向与 tokenId 摘要；
     * 长度控制在 100 以内（processed_trade.leader_trade_id 为 VARCHAR(100)）。
     */
    fun buildTradeId(txHash: String, tokenId: String, side: String): String {
        val sideFlag = if (side.equals("SELL", ignoreCase = true)) "S" else "B"
        return "${normalizeHash(txHash)}:$sideFlag:${tokenId.trim().takeLast(16)}"
    }

    /**
     * 解析单条日志为 OrderFilled 事件；非 V2 交易所发出、topic 不匹配或数据不完整时返回 null
     */
    fun parseOrderFilledLog(log: JsonObject, fallbackTxHash: String = ""): OrderFilledEvent? {
        val address = log.get("address")?.takeIf { !it.isJsonNull }?.asString?.lowercase() ?: return null
        if (address !in EXCHANGE_CONTRACTS) return null
        val topics = log.getAsJsonArray("topics")?.mapNotNull { if (it.isJsonNull) null else it.asString } ?: return null
        if (topics.size < 4 || topics[0].lowercase() != ORDER_FILLED_TOPIC) return null
        val data = log.get("data")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val bytes = bytesFromHex(data)
        if (bytes.size < 32 * 5) return null
        val side = sliceBigInt32(bytes, 0)
        if (side != BigInteger.ZERO && side != BigInteger.ONE) return null
        val txHash = log.get("transactionHash")?.takeIf { !it.isJsonNull }?.asString ?: fallbackTxHash
        return OrderFilledEvent(
            exchange = address,
            orderHash = normalizeHash(topics[1]),
            maker = topicToAddress(topics[2]),
            taker = topicToAddress(topics[3]),
            side = side.toInt(),
            tokenId = sliceBigInt32(bytes, 32),
            makerAmountFilled = sliceBigInt32(bytes, 64),
            takerAmountFilled = sliceBigInt32(bytes, 96),
            fee = sliceBigInt32(bytes, 128),
            txHash = normalizeHash(txHash),
            blockNumber = log.get("blockNumber")?.takeIf { !it.isJsonNull }?.asString?.let { hexToBigInt(it).toLong() },
            logIndex = log.get("logIndex")?.takeIf { !it.isJsonNull }?.asString?.let { hexToBigInt(it).toLong() }
        )
    }

    /**
     * 从 receipt logs 中解析所有 V2 交易所发出的 OrderFilled 事件（其他合约发出的同名事件一律忽略）
     */
    fun parseOrderFilledEvents(logs: JsonArray, txHash: String = ""): List<OrderFilledEvent> {
        return logs.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            parseOrderFilledLog(element.asJsonObject, txHash)
        }
    }

    /**
     * 按 (tokenId, side) 聚合某钱包自己的订单成交。
     *
     * 只统计 maker == 钱包的事件：钱包作为 maker 被撮合时，事件里的 side/金额就是它的订单；
     * 钱包作为 taker 时，交易所会为其 taker 订单再发一条 maker=钱包、taker=交易所 的 OrderFilled。
     * taker == 钱包 的那些事件是对手方 maker 订单的成交（mint/merge 撮合时 token 与方向都不同），
     * 若再按"方向相反"计入会重复计数并产生错误的 token/方向。
     */
    fun aggregateWalletFills(events: List<OrderFilledEvent>, walletAddress: String): List<WalletFillGroup> {
        val wallet = walletAddress.lowercase()
        val groups = LinkedHashMap<Pair<BigInteger, String>, WalletFillGroup>()
        for (event in events) {
            if (event.maker.lowercase() != wallet) continue
            val isBuy = event.side == 0
            val side = if (isBuy) "BUY" else "SELL"
            val shares = if (isBuy) event.takerAmountFilled else event.makerAmountFilled
            val usdc = if (isBuy) event.makerAmountFilled else event.takerAmountFilled
            if (shares.signum() <= 0) continue
            val key = event.tokenId to side
            val existing = groups[key]
            groups[key] = if (existing == null) {
                WalletFillGroup(
                    txHash = event.txHash,
                    tokenId = event.tokenId,
                    side = side,
                    sharesRaw = shares,
                    usdcRaw = usdc,
                    feeRaw = event.fee,
                    orderHashes = listOf(event.orderHash),
                    blockNumber = event.blockNumber
                )
            } else {
                existing.copy(
                    sharesRaw = existing.sharesRaw + shares,
                    usdcRaw = existing.usdcRaw + usdc,
                    feeRaw = existing.feeRaw + event.fee,
                    orderHashes = (existing.orderHashes + event.orderHash).distinct()
                )
            }
        }
        return groups.values.toList()
    }

    /**
     * 将聚合后的成交转换为下游使用的 TradeResponse（market/outcome 由调用方按 tokenId 查询后传入）
     */
    fun toTradeResponse(
        group: WalletFillGroup,
        timestampMillis: Long?,
        walletAddress: String,
        marketInfo: MarketInfo?
    ): TradeResponse {
        return TradeResponse(
            id = buildTradeId(group.txHash, group.tokenId.toString(), group.side),
            market = marketInfo?.conditionId ?: "",
            side = group.side,
            price = group.price.stripTrailingZeros().toPlainString(),
            size = group.size.stripTrailingZeros().toPlainString(),
            timestamp = (timestampMillis ?: System.currentTimeMillis()).toString(),
            user = walletAddress,
            outcomeIndex = marketInfo?.outcomeIndex,
            outcome = marketInfo?.outcome,
            tokenId = group.tokenId.toString()
        )
    }

    /**
     * 通过 Gamma API 查询市场信息（通过 tokenId）
     * 使用 Retrofit 接口，支持 clob_token_ids 参数
     */
    suspend fun fetchMarketByTokenId(tokenId: String, retrofitFactory: RetrofitFactory): MarketInfo? {
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val marketsResponse = gammaApi.listMarkets(
                conditionIds = null,
                clobTokenIds = listOf(tokenId),
                includeTag = null
            )

            if (!marketsResponse.isSuccessful || marketsResponse.body() == null) {
                return null
            }

            // 已结束市场默认查询返回 []，需要带 closed=true 再查一次
            val market = marketsResponse.body()!!.firstOrNull() ?: run {
                val closedResponse = gammaApi.listMarkets(
                    conditionIds = null,
                    clobTokenIds = listOf(tokenId),
                    includeTag = null,
                    closed = true
                )
                if (!closedResponse.isSuccessful) return null
                closedResponse.body()?.firstOrNull()
            }

            if (market == null) {
                return null
            }
            
            // 解析 clobTokenIds（可能是 JSON 字符串或数组）
            val clobTokenIdsRaw = market.clobTokenIds ?: market.clob_token_ids
            val clobTokenIds = when {
                clobTokenIdsRaw == null -> null
                else -> {
                    // 解析 JSON 字符串
                    parseStringArray(clobTokenIdsRaw)
                }
            }
            
            // 解析 outcomes（可能是 JSON 字符串或数组）
            val outcomes = parseStringArray(market.outcomes)
            
            // 查找 tokenId 在 clobTokenIds 中的索引
            val outcomeIndex = clobTokenIds?.indexOfFirst { token ->
                token.equals(tokenId, ignoreCase = true)
            }?.takeIf { it >= 0 }
            
            // 获取 outcome 名称
            val outcome = if (outcomeIndex != null && outcomes.isNotEmpty() && outcomeIndex < outcomes.size) {
                outcomes[outcomeIndex]
            } else {
                null
            }
            
            val conditionId = market.conditionId ?: return null
            
            MarketInfo(
                conditionId = conditionId,
                outcomeIndex = outcomeIndex,
                outcome = outcome
            )
        } catch (e: Exception) {
            logger.warn("查询市场信息失败: tokenId=$tokenId, error=${e.message}")
            null
        }
    }
    
    /**
     * 获取区块时间戳
     */
    suspend fun getBlockTimestamp(blockNumber: String, rpcApi: EthereumRpcApi): Long? {
        return try {
            val blockRequest = JsonRpcRequest(
                method = "eth_getBlockByNumber",
                params = listOf(blockNumber, false)
            )
            
            val blockResponse = rpcApi.call(blockRequest)
            if (blockResponse.isSuccessful && blockResponse.body() != null) {
                val blockRpcResponse = blockResponse.body()!!
                if (blockRpcResponse.error == null && blockRpcResponse.result != null) {
                    val blockJson = blockRpcResponse.result.asJsonObject
                    val timestampHex = blockJson.get("timestamp")?.asString
                    if (timestampHex != null) {
                        BigInteger(timestampHex.removePrefix("0x"), 16).toLong() * 1000  // 转换为毫秒
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("获取区块时间戳失败: blockNumber=$blockNumber, error=${e.message}")
            null
        }
    }
    
    /**
     * 工具函数：地址转 topic（32字节，左对齐）
     */
    fun addressToTopic32(address: String): String {
        val clean = address.removePrefix("0x").lowercase()
        return "0x" + clean.padStart(64, '0')
    }
    
    /**
     * 工具函数：topic 转地址
     */
    fun topicToAddress(topic: String): String {
        val clean = topic.removePrefix("0x").lowercase()
        return "0x" + clean.takeLast(40)
    }
    
    /**
     * 工具函数：十六进制转 BigInteger
     */
    fun hexToBigInt(hex: String): BigInteger {
        val clean = hex.removePrefix("0x")
        return if (clean.isBlank()) BigInteger.ZERO else BigInteger(clean, 16)
    }
    
    /**
     * 工具函数：十六进制转字节数组
     */
    fun bytesFromHex(hex: String): ByteArray {
        val clean = hex.removePrefix("0x")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
    
    /**
     * 工具函数：从字节数组切片 BigInteger（32字节）
     */
    fun sliceBigInt32(bytes: ByteArray, offset: Int): BigInteger {
        if (offset + 32 > bytes.size) return BigInteger.ZERO
        val slice = bytes.sliceArray(offset until offset + 32)
        return BigInteger(1, slice)
    }
}

