# Event Maker 运行手册与故障处理

> 更新时间：2026-10-19 +08:00  
> 适用代码：`backend/src/main/kotlin/com/wrbug/polymarketbot/service/eventmaker/`  
> 计划与验收：[event-maker-delivery-plan.md](event-maker-delivery-plan.md)  
> 工作日志：[event-maker-work-log.md](event-maker-work-log.md)

## 1. 当前能力边界

> 当前验证状态（2026-10-19 07:26）：完整池回测最强训练期候选 ROI 约 +0.81%，q50 和 50bps 费用下转负。keyset 候选发现已修复并完成真实扫描；6 个价格预筛候选中仅 1 个通过活动过滤，但其价差为 1 tick，不满足 2 tick 门槛，因此当前无合格市场、尚未启动干净长测。当前不应实盘、不应跟单。证据见 [完整池回测报告](event-maker-robust-backtest-2026-10-19.md) 和 [执行台账](event-maker-execution-ledger-2026-10-19.md)。

- `SHADOW`：使用真实 Polymarket 公共盘口和公共成交，不签名、不下单、不接触资金。
- `LIVE`：使用真实账户、真实 CLOB 订单，强制 `post-only + GTC`，没有 taker fallback。
- `LIVE` 默认关闭；必须显式设置 `liveTradingEnabled=true` 且提供 `accountId`。
- 历史回测不能证明当前配置有稳定 edge。不要把测试通过等同于可以盈利上线。

## 2. 环境要求

- JDK 17。
- 在 `D:\Project\PolyHermes\backend` 下执行 Gradle 命令。
- 独立 Shadow 不需要 MySQL、不需要 Docker。
- Spring REST 服务仍需要项目原有的 MySQL 和配置。
- 如果 Java 直连 Polymarket 超时，可使用本机 HTTP 代理，例如 `http://127.0.0.1:10808`。

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

## 4. 独立 Shadow 长测

```powershell
cd D:\Project\PolyHermes\backend

.\gradlew.bat eventMakerShadow `
  "-PshadowProxy=http://127.0.0.1:10808" `
  "-PshadowMinutes=120" `
  "-PshadowMaxMarkets=6" `
  "-PshadowDiscoveryPages=50" `
  "-PshadowMinVolume=100000" `
  "-PshadowMinPrice=0.97" `
  "-PshadowMaxPrice=0.99" `
  "-PshadowMinRecentTrades=3" `
  "-PshadowMinRecentNotional=50" `
  "-PshadowMinRecentTradesPerSide=1" `
  "-PshadowMinRecentTrades2h=20" `
  "-PshadowMinRecentNotional2h=500" `
  "-PshadowMinRecentTradesPerSide2h=3" `
  "-PshadowMinRemainingMinutes=120" `
  "-PshadowAllowNegRisk=true" `
  "-PshadowMaxQueue=100000" `
  "-PshadowSnapshotSeconds=10" `
  "-PshadowMinSpreadTicks=2" `
  "-PshadowImproveTicks=0" `
  "-PshadowEntryOffsetCents=0.5" `
  "-PshadowExitImproveTicks=0" `
  "-PshadowExitSpreadCents=2" `
  "-PshadowRequoteSeconds=300" `
  "-PshadowMaxOrderAgeSeconds=1800" `
  "-PshadowStaleBookSeconds=300" `
  "-PshadowActiveOrderStaleSeconds=1800" `
  "-PshadowOrderNotional=25" `
  "-PshadowFillVolumeFactor=1" `
  "-PshadowOutput=build/research/event-maker-shadow-passive" `
  --console=plain
```

参数说明：

| 参数 | 含义 |
|---|---|
| `shadowProxy` | Java 访问 Polymarket 的 HTTP 代理；直连可用时省略 |
| `shadowMinutes` | 运行分钟数 |
| `shadowMaxMarkets` | 最多市场数 |
| `shadowDiscoveryPages` | Gamma keyset 每标签最多扫描页数（1～100，默认 50；每页 100） |
| `shadowMinVolume` | 24 小时最低成交额；因 keyset 按 volume24hr 降序，低于该值的后续页面自动停止 |
| `shadowMinRecentTrades` | 近 30 分钟最少成交笔数 |
| `shadowMinRecentNotional` | 近 30 分钟最少成交额 |
| `shadowMinRecentTradesPerSide` | 近 30 分钟买卖两侧各自最少成交笔数 |
| `shadowMinRecentTrades2h` | 近 2 小时最少成交笔数 |
| `shadowMinRecentNotional2h` | 近 2 小时最少成交额 |
| `shadowMinRecentTradesPerSide2h` | 近 2 小时买卖两侧各自最少成交笔数 |
| `shadowMinPrice` / `shadowMaxPrice` | favorite 买一价格带 |
| `shadowMinRemainingMinutes` | 距离结束至少剩余分钟数 |
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
| `shadowOrderNotional` | 单市场订单名义金额 |
| `shadowFillVolumeFactor` | 队列清空后，累计需要多少倍订单股数的反向成交才成交；0 为旧乐观模式，1 为保守基准，10/50 为深队列压力测试 |
| `shadowOutput` | 输出目录 |

输出文件：

- `markets.json`：本次最终用于 Shadow 的市场。
- `market-discovery.json`：完整发现结果，包括预筛候选、活动过滤逐候选诊断、30m/2h 成交统计、拒绝原因和分页计数。
- `market-selection.json`：每个候选的价差、买一/卖一队列、入场/退出队列及拒绝原因。
- `events.jsonl`：报价、撤单、有效成交对账和 WS 状态。
- `trades.csv`：去重后的公共成交。
- `snapshots.csv`：每个市场每 10 秒的状态、库存、PnL 和回合数。
- `state.json`：逐笔 fill、逐回合 cycle、库存和活跃订单。
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

## 5. Spring REST 控制

启动 Shadow：

```http
POST /api/event-maker/start
Content-Type: application/json

{
  "mode": "SHADOW",
  "maxMarkets": 3,
  "minVolume24hr": 20000,
  "minRecentTrades30m": 3,
  "minRecentNotional30m": 50,
  "minRecentTradesPerSide30m": 1,
  "minRecentTrades2h": 20,
  "minRecentNotional2h": 500,
  "minRecentTradesPerSide2h": 3,
  "minRemainingMinutes": 120,
  "allowNegRisk": true,
  "minPrice": 0.97,
  "maxPrice": 0.99,
  "improveTicks": 0,
  "entryOffsetCents": 0.5,
  "exitSpreadCents": 2.0,
  "exitImproveTicks": 0,
  "requoteSeconds": 300,
  "maxOrderAgeSeconds": 1800,
  "orderNotional": 25.0,
  "maxQueueAheadShares": 100000.0,
  "shadowFillVolumeFactor": 1.0,
  "minSpreadTicks": 2,
  "maxTotalInventoryNotional": 100.0,
  "maxDailyLoss": 5.0,
  "maxCyclesPerMarket": 3,
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
- 所有订单 `postOnly=true`、`orderType=GTC`。

Live 请求示例：

```json
{
  "mode": "LIVE",
  "accountId": 1,
  "liveTradingEnabled": true,
  "maxMarkets": 2,
  "orderNotional": 10.0,
  "maxTotalInventoryNotional": 20.0,
  "maxDailyLoss": 2.0
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

- 活跃订单 ID、方向、价格、原始数量、已成交数量、状态、创建时间；
- 当前库存、入场价、入场时间；
- 已实现 PnL、已完成回合；
- 逐笔 fill 和逐回合 cycle；
- 当前入场订单 ID 和份额。

状态文件不包含私钥或 API 凭证。写入使用临时文件 + 原子替换。

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

行为：停止报价，并在退出清理中尝试撤销所有活跃订单。

处理：

1. 检查 `summary` 或 `lastError=daily_loss_limit`；
2. 核对 realized/unrealized PnL；
3. 不调高上限来掩盖异常；
4. 复盘后再决定是否重启。

### 8.5 启动时发现孤儿订单

触发条件：持久化状态中有活跃订单，但该市场不在本次发现列表。

行为：启动前尝试撤销；撤单失败则拒绝启动。

处理：手动撤销并清理状态后再启动。

### 8.6 WebSocket 断线

`LivePaperWsClient` 会自动重连。订单状态轮询不依赖 WebSocket。如果断线期间出现订单状态异常，按 8.2 处理。

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
- 尚未使用真实账户执行小资金 LIVE smoke。
- 历史回测使用的是成交打印和队列近似，不是完整 L2 队列重建。
- 公共成交不能完全代表真实队列位置，Shadow 的 fill 只能作为上界/近似。
- 流动性奖励、maker rebate、真实 fee 和撤单竞争尚未完整纳入生产 PnL。
- 当前没有自动新闻/事件流反应，只使用盘口和公共成交。
- 真实盘口 Shadow 截至 2026-10-19 05:22 仍为 0 fill，不能据此判断策略可成交。
