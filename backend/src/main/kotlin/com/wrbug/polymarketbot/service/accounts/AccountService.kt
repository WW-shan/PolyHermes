package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.enums.WalletType
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.eq
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.getEventSlug
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.PolymarketApiKeyService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderPushService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.util.CryptoUtils
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.BigInteger

/**
 * 账户管理服务
 */
@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val clobService: PolymarketClobService,
    private val retrofitFactory: RetrofitFactory,
    private val blockchainService: BlockchainService,
    private val apiKeyService: PolymarketApiKeyService,
    private val orderPushService: OrderPushService,
    private val orderSigningService: OrderSigningService,
    private val cryptoUtils: CryptoUtils,
    private val marketService: MarketService,  // 市场信息服务
    private val telegramNotificationService: TelegramNotificationService? = null,  // 可选，避免循环依赖
    private val relayClientService: RelayClientService,
    private val jsonUtils: JsonUtils
) {

    private val logger = LoggerFactory.getLogger(AccountService::class.java)
    
    // 协程作用域（用于异步发送通知）
    private val notificationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 在途赎回（accountId_conditionId），防止同一 condition 在上一笔未完成时重复提交 */
    private val inFlightRedeems: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 账户链上监听（删除账户时同步移除监听；延迟注入避免循环依赖） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private var accountOnChainMonitorService: com.wrbug.polymarketbot.service.copytrading.monitor.AccountOnChainMonitorService? = null

    // 市价单价格调整系数（在最优价基础上调整，确保更快成交）
    // 市价买单：bestAsk + BUY_PRICE_ADJUSTMENT（加价，确保能立即成交）
    // 市价卖单：bestBid - SELL_PRICE_ADJUSTMENT（减价，确保能立即成交）
    private val BUY_PRICE_ADJUSTMENT = BigDecimal("0.01")   // 买单价格调整系数（+0.01）
    private val SELL_PRICE_ADJUSTMENT = BigDecimal("0.02")  // 卖单价格调整系数（-0.02）

    /**
     * 通过私钥导入账户
     */
    @Transactional
    fun importAccount(request: AccountImportRequest): Result<AccountDto> {
        return try {
            // 1. 验证钱包地址格式
            if (!isValidWalletAddress(request.walletAddress)) {
                return Result.failure(IllegalArgumentException("无效的钱包地址格式"))
            }

            // 3. 验证私钥和地址的对应关系（后端必须自行校验，不能只依赖前端）
            if (!isValidPrivateKey(request.privateKey)) {
                return Result.failure(IllegalArgumentException("无效的私钥格式"))
            }
            val privateKeyAddress = deriveAddressFromPrivateKey(request.privateKey)
                ?: return Result.failure(IllegalArgumentException("无效的私钥：无法推导地址"))
            if (!privateKeyAddress.equals(request.walletAddress, ignoreCase = true)) {
                logger.warn("私钥与钱包地址不匹配，拒绝导入")
                return Result.failure(
                    IllegalArgumentException("私钥与钱包地址不匹配：私钥对应地址为 $privateKeyAddress")
                )
            }

            // 钱包类型统一规范化后使用（非法值回退 magic，与历史行为一致）
            val walletTypeEnum = WalletType.fromStringOrDefault(request.walletType, WalletType.MAGIC)

            // 4. 自动获取或创建 API Key（必须成功，否则导入失败）
            val apiKeyCreds = runBlocking {
                val result = apiKeyService.createOrDeriveApiKey(
                    privateKey = request.privateKey,
                    walletAddress = request.walletAddress,
                    chainId = 137L  // Polygon 主网
                )

                if (result.isSuccess) {
                    val creds = result.getOrNull()
                    if (creds != null) {
                        creds
                    } else {
                        logger.error("自动获取 API Key 返回空值")
                        throw IllegalStateException("自动获取 API Key 失败：返回值为空")
                    }
                } else {
                    val error = result.exceptionOrNull()
                    logger.error("自动获取 API Key 失败: ${error?.message}")
                    throw IllegalStateException("自动获取 API Key 失败: ${error?.message}。请确保私钥有效且账户已激活")
                }
            }

            // 5. 获取代理地址（必须成功，否则导入失败）
            // 根据用户选择的钱包类型计算代理地址
            val proxyAddress = runBlocking {
                val proxyResult = blockchainService.getProxyAddress(request.walletAddress, walletTypeEnum)
                if (proxyResult.isSuccess) {
                    val address = proxyResult.getOrNull()
                    if (address != null) {
                        address
                    } else {
                        logger.error("获取代理地址返回空值")
                        throw IllegalStateException("获取代理地址失败：返回值为空")
                    }
                } else {
                    val error = proxyResult.exceptionOrNull()
                    logger.error("获取代理地址失败: ${error?.message}")
                    throw IllegalStateException("获取代理地址失败: ${error?.message}。请确保已配置 Ethereum RPC URL 且 RPC 节点可用")
                }
            }

            // 6. 按代理地址去重：该代理地址已存在则不允许重复导入
            if (accountRepository.existsByProxyAddress(proxyAddress)) {
                return Result.failure(IllegalArgumentException("ACCOUNT_ALREADY_EXISTS"))
            }

            // 6.1 Deposit Wallet 已部署时校验 owner 与 EOA 一致，避免导入错误的钱包
            if (walletTypeEnum == WalletType.DEPOSIT) {
                val mismatch = runBlocking { checkDepositWalletOwnerMismatch(request.walletAddress, proxyAddress) }
                if (mismatch != null) {
                    return Result.failure(IllegalStateException(mismatch))
                }
            }

            // 7. 加密敏感信息
            val encryptedPrivateKey = cryptoUtils.encrypt(request.privateKey)
            val encryptedApiSecret = apiKeyCreds.secret.let { cryptoUtils.encrypt(it) }
            val encryptedApiPassphrase = apiKeyCreds.passphrase.let { cryptoUtils.encrypt(it) }

            // 8. 生成账户名称（如果未提供，使用 SAFE/MAGIC-代理地址后4位）
            val accountName = if (request.accountName.isNullOrBlank()) {
                val typeLabel = walletTypeEnum.name.uppercase()
                val proxyWithoutPrefix = if (proxyAddress.startsWith("0x") || proxyAddress.startsWith("0X")) {
                    proxyAddress.substring(2)
                } else {
                    proxyAddress
                }
                val suffix = if (proxyWithoutPrefix.length >= 4) {
                    proxyWithoutPrefix.substring(proxyWithoutPrefix.length - 4).uppercase()
                } else {
                    proxyWithoutPrefix.uppercase()
                }
                "$typeLabel-$suffix"
            } else {
                request.accountName.trim()
            }

            // 9. 创建账户
            val account = Account(
                privateKey = encryptedPrivateKey,  // 存储加密后的私钥
                walletAddress = request.walletAddress,
                proxyAddress = proxyAddress,
                apiKey = apiKeyCreds.apiKey,
                apiSecret = encryptedApiSecret,  // 存储加密后的 API Secret
                apiPassphrase = encryptedApiPassphrase,  // 存储加密后的 API Passphrase
                accountName = accountName,
                isDefault = false,  // 不再支持默认账户
                isEnabled = request.isEnabled,
                walletType = walletTypeEnum.value,  // 保存规范化后的钱包类型
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            val saved = accountRepository.save(account)

            // 刷新订单推送订阅（如果账户启用且有 API 凭证）
            orderPushService.refreshSubscriptions()

            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("导入账户失败", e)
            Result.failure(e)
        }
    }

    /**
     * 检查代理地址选项（用于账户导入前选择代理类型）
     * 私钥导入：返回 Deposit Wallet、Safe、Magic 三个选项
     * 助记词导入：返回 Deposit Wallet、Safe 两个选项（Magic 仅支持私钥导入）
     * 同时查询 Polymarket 档案中的实际 proxyWallet，命中的选项标记为 recommended 并排在最前
     */
    suspend fun checkProxyOptions(request: CheckProxyOptionsRequest): Result<CheckProxyOptionsResponse> {
        return try {
            // 1. 验证钱包地址格式
            if (!isValidWalletAddress(request.walletAddress)) {
                return Result.failure(IllegalArgumentException("无效的钱包地址格式"))
            }

            // 2. 判断导入类型：私钥导入可选 Magic，助记词导入不可
            // 优先使用 importMethod；旧前端未传时仅按私钥/助记词字段是否非空推断（绝不读取或记录其内容）
            val isPrivateKeyImport = resolveIsPrivateKeyImport(request)
                .getOrElse { return Result.failure(it) }
            val candidateTypes = if (isPrivateKeyImport) {
                listOf(WalletType.DEPOSIT, WalletType.SAFE, WalletType.MAGIC)
            } else {
                listOf(WalletType.DEPOSIT, WalletType.SAFE)
            }

            // 4. 并行获取各类型代理地址、资产及 Polymarket 档案中的实际钱包
            val options = coroutineScope {
                val profileDeferred = async { fetchPolymarketProfileProxyWallet(request.walletAddress) }
                val optionDeferreds = candidateTypes.map { type ->
                    async { buildProxyOption(request.walletAddress, type) }
                }
                val profileWallet = profileDeferred.await()
                val built = optionDeferreds.map { it.await() }
                built.map { option ->
                    val recommended = profileWallet != null &&
                            option.proxyAddress.isNotBlank() &&
                            option.proxyAddress.equals(profileWallet, ignoreCase = true)
                    if (recommended) option.copy(recommended = true) else option
                }.sortedByDescending { it.recommended }
            }

            Result.success(CheckProxyOptionsResponse(options = options))
        } catch (e: Exception) {
            logger.error("检查代理地址选项失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 解析导入方式：PRIVATE_KEY → true，MNEMONIC → false；
     * 未传 importMethod 时兼容旧前端：助记词字段非空且私钥为空 → 助记词导入，其余按私钥导入（返回全部候选）
     */
    internal fun resolveIsPrivateKeyImport(request: CheckProxyOptionsRequest): Result<Boolean> {
        return when (request.importMethod?.trim()?.uppercase()) {
            "PRIVATE_KEY" -> Result.success(true)
            "MNEMONIC" -> Result.success(false)
            null, "" -> Result.success(!(request.privateKey.isNullOrBlank() && !request.mnemonic.isNullOrBlank()))
            else -> Result.failure(IllegalArgumentException("importMethod 无效，应为 PRIVATE_KEY 或 MNEMONIC"))
        }
    }

    /**
     * 构建单个代理地址选项（地址 + 资产 + 部署状态），失败时返回带 error 的选项而不抛异常
     */
    private suspend fun buildProxyOption(walletAddress: String, walletType: WalletType): ProxyOptionDto {
        val descriptionKey = "accountImport.proxyOption.${walletType.value}.description"
        val emptyOption = ProxyOptionDto(
            walletType = walletType.value,
            proxyAddress = "",
            descriptionKey = descriptionKey,
            availableBalance = "0",
            positionBalance = "0",
            totalBalance = "0",
            positionCount = 0,
            hasAssets = false
        )
        return try {
            val proxyAddress = blockchainService.getProxyAddress(walletAddress, walletType).getOrElse {
                return emptyOption.copy(error = "获取 ${walletType.name} 代理地址失败: ${it.message}")
            }
            // 余额 / 部署状态查询失败时在 error 中标注，前端显示“查询失败”，不把失败当作 0 / 未部署
            val balanceResult = blockchainService.getWalletBalance(proxyAddress)
            val deployedResult = blockchainService.checkProxyDeployed(proxyAddress)
            val balance = balanceResult.getOrNull()
            val deployed = deployedResult.getOrDefault(false)
            val queryError = listOfNotNull(
                balanceResult.exceptionOrNull()?.let { "资产查询失败: ${it.message}" },
                deployedResult.exceptionOrNull()?.let { "部署状态查询失败: ${it.message}" }
            ).joinToString("; ").ifBlank { null }
            ProxyOptionDto(
                walletType = walletType.value,
                proxyAddress = proxyAddress,
                descriptionKey = descriptionKey,
                availableBalance = balance?.availableBalance ?: "0",
                positionBalance = balance?.positionBalance ?: "0",
                totalBalance = balance?.totalBalance ?: "0",
                positionCount = balance?.positions?.size ?: 0,
                hasAssets = (balance?.availableBalance?.toSafeBigDecimal()?.gt(BigDecimal.ZERO) == true) ||
                        (balance?.positionBalance?.toSafeBigDecimal()?.gt(BigDecimal.ZERO) == true) ||
                        (balance?.positions?.isNotEmpty() == true),
                deployed = deployed,
                error = queryError
            )
        } catch (e: Exception) {
            logger.warn("获取 ${walletType.name} 代理地址或资产失败: ${e.message}", e)
            emptyOption.copy(error = "获取资产信息失败: ${e.message}")
        }
    }

    /**
     * 查询 Polymarket 档案（gamma public-profile）中该 EOA 实际使用的 proxyWallet，用于推荐正确的钱包类型
     * @return proxyWallet 地址（小写）；无档案或查询失败返回 null
     */
    private suspend fun fetchPolymarketProfileProxyWallet(walletAddress: String): String? {
        return try {
            val response = retrofitFactory.createGammaApi().getPublicProfile(walletAddress)
            if (!response.isSuccessful) {
                logger.debug("查询 Polymarket 档案失败: code=${response.code()}")
                return null
            }
            response.body()?.proxyWallet?.takeIf { isValidWalletAddress(it) }?.lowercase()
        } catch (e: Exception) {
            logger.debug("查询 Polymarket 档案异常: ${e.message}")
            null
        }
    }

    /**
     * Deposit Wallet 已部署时校验链上 owner() 是否为导入的 EOA
     * @return 不匹配时返回错误信息，匹配或未部署返回 null
     */
    private suspend fun checkDepositWalletOwnerMismatch(walletAddress: String, depositWallet: String): String? {
        // 部署状态查询失败时 fail-closed，不能当作“未部署”跳过 owner 校验
        val deployed = blockchainService.checkProxyDeployed(depositWallet).getOrElse {
            return "无法查询 Deposit Wallet $depositWallet 的部署状态，请确认 RPC 节点可用后重试"
        }
        if (!deployed) return null
        // 已部署但读不到 owner 时 fail-closed：不能把 RPC 失败当成 owner 匹配
        val owner = blockchainService.getDepositWalletOwner(depositWallet)
            ?: return "无法读取 Deposit Wallet $depositWallet 的 owner，请确认 RPC 节点可用后重试"
        return if (owner.equals(walletAddress, ignoreCase = true)) {
            null
        } else {
            "Deposit Wallet $depositWallet 的 owner 为 $owner，与导入的钱包地址不一致"
        }
    }

    /**
     * 交易授权项
     * @param key approvalDetails 中的 key（前端按 accountSetup.approvalDetails.<key> 显示）
     * @param erc1155 true: ERC1155 setApprovalForAll；false: ERC20 approve(MAX)
     * @param token 代币合约
     * @param spender ERC20 spender / ERC1155 operator
     */
    internal data class TradingApproval(
        val key: String = "",
        val erc1155: Boolean = false,
        val token: String = "",
        val spender: String = ""
    )

    /**
     * 官方交易授权清单（与 Polymarket ts-sdk actions/approvals.ts getRequiredTradingApprovals 一致）：
     * pUSD 对 7 个 spender 无限授权；CTF 对 7 个 operator、PositionManager 对 3 个 operator setApprovalForAll。
     * 注意：0xd91E80...（CLOB v1 NegRiskAdapter）不在官方清单中，不再要求。
     */
    internal val requiredTradingApprovals: List<TradingApproval> = run {
        val pusd = RelayClientService.PUSD_ADDRESS
        val ctf = RelayClientService.CONDITIONAL_TOKENS_ADDRESS
        val positionManager = "0x006F54F7f9A22e0000CC2AB60031000000ae9fEF"
        val exchange = "0xE111180000d2663C0091e4f400237545B87B996B"
        val negRiskExchange = "0xe2222d279d744050d28e00520010520000310F59"
        val adapter = RelayClientService.CTF_COLLATERAL_ADAPTER
        val negRiskAdapter = RelayClientService.NEG_RISK_CTF_COLLATERAL_ADAPTER
        val router = "0x12121212006e4CD160D18e3f00711DA5c3372600"
        val exchangeV3 = "0xe3333700cA9d93003F00f0F71f8515005F6c00Aa"
        val perps = "0xDCa4af75705dbB50f62437045afF9921947917d2"
        val autoRedeem = "0xa1200000d0002264C9a1698e001292D00E1b00af"
        val binaryModule = "0x1000008dD9001B968442c1000017eaE6E0dA00Ba"
        val negRiskModule = "0x200000900045e3B6259600682756002200028933"
        listOf(
            TradingApproval("CTF_EXCHANGE", false, pusd, exchange),
            TradingApproval("NEG_RISK_EXCHANGE", false, pusd, negRiskExchange),
            TradingApproval("COLLATERAL_ADAPTER", false, pusd, adapter),
            TradingApproval("NEG_RISK_COLLATERAL_ADAPTER", false, pusd, negRiskAdapter),
            TradingApproval("PROTOCOL_V2_ROUTER", false, pusd, router),
            TradingApproval("EXCHANGE_V3", false, pusd, exchangeV3),
            TradingApproval("PERPS_DEPOSIT", false, pusd, perps),
            TradingApproval("CTF_APPROVAL_CTF_EXCHANGE", true, ctf, exchange),
            TradingApproval("CTF_APPROVAL_NEG_RISK_EXCHANGE", true, ctf, negRiskExchange),
            TradingApproval("CTF_APPROVAL_COLLATERAL_ADAPTER", true, ctf, adapter),
            TradingApproval("CTF_APPROVAL_NEG_RISK_COLLATERAL_ADAPTER", true, ctf, negRiskAdapter),
            TradingApproval("CTF_APPROVAL_AUTO_REDEEM", true, ctf, autoRedeem),
            TradingApproval("CTF_APPROVAL_BINARY_MODULE", true, ctf, binaryModule),
            TradingApproval("CTF_APPROVAL_NEG_RISK_MODULE", true, ctf, negRiskModule),
            TradingApproval("POSITION_MANAGER_APPROVAL_PROTOCOL_V2_ROUTER", true, positionManager, router),
            TradingApproval("POSITION_MANAGER_APPROVAL_EXCHANGE_V3", true, positionManager, exchangeV3),
            TradingApproval("POSITION_MANAGER_APPROVAL_AUTO_REDEEM", true, positionManager, autoRedeem)
        )
    }

    /** USDC 精度（6 位小数） */
    private val usdcDecimals = java.math.BigDecimal("1000000")

    /** ERC20 无限授权额度（type(uint256).max），Polymarket 默认使用无限授权 */
    private val unlimitedAllowance = BigInteger("115792089237316195423570985008687907853269984665640564039457584007913129639935")

    /**
     * 检查账户设置状态（代理部署、交易启用、代币批准）
     * @param accountId 账户 ID
     * @return AccountSetupStatusDto
     */
    suspend fun checkAccountSetupStatus(accountId: Long): Result<AccountSetupStatusDto> {
        return try {
            if (accountId <= 0) {
                return Result.failure(IllegalArgumentException("账户 ID 无效"))
            }
            val account = accountRepository.findById(accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))

            val proxyAddress = account.proxyAddress
            if (proxyAddress.isBlank()) {
                return Result.success(
                    AccountSetupStatusDto(
                        proxyDeployed = false,
                        tradingEnabled = account.apiKey != null && account.apiSecret != null && account.apiPassphrase != null,
                        tokensApproved = false,
                        approvalDetails = null,
                        error = "代理地址为空"
                    )
                )
            }

            // 步骤1：代理钱包是否已部署（查询失败不当作“未部署”，在 error 中提示）
            val deployedResult = blockchainService.checkProxyDeployed(proxyAddress)
            val proxyDeployed = deployedResult.getOrDefault(false)

            // 步骤2：交易是否已启用（API 凭证是否已配置）
            val tradingEnabled = account.apiKey != null &&
                    account.apiSecret != null &&
                    account.apiPassphrase != null

            // 步骤3：官方授权清单（ERC20 allowance + ERC1155 isApprovedForAll），任一缺失即未完成
            val (approvalDetails, missing, queryFailed) = queryTradingApprovals(proxyAddress)
            val tokensApproved = missing.isEmpty() && queryFailed.isEmpty()

            val errors = mutableListOf<String>()
            deployedResult.exceptionOrNull()?.let { errors.add("代理部署状态查询失败: ${it.message}") }
            if (queryFailed.isNotEmpty()) errors.add("部分授权状态查询失败: ${queryFailed.joinToString(",")}")

            Result.success(
                AccountSetupStatusDto(
                    proxyDeployed = proxyDeployed,
                    tradingEnabled = tradingEnabled,
                    tokensApproved = tokensApproved,
                    approvalDetails = approvalDetails,
                    error = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
                )
            )
        } catch (e: Exception) {
            logger.error("检查账户设置状态失败: accountId=$accountId, ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 查询官方授权清单每一项的状态
     * @return Triple(approvalDetails, 缺失项, 查询失败项)；
     *   approvalDetails 值：ERC20 为 "unlimited" 或额度（6 位小数），ERC1155 为 "approved" / "0"，查询失败为 "queryFailed"
     */
    internal suspend fun queryTradingApprovals(
        proxyAddress: String
    ): Triple<Map<String, String>, List<TradingApproval>, List<TradingApproval>> {
        val details = linkedMapOf<String, String>()
        val missing = mutableListOf<TradingApproval>()
        val failed = mutableListOf<TradingApproval>()
        for (approval in requiredTradingApprovals) {
            if (approval.erc1155) {
                blockchainService.isErc1155ApprovedForAll(proxyAddress, approval.spender, approval.token).fold(
                    onSuccess = { approved ->
                        details[approval.key] = if (approved) "approved" else "0"
                        if (!approved) missing.add(approval)
                    },
                    onFailure = { details[approval.key] = "queryFailed"; failed.add(approval) }
                )
            } else {
                blockchainService.getErc20Allowance(approval.token, proxyAddress, approval.spender).fold(
                    onSuccess = { allowance ->
                        details[approval.key] = if (allowance >= unlimitedAllowance) {
                            "unlimited"
                        } else {
                            java.math.BigDecimal(allowance).divide(usdcDecimals, 6, java.math.RoundingMode.DOWN).toPlainString()
                        }
                        // 与官方一致：额度低于 MAX 视为需要重新授权
                        if (allowance < unlimitedAllowance) missing.add(approval)
                    },
                    onFailure = { details[approval.key] = "queryFailed"; failed.add(approval) }
                )
            }
        }
        return Triple(details, missing, failed)
    }

    /** 步骤1 跳转 URL（代理部署需在 Polymarket 完成） */
    private val setupStep1RedirectUrl = "https://polymarket.com/settings/wallet"

    /**
     * 执行设置步骤（由后端实现或返回跳转）
     * 步骤1：仅返回跳转 URL，由用户前往 Polymarket 完成部署
     * 步骤2：创建/派生 API Key 并更新账户
     * 步骤3：通过代理钱包批量执行 USDC 授权
     */
    suspend fun executeSetupStep(accountId: Long, step: Int): Result<ExecuteSetupStepResponse> {
        return try {
            if (accountId <= 0) {
                return Result.failure(IllegalArgumentException("账户 ID 无效"))
            }
            val account = accountRepository.findById(accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))

            when (step) {
                1 -> {
                    val walletType = WalletType.fromStringOrDefault(account.walletType, WalletType.MAGIC)
                    if (walletType == WalletType.MAGIC) {
                        Result.success(
                            ExecuteSetupStepResponse(
                                success = false,
                                redirectUrl = setupStep1RedirectUrl
                            )
                        )
                    } else {
                        val proxyAddress = account.proxyAddress
                        if (proxyAddress.isBlank()) {
                            return Result.failure(IllegalArgumentException("代理地址为空"))
                        }
                        // getCode 查询失败时不能当作“未部署”而重复部署
                        val alreadyDeployed = blockchainService.checkProxyDeployed(proxyAddress).getOrElse {
                            return Result.failure(IllegalStateException("查询代理钱包部署状态失败，请稍后重试: ${it.message}"))
                        }
                        if (alreadyDeployed) {
                            Result.success(ExecuteSetupStepResponse(success = true))
                        } else {
                            val deployResult = if (walletType == WalletType.DEPOSIT) {
                                // Deposit Wallet 由 Relayer WALLET-CREATE 部署，无需签名
                                relayClientService.deployDepositWalletViaBuilderRelayer(
                                    fromAddress = account.walletAddress
                                )
                            } else {
                                val privateKey = decryptPrivateKey(account)
                                relayClientService.deploySafeViaBuilderRelayer(
                                    privateKey = privateKey,
                                    proxyAddress = proxyAddress,
                                    fromAddress = account.walletAddress
                                )
                            }
                            deployResult.fold(
                                onSuccess = { txHash ->
                                    Result.success(
                                        ExecuteSetupStepResponse(
                                            success = true,
                                            transactionHash = txHash
                                        )
                                    )
                                },
                                onFailure = { e ->
                                    logger.error("${walletType.name} 代理部署失败: accountId=$accountId, ${e.message}", e)
                                    Result.failure(e)
                                }
                            )
                        }
                    }
                }
                2 -> {
                    val privateKey = decryptPrivateKey(account)
                    val result = apiKeyService.createOrDeriveApiKey(
                        privateKey = privateKey,
                        walletAddress = account.walletAddress,
                        chainId = 137L
                    )
                    if (result.isFailure) {
                        val e = result.exceptionOrNull()
                        logger.error("启用交易（API Key）失败: accountId=$accountId, ${e?.message}", e)
                        return Result.failure(e ?: IllegalStateException("获取 API Key 失败"))
                    }
                    val creds = result.getOrNull()
                        ?: return Result.failure(IllegalStateException("API Key 返回为空"))
                    val encryptedSecret = creds.secret.let { cryptoUtils.encrypt(it) }
                    val encryptedPassphrase = creds.passphrase.let { cryptoUtils.encrypt(it) }
                    val updated = account.copy(
                        apiKey = creds.apiKey,
                        apiSecret = encryptedSecret,
                        apiPassphrase = encryptedPassphrase,
                        updatedAt = System.currentTimeMillis()
                    )
                    accountRepository.save(updated)
                    orderPushService.refreshSubscriptions()
                    Result.success(ExecuteSetupStepResponse(success = true))
                }
                3 -> {
                    val proxyAddress = account.proxyAddress
                    if (proxyAddress.isBlank()) {
                        return Result.failure(IllegalArgumentException("代理地址为空，请先完成步骤1"))
                    }
                    val privateKey = decryptPrivateKey(account)
                    val walletType = WalletType.fromStringOrDefault(account.walletType, WalletType.SAFE)
                    // 代理钱包未部署时 eth_call 返回 0x，直接提示先完成步骤1（Magic 代理由 Polymarket 首次交易时部署）
                    val deployed = blockchainService.checkProxyDeployed(proxyAddress).getOrElse {
                        return Result.failure(IllegalStateException("查询代理钱包部署状态失败，请稍后重试: ${it.message}"))
                    }
                    if (!deployed) {
                        return Result.failure(IllegalStateException("代理钱包尚未部署，请先完成步骤1"))
                    }
                    // Magic / Safe / Deposit Wallet 的授权都通过 Builder Relayer（Gasless）执行
                    if (!relayClientService.isBuilderApiKeyConfigured()) {
                        return Result.failure(RelayClientService.BuilderApiKeyNotConfiguredException("代币授权（Gasless）"))
                    }
                    // 只提交缺失的授权项；查询失败的项也一并提交（授权幂等，重复授权无副作用）
                    val (_, missing, queryFailed) = queryTradingApprovals(proxyAddress)
                    val toApprove = missing + queryFailed
                    if (toApprove.isEmpty()) {
                        return Result.success(ExecuteSetupStepResponse(success = true))
                    }
                    val approveTxs = toApprove.map { approval ->
                        if (approval.erc1155) {
                            relayClientService.createErc1155SetApprovalForAllTx(approval.token, approval.spender)
                        } else {
                            relayClientService.createUsdcApproveTx(approval.spender, unlimitedAllowance)
                        }
                    }
                    // Safe 走 MultiSend，Deposit Wallet 走原生批量调用，Magic 走 proxy(calls[]) 批量调用
                    val executeResult = relayClientService.executeCalls(
                        privateKey = privateKey,
                        proxyAddress = proxyAddress,
                        txs = approveTxs,
                        walletType = walletType,
                        metadata = "PolyHermes token approvals"
                    )
                    executeResult.fold(
                        onSuccess = { txHash ->
                            Result.success(
                                ExecuteSetupStepResponse(
                                    success = true,
                                    transactionHash = txHash
                                )
                            )
                        },
                        onFailure = { e ->
                            logger.error("代币授权执行失败: accountId=$accountId, ${e.message}", e)
                            Result.failure(e)
                        }
                    )
                }
                else -> Result.failure(IllegalArgumentException("无效的步骤: $step，应为 1、2 或 3"))
            }
        } catch (e: Exception) {
            logger.error("执行设置步骤失败: accountId=$accountId, step=$step, ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 更新账户信息
     */
    @Transactional
    fun updateAccount(request: AccountUpdateRequest): Result<AccountDto> {
        return try {
            val account = accountRepository.findById(request.accountId)
                .orElse(null) ?: return Result.failure(IllegalArgumentException("账户不存在"))

            // 更新账户名称
            val updatedAccountName = request.accountName ?: account.accountName

            // 更新启用状态
            val updatedIsEnabled = request.isEnabled ?: account.isEnabled

            val updated = account.copy(
                accountName = updatedAccountName,
                isDefault = account.isDefault,  // 保持原值，不再支持修改
                isEnabled = updatedIsEnabled,
                updatedAt = System.currentTimeMillis()
            )

            val saved = accountRepository.save(updated)

            // 刷新订单推送订阅（账户状态变更时）
            orderPushService.refreshSubscriptions()

            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("更新账户失败", e)
            Result.failure(e)
        }
    }

    /**
     * 删除账户
     */
    @Transactional
    fun deleteAccount(accountId: Long): Result<Unit> {
        return try {
            val account = accountRepository.findById(accountId)
                .orElse(null) ?: return Result.failure(IllegalArgumentException("账户不存在"))

            // 注意：不再检查活跃订单，允许用户删除有活跃订单的账户
            // 前端会显示确认提示框，由用户决定是否删除

            accountRepository.delete(account)

            // 同步移除该账户的链上监听，避免资源泄漏
            try {
                accountOnChainMonitorService?.removeAccount(accountId)
            } catch (e: Exception) {
                logger.warn("移除账户链上监听失败: accountId=$accountId, ${e.message}")
            }

            // 刷新订单推送订阅（账户删除时）
            orderPushService.refreshSubscriptions()

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除账户失败", e)
            Result.failure(e)
        }
    }

    /**
     * 查询账户列表
     * 列表接口只返回基本信息，不查询统计信息（统计信息只在详情接口中查询）
     */
    fun getAccountList(): Result<AccountListResponse> {
        return try {
            val accounts = accountRepository.findAllByOrderByCreatedAtAsc()
            val accountDtos = accounts.map { toBasicDto(it) }

            Result.success(
                AccountListResponse(
                    list = accountDtos,
                    total = accountDtos.size.toLong()
                )
            )
        } catch (e: Exception) {
            logger.error("查询账户列表失败", e)
            Result.failure(e)
        }
    }

    /**
     * 查询账户详情
     */
    fun getAccountDetail(accountId: Long?): Result<AccountDto> {
        return try {
            if (accountId == null) {
                return Result.failure(IllegalArgumentException("账户ID不能为空"))
            }
            
            val account = accountRepository.findById(accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))

            Result.success(toDto(account))
        } catch (e: Exception) {
            logger.error("查询账户详情失败", e)
            Result.failure(e)
        }
    }

    /**
     * 查询账户余额
     * 通过链上 RPC 查询 USDC 余额，并通过 Subgraph API 查询持仓信息
     */
    fun getAccountBalance(accountId: Long?): Result<AccountBalanceResponse> {
        return try {
            if (accountId == null) {
                return Result.failure(IllegalArgumentException("账户ID不能为空"))
            }

            val account = accountRepository.findById(accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))

            // 检查代理地址是否存在
            if (account.proxyAddress.isBlank()) {
                logger.error("账户 ${account.id} 的代理地址为空，无法查询余额")
                return Result.failure(IllegalStateException("账户代理地址不存在，无法查询余额。请重新导入账户以获取代理地址"))
            }

            // 使用通用方法查询余额
            val balanceResult = runBlocking {
                blockchainService.getWalletBalance(account.proxyAddress)
            }

            balanceResult.map { walletBalance: WalletBalanceResponse ->
                AccountBalanceResponse(
                    availableBalance = walletBalance.availableBalance,
                    positionBalance = walletBalance.positionBalance,
                    totalBalance = walletBalance.totalBalance,
                    positions = walletBalance.positions
                )
            }
        } catch (e: Exception) {
            logger.error("查询账户余额失败", e)
            Result.failure(e)
        }
    }

    /**
     * 转换为基础 DTO（列表使用，不包含统计信息）
     * 列表接口只返回基本信息，不查询统计信息，以提高性能
     */
    private fun toBasicDto(account: Account): AccountDto {
        return AccountDto(
            id = account.id!!,
            walletAddress = account.walletAddress,
            proxyAddress = account.proxyAddress,
            accountName = account.accountName,
            isEnabled = account.isEnabled,
            walletType = account.walletType,
            apiKeyConfigured = account.apiKey != null,
            apiSecretConfigured = account.apiSecret != null,
            apiPassphraseConfigured = account.apiPassphrase != null,
            totalOrders = null,
            totalPnl = null,
            activeOrders = null,
            completedOrders = null,
            positionCount = null
        )
    }

    /**
     * 转换为完整 DTO（详情使用，包含交易统计数据）
     * 包含交易统计数据（总订单数、总盈亏、活跃订单数、已完成订单数、持仓数量）
     */
    private fun toDto(account: Account): AccountDto {
        return runBlocking {
            val statistics = getAccountStatistics(account)
            AccountDto(
                id = account.id!!,
                walletAddress = account.walletAddress,
                proxyAddress = account.proxyAddress,
                accountName = account.accountName,
                isEnabled = account.isEnabled,
                walletType = account.walletType,
                apiKeyConfigured = account.apiKey != null,
                apiSecretConfigured = account.apiSecret != null,
                apiPassphraseConfigured = account.apiPassphrase != null,
                totalOrders = statistics.totalOrders,
                totalPnl = statistics.totalPnl,
                activeOrders = statistics.activeOrders,
                completedOrders = statistics.completedOrders,
                positionCount = statistics.positionCount
            )
        }
    }

    /**
     * 获取账户交易统计数据
     */
    private suspend fun getAccountStatistics(account: Account): AccountStatistics {
        return try {
            // 如果账户没有配置 API 凭证，无法查询统计数据
            if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return AccountStatistics(
                    totalOrders = null,
                    totalPnl = null,
                    activeOrders = null,
                    completedOrders = null,
                    positionCount = null
                )
            }

            // 解密 API 凭证
            val apiKey = account.apiKey
            val apiSecret = decryptApiSecret(account)
            val apiPassphrase = decryptApiPassphrase(account)

            // 创建带认证的 API 客户端（需要钱包地址用于 POLY_ADDRESS 请求头）
            val clobApi = retrofitFactory.createClobApi(apiKey, apiSecret, apiPassphrase, account.walletAddress)

            // 1. 查询活跃订单数量（open/active 状态）
            val activeOrdersResult = try {
                var totalActiveOrders = 0L
                var nextCursor: String? = null

                // 分页查询所有活跃订单
                do {
                    val response = clobApi.getActiveOrders(
                        id = null,
                        market = null,
                        asset_id = null,
                        next_cursor = nextCursor
                    )
                    if (response.isSuccessful && response.body() != null) {
                        val ordersResponse = response.body()!!
                        totalActiveOrders += ordersResponse.data.size
                        nextCursor = ordersResponse.next_cursor
                    } else {
                        break
                    }
                } while (nextCursor != null && nextCursor.isNotEmpty())

                Result.success(totalActiveOrders)
            } catch (e: Exception) {
                logger.warn("查询活跃订单失败: ${e.message}", e)
                Result.failure(e)
            }

            // 2. 查询已完成订单数
            // 注意：交易记录数不等于已完成订单数，因为一个订单可能产生多笔交易
            // 已完成订单应该是指已完全成交或已关闭的订单
            // 由于 Polymarket CLOB API 没有直接查询所有订单（包括已完成）的接口，
            // 我们通过查询交易记录来估算已完成订单数
            // 但更准确的方式是统计去重后的订单ID数量
            val completedOrdersResult = try {
                // 使用代理地址查询交易记录（作为 maker 的交易）
                var allTrades = mutableListOf<TradeResponse>()
                var nextCursor: String? = null

                // 分页查询所有交易（作为 maker）
                do {
                    val response = clobApi.getTrades(
                        maker_address = account.proxyAddress,
                        next_cursor = nextCursor
                    )
                    if (response.isSuccessful && response.body() != null) {
                        val tradesResponse = response.body()!!
                        allTrades.addAll(tradesResponse.data)
                        nextCursor = tradesResponse.next_cursor
                    } else {
                        break
                    }
                } while (nextCursor != null && nextCursor.isNotEmpty())

                // 注意：Polymarket API 的 getTrades 接口只支持查询 maker_address，
                // 如果需要查询作为 taker 的交易，可能需要使用其他接口或查询方式
                // 目前只统计作为 maker 的交易记录

                // 由于 TradeResponse 没有 orderId 字段，我们无法直接去重订单
                // 这里使用交易记录数作为已完成订单数的近似值
                // 更准确的方式需要查询所有订单并统计状态为 "filled" 的订单
                val completedOrdersCount = allTrades.size.toLong()

                Result.success(completedOrdersCount)
            } catch (e: Exception) {
                logger.warn("查询交易记录失败: ${e.message}", e)
                Result.failure(e)
            }

            // 3. 查询仓位信息计算总盈亏（已实现盈亏）和持仓数量
            val positionsResult = try {
                val positions = blockchainService.getPositions(account.proxyAddress)
                if (positions.isSuccess) {
                    val positionList = positions.getOrNull() ?: emptyList()
                    // 汇总所有仓位的已实现盈亏
                    val totalRealizedPnl = positionList.sumOf { pos ->
                        pos.realizedPnl?.toSafeBigDecimal() ?: BigDecimal.ZERO
                    }
                    // 统计持仓数量（所有非零持仓，包括正负仓位）
                    // size 可能为正数（做多）或负数（做空），都应该统计
                    val positionCount = positionList.count { pos ->
                        val size = pos.size?.toSafeBigDecimal() ?: BigDecimal.ZERO
                        size != BigDecimal.ZERO  // 统计所有非零持仓
                    }
                    Result.success(Pair(totalRealizedPnl.toPlainString(), positionCount.toLong()))
                } else {
                    Result.failure(Exception("查询仓位信息失败"))
                }
            } catch (e: Exception) {
                logger.warn("查询仓位信息失败: ${e.message}", e)
                Result.failure(e)
            }

            val activeOrders = activeOrdersResult.getOrNull() ?: 0L
            val completedOrders = completedOrdersResult.getOrNull() ?: 0L
            // 总订单数 = 活跃订单数 + 已完成订单数
            val totalOrders = activeOrders + completedOrders
            val (totalPnl, positionCount) = positionsResult.getOrNull() ?: Pair(null, null)

            AccountStatistics(
                totalOrders = totalOrders,
                totalPnl = totalPnl,
                activeOrders = activeOrders,
                completedOrders = completedOrders,  // 已完成订单数 = 交易记录数（已成交的订单）
                positionCount = positionCount
            )
        } catch (e: Exception) {
            logger.warn("获取账户统计数据失败: ${e.message}", e)
            AccountStatistics(
                totalOrders = null,
                totalPnl = null,
                activeOrders = null,
                completedOrders = null,
                positionCount = null
            )
        }
    }

    /**
     * 账户统计数据
     */
    private data class AccountStatistics(
        val totalOrders: Long?,
        val totalPnl: String?,
        val activeOrders: Long?,
        val completedOrders: Long?,
        val positionCount: Long?
    )

    /**
     * 验证钱包地址格式
     */
    private fun isValidWalletAddress(address: String): Boolean {
        // 以太坊地址格式：0x 开头，42 位字符
        return address.startsWith("0x") && address.length == 42 && address.matches(Regex("^0x[0-9a-fA-F]{40}$"))
    }

    /**
     * 验证私钥格式
     */
    private fun isValidPrivateKey(privateKey: String): Boolean {
        // 私钥格式：64 位十六进制字符（可选 0x 前缀）
        val cleanKey = if (privateKey.startsWith("0x")) privateKey.substring(2) else privateKey
        return cleanKey.length == 64 && cleanKey.matches(Regex("^[0-9a-fA-F]{64}$"))
    }

    /**
     * 从私钥推导 EOA 地址（小写）；私钥非法时返回 null
     */
    private fun deriveAddressFromPrivateKey(privateKey: String): String? {
        return try {
            val cleanKey = privateKey.removePrefix("0x").removePrefix("0X")
            val keyPair = org.web3j.crypto.ECKeyPair.create(java.math.BigInteger(cleanKey, 16))
            "0x" + org.web3j.crypto.Keys.getAddress(keyPair)
        } catch (e: Exception) {
            logger.warn("私钥推导地址失败: ${e.message}")
            null
        }
    }

    /**
     * 解密账户私钥
     */
    fun decryptPrivateKey(account: Account): String {
        return try {
            cryptoUtils.decrypt(account.privateKey)
        } catch (e: Exception) {
            logger.error("解密私钥失败: accountId=${account.id}", e)
            throw RuntimeException("解密私钥失败: ${e.message}", e)
        }
    }

    /**
     * 轮询用：遍历所有账户，对代理地址 WCOL 余额 > 0 的执行解包。
     * 由 WcolUnwrapJobService 每 20 秒调用，赎回后无需在赎回流程内等待确认与解包。
     */
    suspend fun runWcolUnwrapForAllAccounts() {
        val accounts = accountRepository.findAllByOrderByCreatedAtAsc()
        if (accounts.isEmpty()) return
        for (account in accounts) {
            try {
                val privateKey = decryptPrivateKey(account)
                val walletType = WalletType.fromStringOrDefault(account.walletType, WalletType.SAFE)
                blockchainService.unwrapWcolForProxy(
                    privateKey = privateKey,
                    proxyAddress = account.proxyAddress,
                    walletType = walletType
                ).fold(
                    onSuccess = { txHash ->
                        if (txHash != null) {
                            logger.info("轮询解包 WCOL: accountId=${account.id}, proxy=${account.proxyAddress.take(10)}..., txHash=$txHash")
                        }
                    },
                    onFailure = { e ->
                        logger.warn("轮询解包 WCOL 失败 accountId=${account.id}: ${e.message}")
                    }
                )
            } catch (e: Exception) {
                logger.warn("轮询解包 WCOL 跳过 accountId=${account.id}: ${e.message}")
            }
        }
    }

    /**
     * 解密账户 API Secret
     */
    private fun decryptApiSecret(account: Account): String {
        return account.apiSecret?.let { secret ->
            try {
                cryptoUtils.decrypt(secret)
            } catch (e: Exception) {
                logger.error("解密 API Secret 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Secret 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Secret")
    }
    
    /**
     * 解密账户 API Passphrase
     */
    private fun decryptApiPassphrase(account: Account): String {
        return account.apiPassphrase?.let { passphrase ->
            try {
                cryptoUtils.decrypt(passphrase)
            } catch (e: Exception) {
                logger.error("解密 API Passphrase 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Passphrase 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Passphrase")
    }

    /**
     * 查询所有账户的仓位列表
     * 返回所有账户的仓位信息，包括账户信息
     */
    suspend fun getAllPositions(): Result<PositionListResponse> {
        return try {
            val accounts = accountRepository.findAll()
            val currentPositions = mutableListOf<AccountPositionDto>()
            val historyPositions = mutableListOf<AccountPositionDto>()
            // 获取失败的账户：调用方（持仓核对、卖出）必须跳过，不能把“拉不到”当成“没有仓位”
            val failedAccountIds = mutableListOf<Long>()

            // 遍历所有账户，查询每个账户的仓位
            accounts.forEach { account ->
                if (account.proxyAddress.isNotBlank()) {
                    try {
                        // 查询所有仓位（不限制 sortBy，获取当前和历史仓位）
                        val positionsResult = blockchainService.getPositions(account.proxyAddress)
                        if (positionsResult.isSuccess) {
                            val positions = positionsResult.getOrNull() ?: emptyList()
                            // 遍历所有仓位，区分当前仓位和历史仓位
                            positions.forEach { pos ->
                                val currentValue = pos.currentValue?.toSafeBigDecimal() ?: BigDecimal.ZERO
                                val curPrice = pos.curPrice?.toSafeBigDecimal() ?: BigDecimal.ZERO

                                // 判断是否为当前仓位：currentValue != 0 且 curPrice != 0
                                // 使用 eq 方法判断值是否等于 0
                                val isCurrent = !currentValue.eq(BigDecimal.ZERO) && !curPrice.eq(BigDecimal.ZERO)

                                // 将 Double 转换为精确的 BigDecimal，保留完整精度
                                val sizeDecimal = pos.size?.let { 
                                    BigDecimal.valueOf(it)  // 使用 BigDecimal.valueOf 保留 Double 的完整精度
                                } ?: BigDecimal.ZERO
                                
                                // 显示用的数量（保留4位小数，用于显示）
                                val displayQuantity = sizeDecimal.setScale(4, java.math.RoundingMode.DOWN).toPlainString()
                                // 原始数量（保留完整精度，用于100%出售）
                                val originalQuantity = sizeDecimal.toPlainString()

                                val positionDto = AccountPositionDto(
                                    accountId = account.id!!,
                                    accountName = account.accountName,
                                    walletAddress = account.walletAddress,
                                    proxyAddress = account.proxyAddress,
                                    marketId = pos.conditionId ?: "",
                                    marketTitle = pos.title ?: "",
                                    marketSlug = pos.slug ?: "",  // 显示用的 slug
                                    eventSlug = pos.eventSlug,  // 跳转用的 slug（从 events[0].slug 获取）
                                    marketIcon = pos.icon,  // 市场图标
                                    side = pos.outcome ?: "",
                                    outcomeIndex = pos.outcomeIndex,  // 添加 outcomeIndex
                                    quantity = displayQuantity,  // 显示用的数量
                                    originalQuantity = originalQuantity,  // 原始数量（完整精度）
                                    avgPrice = pos.avgPrice?.toString() ?: "0",
                                    currentPrice = pos.curPrice?.toString() ?: "0",
                                    currentValue = pos.currentValue?.toString() ?: "0",
                                    initialValue = pos.initialValue?.toString() ?: "0",
                                    pnl = pos.cashPnl?.toString() ?: "0",
                                    percentPnl = pos.percentPnl?.toString() ?: "0",
                                    realizedPnl = pos.realizedPnl?.toString(),
                                    percentRealizedPnl = pos.percentRealizedPnl?.toString(),
                                    redeemable = pos.redeemable ?: false,
                                    mergeable = pos.mergeable ?: false,
                                    endDate = pos.endDate,
                                    isCurrent = isCurrent,  // 标识是当前仓位还是历史仓位
                                    tokenId = pos.asset?.takeIf { it.isNotBlank() },
                                    negativeRisk = pos.negativeRisk
                                )

                                // 根据 isCurrent 分别添加到对应的列表
                                if (isCurrent) {
                                    currentPositions.add(positionDto)
                                } else {
                                    historyPositions.add(positionDto)
                                }
                            }
                        } else {
                            failedAccountIds.add(account.id!!)
                            logger.warn("查询账户 ${account.id} 仓位失败: ${positionsResult.exceptionOrNull()?.message}")
                        }
                    } catch (e: Exception) {
                        failedAccountIds.add(account.id!!)
                        logger.warn("查询账户 ${account.id} 仓位失败: ${e.message}", e)
                    }
                }
            }

            // 按照接口返回的顺序返回，不进行排序
            // 前端负责本地排序
            Result.success(
                PositionListResponse(
                    currentPositions = currentPositions,
                    historyPositions = historyPositions,
                    failedAccountIds = failedAccountIds
                )
            )
        } catch (e: Exception) {
            logger.error("查询所有仓位失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    internal fun exchangeContractForMarket(negRisk: Boolean?): String =
        orderSigningService.getExchangeContract(negRisk == true)

    /**
     * 卖出仓位
     */
    suspend fun sellPosition(request: PositionSellRequest): Result<PositionSellResponse> {
        return try {
            // 1. 验证账户是否存在且已配置API凭证
            val account = accountRepository.findById(request.accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("账户不存在"))

            if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return Result.failure(IllegalStateException("账户未配置API凭证，无法创建订单"))
            }

            // 2. 验证参数：percent 和 quantity 至少提供一个
            if (request.percent.isNullOrBlank() && request.quantity.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("必须提供卖出数量(quantity)或卖出百分比(percent)"))
            }
            
            if (!request.percent.isNullOrBlank() && !request.quantity.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("不能同时提供卖出数量(quantity)和卖出百分比(percent)"))
            }
            
            // 验证百分比值（如果提供了）
            val percentDecimal = if (!request.percent.isNullOrBlank()) {
                try {
                    val percent = request.percent!!.toSafeBigDecimal()
                    if (percent <= BigDecimal.ZERO || percent > BigDecimal.valueOf(100)) {
                        return Result.failure(IllegalArgumentException("卖出百分比必须在 0-100 之间"))
                    }
                    percent
                } catch (e: Exception) {
                    return Result.failure(IllegalArgumentException("卖出百分比格式不正确: ${e.message}"))
                }
            } else {
                null
            }

            // 3. 从实时仓位中定位要卖的仓位（获取失败的账户直接报错，不能当作“仓位不存在”）
            val positionList = getAllPositions().getOrElse {
                return Result.failure(Exception("查询仓位失败: ${it.message}"))
            }
            if (request.accountId in positionList.failedAccountIds) {
                return Result.failure(IllegalStateException("账户仓位获取失败，请稍后刷新重试"))
            }
            val position = positionList.currentPositions.find {
                it.accountId == request.accountId && it.marketId == request.marketId &&
                        (if (request.outcomeIndex != null) it.outcomeIndex == request.outcomeIndex else it.side == request.side)
            } ?: return Result.failure(IllegalArgumentException("仓位不存在"))
            val originalQuantity = (position.originalQuantity ?: position.quantity).toSafeBigDecimal()

            // 4. 确定 tokenId：优先实时仓位的 asset；前端传入的 tokenId 必须与之一致
            val positionTokenId = position.tokenId
            if (!request.tokenId.isNullOrBlank() && positionTokenId != null && request.tokenId != positionTokenId) {
                return Result.failure(IllegalArgumentException("tokenId 与实时仓位不一致，请刷新后重试"))
            }
            val negRisk = position.negativeRisk ?: marketService.getNegRiskByConditionId(request.marketId)
                ?: return Result.failure(IllegalStateException("无法确定市场是否为 Neg Risk，已拒绝下单，请稍后重试"))
            val tokenId = positionTokenId ?: run {
                val outcomeIndex = position.outcomeIndex ?: request.outcomeIndex
                    ?: return Result.failure(IllegalArgumentException("缺少 outcomeIndex，无法确定 tokenId"))
                blockchainService.getTokenId(request.marketId, outcomeIndex, negRisk).getOrElse {
                    return Result.failure(IllegalStateException("无法获取 tokenId: ${it.message}"))
                }
            }

            // 5. 百分比卖出：前端看到的持仓与实时持仓偏差过大时拒绝，提示刷新
            if (percentDecimal != null && !request.expectedQuantity.isNullOrBlank()) {
                val expected = request.expectedQuantity.toBigDecimalOrNull()
                    ?: return Result.failure(IllegalArgumentException("expectedQuantity 格式不正确"))
                if (!isExpectedQuantityConsistent(expected, originalQuantity)) {
                    return Result.failure(IllegalStateException("持仓已变化（当前 ${originalQuantity.toPlainString()}），请刷新后重试"))
                }
            }

            // 6. 计算卖出数量：按 2 位小数向下取整，且不超过持仓
            val rawQuantity = if (percentDecimal != null) {
                originalQuantity.multiply(percentDecimal).divide(BigDecimal.valueOf(100), 8, java.math.RoundingMode.DOWN)
            } else {
                request.quantity!!.toBigDecimalOrNull()
                    ?: return Result.failure(IllegalArgumentException("卖出数量格式不正确"))
            }
            val sellQuantity = normalizeSellQuantity(rawQuantity)
            if (sellQuantity <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException("卖出数量必须大于0（最小 0.01）"))
            }
            if (sellQuantity > originalQuantity) {
                return Result.failure(IllegalArgumentException("卖出数量不能超过持仓数量"))
            }

            // 7. 价格：按市场 tick 校验 / 计算（签名前自行校验，不依赖签名服务）
            val tick = fetchTickSize(tokenId).getOrElse {
                return Result.failure(IllegalStateException("获取市场最小价格单位失败: ${it.message}"))
            }
            val sellPrice = if (request.orderType == "MARKET") {
                val bestBid = fetchBestBid(tokenId).getOrElse {
                    return Result.failure(IllegalStateException("无法获取订单表最优价: ${it.message}"))
                }
                marketSellPrice(bestBid, SELL_PRICE_ADJUSTMENT, tick).toPlainString()
            } else {
                val limit = request.price?.toBigDecimalOrNull()
                    ?: return Result.failure(IllegalArgumentException("限价订单必须提供有效价格"))
                validateLimitPrice(limit, tick)?.let { return Result.failure(IllegalArgumentException(it)) }
                limit.stripTrailingZeros().toPlainString()
            }

            // 8. 确定订单类型和过期时间（GTC/FOK/FAK 的 expiration 均为 "0"）
            val orderType = when (request.orderType) {
                "MARKET" -> "FAK"  // Fill-And-Kill（与官方市价单一致，允许部分成交）
                "LIMIT" -> "GTC"   // Good-Til-Cancelled
                else -> "GTC"
            }

            // 9. 解密私钥
            val decryptedPrivateKey = decryptPrivateKey(account)

            // 10. Neg Risk 市场使用 Neg Risk Exchange 签约
            val exchangeContract = exchangeContractForMarket(negRisk)

            // 12. 创建并签名订单（使用计算后的卖出数量，按账户钱包类型使用对应 signatureType）
            val signedOrder = try {
                orderSigningService.createAndSignOrder(
                    privateKey = decryptedPrivateKey,
                    makerAddress = account.proxyAddress,  // 使用代理地址作为 maker
                    tokenId = tokenId,
                    side = "SELL",
                    price = sellPrice,
                    size = sellQuantity.toPlainString(),  // 使用计算后的卖出数量
                    signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType),
                    exchangeContract = exchangeContract,
                    tickSize = tick,
                    strictTick = true
                )
            } catch (e: Exception) {
                logger.error("创建并签名订单失败", e)
                return Result.failure(Exception("创建并签名订单失败: ${e.message}"))
            }

            // 12. 构建订单请求

            val newOrderRequest = com.wrbug.polymarketbot.api.NewOrderRequest(
                order = signedOrder,
                owner = account.apiKey,  // API Key
                orderType = orderType
            )

            // 13. 解密 API 凭证并使用账户的API凭证创建订单
            val apiSecret = try {
                decryptApiSecret(account)
            } catch (e: Exception) {
                logger.error("解密 API 凭证失败: accountId=${account.id}", e)
                return Result.failure(IllegalStateException("解密 API 凭证失败: ${e.message}"))
            }
            val apiPassphrase = try {
                decryptApiPassphrase(account)
            } catch (e: Exception) {
                logger.error("解密 API 凭证失败: accountId=${account.id}", e)
                return Result.failure(IllegalStateException("解密 API 凭证失败: ${e.message}"))
            }

            val clobApi = retrofitFactory.createClobApi(
                account.apiKey,
                apiSecret,
                apiPassphrase,
                account.walletAddress
            )


            val orderResponse = clobApi.createOrder(newOrderRequest)

            if (orderResponse.isSuccessful && orderResponse.body() != null) {
                val response = orderResponse.body()!!
                if (response.success) {
                    val orderId = response.orderId ?: ""
                    
                    // 发送订单成功通知（异步，不阻塞）
                    notificationScope.launch {
                        try {
                            // 获取市场信息（标题和slug）
                            val market = marketService.getMarket(request.marketId)
                            val marketTitle = market?.title ?: request.marketId
                            val marketSlug = market?.eventSlug  // 跳转用的 slug

                            // 获取当前语言设置（从 LocaleContextHolder）
                            val locale = try {
                                org.springframework.context.i18n.LocaleContextHolder.getLocale()
                            } catch (e: Exception) {
                                java.util.Locale("zh", "CN")  // 默认简体中文
                            }

                            // 使用当前时间作为订单创建时间
                            val orderTime = System.currentTimeMillis()
                            
                            // 查询可用余额
                            val availableBalance = try {
                                blockchainService.getUsdcBalance(account.walletAddress, account.proxyAddress).getOrNull()
                            } catch (e: Exception) {
                                logger.warn("查询可用余额失败: accountId=${account.id}, ${e.message}")
                                null
                            }

                            telegramNotificationService?.sendOrderSuccessNotification(
                                orderId = orderId,
                                marketTitle = marketTitle,
                                marketId = request.marketId,
                                marketSlug = marketSlug,
                                side = "SELL",  // 手动卖出订单，方向固定为 SELL
                                outcome = request.side,  // request.side 是市场方向（YES/NO）
                                price = sellPrice,  // 直接传递卖出价格
                                size = sellQuantity.toPlainString(),  // 直接传递卖出数量
                                accountName = account.accountName,
                                walletAddress = account.walletAddress,
                                clobApi = clobApi,
                                apiKey = account.apiKey,
                                apiSecret = try { cryptoUtils.decrypt(account.apiSecret!!) } catch (e: Exception) { null },
                                apiPassphrase = try { cryptoUtils.decrypt(account.apiPassphrase!!) } catch (e: Exception) { null },
                                walletAddressForApi = account.walletAddress,
                                locale = locale,
                                orderTime = orderTime,  // 使用订单创建时间
                                availableBalance = availableBalance
                            )
                        } catch (e: Exception) {
                            logger.warn("发送订单成功通知失败: ${e.message}", e)
                        }
                    }
                    
                    Result.success(
                        PositionSellResponse(
                            orderId = orderId,
                            marketId = request.marketId,
                            side = request.side,
                            orderType = request.orderType,
                            quantity = sellQuantity.toPlainString(),  // 使用计算后的卖出数量
                            price = if (request.orderType == "LIMIT") sellPrice else null,
                            status = "pending",  // 订单状态需要从响应中获取
                            createdAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    val errorMsg = response.getErrorMessage()
                    val fullErrorMsg = "创建订单失败: accountId=${account.id}, marketId=${request.marketId}, side=${request.side}, orderType=${request.orderType}, price=${if (request.orderType == "LIMIT") sellPrice else "MARKET"}, quantity=${sellQuantity.toPlainString()}, errorMsg=$errorMsg"
                    logger.error(fullErrorMsg)
                    
                    // 发送订单失败通知（异步，不阻塞）
                    notificationScope.launch {
                        try {
                            // 获取市场信息（标题和slug）
                            val market = marketService.getMarket(request.marketId)
                            val marketTitle = market?.title ?: request.marketId
                            val marketSlug = market?.eventSlug  // 跳转用的 slug

                            // 获取当前语言设置（从 LocaleContextHolder）
                            val locale = try {
                                org.springframework.context.i18n.LocaleContextHolder.getLocale()
                            } catch (e: Exception) {
                                java.util.Locale("zh", "CN")  // 默认简体中文
                            }

                            telegramNotificationService?.sendOrderFailureNotification(
                                marketTitle = marketTitle,
                                marketId = request.marketId,
                                marketSlug = marketSlug,
                                side = request.side,
                                outcome = null,  // 失败时可能没有 outcome
                                price = if (request.orderType == "LIMIT") sellPrice.toString() else "MARKET",
                                size = sellQuantity.toString(),
                                errorMessage = errorMsg,  // 只传递后端返回的 msg
                                accountName = account.accountName,
                                walletAddress = account.walletAddress,
                                locale = locale
                            )
                        } catch (e: Exception) {
                            logger.warn("发送订单失败通知失败: ${e.message}", e)
                        }
                    }
                    
                    Result.failure(Exception(fullErrorMsg))
                }
            } else {
                val errorBody = try {
                    orderResponse.errorBody()?.string()
                } catch (e: Exception) {
                    null
                }
                
                // 尝试从 errorBody 解析 error 字段（使用 Gson）
                val apiError = try {
                    (errorBody?.fromJson<JsonObject>()?.get("error") as? JsonPrimitive)?.asString
                } catch (e: Exception) {
                    null
                }
                
                val fullErrorMsg = "创建订单失败: accountId=${account.id}, marketId=${request.marketId}, side=${request.side}, orderType=${request.orderType}, price=${if (request.orderType == "LIMIT") sellPrice else "MARKET"}, quantity=${sellQuantity.toPlainString()}, code=${orderResponse.code()}, message=${orderResponse.message()}${if (errorBody != null) ", errorBody=$errorBody" else ""}"
                logger.error(fullErrorMsg)
                
                // 发送订单失败通知（异步，不阻塞）
                notificationScope.launch {
                    try {
                        // 获取市场信息（标题和slug）
                        val market = marketService.getMarket(request.marketId)
                        val marketTitle = market?.title ?: request.marketId
                        val marketSlug = market?.eventSlug  // 跳转用的 slug

                        // 获取当前语言设置（从 LocaleContextHolder）
                        val locale = try {
                            org.springframework.context.i18n.LocaleContextHolder.getLocale()
                        } catch (e: Exception) {
                            java.util.Locale("zh", "CN")  // 默认简体中文
                        }

                        // 优先使用解析的 API error，其次使用响应体的 errorMsg，最后使用默认消息
                        val errorMsg = apiError 
                            ?: orderResponse.body()?.getErrorMessage() 
                            ?: "创建订单失败 (HTTP ${orderResponse.code()})"

                        telegramNotificationService?.sendOrderFailureNotification(
                            marketTitle = marketTitle,
                            marketId = request.marketId,
                            marketSlug = marketSlug,
                            side = request.side,
                            outcome = null,  // 失败时可能没有 outcome
                            price = if (request.orderType == "LIMIT") sellPrice.toString() else "MARKET",
                            size = sellQuantity.toString(),
                            errorMessage = errorMsg,  // 只传递后端返回的错误信息
                            accountName = account.accountName,
                            walletAddress = account.walletAddress,
                            locale = locale
                        )
                    } catch (e: Exception) {
                        logger.warn("发送订单失败通知失败: ${e.message}", e)
                    }
                }
                
                Result.failure(Exception(fullErrorMsg))
            }
        } catch (e: Exception) {
            val fullErrorMsg = "卖出仓位异常: accountId=${request.accountId}, marketId=${request.marketId}, side=${request.side}, orderType=${request.orderType}, error=${e.message}"
            logger.error(fullErrorMsg, e)
            Result.failure(Exception(fullErrorMsg))
        }
    }

    /**
     * 限价校验：价格必须在 [tick, 1 - tick] 且是 tick 的整数倍；不合法返回错误信息
     */
    internal fun validateLimitPrice(price: BigDecimal, tick: BigDecimal): String? {
        if (price < tick || price > BigDecimal.ONE.subtract(tick)) {
            return "限价必须在 [$tick, ${BigDecimal.ONE.subtract(tick).toPlainString()}] 之间"
        }
        if (price.remainder(tick).compareTo(BigDecimal.ZERO) != 0) {
            return "限价必须是最小价格单位 $tick 的整数倍"
        }
        return null
    }

    /**
     * 市价卖出价格 = max(bestBid − 滑点, tick)，按 tick 向下取整（bestBid 为最高买价）
     */
    internal fun marketSellPrice(bestBid: BigDecimal, slippage: BigDecimal, tick: BigDecimal): BigDecimal {
        val raw = bestBid.subtract(slippage)
        val floored = raw.divide(tick, 0, java.math.RoundingMode.DOWN).multiply(tick)
        return maxOf(floored, tick).stripTrailingZeros()
    }

    /**
     * 卖出数量按 2 位小数向下取整（与官方 SDK SELL makerAmount 精度一致）
     */
    internal fun normalizeSellQuantity(quantity: BigDecimal): BigDecimal =
        quantity.setScale(2, java.math.RoundingMode.DOWN)

    /**
     * 前端看到的持仓与实时持仓偏差是否在允许范围内：max(1% × 实时持仓, 0.01 份)
     */
    internal fun isExpectedQuantityConsistent(expected: BigDecimal, actual: BigDecimal): Boolean {
        val tolerance = maxOf(actual.abs().multiply(BigDecimal("0.01")), BigDecimal("0.01"))
        return expected.subtract(actual).abs() <= tolerance
    }

    /**
     * 查询 CLOB 最小价格单位（GET /tick-size?token_id=，返回 minimum_tick_size）；失败返回 Result.failure
     */
    private suspend fun fetchTickSize(tokenId: String): Result<BigDecimal> = withContext(Dispatchers.IO) {
        try {
            val url = "${com.wrbug.polymarketbot.constants.PolymarketConstants.CLOB_BASE_URL}/tick-size?token_id=$tokenId"
            val request = okhttp3.Request.Builder().url(url).get().build()
            com.wrbug.polymarketbot.util.createClient().build().newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrBlank()) {
                    return@withContext Result.failure(Exception("查询 tick size 失败: HTTP ${response.code}"))
                }
                val tick = body.fromJson<JsonObject>()?.get("minimum_tick_size")?.asString?.toSafeBigDecimal()
                if (tick == null || tick <= BigDecimal.ZERO || tick >= BigDecimal.ONE) {
                    return@withContext Result.failure(Exception("tick size 无效: $body"))
                }
                Result.success(tick.stripTrailingZeros())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 查询订单簿最高买价（bids 按价格升序返回，最优价为最大值）
     */
    private suspend fun fetchBestBid(tokenId: String): Result<BigDecimal> {
        val orderbook = clobService.getOrderbookByTokenId(tokenId).getOrElse { return Result.failure(it) }
        val bestBid = orderbook.bids.mapNotNull { it.price.toBigDecimalOrNull() }.maxOrNull()
            ?: return Result.failure(IllegalStateException("订单簿没有买单，无法市价卖出"))
        return Result.success(bestBid)
    }

    /**
     * 从订单表获取最优价（用于市价单）
     * 支持多元市场（二元、三元及以上）
     * 委托给 com.wrbug.polymarketbot.service.common.PolymarketClobService.getOptimalPrice 方法
     *
     * @param tokenId token ID（通过 marketId 和 outcomeIndex 计算得出）
     * @param isSellOrder 是否为卖出订单（true: 卖单，需要 bestBid；false: 买单，需要 bestAsk）
     * @return 最优价格（已应用调整系数）
     * @throws IllegalStateException 如果无法获取订单表或订单表为空
     */
    private suspend fun getOptimalPriceFromOrderbook(tokenId: String, isSellOrder: Boolean): String {
        return clobService.getOptimalPrice(
            tokenId = tokenId,
            isSellOrder = isSellOrder,
            buyPriceAdjustment = BUY_PRICE_ADJUSTMENT,
            sellPriceAdjustment = SELL_PRICE_ADJUSTMENT
        )
    }

    /**
     * 获取市场价格
     * 使用 Gamma API 获取价格信息，因为 Gamma API 支持 condition_ids 参数
     * @param marketId 市场ID
     * @param outcomeIndex 结果索引（可选）：0, 1, 2...，用于确定需要查询哪个 outcome 的价格。如果提供了 outcomeIndex 且 > 0，会转换价格（1 - 第一个outcome的价格）
     */
    suspend fun getMarketPrice(marketId: String, outcomeIndex: Int? = null): Result<MarketPriceResponse> {
        return try {
            // 使用 Gamma API 获取市场信息（支持 condition_ids 参数）
            val gammaApi = retrofitFactory.createGammaApi()
            var response = gammaApi.listMarkets(conditionIds = listOf(marketId))
            // Gamma 对已结束市场默认返回 []，需加 closed=true 才能查到
            if (response.isSuccessful && response.body().isNullOrEmpty()) {
                response = gammaApi.listMarkets(conditionIds = listOf(marketId), closed = true)
            }

            if (response.isSuccessful && response.body() != null) {
                val markets = response.body()!!
                val market = markets.firstOrNull()

                if (market != null) {
                    // 从 Gamma API 响应中提取价格信息（这些价格通常是针对第一个 outcome，index = 0）
                    var bestBid = market.bestBid?.toString()
                    var bestAsk = market.bestAsk?.toString()
                    var lastPrice = market.lastTradePrice?.toString()

                    // 如果目标 outcome 不是第一个（index != 0），需要转换价格
                    // 对于二元市场：第二个 outcome 的价格 = 1 - 第一个 outcome 的价格
                    if (outcomeIndex != null && outcomeIndex > 0) {
                        val outcomes = jsonUtils.parseStringArray(market.outcomes)
                        // 只对二元市场进行价格转换
                        if (outcomes.size == 2) {
                            // 保存原始第一个 outcome 的价格
                            val firstOutcomeBestBid = bestBid
                            val firstOutcomeBestAsk = bestAsk
                            
                            // 转换价格：第二个 outcome 的 bestBid = 1 - 第一个 outcome 的 bestAsk
                            // 第二个 outcome 的 bestAsk = 1 - 第一个 outcome 的 bestBid
                            bestBid = firstOutcomeBestAsk?.let { 
                                BigDecimal.ONE.subtract(it.toSafeBigDecimal()).toString()
                            }
                            bestAsk = firstOutcomeBestBid?.let { 
                                BigDecimal.ONE.subtract(it.toSafeBigDecimal()).toString()
                            }
                            
                            // 转换最后成交价：第二个 outcome 的 lastPrice = 1 - 第一个 outcome 的 lastPrice
                            lastPrice = lastPrice?.let {
                                BigDecimal.ONE.subtract(it.toSafeBigDecimal()).toString()
                            }
                        }
                    }

                    // 计算中间价 = (bestBid + bestAsk) / 2
                    val midpoint = if (bestBid != null && bestAsk != null) {
                        val bid = bestBid.toSafeBigDecimal()
                        val ask = bestAsk.toSafeBigDecimal()
                        bid.add(ask).divide(BigDecimal("2"), 8, java.math.RoundingMode.HALF_UP).toString()
                    } else {
                        null
                    }

                    // 优先使用 lastPrice（最近成交价），如果没有则使用 bestBid，最后使用 midpoint
                    val currentPrice = lastPrice ?: bestBid ?: midpoint ?: "0"

                    Result.success(
                        MarketPriceResponse(
                            marketId = marketId,
                            currentPrice = currentPrice
                        )
                    )
                } else {
                    Result.failure(Exception("未找到市场信息: $marketId"))
                }
            } else {
                Result.failure(Exception("获取市场价格失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取市场价格异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 获取可赎回仓位统计
     */
    suspend fun getRedeemablePositionsSummary(accountId: Long? = null): Result<RedeemablePositionsSummary> {
        return try {
            val positionsResult = getAllPositions()
            positionsResult.fold(
                onSuccess = { positionListResponse ->
                    // 筛选可赎回的仓位
                    val redeemablePositions = positionListResponse.currentPositions.filter { it.redeemable }

                    // 如果指定了账户ID，进一步筛选
                    val filteredPositions = if (accountId != null) {
                        redeemablePositions.filter { it.accountId == accountId }
                    } else {
                        redeemablePositions
                    }

                    // 计算总价值（赎回是1:1，所以价值等于数量）
                    val totalValue = filteredPositions.fold(BigDecimal.ZERO) { sum, pos ->
                        sum.add(pos.quantity.toSafeBigDecimal())
                    }

                    // 转换为可赎回仓位信息列表
                    val redeemableInfoList = filteredPositions.map { pos ->
                        com.wrbug.polymarketbot.dto.RedeemablePositionInfo(
                            accountId = pos.accountId,
                            accountName = pos.accountName,
                            marketId = pos.marketId,
                            marketTitle = pos.marketTitle,
                            side = pos.side,
                            outcomeIndex = pos.outcomeIndex ?: 0,
                            quantity = pos.quantity,
                            value = pos.quantity  // 赎回价值等于数量（1:1）
                        )
                    }

                    Result.success(
                        com.wrbug.polymarketbot.dto.RedeemablePositionsSummary(
                            totalCount = redeemableInfoList.size,
                            totalValue = totalValue.toPlainString(),
                            positions = redeemableInfoList
                        )
                    )
                },
                onFailure = { e ->
                    Result.failure(Exception("查询仓位失败: ${e.message}"))
                }
            )
        } catch (e: Exception) {
            logger.error("获取可赎回仓位统计失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 赎回仓位
     * 支持多账户、多仓位赎回（自动按账户和市场分组）
     */
    suspend fun redeemPositions(request: PositionRedeemRequest): Result<PositionRedeemResponse> {
        return try {
            if (request.positions.isEmpty()) {
                return Result.failure(IllegalArgumentException("赎回仓位列表不能为空"))
            }

            // 1. 验证仓位是否存在且可赎回
            val positionsResult = getAllPositions()
            val allPositions = positionsResult.getOrElse {
                return Result.failure(Exception("查询仓位失败: ${it.message}"))
            }

            // 2. 按账户分组
            val positionsByAccount = request.positions.groupBy { it.accountId }

            // 3. 验证所有账户是否存在
            val accounts = mutableMapOf<Long, Account>()
            for (accountId in positionsByAccount.keys) {
                val account = accountRepository.findById(accountId).orElse(null)
                    ?: return Result.failure(IllegalArgumentException("账户不存在: $accountId"))
                accounts[accountId] = account
            }

            // 4. 所有钱包类型（Safe / Magic / Deposit Wallet）的赎回都通过 Builder Relayer 执行，必须已配置 Builder API Key
            if (!relayClientService.isBuilderApiKeyConfigured()) {
                return Result.failure(RelayClientService.BuilderApiKeyNotConfiguredException("赎回（Gasless）"))
            }

            // 5. 验证并收集要赎回的仓位信息（按账户分组）
            val accountRedeemData = mutableMapOf<Long, MutableList<Pair<AccountPositionDto, BigInteger>>>()
            val accountRedeemedInfo =
                mutableMapOf<Long, MutableList<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>>()

            for ((accountId, requestItems) in positionsByAccount) {
                val accountPositions = mutableListOf<Pair<AccountPositionDto, BigInteger>>()
                val accountInfo = mutableListOf<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>()

                for (requestItem in requestItems) {
                    val position = allPositions.currentPositions.find {
                        it.accountId == accountId &&
                                it.marketId == requestItem.marketId &&
                                it.outcomeIndex == requestItem.outcomeIndex
                    }

                    if (position == null) {
                        return Result.failure(IllegalArgumentException("仓位不存在: accountId=$accountId, marketId=${requestItem.marketId}, outcomeIndex=${requestItem.outcomeIndex}"))
                    }

                    if (!position.redeemable) {
                        return Result.failure(IllegalStateException("仓位不可赎回: accountId=$accountId, marketId=${requestItem.marketId}, outcomeIndex=${requestItem.outcomeIndex}"))
                    }

                    // 计算 indexSet = 2^outcomeIndex
                    val indexSet = BigInteger.TWO.pow(requestItem.outcomeIndex)
                    accountPositions.add(Pair(position, indexSet))

                    accountInfo.add(
                        com.wrbug.polymarketbot.dto.RedeemedPositionInfo(
                            marketId = position.marketId,
                            side = position.side,
                            outcomeIndex = requestItem.outcomeIndex,
                            quantity = position.quantity,
                            // 预估赎回价值 = 当前价值（赢方 1×数量，输方 0）；实际到账以链上回执为准
                            value = position.currentValue.toSafeBigDecimal().toPlainString()
                        )
                    )
                }

                accountRedeemData[accountId] = accountPositions
                accountRedeemedInfo[accountId] = accountInfo
            }

            // 6. 对每个账户执行赎回（Safe 与 Magic 均支持，Magic 通过 Builder Relayer PROXY Gasless 执行）
            val accountTransactions = mutableListOf<com.wrbug.polymarketbot.dto.AccountRedeemTransaction>()
            var totalRedeemedValue = BigDecimal.ZERO

            for ((accountId, positions) in accountRedeemData) {
                val account = accounts[accountId]!!
                val redeemedInfo = accountRedeemedInfo[accountId]!!

                // 按市场分组（同一市场的仓位可以批量赎回）
                val positionsByMarket = positions.groupBy { it.first.marketId }

                // 获取钱包类型
                val walletTypeEnum = WalletType.fromStringOrDefault(account.walletType, WalletType.SAFE)

                // 解密私钥（只需解密一次）
                val decryptedPrivateKey = decryptPrivateKey(account)

                // 执行赎回（以链上回执为准）
                // negRisk 取自 Data API 仓位自带的 negativeRisk 字段；缺失时 fail-closed，不猜测 adapter
                val marketsWithNegRisk = mutableListOf<Pair<String, Boolean>>()
                for ((marketId, marketPositions) in positionsByMarket) {
                    val negRisk = marketPositions.firstNotNullOfOrNull { it.first.negativeRisk }
                        ?: return Result.failure(IllegalStateException("无法确定市场 $marketId 是否为 Neg Risk 市场（仓位缺少 negativeRisk），已拒绝赎回"))
                    marketsWithNegRisk.add(marketId to negRisk)
                }

                // 同一 condition 有在途赎回时不重复提交
                val redeemKeys = marketsWithNegRisk.map { "${accountId}_${it.first.lowercase()}" }
                val conflict = redeemKeys.firstOrNull { !inFlightRedeems.add(it) }
                if (conflict != null) {
                    redeemKeys.takeWhile { it != conflict }.forEach { inFlightRedeems.remove(it) }
                    return Result.failure(IllegalStateException("REDEEM_IN_PROGRESS: 账户 $accountId 的市场赎回正在处理中，请勿重复提交"))
                }

                var lastTxHash: String? = null
                var accountPayout = BigDecimal.ZERO
                try {
                    // Safe / Deposit Wallet：多个市场合并为一笔（MultiSend / 原生批量）；Magic 不支持批量，逐市场执行
                    val batches = if (walletTypeEnum == WalletType.MAGIC) {
                        marketsWithNegRisk.map { listOf(it) }
                    } else {
                        listOf(marketsWithNegRisk)
                    }
                    for (batch in batches) {
                        val result = blockchainService.redeemMarkets(
                            privateKey = decryptedPrivateKey,
                            proxyAddress = account.proxyAddress,
                            markets = batch,
                            walletType = walletTypeEnum
                        ).getOrElse { e ->
                            logger.error("账户 $accountId 赎回失败: markets=${batch.map { it.first }}, ${e.message}", e)
                            return Result.failure(e)
                        }
                        lastTxHash = result.transactionHash
                        accountPayout = accountPayout.add(result.payout)
                    }
                } finally {
                    redeemKeys.forEach { inFlightRedeems.remove(it) }
                }

                // 以链上到账校验：预期有收益（赢方仓位当前价值 > 0）却到账为 0，视为失败（赎回未生效）
                val expectedValue = positions.fold(BigDecimal.ZERO) { sum, p ->
                    sum.add(p.first.currentValue.toSafeBigDecimal())
                }
                if (expectedValue.gt(BigDecimal("0.01")) && accountPayout.compareTo(BigDecimal.ZERO) == 0) {
                    logger.error("赎回交易已上链但 pUSD 到账为 0（预期约 $expectedValue）: accountId=$accountId, txHash=$lastTxHash")
                    return Result.failure(
                        IllegalStateException("赎回交易已上链但未到账（payout=0，预期约 $expectedValue），请检查: txHash=$lastTxHash")
                    )
                }

                // WCOL 历史余额解包由 WcolUnwrapJobService 处理；adapter 赎回直接得到 pUSD
                totalRedeemedValue = totalRedeemedValue.add(accountPayout)

                // 添加到交易列表
                accountTransactions.add(
                    com.wrbug.polymarketbot.dto.AccountRedeemTransaction(
                        accountId = accountId,
                        accountName = account.accountName,
                        transactionHash = lastTxHash ?: "",
                        positions = redeemedInfo
                    )
                )
            }

            // 7. 发送赎回推送通知（异步，不阻塞）
            notificationScope.launch {
                try {
                    // 获取当前语言设置
                    val locale = try {
                        org.springframework.context.i18n.LocaleContextHolder.getLocale()
                    } catch (e: Exception) {
                        java.util.Locale("zh", "CN")  // 默认简体中文
                    }
                    
                    // 为每个账户发送推送
                    for (transaction in accountTransactions) {
                        val account = accounts[transaction.accountId]
                        if (account != null) {
                            // 查询可用余额
                            val availableBalance = try {
                                blockchainService.getUsdcBalance(account.walletAddress, account.proxyAddress).getOrNull()
                            } catch (e: Exception) {
                                logger.warn("查询可用余额失败: accountId=${account.id}, ${e.message}")
                                null
                            }
                            
                            // 计算该账户的赎回总价值
                            val accountTotalValue = transaction.positions.fold(BigDecimal.ZERO) { sum, info ->
                                sum.add(info.value.toSafeBigDecimal())
                            }
                            
                            // 根据赎回价值选择不同的通知类型
                            if (accountTotalValue.gt(BigDecimal.ZERO)) {
                                // 有收益：发送赎回成功通知
                                telegramNotificationService?.sendRedeemNotification(
                                    accountName = account.accountName,
                                    walletAddress = account.walletAddress,
                                    transactionHash = transaction.transactionHash,
                                    totalRedeemedValue = accountTotalValue.toPlainString(),
                                    positions = transaction.positions,
                                    locale = locale,
                                    availableBalance = availableBalance
                                )
                            } else {
                                // 无收益（输的仓位）：发送已结算无收益通知
                                telegramNotificationService?.sendRedeemNoReturnNotification(
                                    accountName = account.accountName,
                                    walletAddress = account.walletAddress,
                                    transactionHash = transaction.transactionHash,
                                    positions = transaction.positions,
                                    locale = locale,
                                    availableBalance = availableBalance
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("发送赎回推送通知失败: ${e.message}", e)
                }
            }
            
            // 7. 返回结果
            Result.success(
                com.wrbug.polymarketbot.dto.PositionRedeemResponse(
                    transactions = accountTransactions,
                    totalRedeemedValue = totalRedeemedValue.toPlainString(),
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            logger.error("赎回仓位异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 检查账户是否有活跃订单
     * 使用账户的 API Key 查询该账户的活跃订单
     */
    private suspend fun hasActiveOrders(account: Account): Boolean {
        return try {
            // 如果账户没有配置 API 凭证，无法查询活跃订单，允许删除
            if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return false
            }

            // 解密 API 凭证
            val apiKey = account.apiKey
            val apiSecret = decryptApiSecret(account)
            val apiPassphrase = decryptApiPassphrase(account)

            // 创建带认证的 API 客户端（需要钱包地址用于 POLY_ADDRESS 请求头）
            val clobApi = retrofitFactory.createClobApi(apiKey, apiSecret, apiPassphrase, account.walletAddress)

            // 查询活跃订单（只查询第一条，用于判断是否有订单）
            // 使用 next_cursor 参数进行分页，这里只查询第一页
            val response = clobApi.getActiveOrders(
                id = null,
                market = null,
                asset_id = null,
                next_cursor = null  // null 表示从第一页开始
            )

            if (response.isSuccessful && response.body() != null) {
                val ordersResponse = response.body()!!
                val hasOrders = ordersResponse.data.isNotEmpty()
                hasOrders
            } else {
                // 如果查询失败（可能是认证失败或网络问题），记录警告但允许删除
                // 因为无法确定是否有活跃订单，不应该阻止删除操作
                logger.warn("查询活跃订单失败: ${response.code()} ${response.message()}，允许删除账户")
                false
            }
        } catch (e: Exception) {
            // 如果查询异常（网络问题、API 错误等），记录警告但允许删除
            // 因为无法确定是否有活跃订单，不应该阻止删除操作
            logger.warn("检查活跃订单异常: ${e.message}，允许删除账户", e)
            false
        }
    }

    /**
     * 将账户的 USDC.e wrap 为 pUSD
     */
    suspend fun wrapUsdcToPusd(accountId: Long): Result<String?> {
        val account = accountRepository.findById(accountId).orElse(null)
            ?: return Result.failure(IllegalArgumentException("账户不存在"))
        if (account.proxyAddress.isBlank()) {
            return Result.failure(IllegalStateException("账户代理地址不存在"))
        }
        val privateKey = cryptoUtils.decrypt(account.privateKey)
        val walletType = WalletType.fromStringOrDefault(account.walletType, WalletType.SAFE)
        return blockchainService.wrapUsdcToPusd(privateKey, account.proxyAddress, walletType)
    }

    /**
     * 查询 USDC.e 余额（用于迁移提示）
     */
    suspend fun getUsdceBalance(accountId: Long): Result<BigDecimal> {
        val account = accountRepository.findById(accountId).orElse(null)
            ?: return Result.failure(IllegalArgumentException("账户不存在"))
        if (account.proxyAddress.isBlank()) {
            return Result.failure(IllegalStateException("账户代理地址不存在"))
        }
        return blockchainService.queryUsdceBalance(account.proxyAddress)
    }
}

