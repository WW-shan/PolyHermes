# 跟单全链路审查 / 修复 / 实战验证报告（2026-10-08）

> 本文记录一次对「识别 Leader 成交 → 风控过滤 → 定价/签名 → 下单 → 成交回填 → 卖出核销」全链路的系统审查、修复与实战验证结果，供项目迁移后继续跟进使用。
> 迁移相关注意事项见文末「六、迁移注意事项」，未完成事项见「五、还需要做的事」。

## 一、审查范围与方法

| 手段 | 内容 |
| --- | --- |
| 全量测试 | `./gradlew clean test bootJar` → **356 tests / 0 failures / 2 skipped**，jar 构建成功 |
| 真机启动 | 本地 MySQL 8.2 + 后端启动，健康检查 `UP`，Flyway 应用 **49** 个迁移，表结构完整 |
| 实盘只读验证 | 走本机代理连真实 Polymarket（activity WS / data-api / gamma-api / clob / polygon RPC 均可达），抓真实成交报文、真实市场上下文（tick / negRisk / minOrderSize / book） |
| 官方 SDK 比对 | 与 `@polymarket/clob-client-v2` 的 `getOrderRawAmounts` / `ROUNDING_CONFIG` / EIP-712 域 / L2 鉴权实现逐项对齐 |

**代理**：本机访问 Polymarket 需走 `127.0.0.1:10808`（直连超时）；git 也已配置该代理。

## 二、发现并修复的 Bug（按严重度）

**1. 【致命】BUY 订单金额舍入写反 → 实际价格偏离 tick，会被交易所拒单**
`OrderSigningService.calculateOrderAmounts` 的 BUY 分支把 `roundConfig.size`（股数 2 位）和 `roundConfig.amount`（USDC 精度）用反了。官方 `getOrderRawAmounts` 是「股数取 size 位、USDC 取 amount 位」，代码里是「股数取 amount 位、USDC 取 size 位」。
后果举例：买 3.33 股 @0.55，正确 `makerAmount=1.8315`（隐含价 0.55），原代码得 `1.83`（隐含价 **0.5495，不在 0.01 tick 上**）→ 交易所按价格非法拒单，跟单买入整条链路实际不可用。
实盘验证：修复前真实市场签名得 `makerAmount=130000`（隐含 0.026），修复后 `135000`（正好 0.027，落在 tick 上）。同步修复 `CryptoTailManualOrderPricing`（同源错误，否则手动下单会报「签名金额与报价不一致」）。

**2. 【高】去重记录只保留 10 分钟 → 兜底路径回放历史交易时重复下单**
`ProcessedTradeCleanupService` 注释写 1 小时，代码是 `600_000L`。而 activity 与 on-chain 两条路径的跨来源去重**完全依赖 `processed_trade`**，链上 WS 断线重连会回溯最多 1800 区块（≈1 小时）重放历史成交。超过 10 分钟后同一笔成交会被再次下单。已改为 24 小时并加回归测试。

**3. 【高】Activity 消息缺 `transactionHash` 时直接下单，无法与链上路径去重**
原逻辑用 `${leaderId}_${currentTimeMillis()}_...` 生成 fallback id 直接交付，且 `trades`/`orders_matched` 两条重复消息 id 互不相同 → 同一笔成交可能下 2~3 单。现改为丢弃并计数告警（`missingTxHashMessages`，进 `getPerformanceStats()`），交由链上路径兜底（实盘抓包 59/59 条消息均带 txHash，此项为防御性修复）。

**4. 【中】快速地址预过滤对 JSON 空格敏感 → 漏单**
`containsMonitoredAddress` 用 `"proxyWallet":"0x…"` 精确匹配，服务端若输出带空格 JSON 就会把真实成交判为无关消息。已改为去空白后匹配，实盘 59/59 命中。

**5. 【中】UNCONFIRMED 卖单永久占位**
卖单长时间查不到会标记 UNCONFIRMED 并**停止轮询**，但 tracking 的卖出预占不释放 → 持仓永远卖不掉、盈亏错账。新增 `recheckUnconfirmedSellOrders()`：长间隔（10 分钟）复核，终态则核销，未终结则退回 PENDING 常规轮询。

### 改动文件一览

| 文件 | 改动 |
| --- | --- |
| `backend/src/main/kotlin/.../service/copytrading/orders/OrderSigningService.kt` | 修复 BUY 分支 size/amount 舍入方向 |
| `backend/src/main/kotlin/.../service/system/ProcessedTradeCleanupService.kt` | 去重记录保留 10 分钟 → 24 小时 |
| `backend/src/main/kotlin/.../service/copytrading/monitor/PolymarketActivityWsService.kt` | 缺 txHash 丢弃并计数；tradeId 统一 `OnChainWsUtils.buildTradeId`；地址预过滤去空白 |
| `backend/src/main/kotlin/.../service/copytrading/statistics/OrderStatusUpdateService.kt` | 新增 UNCONFIRMED 卖出复核 |
| `backend/src/main/kotlin/.../service/cryptotail/CryptoTailManualOrderPricing.kt` | 金额精度按 tick 取 3/4/5/6 位，与签名器一致 |
| `backend/src/main/kotlin/.../service/cryptotail/CryptoTailStrategyExecutionService.kt` | 展示金额 `stripTrailingZeros` |

## 三、新增/修改的测试（可复现）

- `CopyTradingFullChainE2ETest`（新增）：识别（activity WS 报文）→ 过滤/风控 → 限价与数量 → 真实 EIP-712 签名 → 下单 → 成交回填真实均价 → Leader 卖出 → FIFO 核销与盈亏；另含**跨来源去重（双路推送只下 1 单）**和**缺 txHash 不下单**两个回归。
- `LiveActivityWsSmokeTest`（新增，默认 assume-skip）：连真实 Polymarket，验证订阅协议、报文解析、txHash、快速过滤、真实市场上下文解析与真实签名。
- `ProcessedTradeCleanupRetentionTest`（新增）：保留窗口回归。
- `OrderSigningServiceTickSizeTest`（修改）：改为与官方 SDK 数值对齐 + 隐含价格断言。
- `OrderStatusUpdateServiceTest`（修改）：+2 个 UNCONFIRMED 卖出复核用例。
- `CryptoTailManualOrderPricingTest`（修改）：金额精度断言更新。

```bash
# 全量测试
cd backend && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew clean test bootJar

# 实盘冒烟（需代理）
POLYHERMES_LIVE_TEST=1 POLYHERMES_PROXY_HOST=127.0.0.1 POLYHERMES_PROXY_PORT=10808 \
  ./gradlew test --tests '*LiveActivityWsSmokeTest*'
```

## 四、结论：能不能真正完成

修完 #1 后，从识别到下单的每一环都有真实数据背书：识别（真实 WS）、市场上下文（真实 book/tick/negRisk）、签名（与官方 SDK 向量一致、隐含价正好在 tick 上）、下单重试与幂等（同一 hash 不重复成交）、成交回填与卖出核销。**修复前 BUY 单大概率被拒，链路不能真正完成；现在可以。**

## 五、还需要做的事（TODO，未改代码）

1. **轮询兜底未实现（重要）**：`pollIntervalSeconds` / `useWebSocket` / `copy.trading.polling.*` 目前是**死配置**，没有轮询兜底实现。两条 WS（activity + on-chain）同时不可用时，跟单会**静默停止**且不告警。建议实现 Data API 轮询兜底，或在后端加健康告警、前端隐藏无效配置项。
2. **真实小额账户端到端实盘验证**：本次只做到「签名 + 市场上下文」级别的真实数据验证，**未真实提交订单**。上线前需用 proxy 已授权 exchange 且有余额的账户跑一笔真实小额端到端下单，确认成交与回填。
3. **成交价回退可优化**：`queryExecutionPrice` 失败时仍回退到订单限价（已有 WARN 日志），PnL 可能偏差。可考虑失败时不发通知 / 持续重试直至拿到真实均价。
4. **多实例部署注意**：`tradeLocks` / `configLocks` / `processedTxHashes` 等为进程内状态，跨实例去重依赖 DB 唯一键；迁移/扩容时需确认唯一键覆盖到位。
5. **旧文档口径不一致（可选）**：`docs/zh/copy-trading-monitor-strategy.md` 等旧文档仍写 `leaderTradeId = transactionHash`，与新 `buildTradeId` 格式（`normalizedTxHash:B|S:tokenId后16位`）不一致，可一并更新。

## 六、迁移注意事项

- **JDK**：需 Java 17；本机路径 `/opt/homebrew/opt/openjdk@17`（`JAVA_HOME=/opt/homebrew/opt/openjdk@17`，系统未注册 `java`）。
- **代理**：访问 Polymarket 需 `POLYHERMES_PROXY_HOST=127.0.0.1` / `POLYHERMES_PROXY_PORT=10808`；git 亦配置该 http(s) 代理。
- **数据库**：MySQL 8.2，Flyway 自动迁移（`spring.flyway.enabled=true`，当前 **49** 个迁移）。
- **关键环境变量**：`DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `SERVER_PORT` / `JWT_SECRET` / `ENCRYPTION_KEY` / `ADMIN_RESET_PASSWORD_KEY`（详见 `docs/zh/DEPLOYMENT*.md` 与 `backend/src/main/resources/application.properties`）。
- **构建/验证命令**：`cd backend && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew clean test bootJar`。
- **迁移后自检**：启动后确认 `/actuator/health` 为 `UP`、Flyway 迁移全部 success、日志无「缺失 transactionHash」持续刷屏。

## 七、参考事实（本次比对记录）

- 官方 SDK：`@polymarket/clob-client-v2@1.2.0`
  - `ORDER_TYPE_STRING = "Order(uint256 salt,address maker,address signer,uint256 tokenId,uint256 makerAmount,uint256 takerAmount,uint8 side,uint8 signatureType,uint256 timestamp,bytes32 metadata,bytes32 builder)"`
  - EIP-712 域：name `Polymarket CTF Exchange`、version `2`、chainId `137`
  - `ROUNDING_CONFIG`：tick 0.1 → price1/size2/amount3；0.01 → 2/2/4；0.001 → 3/2/5；0.0001 → 4/2/6
  - L2 鉴权 `createL2Headers` 用 `encodedPath`（不含 query），HMAC secret base64 解码后 URL-safe base64 输出
  - `clob-v2.polymarket.com` 当前不可达；生产仍用 `clob.polymarket.com`
- 实盘抓包：activity WS 每条消息都带 `transactionHash`（59/59）；真实市场 tick=0.001、negRisk=false、minOrderSize=5；签名 hash 长度 66。

---

_报告日期：2026-10-08_
