# Event Maker 策略交付计划与验收矩阵

> 创建时间：2026-10-18 23:37 +08:00<br>
> 目标：按计划完整实现事件做市策略，完成回测、影子交易和实盘执行链路的可验证测试，直到没有已知阻断问题。<br>
> 当前状态：**Live 状态恢复/账户级对账已加固，97 suites / 475 tests 通过；已提交 596504e，v4 等待+Shadow 运行中**<br>
> 最后更新：2026-10-19 09:01 +08:00<br>
> 工作日志：[event-maker-work-log.md](event-maker-work-log.md)<br>
> 恢复顺序：先读[执行台账](event-maker-execution-ledger-2026-10-19.md)，再读第 9.2 节“当前检查点”和第 10 节“当前下一步”，最后核对 `git status` 和工作日志末尾。<br>
> 前置研究：[事件做市历史回测](event-maker-backtest-2026-10-17.md)、[真实盘口纸交易](event-maker-live-paper-2026-10-17.md)<br>
> 正式回测：[完整池回测与策略审计](event-maker-robust-backtest-2026-10-19.md)<br>
> 稳健性核验：[交叉分段与 cluster bootstrap](event-maker-robustness-crosscheck-2026-10-19.md)<br>
> Live 验证：[执行链路验证](event-maker-live-execution-validation-2026-10-19.md)<br>

## 1. 完成定义

本目标只有在以下条件全部满足时才算完成：

1. 策略核心可复现：报价、风险、库存、PnL 计算都有单元测试。
2. 历史回测可重复运行，并生成明确的收益、回撤、fill rate 和尾部风险结果。
3. Shadow 模式能用真实盘口和真实成交持续运行，且无重复成交、无未知状态、无订单状态错乱。
4. Live 执行链路通过 mock/integration test 验证：post-only、GTC、签名、下单、撤单、部分成交、断线恢复和对账。
5. Live 模式有显式开关、账户校验、资金/库存/亏损上限和一键停止。
6. 实际运行证据、命令、输出文件、测试结果和已知限制都写入仓库文档。
7. 代码、测试、报告提交并推送。
8. 在用户提供账户、资金并明确授权后，完成真实小资金 Live smoke，并记录订单、成交、撤单、对账和停止证据。

## 2. 当前基线

| 项目 | 状态 | 证据 |
|---|---|---|
| adonpoli 成交结构分析 | 已完成 | 历史报告 |
| 1,000 市场 / 162 配置历史回测 | 已完成 | `event-maker-backtest-2026-10-17.md` |
| 4,187 市场 / 864 配置完整池回测 | 已完成；策略 edge 不足以实盘 | `event-maker-robust-backtest-2026-10-19.md` |
| 真实盘口 maker-only 纸交易 | 已完成一轮 | `event-maker-live-paper-2026-10-17.md` |
| 报价规划器 | 已完成 | `EventMakerQuotePlanner.kt` |
| 风险策略 | 已完成 | `EventMakerRiskPolicy.kt` |
| 策略核心单元测试 | 已完成 | event-maker/research 定向 94 tests；全量 475 tests 通过 |
| Shadow Spring 服务 | 已完成，统一 Shadow/Live 引擎 | `EventMakerStrategyService.kt`、`EventMakerLiveEngine.kt` |
| Live CLOB 执行适配器 | 已完成，mock/集成验证通过 | `ClobEventMakerExecutionAdapter.kt` |
| REST 控制接口 | 已完成 | `/api/event-maker/*` |
| Live 订单对账 | 已完成强化：状态损坏/错账户/未登记订单与持仓/部分成交/停止终态均 fail-closed | `EventMakerStateStore.kt`、`EventMakerLiveState.kt`、`EventMakerLiveEngine.kt` |
| 2 小时 Shadow 长测 | 旧基线稳定性通过；策略 0 fill | `event-maker-shadow-2026-10-19.md` |
| 旧被动 2h/12h Shadow | 2h 因类版本冲突无效；12h 已停止；均 0 fill | `backend/build/research/event-maker-shadow-passive-2c-*` |
| 新版保守 Shadow 冒烟 | 10 分钟 0 fill、0 错误；1 分钟停止复测 activeOrders=0 | `backend/build/research/event-maker-shadow-v2-smoke*-20261019/` |
| 新干净长测 | v2 等待 12 轮无活动通过市场，已因安全修复停止；提交后重启 | `event-maker-shadow-watch-v2-20261019/`；不降低价格带 |
| 全量测试 | 97 suites / 475 tests / 0 failures / 0 errors / 2 skipped | `backend/build/test-results/test/` |
| Live 端到端测试 | 引擎级 mock/恢复/账户级对账已完成；真实小资金 smoke 待授权 | 当前无可用账户、资金和明确授权 |

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
- [x] 完整候选池 4,187 市场、864 配置回测完成。
- [x] 训练期唯一选参、费用压力、队列压力、MAE 和 markout 分析完成。
- [x] Shadow 长测运行完成（旧基线稳定性通过；策略成交 edge 未通过）。
- [ ] 保守成交因子下 Shadow 产生足够 fill，且 markout/PnL/尾部风险可解释。
- [x] Live mock 端到端测试通过（引擎级 mock 流程）。
- [ ] 实际小资金 live smoke 通过（需要用户账户和显式授权）。
- [x] 更新策略文档、运行手册和故障处理手册。
- [x] 全量测试通过。
- [x] 早期执行框架提交并推送（`6eca4ff`、`179f2b2`）。
- [x] 本轮完整池回测、Shadow 修复和文档提交并推送（`06d168f`）。

## 5. 验收矩阵

| 需求 | 必须提供的证据 | 当前 |
|---|---|---|
| 回测可复现 | 命令、输入参数、summary、报告 | 已完成（4,187 市场完整池） |
| 报价逻辑正确 | 单元测试覆盖买入/卖出/队列/tick | 已完成 |
| 风险限制有效 | 单元测试覆盖库存/亏损/市场数 | 已完成 |
| Shadow 无重复成交 | transaction hash 去重统计为 0 重复组 | 已完成（2h 基线 0 重复键） |
| Shadow 有真实 fill | events/trades/snapshots 和回合明细 | 历史纸交易有 fill；新版冒烟 0 fill；旧长测无效或已停止 |
| Live post-only | mock 捕获 `postOnly=true` 和 `orderType=GTC` | 已完成 |
| Live 部分成交 | mock 状态机测试 | 已完成 |
| Live 断线恢复 | 重连和对账测试 | 已完成（引擎恢复/未知状态测试） |
| 一键停止 | 停止后无活跃订单、无新报价 | 已完成（引擎撤单测试） |
| 资金上限 | 启动前和每次报价前双重校验 | 已完成（风险与执行守卫测试） |
| Shadow 长测稳定性 | 无错误、无未知状态、无重复成交键 | 旧 2h 基线已完成；v2 等待 12 轮无市场后因安全修复停止，待重启 |
| 队列/费用压力 | q1~q50、0~100bps 费用表和报告 | 已完成；q50 与 50bps 均转负 |
| 全量回归 | 97 suites / 475 tests / 0 failures / 0 errors / 2 skipped | 已完成 |
| 真实小资金 Live smoke | 真实账户、资金、显式授权、订单与对账证据 | 未执行，等待用户授权 |

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
- 状态文件可解析、账户匹配、favorite token 匹配且 fill ledger 自洽；
- 账户全部活跃订单都在状态文件中；
- 账户正数持仓与状态库存集合一致；
- 撤单后能读取最终订单快照，部分成交可对账；
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

### 9.2 当前检查点（2026-10-19 07:29 +08:00）

- 当前分支：`main`。
- 最新已推送提交：`7b0bf7b feat: use keyset pagination for market discovery`。
- 当前工作区：主体代码与文档已推送；最新发现修复和证据已写入仓库。
- 已完成：策略模型、报价规划、风险策略、统一 Shadow/Live 引擎、Live 执行适配器、REST 接口、持久化恢复、账户与资金校验、执行守卫、逐笔/逐回合审计。
- 已完成：编译修复、持久化测试、Live mock 集成测试、统一 Shadow 与 planner、执行守卫、逐笔/逐回合审计、运行手册。
- 已验证：全量 96 suites / 450 tests / 0 failures / 2 skipped；完整池 4,187 市场 / 864 配置回测成功。
- 已失效：旧被动 2h 在收尾时因同 classpath 重编译触发 `NoSuchMethodError`；旧 12h 已停止。
- 已完成：官方 keyset 分页、价格预筛和逐候选活动诊断；真实扫描 6 个候选、1 个活动通过，但价差仅 1 tick。
- 未完成：真实小资金 Live smoke。原因是没有可用账户、资金和明确实盘授权；不得用 mock 结果冒充真实实盘验证。
- 策略结论：训练期选参配置在完整池为总 ROI +0.81%、训练 +1.10%、测试 +0.56%；3 次结算亏损、最大回撤 146.49 USDC，q50 与 50bps 费用下转负。当前不能实盘，也不能跟单。

### 9.3 后续执行顺序

1. 保持 keyset 发现版本，监控满足价格带、成交额、活动度和 `minSpreadTicks` 的真实市场。
2. 保持 Shadow/mock 模式，继续验证策略 edge；不能通过放宽 post-only、风控或队列约束来制造成交。
3. 只有用户提供可用账户、资金并明确授权后，才按运行手册执行最小金额 Live smoke。
4. Live smoke 必须记录账户、配置、订单、成交、撤单、对账、PnL、错误和停止结果；任何未知状态都必须 fail-closed。
5. 没有稳定 edge 证据前，不进入真实资金运行。

## 10. 当前下一步

### 10.1 当前收尾

1. 最新提交 `7b0bf7b` 已推送；文档收尾提交后确认工作区干净。
2. 等待满足全部策略条件的真实市场；不要通过放宽门槛制造 fill。
3. 出现合格市场后使用最终代码启动干净 2h/12h Shadow，并在运行期间禁止重编译/测试。

### 10.2 需要用户输入的阻塞项

真实小资金 Live smoke 需要：

- 可用账户和 `accountId`；
- 明确可用的资金上限；
- 用户对真实下单的显式授权；
- 允许运行的时间和市场范围。

在上述条件满足前，只允许 Shadow、mock 和离线回测，不允许真实下单。

### 10.3 策略研究下一步

当前研究轮次（2026-10-19 07:29）：

1. 完整池回测已完成：训练期选参配置 `p0.97-0.99_off0.5_spread2.0_q1.0_c3_r0`，总 ROI +0.81%、训练 +1.10%、测试 +0.56%。
2. 尾部风险已完成：278 回合中 3 次结算亏损，每次 -100 USDC；最大已实现回撤 146.49 USDC。
3. 队列压力已完成：off1/q1 配置在 q10 为 +0.41%、q20 约 0、q50 为 -0.77%。
4. 费用压力已完成：训练期选参配置在 25bps 双边费用下总 ROI +0.31%、测试 +0.06%；50bps 下转负。
5. Shadow 修复已完成：`shadowFillVolumeFactor`、60/300/1800 秒 markout、盘口年龄和订单队列诊断。
6. 当前没有运行中的 Shadow；keyset 扫描确认严格条件下暂无 2-tick 可报价市场，不能通过降低价差或活动门槛制造 fill。
7. 下一步：监控合格市场出现，再用最终代码启动干净的 2h/12h Shadow；启动后禁止重新编译或跑测试。
8. 只有在保守成交因子下出现足够 fill、markout 可解释、尾部亏损可控且费用后仍为正，才讨论小资金 Live smoke。
9. 真实 Live smoke 仍需要账户、资金上限、明确授权、时间窗口和市场范围。

### 10.4 2026-10-19 06:57 新轮次可恢复检查点

当前轮次先处理市场发现问题，避免把发现逻辑假空窗误判为市场真空窗。

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| DISC-1 | Gamma 市场发现分页与资格过滤前移 | 已完成 | keyset 分页、5 个发现测试、`market-discovery.json` 统计 |
| DISC-2 | 用真实 API 检查 0.97～0.99 候选覆盖 | 已完成 | keyset 扫描、6 个预筛候选、`market-discovery.json` |
| TEST-2 | 定向和全量回归测试 | 已完成 | 96 suites / 450 tests / 0 failures / 2 skipped |
| GIT-2 | 提交并推送修复 | 已完成 | `7b0bf7b`、`main -> main` |
| OBS-3 | 修复后干净 Shadow 长测 | 后台等待中 | 最多等待 180 分钟，选出后运行 120 分钟 |
| WATCH-1 | 自动等待合格市场并启动 Shadow | 已完成 | 自动重试、attempt 日志、超时文件、实际 5 次等待冒烟 |
| CLEAN-1 | 清理一次性构建/研究产物 | 已完成本轮 | 删除 9 个失效/重复目录，保留正式证据 |

执行顺序：DISC-1 → 测试 → 真实候选检查 → 提交 → OBS-3 → 结果分析 → CLEAN-1。若真实候选仍为空，记录为真实市场空窗，不降低价格带。

动作记录要求：每完成一项，在本节和 `event-maker-work-log.md` 同时写入实际命令、结果、证据、失败与修复；执行台账保留恢复入口。

### 10.5 2026-10-19 07:26 发现阶段验收结果

- `/markets/keyset` 已替代 offset 分页；超出 100,000 USDC 24h 成交额门槛的市场已覆盖。
- 6 个价格预筛候选，6 个活动过滤检查，1 个通过；唯一通过者价差 1 tick，被 `minSpreadTicks=2` 拒绝。
- 当前不是市场发现故障，而是严格策略条件没有可报价市场。
- 下一步不是放宽条件，而是等待满足价格带、成交额、活动度和价差要求的市场；出现后再启动干净 2h/12h Shadow。

### 10.6 2026-10-19 07:29 提交记录

- 提交：`7b0bf7b feat: use keyset pagination for market discovery`。
- 推送：`origin/main`，`a2e14c7..7b0bf7b main -> main`。
- 全量测试：96 suites / 450 tests / 0 failures / 0 errors / 2 skipped。
- 当前没有 Shadow/Backtest 进程；等待合格市场。

### 10.7 2026-10-19 07:43 自动等待模式验收

- 参数：`shadowWaitForMarketMinutes`、`shadowDiscoveryRetrySeconds`。
- 输出：`discovery-attempts.jsonl`、`market-watch-timeout.json`、最新 `market-discovery.json`/`market-selection.json`。
- 冒烟：0 分钟等待 1 次尝试；1 分钟等待/15 秒重试 5 次尝试；均正确超时且未伪造 fill。
- 全量测试：96 suites / 450 tests / 0 failures / 0 errors / 2 skipped。
- 下一步：提交后启动长等待任务。出现合格市场后，同一 JVM 直接进入 Shadow，避免重复重编译。
### 10.8 2026-10-19 08:46 状态恢复与账户级对账加固

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| SAFE-1 | 状态文件损坏、错账户、缺失 favorite token、非法快照 fail-closed | 已完成 | `EventMakerStateStoreTest`、`EventMakerLiveStateTest` |
| SAFE-2 | 账户全部活跃订单与状态登记比对 | 已完成 | `ClobEventMakerExecutionAdapterTest`、`EventMakerStrategyServiceSafetyTest` |
| SAFE-3 | 账户正数持仓 conditionId 和数量与状态库存比对 | 已完成 | `ClobEventMakerExecutionAdapterTest`、`EventMakerStrategyServiceSafetyTest` |
| SAFE-4 | 孤儿订单确认撤销、孤儿库存阻止启动 | 已完成 | `EventMakerStartupRecoveryTest` |
| SAFE-5 | 停止时最终快照对账部分成交 | 已完成 | `EventMakerLiveEngineTest` |
| SAFE-6 | 部分卖出跨订单累计 cycle PnL | 已完成 | `EventMakerLiveStateTest` |
| SAFE-7 | Shadow 审计脚本逐笔重放财务数据 | 已完成 | `scripts/audit-event-maker-shadow.ps1` 自测：有效 exit 0、篡改 exit 1 |
| BT-4 | 标签/价格桶 × 训练/测试与 cluster bootstrap | 已完成 | `docs/zh/event-maker-robustness-crosscheck-2026-10-19.md`、分析脚本 |
| TEST-3 | 定向与全量回归 | 已完成 | eventmaker 94 tests；全量 97 suites / 475 tests / 0 failures / 0 errors / 2 skipped |
| GIT-3 | 提交并推送本轮安全修复 | 已完成 | `596504e fix: harden event maker live state recovery`、`main -> main` |
| OBS-4 | 12h 等待 + 12h Shadow 主臂 | 运行中 | v5 Java PID 25560，输出 `event-maker-shadow-watch-v5-20261019/` |
| EXP-1 | 一档价差独立实验臂（只读，不混入正式验证） | 运行中 | Java PID 15356，`minSpreadTicks=1`，输出 `event-maker-shadow-experiment-spread1-v2-20261019/` |

- 当前没有运行中的 Shadow/回测进程。
- 下一执行顺序：GIT-3 → OBS-4 → 等待结果 → 审计 → 文档收尾。
- 真实 Live smoke 仍缺少 `accountId`、资金上限、明确授权、时间窗口和市场范围。