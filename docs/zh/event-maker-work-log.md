# Event Maker 工作日志

> 本文件记录每个实际动作、命令、结果和下一步。计划与验收状态见 [event-maker-delivery-plan.md](event-maker-delivery-plan.md)。

## 2026-10-17

### 研究基线

- 完成 adonpoli 地址的公开成交、结算和收益归因分析。
- 结论：主要是被动 maker、卖出价差和库存周转，不是主动跟单。
- 证据：`docs/zh/bond-carry-strategy-backtest-2026-10-17.md`。

### 历史回测

- 实现并运行 1,000 个市场、162 组参数的事件 maker 回测。
- 结果：训练/测试都覆盖足够样本的配置没有稳定正收益；最好配置样本很小。
- 证据：`docs/zh/event-maker-backtest-2026-10-17.md`。
- 提交：`6bc7392 feat: add event maker backtest research`。

### 真实盘口纸交易

- 实现 maker-only 实时纸交易：WebSocket 盘口、Data API 成交、队列消耗、部分成交、transactionHash 去重。
- 运行 161.2 分钟，12 个市场，155 笔唯一成交，113,592 次盘口更新，6 个完整回合。
- 最优臂 `improveask1-095-099-sp1`：4/4 盈利，已实现 +0.5172 USDC，总 PnL +0.5048 USDC。
- 关键发现：0.95 以上 join 队列的 3 个臂 0 买入 fill；改善 1 tick 的臂 fill rate 25%～27%。
- 证据：`docs/zh/event-maker-live-paper-2026-10-17.md`、`backend/build/research/live-paper-final6-20261017/`。
- 提交：`e48164a feat: add live maker paper trading research`。

## 2026-10-18

### 23:37 建立交付计划

- 动作：创建交付计划与验收矩阵。
- 文件：`docs/zh/event-maker-delivery-plan.md`。
- 当前判断：历史回测和一轮 shadow 已完成；生产化 Shadow 服务、Live 执行适配器、订单对账和 mock 端到端测试仍未完成。

### 23:37 策略核心开始实现

- 动作：新增 `service/eventmaker` 包，落地配置、报价意图、风险快照和状态模型。
- 文件：
  - `EventMakerStrategyModels.kt`
  - `EventMakerQuotePlanner.kt`
  - `EventMakerRiskPolicy.kt`
- 关键规则：
  - 买价改善 1 tick；
  - 前方队列超过上限则拒绝；
  - 盘口过期拒绝；
  - 卖价放到 best ask 内侧 1 tick；
  - 总库存、市场数量、日亏损限制。
- 测试：
  - `EventMakerQuotePlannerTest`
  - `EventMakerRiskPolicyTest`
- 命令：`.\gradlew.bat test --tests "com.wrbug.polymarketbot.service.eventmaker.*"`
- 结果：7 tests passed。
- 下一步：补策略状态机测试，然后实现 Shadow Spring 服务和 Live 执行适配器。


### 23:55 生产化核心与 Shadow 服务初版

- 动作：
  - 新增 `EventMakerExecutionPort` 和 `ClobEventMakerExecutionAdapter`。
  - 新增 `EventMakerOrderRequestFactory`，强制 `postOnly=true`、`orderType=GTC`。
  - 新增 `EventMakerStrategyService`，支持 Shadow 启动/停止/状态查询。
  - 新增 `EventMakerStrategyController`，提供 `/api/event-maker/start`、`/stop`、`/status`。
  - 新增 `EventMakerStartRequest` 配置 DTO。
- 安全行为：
  - LIVE 模式需要 `liveTradingEnabled=true` 和 `accountId`。
  - 当前 LIVE 编排尚未接通真实订单状态机，服务会 fail-closed 拒绝启动，不会误下单。
- 命令：`.\gradlew.bat test --tests "com.wrbug.polymarketbot.service.eventmaker.*" --console=plain`
- 结果：8 tests passed。
- 证据：`EventMakerQuotePlannerTest`、`EventMakerRiskPolicyTest`、`EventMakerOrderRequestFactoryTest`。
- 下一步：实现 Live 市场状态机、订单对账和停止时批量撤单。

### 00:20 Live 状态机与编排接入

- 动作：
  - 新增 `EventMakerLiveState`，以 CLOB 订单状态为成交权威来源，处理买入成交、部分卖出、完整回合和撤单后的库存恢复。
  - `EventMakerStrategyService` 增加 LIVE 分支：订单状态轮询、部分成交撤单、重挂、库存统计、日亏损停机。
  - `stop()` 会对当前活跃订单执行撤单。
  - `EventMakerStrategyController` 继续提供启动、停止和状态接口。
- 测试：
  - `EventMakerLiveStateTest` 覆盖买入成交、卖出成交、部分卖出和撤单后库存。
  - 命令：`.\gradlew.bat test --tests "com.wrbug.polymarketbot.service.eventmaker.*" --console=plain`
  - 结果：10 tests passed。
- 当前限制：
  - 真实 LIVE 尚未用账户跑过；需要显式账户和资金授权。
  - 订单丢失/404 的成交补查仍待实现。
  - 代理钱包部署状态和持久化恢复仍待补。
- 下一步：补订单丢失恢复、批量撤单、代理钱包校验和 mock 端到端测试。

### 00:40 账户安全校验补齐

- 动作：`EventMakerExecutionPort.validateAccount` 接入真实账户校验，LIVE 启动前验证账户、API 凭证、私钥和代理钱包部署状态。
- 测试：`EventMakerStrategyServiceSafetyTest` 验证 LIVE 未开启和缺少账户时拒绝启动。
- 命令：`.\gradlew.bat test --tests "com.wrbug.polymarketbot.service.eventmaker.*" --console=plain`
- 结果：12 tests passed。
- 下一步：补订单丢失/404 恢复、持久化恢复和 mock 端到端 Live 测试。
## 记录模板

```text
### HH:mm 动作标题
- 动作：
- 文件：
- 命令：
- 结果：
- 证据：
- 下一步：
```
## 2026-10-19

### 00:17 建立可追踪执行记录并恢复工作

- 用户要求：先把计划和后续行为动作记录到仓库，避免依赖聊天记忆。
- 动作：
  - 检查当前 Git 状态、交付计划和已有工作日志。
  - 确认新增 event-maker 生产代码、测试和文档仍为未提交状态。
  - 检查 `EventMakerStrategyService`、`EventMakerLiveState`、`EventMakerStateStore` 和 Live 执行适配器。
  - 在交付计划中加入“执行协议与检查点”，明确每轮必须记录目标、命令、结果、证据和下一步。
- 当前 Git 基线：
  - 分支：`main`
  - 最近提交：`e48164a feat: add live maker paper trading research`
  - 未提交：event-maker 生产代码、测试、交付计划和工作日志。
- 已确认问题：
  - `EventMakerStrategyService.kt:425` 调用 `updateLiveStatus(config, ...)`。
  - 当前 `reconcileLiveOrders` 签名缺少 `config: EventMakerConfig`，会编译失败。
- 本轮下一步：
  1. 修复编译错误；
  2. 运行 event-maker 定向测试；
  3. 根据结果继续补持久化和 Live mock 测试；
  4. 每完成一步追加本日志。
### 00:20 修复编译阻断并通过定向测试

- 动作：
  - 将 `reconcileLiveOrders` 签名改为接收 `config: EventMakerConfig`。
  - 将调用点改为 `reconcileLiveOrders(config, states, accountId, books)`。
  - 更新 `EventMakerStrategyServiceSafetyTest` 构造函数，注入 `EventMakerStateStore`。
- 文件：
  - `backend/src/main/kotlin/com/wrbug/polymarketbot/service/eventmaker/EventMakerStrategyService.kt`
  - `backend/src/test/kotlin/com/wrbug/polymarketbot/service/eventmaker/EventMakerStrategyServiceSafetyTest.kt`
- 命令：`.\gradlew.bat test --tests "com.wrbug.polymarketbot.service.eventmaker.*" --console=plain`
- 结果：`BUILD SUCCESSFUL`，event-maker 定向测试通过（12 个既有测试）。
- 下一步：补 `EventMakerStateStore` 和 `EventMakerLiveState.restore` 的持久化测试。
### 00:35 持久化、执行安全和 Shadow/Live 统一编排

- 动作：
  - `EventMakerStateStore` 改为原子写盘，读取时校验版本，默认状态文件移到 `backend/data/event-maker-state.json` 并加入 `.gitignore`。
  - 持久化模型增加 `totalBuyNotional` 和 `activeOrderCreatedAt`，恢复时校验库存、价格、数量和订单状态。
  - 修复终态订单在零成交时错误进入 `LONG` 的状态机缺陷。
  - 买入报价增加每市场最大回合数限制。
  - 新增 `EventMakerExecutionGuards`：tick、最小订单量、USDC 余额/授权、CTF 库存/授权检查。
  - 新增 `EventMakerShadowExecutionPort`：用公共成交消耗队列并模拟 maker 成交，不签名、不下单。
  - 新增 `EventMakerLiveEngine`：Shadow 与 Live 共用报价、风险、撤单、部分成交和订单对账路径。
  - Live 启动恢复持久化订单并登记到引擎；无法匹配当前市场集合的旧活跃订单会先尝试撤销，失败则拒绝启动。
  - 订单 404、未知状态、部分成交撤单失败、下单结果不确定时进入 fail-closed 停机。
  - `stop()` 只在撤单成功后清除活跃订单；撤单失败会保留持久化订单并写入 `lastError`。
- 文件：
  - `EventMakerStateStore.kt`
  - `EventMakerStrategyModels.kt`
  - `EventMakerLiveState.kt`
  - `EventMakerExecutionErrors.kt`
  - `EventMakerShadowExecutionPort.kt`
  - `EventMakerLiveEngine.kt`
  - `EventMakerStrategyService.kt`
  - `ClobEventMakerExecutionAdapter.kt`
  - `backend/src/main/resources/application.properties`
  - `.gitignore`
- 命令：`.\gradlew.bat test --tests "com.wrbug.polymarketbot.service.eventmaker.*" --console=plain`
- 结果：`BUILD SUCCESSFUL`，当前 16 个 event-maker 测试通过。
- 证据：
  - `EventMakerStateStoreTest` 3 个测试通过；
  - `EventMakerLiveStateTest` 3 个测试通过；
  - 现有报价、风险、请求工厂和安全测试全部通过。
- 尚未验证：新增执行守卫、Shadow 模拟器和 Live 引擎还没有独立测试，下一步补齐。
### 00:55 执行守卫、Shadow 模拟和 Live mock 流程测试

- 动作：
  - 新增 `EventMakerExecutionGuardsTest`，覆盖 tick、最小订单量、USDC 余额/授权、CTF 库存/授权。
  - 新增 `EventMakerShadowExecutionPortTest`，覆盖队列消耗、部分成交、撤单、买入和卖出成交。
  - 新增 `EventMakerLiveEngineTest`，覆盖完整买卖回合、部分成交撤单、未知状态停机、订单丢失停机和恢复后撤单。
  - 新增 `ClobEventMakerExecutionAdapterTest`，覆盖 post-only/GTC、余额不足拒绝下单、订单 404、非法快照和撤单 404。
  - 修复适配器未拒绝未知订单状态的缺陷，`MYSTERY` 等状态现在进入 fail-closed。
  - 计划文件同步勾选统一 planner、资金/最小订单量、断线恢复、持久化和 mock E2E。
- 文件：
  - `EventMakerExecutionGuardsTest.kt`
  - `EventMakerShadowExecutionPortTest.kt`
  - `EventMakerLiveEngineTest.kt`
  - `ClobEventMakerExecutionAdapterTest.kt`
  - `ClobEventMakerExecutionAdapter.kt`
  - `EventMakerLiveEngine.kt`
- 命令：`.\gradlew.bat test --tests "com.wrbug.polymarketbot.service.eventmaker.*" --console=plain`
- 结果：`BUILD SUCCESSFUL`，31 个 event-maker 测试，0 失败。
- 证据：`backend/build/test-results/test/TEST-com.wrbug.polymarketbot.service.eventmaker.*.xml`。
- 下一步：运行全量测试；补逐回合审计产物和运行手册；检查真实 Shadow 运行条件。
### 01:10 全量测试、逐回合审计与统一 Shadow 长测启动

- 动作：
  - 运行全量测试：93 个测试套件、414 个测试，0 失败，2 跳过。
  - 为 `EventMakerLiveState` 增加逐笔 fill 和逐回合 cycle 记录，并持久化到状态文件。
  - `EventMakerStatus` 增加最近 100 条 fill/cycle，便于 API 审计。
  - 新增独立 `EventMakerShadowCli` 和 Gradle 任务 `eventMakerShadow`，不依赖 MySQL、不签名、不下单。
  - Shadow 输出 `markets.json`、`events.jsonl`、`trades.csv`、`snapshots.csv`、`state.json`、`summary.json`。
  - 修复 Shadow 每 1 秒写空引擎事件导致日志膨胀的问题。
  - 增加 `--max-queue`/`shadowMaxQueue` 参数，避免一档价差市场因队列限制完全无法报价。
  - 2 分钟和 1 分钟冒烟均正常；2 分钟首轮直连失败，记录为网络超时，改用代理后成功。
- 命令：
  - `.\gradlew.bat test --console=plain`
  - `.\gradlew.bat eventMakerShadow "-PshadowProxy=http://127.0.0.1:10808" "-PshadowMinutes=2" ...`
- 结果：
  - 全量测试 414 tests / 0 failures。
  - 冒烟：真实盘口更新、成交去重、报价和审计文件均正常。
  - 1 分钟冒烟：4 个市场、801 条唯一成交、330 次盘口更新、2 笔 Shadow 报价。
- 证据：
  - `backend/build/research/event-maker-shadow-smoke4-20261019/`
- 正在执行：
  - 2 小时统一 Shadow 长测，输出目录 `backend/build/research/event-maker-shadow-2h-20261019/`。
  - 启动参数：8 个市场、代理 `127.0.0.1:10808`、最大队列 100000、每 10 秒快照。
- 下一步：长测完成后检查重复成交、未知状态、报价/撤单/回合记账，并写正式报告。
### 01:15 回测复跑与最小价差筛选

- 动作：
  - 新增 `EventMakerMarketSelector`：默认排除只有 1 tick 价差的市场，避免买单只能排在大队列后面；同时过滤价格区间。
  - `EventMakerConfig`、REST DTO、Shadow CLI 和 Gradle 参数增加 `minSpreadTicks`（默认 2）。
  - 新增市场筛选单元测试。
  - 复跑历史回测：30 个市场、162 组配置。
- 命令：
  - `.\gradlew.bat eventMakerBacktest "-PresearchProxy=http://127.0.0.1:10808" "-PresearchMaxMarkets=30" "-PresearchMinVolume=100000" "-PresearchFrom=2024-01-01" "-PresearchTo=2026-10-01" "-PresearchConcurrency=3" "-PresearchOutput=build/research/event-maker-repro-20261019" --console=plain`
  - `.\gradlew.bat test --tests "com.wrbug.polymarketbot.service.eventmaker.*" --console=plain`
- 结果：
  - 回测 30/30 市场成交历史抓取成功，失败 0；162 组配置完成。
  - 训练/测试均有 ≥20 个成交市场的稳健配置仍为 0。
  - 最高 ROI 配置只有 1 个成交市场，不能作为实盘依据。
  - event-maker 定向测试通过。
- 证据：
  - `backend/build/research/event-maker-repro-20261019-summary.csv`
  - `docs/zh/event-maker-backtest-2026-10-17.md` 第七节。
- 结论：
  - 当前策略框架可运行、可审计、可复现，但没有证明稳定 edge。
  - 1 tick 价差市场结构上无法用 post-only 改善价格，必须筛选更宽价差市场。
### 01:32 报价精度修复与最终全量测试

- 动作：
  - 修复 `EventMakerQuotePlanner` 将 `Double` 直接转 `BigDecimal` 导致的浮点尾差。
  - 统一使用 `BigDecimal.valueOf(...)`，并补充 0.999 附近、极小 tick、交叉/锁定盘口测试。
  - 新增最小价差市场筛选，过滤价格区间外的市场。
  - 运行最终全量测试。
- 命令：
  - `.\gradlew.bat test --tests "com.wrbug.polymarketbot.service.eventmaker.*" --console=plain`
  - `.\gradlew.bat test --console=plain`
- 结果：
  - event-maker：35 tests，0 failures。
  - 全量：94 suites / 418 tests / 0 failures / 2 skipped。
- 证据：
  - `backend/build/test-results/test/`
  - `docs/zh/event-maker-live-execution-validation-2026-10-19.md`
- 正在运行：
  - 未筛选 2 小时 Shadow（基线）。
  - 最小价差 2 tick、6 市场、60 分钟 Shadow。
  - 最小价差 2 tick、12 市场、45 分钟 Shadow（更宽候选池）。
### 01:46 最终测试与 markout 审计

- 动作：
  - `EventMakerShadowExecutionPort.onTrade` 返回逐笔模拟成交明细。
  - Shadow CLI 增加 `markouts.csv`：每笔成交后 60 秒记录 best bid/ask 和 markout PnL。
  - 增加状态文件版本校验、拒单回到 FLAT、极端价格/tick、市场筛选等测试。
  - 再次运行全量测试。
- 结果：
  - event-maker：40 tests，0 failures。
  - 全量：94 suites / 423 tests / 0 failures / 2 skipped。
- 证据：
  - `backend/build/test-results/test/`
  - `backend/build/research/event-maker-shadow-final-smoke-20261019/markouts.csv`（本次无成交，文件为空表头）
- 说明：
  - 1 分钟最终冒烟中 5 个市场、1,000 条唯一成交、258 次盘口更新、5 笔 Shadow 报价，但无成交；这是当前价差/队列条件的真实结果，不是代码异常。
### 03:00 统一 Shadow 长测完成

- 运行结果：
  - 2 小时基线：8 市场、1,799 条唯一成交、52,426 次盘口更新、240 笔报价、236 次撤单、0 成交、0 错误。
  - 60 分钟筛选版：6 市场、1,214 条唯一成交、21,600 次盘口更新、180 笔报价、174 次撤单、0 成交。
  - 45 分钟宽候选池：7 市场、1,405 条唯一成交、21,028 次盘口更新、161 笔报价、154 次撤单、0 成交。
  - 30 分钟 `improveTicks=2`：7 市场、1,406 条唯一成交、14,656 次盘口更新、105 笔报价、98 次撤单、0 成交。
- 审计：
  - 2 小时基线 `trades.csv` 1,799 行，按 tx/token/side/size 分组重复键 0。
  - 240 个唯一报价订单、236 个唯一撤单订单，引擎错误 0，未知状态 0。
  - `summary.json` 的 `haltedReason` 和 `lastErrors` 均为空。
- 结论：
  - 执行链路稳定，但当前策略在真实盘口上没有获得 fill。
  - 1 tick 价差市场无法 post-only 改善；宽价差市场 taker 流不足。
  - 不能把“无错误”当成“有 edge”，当前不应实盘、不应跟单。
- 证据：
  - `backend/build/research/event-maker-shadow-2h-20261019/`
  - `backend/build/research/event-maker-shadow-filtered-1h-20261019/`
  - `backend/build/research/event-maker-shadow-filtered-wide-45m-20261019/`
  - `backend/build/research/event-maker-shadow-aggressive-30m-20261019/`
  - 正式报告：`docs/zh/event-maker-shadow-2026-10-19.md`
### 03:12 最终全量测试

- 命令：`.\gradlew.bat test --console=plain`
- 结果：95 个测试套件、428 个测试、0 失败、0 错误、2 跳过。
- event-maker 专项：45 个测试，0 失败。
- 额外修复：
  - 发现并修复 Spring 执行端口 Bean 歧义：`ClobEventMakerExecutionAdapter` 标记为 `@Primary`。
  - 新增测试验证真实适配器是主执行端口。
- 证据：`backend/build/test-results/test/`