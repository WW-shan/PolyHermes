# Event Maker 运行手册与故障处理

> 更新时间：2026-10-20 00:00 +08:00<br>
> 适用代码：`backend/src/main/kotlin/com/wrbug/polymarketbot/service/eventmaker/`  
> 计划与验收：[event-maker-delivery-plan.md](event-maker-delivery-plan.md)  
> 工作日志：[event-maker-work-log.md](event-maker-work-log.md)

## 1. 当前能力边界

> 当前验证状态（2026-10-20 02:05）：正式候选通过 12h–7d 八窗口回测；24h 为 93 cycles、0 结算亏损、ROI +1.052%，但尾部风险上界仍不足。全量 103 suites / 555 tests 通过。日亏损使用持久化 UTC 锚点，同日重启不重置、跨日重锚、时钟回拨不重置；无买盘持仓按最坏估值。对账/初始 tick 的未知状态和日亏损会主动关闭事件通道并进入统一清理。v3 Shadow 严格门槛下 0 选中后因本轮修复停止；v4 Shadow 在后续初始清理边界审计后停止；v5 已选中市场但被 adverse-flow Guard 阻止，实验臂暴露审计误分类；修复后 v6 已完成六次挂单：shadow-1 至 shadow-6 均因 requote 撤销；最后一笔 shadow-6 于 02:56 撤销。当前 FLAT、0 fill、0 cycle，继续等待价格与真实 crossing flow。当前仍不应实盘、不应跟单。证据见 [候选报告](event-maker-safe-candidate-2026-10-19.md)、[Live 执行验证](event-maker-live-execution-validation-2026-10-19.md) 和 [执行台账](event-maker-execution-ledger-2026-10-19.md)。

- `SHADOW`：使用真实 Polymarket 公共盘口和公共成交，不签名、不下单、不接触资金。
- `LIVE`：使用真实账户、真实 CLOB 订单，强制 `post-only + GTC`，没有 taker fallback。
- `LIVE` 默认关闭；必须显式设置 `liveTradingEnabled=true` 且提供 `accountId`。
- 历史回测不能证明当前配置有稳定 edge。不要把测试通过等同于可以盈利上线。

## 2. 环境要求

- JDK 17。
- 在 `D:\Project\PolyHermes\backend` 下执行 Gradle 命令。
- 独立 Shadow 不需要 MySQL、不需要 Docker。
- Spring REST 服务仍需要项目原有的 MySQL 和配置（本机可用 Docker 起 `mysql:8.2`，库 `polyhermes`，端口 3306）。
- 如果 Java 直连 Polymarket 超时，可使用本机 HTTP 代理，例如 `http://127.0.0.1:10808`。
- Spring 路径未显式配置 `event-maker.proxy-url` 时，会回退到数据库里已启用的 HTTP 系统代理；显式配置优先。

## 3. 运行测试

```powershell
cd D:\Project\PolyHermes\backend
.\gradlew.bat test --tests "com.wrbug.polymarketbot.service.eventmaker.*" --console=plain
.\gradlew.bat test --console=plain
```

验收标准：

- event-maker 定向测试 0 失败；
- 全量测试 0 失败；
- 不允许用“编译通过”代替订单状态机、执行守卫和 mock 流程测试。

### 3.1 历史研究数据缓存与参数邻域

完整池抓取一次后可以复用压缩数据集，避免参数网格重复请求 API：

```powershell
cd D:\Project\PolyHermes\backend

.\gradlew.bat eventMakerBacktest `
  "-PresearchProxy=http://127.0.0.1:10808" `
  "-PresearchDataset=build/research/event-maker-24h.json.gz" `
  "-PresearchMaxMarkets=5000" `
  "-PresearchMinVolume=100000" `
  "-PresearchFrom=2024-01-01" `
  "-PresearchTo=2026-10-01" `
  "-PresearchEntryHorizonSeconds=86400" `
  "-PresearchFlowGuardWindowSeconds=3600" `
  "-PresearchFlowGuardMaxSignedFlow=-0.4" `
  "-PresearchFlowGuardMinPriceDropCents=0.5" `
  "-PresearchOutput=build/research/event-maker-grid" `
  --console=plain
```

- 数据集是 gzip JSON，写入采用临时文件 + 原子替换；损坏或不完整的数据不会覆盖旧缓存。
- 缓存必须匹配 `from/to/minVolume/maxMarkets`，且数据集的成交窗口不能短于本次 `entryHorizonSeconds`；更长窗口的数据集可安全重放更短窗口。
- 需要强制重新抓取时增加 `"-PresearchRefreshDataset=true"`。
- Gamma 发现或任一市场成交抓取失败会中止整次运行，不再静默跳过月份或写成空成交。
- 未传 `-PresearchProxy` 时研究客户端明确使用直连；如果本机 DNS 被污染导致直连超时，显式传代理。
- `"-PresearchGridProfile=candidate-neighborhood"` 会运行候选价格带、相邻价格带、0.75–2.0c 退出价差和 q10–q50 的 1,008 配置邻域。
- `"-PresearchFocusConfig=<config>"` 会额外输出该配置的完整 markets、cycles 和费用敏感性。
- `"-PresearchMaxOrderAgeSeconds=1800"` 必须与 Shadow 的 `shadowMaxOrderAgeSeconds` 一致；回测会在成交前撤销过期订单。

## 4. 独立 Shadow 长测

推荐用封装脚本启动正式候选（只读、不会下真实订单）：

```powershell
.\scripts\start-event-maker-shadow.ps1 `
  -OutputDir "backend/build/research/event-maker-safe-candidate-shadow-main-20261019" `
  -Spread 2 `
  -MaxMarkets 18 `
  -WaitMinutes 720 `
  -Minutes 720 `
  -MinRemainingMinutes 720 `
  -MaxRemainingMinutes 10080 `
  -MaxCyclesPerMarket 1 `
  -FillVolumeFactor 20 `
  -EntryOffsetCents 0 `
  -ExitSpreadCents 1
```

等价的底层 Gradle 命令：

```powershell
cd D:\Project\PolyHermes\backend

.\gradlew.bat eventMakerShadow `
  "-PshadowProxy=http://127.0.0.1:10808" `
  "-PshadowMinutes=720" `
  "-PshadowMaxMarkets=18" `
  "-PshadowDiscoveryPages=50" `
  "-PshadowWaitForMarketMinutes=720" `
  "-PshadowDiscoveryRetrySeconds=60" `
  "-PshadowMinVolume=100000" `
  "-PshadowMinPrice=0.97" `
  "-PshadowMaxPrice=0.99" `
  "-PshadowMinRecentTrades=3" `
  "-PshadowMinRecentNotional=50" `
  "-PshadowMinRecentTradesPerSide=1" `
  "-PshadowMinRecentTrades2h=20" `
  "-PshadowMinRecentNotional2h=500" `
  "-PshadowMinRecentTradesPerSide2h=3" `
  "-PshadowMinRemainingMinutes=720" `
  "-PshadowMaxRemainingMinutes=10080" `
  "-PshadowAllowNegRisk=true" `
  "-PshadowMaxQueue=100000" `
  "-PshadowSnapshotSeconds=10" `
  "-PshadowMinSpreadTicks=2" `
  "-PshadowImproveTicks=0" `
  "-PshadowEntryOffsetCents=0" `
  "-PshadowExitImproveTicks=0" `
  "-PshadowExitSpreadCents=1" `
  "-PshadowRequoteSeconds=300" `
  "-PshadowMaxOrderAgeSeconds=1800" `
  "-PshadowStaleBookSeconds=300" `
  "-PshadowActiveOrderStaleSeconds=1800" `
  "-PshadowFlowGuardWindowSeconds=3600" `
  "-PshadowFlowGuardMaxSignedFlow=-0.4" `
  "-PshadowFlowGuardMinPriceDropCents=0.5" `
  "-PshadowOrderNotional=25" `
  "-PshadowFillVolumeFactor=20" `
  "-PshadowMaxCyclesPerMarket=1" `
  "-PshadowOutput=build/research/event-maker-safe-candidate-shadow" `
  --console=plain
```

参数说明：

| 参数 | 含义 |
|---|---|
| `shadowProxy` | Java 访问 Polymarket 的 HTTP 代理；直连可用时省略 |
| `shadowMinutes` | 运行分钟数 |
| `shadowMaxMarkets` | 最多市场数 |
| `shadowDiscoveryPages` | Gamma keyset 每标签最多扫描页数（1～100，默认 50；每页 100） |
| `shadowWaitForMarketMinutes` | 等待合格市场的分钟数；0 表示只检查一次，>0 时按重试间隔持续重新发现 |
| `shadowDiscoveryRetrySeconds` | 无合格市场时的重新发现间隔秒数 |
| `shadowMinVolume` | 24 小时最低成交额；因 keyset 按 volume24hr 降序，低于该值的后续页面自动停止 |
| `shadowMinRecentTrades` | 近 30 分钟最少成交笔数 |
| `shadowMinRecentNotional` | 近 30 分钟最少成交额 |
| `shadowMinRecentTradesPerSide` | 近 30 分钟买卖两侧各自最少成交笔数 |
| `shadowMinRecentTrades2h` | 近 2 小时最少成交笔数 |
| `shadowMinRecentNotional2h` | 近 2 小时最少成交额 |
| `shadowMinRecentTradesPerSide2h` | 近 2 小时买卖两侧各自最少成交笔数 |
| `shadowMinPrice` / `shadowMaxPrice` | favorite 买一价格带 |
| `shadowMinRemainingMinutes` | 距离结束至少剩余分钟数；正式候选默认 720（12h） |
| `shadowMaxRemainingMinutes` | 距离结束最多剩余分钟数；正式候选默认 10080（7d），与回测窗口范围一致 |
| `shadowAllowNegRisk` | 是否允许 negRisk 市场 |
| `shadowMaxQueue` | 前方最大可接受队列股数；过小会导致一档价差市场完全不报价 |
| `shadowSnapshotSeconds` | 快照间隔 |
| `shadowMinSpreadTicks` | 报价时要求的最小买卖价差 tick 数 |
| `shadowImproveTicks` | 买入价相对买一改善的 tick 数；0 表示不改善 |
| `shadowEntryOffsetCents` | 买入价相对买一下移的美分数；1 表示等待 1 美分 dip |
| `shadowExitImproveTicks` | 卖出价相对卖一的改善 tick 数；0 表示按目标价挂单 |
| `shadowExitSpreadCents` | 卖出目标价相对买入价的价差美分数 |
| `shadowRequoteSeconds` | 价格变化后的最小重挂冷却时间 |
| `shadowMaxOrderAgeSeconds` | 订单最大存活时间，到时强制重挂 |
| `shadowStaleBookSeconds` | 新报价允许的最大盘口年龄 |
| `shadowActiveOrderStaleSeconds` | 已有挂单允许的最大盘口静默时间 |
| `shadowFlowGuardWindowSeconds` | 逆势流 Guard 滚动窗口；0 关闭，默认 3600 |
| `shadowFlowGuardMaxSignedFlow` | 触发 Guard 的最大 signed flow，默认 -0.4 |
| `shadowFlowGuardMinPriceDropCents` | 触发 Guard 的最小价格跌幅（美分），默认 0.5 |
| `shadowOrderNotional` | 单市场订单名义金额 |
| `shadowMaxCyclesPerMarket` | 每个市场最多完成的回合数；正式候选为 1 |
| `shadowFillVolumeFactor` | 队列清空后，累计需要多少倍订单股数的反向成交才成交；0 为旧乐观模式，1 为保守基准，正式候选为 20 |
| `shadowOutput` | 输出目录 |

输出文件：

- `markets.json`：本次最终用于 Shadow 的市场。
- `market-discovery.json`：最新一轮完整发现结果，包括预筛候选、活动过滤逐候选诊断、30m/2h 成交统计、拒绝原因和分页计数。
- `market-selection.json`：最新一轮每个候选的价差、买一/卖一队列、入场/退出队列及拒绝原因。
- `discovery-attempts.jsonl`：等待模式的每一轮尝试、候选/活动/选择数量和停止原因。
- `market-watch-timeout.json`：等待超时证据，包含尝试次数和最新拒绝原因。
- `events.jsonl`：报价、撤单、有效成交对账和 WS 状态。
- `trades.csv`：去重后的公共成交。
- `snapshots.csv`：每个市场每 10 秒的状态、库存、PnL 和回合数。
- `state.json`：逐笔 fill、逐回合 cycle、SETTLE settlement、赎回状态、库存和活跃订单。
- `markouts.csv`：每笔 Shadow 成交后 60/300/1800 秒的盘口 markout，同时记录盘口年龄。
- `execution-diagnostics.json`：订单级队列、反向成交、穿越成交、已消耗队列、成交量和最终状态。
- `summary.json`：最终汇总和错误。

长测后必须检查：

0. `market-discovery.json` 的 `stoppedReason` 不能是无解释的 `page_cap_reached`；若为 `volume_floor_reached`，确认扫描已覆盖最低成交额门槛以上市场；
1. `summary.json` 中 `haltedReason` 为空；
2. `lastErrors` 不包含未知订单状态；
3. `events.jsonl` 中同一订单没有重复成交事件；
4. `state.json` 的 `fills` 与 `cycles` 能对应；
5. 所有已完成 cycle 的 PnL 可由买卖价差复算；
6. `trades.csv` 的 transaction hash 没有重复计数；
7. `execution-diagnostics.json` 明确记录 `fillVolumeFactor`、穿越成交和队列消耗；
8. `markouts.csv` 的盘口年龄不能长期超过 `shadowActiveOrderStaleSeconds`。
9. Shadow 退出后 `execution-diagnostics.json` 的 `activeOrders` 必须为 0，最终快照必须回到 FLAT。

### 4.1 检查后台等待任务

```powershell
cd D:\Project\PolyHermes
.\scripts\check-event-maker-watch.ps1
```

脚本输出：

- `wrapperAlive` / `shadowAlive`：包装进程和 JavaExec 是否存活；
- `logIssues` / `stderrBytes`：扫描 stdout/stderr 中的 API 失败、HTTP 429、异常和 OOM 信号；
- `attempts`：已完成发现轮数；
- `lastAttempt`：最近一轮候选、活动过滤、选择数和停止原因；
- `state=waiting`：进程存活，但尚未写出 `state.json`；
- `state=discovering`：`state.json` 已建立但尚未选中市场；
- `state=running`：已选中市场，显示 markets、activeOrders、fills、cycles、inventoryShares 和 phases；
- `state=summary`：Shadow 已结束，同时显示 markets/fills/cycles/totalPnl；
- `state=timeout`：等待超时，显示尝试次数和最后拒绝原因。

禁止在等待或 Shadow 运行期间执行编译或测试；需要改代码时先停止 PID，并记录停止原因。
### 4.2 Shadow 结束后审计

```powershell
cd D:\Project\PolyHermes
.\scripts\audit-event-maker-shadow.ps1 `
  -OutputDir "D:\Project\PolyHermes\backend\build\research\event-maker-shadow-watch-v2-20261019"
```

审计会检查：

- `summary.fills` 与 `state.json` 的逐市场 fills 总数一致；
- `summary.completedCycles` 与 state cycles 总数一致；
- `execution-diagnostics.json` 的 `activeOrders=0`；
- `haltedReason` 为空，且 `lastErrors` 中没有非预期异常；
- `trades.csv` 中同一 transaction hash/token/side/size 没有重复行；
- `events.jsonl` 的 placed/canceled 数量与 summary 的 quotesPlaced/cancels 一致；
- 每个已挂订单都出现在 `execution-diagnostics.json`，且不存在重复 orderId；
- 有 fill 时 markout 是否写出；
- 未平库存作为 warning 报告；
- `events.jsonl` 缺失或为空只作为 warning，兼容旧运行产物；
- `risk_blocked:*` 和 `flow_guard_blocked:*` 是预期防守事件，不应作为 fatal `lastErrors`；Guard 阻止会记录在 engine event 的 `blocked` 字段。

结果写入同目录 `shadow-audit.json`；`failures` 非空时脚本非零退出。
## 5. Spring REST 控制

启动 Shadow：

```http
POST /api/event-maker/start
Content-Type: application/json

{
  "mode": "SHADOW",
  "maxMarkets": 18,
  "minVolume24hr": 100000,
  "minRecentTrades30m": 3,
  "minRecentNotional30m": 50,
  "minRecentTradesPerSide30m": 1,
  "minRecentTrades2h": 20,
  "minRecentNotional2h": 500,
  "minRecentTradesPerSide2h": 3,
  "minRemainingMinutes": 720,
  "maxRemainingMinutes": 10080,
  "allowNegRisk": true,
  "minPrice": 0.97,
  "maxPrice": 0.99,
  "improveTicks": 0,
  "entryOffsetCents": 0.0,
  "exitSpreadCents": 1.0,
  "exitImproveTicks": 0,
  "requoteSeconds": 300,
  "maxOrderAgeSeconds": 1800,
  "flowGuardWindowSeconds": 3600,
  "flowGuardMaxSignedFlow": -0.4,
  "flowGuardMinPriceDropCents": 0.5,
  "orderNotional": 25.0,
  "maxQueueAheadShares": 100000.0,
  "shadowFillVolumeFactor": 20.0,
  "minSpreadTicks": 2,
  "maxTotalInventoryNotional": 100.0,
  "maxDailyLoss": 5.0,
  "maxCyclesPerMarket": 1,
  "staleBookSeconds": 300,
  "activeOrderStaleBookSeconds": 1800,
  "liveTradingEnabled": false
}
```

查询状态：

```http
GET /api/event-maker/status
```

停止：

```http
POST /api/event-maker/stop
```

停止路径会：

- 先关闭 WebSocket 与数据客户端并取消在途 OkHttp 调用（避免卡在 60 秒超时里）；
- 停止新报价；
- 尝试撤销所有已知活跃订单；
- 仅在撤单成功后清除订单状态；
- 撤单失败时保留持久化订单并写入 `lastError`；
- 将状态写入 `event-maker.state-file`。

## 6. Live 启动前检查

以下任一条件不满足，LIVE 必须拒绝启动：

- `liveTradingEnabled=true`；
- `accountId` 存在；
- API Key / Secret / Passphrase 可解密；
- 私钥可解密；
- 代理钱包已部署；
- tick size 和 negRisk 查询成功；
- `/book` 返回可用的 `min_order_size`；
- 买入时 USDC 余额和授权额度足够；
- 卖出时 CTF 库存和 exchange 授权足够；
- 订单价格在 tick 上，数量不低于最小订单量；
- 所有订单 `postOnly=true`、`orderType=GTC`；
- 状态文件可解析且版本受支持（当前 schema v4）；
- 状态文件账户与本次 `accountId` 一致；
- 状态文件包含 UTC 日亏损锚点 `dailyPnlDay` / `dailyPnlBaseline`，且 baseline 为有限值；
- 状态文件包含 `conditionId` 和 `favoriteTokenId`；
- 账户全部活跃订单都在状态文件中有登记；
- 账户全部正数持仓的 conditionId 和数量与状态库存完全一致；
- 同一 condition 不得出现多个正数持仓资产。

推荐使用专用账户。复制交易或其他策略留下的订单/持仓会让 Event Maker 拒绝启动，这是刻意 fail-closed。以下是“单市场最小 smoke”示例，不是可直接放宽参数的生产配置；必须由用户提供 `accountId`、资金上限、时间窗口、市场范围并明确授权后再执行：

```json
{
  "mode": "LIVE",
  "accountId": 1,
  "liveTradingEnabled": true,
  "maxMarkets": 1,
  "minVolume24hr": 100000,
  "minRecentTrades30m": 3,
  "minRecentNotional30m": 50,
  "minRecentTradesPerSide30m": 1,
  "minRecentTrades2h": 20,
  "minRecentNotional2h": 500,
  "minRecentTradesPerSide2h": 3,
  "minRemainingMinutes": 720,
  "maxRemainingMinutes": 10080,
  "allowNegRisk": true,
  "minPrice": 0.97,
  "maxPrice": 0.99,
  "improveTicks": 0,
  "entryOffsetCents": 0.0,
  "exitSpreadCents": 1.0,
  "exitImproveTicks": 0,
  "requoteSeconds": 300,
  "maxOrderAgeSeconds": 1800,
  "flowGuardWindowSeconds": 3600,
  "flowGuardMaxSignedFlow": -0.4,
  "flowGuardMinPriceDropCents": 0.5,
  "orderNotional": 25.0,
  "maxQueueAheadShares": 100000.0,
  "shadowFillVolumeFactor": 20.0,
  "minSpreadTicks": 2,
  "maxTotalInventoryNotional": 25.0,
  "maxDailyLoss": 5.0,
  "maxCyclesPerMarket": 1,
  "reconcileSeconds": 2,
  "staleBookSeconds": 300,
  "activeOrderStaleBookSeconds": 1800
}
```

## 7. 状态文件

默认路径：

```text
backend/data/event-maker-state.json
```

可通过环境变量覆盖：

```powershell
$env:EVENT_MAKER_STATE_FILE = "D:\Project\PolyHermes\backend\data\event-maker-state.json"
```

状态文件包含：

- `conditionId`、`favoriteTokenId`；
- 活跃订单 ID、方向、价格、原始数量、已成交数量、状态、创建时间；
- 当前库存、入场价、入场时间；
- 状态级 `cumulativeRealizedPnl`、当前市场已实现 PnL 和已完成回合；
- 日亏损锚点 `dailyPnlDay`（UTC epoch day）和 `dailyPnlBaseline`（该日开始的累计已实现 PnL）；
- 逐笔 fill、逐回合 cycle、SETTLE settlement 和赎回状态；
- 当前入场订单 ID 和份额。

状态文件不包含私钥或 API 凭证。写入使用临时文件 + 原子替换。当前 schema 为 v4；读取时会拒绝 v2/v3/未知版本、损坏 JSON、错账户状态、缺失日亏损锚点、缺失 favorite token、重复 condition 和非法订单快照。恢复时会重放 fills + settlements，核对库存、已实现 PnL、完成回合数、cycle 记录和赎回记录。

## 8. 故障处理

### 8.1 下单结果不确定

触发条件：`createOrder` 网络异常、HTTP 5xx、408、429。

行为：立即 fail-closed，停止新报价。

处理：

1. 查询账户活跃订单和成交记录；
2. 不要在确认订单状态前重启 LIVE；
3. 必要时手动撤单；
4. 记录订单 ID、账户和请求时间。

### 8.2 订单 404 / 状态未知

触发条件：`getOrder` 404、返回非法快照、未知状态、连续 3 次查询失败。

行为：立即 fail-closed，不再报价。

处理：

1. 到 Polymarket 页面或 CLOB API 核对订单和成交；
2. 检查是否已经成交但订单查询不可见；
3. 不要仅因进程停止就认为订单不存在；
4. 确认库存和余额后，清理状态文件或修正状态。

### 8.3 部分成交后撤单失败

触发条件：订单已有 `matchedShares > 0`，撤销剩余数量失败。

行为：立即 fail-closed，保留活跃订单和库存。

处理：

1. 手动撤销剩余订单；
2. 核对实际成交份额；
3. 确认状态文件中的 `activeOrder*` 字段；
4. 未完成对账前不要重启 LIVE。

### 8.4 日亏损触发

行为：当“今日已实现 PnL + 当前浮亏”达到 `-maxDailyLoss` 时停止报价，并在退出清理中尝试撤销所有活跃订单。

日界使用 UTC。`dailyPnlDay` 和 `dailyPnlBaseline` 会持久化：同一 UTC 日内重启继续扣减当天已经发生的亏损，只有跨 UTC 日才把基线重锚到当时的累计已实现 PnL。缺失或非有限锚点会让状态校验失败。

处理：

1. 检查 `summary` 或 `lastError=daily_loss_limit`；
2. 核对 UTC 日锚点、realized/unrealized PnL；
3. 不调高上限来掩盖异常；
4. 复盘后再决定是否重启。

### 8.5 启动时发现孤儿订单或孤儿持仓

触发条件：持久化状态中的市场不在本次发现列表。

行为：

- 有活跃订单：先撤销，再读取最终订单快照；
- 确认终态且 matchedShares=0 才允许丢弃该订单；
- 有持仓、撤单失败、出现成交或无法确认终态：拒绝启动。

处理：手动核对并处理持仓；未对账前不要删除状态文件。

### 8.6 账户级状态不匹配

触发条件：账户存在未登记活跃订单、未登记正数持仓、状态库存数量与链上不一致，或同一 condition 有多个正数资产。

行为：LIVE 拒绝启动。

处理：

1. 确认是否使用了专用账户；
2. 手动撤销无关订单或转出/赎回无关持仓；
3. 核对 `event-maker-state.json` 与账户实际状态；
4. 不允许通过删除状态文件绕过未平仓持仓。

### 8.7 停止时最终快照无法确认

触发条件：撤单后的 `getOrder` 失败、订单仍活跃、快照字段不一致或部分成交记账失败。

行为：保留 `activeOrder*` 和库存，写入错误，退出 LIVE。

处理：

1. 在 CLOB 查询订单终态和 matchedShares；
2. 核对 fills/inventory；
3. 未完成对账前不要重启 LIVE；
4. 下次启动的账户级订单/持仓检查会继续阻止不一致状态。

### 8.8 WebSocket 断线

`LivePaperWsClient` 会自动重连。订单状态轮询不依赖 WebSocket。如果断线期间出现订单状态异常，按 8.2 处理。

### 8.9 状态 schema 版本升级

schema v3 新增日亏损锚点；schema v4 新增 settlement 账本与赎回状态。v2/v3 状态会被明确拒绝，避免升级或重启时无依据地遗忘当天亏损或丢失结算记录。处理方式：

1. 先停止 LIVE，查询账户活跃订单和正数持仓；
2. 核对 `event-maker-state.json` 的 `cumulativeRealizedPnl`、fills 和 cycles；
3. 若有未平订单/持仓，先完成人工对账，不能直接删状态；
4. 只有在确认账户无订单、无持仓且旧状态无需保留后，才归档旧状态并重新启动；
5. 不得把“版本不支持”当成自动清仓或重置日亏损预算的许可。

## 9. 实盘上线门槛

除了账户、资金和用户授权外，策略本身还必须同时满足：

- Shadow 使用 `shadowFillVolumeFactor >= 1`，不能再用 0 的乐观模式；
- 至少 20 个真实盘口完成的 cycle，且 fill、队列、markout 和 PnL 能逐笔复算；
- 60/300/1800 秒 markout 没有系统性恶化；
- 结算亏损率和单次最大亏损在资金上限内；
- 50bps 双边费用压力下仍有正期望，或能证明真实费用确实接近零；
- 队列因子 10 下仍有可解释成交，不把深队列 adverse selection 当成 alpha；
- mock 端到端测试全部通过；
- 用户明确授权并准备极小资金；
- 先用最小订单量做单市场 smoke；
- 记录订单 ID、成交、余额、库存和撤单结果。

任何一项不满足，只能运行 SHADOW，不能运行 LIVE。

## 10. 当前已知限制

- 完整池 4,187 市场、864 组配置的最强训练期选参 ROI 约 +0.81%，测试 +0.56%，不足以覆盖不确定性。
- 队列因子从 1 提到 50，同族配置 ROI 从约 +0.92% 降到 -0.77%，深队列会带来 adverse selection。
- 50bps 双边 maker fee 下，训练期选参配置总 ROI 为 -0.18%，2 美分价差不足以承受该费用。
- 已实现回测最大回撤约 146.49 USDC；单次结算亏损 -100 USDC，约等于 52 次平均盈利。
- Cluster bootstrap 和自然月聚类区间均包含 0，当前约 +0.5%～+1% 的 ROI 不能视为统计显著。
- `maxDailyLoss` 按 UTC 日统计并持久化锚点，同日重启不会重置；仍需真实小资金 smoke 验证账户级实现与 UTC 日界是否符合实际运营口径。
- 账户级启动对账要求使用专用账户；外部订单或持仓会阻止 LIVE 启动。
- 尚未使用真实账户执行小资金 LIVE smoke。
- 历史回测使用的是成交打印和队列近似，不是完整 L2 队列重建。
- 公共成交不能完全代表真实队列位置，Shadow 的 fill 只能作为上界/近似。
- 流动性奖励、maker rebate、真实 fee 和撤单竞争尚未完整纳入生产 PnL。
- 当前没有自动新闻/事件流反应，只使用盘口和公共成交。
- 真实盘口 Shadow 截至 2026-10-19 05:22 仍为 0 fill，不能据此判断策略可成交。

## 11. 结算与赎回闭环

市场没有产生 SELL 而直接进入结算时，Live 流程如下：

1. LONG 仓位每 30 秒接受一次 Gamma 终局检查；
2. 只有 Gamma 返回终局 0/1 且持仓身份核对通过后，才写入 `SETTLE` cycle；
3. 先把 `PENDING` settlement 持久化到 state v4；
4. 通过 Builder Relayer 赎回，成功后写入 `REDEEMED` 和交易哈希；
5. 若链上已无对应持仓，写入 `ALREADY_ABSENT`，不重复赎回；
6. 重启时自动恢复 pending settlement；discovery 不再返回的孤儿持仓使用持久化 market 元数据进入仅结算模式。

部分卖出后结算的账本语义：`settlement.shares` 只表示链上可赎回的剩余股数；`cycle.shares` 表示完整周期总买入股数；`cycle.pnl` 必须同时包含之前部分卖出的已实现 PnL 和剩余仓位的结算 PnL。审计或恢复发现三者不守恒时必须 fail-closed。

Live 启动新增 Builder API Key 前置检查。未配置时直接拒绝，不允许先建仓。

当前 schema 为 v4。v2/v3/未知版本会被明确拒绝；上线前必须核对 `settlements`、`cycles`、`fills` 和账户实际持仓。

## 12. Shadow 成交缺口复核

Shadow 结束后除了 `audit-event-maker-shadow.ps1`，可单独复核订单为什么成交或未成交：

```powershell
.\scripts\analyze-event-maker-shadow-fills.ps1 `
  -OutputDir "backend/build/research/event-maker-safe-candidate-shadow-main-v6-20261020" `
  -WriteReport
```

脚本会从 `events.jsonl` 重建订单存活窗口，从 `trades.csv` 统计反向可穿越成交，计算：

- 淘汰 queue 后可成交股数 `eligibleShares`；
- q20 要求的确认股数 `requiredShares`；
- 模型是否应成交 `modelFill`；
- state 中的实际成交股数。

`audit-event-maker-shadow.ps1` 已自动执行该复核，并重放 `SETTLE` cycle；若模型应成交但 state 无成交，或 settlement/cycle 股数与 PnL 不守恒，审计失败。若模型和实际均未成交，则 0 fill 是可解释结果，不得据此放宽门槛。