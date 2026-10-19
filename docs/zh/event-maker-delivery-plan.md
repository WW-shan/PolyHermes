# Event Maker 策略交付计划与验收矩阵

> 创建时间：2026-10-18 23:37 +08:00<br>
> 目标：按计划完整实现事件做市策略，完成回测、影子交易和实盘执行链路的可验证测试，直到没有已知阻断问题。<br>
> 当前状态：**回测与执行链路审计完成；全量测试 103 suites / 555 tests 通过；跨进程 UTC 日亏损锚点、SETTLE 结算账本和 pending redemption 恢复已补齐。v2–v5 Shadow 的缺陷已修复；v6 已三次挂单并三次 requote 撤单，当前 FLAT、0 fill，仍缺真实成交验收。真实小资金 Live smoke 仍缺账户、资金和明确授权，不能实盘**<br>
> 最后更新：2026-10-20 02:05 +08:00<br>
> 工作日志：[event-maker-work-log.md](event-maker-work-log.md)<br>
> 恢复顺序：先读[执行台账](event-maker-execution-ledger-2026-10-19.md)，再读第 9.2 节“当前检查点”和第 10 节“当前下一步”，最后核对 `git status` 和工作日志末尾。<br>
> 前置研究：[事件做市历史回测](event-maker-backtest-2026-10-17.md)、[真实盘口纸交易](event-maker-live-paper-2026-10-17.md)<br>
> 正式回测：[完整池回测与策略审计](event-maker-robust-backtest-2026-10-19.md)<br>
> 稳健性核验：[交叉分段与 cluster bootstrap](event-maker-robustness-crosscheck-2026-10-19.md)<br>
> 正式候选报告：[12h–7d 跨窗口、Guard A/B、费用与邻域](event-maker-safe-candidate-2026-10-19.md)<br>
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
| 4,187 市场 / 864 配置完整池回测 | 历史基线；旧回测未模拟最大挂单年龄 | `event-maker-robust-backtest-2026-10-19.md` |
| 4,189 市场 / 7d 数据快照 | 已完成；可原子缓存并重放 12h–7d 窗口 | `EventMakerDatasetStore.kt`、`event-maker-full-v2-7d-20261019-dataset.json.gz` |
| 回测/Live 挂单年龄一致性 | 已修复；过期买单/卖单撤单已验证 | `EventMakerBacktestEngineTest` |
| 正式候选跨窗口回测 | 8 窗口全零结算亏损，最差窗口 ROI +1.033% | [候选报告](event-maker-safe-candidate-2026-10-19.md) |
| 真实盘口 maker-only 纸交易 | 已完成一轮 | `event-maker-live-paper-2026-10-17.md` |
| 报价规划器 | 已完成 | `EventMakerQuotePlanner.kt` |
| 风险策略 | 已完成 | `EventMakerRiskPolicy.kt` |
| 策略核心单元测试 | 已完成 | 全量 103 suites / 555 tests 通过；新增日锚点、时钟回拨、无买盘估值、结算账本和状态 schema 回归 |
| Shadow Spring 服务 | 已完成，统一 Shadow/Live 引擎 | `EventMakerStrategyService.kt`、`EventMakerLiveEngine.kt` |
| Live CLOB 执行适配器 | 已完成，mock/集成验证通过 | `ClobEventMakerExecutionAdapter.kt` |
| REST 控制接口 | 已完成 | `/api/event-maker/*` |
| Live 订单对账 | 已完成强化：状态损坏/错账户/未登记订单与持仓/部分成交/停止终态均 fail-closed | `EventMakerStateStore.kt`、`EventMakerLiveState.kt`、`EventMakerLiveEngine.kt` |
| 2 小时 Shadow 长测 | 旧基线稳定性通过；策略 0 fill | `event-maker-shadow-2026-10-19.md` |
| 旧被动 2h/12h Shadow | 2h 因类版本冲突无效；12h 已停止；均 0 fill | `backend/build/research/event-maker-shadow-passive-2c-*` |
| 新版保守 Shadow 冒烟 | 10 分钟 0 fill、0 错误；1 分钟停止复测 activeOrders=0 | `backend/build/research/event-maker-shadow-v2-smoke*-20261019/` |
| 新干净长测 | v2 等待 12 轮无活动通过市场，已因安全修复停止；提交后重启 | `event-maker-shadow-watch-v2-20261019/`；不降低价格带 |
| 全量测试 | 103 suites / 555 tests / 0 failures / 0 errors / 2 skipped | `backend/build/test-results/test/` |
| 一键停止 | stop 先中断在途 OkHttp 调用再撤单；REST 实测 20ms 返回 | `EventMakerStrategyStopTest`、`69649e5` |
| REST 代理回退 | 未显式配置时回退数据库系统代理；显式配置优先 | `EventMakerStrategyStopTest`、`69649e5` |
| Live 端到端测试 | 引擎级 mock/恢复/账户级对账已完成；真实小资金 smoke 待授权 | 当前无可用账户、资金和明确授权 |
| 逆势流 Guard | 已接入回测、REST DTO、Shadow/Live 引擎；默认 3600s / -0.4 / 0.5c | `EventMakerAdverseFlowGuard.kt`、Guard 单测、LiveEngine 撤单测试 |
| Guard 同模型 A/B | 24h：无 Guard 测试 -0.39% 且有 1 次归零；Guard 测试 +1.058% 且 0 归零 | [候选报告](event-maker-safe-candidate-2026-10-19.md) |
| 最终 Shadow 冒烟 | 5 分钟、1 市场、1 报价、1 撤单、0 fill、0 错误；审计通过 | `backend/build/research/event-maker-shadow-flowguard-smoke-wide-20261019/` |

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
- [x] 完整候选池 4,189 市场、864 配置回测完成。
- [x] 新增原子 gzip 数据快照，参数网格可复用同一数据且失败时不写残缺缓存。
- [x] 回测补上 `maxOrderAgeSeconds` 撤单，和 Shadow/Live 执行生命周期一致。
- [x] 正式候选通过 12h/1d/2d/3d/4d/5d/6d/7d 八窗口回测；全部零结算亏损，训练/测试为正。
- [x] 训练期唯一选参、费用压力、队列压力、MAE、跨窗口和参数邻域分析完成。
- [x] Shadow 长测运行完成（旧基线稳定性通过；旧策略成交 edge 未通过）。
- [ ] 保守成交因子下 Shadow 产生足够 fill，且 markout/PnL/尾部风险可解释。（正式候选待启动长测并通过审计）
- [x] Live mock 端到端测试通过（引擎级 mock 流程）。
- [x] 逆势流 Guard 在回测、Shadow、Live 共用引擎中实现并通过边界/撤单测试。
- [x] 完成 4,189 市场 Guard A/B、三笔历史归零因果审计和 Shadow 冒烟。
- [ ] 完全消除不可预测的第一笔大额卖单尾部风险。
- [ ] 实际小资金 live smoke 通过（需要用户账户和显式授权）。
- [x] 更新策略文档、运行手册和故障处理手册。
- [x] 全量测试通过。
- [x] 早期执行框架提交并推送（`6eca4ff`、`179f2b2`）。
- [x] 本轮完整池回测、Shadow 修复和文档提交并推送（`06d168f`）。

## 5. 验收矩阵

| 需求 | 必须提供的证据 | 当前 |
|---|---|---|
| 回测可复现 | 命令、输入参数、summary、报告 | 已完成（4,189 市场 7d 快照，12h–7d 可重放） |
| 报价逻辑正确 | 单元测试覆盖买入/卖出/队列/tick | 已完成 |
| 风险限制有效 | 单元测试覆盖库存/亏损/市场数 | 已完成 |
| Shadow 无重复成交 | transaction hash 去重统计为 0 重复组 | 已完成（2h 基线 0 重复键） |
| Shadow 有真实 fill | events/trades/snapshots 和回合明细 | 历史纸交易有 fill；新版冒烟 0 fill；旧长测无效或已停止 |
| Live post-only | mock 捕获 `postOnly=true` 和 `orderType=GTC` | 已完成 |
| Live 部分成交 | mock 状态机测试 | 已完成 |
| Live 断线恢复 | 重连和对账测试 | 已完成（引擎恢复/未知状态测试） |
| 一键停止 | 停止后无活跃订单、无新报价 | 已完成（引擎撤单测试） |
| 资金上限 | 启动前和每次报价前双重校验 | 已完成（风险与执行守卫测试） |
| Shadow 长测稳定性 | 无错误、无未知状态、无重复成交键 | 旧 2h 基线已完成；v5 主臂/一档实验臂已用 69649e5 重启；实验臂已选中真实市场并挂单 |
| 队列/费用压力 | q1~q50、0~100bps 费用表和报告 | 已完成；候选 q15–q50 零归零，50bps +0.069%、100bps -0.914% |
| 全量回归 | 102 suites / 530 tests / 0 failures / 0 errors / 2 skipped | 已完成 |
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

### 9.2 当前检查点（2026-10-19 12:05 +08:00）

- 当前分支：`main`。
- 最新已推送提交：`e4314c2 docs: record shadow evidence-integrity fix and restart`。
- 本轮新增修复：`69649e5`（stop 中断在途网络调用 + REST 系统代理回退）、`b2846a8`（trades.csv 逐笔 flush + state.json 周期落盘）。
- 本轮新增证据：MySQL 8.2 容器 + Flyway 50 迁移；REST SHADOW 全链路（start/status/stop，stop 20ms）；LIVE 三条负向路径全部 fail-closed；一档实验臂首次选中真实市场并挂出 Shadow 买单。
- 当前两臂：主臂 Java PID `21704`（spread=2，等待）；实验臂 Java PID `14376`（spread=1，等待活动门槛回升）。
- 当前工作区：主体代码与文档已推送；最新发现修复和证据已写入仓库。
- 已完成：策略模型、报价规划、风险策略、统一 Shadow/Live 引擎、Live 执行适配器、REST 接口、持久化恢复、账户与资金校验、执行守卫、逐笔/逐回合审计。
- 已完成：编译修复、持久化测试、Live mock 集成测试、统一 Shadow 与 planner、执行守卫、逐笔/逐回合审计、运行手册。
- 已验证：全量 98 suites / 478 tests / 0 failures / 0 errors / 2 skipped；完整池 4,187 市场 / 864 配置回测成功。
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
| OBS-4 | 12h 等待 + 12h Shadow 主臂 | 运行中 | v5 Java PID `25924`（wrapper `20468`），输出 `event-maker-shadow-watch-v5-20261019/` |
| EXP-1 | 一档价差独立实验臂（只读，不混入正式验证） | 运行中 | Java PID `25556`（wrapper `22844`），`minSpreadTicks=1`，输出 `event-maker-shadow-experiment-spread1-v2-20261019/` |

- 最新两臂进程状态以 10.9 节为准；旧行中的 PID 是历史检查点。
- 当前 Shadow：v6 目录 `backend/build/research/event-maker-safe-candidate-shadow-main-v6-20261020/` 于 00:53 启动。01:01 挂 `shadow-1` BUY 0.972 / 25.72（queue 15.05）；01:08 因 requote 撤销并挂 `shadow-2` BUY 0.971 / 25.74（queue 65.04）；01:13 再次 requote 撤销；01:21 bid 回到 0.972 后重挂 `shadow-3` BUY 0.972 / 25.72（queue 5.0）；01:26 `shadow-3` 再次 requote 撤销。截至 02:05 为 FLAT、0 fill、0 cycle。`logIssues=0`、`stderrBytes=0`。

下一执行顺序：GIT-3 → OBS-4 → 等待结果 → 审计 → 文档收尾。
- 真实 Live smoke 仍缺少 `accountId`、资金上限、明确授权、时间窗口和市场范围。

### 10.9 2026-10-19 17:3x-17:5x 实盘执行链路审计补漏

本轮不是放宽策略条件，而是逐段核对官方 CLOB V2 文档和 `clob-client` 实现，修复真实 Live 适配器的结构性问题：

| ID | 验收项 | 状态 | 证据 |
|---|---|---|---|
| LIVE-A1 | 单笔撤单端点/请求体/响应确认 | 已完成 | `DELETE /order` + `orderID`；`canceled` 精确确认，`not_canceled` 失败；单测 + HTTP 探测 |
| LIVE-A2 | 活跃订单分页终止游标 | 已完成 | `MA==` 起始、`LTE=` 终止；单测 |
| LIVE-A3 | 撤单与部分成交竞态 | 已完成 | 撤单后最终快照先对账，再清 activeOrder；竞态测试 |
| LIVE-A4 | 不确定下单结果 | 已完成 | 2xx 无 body / 无 orderID / 矛盾 orderID 均 ambiguous + halt；单测 |
| LIVE-A5 | 签名异常不逃逸 | 已完成 | `order_signing_failed` 预检失败；单测 |
| LIVE-A6 | CLOB V2 状态集合 | 已完成 | `INVALID`、`CANCELED_MARKET_RESOLVED`、`unmatched`、小写兼容；单测 |
| LIVE-A7 | 订单快照 maker/condition/token/满额语义 | 已完成 | 不匹配 fail-closed；单测 |
| LIVE-A8 | 重启后的日亏损基线 | 已完成 | `resetDailyPnlBaseline(states)`；单测 |
| LIVE-A9 | 下单/撤单失败请求退避 | 已完成 | 5 秒退避；单测 |
| LIVE-A10 | Shadow 快照字段 | 已完成 | 补齐 token/condition/maker；EventMaker 全测试 |
| LIVE-A11 | 孤儿订单终态恢复 | 已完成 | 撤单返回 not_canceled 仍查最终快照；单测 |
| LIVE-A12 | 持久化状态大小写 | 已完成 | 统一状态校验；单测 |

- 全量：`100 suites / 513 tests / 0 failures / 0 errors / 2 skipped`。
- 提交：`05277a3`（代码）、`37474ec`（文档），已推送。
- Spring REST 冒烟：PID `24064`，`/api/system/health=200`；SHADOW `start → status → stop` 通过；`stop=95ms`。
- 当前两臂：主臂 Java PID `25924`（wrapper `20468`，spread=2）；实验臂 Java PID `25556`（wrapper `22844`，spread=1）。两臂均用新代码等待严格条件市场，输出目录保持不变。
- 运行期间仍禁止编译/测试；轮询使用 `.\scripts\check-event-maker-watch.ps1`。
- 真实 Live smoke 仍缺：可用 `accountId`、资金上限、用户明确下单授权、时间窗口、市场范围。
- **结论不变：执行链路已知结构性问题已修复，但回测尾部风险未被约束，不能宣布可实盘，不能标记目标完成。**

### 10.10 2026-10-19 18:00-19:20 逆势流 Guard 收口

| ID | 验收项 | 状态 | 证据 |
|---|---|---|---|
| GUARD-1 | favorite token 滚动成交流与价格跌幅计算 | 已完成 | `EventMakerAdverseFlowGuard.kt`、Guard 单测 |
| GUARD-2 | BUY 撤单与 Guard 解除前禁止重挂 | 已完成 | `EventMakerLiveEngineTest` |
| GUARD-3 | 回测因果判断，不使用同笔成交未来信息 | 已完成 | `EventMakerBacktestEngineTest` |
| GUARD-4 | REST/CLI/Gradle 参数贯通 | 已完成 | DTO、`EventMakerShadowCli`、`build.gradle.kts` |
| GUARD-5 | 启动时用最近成交预热 Guard | 已完成 | Spring/Shadow 运行路径 |
| GUARD-6 | 全量 4,189 市场 A/B | 已完成，部分有效 | [尾部审计](event-maker-flow-guard-tail-audit-2026-10-19.md) |
| GUARD-7 | Shadow 运行链路冒烟 | 已完成 | `event-maker-shadow-flowguard-smoke-wide-20261019/` |
| TEST-4 | 全量回归 | 已完成 | 101 suites / 525 tests / 0 failures / 0 errors / 2 skipped |
| WATCH-2 | 最终两臂重启 | 运行中 | 主臂 wrapper `11600` / Java `26308`；实验臂 wrapper `25864` / Java `22788` |

- Guard 只能拦住三笔历史归零中的 China-Vietnam；Iran May 25 和 US–Iran Aug 31 仍亏 100。
- 全量配置中位数 ROI 未改善，845/864 个配置仍出现单市场归零，**不能实盘**。
- 严格 0.97–0.99 价格带当前仍无报价市场；正式两臂继续等待，不降低门槛。
- 当前两臂首轮均无日志错误、无 stderr；运行期间禁止编译/测试。
- 真实 Live smoke 仍缺 `accountId`、资金上限、明确授权、时间窗口和市场范围。- 提交并推送：`d768950 feat: add causal adverse-flow guard to event maker`（`c8fb838..d768950 main -> main`）。
### 10.11 2026-10-19 22:45 数据/执行一致性收口与正式候选

| ID | 验收项 | 状态 | 证据 |
|---|---|---|---|
| DATA-1 | gzip 数据快照可复用 | 已完成 | `EventMakerDatasetStore.kt`、3 条存储测试 |
| DATA-2 | 抓取失败不产生残缺缓存 | 已完成 | Gamma/成交失败 fail-fast；成交 3 次重试 |
| DATA-3 | 缓存原子替换 | 已完成 | 临时文件 + `ATOMIC_MOVE`，测试无残留 |
| EXEC-1 | 回测模拟买单最大年龄撤单 | 已完成 | `EventMakerBacktestEngineTest` |
| EXEC-2 | 回测模拟卖单最大年龄重挂 | 已完成 | `EventMakerBacktestEngineTest` |
| GRID-1 | 候选参数邻域 | 已完成 | `candidate-neighborhood`，1,008 配置 |
| GRID-2 | q/spread/offset 稳定性 | 已完成 | q15–q50、spread 0.75–1.5c、offset 0–1c 全部零归零 |
| OOS-1 | 12h–7d 八窗口 | 已完成 | 正式候选 8 窗口全零结算亏损、训练/测试为正 |
| OOS-2 | 全窗口配置交集 | 已完成 | 864 配置中仅 2 个通过；正式候选最差窗口 ROI 最高 |
| GUARD-8 | 同数据同执行模型 A/B | 已完成 | 无 Guard 测试 -0.39% + 1 次归零；Guard +1.058% + 0 归零 |
| TEST-5 | 全量回归 | 已完成 | 103 suites / 555 tests / 0 failures / 0 errors / 2 skipped |
| SHADOW-3 | 正式候选 Shadow 长测 | v6 运行中 | 已挂 3 次 Shadow BUY、3 次 requote 撤单；`shadow-3` 于 01:26 撤销；当前 FLAT、0 fill |
| LIVE-A13 | 日亏损跨重启持久化 | 已完成 | UTC day + baseline 写入 state v3；同日重启沿用，跨日重锚；8 条新增回归 |
| LIVE-1 | 真实小资金 Live smoke | 未执行 | 缺账户、资金、明确授权、时间窗口、市场范围 |

当前检查点：

- 正式候选：`p0.97-0.99_off0.0_spread1.0_q20.0_c1_r300`。
- 24h：93 回合、0 结算亏损、PnL +97.87、ROI +1.052%、测试 ROI +1.058%。
- 8 窗口 ROI 范围：+1.033%..+1.164%；所有窗口最差市场 +1.011。
- 24h 费用：50bps +0.069%，100bps -0.914%。
- 尾部统计：93 个零亏损回合，rule-of-three 95% 上界 3.23%，仍高于 1.041% 盈亏平衡线；约需 289 个零亏损独立回合。

当前 Shadow：v6 目录 `backend/build/research/event-maker-safe-candidate-shadow-main-v6-20261020/` 于 00:53 启动。01:01 挂 `shadow-1` BUY 0.972 / 25.72（queue 15.05）；01:08 因 requote 撤销并挂 `shadow-2` BUY 0.971 / 25.74（queue 65.04）；01:13 再次 requote 撤销；01:21 bid 回到 0.972 后重挂 `shadow-3` BUY 0.972 / 25.72（queue 5.0）；01:26 `shadow-3` 再次 requote 撤销。截至 02:05 为 FLAT、0 fill、0 cycle。`logIssues=0`、`stderrBytes=0`。

下一执行顺序：

1. 用正式候选启动 Shadow 长测，保持 post-only、不降低活动门槛、不放宽价格带。
2. 轮询 `check-event-maker-watch.ps1`，运行结束或达到证据门槛后执行 `audit-event-maker-shadow.ps1`。
3. 检查真实 fill、部分成交、撤单竞态、markout、activeOrders=0 和重复成交。
4. 若 Shadow 通过，仍需用户提供账户、资金上限、明确授权、时间窗口和市场范围后才能做真实 Live smoke。
5. 在真实 Live smoke 通过前，不把目标标记完成，不使用回测/Shadow 冒充实盘。
### 22:55 提交与推送

- 提交：`93e4ccc feat: align event maker research with live execution`。
- 推送：`9fd75e5..93e4ccc main -> main`。
- 内容：数据快照/原子写入、严格抓取、回测最大挂单年龄一致性、8 窗口候选回测、参数邻域、Shadow 参数、全量 530 tests 和运行文档。
- 提交后 Shadow 继续运行，运行期间不重新编译。
### 10.12 2026-10-19 23:30 日亏损重启语义修复与 Shadow 重启

| ID | 验收项 | 状态 | 证据 |
|---|---|---|---|
| RISK-DAY-1 | 同日重启不得清零 `maxDailyLoss` | 已完成 | state v3 持久化 `dailyPnlDay` / `dailyPnlBaseline`；同日沿用锚点 |
| RISK-DAY-2 | UTC 跨日必须重置日亏损预算 | 已完成 | `EventMakerDailyPnlBudget.resolve`；跨日重锚到当前累计已实现 PnL |
| RISK-DAY-3 | 跨午夜成交不得被新日基线吞掉 | 已完成 | 对账、报价与停止清理在应用成交前同步日锚点；持久化只写已确定锚点 |
| RISK-DAY-4 | 状态文件必须 fail-closed 校验日锚点 | 已完成 | v2 state 被拒；缺失/非有限 day/baseline 被拒 |
| TEST-6 | 全量回归 | 已完成 | 103 suites / 538 tests / 0 failures / 0 errors / 2 skipped |

- v2 Shadow 于 22:51 启动，23:20 主动停止；运行 29 分钟、1 个市场、2 次 Shadow 挂单、1 次 requote、0 fill、无真实订单。
- 主动停止原因：发现同日重启会遗忘当天已实现亏损，可能在崩溃恢复后绕过日亏损上限。
- 修复后必须重启 v3 Shadow；v2 产物只作为“发现缺陷”的过程证据，不能作为最终 Shadow 验收。
- 真实 Live smoke 仍等待用户提供 `accountId`、资金上限、明确授权、时间窗口和市场范围。
### 10.13 2026-10-19 23:42 v3 Shadow 启动

- 提交 `c39b00a fix: persist daily loss budget across restarts` 已推送。
- v3 输出目录：`backend/build/research/event-maker-safe-candidate-shadow-main-v3-20261019/`。
- wrapper PID `23852`，Shadow Java PID `20988`，启动时间 `2026-10-19 23:41:45 +08:00`。
- 首两轮发现：qualified=2、activityPassed=0、selected=0；拒绝原因均为 `recent_trades_30m_below_min=2`。
- 当前：`state=waiting`、`logIssues=0`、`stderrBytes=0`。保持门槛，不调低活动要求。
### 10.14 2026-10-19 23:55 停机与估值边界收口

| ID | 验收项 | 状态 | 证据 |
|---|---|---|---|
| HALT-1 | 对账任务发现未知订单状态时必须唤醒主循环清理 | 已完成 | reconcile job 关闭 feed channel，进入统一 cancel/reconcile/persist |
| HALT-2 | 初始对账触发停机时不得继续挂单 | 已完成 | initial tick 检查 engine halted + daily loss，关闭 feed |
| MTM-1 | 无买盘持仓不得按 0 浮亏估值 | 已完成 | `markToMarket` 使用 bid=0 的最坏估值；新增单测 |
| CLOCK-1 | 系统时间回拨不得重置日亏损锚点 | 已完成 | 保留未来日锚点直到 UTC 时间追上；新增单测 |
| TEST-7 | 全量回归 | 已完成 | 103 suites / 538 tests / 0 failures / 0 errors / 2 skipped |

- v3 Shadow 停止前保持 waiting，5 轮 qualified=2、activityPassed=0、selected=0；未产生 Shadow 订单或真实订单。
- v2/v3 目录均保留为缺陷发现过程证据，不作为最终 Shadow 验收。
### 10.15 2026-10-20 00:04 v4 Shadow 启动

- 提交 `127dff3 fix: close event maker halt and mark-to-market gaps` 已推送。
- v4 输出目录：`backend/build/research/event-maker-safe-candidate-shadow-main-v4-20261020/`。
- wrapper PID `21352`，Shadow Java PID `17312`，启动时间 `2026-10-20 00:03:45 +08:00`。
- 首两轮发现：qualified=1、activityPassed=0、selected=0；原因 `sell_trades_30m_below_min=1` 和 `recent_trades_30m_below_min=1`。
- 当前：`state=waiting`、`logIssues=0`、`stderrBytes=0`。门槛未降低。
### 10.16 2026-10-20 00:20 初始下单阶段清理边界

| ID | 验收项 | 状态 | 证据 |
|---|---|---|---|
| EXEC-SETUP-1 | 初始对账下单后任何异常都必须进入统一撤单/对账 | 已完成 | try/finally 前移到 ws.start 之前，覆盖 setup、initial tick 和事件循环 |
| EXEC-SETUP-2 | 启动失败必须取消子任务并关闭运行资源 | 已完成 | 外层失败 catch 取消 runScope、关闭 feed/ws/data client |
| TEST-8 | 全量回归 | 已完成 | 103 suites / 538 tests / 0 failures / 0 errors / 2 skipped |

- v4 Shadow 于 00:20 主动停止；15 分钟内 11 轮严格发现均为 activityPassed=0、selected=0，未产生 Shadow 订单或真实订单。
- v5 必须在本次提交后重启；v4 不作为最终 Shadow 验收。
### 10.17 2026-10-20 00:22 v5 Shadow 启动

- 提交 `c570151 fix: cover initial live setup in cleanup` 已推送。
- v5 输出目录：`backend/build/research/event-maker-safe-candidate-shadow-main-v5-20261020/`。
- wrapper PID `3076`，Shadow Java PID `23220`，启动时间 `2026-10-20 00:21:30 +08:00`。
- 首轮 qualified=1、activityPassed=0、selected=0；原因 `recent_trades_30m_below_min=1`。
- 当前 waiting、logIssues=0、stderrBytes=0；门槛不变。
### 10.18 2026-10-20 00:50 Guard 审计语义与 Shadow 执行证据

| ID | 验收项 | 状态 | 证据 |
|---|---|---|---|
| SHADOW-E1 | 正式臂能在真实盘口选中市场 | 已完成 | v5 第 17 轮选中 Israel x Iran；bid 0.970 / ask 0.975、5 ticks、队列 6.87 |
| SHADOW-E2 | Guard 能阻止不利流挂单 | 已完成 | v5 出现 `flow_guard_blocked`，未产生活动订单 |
| SHADOW-E3 | Shadow 能完成挂单与 adverse-flow 撤单 | 已完成（实验臂） | `shadow-1` BUY 0.972 / 25.72 股，queue 5.74，5 秒后撤销 |
| AUDIT-E1 | 正常 Guard block 不得污染 fatal lastErrors | 已完成 | Shadow CLI 将 flow guard block 写入 `blocked`，不再写入 `lastErrors` |
| AUDIT-E2 | 审计仍拒绝非预期 lastErrors | 已完成 | fixture：flow_guard_blocked 通过；fatal_state_error 非零失败 |
| TEST-9 | 全量回归 | 已完成 | 103 suites / 538 tests / 0 failures / 0 errors / 2 skipped |

- 实验臂仅将 `minSpreadTicks` 改为 1，不进入正式验收，也不得作为放宽正式门槛的理由。
- v5 正式臂与实验臂均在 00:50 主动停止；v6 使用修复后的最终代码重启。
- 实验臂期间出现 Data API/WS 短暂握手失败，随后自动重连并恢复正常；该外部瞬态不作为策略状态错误，但需在最终审计中单独记录。
### 10.19 2026-10-20 00:54 v6 Shadow 启动

- 提交 `e321f4a fix: separate expected shadow guard blocks from errors` 已推送。
- v6 输出目录：`backend/build/research/event-maker-safe-candidate-shadow-main-v6-20261020/`。
- wrapper PID `1252`，Shadow Java PID `22320`，监控 PID `8120`，启动时间 `2026-10-20 00:53:09 +08:00`。
- 首轮 qualified=2、activityPassed=0、selected=0；原因 `recent_trades_30m_below_min=2`。
- 当前 waiting、logIssues=0、stderrBytes=0；门槛不变。此后不再改代码，等待 Shadow 结果。
### 10.20 2026-10-20 01:20 v6 正式 Shadow 执行链路

| ID | 验收项 | 状态 | 证据 |
|---|---|---|---|
| SHADOW-E4 | 正式候选首次真实盘口挂单 | 已完成 | `shadow-1` BUY 0.972 / 25.72，queue 15.05 |
| SHADOW-E5 | 正式候选 requote 撤单与重挂 | 已完成 | `shadow-2` BUY 0.971 / 25.74，queue 65.04；两次 requote 撤单 |
| SHADOW-E6 | 价格跌出区间后不追单 | 已完成 | 当前 bid 0.966 < minPrice 0.97，状态 FLAT |
| SHADOW-E7 | 正式候选真实 fill/cycle | 未完成 | 当前 0 fill、0 cycle；继续等待 |
| SHADOW-E8 | 价格回归后第三次重挂 | 已完成 | 01:21 bid 回到 0.972；`shadow-3` BUY 0.972 / 25.72，queue 5.0；未放宽门槛 |

- v6 运行期间 `logIssues=0`、`stderrBytes=0`。
- 未放宽价格带、活动门槛、post-only、队列或亏损限制。
- 只有出现真实 Shadow fill、SELL/SETTLE cycle 和 markout 后，才可进入下一步 Live 授权讨论。

### 10.21 2026-10-20 02:05 结算与赎回闭环补强

| ID | 验收项 | 状态 | 证据 |
|---|---|---|---|
| LIVE-A14 | Gamma 终局 0/1 结算识别 | 已完成 | 终局每 30s 轮询；覆盖提前结算、endDate 仍在未来的市场 |
| LIVE-A15 | SETTLE cycle 与 PnL | 已完成 | 结算账本、cycle 重放、schema v4；定向测试 |
| LIVE-A16 | 赎回前持久化与崩溃恢复 | 已完成 | pending settlement 先落盘；启动时重试/确认已赎回 |
| LIVE-A17 | Builder API Key 启动前置检查 | 已完成 | 未配置时 `settlement_builder_api_key_missing` |
| LIVE-A18 | discovery 不再返回的持仓恢复 | 已完成 | 持久化 market 元数据，重建仅结算市场 |
| TEST-10 | 全量回归 | 已完成 | 103 suites / 555 tests / 0 failures / 0 errors / 2 skipped |

- v6 Shadow 已三次挂单、三次 requote 撤单；`shadow-3` 于 01:26 撤销，当前 FLAT、0 fill，仍不满足真实成交验收。
- 结算/赎回闭环已完成代码与 mock/engine/startup 级验证。
- 尚未用真实账户、真实 Builder API Key 和真实资金执行赎回 smoke，因此还不能把“真实结算到账”标记为完成。