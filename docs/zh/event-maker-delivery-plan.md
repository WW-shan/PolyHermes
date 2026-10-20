# Event Maker 策略交付计划与验收矩阵

> 创建时间：2026-10-18 23:37 +08:00<br>
> 目标：按计划完整实现事件做市策略，完成回测、影子交易和实盘执行链路的可验证测试，直到没有已知阻断问题。<br>
> 当前状态：**回测与执行链路审计完成；全量测试 103 suites / 561 tests 通过；BOND 全价格带复核完成：0.90–0.95 明确负期望，0.95–0.99 只有边界性正期望，4.10% 不能当作稳定收益；与目标账户对账后，0.95–0.97 队列回测约为其已平仓 ROI 的 18 倍，当前 BOND 只是理想化假设模型，不是已验证复刻；逐笔库存账本显示目标账户 matched ROI +0.748%，edge 分布在多价格带，不是纯 bond carry。BOND 2h Shadow 已完成（5 市场、5 挂单、5 撤单、0 fill、0 错误）；全量测试通过；7 天 Shadow 已启动（8 市场、flowGuard=0）。真实小资金 Live smoke 仍缺账户、资金和明确授权，不能实盘**<br>
> 最后更新：2026-10-21 00:05 +08:00<br>
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
| 策略核心单元测试 | 已完成 | 全量 103 suites / 561 tests 通过；新增日锚点、时钟回拨、无买盘估值、结算账本和状态 schema 回归 |
| Shadow Spring 服务 | 已完成，统一 Shadow/Live 引擎 | `EventMakerStrategyService.kt`、`EventMakerLiveEngine.kt` |
| Live CLOB 执行适配器 | 已完成，mock/集成验证通过 | `ClobEventMakerExecutionAdapter.kt` |
| REST 控制接口 | 已完成 | `/api/event-maker/*` |
| Live 订单对账 | 已完成强化：状态损坏/错账户/未登记订单与持仓/部分成交/停止终态均 fail-closed | `EventMakerStateStore.kt`、`EventMakerLiveState.kt`、`EventMakerLiveEngine.kt` |
| 2 小时 Shadow 长测 | 旧基线稳定性通过；策略 0 fill | `event-maker-shadow-2026-10-19.md` |
| 旧被动 2h/12h Shadow | 2h 因类版本冲突无效；12h 已停止；均 0 fill | `backend/build/research/event-maker-shadow-passive-2c-*` |
| 新版保守 Shadow 冒烟 | 10 分钟 0 fill、0 错误；1 分钟停止复测 activeOrders=0 | `backend/build/research/event-maker-shadow-v2-smoke*-20261019/` |
| 新干净长测 | v2 等待 12 轮无活动通过市场，已因安全修复停止；提交后重启 | `event-maker-shadow-watch-v2-20261019/`；不降低价格带 |
| 全量测试 | 103 suites / 561 tests / 0 failures / 0 errors / 2 skipped | `backend/build/test-results/test/` |
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
- [ ] ~~正式候选通过 12h/1d/2d/3d/4d/5d/6d/7d 八窗口回测；全部零结算亏损，训练/测试为正。~~ **已推翻（2026-10-20）**：旧八窗口漏传活动门槛；补门槛后 1d 出现 1 次结算亏损、ROI -0.459%、测试 -0.808%，八窗口全通过配置为 0。
- [ ] ~~训练期唯一选参、费用压力、队列压力、MAE、跨窗口和参数邻域分析完成。~~ **需按活动门槛口径重做**；旧分析基于无门槛产物。
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
- 当前 Shadow：v6 目录 `backend/build/research/event-maker-safe-candidate-shadow-main-v6-20261020/` 于 00:53 启动。01:01 挂 `shadow-1` BUY 0.972 / 25.72（queue 15.05）；01:08 因 requote 撤销并挂 `shadow-2` BUY 0.971 / 25.74（queue 65.04）；01:13 再次 requote 撤销；01:21 bid 回到 0.972 后重挂 `shadow-3` BUY 0.972 / 25.72（queue 5.0）；01:26 `shadow-3` 再次 requote 撤销。02:21 重挂 `shadow-4` BUY 0.970 / 25.77（queue 25.0），02:30 requote 后挂 `shadow-5` BUY 0.971 / 25.74（queue 20.03），02:35 requote 后挂 `shadow-6` BUY 0.970 / 25.77（queue 485.03），02:56 再次 requote 撤销；03:23 挂 `shadow-7` BUY 0.973 / 25.69（queue 6.18），03:28 requote 撤销；03:45 挂 `shadow-8` BUY 0.970 / 25.77（queue 105.70），03:50 requote 撤销。截至 04:55 为 FLAT、0 fill、0 cycle。`logIssues=0`、`stderrBytes=0`。

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
| SHADOW-3 | 正式候选 Shadow 长测 | v6 运行中 | 已挂 8 次 Shadow BUY、8 次 requote 撤单；`shadow-8` 于 03:50 撤销；当前 FLAT、0 fill |
| LIVE-A13 | 日亏损跨重启持久化 | 已完成 | UTC day + baseline 写入 state v3；同日重启沿用，跨日重锚；8 条新增回归 |
| LIVE-1 | 真实小资金 Live smoke | 未执行 | 缺账户、资金、明确授权、时间窗口、市场范围 |

当前检查点：

- 正式候选：`p0.97-0.99_off0.0_spread1.0_q20.0_c1_r300`。
- 24h：93 回合、0 结算亏损、PnL +97.87、ROI +1.052%、测试 ROI +1.058%。
- 8 窗口 ROI 范围：+1.033%..+1.164%；所有窗口最差市场 +1.011。
- 24h 费用：50bps +0.069%，100bps -0.914%。
- 尾部统计：93 个零亏损回合，rule-of-three 95% 上界 3.23%，仍高于 1.041% 盈亏平衡线；约需 289 个零亏损独立回合。

当前 Shadow：v6 目录 `backend/build/research/event-maker-safe-candidate-shadow-main-v6-20261020/` 于 00:53 启动。01:01 挂 `shadow-1` BUY 0.972 / 25.72（queue 15.05）；01:08 因 requote 撤销并挂 `shadow-2` BUY 0.971 / 25.74（queue 65.04）；01:13 再次 requote 撤销；01:21 bid 回到 0.972 后重挂 `shadow-3` BUY 0.972 / 25.72（queue 5.0）；01:26 `shadow-3` 再次 requote 撤销。02:21 重挂 `shadow-4` BUY 0.970 / 25.77（queue 25.0），02:30 requote 后挂 `shadow-5` BUY 0.971 / 25.74（queue 20.03），02:35 requote 后挂 `shadow-6` BUY 0.970 / 25.77（queue 485.03），02:56 再次 requote 撤销；03:23 挂 `shadow-7` BUY 0.973 / 25.69（queue 6.18），03:28 requote 撤销；03:45 挂 `shadow-8` BUY 0.970 / 25.77（queue 105.70），03:50 requote 撤销。截至 04:55 为 FLAT、0 fill、0 cycle。`logIssues=0`、`stderrBytes=0`。

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
| SHADOW-E9 | 多轮 requote 后持续重挂 | 已完成 | 02:21–02:35 依次挂 `shadow-4` 0.970/25.77、`shadow-5` 0.971/25.74、`shadow-6` 0.970/25.77 |
| SHADOW-E10 | 高队列 + q20 确认仍不降门槛 | 已完成 | `shadow-6` queue 485.03；02:56 撤销；0 fill，`shadowFillVolumeFactor` 保持 20 |

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
| LIVE-A19 | 部分卖出后结算的 PnL 与周期股数 | 已完成 | cycle 记总买入股数；settlement 记剩余可赎回股数；恢复重放一致 |
| TEST-10 | 全量回归 | 已完成 | 103 suites / 555 tests / 0 failures / 0 errors / 2 skipped |

- v6 Shadow 已三次挂单、三次 requote 撤单；`shadow-3` 于 01:26 撤销，当前 FLAT、0 fill，仍不满足真实成交验收。
- 结算/赎回闭环已完成代码与 mock/engine/startup 级验证。
- 尚未用真实账户、真实 Builder API Key 和真实资金执行赎回 smoke，因此还不能把“真实结算到账”标记为完成。

### 10.22 2026-10-20 03:05 v6 继续重挂但仍 0 fill

| 时间 | 订单 | 价格/股数 | queue | 结果 |
|---|---|---|---|---|
| 02:21:32 | `shadow-4` | 0.970 / 25.77 | 25.0 | 02:30:30 requote 撤销 |
| 02:30:30 | `shadow-5` | 0.971 / 25.74 | 20.03 | 02:35:31 requote 撤销 |
| 02:35:32 | `shadow-6` | 0.970 / 25.77 | 485.03 | 02:56:19 requote 撤销 |

- v6 累计 6 次挂单、6 次 requote 撤单，当前 FLAT、0 fill、0 cycle、0 inventory。
- `logIssues=0`、`stderrBytes=0`；未放宽价格带、活动门槛、post-only、队列或亏损限制。
- 正式启动参数为 `maxQueueAheadShares=100000`、`shadowFillVolumeFactor=20`；`shadow-6` queue 485.03 仍未成交，不能降低 q20 成交确认量来追求 fill。
### 10.23 2026-10-20 03:10 v6 0 fill 复算结论

- 六笔订单中只有 `shadow-5` 在存活期内遇到可穿越 SELL：215.00 股。
- 队列消耗 20.03 后剩 194.97 股；q20 需要 25.74 × 20 = 514.80 股，因此仍未成交。
- 其余五笔订单没有可穿越 SELL 成交。
- 结论：0 fill 与正式保守成交模型一致；不得降低 `shadowFillVolumeFactor`、放宽价格带或追价来伪造成交。

### 10.24 2026-10-20 03:45 部分卖出后结算记账修复

- 审计发现：买入后先部分卖出、剩余仓位再到期结算时，旧实现只把剩余仓位的结算 PnL 和股数写入 cycle，漏掉之前部分卖出的已实现 PnL，也会把 cycle.shares 记成剩余股数。
- 修复后：`settlement.shares` 仍是链上可赎回的剩余股数；`cycle.shares` 是完整周期的总买入股数；`cycle.pnl` = 部分卖出已实现 PnL + 剩余仓位结算 PnL；`realizedPnl` 只累加尚未计入的结算 PnL，避免重复记账。
- 状态恢复与审计回放使用同一语义，并校验 `已卖出股数 + settlement.shares == 总买入股数`。
- `audit-event-maker-shadow.ps1` 已支持 SETTLE cycle，校验 settlement 身份、outcome、negativeRisk、结算 PnL 和 cycle 期望值；同时修复了 fill-analysis 告警被重复初始化覆盖的问题。
- 临时正反例已验证：正常部分卖出后结算通过；篡改 settlement PnL 或 cycle 股数会被拒绝；临时 fixture 已删除。
- 隔离 worktree 全量回归：103 suites / 556 tests / 0 failures / 0 errors / 2 skipped。
- v6 Shadow 在 03:38 仍正常等待，6 次挂单、0 fill、0 cycle、0 inventory；没有放宽任何正式门槛。真实 Builder Relayer、链上赎回和到账仍未验证。

### 10.25 2026-10-20 03:50 v6 新增 shadow-7 / shadow-8

| 时间 | 订单 | 价格/股数 | queue | 结果 |
|---|---|---|---|---|
| 03:23:44 | `shadow-7` | 0.973 / 25.69 | 6.18 | 03:28:44 requote 撤销 |
| 03:45:38 | `shadow-8` | 0.970 / 25.77 | 105.70 | 03:50:39 requote 撤销 |

- v6 累计 8 次挂单、8 次 requote 撤单；`shadow-7` 和 `shadow-8` 均未产生 fill。
- 当前 FLAT、activeOrders=0、fills=0、cycles=0、inventory=0；`logIssues=0`、`stderrBytes=0`。
- 仍保持 post-only、正式价格带、活动门槛、q20 和队列限制，没有为了成交放宽任何参数。

### 10.26 2026-10-20 04:05 结算/赎回瞬态失败计数修复

- 结算快照和赎回持仓查询原先只在最终成功时清零失败计数；一次成功读取不足以证明链路恢复，后续少量瞬态失败可能误触发停机。
- 现在只要成功读取到 settlement snapshot，就清零 `settlementFailures`；只要成功读取到 redemption position，就清零 `redemptionFailures`。
- 新增两条隔离回归：结算快照“2 次失败 -> 成功未决议 -> 2 次再失败”不 halt；赎回持仓“2 次失败 -> 成功不可赎回 -> 2 次再失败”不 halt。
- 隔离 worktree 全量回归：103 suites / 558 tests / 0 failures / 0 errors / 2 skipped。
- v6 在 04:02 仍正常运行，当前 FLAT、0 fill、0 cycle、0 inventory，`logIssues=0`、`stderrBytes=0`。
### 10.27 2026-10-20 04:15 结算身份与赎回回执核验

- `getSettlementSnapshot` 现在使用已发现市场的 `negRisk` 校验账户持仓，不再接受账户数据与市场元数据不一致的仓位。
- `redeemSettlement` 不再只看交易哈希：必须从 AccountService 回执中找到相同 `marketId`、`outcomeIndex`、且 `quantity == settlement.shares` 的赎回记录。
- 新增两条 adapter 反例：negRisk 不匹配拒绝读取；赎回数量不匹配拒绝标记 REDEEMED。
- 隔离 worktree 全量回归：103 suites / 560 tests / 0 failures / 0 errors / 2 skipped。
- 这仍是 mock/回执结构级验证；真实 Builder Relayer、真实链上回执和 pUSD 到账必须由小资金 Live smoke 证明。
### 10.28 2026-10-20 04:45 审计脚本旧产物兼容

- 旧版 `execution-diagnostics.json` 没有 `fillVolumeFactor` 字段；审计脚本原先回退为 `-1`，会被成交复核脚本拒绝。
- 现在缺字段时回退为保守默认 `1.0`，只用于读取历史证据；正式 Shadow 仍由 `watch-process.json` 提供 q20 factor。
- 对 `backend/build/research` 下全部旧 Shadow 目录做了一轮回归：最终干净产物通过；早期中断目录因 `activeOrders` 非零仍按预期失败。
- v6 在 04:44 仍正常运行，FLAT、0 fill、0 cycle、0 inventory，`logIssues=0`、`stderrBytes=0`。
### 10.29 2026-10-20 04:55 Shadow 逐单成交股数校验

- 审计原先只比较“模型应成交订单数”和“实际有成交订单数”；整单成交模型与部分成交可能数量相等而漏检。
- 现在逐单校验：模型整单成交时，实际成交股数必须等于订单原始股数；实际超过模型或超出订单股数直接失败；模型未成交但实际有成交记为告警。
- 用合成 fixture 验证：模型 full、实际 0 时审计失败并输出 `fillModelShareMismatch`；临时 fixture 已清理。
- v6 仍以正式 q20 运行，当前 0 fill；该改动只增强审计，不改变策略门槛。
### 10.30 2026-10-20 05:10 已赎回状态必须带交易哈希

- `EventMakerStateStore` 现在拒绝 `REDEEMED` 但没有 `transactionHash` 的 settlement 记录。
- 新增 `EventMakerStateStoreTest` 反例：已赎回结算缺失哈希时保存失败，错误为 `settlement_values_invalid`。
- 隔离 worktree 全量回归：103 suites / 561 tests / 0 failures / 0 errors / 2 skipped。
- 这保证重启后不会出现“本地认为已赎回、但没有链上交易可审计”的状态。

### 10.31 2026-10-20 07:00-08:30 活动门槛口径复核（推翻旧八窗口结论）

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| RECHECK-1 | 修正回测 markout 时间单位（秒 vs 毫秒） | 已完成 | `dde0131 fix: use second-based markout horizon in backtest`；新增回归测试；103 suites / 562 tests 通过 |
| RECHECK-2 | 用数据集直接复算真 60s markout | 已完成 | 278 回合真值 -1.019 均值（旧 +0.392）；93 回合候选真值 -0.358 均值 |
| RECHECK-3 | 定位旧八窗口活动门槛缺失 | 已完成 | 无门槛重跑与旧文件逐行一致（93 回合、408 Guard）；带门槛 1d 为 68 回合、1 亏损 |
| RECHECK-4 | 带正式门槛重跑 12h–7d 八窗口 | 已完成 | 1d 窗口 ROI -0.459%、测试 -0.808%；八窗口全通过配置 0 个 |
| RECHECK-5 | 止损可行性复核 | 已完成 | -2c 止损触发 125 次、122 次 whipsaw，总 PnL 由 +224 变 -356；不能靠止损解决尾部 |
| RECHECK-6 | Shadow 经济性结论重评 | 阻塞待证据 | 旧“零结算亏损”不成立；需在正确门槛口径下重新确定候选或放弃 |

- 新报告：`docs/zh/event-maker-activity-gate-recheck-2026-10-20.md`。
- 旧候选报告第 3–4 节与“可进入 Shadow”结论已在文首标记作废。
- v6 Shadow 继续运行，仅作为执行链路证据；它不能补足经济性验证。
- 正式门槛（价格带、活动门槛、post-only、q20、队列、亏损限制）一条都没有放宽。

### 10.32 2026-10-20 08:30-09:00 结算前尾部窗口规则（部分缓解，不是通解）

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| TAIL-1 | 回测支持 `minRemainingSeconds`：剩余不足时撤 BUY 且不再新挂 | 已完成 | 成交判定前撤单；两条回归测试 |
| TAIL-2 | Live/Shadow 支持 `tailWindowMinutes`，触发原因 `tail_window` | 已完成 | 活跃 BUY 撤单 + FLAT 禁止新挂；两条 LiveEngine 测试；全量 103 suites / 566 tests |
| TAIL-3 | 1d 阈值扫描 2h/4h/6h/8h/12h | 已完成 | 6h 更差（-0.576%）；8h +1.029%；12h +1.024% |
| TAIL-4 | 8h 规则八窗口重跑 | 已完成 | 八窗口 0 结算亏损、ROI +1.025%..+1.076%、训练/测试均为正 |
| TAIL-5 | 参数族效果复核 | 已完成 | 12h/1d/2d 亏损配置数分别 -201/-205/-144；3d/4d/6d 无改善 |
| TAIL-6 | 8h 规则前瞻 Shadow | 待启动 | v6 结束后用 `shadowTailWindowMinutes=480` 启动 v7 |
| TAIL-7 | 证明尾部风险已消除 | 未完成 | 只覆盖少量独立尾部事件；8h 阈值紧邻唯一亏损成交时点（6.65h），存在过拟合风险 |

- 报告：`docs/zh/event-maker-tail-window-2026-10-20.md`。
- 候选在 8h 规则下八窗口全绿，但 6/8 个窗口训练成交数 <20（12h 仅 4）；不能按旧“训练成交 ≥20”口径宣称通过。
- 规则是事前不报价，不是止损；SELL 退出不受影响。
- 正式门槛（价格带、活动门槛、post-only、q20、队列、亏损限制）没有放宽。

### 10.33 2026-10-20 09:10 结构性 EV 复核

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| EV-1 | 统计 8 个入场窗口的 favorite 基础失败率 | 已完成 | 1.60%–2.85%（7d 为 0%，仅 216 市场） |
| EV-2 | 对比每回合收益与盈亏平衡亏损率 | 已完成 | 盈亏平衡约 1.01%；7/8 窗口无条件 EV 为负 |
| EV-3 | 复核短样本“0 亏损”的统计意义 | 已完成 | 1d：n=57、p=1.60%，P(0 亏损)≈39.9% |
| EV-4 | 找到能把成交池失败率压到 1% 以下的事前筛选 | 未完成 | 这是继续该策略方向的硬前提 |
| EV-5 | 或证明更宽退出价差能提高净收益且不增加结算暴露 | 进行中 | 宽退出网格（活动门槛 + 8h 尾部规则）运行中 |

- 结论：当前策略族是结构性负期望，参数调优不能解决。
- 报告：`docs/zh/event-maker-tail-window-2026-10-20.md` 第 9 节。

### 10.34 2026-10-20 09:20 成交假设稳健性终判

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| FILL-1 | 宽退出 1,440 配置 × 8 窗口重跑 | 已完成 | 全部带活动门槛 + Guard + 8h 尾部规则 |
| FILL-2 | 按队列因子做跨窗口交叉 | 已完成 | q1 全过 7；q2/q5/q10 最好 6；q20 最好 3；q50 为 0 |
| FILL-3 | 判定生产 q20 是否有稳健配置 | 已完成 | **没有**；q20 最好 3/7 |
| FILL-4 | 用真实 Shadow 成交校准 fill factor | 阻塞 | v6 当前 0 fill，没有校准样本 |
| FILL-5 | 找到把成交池失败率压到 1% 以下的事前筛选 | 未完成 | 基础失败率 1.60%–2.85%，需要可解释的筛选 |
| FILL-6 | 更换收益结构（对冲/分批/非二元尾部） | 未开始 | 需要新的策略设计 |

- 结论：**当前策略族不能实盘，也不应继续在 0.97–0.99 内调参。**
- 报告：`docs/zh/event-maker-fill-robustness-verdict-2026-10-20.md`。

### 10.35 2026-10-20 10:30 容量与经济性终判

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| CAP-1 | 计算高价带年化交易频率与 EV | 已完成 | 35.7 回合/年，EV 0.1382 USDC/回合（100 名义本金） |
| CAP-2 | 计算不同资金规模下的年化 | 已完成 | 10,000 资金、1% 单损上限 → 年化 0.049% |
| CAP-3 | 判断是否值得投入真实资金 | 已完成 | **不值得**；需约 203 倍 edge/仓位才能达到 10% 年化 |
| CAP-4 | 结论 | 已完成 | Event Maker 两个价格带均不适合真实资金 |

- 这是研究结论，不是实现缺陷；继续调参会重蹈“看起来正期望、实际无容量”的覆辙。
- 下一步若继续，应更换策略结构（对冲、分批、非二元尾部、或不同市场类型），而不是继续调 0.97–0.999 的价格带。

### 10.36 2026-10-20 10:35 流动性奖励复核：0.90–0.95 是唯一待验证区间

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| RWD-0 | 按官方公式实现奖励份额快照工具 | 已完成 | `scripts/analyze-event-maker-liquidity-rewards.mjs`；扫描 2,000 个 active 市场，识别 692 个奖励市场，抓取 500 个高奖励池双边 book |
| RWD-1 | 量化奖励是否能救活 0.97–0.99 单边 maker | 已完成 | 当前 0.97–0.99 无活跃奖励池；0.90+ 奖励集中在 0.905–0.955；原策略不能靠奖励转正 |
| RWD-2 | 量化 0.90–0.95 paired-bid 收益与容量 | 已完成 | 过滤后 7 个市场：日池 662、估计日奖励 0.2266、资金 267.36、日收益 0.0847%；简单年化约 30.9%，但样本小且市场很快结算 |
| RWD-3 | 将奖励接入正式 Shadow 路径 | 待开始 | 必须先实现 Kotlin reward calculator；当前只有研究脚本 |
| RWD-4 | 实现 paired-bid + 单边成交自动 IOC hedge | 待开始 | 必须证明单边成交后不留下未对冲库存，并记录 hedge 滑点/延迟 |
| RWD-5 | 0.90–0.95 前瞻 Shadow 72h | 待开始 | 覆盖至少 7 个奖励市场；0 未处理错误；统计实际/估计奖励偏差 |
| RWD-6 | 决定是否小资金实盘 | 阻塞 | 需 RWD-3～RWD-5 通过，且用户提供 accountId、资金上限、时间窗口、明确授权 |

- 报告：`docs/zh/event-maker-liquidity-rewards-2026-10-20.md`。
- 目标地址 446 天公开奖励合计 4,195.28 USDC；最近 30 天 70.35、最近 7 天 5.49，当前公开仓位约 20,084 USDC。奖励只占其收益的小部分。
- **禁止**因为奖励而放宽 0.97–0.99 正式门槛，也**禁止**直接上 paired-bid 实盘；当前只是存在收益结构，尚未证明单边成交与对冲成本可控。

### 10.37 2026-10-20 11:05 奖励计算器与配对报价器落地

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| RWD-CALC-1 | 官方 `S(v,s)`、`Q_one`、`Q_two`、`Q_min` Kotlin 实现 | 已完成 | `LiquidityRewardCalculator.kt`；测试覆盖高低价格带、单边/双边、最小 size、边界 0.10/0.90 |
| RWD-CALC-2 | Gamma 奖励字段进入研究市场模型 | 已完成 | `LivePaperMarket` 新增 `rewardDailyRate`、`rewardsMinSize`、`rewardsMaxSpread`、`holdingRewardsEnabled`；`LivePaperDataClient` 按 UTC 日期过滤 active allocation |
| RWD-CALC-3 | 双边 post-only 报价规划器 | 已完成 | `PairedRewardQuotePlanner.kt`；确保两边 size 达到奖励门槛、价格不穿 ask、配对成本可配置上限 |
| RWD-CALC-4 | 回归测试 | 已完成 | 105 suites / 578 tests / 0 failures / 0 errors / 2 skipped |
| RWD-SHADOW-1 | 将 paired planner 接入真实 Shadow 循环 | 待开始 | 需要同时订阅 YES/NO book、双订单账本、成交后 IOC hedge |
| RWD-SHADOW-2 | 单边成交自动对冲与库存上限 | 待开始 | 未完成前不得启用实盘 |
| RWD-SHADOW-3 | 0.90–0.95 前瞻 Shadow 72h | 待开始 | 7 个候选市场，记录 reward 估计/实际偏差、hedge 滑点、单边成交次数 |

- 这些代码只新增研究/规划能力，未改变 v6 正在运行的单边策略行为，也未提交任何真实订单能力。
- 下一步不是直接实盘，而是实现双 token Shadow；没有单边对冲证据时，reward 收益不能覆盖尾部风险。

### 10.38 2026-10-20 11:45 配对奖励 Shadow：当前市场结构性不可行

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| RWD-SHADOW-1 | 双 token WebSocket + 双边 post-only 模拟报价 | 已完成 | `PairedRewardShadowCli.kt`；7 个市场、14 个 token 订阅；报价/requote/成交/对冲全部落盘 |
| RWD-SHADOW-2 | 单边成交后 grace / 立即 IOC hedge | 已完成 | 两轮 smoke 均执行对冲；5 秒宽限净 -2.3954，立即对冲净 -1.6386，均远差于奖励 |
| RWD-SAFE-1 | 事前将立即对冲成本纳入选择 | 已完成 | `maxHedgePairCostPerShare` + `hedgeSlippageCents`；1.0 成本 + 0.2c 滑点下 87 个候选全部拒绝 |
| RWD-SHADOW-3 | 0.90–0.95 前瞻 Shadow | 已完成（失败判定） | 自然配对 0 次；两次均只出现单边成交后对冲；奖励 0.0068/0.2100，对冲亏损 -2.4022/-1.8486 |
| RWD-LIVE-1 | 小资金实盘 | 阻塞且禁止 | 没有通过安全对冲门槛的市场；不得用实盘验证已被 smoke 证伪的结构 |

- 报告：`docs/zh/event-maker-paired-reward-shadow-2026-10-20.md`。
- 典型盘口：YES 0.903/0.909、NO 0.091/0.097；bid 配对 0.994，但单边成交后买对手 ask + 滑点约 1.006，奖励无法覆盖。
- 结论：**0.90–0.95 paired reward farming 在当前市场条件下不成立；0.97–0.99 单边 maker 也不成立。**
- 下一步只有在“自然配对率显著提高”“盘口对冲成本 <=1.0”“改变收益结构”之一有真实数据后才重开，不再继续调价格带/宽限时间。
## 11. 2026-10-20 12:10 完整集套利检查点

### 11.1 已完成

1. `scan-event-complete-set-arbitrage.mjs` 已支持 Binary 与 NegRisk 完整集扫描。
2. 已加入手续费、逐档深度、最小下单量、数量精度、盘口过期、交叉盘口、事件完整性、gas buffer 和多腿风险标记。
3. 15 个 Node 测试通过。
4. Politics + Geopolitics 全量快照完成：821 组，582 组有效，0 个扣费后可执行机会。
5. 连续监控器完成并通过 15 秒 smoke。
6. 报告：`event-maker-complete-set-arbitrage-2026-10-20.md`。

### 11.2 当前结论

- 完整集/NegRisk 点快照无可用机会。
- 最低 top ask sum 0.983 的候选在 fee 后为负，不能用于实盘。
- 继续禁止真实资金。

### 11.3 下一步执行顺序

1. v6 Shadow 于 12:53 左右结束后运行审计，保存证据并更新台账。
2. 启动完整集/NegRisk 24 小时连续监控，记录机会持续时间与二次确认结果。
3. 若 24 小时为 0，扩展 Sports/Crypto/Finance 扫描，研究不同 fee curve 和盘口结构。
4. 研究关联市场约束（嵌套日期、范围市场、资格条件），不重复调 0.97–0.99 maker 参数。
5. 只有出现连续可确认、扣费后至少 0.5c/share、且多腿失败损失可界的结构，才进入 mock 执行和最小资金 Live smoke。

### 11.4 实盘阻塞项

仍需要用户提供 `accountId`、资金上限、明确授权、运行时间窗口和市场范围；在上述条件满足前只允许 Shadow/mock/离线回测。
## 12. 2026-10-20 13:30 修正后检查点

### 12.1 v6 Shadow 审计

- v6 运行 12 小时正常结束。
- 8 次挂单、8 次撤单、0 fill、0 cycle、0 PnL。
- `shadow-audit.json` `passed=true`，没有 log/stderr/duplicate 问题。
- 由于没有 fill，v6 不能作为 maker edge 验证。

### 12.2 完整集修正

- 修复最优数量算法：从“累计利润仍大于 0 的最大数量”改为逐档边际利润最优化。
- 修正后 Politics + Geopolitics 全量扫描出现 1 个真实 NegRisk 候选：
  - `Who wins the Senate pending Hormuz Agreement by Oct 23?`
  - 3 outcomes，top ask sum 0.987；
  - 310 shares，净利润 1.63 USDC，0.525c/share；
  - 1 秒后连续 3 次复核为 0.352c/share；
  - 低于 0.5c 正式门槛，禁止实盘。
- 24h 监控 v3 正在运行：
  - PID `22668`；
  - output `backend/build/research/complete-set-monitor-24h-v3-20261020`；
  - watchlist 30，poll 15 秒，discovery 10 分钟；
  - Hormuz 候选持续出现，但当前仍为 `below_edge_buffer`。

### 12.3 日期单调性

- 新增同事件日期单调性扫描器。
- 修正两个假信号：
  - `on DATE` 的独立日市场不是 `by DATE` 嵌套市场；
  - `continues through DATE` 是持续/否定事件，方向与“X happens by DATE”相反。
- 最终 Politics + Geopolitics 快照：327 pairs，0 正收益，最低 top ask sum 1.015。

### 12.4 跨类别

- Crypto + Finance：851 个完整评估，2 个正收益但盘口均 stale，0 actionable。
- Sports：410 个完整评估，0 正收益。
- 跨类别连续监控尚未启动。

### 12.5 下一步

1. 等完整集 24h 监控产出：
   - Hormuz 候选出现频率；
   - 持续时间；
   - 二次确认通过率；
   - 最大可成交容量。
2. 对 0.35–0.50c 的候选不做实盘，不用降低门槛换成交。
3. 如果 24h 仍无可确认机会，启动 Crypto/Finance 与日期单调性连续监控。
4. 研究三腿非原子执行的失败成本；在无法界定前不进入真实资金。
5. 实盘仍需要用户提供账户、资金上限、明确授权、时间窗口和市场范围。
## 13. 2026-10-20 13:55 当前策略状态

- complete-set ask-side 已有 1 个真实候选，二次确认后 0.60909c/share，约 3.02 USDC 理论利润。
- NegRisk 全 NO 方向已实现并扫描，当前无机会。
- 24h 监控 v4 正在运行；当前只有一次确认，不能外推为稳定策略。
- 仍禁止实盘。下一步必须先做三腿非原子执行与失败回滚模型，再决定是否需要真实小额 smoke。
## 14. 2026-10-20 14:35 非原子执行风险与 Mock 闭环

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| CSA-19 | 完整集支付矩阵与部分成交风险模型 | 已完成 | `complete-set-basket-risk.mjs`；YES/NO payout、0/25/50/75/100% fill 穷举 |
| CSA-20 | 最坏损失、资金上限与亏损预算 | 已完成 | 分离 `maxLossUsd` / `maxCapitalUsd`；非穷举场景 fail-closed |
| CSA-21 | 便宜腿优先的顺序执行计划 | 已完成 | 按实际每股成本升序，失败后停止并回滚 |
| CSA-22 | Mock basket executor | 已完成 | 完整成交、部分成交、撤单、平仓、残库存、预检拒绝全部有测试 |
| CSA-23 | 实时 Hormuz 风险复核 | 已完成 | top ask sum 0.99；完整 PnL -1.541957；最坏观测损失 40.9809；reject |
| CSA-24 | 风险指标进入扫描与监控 | 已完成代码 | ask/noBasket 结果带 capital/loss/worst fills；确认统计增加通过率和 episode 时长 |
| TEST-CSA-RISK | Node 回归 | 已完成 | 50 tests / 50 pass / 0 fail |
| CSA-25 | 24h 监控完成审计 | 运行中 | v4 PID 15632；poll 92；lastPollErrors 0；confirmed=1 |
| LIVE-1 | 真实小额 Live smoke | 阻塞 | 当前候选完整成交已为负，且无账户、资金、授权、时间窗口 |

- 报告：`docs/zh/event-maker-complete-set-execution-risk-2026-10-20.md`。
- 执行链路本身已 mock 闭环，但“非原子多腿”的结构性风险没有消失：最坏损失只能由资金上限约束。
- 当前不降低 0.5c 门槛，不启动真实资金；先等 v4 24h 结束并重跑带 NO-basket 的跨类别扫描。
## 15. 2026-10-20 15:25 盘口新鲜度修复与 OpenAI 真实收益复核

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| CSA-26 | 区分 CLOB 最后变化时间与抓取时间 | 已完成 | `sourceUpdatedAt` + `fetchedAt`；旧 source 时间不再误判 stale |
| CSA-27 | OpenAI 七档完整性人工复核 | 已完成 | 7 档完整覆盖 OpenAI 市值 + No IPO；NegRisk 一致 |
| CSA-28 | OpenAI 实时盘口连续确认 | 已完成 | v3 监控 74 次轮询、约 30 分钟持续 actionable；edge 4.3657c |
| CSA-29 | 资本锁定与年化收益模型 | 已完成 | 26.03 股净利 1.1364、资金 24.8936、最晚年化 3.791% |
| CSA-30 | 全类别重扫 | 已完成 | 4,712 市场 / 1,337 组 / 1,028 评估 / 3 正收益 / 1 actionable |
| CSA-31 | OpenAI mock 失败场景 | 已完成 | 全成交 +1.1974；最后腿无买盘 -13.8822 |
| TEST-CSA-RISK-2 | Node 回归 | 已完成 | 54 tests / 54 pass / 0 fail |
| CSA-32 | 24h 双类别监控 | 运行中 | Politics v6 PID 23984；Crypto+Finance v3 PID 6804 |

- 结论：完整集套利确实能产生正收益，但当前最佳候选是低收益、长期占用资金的 carry trade，不是高收益高频策略。
- 继续监控，不降低 0.5c 门槛；优先寻找短结算日、可立即 convert/merge、容量更大的结构。
## 16. 2026-10-20 17:45 全市场与实时顺序 paper 验证

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| CSA-26 | Gamma keyset 全市场发现 | 已完成 | 99,956 市场；`complete-set-all-markets-20261020/scan.json` |
| CSA-27 | CLOB `/books` 批量预取 | 已完成 | 315 批；0 预取错误；全量 14.8 分钟 |
| CSA-28 | 全量 ask/bid/NO-basket 扫描 | 已完成 | 4 正收益 / 3 actionable / 0 bid / 0 NO-basket |
| CSA-29 | 实时盘口逐腿顺序 paper 执行 | 已完成 | `scripts/paper-execute-complete-set.mjs`；三候选全成交 |
| CSA-30 | 真实盘口故障注入与失败平仓 | 已完成 | World Cup -18.8467；OpenAI -1.9831 |
| CSA-31 | 全市场收益/风险结论 | 已完成 | `docs/zh/complete-set-all-market-scan-2026-10-20.md` |
| CSA-32 | 24h 监控结束审计 | 运行中 | v6 PID 23984、v3 PID 6804；预计 2026-10-21 06:50Z 结束 |
| LIVE-2 | 真实签名/提交流程 | 阻塞 | 仍需用户提供 accountId、资金上限、明确授权、时间窗口、市场范围 |

- 当前没有值得直接投入大资金的完整集机会：最大绝对利润 World Cup 约 24.34 USDC，但最坏持有损失约 378 USDC；OpenAI 年化约 4.53%，容量仅约 26 股。
- Paper 验证证明执行间隔 1 秒时盘口仍可成交，但不证明真实成交；未签名、未提交、无链上对账。
- 下一步：等 24h 监控结束，审计候选持续时间和二次确认通过率；若仍只有低收益长锁仓候选，则不进入真实资金。

### 16.1 监控进程生命周期修复（21:27）

- 原 24h 监控因 PTY 会话结束在约 6.45 小时后被终止；数据未丢失。
- 已新增 `scripts/start-complete-set-monitors.ps1`，用隐藏后台进程并显式注入代理环境变量。
- 续跑至原始终点 `2026-10-21T06:50:16Z`：v6 PID 11804、v3 PID 23072；两套均已正常轮询且 errorRows=0。
- 中断前已确认：v6 2 次（Next leader）；v3 11 次（OpenAI 6、Anthropic 3、BTC vs Gold 2）。
## 17. 2026-10-20 21:46 债券型复刻（adonpoli 风格）

- 纠正：目标策略不是完整集套利，而是**高概率债券 carry + maker 库存周转**。
- 公开账户：组合 20,092.56 USDC；390 个已平仓；累计买入 5.63M；已实现 +29.9k；单次周转 0.532%；281 盈 / 86 亏。
- 新增 `BOND` 回测 profile；4,189 市场 7 天数据：
  - 0.95–0.97 hold：88 成交，ROI +4.10%，train +4.05% / test +4.13%，0 结算亏损；
  - 0.97–0.99 hold：170 成交，ROI +1.81%，train +1.80% / test +1.82%，0 结算亏损；
  - 100bps maker fee 下 0.95–0.97 仍 +2.32%。
- 风险：MAE 到 -92%～-95%，样本内 0 结算亏损不等于尾部不存在；他的 86 个亏损记录证明尾部真实。
- 已启动 BOND Shadow：PID 24440，5 个市场，WS 10 token，输出 `event-maker-bond-shadow-20261020-v2`，运行 120 分钟。
- 下一步：Shadow 结束后审计 fill rate、平均成交价、MAE、结算亏损；若 fill 足够，扩大到 2–4 周并覆盖 50–100 个结算事件。

## 18. 2026-10-20 22:20 BOND 全价格带与统计显著性复核

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| BOND-R1 | 0.90–0.95 是否可做 | 已否定 | q1/off0 ROI -1.23%，10 次结算亏损，盈亏平衡率 7.63% |
| BOND-R2 | 0.95–0.97 是否稳健 | 存疑 | 队列模型 +4.10%，但全 eligible 模型 +2.17%，3/159 亏损，95% CI 0.64%–5.40% |
| BOND-R3 | 0.97–0.99 是否稳健 | 边界性 | 全 eligible +1.54%，1/261 亏损，95% CI 0.07%–2.14%，盈亏平衡 1.90% |
| BOND-R4 | 0.99–1.0 是否值得做 | 收益过薄 | 全 eligible +0.51%，0/349 亏损，95% CI 0%–1.09% |
| BOND-R5 | 可复现分析脚本 | 已完成 | `scripts/analyze-leader-bond-style.cjs`；输出 `leader-bond-style-backtest-20261020-repro.json` |
| BOND-R6 | 0.5–0.8 能否用持有模型复刻 | 不能 | 全 eligible 7d hold ROI -10.26%；他的 +1.90% 需要主动周转/退出或更强选品 |

结论：

- 只继续 Shadow `0.95–0.99`，不碰 `0.90–0.95`。
- 4.10% 是队列约束样本内结果，不是稳定年化；全 eligible 模型只有 +2.17%，且置信区间未排除负期望。
- 需要 2–4 周、50–100 个结算事件；在亏损率置信区间下界高于盈亏平衡率前，不进入真实资金。
- 当前 BOND Shadow（PID 24440）继续跑满 120 分钟；结束后按本表审计 fill、MAE 和结算行为。
- 7 天续跑已武装：scripts/continue-bond-validation.ps1 等待 PID 24440/7304 退出，先跑全量测试，通过后启动 event-maker-bond-shadow-7d-20261020（最多 20 市场、0.95–0.99、hold-to-settlement）。
### 18.1 与目标账户真实成交对账

| 价格带 | 目标已平仓 ROI | 目标 matched-flow ROI | 队列回测 ROI | 全 eligible ROI |
|---|---:|---:|---:|---:|
| 0.90–0.95 | +0.72% | +0.73% | -1.23% | -3.39% |
| 0.95–0.97 | +0.225% | +1.24% | +4.10% | +2.17% |
| 0.97–0.99 | +0.815% | +0.001% | +1.81% | +1.54% |
| 0.99–1.0 | +0.255% | +0.099% | +0.40% | +0.51% |

- 目标账户整体单次周转约 0.532%，不是每笔 4%；我们的 0.95–0.97 队列回测是目标的约 18 倍，不能当作复刻收益。
- `leader-trade-flow` 的 matched round-trip PnL 为 -0.2055%，说明他的正收益更依赖持仓到结算、库存管理、奖励/返佣和选品，而不是简单买卖价差。
- 7 天 Shadow 继续跑，但必须按“真实成交/库存/结算账本”验收；若出现单笔远高于 1% 的收益，先怀疑成交假设。
### 18.2 目标账户收益结构再判断

- 持有期中位数 3.4 天、p90 32.1 天；36% 股数 1 天内平掉、35% 1–7 天、29% >7 天。
- matched round-trip 整体 ROI -0.2055%；正收益更可能来自库存持有到结算、事件重定价、奖励/返佣和选品。
- 最大盈利 round trip 多为 geopolitics 事件重定价（0.23→0.976、0.057→0.999、0.70→0.83）；最大亏损集中在 0.8–0.99。
- 更准确判断：目标是**事件驱动 + maker 执行 + 大库存周转**，不是纯被动债券做市。
- 仅复刻 0.95–0.99 挂单持有无法解释其 0.5–0.8/geopolitics 利润；下一步必须做逐笔库存账本或承认当前 BOND 只是另一个低收益假设。
- BOND 运行手册：`docs/zh/event-maker-bond-runbook-2026-10-20.md`（配置、启动/停止、产物、审计清单、Live 门槛）。
### 18.3 2h Shadow 结果与 7d 启动

| 指标 | 2h 结果 |
|---|---:|
| duration | 7200s |
| markets | 5 |
| trades | 1,023 |
| bookUpdates | 50,982 |
| quotesPlaced | 5 |
| cancels | 5 |
| fills | 0 |
| completedCycles | 0 |
| realized / unrealized / total PnL | 0 / 0 / 0 |
| duplicateFillKeys | 0 |
| lastErrors | [] |

- 0 fill 与保守队列模型一致；不能通过降低 queueFactor 或追价制造 fill。
- duplicateTradeEvents=8,762 是 WS + Data API 双路重复投递，engine 按 trade key 去重，未产生重复 fill；离线审计 `duplicateFillKeys=0`。
- 全量测试 `BUILD SUCCESSFUL in 21s`。
- 7d Shadow 已启动：PID 10868、8 市场、16 token、flowGuard=0；输出 `event-maker-bond-shadow-7d-20261020`。
- 7d 结束后用 `npm run audit:bond-shadow` 审计；至少覆盖 2–4 周/50–100 个结算事件前不进入真实资金。
### 18.4 逐笔库存账本复核（2026-10-21）

| 指标 | 结果 |
|---|---:|
| TRADE activity 覆盖 | 2026-03-01 至 2026-10-16 |
| trades / closed positions / open positions | 5,155 / 390 / 5 |
| matched PnL | +14,342 USDC |
| matched ROI | +0.748% |
| settlement PnL | +3,637 USDC |
| all closed ROI | +0.802% |
| 全周期 closed API ROI | +0.532% |
| 持有期中位数 / p90 | 2.02 天 / 10.96 天 |

价格带 matched ROI：0.5–0.8 +0.94%、0.8–0.9 +0.69%、0.9–0.95 +1.39%、0.95–0.97 +1.26%、0.97–0.99 +0.85%、0.99–1 +0.31%。

- 目标账户不是纯 0.95–0.99 债券 carry；edge 分布在多个价格带，0.9–0.97 最强。
- 我们的 BOND 0.95–0.97 队列回测 +4.10% 约为目标同 band matched ROI 的 3.3 倍、closed-position ROI 的 18 倍，仍高估。
- 新增脚本：`scripts/snapshot-leader-ledger.mjs`、`scripts/analyze-leader-ledger.mjs`。
- 下一步若要复刻，需研究事件选择、跨价格带库存周转和退出纪律；单靠 0.95–0.99 持有不够。
- 类别归因：Geopolitics matched PnL +13,112 / ROI +0.825%；Politics +2,481 / +1.33%；Crypto -186 / -0.35%；Sports -140 / -0.69%。下一步应收缩到政治/地缘事件，而不是全类别。


## 19. 2026-10-21 BOND_SCALP 主动退出与 7d Shadow

### 19.1 背景

目标账户 matched round-trip 价差中位数 +1.0c、p90 +3.0c，18.4% 为负价差主动退出；纯 hold-to-settlement 无法复刻这种周转。因此新增 `GridProfile.BOND_SCALP`。

### 19.2 验收矩阵

| 项目 | 结果 | 判定 |
|---|---|---|
| profile 可解析 | `bond-scalp` | PASS |
| 价格带 | 0.90–0.95 / 0.95–0.97 / 0.97–0.99 / 0.99–0.999 | PASS |
| entry offset | 0 / 0.25c | PASS |
| exit | +1c / +2c / +3c | PASS |
| queueFactor | 1 / 2 / 5 | PASS |
| maxCycles | 3 / 10 | PASS |
| requote | 300s | PASS |
| 0.95–0.97 off0 +3c q2 c3 | 79/90, ROI +3.20%, train +3.19% / test +3.20%, 0 结算亏损 | PASS（队列模型） |
| 0.97–0.99 off0.25 +3c q2 c3 | 78/79, ROI +2.20%, train +2.13% / test +2.28%, 0 结算亏损 | PASS（队列模型） |
| 全 eligible +3c 0.95–0.97 | n159, 平均 ROI +1.82%, 亏损率 1.26% | PASS（保守成交） |
| 全 eligible +3c 0.97–0.99 | n261, 平均 ROI +1.44%, 亏损率 0.38% | PASS（保守成交） |
| 0.90–0.95 +3c | 平均 ROI -1.58%, 亏损率 4.66% | REJECT，不做 |
| 7d Shadow 启动 | 2026-10-21 01:08（v2），8 市场 / 16 token，WS 已订阅，requote=300s / maxOrderAge=1800s | RUNNING |
| 7d Shadow 审计 | 待运行结束 | PENDING |

### 19.3 已知限制

- 队列模型 100% 胜率、0 结算亏损，明显优于目标账户约 81% 胜率，属于乐观估计。
- 全 eligible 模型仍假设每笔都成交，真实 fill rate 需 Shadow 验证。
- 主动卖出若为 taker，100bps 成本下 0.95–0.97 ROI 从 3.20% 降至 1.22%，仍为正但腰斩。
- 未计 maker rebate / liquidity reward，也未计滑点与网络延迟。

### 19.4 产物

- `backend/build/research/event-maker-bond-scalp-20261021-summary.csv`
- `backend/build/research/event-maker-bond-scalp-20261021-robust-cycles.csv`
- `backend/build/research/event-maker-bond-scalp-20261021-fee-sensitivity.csv`
- `backend/build/research/event-maker-bond-scalp-20261021-top-cycles.csv`
- `backend/build/research/leader-bond-style-backtest-20261021-repro.json`
- `backend/build/research/event-maker-bond-scalp-shadow-7d-20261021-v2/`
- 自动审计：`scripts/watch-bond-shadow.ps1`（PID 19216）→ `audit-final.json`
### 19.5 统计显著性与实盘执行补测（2026-10-21）

| 项目 | 结果 | 判定 |
|---|---|---|
| 队列模型 0.95–0.97 +3c q2 c3 | 90 cycles，0 结算亏损，ROI +3.20%，亏损率 CI 上限 4.09% vs 盈亏平衡 3.10% | 未证明 |
| 全 eligible 0.95–0.97 +3c | n159，亏损率 1.26%，CI [0.35%, 4.47%] vs 3.03% | 未证明 |
| 全 eligible 0.97–0.99 +3c | n261，亏损率 0.38%，CI [0.07%, 2.14%] vs 1.80% | 未证明 |
| 全 eligible 0.90–0.95 +3c | n193，亏损率 4.66%，平均 ROI -1.58% | 排除 |
| 60s markout | 中位≈0，50% 为负 | 存在逆选择 |
| maxCycles 实盘语义 | 3 循环后不再开仓 | PASS |
| SELL 不受 flow guard 拦截 | 测试通过 | PASS |
| 全量测试 | BUILD SUCCESSFUL | PASS |

结论：回测正收益 ≠ 统计显著；继续 Shadow，不上实盘。脚本 `scripts/analyze-bond-scalp-risk.cjs`，结果 `backend/build/research/bond-scalp-risk-20261021.json`。
### 19.6 +1c / +3c 退出价差 A/B（2026-10-21）

| 退出 | 0.95–0.97 ROI | 0.97–0.99 ROI | 定位 |
|---|---:|---:|---|
| +1c | +1.08% | +1.05% | 中位复刻，与 leader matched ROI +1.26% / +0.85% 接近 |
| +2c | +2.15% | +1.65%–1.87% | 中间变体 |
| +3c | +3.20% | +2.20% | p90 上行变体，约为 leader 的 2.5–3 倍 |

- 目标账户价差中位 +1c、p90 +3c；固定 +3c 会高估，不能当作主复刻。
- 已并行启动两个 7 天 Shadow：+3c（01:08，`...-v2`）和 +1c（01:29，`...-1c-...`），各带 watcher。
- 验收：比较两臂真实 fill rate、平均退出价、结算占比、MAE 和实际 ROI；+1c 应接近 1%，+3c 若显著高于 3% 先怀疑成交假设。
### 19.7 持续监控（2026-10-21）

- `scripts/monitor-bond-shadows.ps1`，PID 17676，每 30 分钟审计两个 Shadow 目录。
- 输出 `backend/build/research/bond-shadow-audit-series.jsonl`。
- 字段：markets / fills / cycles / activeOrders / inventoryShares / realizedPnl / realizedRoi / flags。
- 退出审计：+3c watcher PID 19216，+1c watcher PID 23192。
### 19.8 完整集 24h 监控的自动终审（2026-10-21）

- 新增 `scripts/watch-complete-set-monitors.ps1`（PID 9708，01:40 升级脚本后重启）：等两个 24h 监控进程（11804、23072）退出后，自动读取各自的 `monitor-summary.json` 和 `opportunities.jsonl`，写 `complete-set-monitor-final-audit.json`。
- 审计字段：pollCycles / discoveryCycles / errorRows / lastPollErrors / confirmedEpisodes / maxConfirmedEdge / maxConfirmedProfit / opportunities / actionable / rejectionReasons / verdict。
- 判定规则：有 `best.actionable=true` 才是 `ACTIONABLE_FOUND`；否则为 `NO_ACTIONABLE_OPPORTUNITY`。
### 19.9 +1c / +3c 与目标账户行为对账（2026-10-21）

| 指标 | 目标账户 | +1c 队列 | +3c 队列 |
|---|---:|---:|---:|
| 0.95–0.97 ROI | +1.26% | +1.08% | +3.20% |
| 持有期中位 / p90 | 2.02 / 10.96 天 | 0.69 / 2.49 天 | 3.65 / 6.35 天 |
| 负价差占比 | 18.4% | 0% | 0% |
| 结算亏损 | 有 | 0/133 | 0/90 |
| 亏损率 CI 上限 vs 盈亏平衡 | — | 2.81% vs 1.07% | 4.09% vs 3.10% |

- +1c 复刻 ROI，但周转过快、无止损；+3c 复刻持有期，但 ROI 高 2.5–3 倍。
- 目标账户是动态退出 + 主动止损，固定退出只能近似；需 Shadow A/B 验证。
- 证据：`event-maker-bond-scalp-1c-focus-20261021-focus-cycles.csv`、`bond-scalp-1c-risk-20261021.json`。
### 19.10 回测与 Shadow 入场口径差异（2026-10-21）

- 回测：`floor(last trade - offset, tick)`；Shadow：`bestBid + improveTicks × tick`（当前 improveTicks=1）。
- 实例：bid 0.976 / ask 0.980，Shadow 挂 0.977；回测可能假设 0.977–0.980。
- 结论：回测 fill rate 不能直接外推到 Shadow，真实成交率以 Shadow 为准。
### 19.11 完整集监控的 actionable 是假阳性：部分成交裸风险（2026-10-21）

检查 crypto/finance 24h monitor 的 16 条 `opportunities.jsonl`：

- 10 条 confirmation 记录，5 条 `best.actionable=true`。
- 但这 5 条全部 `askRiskWorstPnlUsd < 0`：
  - Bitcoin vs. Gold vs. S&P 500：edge 1.49c，capital 224.6，worst fills `228/228/0`，worst PnL **-59.52**。
  - OpenAI IPO Closing Market Cap：edge 5.7–5.8c，capital ~24.5，worst PnL **-13.96**。
- 原因：negRisk 篮子只成交 2/3 条腿，第 3 条腿 0 股，等于裸风险；不是风险自由完整集。
- 结论：`actionable=true` 只代表 edge 过缓冲，不代表 risk-free；必须同时满足 `askRiskWorstPnlUsd >= 0` 且 worst fills 覆盖所有腿。
- 已更新 `watch-complete-set-monitors.ps1`：区分 `riskFreeActionable` 与 `nakedRiskActionable`；只有 riskFree>0 才判 `ACTIONABLE_FOUND`，否则 `PARTIAL_BASKET_NAKED_RISK_ONLY`。
### 19.12 修复 Shadow 单市场风险上限（2026-10-21）

| 项目 | 修复前 | 修复后 |
|---|---|---|
| maxTotalInventoryNotional | 硬编码 100 | CLI/Gradle/脚本可配 |
| maxDailyLoss | 硬编码 5 | CLI/Gradle/脚本可配 |
| +3c active orders | 1 / 8 市场 | 10 / 10 市场 |
| +1c active orders | 1 / 10 市场 | 10 / 10 市场 |
| 测试 | — | `EventMakerShadowCliOptionsTest`，全量 BUILD SUCCESSFUL |

- 旧臂 0 fill / 0 cycle，已审计停止。
- 新臂 cap=1000，watcher 21160 / 26520，monitor 26068。
- 这是“多市场分散”策略能否成立的前提修复。
### 19.13 首次 Shadow 成交（2026-10-21 02:04）

- +3c / +1c 两臂均在 `US x Iran ceasefire continues through October 22?` 成交 BUY @0.953，104.93 股。
- 成交后 LONG；卖出限价等待 best ask 达到 entry+1c/+3c。
- 多市场挂单（10/10 active）与成交状态机已验证；继续观察退出与结算。
### 19.14 目标账户大止损行为（2026-10-21）

- worst round trips 的亏损 ROI 为 -13% 到 -24%，持有 30 分钟到 8 天；不是 -1c/-2c 机械止损。
- 更像事件逻辑破坏后的主动离场；机械小止损会过早砍仓，可能破坏 edge。
- 固定 +1c/+3c 模型只能复刻正常盈利路径，无法复刻事件判断 + 大止损。
- 结论：机械基线用于 Shadow 验证，但不能期望等于目标账户全部收益。
### 19.15 监控增强：持续记录 markout（2026-10-21 02:45）

- `monitor-bond-shadows.ps1` 新增 `markout60Count` / `markout60Mean` / `markout300Mean` / `markout60NegativeShare`。
- 首条记录：两个 cap1000 臂 `markout60=-1.05`、`markout300=-1.99`、负占比 **100%**。
- monitor 重启 PID 21168；序列 `bond-shadow-cap1000-audit-series.jsonl`。
- 意义：不必等 7 天，数小时内即可看到成交质量是否持续为负。
### 19.16 宽价格带回测：低价格带不可机械复刻（2026-10-21）

| 价格带 | 最佳 ROI | 结算亏损 |
|---|---:|---:|
| 0.5–0.8 | -9.67% | 75 |
| 0.8–0.9 | -5.77% | 24 |
| 0.9–0.95 | -0.19% | 4 |
| 0.95–0.97 | +3.22% | 0 |
| 0.97–0.99 | +2.23% | 0 |
| 0.99–0.999 | +0.70% | 0 |

- 新增 `SCALP_WIDE` profile（108 配置）+ 单测，全量测试通过。
- 低价格带的正收益是目标账户的事件判断/主动管理，不是机械 scalp。
- 继续聚焦 0.95–0.99；不要把策略扩展到低价格带。
### 19.17 入场质量与被动挂单 A/B（2026-10-21）

- Leader 买入前 1h 无论涨跌，60s markout 全为正（+0.13% ~ +0.72%）。
- 我们首笔 Shadow 60s markout 约 -1.0%，说明 improveTicks=1 可能过于激进。
- 新增 `ImproveTicks` 参数；启动被动臂 improveTicks=0、entryOffset=0.25c。
- 三臂 A/B：+3c aggressive、+1c aggressive、+1c passive；比较 fill rate 与 markout。
### 19.18 +1c/+3c 双目标组合（2026-10-21）

- 50% +1c + 50% +3c：预期 ROI ≈ 2.14%，持有中位 ≈ 2.17 天，接近 leader 的 2.02 天。
- 退出分布约一半 1c、一半 3c，更接近 leader 的中位/p90。
- 仍缺 leader 的 18.4% 负价差退出（discretionary loss-cutting）。
- 实操：资金分两半跑两个目标，或同市场先卖一半 +1c、剩余挂 +3c（需 live 部分卖出支持）。
### 19.19 挂单位置 A/B：aggressive / join-bid / passive（2026-10-21）

| 臂 | 挂单价 | 市场/active | 首笔 | 60s markout |
|---|---|---|---:|---:|
| aggressive | bid + 1 tick | 10/9 | 已成交 | -1.0% |
| join-bid | best bid | 5/5 | 0 | — |
| passive | bid - 0.25c | 8/8 | 0 | — |

- join-bid 有 3 个市场因 entry_queue_too_deep 被拒绝，join 队列深度太大。
- aggressive 成交最快但 markout 最差；join-bid/passive 需等成交数据。
- 结论：成交速度与入场质量存在 trade-off。
### 19.20 四臂 Shadow 与完整集监控状态（2026-10-21 03:30）

| 臂 | 市场/active | fills | cycles | 60s markout |
|---|---|---:|---:|---:|
| +3c aggressive | 10/9 | 1 | 0 | -1.05 |
| +1c aggressive | 10/9 | 1 | 0 | -1.05 |
| +1c join-bid | 5/5 | 0 | 0 | — |
| +1c passive | 8/8 | 0 | 0 | — |

- 首笔成交市场最新 bid 0.945 / ask 0.946，entry 0.953，浮亏 -0.84，未触发 +1c/+3c 退出。
- crypto/finance 24h monitor：27 条 opportunities，11 条 actionable，但全部 `worstPnl < 0`（部分成交裸风险）；politics 0 条。
- 结论：没有风险自由完整集；继续等 join-bid/passive 成交与 markout 数据。
### 19.21 四臂状态报告脚本（2026-10-21 03:35）

- 新增 `scripts/report-bond-shadow-status.cjs`，汇总四臂的 markets / active / fills / cycles / inventory / realizedPnl / markout60 / markout300 / neg60。
- 当前：aggressive 两臂各 1 fill（markout -1.05 / -1.99，neg60 100%）；join-bid 5/5 active 0 fill；passive 8/8 active 0 fill。
- 输出 `backend/build/research/bond-shadow-status-20261021.md`，可随时重跑。
