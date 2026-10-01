package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.api.BuilderRelayerApi
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.enums.WalletType
import com.wrbug.polymarketbot.util.Eip712Encoder
import com.wrbug.polymarketbot.util.EthereumUtils
import com.wrbug.polymarketbot.util.PolymarketWalletDerivation
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.createClient
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import retrofit2.Response
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * RelayClient 服务
 * 参考 TypeScript 项目的实现方式，提供 Gasless 交易支持
 *
 * 所有代理钱包链上操作（Safe / Magic / Deposit Wallet）均通过 Builder Relayer（Gasless）执行，
 * 必须配置 Builder API Key；提交后等待 Relayer 终态并核验回执，不以“拿到 txHash”作为成功。
 *
 * 参考：
 * - TypeScript: https://github.com/Polymarket/builder-relayer-client（client.execute、src/encode/safe.ts MultiSend）
 * - 赎回与授权清单参考 Polymarket/ts-sdk（prepareMarketRedemptionCalls、getRequiredTradingApprovals）
 */
@Service
class RelayClientService(
    private val retrofitFactory: RetrofitFactory,
    private val systemConfigService: SystemConfigService,
    private val rpcNodeService: RpcNodeService
) {

    private val logger = LoggerFactory.getLogger(RelayClientService::class.java)

    // ConditionalTokens 合约地址
    private val conditionalTokensAddress = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045"

    // pUSD 合约地址（普通市场抵押品）
    private val usdcContractAddress = "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB"

    // USDC.e 合约地址（仅用于 wrap 到 pUSD）
    private val usdceContractAddress = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174"

    // CollateralOnramp 合约地址（USDC.e → pUSD）
    private val collateralOnrampAddress = "0x93070a847efEf7F70739046A929D47a521F5B8ee"

    // Neg Risk 市场使用的 WrappedCollateral 合约地址（Polygon，neg-risk-ctf-adapter）
    private val negRiskWrappedCollateralAddress = "0x3A3BD7bb9528E159577F7C2e685CC81A765002E2"

    // 空集合ID
    private val EMPTY_SET = "0x0000000000000000000000000000000000000000000000000000000000000000"

    companion object {
        /** 普通市场赎回适配器（ts-sdk environments.production.contracts.collateralAdapter） */
        const val CTF_COLLATERAL_ADAPTER = "0xAdA100Db00Ca00073811820692005400218FcE1f"

        /** Neg Risk 市场赎回适配器（ts-sdk environments.production.contracts.negRiskCollateralAdapter） */
        const val NEG_RISK_CTF_COLLATERAL_ADAPTER = "0xadA2005600Dec949baf300f4C6120000bDB6eAab"

        /** pUSD（CollateralToken） */
        const val PUSD_ADDRESS = "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB"

        /** ConditionalTokens（ERC1155） */
        const val CONDITIONAL_TOKENS_ADDRESS = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045"

        /** 二元市场赎回 indexSets（ts-sdk BINARY_OUTCOME_INDEX_SETS） */
        val BINARY_INDEX_SETS: List<BigInteger> = listOf(BigInteger.ONE, BigInteger.TWO)

        /** 按市场类型选择赎回适配器 */
        fun redeemAdapterFor(isNegRisk: Boolean): String =
            if (isNegRisk) NEG_RISK_CTF_COLLATERAL_ADAPTER else CTF_COLLATERAL_ADAPTER

        /** Relayer 终态（参考 ts-sdk RelayerTransactionState） */
        const val STATE_CONFIRMED = "STATE_CONFIRMED"
        const val STATE_FAILED = "STATE_FAILED"
        const val STATE_INVALID = "STATE_INVALID"

        /** GSN RelayHub TransactionRelayed(address,address,address,bytes4,uint8,uint256) 事件 topic0 */
        const val TRANSACTION_RELAYED_TOPIC = "0xab74390d395916d9e0006298d47938a5def5d367054dcca78fa6ec84381f3f22"
    }

    /**
     * Relayer 交易在同步等待时间内未到终态：结果未知，可能稍后上链。
     * 调用方必须提示“处理中，请勿重复提交”，不能当作失败重试。
     */
    class RelayerTransactionPendingException(
        val transactionId: String,
        val transactionHash: String?,
        message: String
    ) : Exception(message)

    /**
     * Relayer 交易已到失败终态（STATE_FAILED / STATE_INVALID），或链上回执显示执行失败
     */
    class RelayerTransactionFailedException(
        val transactionId: String?,
        val transactionHash: String?,
        message: String
    ) : Exception(message)

    /**
     * Builder API Key 未配置：Safe / Magic / Deposit Wallet 的链上操作均需通过 Builder Relayer 执行。
     * 消息中保留“Builder API Key 未配置”，控制器据此映射为 ErrorCode.BUILDER_API_KEY_NOT_CONFIGURED(2014)。
     */
    class BuilderApiKeyNotConfiguredException(operation: String) :
        IllegalStateException("Builder API Key 未配置，无法执行$operation。请前往系统设置页面配置 Builder API Key。")

    // Polygon PROXY（Magic）合约地址，参考 builder-relayer-client config
    private val proxyFactoryAddress = "0xaB45c5A4B0c941a2F231C04C3f49182e1A254052"
    private val relayHubAddress = "0xD216153c06E857cD7f72665E0aF1d7D82172F494"
    // PROXY relayCall 内层 gasLimit（签名参数）不能给过大值，否则 RelayHub 会因 gasleft 校验失败回滚。
    private val defaultProxyGasLimit = "2400000"
    private val maxProxyGasLimit = BigInteger.valueOf(2400000)

    // Safe MultiSend 合约地址（Polygon 主网）
    private val safeMultisendAddress = "0xA238CBeb142c10Ef7Ad8442C6D1f9E89e07e7761"
    
    // Builder Relayer API 交易类型常量
    private val RELAYER_TYPE_PROXY = "PROXY"
    private val RELAYER_TYPE_SAFE = "SAFE"
    private val RELAYER_TYPE_SAFE_CREATE = "SAFE-CREATE"
    private val RELAYER_TYPE_WALLET = "WALLET"
    private val RELAYER_TYPE_WALLET_CREATE = "WALLET-CREATE"

    // Deposit Wallet 工厂地址（WALLET / WALLET-CREATE 的 to 字段）
    private val depositWalletFactoryAddress = PolymarketWalletDerivation.DEPOSIT_WALLET_FACTORY

    // Deposit Wallet 批量签名有效期（秒），参考 ts-sdk DEPOSIT_WALLET_DEFAULT_DEADLINE_SECONDS
    private val depositWalletDeadlineSeconds = 600L

    // 提交后等待 Relayer 终态的轮询次数与间隔（同步等待上限约 60 秒，超时返回“处理中”）
    internal var relayerHashPollAttempts = 30
    internal var relayerHashPollIntervalMs = 2000L

    // Relayer 确认后查询回执的次数（节点同步可能略慢）
    internal var relayerReceiptPollAttempts = 5

    /** 在途（结果未知）Relayer 交易：代理地址（小写）→ transactionID */
    private val inFlightTransactions = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Safe 代理工厂（用于 SAFE-CREATE 部署）
    private val safeProxyFactoryAddress = PolymarketConstants.SAFE_PROXY_FACTORY_ADDRESS

    // 只读请求可在本次调用内切换 RPC；交易提交遇到不确定网络错误时不会自动重发。
    private val polygonRpcApi: EthereumRpcApi
        get() = rpcNodeService.createFailoverRpcApi()

    /** 遇到 429 限流时的重试次数 */
    private val builderRelayerRateLimitMaxAttempts = 3

    /** 429 限流重试退避基数（毫秒），第 n 次重试等待 baseMs * 2^(n-1) */
    private val builderRelayerRateLimitBackoffMs = 2000L

    /** Builder Relayer 配额用尽后的冷却截止时间（毫秒时间戳），在此时间前不再发起赎回 */
    private val builderRelayerQuotaBlockedUntilMs = AtomicLong(0)

    /**
     * 是否处于 Builder Relayer 配额冷却期（配额用尽后在该时间内不再发起赎回）。
     */
    fun isBuilderRelayerQuotaBlocked(): Boolean = System.currentTimeMillis() < builderRelayerQuotaBlockedUntilMs.get()

    /**
     * 配额冷却剩余秒数，未在冷却期时返回 0。
     */
    fun getBuilderRelayerQuotaBlockedRemainingSeconds(): Long {
        val remaining = (builderRelayerQuotaBlockedUntilMs.get() - System.currentTimeMillis()) / 1000
        return maxOf(0, remaining)
    }

    /**
     * 从 API 错误响应中解析 "quota exceeded... resets in N seconds"，并设置配额冷却截止时间。
     */
    private fun updateQuotaBlockedFromErrorBody(errorBody: String) {
        if (!errorBody.contains("quota exceeded", ignoreCase = true)) return
        val regex = Regex("resets\\s+in\\s+(\\d+)\\s+seconds", RegexOption.IGNORE_CASE)
        regex.find(errorBody)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { seconds ->
            val untilMs = System.currentTimeMillis() + seconds * 1000
            builderRelayerQuotaBlockedUntilMs.set(untilMs)
            logger.warn("Builder Relayer 配额已用尽，${seconds}秒内不再发起赎回")
        }
    }

    /**
     * 对 Builder Relayer API 调用进行 429 限流重试（指数退避）。
     * 当 HTTP 状态为 429（Too Many Requests，如 Cloudflare 1015）时等待后重试，避免瞬时限流导致赎回失败。
     */
    private suspend fun <T> withBuilderRelayerRateLimitRetry(block: suspend () -> Response<T>): Response<T> {
        var lastResponse: Response<T>? = null
        for (attempt in 1..builderRelayerRateLimitMaxAttempts) {
            val response = block()
            lastResponse = response
            if (response.code() != 429) return response
            if (attempt == builderRelayerRateLimitMaxAttempts) return response
            val delayMs = builderRelayerRateLimitBackoffMs * (1L shl (attempt - 1))
            logger.warn("Builder Relayer API 限流(429)，${delayMs}ms 后重试 (${attempt}/${builderRelayerRateLimitMaxAttempts})")
            delay(delayMs)
        }
        return lastResponse!!
    }

    /**
     * 获取 Builder Relayer API 客户端（动态获取，因为配置可能更新）
     */
    private fun getBuilderRelayerApi(): BuilderRelayerApi? {
        val builderApiKey = systemConfigService.getBuilderApiKey()
        val builderSecret = systemConfigService.getBuilderSecret()
        val builderPassphrase = systemConfigService.getBuilderPassphrase()

        if (isBuilderRelayerEnabled(builderApiKey, builderSecret, builderPassphrase)) {
            return retrofitFactory.createBuilderRelayerApi(
                relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
                apiKey = builderApiKey!!,
                secret = builderSecret!!,
                passphrase = builderPassphrase!!
            )
        }
        return null
    }

    /**
     * 检查是否启用了 Builder Relayer（Gasless 交易）
     */
    private fun isBuilderRelayerEnabled(
        builderApiKey: String?,
        builderSecret: String?,
        builderPassphrase: String?
    ): Boolean {
        return PolymarketConstants.BUILDER_RELAYER_URL.isNotBlank() &&
                builderApiKey != null && builderApiKey.isNotBlank() &&
                builderSecret != null && builderSecret.isNotBlank() &&
                builderPassphrase != null && builderPassphrase.isNotBlank()
    }

    /**
     * 检查 Builder API Key 是否已配置
     */
    fun isBuilderApiKeyConfigured(): Boolean {
        return systemConfigService.isBuilderApiKeyConfigured()
    }

    /**
     * 检查 Builder Relayer API 健康状态（用于 API 健康检查）
     */
    suspend fun checkBuilderRelayerApiHealth(): Result<Long> {
        return try {
            val builderApiKey = systemConfigService.getBuilderApiKey()
            val builderSecret = systemConfigService.getBuilderSecret()
            val builderPassphrase = systemConfigService.getBuilderPassphrase()

            if (builderApiKey == null || builderSecret == null || builderPassphrase == null) {
                return Result.failure(IllegalStateException("Builder API Key 未配置"))
            }

            val relayerApi = retrofitFactory.createBuilderRelayerApi(
                relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
                apiKey = builderApiKey,
                secret = builderSecret,
                passphrase = builderPassphrase
            )

            // 使用一个测试地址来检查 API 是否可用（使用一个已知的地址，如零地址）
            val testAddress = "0x0000000000000000000000000000000000000000"
            val startTime = System.currentTimeMillis()
            val response = relayerApi.getDeployed(testAddress)
            val responseTime = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                Result.success(responseTime)
            } else {
                val errorBody = response.errorBody()?.string() ?: "未知错误"
                updateQuotaBlockedFromErrorBody(errorBody)
                Result.failure(Exception("Builder Relayer API 调用失败: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            logger.error("检查 Builder Relayer API 健康状态失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 赎回仓位参数
     */
    data class RedeemParams(
        val conditionId: String,      // 市场条件ID
        val outcomeIndex: Int         // 结果索引（0, 1, 2...）
    )

    /**
     * Safe 交易结构
     * 参考 TypeScript: @polymarket/builder-relayer-client 的 SafeTransaction
     */
    data class SafeTransaction(
        val to: String,               // 目标合约地址
        val operation: Int = 0,       // 0 = CALL, 1 = DELEGATE_CALL
        val data: String,            // 调用数据
        val value: String = "0"      // 发送的 ETH 数量
    )

    /**
     * 创建赎回交易（单个 outcomeIndex）
     * 参考 TypeScript: utils/redeem.ts 的 createRedeemTx
     *
     * @param params 赎回参数
     * @return Safe 交易对象
     */
    fun createRedeemTx(params: RedeemParams): SafeTransaction {
        val (conditionId, outcomeIndex) = params

        // 计算 indexSet = 2^outcomeIndex
        val indexSet = BigInteger.TWO.pow(outcomeIndex)
        return createRedeemTx(conditionId, listOf(indexSet))
    }

    /**
     * 创建赎回交易（官方 V2 路径：通过 CollateralAdapter 赎回，直接得到 pUSD）
     * 参考 ts-sdk actions/positions.ts prepareMarketRedemptionCalls + abis.ts ctfRedeemPositionsCall：
     * 普通市场调用 CtfCollateralAdapter，Neg Risk 市场调用 NegRiskCtfCollateralAdapter，
     * 参数固定为 redeemPositions(pUSD, 0x0, conditionId, [1, 2])。
     *
     * 注意：CTF 仓位本身按 USDC.e / WCOL 抵押推导，直接对 CTF 以 pUSD 作 collateral 调用
     * redeemPositions 会“成功但 payout=0”，因此禁止直接调用 CTF。
     * 调用前代理钱包需对该 adapter 做 CTF setApprovalForAll（见 [createCtfSetApprovalForAllTx]）。
     *
     * @param conditionId 市场条件ID
     * @param indexSets 保留参数（兼容旧调用方）；adapter 按官方实现总是赎回 [1, 2]
     * @param isNegRisk 是否为 Neg Risk 市场（决定使用哪个 adapter）
     * @return Safe 交易对象
     */
    fun createRedeemTx(conditionId: String, indexSets: List<BigInteger>, isNegRisk: Boolean = false): SafeTransaction {
        if (indexSets.isEmpty()) {
            throw IllegalArgumentException("indexSets 不能为空")
        }
        return encodeAdapterRedeemTx(conditionId, isNegRisk)
    }

    /**
     * 编码 adapter.redeemPositions(pUSD, 0x0, conditionId, [1, 2])
     */
    private fun encodeAdapterRedeemTx(conditionId: String, isNegRisk: Boolean): SafeTransaction {
        val indexSets = BINARY_INDEX_SETS
        val functionSelector = EthereumUtils.getFunctionSelector(
            "redeemPositions(address,bytes32,bytes32,uint256[])"
        )

        // adapter 接收 pUSD 作为 collateral，内部完成 USDC.e / WCOL 的转换
        val encodedCollateral = EthereumUtils.encodeAddress(PUSD_ADDRESS)
        val encodedParentCollection = EthereumUtils.encodeBytes32(EMPTY_SET)
        val encodedConditionId = EthereumUtils.encodeBytes32(conditionId)

        // 编码数组：offset (32字节) + length (32字节) + 每个元素 (32字节)
        val arrayOffset = BigInteger.valueOf(128)
        val arrayLength = BigInteger.valueOf(indexSets.size.toLong())
        val encodedArrayOffset = EthereumUtils.encodeUint256(arrayOffset)
        val encodedArrayLength = EthereumUtils.encodeUint256(arrayLength)
        val encodedArrayElements = indexSets.joinToString("") { EthereumUtils.encodeUint256(it) }

        // 组合调用数据
        val callData = "0x" + functionSelector.removePrefix("0x") +
                encodedCollateral +
                encodedParentCollection +
                encodedConditionId +
                encodedArrayOffset +
                encodedArrayLength +
                encodedArrayElements

        return SafeTransaction(
            to = redeemAdapterFor(isNegRisk),
            operation = 0,  // CALL
            data = callData,
            value = "0"
        )
    }

    /**
     * 创建 CTF setApprovalForAll(operator, true) 交易（ERC1155 授权）
     * 用于赎回前授权 collateral adapter，以及设置步骤3的交易授权
     */
    fun createCtfSetApprovalForAllTx(operator: String): SafeTransaction =
        createErc1155SetApprovalForAllTx(CONDITIONAL_TOKENS_ADDRESS, operator)

    /**
     * 创建任意 ERC1155 合约的 setApprovalForAll(operator, true) 交易
     */
    fun createErc1155SetApprovalForAllTx(token: String, operator: String): SafeTransaction {
        val functionSelector = EthereumUtils.getFunctionSelector("setApprovalForAll(address,bool)")
        val callData = "0x" + functionSelector.removePrefix("0x") +
                EthereumUtils.encodeAddress(operator) +
                EthereumUtils.encodeUint256(BigInteger.ONE)
        return SafeTransaction(
            to = token,
            operation = 0,
            data = callData,
            value = "0"
        )
    }

    /**
     * 创建 WCOL 解包交易（将 Wrapped Collateral 执行解包）
     * 合约: Neg Risk WrappedCollateral 0x3A3BD7bb9528E159577F7C2e685CC81A765002E2
     * 方法: unwrap(address _to, uint256 _amount)
     *
     * Safe 与 Magic 共用此交易对象：Safe 走 [executeViaBuilderRelayer]（execTransaction），
     * Magic 走 [executeViaBuilderRelayerProxy]（encodeProxyTransactionData），语义一致。
     *
     * @param toAddress 接收解包资产的地址（通常为 proxy 自身，使余额留在代理钱包）
     * @param amountWei WCOL 数量（6 位小数对应的 raw 值，与 balanceOf 返回一致）
     * @return Safe 交易对象
     */
    fun createUnwrapWcolTx(toAddress: String, amountWei: BigInteger): SafeTransaction {
        val functionSelector = EthereumUtils.getFunctionSelector("unwrap(address,uint256)")
        val encodedTo = EthereumUtils.encodeAddress(toAddress)
        val encodedAmount = EthereumUtils.encodeUint256(amountWei)
        val callData = "0x" + functionSelector.removePrefix("0x") + encodedTo + encodedAmount
        return SafeTransaction(
            to = negRiskWrappedCollateralAddress,
            operation = 0,  // CALL
            data = callData,
            value = "0"
        )
    }

    /**
     * 创建 USDC approve 交易（ERC20 approve(spender, amount)）
     * 用于 Polymarket 设置步骤3：代币授权
     */
    fun createUsdcApproveTx(spender: String, amount: BigInteger): SafeTransaction {
        val functionSelector = EthereumUtils.getFunctionSelector("approve(address,uint256)")
        val encodedSpender = EthereumUtils.encodeAddress(spender)
        val encodedAmount = EthereumUtils.encodeUint256(amount)
        val callData = "0x" + functionSelector.removePrefix("0x") + encodedSpender + encodedAmount
        return SafeTransaction(
            to = usdcContractAddress,
            operation = 0,  // CALL
            data = callData,
            value = "0"
        )
    }

    /**
     * 创建 USDC.e approve 交易（用于 wrap 到 pUSD）
     * 授权 CollateralOnramp 合约花费用户的 USDC.e
     */
    fun createUsdceApproveForWrapTx(amount: BigInteger): SafeTransaction {
        val functionSelector = EthereumUtils.getFunctionSelector("approve(address,uint256)")
        val encodedSpender = EthereumUtils.encodeAddress(collateralOnrampAddress)
        val encodedAmount = EthereumUtils.encodeUint256(amount)
        val callData = "0x" + functionSelector.removePrefix("0x") + encodedSpender + encodedAmount
        return SafeTransaction(
            to = usdceContractAddress,
            operation = 0,
            data = callData,
            value = "0"
        )
    }

    /**
     * 创建 USDC.e → pUSD wrap 交易
     * CollateralOnramp.wrap(address _asset, address _to, uint256 _amount)
     */
    fun createWrapToPusdTx(recipientAddress: String, amount: BigInteger): SafeTransaction {
        val functionSelector = EthereumUtils.getFunctionSelector("wrap(address,address,uint256)")
        val asset = EthereumUtils.encodeAddress(usdceContractAddress)
        val to = EthereumUtils.encodeAddress(recipientAddress)
        val amt = EthereumUtils.encodeUint256(amount)
        val callData = "0x" + functionSelector.removePrefix("0x") + asset + to + amt
        return SafeTransaction(
            to = collateralOnrampAddress,
            operation = 0,
            data = callData,
            value = "0"
        )
    }

    /**
     * 创建 MultiSend 交易（合并多个 SafeTransaction 为一笔交易）
     * 参考 TypeScript: builder-relayer-client/src/encode/safe.ts createSafeMultisendTransaction
     *
     * 使用 Gnosis Safe 的 MultiSend 合约将多个交易合并为一笔 DelegateCall 交易
     *
     * @param safeTxs 多个 Safe 交易
     * @return 合并后的 MultiSend 交易（operation = 1 = DelegateCall）
     */
    fun createMultiSendTx(safeTxs: List<SafeTransaction>): SafeTransaction {
        if (safeTxs.isEmpty()) {
            throw IllegalArgumentException("safeTxs 不能为空")
        }

        // 单个交易直接返回，不需要 MultiSend
        if (safeTxs.size == 1) {
            logger.debug("单个交易，不使用 MultiSend")
            return safeTxs.first()
        }

        logger.debug("创建 MultiSend 交易: ${safeTxs.size} 个交易待合并")

        // MultiSend 函数选择器：multiSend(bytes)
        val multiSendSelector = EthereumUtils.getFunctionSelector("multiSend(bytes)")

        // 编码每个交易：encodePacked([uint8 operation, address to, uint256 value, uint256 dataLength, bytes data])
        // 与 builder-relayer-client encode/safe.ts 完全一致
        val encodedTransactions = safeTxs.map { tx ->
            val operation = tx.operation.toByte()
            // address: 20 字节，右对齐（取最后 40 个十六进制字符）
            val toHex = tx.to.removePrefix("0x").lowercase().padStart(40, '0').takeLast(40)
            val to = EthereumUtils.hexToBytes(toHex)
            // value: 32 字节大端
            val valueHex = BigInteger(tx.value).toString(16).padStart(64, '0')
            val value = EthereumUtils.hexToBytes(valueHex)

            val dataBytes = EthereumUtils.hexToBytes(tx.data.removePrefix("0x"))
            // dataLength: 32 字节大端，表示 data 的字节数
            val dataLengthHex = BigInteger.valueOf(dataBytes.size.toLong()).toString(16).padStart(64, '0')
            val dataLength = EthereumUtils.hexToBytes(dataLengthHex)

            // encodePacked: operation(1) + to(20) + value(32) + dataLength(32) + data(variable)
            byteArrayOf(operation) + to + value + dataLength + dataBytes
        }

        // 拼接所有交易（无 padding，与 viem concatHex 一致）
        val concatenatedTransactions = encodedTransactions.reduce { acc, bytes -> acc + bytes }
        val totalDataLength = concatenatedTransactions.size

        // multiSend(bytes) 的 ABI 编码：offset(32) + length(32) + data(按 32 字节对齐 padding)
        val paddedLength = ((totalDataLength + 31) / 32) * 32
        val paddedData = concatenatedTransactions + ByteArray(paddedLength - totalDataLength)

        val encodedOffset = EthereumUtils.encodeUint256(BigInteger.valueOf(32))
        val encodedLength = EthereumUtils.encodeUint256(BigInteger.valueOf(totalDataLength.toLong()))
        val encodedData = paddedData.joinToString("") { "%02x".format(it) }

        val callData = "0x" + multiSendSelector.removePrefix("0x") + encodedOffset + encodedLength + encodedData

        return SafeTransaction(
            to = safeMultisendAddress,
            operation = 1,  // DelegateCall
            data = callData,
            value = "0"
        )
    }

    /**
     * 执行代理交易（Safe 或 Magic PROXY）
     * 参考 TypeScript: RelayClient.execute()
     *
     * @param privateKey 私钥
     * @param proxyAddress 代理钱包地址
     * @param safeTx 交易对象（to/data/value）
     * @param walletType 钱包类型：MAGIC 使用 PROXY Gasless，SAFE 使用 Safe 流程
     * @param metadata Relayer metadata（描述本次操作，如“Redeem positions”“Token approvals”）
     * @return 已确认且回执核验通过的交易哈希；超时返回 [RelayerTransactionPendingException]
     */
    suspend fun execute(
        privateKey: String,
        proxyAddress: String,
        safeTx: SafeTransaction,
        walletType: WalletType = WalletType.SAFE,
        metadata: String? = null
    ): Result<String> {
        return try {
            if (proxyAddress.isBlank() || !proxyAddress.startsWith("0x") || proxyAddress.length != 42) {
                return Result.failure(IllegalArgumentException("proxyAddress 格式错误，必须是有效的以太坊地址"))
            }

            val builderApiKey = systemConfigService.getBuilderApiKey()
            val builderSecret = systemConfigService.getBuilderSecret()
            val builderPassphrase = systemConfigService.getBuilderPassphrase()

            if (walletType == WalletType.DEPOSIT) {
                if (safeTx.operation == 1) {
                    return Result.failure(IllegalArgumentException("Deposit Wallet 不支持 MultiSend delegatecall，请使用 executeCalls 批量执行"))
                }
                return executeCalls(privateKey, proxyAddress, listOf(safeTx), walletType, metadata)
            }

            if (!isBuilderRelayerEnabled(builderApiKey, builderSecret, builderPassphrase)) {
                // 不再提供“EOA 自付 gas 手动发送”的兜底路径：该路径签名/编码有误且广播即当成功
                return Result.failure(BuilderApiKeyNotConfiguredException("代理钱包链上操作（Gasless）"))
            }

            if (walletType == WalletType.MAGIC) {
                logger.info("使用 Builder Relayer PROXY 执行 Magic 交易")
                return executeViaBuilderRelayerProxy(
                    privateKey,
                    proxyAddress,
                    listOf(safeTx),
                    builderApiKey!!,
                    builderSecret!!,
                    builderPassphrase!!,
                    metadata
                )
            }

            logger.info("使用 Builder Relayer 执行 Gasless 交易")
            return executeViaBuilderRelayer(
                privateKey,
                proxyAddress,
                safeTx,
                builderApiKey!!,
                builderSecret!!,
                builderPassphrase!!,
                metadata
            )
        } catch (e: Exception) {
            logger.error("执行交易失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 批量执行多笔调用，按钱包类型选择最合适的方式：
     * - DEPOSIT：一次 WALLET 批量提交（原生支持 calls[]）
     * - SAFE：MultiSend 合并为一笔 execTransaction
     * - MAGIC：多笔 CALL 编码进同一个 proxy(calls[])，一次 PROXY 交易完成（PROXY 不支持 delegatecall）
     *
     * @return 最后一笔交易的哈希（均已确认且回执核验通过）
     */
    suspend fun executeCalls(
        privateKey: String,
        proxyAddress: String,
        txs: List<SafeTransaction>,
        walletType: WalletType,
        metadata: String? = null
    ): Result<String> {
        if (txs.isEmpty()) {
            return Result.failure(IllegalArgumentException("txs 不能为空"))
        }
        return try {
            when (walletType) {
                WalletType.DEPOSIT -> {
                    if (txs.any { it.operation == 1 }) {
                        return Result.failure(IllegalArgumentException("Deposit Wallet 不支持 delegatecall 调用"))
                    }
                    val builderApiKey = systemConfigService.getBuilderApiKey()
                    val builderSecret = systemConfigService.getBuilderSecret()
                    val builderPassphrase = systemConfigService.getBuilderPassphrase()
                    if (!isBuilderRelayerEnabled(builderApiKey, builderSecret, builderPassphrase)) {
                        return Result.failure(BuilderApiKeyNotConfiguredException("Deposit Wallet 账户链上操作（Gasless）"))
                    }
                    logger.info("使用 Builder Relayer WALLET 批量执行 Deposit Wallet 调用: calls=${txs.size}")
                    executeDepositWalletBatch(
                        privateKey, proxyAddress, txs, builderApiKey!!, builderSecret!!, builderPassphrase!!, metadata
                    )
                }
                WalletType.SAFE -> {
                    val tx = if (txs.size == 1) txs.first() else createMultiSendTx(txs)
                    execute(privateKey, proxyAddress, tx, walletType, metadata)
                }
                WalletType.MAGIC -> {
                    // 与 ts-sdk buildProxyWalletExecuteRequest 一致：多笔调用编码进同一个 proxy(calls[])，一次 Relayer 交易完成
                    if (txs.any { it.operation == 1 }) {
                        return Result.failure(IllegalArgumentException("Magic 代理钱包不支持 delegatecall 调用"))
                    }
                    val builderApiKey = systemConfigService.getBuilderApiKey()
                    val builderSecret = systemConfigService.getBuilderSecret()
                    val builderPassphrase = systemConfigService.getBuilderPassphrase()
                    if (!isBuilderRelayerEnabled(builderApiKey, builderSecret, builderPassphrase)) {
                        return Result.failure(BuilderApiKeyNotConfiguredException("代理钱包链上操作（Gasless）"))
                    }
                    logger.info("使用 Builder Relayer PROXY 批量执行 Magic 调用: calls=${txs.size}")
                    executeViaBuilderRelayerProxy(
                        privateKey, proxyAddress, txs, builderApiKey!!, builderSecret!!, builderPassphrase!!, metadata
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("批量执行交易失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 查询 Deposit Wallet 的批量签名 nonce（Relayer GET /nonce?type=WALLET，address 为 owner EOA）
     */
    private suspend fun getDepositWalletNonce(
        relayerApi: BuilderRelayerApi,
        ownerAddress: String
    ): Result<BigInteger> {
        val nonceResponse = withBuilderRelayerRateLimitRetry { relayerApi.getNonce(ownerAddress, RELAYER_TYPE_WALLET) }
        if (!nonceResponse.isSuccessful || nonceResponse.body() == null) {
            val errorBody = nonceResponse.errorBody()?.string() ?: "未知错误"
            updateQuotaBlockedFromErrorBody(errorBody)
            logger.error("获取 Deposit Wallet nonce 失败: code=${nonceResponse.code()}, body=$errorBody")
            return Result.failure(Exception("获取 Deposit Wallet nonce 失败: ${nonceResponse.code()} - $errorBody"))
        }
        return Result.success(BigInteger(nonceResponse.body()!!.nonce))
    }

    /**
     * 通过 Builder Relayer 执行 Deposit Wallet 批量调用（WALLET 类型，Gasless）
     * 参考: ts-sdk actions/gasless.ts buildDepositWalletExecuteRequest
     *
     * 签名为标准 EIP-712（signTypedData）：
     * domain = { name: "DepositWallet", version: "1", chainId: 137, verifyingContract: depositWallet }
     * message = Batch { wallet, nonce, deadline, calls[{target, value, data}] }
     * 若 Relayer 返回 nonce 与链上不一致，按其提示的链上 nonce 重签一次。
     */
    private suspend fun executeDepositWalletBatch(
        privateKey: String,
        depositWallet: String,
        txs: List<SafeTransaction>,
        builderApiKey: String,
        builderSecret: String,
        builderPassphrase: String,
        metadata: String? = null
    ): Result<String> {
        val relayerApi = retrofitFactory.createBuilderRelayerApi(
            relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
            apiKey = builderApiKey,
            secret = builderSecret,
            passphrase = builderPassphrase
        )
        // 同一钱包有结果未知的在途交易时拒绝再次提交，避免重复执行
        checkNoInFlightTransaction(relayerApi, depositWallet).getOrElse { return Result.failure(it) }

        val cleanPrivateKey = privateKey.removePrefix("0x")
        val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
        val ecKeyPair = org.web3j.crypto.ECKeyPair.create(privateKeyBigInt)
        val fromAddress = org.web3j.crypto.Credentials.create(ecKeyPair).address
        if (!PolymarketWalletDerivation.isDepositWalletForSigner(fromAddress, depositWallet)) {
            return Result.failure(IllegalArgumentException("Deposit Wallet 地址与签名 EOA 不匹配，拒绝签名"))
        }

        val calls = txs.map { tx ->
            Eip712Encoder.DepositWalletCall(
                target = tx.to,
                value = BigInteger(tx.value.ifBlank { "0" }),
                data = if (tx.data.startsWith("0x")) tx.data else "0x${tx.data}"
            )
        }
        val deadline = BigInteger.valueOf(System.currentTimeMillis() / 1000 + depositWalletDeadlineSeconds)

        var nonce = getDepositWalletNonce(relayerApi, fromAddress).getOrElse { return Result.failure(it) }

        // 最多两次：第二次用于 Relayer 提示 nonce 不匹配时按链上 nonce 重签
        repeat(2) { attempt ->
            val signature = signDepositWalletBatch(ecKeyPair, depositWallet, nonce, deadline, calls)
            val request = BuilderRelayerApi.DepositWalletTransactionRequest(
                type = RELAYER_TYPE_WALLET,
                from = fromAddress,
                to = depositWalletFactoryAddress,
                nonce = nonce.toString(),
                signature = signature,
                depositWalletParams = BuilderRelayerApi.DepositWalletParams(
                    calls = calls.map {
                        BuilderRelayerApi.DepositWalletCallRequest(
                            target = it.target,
                            value = it.value.toString(),
                            data = it.data
                        )
                    },
                    deadline = deadline.toString(),
                    depositWallet = depositWallet
                ),
                metadata = metadata ?: "PolyHermes deposit wallet batch (${calls.size} calls)"
            )
            logger.debug(
                "Deposit Wallet 批量提交: wallet={}, nonce={}, deadline={}, calls={}",
                depositWallet, nonce, deadline, calls.size
            )

            val response = withBuilderRelayerRateLimitRetry { relayerApi.submitDepositWalletTransaction(request) }
            if (response.isSuccessful && response.body() != null) {
                val relayerResponse = response.body()!!
                // 等待 Relayer 终态并核验回执，未到终态前不当作成功
                val txHash = awaitRelayerOutcome(relayerApi, relayerResponse, depositWallet, checkRelayHubInnerStatus = false)
                    .getOrElse { return Result.failure(it) }
                logger.info("Builder Relayer WALLET 执行成功: transactionID=${relayerResponse.transactionID}, txHash=$txHash")
                return Result.success(txHash)
            }

            val errorBody = response.errorBody()?.string() ?: "未知错误"
            updateQuotaBlockedFromErrorBody(errorBody)
            val onChainNonce = extractOnChainNonceFromError(errorBody)
            // 只处理“提交的 nonce 已落后于链上 nonce”的官方可重试场景。
            // 反向差异可能意味着提交状态不明，继续重签有重复执行风险。
            if (attempt == 0 && response.code() == 400 && onChainNonce != null && onChainNonce > nonce) {
                logger.warn("Deposit Wallet nonce 不匹配（提交 $nonce，链上 $onChainNonce），按链上 nonce 重签")
                nonce = onChainNonce
            } else {
                logger.error("Builder Relayer WALLET 调用失败: code=${response.code()}, body=$errorBody")
                return Result.failure(Exception("Builder Relayer WALLET 调用失败: ${response.code()} - $errorBody"))
            }
        }
        return Result.failure(Exception("Deposit Wallet 批量提交失败：nonce 重试后仍不匹配"))
    }

    /**
     * 对 Deposit Wallet Batch 做标准 EIP-712 签名，返回 0x + r + s + v（v 为 27/28）
     */
    internal fun signDepositWalletBatch(
        ecKeyPair: org.web3j.crypto.ECKeyPair,
        depositWallet: String,
        nonce: BigInteger,
        deadline: BigInteger,
        calls: List<Eip712Encoder.DepositWalletCall>
    ): String {
        val domainSeparator = Eip712Encoder.encodeDepositWalletDomain(
            chainId = 137L,
            depositWallet = depositWallet
        )
        val batchHash = Eip712Encoder.encodeDepositWalletBatch(
            wallet = depositWallet,
            nonce = nonce,
            deadline = deadline,
            calls = calls
        )
        val digest = Eip712Encoder.hashStructuredData(domainSeparator, batchHash)
        val signature = org.web3j.crypto.Sign.signMessage(digest, ecKeyPair, false)
        return signatureToStandardHex(signature)
    }

    /**
     * 从 Relayer 400 响应中解析 "batch nonce X does not match on-chain nonce Y" 的链上 nonce
     * 参考 ts-sdk gasless.ts extractOnChainNonceFromSubmitError
     */
    internal fun extractOnChainNonceFromError(errorBody: String): BigInteger? {
        val regex = Regex("batch nonce\\s+\\d+\\s+does not match on-chain nonce\\s+(\\d+)", RegexOption.IGNORE_CASE)
        return regex.find(errorBody)?.groupValues?.getOrNull(1)?.let { BigInteger(it) }
    }

    /**
     * 按 transactionID 轮询 Relayer 直到终态（参考 ts-sdk GaslessTransactionHandle.wait）：
     * - STATE_CONFIRMED：返回交易哈希（随后由调用方核验回执）
     * - STATE_FAILED / STATE_INVALID：返回 [RelayerTransactionFailedException]
     * - 超时（约 60 秒）：返回 [RelayerTransactionPendingException]，结果未知，不可当作失败重试
     * 状态查询本身出错时继续轮询，不把网络错误当成终态。
     */
    internal suspend fun waitForRelayerTerminalState(
        relayerApi: BuilderRelayerApi,
        transactionId: String,
        submittedHash: String? = null
    ): Result<String> {
        var lastHash = submittedHash
        repeat(relayerHashPollAttempts) {
            delay(relayerHashPollIntervalMs)
            val response = try {
                relayerApi.getTransactionById(transactionId)
            } catch (e: Exception) {
                logger.warn("查询 Relayer 交易状态异常: ${e.message}")
                null
            }
            val status = response?.body()
            if (response != null && response.isSuccessful && status != null) {
                if (!status.transactionHash.isNullOrBlank()) {
                    lastHash = status.transactionHash
                }
                when (status.state) {
                    STATE_CONFIRMED -> {
                        val hash = lastHash
                            ?: return Result.failure(RelayerTransactionFailedException(transactionId, null, "Relayer 交易已确认但缺少交易哈希"))
                        return Result.success(hash)
                    }
                    STATE_FAILED, STATE_INVALID -> return Result.failure(
                        RelayerTransactionFailedException(
                            transactionId, lastHash,
                            "Relayer 交易失败: state=${status.state}, ${status.errorMsg ?: ""}"
                        )
                    )
                }
            }
        }
        return Result.failure(
            RelayerTransactionPendingException(
                transactionId, lastHash,
                "Relayer 交易 $transactionId 在 ${relayerHashPollAttempts * relayerHashPollIntervalMs / 1000} 秒内未到终态，结果未知，请勿重复提交"
            )
        )
    }

    /**
     * 提交后的统一结果判定：等待 Relayer 终态 → 核验回执（Magic 额外核验 RelayHub 内层状态）。
     * 超时（结果未知）时记录在途交易，同一代理钱包在其终态前拒绝新的提交，防止重复执行。
     */
    private suspend fun awaitRelayerOutcome(
        relayerApi: BuilderRelayerApi,
        relayerResponse: BuilderRelayerApi.RelayerTransactionResponse,
        proxyAddress: String,
        checkRelayHubInnerStatus: Boolean
    ): Result<String> {
        val submittedHash = relayerResponse.transactionHash ?: relayerResponse.hash
        val txHash = waitForRelayerTerminalState(relayerApi, relayerResponse.transactionID, submittedHash).getOrElse { e ->
            if (e is RelayerTransactionPendingException) {
                inFlightTransactions[proxyAddress.lowercase()] = relayerResponse.transactionID
            }
            return Result.failure(e)
        }
        verifyTransactionReceipt(txHash, checkRelayHubInnerStatus).getOrElse { return Result.failure(it) }
        return Result.success(txHash)
    }

    /**
     * 检查该代理钱包是否有未到终态的在途交易：有则查询一次状态，仍未到终态时返回处理中错误
     */
    private suspend fun checkNoInFlightTransaction(relayerApi: BuilderRelayerApi, proxyAddress: String): Result<Unit> {
        val key = proxyAddress.lowercase()
        val transactionId = inFlightTransactions[key] ?: return Result.success(Unit)
        val state = try {
            relayerApi.getTransactionById(transactionId).body()?.state
        } catch (e: Exception) {
            logger.warn("查询在途 Relayer 交易状态异常: ${e.message}")
            null
        }
        if (state == STATE_CONFIRMED || state == STATE_FAILED || state == STATE_INVALID) {
            inFlightTransactions.remove(key, transactionId)
            logger.info("在途 Relayer 交易已到终态: proxy=$proxyAddress, transactionID=$transactionId, state=$state")
            return Result.success(Unit)
        }
        return Result.failure(
            RelayerTransactionPendingException(transactionId, null, "该钱包上一笔链上交易 $transactionId 仍在处理中（state=$state），请勿重复提交")
        )
    }

    /**
     * 核验交易回执：status 必须为 0x1；Magic PROXY 还需检查 RelayHub TransactionRelayed 的内层状态（0=成功）。
     * RelayHub 外层交易成功但内层调用失败时 status 仍为 0x1，只能靠该事件识别。
     * @return 回执 JSON（供调用方解析 PayoutRedemption / Transfer 等事件）
     */
    internal suspend fun verifyTransactionReceipt(
        txHash: String,
        checkRelayHubInnerStatus: Boolean
    ): Result<com.google.gson.JsonObject> {
        repeat(relayerReceiptPollAttempts) { attempt ->
            if (attempt > 0) delay(relayerHashPollIntervalMs)
            val receipt = try {
                val response = polygonRpcApi.call(JsonRpcRequest(method = "eth_getTransactionReceipt", params = listOf(txHash)))
                val body = response.body()
                if (response.isSuccessful && body != null && body.error == null) body.result else null
            } catch (e: Exception) {
                logger.warn("查询交易回执异常: txHash=$txHash, ${e.message}")
                null
            }
            if (receipt != null && receipt.isJsonObject) {
                return checkReceipt(txHash, receipt.asJsonObject, checkRelayHubInnerStatus)
            }
        }
        return Result.failure(
            RelayerTransactionPendingException("", txHash, "Relayer 已确认但暂未查到交易回执: $txHash，结果未知，请勿重复提交")
        )
    }

    /**
     * 判定回执是否成功（纯函数，便于单元测试）
     */
    internal fun checkReceipt(
        txHash: String,
        receipt: com.google.gson.JsonObject,
        checkRelayHubInnerStatus: Boolean
    ): Result<com.google.gson.JsonObject> {
        val status = receipt.get("status")?.takeIf { !it.isJsonNull }?.asString
        if (status != "0x1") {
            return Result.failure(RelayerTransactionFailedException(null, txHash, "交易已上链但执行失败: status=$status"))
        }
        if (checkRelayHubInnerStatus) {
            val relayed = receipt.getAsJsonArray("logs")?.map { it.asJsonObject }?.filter { log ->
                log.get("address")?.asString.equals(relayHubAddress, ignoreCase = true) &&
                        log.getAsJsonArray("topics")?.firstOrNull()?.asString.equals(TRANSACTION_RELAYED_TOPIC, ignoreCase = true)
            } ?: emptyList()
            if (relayed.isEmpty()) {
                return Result.failure(RelayerTransactionFailedException(null, txHash, "回执中缺少 RelayHub TransactionRelayed 事件，无法确认内层执行结果"))
            }
            for (log in relayed) {
                // data = selector(bytes4) | status(uint8) | charge(uint256)，每项 32 字节
                val data = log.get("data")?.asString?.removePrefix("0x") ?: ""
                val innerStatus = if (data.length >= 128) BigInteger(data.substring(64, 128), 16) else null
                if (innerStatus != BigInteger.ZERO) {
                    return Result.failure(RelayerTransactionFailedException(null, txHash, "Magic PROXY 内层调用失败: relayStatus=$innerStatus"))
                }
            }
        }
        return Result.success(receipt)
    }

    /**
     * 通过 Builder Relayer 部署 Deposit Wallet（WALLET-CREATE，无需签名）
     * 参考: ts-sdk actions/gasless.ts deployDepositWallet
     *
     * @param fromAddress owner EOA 地址
     * @return 交易哈希
     */
    suspend fun deployDepositWalletViaBuilderRelayer(fromAddress: String): Result<String> {
        return try {
            val builderApiKey = systemConfigService.getBuilderApiKey()
            val builderSecret = systemConfigService.getBuilderSecret()
            val builderPassphrase = systemConfigService.getBuilderPassphrase()
            if (!isBuilderRelayerEnabled(builderApiKey, builderSecret, builderPassphrase)) {
                return Result.failure(BuilderApiKeyNotConfiguredException("Deposit Wallet 部署"))
            }
            val relayerApi = retrofitFactory.createBuilderRelayerApi(
                relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
                apiKey = builderApiKey!!,
                secret = builderSecret!!,
                passphrase = builderPassphrase!!
            )
            val request = BuilderRelayerApi.DepositWalletCreateRequest(
                type = RELAYER_TYPE_WALLET_CREATE,
                from = fromAddress,
                to = depositWalletFactoryAddress,
                metadata = "Deploy Deposit Wallet"
            )
            val response = withBuilderRelayerRateLimitRetry { relayerApi.submitDepositWalletCreate(request) }
            if (!response.isSuccessful || response.body() == null) {
                val errorBody = response.errorBody()?.string() ?: "未知错误"
                updateQuotaBlockedFromErrorBody(errorBody)
                logger.error("Builder Relayer WALLET-CREATE 失败: code=${response.code()}, body=$errorBody")
                return Result.failure(Exception("部署 Deposit Wallet 失败: ${response.code()} - $errorBody"))
            }
            val relayerResponse = response.body()!!
            // 等待 Relayer 终态并核验回执，未到终态前不当作成功
            val txHash = awaitRelayerOutcome(relayerApi, relayerResponse, fromAddress, checkRelayHubInnerStatus = false)
                .getOrElse { return Result.failure(it) }
            logger.info("Deposit Wallet 部署成功: owner=$fromAddress, txHash=$txHash")
            Result.success(txHash)
        } catch (e: Exception) {
            logger.error("部署 Deposit Wallet 失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 通过 Builder Relayer 执行 PROXY（Magic）交易（Gasless）
     * 参考: builder-relayer-client client.ts executeProxyTransactions, builder/proxy.ts
     */
    private suspend fun executeViaBuilderRelayerProxy(
        privateKey: String,
        proxyAddress: String,
        calls: List<SafeTransaction>,
        builderApiKey: String,
        builderSecret: String,
        builderPassphrase: String,
        metadata: String? = null
    ): Result<String> {
        val relayerApi = retrofitFactory.createBuilderRelayerApi(
            relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
            apiKey = builderApiKey,
            secret = builderSecret,
            passphrase = builderPassphrase
        )
        // 同一钱包有结果未知的在途交易时拒绝再次提交，避免重复执行
        checkNoInFlightTransaction(relayerApi, proxyAddress).getOrElse { return Result.failure(it) }

        val cleanPrivateKey = privateKey.removePrefix("0x")
        val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
        val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))
        val fromAddress = credentials.address

        val relayPayloadResponse = withBuilderRelayerRateLimitRetry { relayerApi.getRelayPayload(fromAddress, RELAYER_TYPE_PROXY) }
        if (!relayPayloadResponse.isSuccessful || relayPayloadResponse.body() == null) {
            val errorBody = relayPayloadResponse.errorBody()?.string() ?: "未知错误"
            updateQuotaBlockedFromErrorBody(errorBody)
            logger.error("获取 Relay Payload 失败: code=${relayPayloadResponse.code()}, body=$errorBody")
            return Result.failure(Exception("获取 Relay Payload 失败: ${relayPayloadResponse.code()} - $errorBody"))
        }
        val relayPayload = relayPayloadResponse.body()!!
        val relayAddress = relayPayload.address
        val nonce = relayPayload.nonce

        val proxyCallData = encodeProxyTransactionData(calls)
        
        // 估算 gas limit（参考 builder-relayer-client builder/proxy.ts getGasLimit）
        val gasLimit = try {
            val estimatedGasLimit = estimateProxyGasLimit(fromAddress, proxyFactoryAddress, proxyCallData)
            val estimatedBigInt = BigInteger(estimatedGasLimit)
            if (estimatedBigInt > maxProxyGasLimit) {
                logger.warn(
                    "估算 PROXY gas limit 过大，进行截断: estimated=$estimatedGasLimit, capped=$maxProxyGasLimit"
                )
                maxProxyGasLimit.toString()
            } else {
                estimatedGasLimit
            }
        } catch (e: Exception) {
            logger.warn("估算 PROXY gas limit 失败，使用默认值: ${e.message}", e)
            defaultProxyGasLimit
        }

        val structHash = createProxyStructHash(
            from = fromAddress,
            to = proxyFactoryAddress,
            data = proxyCallData,
            txFee = "0",
            gasPrice = "0",
            gasLimit = gasLimit,
            nonce = nonce,
            relayHubAddress = relayHubAddress,
            relayAddress = relayAddress
        )

        val prefix = "\u0019Ethereum Signed Message:\n32".toByteArray(Charsets.UTF_8)
        val messageWithPrefix = ByteArray(prefix.size + structHash.size)
        System.arraycopy(prefix, 0, messageWithPrefix, 0, prefix.size)
        System.arraycopy(structHash, 0, messageWithPrefix, prefix.size, structHash.size)

        val keccak256 = org.bouncycastle.crypto.digests.KeccakDigest(256)
        keccak256.update(messageWithPrefix, 0, messageWithPrefix.size)
        val hashWithPrefix = ByteArray(keccak256.digestSize)
        keccak256.doFinal(hashWithPrefix, 0)

        val ecKeyPair = org.web3j.crypto.ECKeyPair.create(privateKeyBigInt)
        val signature = org.web3j.crypto.Sign.signMessage(hashWithPrefix, ecKeyPair, false)
        val sigHex = "0x" + org.web3j.utils.Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0') +
                org.web3j.utils.Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0') +
                String.format("%02x", (signature.v as ByteArray).getOrElse(0) { 0 }.toInt() and 0xff)

        val request = BuilderRelayerApi.TransactionRequest(
            type = RELAYER_TYPE_PROXY,
            from = fromAddress,
            to = proxyFactoryAddress,
            proxyWallet = proxyAddress,
            data = proxyCallData,
            nonce = nonce,
            signature = sigHex,
            signatureParams = BuilderRelayerApi.SignatureParams(
                gasPrice = "0",
                gasLimit = gasLimit,
                relayerFee = "0",
                relayHub = relayHubAddress,
                relay = relayAddress
            ),
            metadata = metadata ?: "PolyHermes proxy call"
        )

        val response = withBuilderRelayerRateLimitRetry { relayerApi.submitTransaction(request) }
        if (!response.isSuccessful || response.body() == null) {
            val errorBody = response.errorBody()?.string() ?: "未知错误"
            updateQuotaBlockedFromErrorBody(errorBody)
            logger.error("Builder Relayer PROXY API 调用失败: code=${response.code()}, body=$errorBody")
            return Result.failure(Exception("Builder Relayer PROXY 调用失败: ${response.code()} - $errorBody"))
        }

        val relayerResponse = response.body()!!
        // 等待 Relayer 终态并核验回执，未到终态前不当作成功
        val txHash = awaitRelayerOutcome(relayerApi, relayerResponse, proxyAddress, checkRelayHubInnerStatus = true)
            .getOrElse { return Result.failure(it) }
        logger.info("Builder Relayer PROXY 执行成功: transactionID=${relayerResponse.transactionID}, txHash=$txHash")
        return Result.success(txHash)
    }

    /**
     * 编码 ProxyFactory.proxy(calls) 调用数据
     * 参考: builder-relayer-client encode/proxy.ts, abis proxyFactory proxy((uint8,address,uint256,bytes)[])
     * 
     * ABI 编码规则：当 tuple 数组中的 tuple 包含动态类型（bytes）时，需要先存储 tuple offset
     * 结构：
     * - selector (4 bytes)
     * - array offset (32 bytes) = 32
     * - array length (32 bytes) = N
     * - tuple[i] offset (32 bytes)，从 offsets 区起算：tuple[0] = 32 * N，之后依次累加前一个 tuple 的字节数
     * - tuple[i] 数据：
     *   - typeCode (32 bytes) = 1
     *   - to (32 bytes)
     *   - value (32 bytes) = 0
     *   - data offset (32 bytes) = 128 (从 tuple 数据开始计算)
     *   - data length (32 bytes)
     *   - data (padded to 32-byte boundary)
     */
    internal fun encodeProxyTransactionData(calls: List<SafeTransaction>): String {
        val selector = EthereumUtils.getFunctionSelector("proxy((uint8,address,uint256,bytes)[])")
        // ABI 编码：动态 tuple 数组。每个 tuple = typeCode(1=CALL) + to + value + data 偏移(128) + data 长度 + data（补齐到 32 字节）
        val encodedTuples = calls.map { call ->
            val callData = call.data.removePrefix("0x")
            val dataLen = callData.length / 2
            val dataPadded = callData.padEnd((dataLen + 31) / 32 * 32 * 2, '0')
            EthereumUtils.encodeUint256(BigInteger.ONE) +
                EthereumUtils.encodeAddress(call.to) +
                EthereumUtils.encodeUint256(BigInteger.ZERO) +
                EthereumUtils.encodeUint256(BigInteger.valueOf(128)) +
                EthereumUtils.encodeUint256(BigInteger.valueOf(dataLen.toLong())) +
                dataPadded
        }
        // tuple 偏移从 offsets 区起算：第一个为 32 * N，之后依次累加前一个 tuple 的字节数
        var offset = 32L * calls.size
        val offsets = encodedTuples.joinToString("") { tuple ->
            val encoded = EthereumUtils.encodeUint256(BigInteger.valueOf(offset))
            offset += tuple.length / 2
            encoded
        }
        return "0x" + selector.removePrefix("0x") +
            EthereumUtils.encodeUint256(BigInteger.valueOf(32)) +
            EthereumUtils.encodeUint256(BigInteger.valueOf(calls.size.toLong())) +
            offsets + encodedTuples.joinToString("")
    }

    /**
     * 估算 PROXY 交易的 gas limit
     * 参考: builder-relayer-client builder/proxy.ts getGasLimit
     */
    private suspend fun estimateProxyGasLimit(
        from: String,
        to: String,
        data: String
    ): String {
        val rpcApi = polygonRpcApi
        
        val rpcRequest = JsonRpcRequest(
            method = "eth_estimateGas",
            params = listOf(
                mapOf(
                    "from" to from,
                    "to" to to,
                    "data" to data
                )
            )
        )
        
        val response = rpcApi.call(rpcRequest)
        if (!response.isSuccessful || response.body() == null) {
            throw Exception("eth_estimateGas 调用失败: ${response.code()} ${response.message()}")
        }
        
        val rpcResponse = response.body()!!
        if (rpcResponse.error != null) {
            throw Exception("eth_estimateGas 返回错误: ${rpcResponse.error.message}")
        }
        
        val hexGasLimit = rpcResponse.result?.asString
            ?: throw Exception("eth_estimateGas 结果为空")
        
        // 将十六进制转换为十进制字符串
        val gasLimitBigInt = BigInteger(hexGasLimit.removePrefix("0x"), 16)
        return gasLimitBigInt.toString()
    }

    /**
     * 创建 PROXY 结构哈希，参考 builder-relayer-client builder/proxy.ts createStructHash
     * concat: "rlx:" + from + to + data + txFee + gasPrice + gasLimit + nonce + relayHub + relay, then keccak256
     */
    private fun createProxyStructHash(
        from: String,
        to: String,
        data: String,
        txFee: String,
        gasPrice: String,
        gasLimit: String,
        nonce: String,
        relayHubAddress: String,
        relayAddress: String
    ): ByteArray {
        val rlxPrefix = "rlx:".toByteArray(Charsets.UTF_8)
        val fromBytes = EthereumUtils.hexToBytes(from.lowercase().removePrefix("0x").padStart(40, '0'))
        val toBytes = EthereumUtils.hexToBytes(to.lowercase().removePrefix("0x").padStart(40, '0'))
        val dataBytes = EthereumUtils.hexToBytes(data.removePrefix("0x"))
        val txFeeBytes = EthereumUtils.encodeUint256(BigInteger(txFee)).let { EthereumUtils.hexToBytes(it) }
        val gasPriceBytes = EthereumUtils.encodeUint256(BigInteger(gasPrice)).let { EthereumUtils.hexToBytes(it) }
        val gasLimitBytes = EthereumUtils.encodeUint256(BigInteger(gasLimit)).let { EthereumUtils.hexToBytes(it) }
        val nonceBytes = EthereumUtils.encodeUint256(BigInteger(nonce)).let { EthereumUtils.hexToBytes(it) }
        val relayHubBytes = EthereumUtils.hexToBytes(relayHubAddress.lowercase().removePrefix("0x").padStart(40, '0'))
        val relayBytes = EthereumUtils.hexToBytes(relayAddress.lowercase().removePrefix("0x").padStart(40, '0'))

        val concat = rlxPrefix + fromBytes + toBytes + dataBytes + txFeeBytes + gasPriceBytes +
                gasLimitBytes + nonceBytes + relayHubBytes + relayBytes
        return EthereumUtils.keccak256(concat)
    }

    /**
     * 通过 Builder Relayer 执行交易（Gasless）
     * 参考: builder-relayer-client/src/client.ts 的 execute 方法
     */
    private suspend fun executeViaBuilderRelayer(
        privateKey: String,
        proxyAddress: String,
        safeTx: SafeTransaction,
        builderApiKey: String,
        builderSecret: String,
        builderPassphrase: String,
        metadata: String? = null
    ): Result<String> {
        val relayerApi = retrofitFactory.createBuilderRelayerApi(
            relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
            apiKey = builderApiKey,
            secret = builderSecret,
            passphrase = builderPassphrase
        )
        // 同一钱包有结果未知的在途交易时拒绝再次提交，避免重复执行
        checkNoInFlightTransaction(relayerApi, proxyAddress).getOrElse { return Result.failure(it) }

        // 从私钥推导实际签名地址（EOA）
        val cleanPrivateKey = privateKey.removePrefix("0x")
        val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
        val credentials = org.web3j.crypto.Credentials.create(privateKeyBigInt.toString(16))
        val fromAddress = credentials.address

        // safeTx.data 已经是带 0x 前缀的完整调用数据
        val redeemCallData = safeTx.data

        // 获取 Proxy 的 nonce（通过 Builder Relayer API，遇 429 限流时重试）
        val nonceResponse = withBuilderRelayerRateLimitRetry { relayerApi.getNonce(fromAddress, RELAYER_TYPE_SAFE) }
        if (!nonceResponse.isSuccessful || nonceResponse.body() == null) {
            val errorBody = nonceResponse.errorBody()?.string() ?: "未知错误"
            updateQuotaBlockedFromErrorBody(errorBody)
            logger.error("获取 nonce 失败: code=${nonceResponse.code()}, body=$errorBody")
            return Result.failure(Exception("获取 nonce 失败: ${nonceResponse.code()} - $errorBody"))
        }
        val proxyNonce = BigInteger(nonceResponse.body()!!.nonce)

        // 调试 GS026：记录 nonce 与交易参数，便于与 relayer/链上对比
        logger.debug(
            "Safe exec 签名参数: nonce={}, to={}, value={}, dataLen={}, operation={}, proxyWallet={}",
            proxyNonce,
            safeTx.to,
            safeTx.value,
            redeemCallData.removePrefix("0x").length / 2,
            safeTx.operation,
            proxyAddress
        )

        // 构建 Safe 交易哈希并签名
        // 注意：encodeSafeTx 需要 data 带 0x 前缀
        val safeTxGas = BigInteger.ZERO
        val baseGas = BigInteger.ZERO
        val safeGasPrice = BigInteger.ZERO
        val gasToken = "0x0000000000000000000000000000000000000000"
        val refundReceiver = "0x0000000000000000000000000000000000000000"

        val safeDomainSeparator = com.wrbug.polymarketbot.util.Eip712Encoder.encodeSafeDomain(
            chainId = 137L,  // Polygon 主网
            verifyingContract = proxyAddress
        )

        val safeTxHash = com.wrbug.polymarketbot.util.Eip712Encoder.encodeSafeTx(
            to = safeTx.to,
            value = BigInteger.ZERO,
            data = redeemCallData,  // 带 0x 前缀
            operation = safeTx.operation,
            safeTxGas = safeTxGas,
            baseGas = baseGas,
            gasPrice = safeGasPrice,
            gasToken = gasToken,
            refundReceiver = refundReceiver,
            nonce = proxyNonce
        )

        val safeTxStructuredHash = com.wrbug.polymarketbot.util.Eip712Encoder.hashStructuredData(
            domainSeparator = safeDomainSeparator,
            messageHash = safeTxHash
        )

        // 调试 GS026：记录 EIP-712 structHash 与最终签名的 hash（可与 Safe.getTransactionHash 对比）
        logger.debug(
            "Safe exec 哈希: structHash=0x{}, hashToSign 将基于 prefix+structHash 的 keccak256",
            safeTxStructuredHash.joinToString("") { "%02x".format(it) }
        )

        // 注意：ethers.js 的 signMessage 会添加 EIP-191 前缀
        // 格式：\x19Ethereum Signed Message:\n<length><message>
        // 我们需要模拟这个行为以匹配 TypeScript 实现
        val prefix = "\u0019Ethereum Signed Message:\n${safeTxStructuredHash.size}".toByteArray(Charsets.UTF_8)
        val messageWithPrefix = ByteArray(prefix.size + safeTxStructuredHash.size)
        System.arraycopy(prefix, 0, messageWithPrefix, 0, prefix.size)
        System.arraycopy(safeTxStructuredHash, 0, messageWithPrefix, prefix.size, safeTxStructuredHash.size)

        // 对带前缀的消息进行 keccak256 哈希
        val keccak256 = org.bouncycastle.crypto.digests.KeccakDigest(256)
        keccak256.update(messageWithPrefix, 0, messageWithPrefix.size)
        val hashWithPrefix = ByteArray(keccak256.digestSize)
        keccak256.doFinal(hashWithPrefix, 0)

        logger.debug(
            "Safe exec hashToSign=0x{} (personal_sign 后签名的 32 字节)",
            hashWithPrefix.joinToString("") { "%02x".format(it) }
        )

        val ecKeyPair = org.web3j.crypto.ECKeyPair.create(privateKeyBigInt)
        val safeSignature = org.web3j.crypto.Sign.signMessage(hashWithPrefix, ecKeyPair, false)

        // 打包签名（参考 builder-relayer-client/src/utils/index.ts 的 splitAndPackSig）
        val packedSignature = splitAndPackSig(safeSignature)

        // 构建 TransactionRequest（参考 builder-relayer-client/src/builder/safe.ts）
        // 注意：根据 TypeScript 实现，data 和 signature 都应该带 0x 前缀
        val request = BuilderRelayerApi.TransactionRequest(
            type = RELAYER_TYPE_SAFE,
            from = fromAddress,
            to = safeTx.to,
            proxyWallet = proxyAddress,
            data = redeemCallData,  // 带 0x 前缀
            nonce = proxyNonce.toString(),
            signature = packedSignature,  // 带 0x 前缀
            signatureParams = BuilderRelayerApi.SignatureParams(
                gasPrice = "0",
                operation = safeTx.operation.toString(),
                safeTxnGas = "0",
                baseGas = "0",
                gasToken = gasToken,
                refundReceiver = refundReceiver
            ),
            metadata = metadata ?: if (safeTx.operation == 1) {
                "PolyHermes safe MultiSend batch"
            } else {
                "PolyHermes safe call"
            }
        )

        // 调用 Builder Relayer API（认证头通过拦截器添加，遇 429 限流时重试）
        val response = withBuilderRelayerRateLimitRetry { relayerApi.submitTransaction(request) }

        if (!response.isSuccessful || response.body() == null) {
            val errorBody = response.errorBody()?.string() ?: "未知错误"
            updateQuotaBlockedFromErrorBody(errorBody)
            logger.error("Builder Relayer API 调用失败: code=${response.code()}, body=$errorBody")
            return Result.failure(Exception("Builder Relayer API 调用失败: ${response.code()} - $errorBody"))
        }

        val relayerResponse = response.body()!!
        // 等待 Relayer 终态并核验回执，未到终态前不当作成功
        val txHash = awaitRelayerOutcome(relayerApi, relayerResponse, proxyAddress, checkRelayHubInnerStatus = false)
            .getOrElse { return Result.failure(it) }

        logger.info("Builder Relayer 执行成功: transactionID=${relayerResponse.transactionID}, txHash=$txHash")
        return Result.success(txHash)
    }

    /**
     * 通过 Builder Relayer 部署 Safe 代理（SAFE-CREATE）
     * 参考: builder-relayer-client client.ts deploy()、builder/create.ts buildSafeCreateTransactionRequest
     *
     * @param privateKey EOA 私钥
     * @param proxyAddress 待部署的 Safe 代理地址（与 getProxyAddress 一致）
     * @param fromAddress EOA 地址（from）
     * @return 交易哈希
     */
    suspend fun deploySafeViaBuilderRelayer(
        privateKey: String,
        proxyAddress: String,
        fromAddress: String
    ): Result<String> {
        return try {
            val builderApiKey = systemConfigService.getBuilderApiKey()
            val builderSecret = systemConfigService.getBuilderSecret()
            val builderPassphrase = systemConfigService.getBuilderPassphrase()
            if (!isBuilderRelayerEnabled(builderApiKey, builderSecret, builderPassphrase)) {
                return Result.failure(BuilderApiKeyNotConfiguredException("Safe 部署"))
            }
            val relayerApi = retrofitFactory.createBuilderRelayerApi(
                relayerUrl = PolymarketConstants.BUILDER_RELAYER_URL,
                apiKey = builderApiKey!!,
                secret = builderSecret!!,
                passphrase = builderPassphrase!!
            )
            val zeroAddress = "0x0000000000000000000000000000000000000000"
            val paymentToken = zeroAddress
            val payment = "0"
            val paymentReceiver = zeroAddress
            val domainSeparator = Eip712Encoder.encodeSafeCreateDomain(
                name = PolymarketConstants.SAFE_FACTORY_EIP712_NAME,
                chainId = 137L,
                verifyingContract = safeProxyFactoryAddress
            )
            val createProxyHash = Eip712Encoder.encodeCreateProxyMessage(
                paymentToken = paymentToken,
                payment = BigInteger.ZERO,
                paymentReceiver = paymentReceiver
            )
            val digest = Eip712Encoder.hashStructuredData(domainSeparator, createProxyHash)
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            val ecKeyPair = org.web3j.crypto.ECKeyPair.create(privateKeyBigInt)
            val signature = org.web3j.crypto.Sign.signMessage(digest, ecKeyPair, false)
            // SAFE-CREATE 使用标准 EIP-712 签名格式（0x + r + s + v，v 为 27/28），与 signTypedData 一致
            val signatureHex = signatureToStandardHex(signature)
            val request = BuilderRelayerApi.TransactionRequest(
                type = RELAYER_TYPE_SAFE_CREATE,
                from = fromAddress,
                to = safeProxyFactoryAddress,
                proxyWallet = proxyAddress,
                data = "0x",
                nonce = null,
                signature = signatureHex,
                signatureParams = BuilderRelayerApi.SignatureParams(
                    paymentToken = paymentToken,
                    payment = payment,
                    paymentReceiver = paymentReceiver
                ),
                metadata = null
            )
            val response = withBuilderRelayerRateLimitRetry { relayerApi.submitTransaction(request) }
            if (!response.isSuccessful || response.body() == null) {
                val errorBody = response.errorBody()?.string() ?: "未知错误"
                updateQuotaBlockedFromErrorBody(errorBody)
                logger.error("Builder Relayer SAFE-CREATE 失败: code=${response.code()}, body=$errorBody")
                return Result.failure(Exception("部署 Safe 失败: ${response.code()} - $errorBody"))
            }
            val relayerResponse = response.body()!!
            // 等待 Relayer 终态并核验回执，未到终态前不当作成功
            val txHash = awaitRelayerOutcome(relayerApi, relayerResponse, proxyAddress, checkRelayHubInnerStatus = false)
                .getOrElse { return Result.failure(it) }
            logger.info("Safe 部署成功: proxy=$proxyAddress, txHash=$txHash")
            Result.success(txHash)
        } catch (e: Exception) {
            logger.error("部署 Safe 失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 将 SignatureData 转为标准 hex 签名（0x + r(64) + s(64) + v(2)，v 为 27/28）
     * 用于 SAFE-CREATE，与 viem signTypedData 输出格式一致
     */
    private fun signatureToStandardHex(signature: org.web3j.crypto.Sign.SignatureData): String {
        val rHex = org.web3j.utils.Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0')
        val sHex = org.web3j.utils.Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0')
        val vBytes = signature.v
        val v = if (vBytes != null && vBytes.isNotEmpty()) {
            vBytes[0].toInt() and 0xff
        } else {
            27
        }
        val vHex = String.format("%02x", v)
        return "0x$rHex$sHex$vHex"
    }

    /**
     * 打包签名（参考 builder-relayer-client/src/utils/index.ts 的 splitAndPackSig）
     * 将签名打包成 Gnosis Safe 接受的格式：encodePacked(["uint256", "uint256", "uint8"], [r, s, v])
     *
     * TypeScript 实现流程：
     * 1. 从签名字符串中提取 v（最后 2 个字符）
     * 2. 调整 v 值（0,1 -> +31; 27,28 -> +4）
     * 3. 修改签名字符串（替换最后 2 个字符）
     * 4. 从修改后的签名字符串中提取 r, s, v（作为十进制字符串）
     * 5. 使用 encodePacked 打包：uint256(BigInt(r)) + uint256(BigInt(s)) + uint8(parseInt(v))
     *
     * 关键：encodePacked 会将 BigInt 编码为 32 字节（64 个十六进制字符），uint8 编码为 1 字节（2 个十六进制字符）
     */
    private fun splitAndPackSig(signature: org.web3j.crypto.Sign.SignatureData): String {
        // 1. 先将 SignatureData 转换为签名字符串（r + s + v）
        val rHex = org.web3j.utils.Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0')
        val sHex = org.web3j.utils.Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0')
        val vBytes = signature.v as ByteArray
        val originalV = if (vBytes.isNotEmpty()) {
            vBytes[0].toInt() and 0xff
        } else {
            throw IllegalArgumentException("Signature v is empty")
        }
        val originalVHex = String.format("%02x", originalV)
        val sigString = "0x$rHex$sHex$originalVHex"  // 130 个十六进制字符（65 字节）

        // 2. 从签名字符串中提取 v（最后 2 个字符，作为十六进制）
        val sigV = sigString.substring(sigString.length - 2).toInt(16)

        // 3. 调整 v 值（参考 TypeScript 实现）
        val adjustedV = when (sigV) {
            0, 1 -> sigV + 31
            27, 28 -> sigV + 4
            else -> throw IllegalArgumentException("Invalid signature v value: $sigV")
        }

        // 4. 修改签名字符串（替换最后 2 个字符）
        val modifiedSigString = sigString.substring(0, sigString.length - 2) + String.format("%02x", adjustedV)

        // 5. 从修改后的签名字符串中提取 r, s, v（作为十六进制字符串）
        // modifiedSigString 格式：0x + r(64) + s(64) + v(2) = 132 个字符
        val rHexStr = modifiedSigString.substring(2, 66)  // 64 个字符（十六进制）
        val sHexStr = modifiedSigString.substring(66, 130)  // 64 个字符（十六进制）
        val vHexStr = modifiedSigString.substring(130, 132)  // 2 个字符（十六进制）

        // 6. 转换为 BigInteger 和 Int（模拟 TypeScript 的 BigInt 和 parseInt）
        val rBigInt = BigInteger(rHexStr, 16)
        val sBigInt = BigInteger(sHexStr, 16)
        val vInt = vHexStr.toInt(16)

        // 7. 使用 encodePacked 打包：uint256(r) + uint256(s) + uint8(v)
        // encodePacked 会将 BigInt 编码为 32 字节（64 个十六进制字符），uint8 编码为 1 字节（2 个十六进制字符）
        val rEncoded = EthereumUtils.encodeUint256(rBigInt)  // 64 个十六进制字符
        val sEncoded = EthereumUtils.encodeUint256(sBigInt)  // 64 个十六进制字符
        val vEncoded = String.format("%02x", vInt)  // 2 个十六进制字符

        return "0x$rEncoded$sEncoded$vEncoded"
    }

    /**
     * 批量执行 Safe 交易
     * 参考 TypeScript: RelayClient.execute() 支持批量交易
     *
     * @param privateKey 私钥
     * @param proxyAddress 代理钱包地址
     * @param safeTxs Safe 交易列表
     * @return 交易哈希
     */
    suspend fun executeBatch(
        privateKey: String,
        proxyAddress: String,
        safeTxs: List<SafeTransaction>
    ): Result<String> {
        // 批量执行：将多个交易合并为一个 execTransaction 调用
        // 当前实现：委托给 com.wrbug.polymarketbot.service.common.BlockchainService
        return Result.failure(
            UnsupportedOperationException(
                "批量 Gasless 执行暂未实现。请使用 com.wrbug.polymarketbot.service.common.BlockchainService.redeemPositions() 方法。"
            )
        )
    }
}
