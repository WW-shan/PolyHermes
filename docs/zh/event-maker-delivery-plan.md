# Event Maker 策略交付计划与验收矩阵

> 创建时间：2026-10-18 23:37 +08:00  
> 目标：按计划完整实现事件做市策略，完成回测、影子交易和实盘执行链路的可验证测试，直到没有已知阻断问题。  
> 当前状态：**进行中（P3 持久化恢复与 P4 验证阶段）**  
> 最后更新：2026-10-19 00:17 +08:00
> 工作日志：[event-maker-work-log.md](event-maker-work-log.md)  
> 前置研究：[事件做市历史回测](event-maker-backtest-2026-10-17.md)、[真实盘口纸交易](event-maker-live-paper-2026-10-17.md)

## 1. 完成定义

本目标只有在以下条件全部满足时才算完成：

1. 策略核心可复现：报价、风险、库存、PnL 计算都有单元测试。
2. 历史回测可重复运行，并生成明确的收益、回撤、fill rate 和尾部风险结果。
3. Shadow 模式能用真实盘口和真实成交持续运行，且无重复成交、无未知状态、无订单状态错乱。
4. Live 执行链路通过 mock/integration test 验证：post-only、GTC、签名、下单、撤单、部分成交、断线恢复和对账。
5. Live 模式有显式开关、账户校验、资金/库存/亏损上限和一键停止。
6. 实际运行证据、命令、输出文件、测试结果和已知限制都写入仓库文档。
7. 代码、测试、报告提交并推送。

## 2. 当前基线

| 项目 | 状态 | 证据 |
|---|---|---|
| adonpoli 成交结构分析 | 已完成 | 历史报告 |
| 1,000 市场 / 162 配置历史回测 | 已完成 | `event-maker-backtest-2026-10-17.md` |
| 真实盘口 maker-only 纸交易 | 已完成一轮 | `event-maker-live-paper-2026-10-17.md` |
| 报价规划器 | 已完成 | `EventMakerQuotePlanner.kt` |
| 风险策略 | 已完成 | `EventMakerRiskPolicy.kt` |
| 策略核心单元测试 | 已完成 | 7 tests passed |
| Shadow Spring 服务 | 初版已完成，待统一 planner | `EventMakerStrategyService.kt` |
| Live CLOB 执行适配器 | 初版已完成，待补集成测试 | `ClobEventMakerExecutionAdapter.kt` |
| REST 控制接口 | 已完成 | `/api/event-maker/*` |
| Live 订单对账 | 初版已完成，待补恢复测试 | `EventMakerLiveState.kt` |
| Live 端到端测试 | 未完成 | 先做 mock E2E，再做小资金 smoke |

## 3. 目标架构

```text
Gamma / Data API
      |
      v
MarketDiscovery ──> EventMakerStrategyService
      |                    |
      v                    v
CLOB WebSocket ──> EventMakerQuotePlanner ──> RiskPolicy
                           |
                           v
                  EventMakerExecutionPort
                    /                 \
             ShadowExecutor       ClobExecutor
              (simulation)       (post-only GTC)
                                      |
                                      v
                              OrderReconciler
```

### 模块职责

- `EventMakerQuotePlanner`：只负责计算 maker 报价，不做 IO。
- `EventMakerRiskPolicy`：只负责资金、库存、亏损和市场数量限制。
- `EventMakerStrategyService`：编排发现、订阅、报价、撤单和状态。
- `EventMakerExecutionPort`：纸面/实盘统一执行接口。
- `ClobEventMakerExecutionAdapter`：签名、post-only 下单、撤单、订单查询。
- `EventMakerOrderReconciler`：处理部分成交、状态丢失、断线恢复和重复回报。
- `EventMakerStrategyController`：启动、停止、状态查询；默认 shadow。

## 4. 任务清单

### P0：策略核心与安全边界

- [x] 定义策略配置、模式、报价意图和状态模型。
- [x] 实现买入/卖出报价规划。
- [x] 实现大队列拒绝、盘口过期拒绝、post-only 价格保护。
- [x] 实现总库存、市场数量、日亏损限制。
- [x] 为核心逻辑补单元测试。
- [x] 增加策略状态机的完整单元测试。
- [x] 增加异常输入、极端 tick、0/1 附近价格测试。

### P1：Shadow 服务

- [x] 将真实盘口/成交数据接入 Spring 服务（Shadow 初版）。
- [x] 使用统一 planner 生成 shadow 报价（Shadow 与 Live 共用 `EventMakerLiveEngine`）。
- [x] 记录每个市场、每个回合的 fill、markout、PnL（状态文件记录 fills/cycles，Shadow 输出 markouts.csv）。
- [x] 实现启动/停止/状态查询。
- [x] 运行至少 2 小时真实 shadow 测试（运行稳定，但统一引擎 0 成交）。
- [x] 验证跨源成交去重和订单状态无重复（2h 重复成交键 0，订单事件无重复）。

### P2：Live 执行适配器

- [x] 实现 `EventMakerExecutionPort`。
- [x] 使用现有 `OrderSigningService` 签名。
- [x] 强制 `postOnly=true`、`orderType=GTC`。
- [x] 校验 tick size、negRisk、余额、最小订单量。
- [x] 实现单订单撤单和停止时撤单。
- [x] 实现订单状态查询与部分成交对账。
- [x] 实现断线恢复和未知状态 fail-closed。
- [x] 增加订单请求构造测试（post-only/GTC）。

### P3：Live 策略编排

- [x] 显式 `liveTradingEnabled` 开关，默认关闭。
- [x] 启动前校验账户、API 凭证、代理钱包部署和资金上限。
- [x] 实盘只允许 post-only，禁止 taker fallback。
- [x] 每次报价前重新检查风险。
- [x] 实现一键停止和全部撤单。
- [x] 实现最大日亏损自动停机。
- [x] 实现订单/成交/库存持久化或可恢复状态。

### P4：验证与交付

- [x] 历史回测重复运行，结果写入报告。
- [x] Shadow 长测运行完成（稳定性通过；策略成交 edge 未通过）。
- [x] Live mock 端到端测试通过（引擎级 mock 流程）。
- [ ] 实际小资金 live smoke 通过（需要用户账户和显式授权）。
- [x] 更新策略文档、运行手册和故障处理手册。
- [x] 全量测试通过。
- [ ] 提交并推送。

## 5. 验收矩阵

| 需求 | 必须提供的证据 | 当前 |
|---|---|---|
| 回测可复现 | 命令、输入参数、summary、报告 | 已完成（30 市场复跑） |
| 报价逻辑正确 | 单元测试覆盖买入/卖出/队列/tick | 已完成 |
| 风险限制有效 | 单元测试覆盖库存/亏损/市场数 | 已完成 |
| Shadow 无重复成交 | transaction hash 去重统计为 0 重复组 | 已完成（2h 基线 0 重复键） |
| Shadow 有真实 fill | events/trades/snapshots 和回合明细 | 历史纸交易有 fill；统一引擎长测 0 fill |
| Live post-only | mock 捕获 `postOnly=true` 和 `orderType=GTC` | 已完成 |
| Live 部分成交 | mock 状态机测试 | 已完成 |
| Live 断线恢复 | 重连和对账测试 | 已完成（引擎恢复/未知状态测试） |
| 一键停止 | 停止后无活跃订单、无新报价 | 已完成（引擎撤单测试） |
| 资金上限 | 启动前和每次报价前双重校验 | 已完成（风险与执行守卫测试） |

## 6. 实盘安全门槛

以下条件不满足时，Live 模式必须拒绝启动：

- `liveTradingEnabled=true` 显式开启；
- 账户存在且 API Key/Secret/Passphrase 可用；
- 私钥可解密；
- 代理钱包已部署；
- tick size 和 negRisk 查询成功；
- 单笔、单市场、总库存均在配置上限内；
- 日亏损未触发；
- 所有报价均为 post-only；
- 一键停止可用；
- 最近一次 shadow 测试没有重复成交或未知状态。

## 7. 运行与产物

### 历史回测

```powershell
cd D:\Project\PolyHermes\backend
.\gradlew.bat eventMakerBacktest `
  "-PresearchProxy=http://127.0.0.1:10808" `
  "-PresearchMaxMarkets=1000" `
  "-PresearchMinVolume=250000" `
  "-PresearchFrom=2024-01-01" `
  "-PresearchTo=2026-10-01" `
  "-PresearchConcurrency=4" `
  "-PresearchOutput=build/research/event-maker-final"
```

### Shadow / live paper

```powershell
.\gradlew.bat livePaperMaker `
  "-PliveProxy=http://127.0.0.1:10808" `
  "-PliveMaxMarkets=12" `
  "-PliveMinVolume=20000" `
  "-PliveMinMinutes=120" `
  "-PliveMinRemainingMinutes=720" `
  "-PliveMinutes=720" `
  "-PliveTargetCycles=12" `
  "-PliveAllowNegRisk=true" `
  "-PlivePollSeconds=3" `
  "-PliveSnapshotSeconds=10" `
  "-PliveOutput=build/research/live-paper-final6-20261017"
```

## 8. 文档更新规则

每次完成一个动作，必须同步更新：

1. 本文件的 checkbox 和验收矩阵；
2. `event-maker-work-log.md` 的时间、动作、命令、结果、证据；
3. 如果结论变化，更新对应研究报告；
4. 如果代码行为变化，补测试并记录测试命令；
5. 不允许只写“已完成”，必须附可复查的文件、命令或测试结果。

## 9. 执行协议与检查点

### 9.1 每轮工作必须留下的记录

1. 开始前：在 `event-maker-work-log.md` 记录本轮目标、前置状态和预计下一步。
2. 执行中：记录关键命令、修改文件、测试名称、失败原因和修复动作。
3. 结束后：记录实际命令、原始结果摘要、证据路径、未解决风险和下一步。
4. 计划文件同步更新 checkbox；不能只改日志而不改验收矩阵。
5. 不允许用“应该可以”“看起来正常”替代测试输出或运行证据。

### 9.2 当前检查点（2026-10-19 01:50）

- 已完成：策略模型、报价规划、风险策略、统一 Shadow/Live 引擎、Live 执行适配器、REST 接口、持久化恢复、账户与资金校验、执行守卫、逐笔/逐回合审计。
- 已完成：编译修复、持久化测试、Live mock 集成测试、统一 Shadow 与 planner、执行守卫、逐笔/逐回合审计、运行手册。
- 已验证：event-maker 45 tests；全量 428 tests / 0 failures；历史回测 30 市场 / 162 配置复跑成功。
- 已完成：2 小时 Shadow 稳定性验收、去重与订单事件检查、正式 Shadow 报告。

### 9.3 后续执行顺序

1. 完成并验收 2 小时 Shadow 长测；
2. 检查去重、未知状态、订单/回合记账；
3. 运行最终全量测试；
4. 在用户明确授权且有账户资金时执行小资金 smoke；
5. 提交并推送。

## 10. 当前下一步

1. 等待 2 小时 Shadow 和筛选版 Shadow 完成；
2. 分析 `summary.json`、`events.jsonl`、`trades.csv`、`snapshots.csv`、`state.json`；
3. 写正式 Shadow 报告并更新验收矩阵；
4. 最终提交推送。