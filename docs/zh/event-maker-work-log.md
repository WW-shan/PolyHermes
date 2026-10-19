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
### 03:00 最终全量测试

- 命令：`.\gradlew.bat test --console=plain`
- 结果：95 个测试套件、428 个测试、0 失败、0 错误、2 跳过。
- event-maker 专项：45 个测试，0 失败。
- 额外修复：
  - 发现并修复 Spring 执行端口 Bean 歧义：`ClobEventMakerExecutionAdapter` 标记为 `@Primary`。
  - 新增测试验证真实适配器是主执行端口。
- 证据：`backend/build/test-results/test/`
### 03:05 提交并推送

- 提交：`6eca4ff feat: complete event maker execution and shadow validation`
- 推送：`origin/main`，`e48164a..6eca4ff`
- 状态：代码与验证已提交；计划/日志收尾修改尚未提交（以本段记录产生时为准）。
- 说明：实盘小资金 smoke 仍未执行，原因是当前没有提供可用账户、资金和明确授权；mock/引擎级执行链路已验证。
### 03:07 文档审计与执行记录收尾

- 目标：把当前计划、实际动作、命令、结果、证据和阻塞项写入仓库，确保下一轮不依赖聊天记忆。
- 前置状态：`main` 分支；`6eca4ff` 已推送；仅 `event-maker-delivery-plan.md` 和 `event-maker-work-log.md` 有未提交修改。
- 执行的检查：
  - `git status --short`
  - `git log -3 --oneline`
  - `git diff -- docs/zh/event-maker-delivery-plan.md docs/zh/event-maker-work-log.md`
  - 从 `backend/build/test-results/test/TEST-*.xml` 汇总测试数。
- 检查结果：
  - 全量测试产物：95 suites / 428 tests / 0 failures / 0 errors / 2 skipped。
  - 发现并修正日志时间线错误：原记录写 `03:12` 测试、`03:20` 提交，但 Git 实际提交时间为 `2026-10-19 03:05:36 +08:00`；现已改为 `03:00` 和 `03:05`。
  - 文档此前误写“工作区干净”，已修正为代码提交后仍有文档收尾修改。
- 文档动作：
  - 更新计划顶部状态、当前基线、验收矩阵、当前检查点和下一步。
  - 明确真实小资金 Live smoke 未执行，且当前不应实盘、不应跟单。
  - 明确下一步先完成文档提交，再继续策略 edge 研究；真实 Live smoke 必须等待账户、资金和显式授权。
- 证据：本文件及 `docs/zh/event-maker-delivery-plan.md`。
- 下一步：提交并推送本轮文档，随后核对 `git status --short` 和 `git log -2 --oneline`。
### 03:11 文档收尾提交与推送完成

- 提交：`c74c131 docs: record event maker execution status`。
- 推送：`origin/main`，`6eca4ff..c74c131`。
- 核对：本地 `HEAD` 与 `origin/main` 均为 `c74c131b34346eba18a3d5e01006c898627de829`。
- 结果：计划、验收矩阵、运行手册和工作日志已同步；工作区干净。
- 说明：本段是后续仅文档记录提交，不改变代码或运行行为。
- 当前阻塞：真实小资金 Live smoke 仍等待可用账户、资金和明确授权。
### 03:14 策略 edge 根因调查开始

- 本轮目标：查清统一 Shadow 多次 0 fill 的原因，验证“队列优先级被定时撤单破坏”的假设，并改进执行策略。
- 已执行检查：
  - 读取 2 小时 Shadow 的 `events.jsonl`、`trades.csv`、`snapshots.csv`、`markets.json`。
  - 按订单存活窗口重放成交：240 个报价订单中，有 51 笔反向成交穿过报价价格；但模拟器全部被前方队列挡住。
  - 统计 2 小时 `trades.csv`：1,799 行，运行窗口内 194 行，运行开始前历史成交 1,605 行。
  - 检查当前 CLOB 盘口：中国入侵台湾市场 `0.979` 买盘约 5,920 股，说明 25 USDC 小单前面确实可能有较大队列。
- 关键发现：
  - 订单每 120 秒无条件撤单重挂，队列优先级每次归零。
  - 当前 Shadow 没有记录下单时前方队列和队列消耗，无法直接区分“队列太深”和“模拟器错误”。
- 计划动作：
  1. 增加 Shadow 队列诊断并输出 `execution-diagnostics.json`。
  2. 修改统一引擎：价格未变时保留订单；价格变化或超过最大订单年龄时才撤单。
  3. 增加单元测试覆盖保留队列、价格变化撤单、超时撤单。
  4. 运行更长订单寿命的 Shadow 实验，比较 fill、markout、PnL。
- 证据：`backend/build/research/event-maker-shadow-2h-20261019/`、本文件、`event-maker-delivery-plan.md`。
### 04:18 队列保留、活跃度筛选与 1-tick 往返实验

- 本轮目标：从“能运行但 0 fill”推进到“可解释、可成交、可审计”的 maker 策略。
- 代码动作：
  - `EventMakerLiveEngine` 改为价格未变时保留订单队列；价格变化且超过冷却时间、盘口过期或超过最大订单年龄才撤单。
  - `EventMakerConfig` / DTO 增加 `maxOrderAgeSeconds`、`minRecentTrades30m`、`minRecentNotional30m`、`minRecentTradesPerSide30m`。
  - `LivePaperDataClient` 增加近 30 分钟成交笔数、成交额和买卖两侧活跃度筛选。
  - `EventMakerMarketSelector` 增加候选级评估和过滤原因，新增买一/卖一队列上限、目标卖价高于卖一时的退出队列处理。
  - `EventMakerQuotePlanner` 增加报价时价差过滤；1-tick 市场允许显式加入队列；卖出价高于卖一时前方队列按 0 处理。
  - `EventMakerShadowExecutionPort` 输出 `execution-diagnostics.json`，记录下单队列、已消耗队列、穿越成交和成交份额。
  - Shadow CLI/Gradle 增加价格带、重挂冷却、最大订单年龄、退出 tick、退出价差、订单名义金额和活跃度参数。
- 测试：event-maker + research 定向测试 `61 tests / 0 failures`。
- 实验 1：30 分钟“不主动重挂”控制组，7 市场、14 次报价、0 fill、0 错误。结论：长期保留旧价格订单会形成陈旧报价，不能作为主策略。
- 实验 2：2 分钟活跃度冒烟，选中 `Xi Jinping out before 2027?`，买一队列 20 股，诊断文件正常。
- 实验 3：2 小时 1-tick 往返 Shadow 正在运行，输出 `backend/build/research/event-maker-shadow-xi-1tick-2h-20261019/`；当前选中 3 个市场，包含 Xi Jinping、Glucksmann 和 Flávio，参数为 `improveTicks=0`、`exitImproveTicks=0`、`exitSpreadCents=0.1`、`maxQueueAheadShares=5000`、`requoteSeconds=300`。
- 回测：864 配置高队列网格正在独立运行，输出 `backend/build/research/event-maker-queue-grid-20261019-summary.csv`；队列因子覆盖 1/2/5/10/20/50，入场偏移覆盖 0/0.25/0.5/1 美分。
- 下一步：等待 Shadow 和回测完成，检查 fill、markout、回合 PnL、队列诊断和训练/测试稳定性；没有成交或没有稳定 edge 就继续迭代，不进入实盘。

### 04:38 活跃度窗口修正与最终 2 小时实验

- 发现：初始报价盘口新鲜度和已挂订单撤单阈值混用，安静市场 30 秒无更新就撤单，导致队列反复重置。
- 修复：新增 activeOrderStaleBookSeconds；盘口不新鲜时不再把 desired=null 当成价格变化；只有盘口恢复新鲜且价格确实变化后才重挂。
- 活跃度：新增近 2 小时成交笔数、成交额和买卖两侧成交数过滤，避免 30 分钟窗口漏掉脉冲式市场。
- 回测：30 市场只有 2 个可交易样本；100 市场 0.97~0.99 基准 10 个可交易、4 个成交、ROI +1.02%；300 市场 31 个可交易、14 个成交、ROI +1.02%，队列因子 1~50 的头部 ROI 约 +2.06%~+2.10%，但没有稳健配置。
- 正在运行：2 小时 Shadow 输出 event-maker-shadow-2h-active-2h-20261019，选中 Xi Jinping 和 Flavio，使用 1-tick 往返、2 小时活跃度、盘口静默 300 秒、已挂订单过期 1800 秒。
- 正在运行：1000 市场、864 配置回测，输出 event-maker-queue-grid-1000m-20261019-summary.csv。
- 当前判断：仍不能宣称稳定 edge，等待 Shadow 成交和 1000 市场回测结果。
### 04:51 1000 市场稳健回测与 1 美分被动入场

- 回测：1,000 市场、864 配置，成交历史 1000/1000 抓取成功。
- 首个稳健结果：
  - 配置 `p0.97-0.99_off1.0_spread2.0_q1.0_c1_r0`
  - 可交易市场 103，成交市场 41，总回合 41，全部 SELL，ROI +2.06%，最大回撤 0，胜率 100%。
  - 训练窗口 20 个成交市场、测试窗口 21 个成交市场；两端 ROI 分别约 +2.06%/+2.06%。
- 队列敏感性：同一配置在 queueFactor 5/10/20/50 下仍有正 ROI；queueFactor 10 时有 40 个成交市场、5 个结算回合、ROI +1.94%，说明结果对队列深度不是完全敏感。
- 仍需警惕：成交全部或绝大多数为 SELL，100% 胜率，回测没有真实 L2 队列、撤单竞争和 adverse selection；不能直接实盘。
- 代码：实时报价器新增 `entryOffsetCents`，可把买入价放到买一下方 1 美分，队列为 0；卖出使用 entry + 2 美分目标。
- 实时实验：2 小时 Shadow `backend/build/research/event-maker-shadow-passive-2c-2h-20261019/` 已启动，选中 Israel x Iran、Xi Jinping、China Taiwan，分别挂 0.960、0.963、0.968 的被动买单。
- 正式报告：`docs/zh/event-maker-robust-backtest-2026-10-19.md`。
- 下一步：观察是否出现真实 dip fill；若 2 小时无成交，延长 Shadow 运行时间，而不是直接降低价格条件。

### 05:05 建立可恢复执行台账

- 本轮目标：先把计划、后台进程、实际动作、命令、证据和恢复顺序写入仓库，再继续研究，不依赖聊天记忆。
- 动作：创建 `docs/zh/event-maker-execution-ledger-2026-10-19.md`，包含目标边界、任务队列、2h/12h Shadow 会话、动作时间线、关键命令、证据规则和恢复顺序。
- 动作：更新交付计划顶部恢复入口、当前检查点、最新提交状态和策略研究下一步。
- 动作：清理工作日志中的反引号加 n 字面量，拆成独立 Markdown 行。
- 核对：轮询会话 `47525`、`58042`，两个 Shadow 均仍运行；尚无 fill，`markouts.csv` 为空。
- 下一步：审查 1,000 市场回测分布和异常，再补费用、延迟、队列及年份样本外敏感性测试。
### 2026-10-19 05:27 完整池证伪与回测方法修正

- 数据：完整候选池 4,187 个市场，成交历史抓取 4,187/4,187 成功，864 组配置完成。
- 1,000 市场初步配置 p0.97-0.99_off1.0_spread2.0_q1.0_c1_r0 在完整池变为：可交易 450、成交 179、SELL/SETTLE 177/2、胜率 98.9%、PnL +164.99、ROI +0.92%、最大回撤 100、持有基线 +1.11%。
- 训练期唯一选参：p0.97-0.99_off0.5_spread2.0_q1.0_c3_r0，训练 ROI +1.10%、测试 ROI +0.56%、总 ROI +0.81%。
- 队列压力：off1/spread2/c1/r0 配置在 q1/q10/q20/q50 下 ROI 分别为 +0.92%/+0.41%/+0.006%/-0.77%，深队列下 edge 消失。
- 尾部：179 回合中 2 次结算亏损，每次 -100 USDC；实际亏损率 1.12%，盈亏平衡亏损率约 2.02%，Wilson 95% 上界约 3.98%，仍不能排除负期望。
- 方法修复：回测参数选择改为只用训练期；最大回撤改为按回合退出时间计算；新增最大浮亏、time-to-fill、60 秒 markout、结算亏损数和盈亏平衡亏损率；新增 0/10/25/50/100 bps maker fee 敏感性输出。
- Shadow 修复：新增 shadowFillVolumeFactor，避免旧模拟器“任意反向成交即成交”的乐观假设；markout 扩展为 60/300/1800 秒并记录盘口年龄；成交价差 tick 计算改为 HALF_UP，避免浮点边界误判。
- 测试：BacktestEngine、ShadowExecutionPort、QuotePlanner 定向测试通过；中途发现 factor=0 部分成交回归并已修复。
- 已完成：修正后完整池复跑 `backend/build/research/event-maker-queue-grid-full-v2-20261019`，生成训练期选参、费用敏感性、MAE 和 markout 的正式结果。
- 当前判断：策略是薄价差被动 maker，收益依赖队列优先权和低尾部损失率；当前没有足够证据投入真实资金。

### 05:35 修正后完整池结果与 CLI 冒烟

- 修正后完整池：4,187/4,187 市场抓取成功，864 配置完成。
- 训练期选参：`p0.97-0.99_off0.5_spread2.0_q1.0_c3_r0`，成交 240、回合 278、SELL/SETTLE 266/12、3 次结算亏损。
- PnL +224.37、ROI +0.81%、训练 +1.10%、测试 +0.56%、最大已实现回撤 146.49。
- 最大浮亏 -100、平均最大浮亏 -6.99、P10 最大浮亏 -11.35；60 秒 markout 平均 +0.392，21/278 为负。
- 50bps 双边 maker fee 下总 ROI -0.18%、测试 -0.43%；100bps 下 -1.17%。
- 发现并修复 `printSummary` 新参数顺序错位导致的 `IllegalFormatConversionException`。
- 20 市场 CLI 冒烟通过：summary、balanced markets、top markets、top cycles 均正常写出，console 的 MAE/time-to-fill/tailLoss/breakEven 字段正常显示。
- 正式报告已改为完整池结论，运行手册已同步新参数、保守成交因子和 markout。
- 额外发现并修复 Spring 装配错误：@Component 原先误标在 EventMakerShadowFill，已移到 EventMakerShadowExecutionPort，并新增回归测试。
### 06:03 保守 Shadow 冒烟与停止语义修复

- 10 分钟保守 Shadow：1 个市场、201 笔唯一公共成交、35,599 次轮询重复被去重、348 次盘口更新、1 次报价、0 fill、0 错误。
- 发现：10 分钟到期退出时模拟订单仍为 LIVE，`execution-diagnostics.json` 的 `activeOrders=1`。
- 修复：Shadow CLI 在退出路径调用 `cancelAll`，记录 `stop` 撤单并刷新最终快照。
- 1 分钟复测：200 笔唯一成交、1 次报价、1 次退出撤单、0 fill、0 错误；`activeOrders=0`，订单 `CANCELLED`，最终快照 `FLAT`。
- 证据：`backend/build/research/event-maker-shadow-v2-smoke-20261019/`、`backend/build/research/event-maker-shadow-v2-smoke2-20261019/`。

### 06:10 提交与推送

- 提交：`06d168f feat: harden event maker shadow and full-pool validation`。
- 推送：`179f2b2..06d168f main -> main`。
- 推送前全量测试：95 suites / 445 tests / 0 failures / 0 errors / 2 skipped。
### 06:52 旧长测失效与当前市场空窗

- 旧 2h Shadow 在结束时触发 `NoSuchMethodError`：长进程启动后重新编译了同一 classpath，退出时加载到新构造器签名，未能生成有效 `summary.json`。结论：该运行不能作为证据。
- 为避免 12h 组在结束前重复同类错误，已停止旧 12h 进程；停止前仍为 0 fill。
- 用最终代码重启 2h 时，0.97～0.99 价格带没有合格市场；唯一活跃候选 `US x Iran ceasefire continues through October 22?` 的 favorite 买一为 0.95，被 `price_out_of_range` 拒绝。
- 决定：不降低价格带、不伪造成交；等待出现 0.97～0.99 活跃市场后再启动干净长测。
- 操作规则：Shadow 长进程启动后，不再执行 `compileKotlin`/`test`，避免同 classpath 类版本冲突。
- 当前无 Shadow 进程在运行。

### 06:57 新轮次计划与动作基线

- 目标：继续完成事件做市策略验证，优先修复 0.97～0.99 当前无市场的候选发现问题，再恢复干净 Shadow 长测。
- 恢复命令：`git status --short`（无输出）、`git diff --check`（无输出）、`git log -5 --oneline`；最新提交 `5994b4c`，主分支 `main`。
- 进程命令：`Get-Process java` 与 `Get-CimInstance Win32_Process -Filter "Name='java.exe'"`；仅有 Kotlin/Gradle daemon，无 Shadow/Backtest。
- 已读证据：执行台账、交付计划、运行手册和工作日志尾部；保留完整池回测、Shadow v2 冒烟和旧长测失效记录。
- 代码审查：`LivePaperDataClient.discoverMarkets()` 当前每个 tag 只请求一页 100 条，随后 `sortedByDescending(volume24hr).take(40)`，再进行最近成交过滤；存在高成交额截断偏差。
- 下一步：先补文档检查点；再实现分页、资格过滤前移和阶段计数；补测试；运行定向/全量测试；提交后检查真实候选并启动干净 Shadow。
- 约束：Shadow 长进程启动后禁止 `compileKotlin`/`test`；真实下单仍缺 `accountId`、资金上限和明确授权。
### 07:15 Gamma 分页、预筛与发现诊断实现

- 动作：`LivePaperDataClient` 新增 `discoverMarketsDetailed()`；Gamma 按 tag 分页，默认每页 100、每标签最多 20 页，最多覆盖 2,000 条高成交额市场。
- 动作：移除 `sortedByDescending(volume24hr).take(40)` 的硬截断；解析阶段先按 24h 成交额、结束时间、negRisk、订单簿、favorite bid 和价格带（含 1 美分容差）过滤，再对候选做最近成交过滤。
- 动作：最近成交阈值为 0 时不再调用 Data API；非 0 时逐个候选检查 30 分钟和 2 小时成交笔数、成交额及买卖两侧笔数。
- 动作：新增 `MarketDiscoveryStats`/`MarketDiscoveryResult`，记录每标签页数、扫描行数、合格市场数、活动过滤前后数量和停止原因；Shadow 写出 `market-discovery.json`，并嵌入 `summary.json`。
- 动作：新增 `--discovery-pages` / `-PshadowDiscoveryPages`，默认 20；EventMaker 服务把 `config.minPrice`/`maxPrice` 传给发现阶段。
- 动作：`LivePaperDataClient` 增加可注入的 Gamma/Data/CLOB base URL，便于本地 HTTP 测试，无需新增依赖。
- 测试：新增 `LivePaperDataClientDiscoveryTest`，覆盖“价格带市场在第 40 条之后仍能发现”“零活动阈值不请求 Data API”“活动阈值拒绝无成交市场”“页数上限明确报告”。
- 失败与修复：首次测试 4 个中 3 个失败，原因是测试桩第一页只返回价格带外市场；已改为按测试注入 responder。另修正停止原因判断，区分 `all_tags_exhausted`、`page_cap_reached` 和 `tag_error`。
- 结果：`LivePaperDataClientDiscoveryTest` 4/4 通过；livepaper + eventmaker + service.eventmaker 定向测试全部通过。
- 观察：一次真实 Gamma 只读审计扫描 Geopolitics 1,477 条，发现 8 个 favorite bid >= 0.88 的合格市场，其中价格带附近候选位于第一页；该审计不作为完整市场统计，待新 Shadow 的 `market-discovery.json` 复核。
- 下一步：运行全量测试；提交；用新代码执行短 Shadow 发现，检查 `market-discovery.json` 和 `market-selection.json` 后再决定长测。
### 07:17 全量回归通过

- 命令：`.\gradlew.bat test --console=plain`。
- 结果：`BUILD SUCCESSFUL`；96 个测试套件、449 个测试、0 failures、0 errors、2 skipped。
- 新增测试：`LivePaperDataClientDiscoveryTest` 4/4 通过。
- 结论：候选发现分页和诊断代码通过全量回归，可提交后用真实 API 做短 Shadow 发现验证。
### 07:26 Gamma keyset 切换与真实候选审计

- 根因确认：传统 `GET /markets` 响应头明确提示 `use /markets/keyset`；offset 分页在约 2,000 条后返回 422，不能作为完整扫描依据。
- 动作：候选发现切换到官方 `GET /markets/keyset`，使用 `next_cursor`/`after_cursor` 键集分页，移除 offset 上限依赖；默认最多 50 页、每页 100，最大允许 100 页。
- 动作：由于请求按 `volume24hr` 降序，某页最后一条低于 `minVolume24hr` 时停止后续低成交额页面，并记录 `volumeFloorReached=true`；停止原因改为 `volume_floor_reached`，避免误报 `all_tags_exhausted`。
- 动作：`market-discovery.json` 改为保存完整发现结果，包括 `qualifiedMarkets`、`activityPassedMarkets`、逐候选 `activityDiagnostics`（30m/2h 笔数、成交额、买卖两侧笔数和拒绝原因）及阶段统计。
- 测试：新增 volume-floor 停止测试，扩展活动诊断断言；`LivePaperDataClientDiscoveryTest` 5/5 通过。
- 全量：96 suites / 450 tests / 0 failures / 0 errors / 2 skipped。
- 真实 Shadow 发现运行：`backend/build/research/event-maker-shadow-keyset-v3-20261019/`。
  - Politics 1 页/100 条，Geopolitics 1 页/100 条；两标签均因最后一条低于 100,000 的 24h 成交额而停止，超出该门槛的候选已全部覆盖。
  - 价格预筛后 6 个候选；严格活动过滤检查 6 个，1 个通过。
  - 唯一通过者为 `Israel x Iran ceasefire continues through October 21?`：30m 6 笔/1009.10 USDC、买卖各 3 笔；2h 21 笔/15381.04 USDC。
  - 其盘口为 best bid 0.975、best ask 0.976，价差 1 tick，未满足 `minSpreadTicks=2`，因此 `marketSelection selected=0 reasons={spread_below_min=1}`。
  - 其余 5 个候选主要因 30 分钟成交笔数低于 3 被过滤，不能通过放宽活动门槛伪造活跃度。
- 结论：这不是“发现逻辑空窗”，而是当前严格条件（0.97～0.99、成交额 >=100k、活动门槛、价差 >=2 tick）下确实没有可报价市场。不得降低价差或活动门槛来制造成交。
- 当前无 Shadow/Backtest 进程；等待满足全部条件的真实市场后再启动干净 2h/12h Shadow。
### 07:29 提交 keyset 修复

- 提交：`7b0bf7b feat: use keyset pagination for market discovery`；已推送 `a2e14c7..7b0bf7b main -> main`。
- 全量测试：96 suites / 450 tests / 0 failures / 0 errors / 2 skipped。
- 当前无 Shadow/Backtest 进程；等待满足价格带、成交额、活动度和 2-tick 价差的真实市场。
### 07:32 清理失效与一次性运行目录

- 清理范围仅限 `backend/build/research/`，删除前逐项验证解析后的绝对路径位于该目录下。
- 已删除 9 个失效或已被正式证据替代的目录：
  - `event-maker-shadow-fill-smoke-20261019`
  - `event-maker-shadow-fill-smoke2-20261019`
  - `event-maker-shadow-fill-smoke3-20261019`
  - `event-maker-shadow-fill-smoke4-20261019`
  - `event-maker-shadow-final-2h-20261019`
  - `event-maker-discovery-audit-20261019`
  - `event-maker-shadow-discovery-20261019`
  - `event-maker-shadow-keyset-20261019`
  - `event-maker-shadow-keyset-v2-20261019`
- 保留：`event-maker-shadow-keyset-v3-20261019`（最终 keyset 候选诊断证据）、`event-maker-shadow-v2-smoke-20261019`、`event-maker-shadow-v2-smoke2-20261019`、完整池/费用敏感性正式 CSV。
- 删除前已搜索 `docs/` 和 `backend/src/`，上述目录没有被正式报告引用。
### 07:36 自动等待合格市场的计划

- 背景：严格条件下当前无 2-tick 市场；手动短跑只能重复退出，不能形成有效等待。
- 计划：给 Shadow CLI 增加 `waitForMarketMinutes` 和 `discoveryRetrySeconds`；每轮重新执行 keyset 发现、活动过滤、盘口获取和选择器评估。
- 计划：每轮写入 `discovery-attempts.jsonl`，保留时间、预筛候选数、活动通过数、选择结果、拒绝原因和停止原因；`market-discovery.json`/`market-selection.json` 保留最新一轮。
- 计划：等待期间不打开 WebSocket、不报价；出现合格市场后才启动原有 Shadow 引擎和成交/回合/markout 审计。
- 计划：超时写出 `market-watch-timeout.json` 并明确失败，不伪造 summary 或 fill。
- 安全边界：等待和自动启动仍为 Shadow，不下真实订单；长进程启动后禁止重编译或测试。
### 07:43 自动等待合格市场模式完成

- 动作：Shadow CLI 新增 `waitForMarketMinutes` 和 `discoveryRetrySeconds`；等待循环每轮重新执行 keyset 发现、活动过滤、tick/book 获取和 selector 评估。
- 动作：新增 `discovery-attempts.jsonl`，每轮记录预筛候选数、活动通过数、最终选择数、选择原因和 discovery 停止原因。
- 动作：`market-discovery.json` 和 `market-selection.json` 保留最新一轮；超时写出 `market-watch-timeout.json`。
- 动作：启动时删除上次遗留的 `summary.json`、`state.json`、`execution-diagnostics.json`、`market-watch-timeout.json`、`markets.json`，避免把旧证据误当成本次结果。
- 动作：`summary.json` 新增 `discoveryAttempts`；只有真正选出市场后才打开 WebSocket、启动 Shadow 引擎并产生 fill/cycle/markout。
- 冒烟 1：`waitForMarketMinutes=0`，1 次尝试后超时；`discovery-attempts.jsonl` 和 `market-watch-timeout.json` 正确写出。
- 冒烟 2：`waitForMarketMinutes=1`、`discoveryRetrySeconds=15`；完成 5 次尝试后超时，每次均记录 `qualifiedMarkets=6`、`activityPassedMarkets=0`、`selectedMarkets=0`、`stoppedReason=volume_floor_reached`。
- 冒烟证据：`backend/build/research/event-maker-shadow-wait-smoke-20261019/`、`backend/build/research/event-maker-shadow-wait-loop-smoke-20261019/`。
- 全量测试：96 suites / 450 tests / 0 failures / 0 errors / 2 skipped。
- 注意：等待期间行情会变化；两次真实检查中活动通过数从 1 变为 0，说明必须持续重新发现，不能缓存首次候选。
- 下一步：提交自动等待模式；启动长时间后台等待+Shadow 任务，并持续检查 attempt 日志、summary、markouts 和 diagnostics。
### 07:50 启动后台等待与 2 小时 Shadow 的计划

- 输出目录：`backend/build/research/event-maker-shadow-watch-20261019/`；stdout/stderr 写到同目录 `stdout.log`、`stderr.log`。
- 参数：最多等待 180 分钟，60 秒重新发现一次；出现合格市场后 Shadow 运行 120 分钟。
- 严格条件保持不变：价格为 0.97～0.99、24h 成交额 >=100,000、30m/2h 活动门槛、`minSpreadTicks=2`、保守成交因子 1。
- 安全边界：只读公共盘口和成交，不签名、不下真实订单；等待阶段也不打开 WebSocket。
- 运行期间禁止执行 `compileKotlin`、`test` 或其他会改写同一 classpath 的任务。
- 完成后检查：`discovery-attempts.jsonl`、`market-discovery.json`、`market-selection.json`、`summary.json`、`execution-diagnostics.json`、`markouts.csv`、`state.json`。
### 07:47 后台等待任务已启动

- 启动命令：通过隐藏的 PowerShell 包装脚本启动 `eventMakerShadow`，参数见 `backend/build/research/event-maker-shadow-watch-20261019/run.ps1`。
- 进程树：
  - PowerShell 包装：PID `3764`
  - `cmd.exe`：PID `10628`
  - Gradle JavaExec：PID `24888`
- 输出：`backend/build/research/event-maker-shadow-watch-20261019/`
- 启动参数：最多等待 180 分钟、每 60 秒重新发现、选出市场后 Shadow 运行 120 分钟；严格价格带、活动度和 2-tick 价差条件不变。
- 首次状态：`qualified=6 activityPassed=0 selected=0 stopped=volume_floor_reached`，正在等待第 2 次发现。
- 运行期间禁令：不执行 `compileKotlin`、`test` 或其他会改写 classpath 的任务；只允许读取日志、写入文档和提交文档。
### 07:55 后台状态检查与 Shadow 审计脚本

- 新增 `scripts/check-event-maker-watch.ps1`：检查包装进程、JavaExec、attempt 数、最近一次发现结果，以及 waiting/summary/timeout 状态。
- 检查脚本已实际运行，当前输出：PID 3764/24888 存活，`state=waiting`，已完成 9 轮发现，最新一轮仍为 qualified=6、activityPassed=0、selected=0。
- 新增 `scripts/audit-event-maker-shadow.ps1`：校验 summary、execution diagnostics、state、trades 和 markouts 的一致性，并写出 `shadow-audit.json`。
- 审计脚本在 `event-maker-shadow-v2-smoke2-20261019` 上运行通过：fills/stateFills 均为 0、cycles/stateCycles 均为 0、activeOrders=0、重复成交行=0、failures=[]、passed=true。
- 该审计只能验证内部一致性，不能替代真实 fill/PnL 证据，也不能把 mock/Shadow 结果冒充实盘。
### 07:58 校验 maker/taker 费用口径

- 复核 `PolymarketTradingFee.kt` 和测试：maker 不收费；taker 使用 `shares × rate × p × (1-p)`；项目测试覆盖 politics/crypto/sports 等费率与边界。
- 回测报告已补充：0bps maker 是当前基线，10/25/50/100bps 是压力场景，不是当前 maker 收费事实。
- 风险结论不变：真实队列、adverse selection、尾部结算和 markout 仍需 Shadow 证据；若订单实际跨价成为 taker，必须重新计算。
### 08:02 尾部亏损与 markout 复核

- 复核 `event-maker-queue-grid-full-v2-20261019-robust-cycles.csv` 中 3 个结算亏损回合。
- 三笔亏损的 60 秒 markout 分别约为 -99.90、-99.90、-99.90 USDC，说明成交后价格几乎立即跌到接近 0。
- 结论：这类尾部风险不能靠成交后止损解决；只能通过成交前拒绝、缩小单笔、分散市场和避免突发消息敏感市场来控制。
- 该复核不改变当前“不能实盘”的结论。
### 08:04 一档价差过滤与回测口径差异复核

- 回测参数中的 `spread` 是 `exit_spread_cents`（入场后目标卖出价差），不是实时选择器的 `minSpreadTicks`；历史回测没有显式排除一档价差市场。
- 实时选择器的 `minSpreadTicks=2` 是后来加入的防御性门槛，目的是避免无 `entryOffsetCents` 时排在买一深处；它不是回测已验证的参数。
- 当前一档价差市场在 `entryOffsetCents=0.5` 下理论上买单会下移到买一下方、队列为 0，但这还没有经过严格 Shadow 验证。
- 决策：仍保持 `minSpreadTicks=2` 和当前活动门槛，不用一档市场或降低门槛制造 fill；把“一档价差 + 0.5 美分偏移”列为未来独立实验臂，必须单独记录结果，不能混入本次正式验证。
### 08:10 停止无成交长等待并增强 attempt 审计

- 已安全停止上一轮后台任务 PID 3764/10628/24888；停止前完成 15 轮发现，全部为 qualified=6、activityPassed=0、selected=0，无 fill、无真实订单。
- 原因：旧 `discovery-attempts.jsonl` 只有数量，没有逐轮活动拒绝原因，长时间运行会丢失审计信息。
- 修复：`DiscoveryAttemptLog` 增加 `activityRejectionReasons`，每轮记录如 `recent_trades_30m_below_min=6` 的聚合原因。
- 0 分钟真实冒烟：已写出 `activityRejections={recent_trades_30m_below_min=6}`，并正确生成 `market-watch-timeout.json`。
- 证据：`backend/build/research/event-maker-shadow-wait-reason-smoke-20261019/`。
- 全量测试：96 suites / 450 tests / 0 failures / 0 errors / 2 skipped。
- 下一步：提交增强后的版本，重新启动后台等待；等待期间只轮询和写文档，不编译。
### 08:05 重启增强后的后台等待任务

- 新后台任务 PID：PowerShell `7652`，JavaExec `14836`；输出 `event-maker-shadow-watch-v2-20261019/`。
- 首轮：qualified=6、activityPassed=0、selected=0，`activityRejections={recent_trades_30m_below_min=6}`；等待循环正常进入下一次重试。
- 检查脚本默认目录已切到 v2；运行期间禁止编译和跑测试。
### 08:09 增强后台状态检查

- `check-event-maker-watch.ps1` 增加 stdout/stderr 异常扫描：Data API 失败、Gamma 失败、HTTP 429、线程异常、OOM。
- 已实际运行：`logIssues=0`、`stderrBytes=0`；当前 v2 等待任务存活，已完成 3 轮，最近一轮仍为 qualified=6、activityPassed=0、selected=0。
- 该检查只用于观测，不影响正在运行的 Shadow 进程。
### 08:18 只读审查发现状态安全缺口并停止等待任务

- 动作：停止等待中的只读 Shadow/发现任务；包装进程 PID 7652、JavaExec PID 14836，停止前均存活。
- 停止前状态：共 12 轮发现，最近一轮 `qualifiedMarkets=6`、`activityPassedMarkets=0`、`selectedMarkets=0`，拒绝原因全部为 `recent_trades_30m_below_min=6`；`logIssues=0`、`stderrBytes=0`。
- 证据：`backend/build/research/event-maker-shadow-watch-v2-20261019/discovery-attempts.jsonl`、`market-discovery.json`、`stdout.log`。
- 停止原因：只读代码审计发现实盘状态恢复/对账缺口，需要先修复并回归测试，不能在旧实现上继续等待：
  1. `EventMakerStateStore.load()` 对损坏 JSON 和不支持版本返回 `null`，LIVE 可能把未知旧状态当作全新启动。
  2. 已持久化状态若属于其他账户会被静默忽略，旧订单/持仓可能失去跟踪。
  3. 不在本轮发现列表中的活动订单只撤销、不取得终止快照；已有持仓会被后续状态写入丢弃。
  4. 部分卖出后完成回合时，`EventMakerCycleRecord` 的 PnL/股数只包含最后一次卖出，和 `realizedPnl` 不一致。
- 安全影响：以上问题不影响这次纯只读 Shadow，但不满足真实资金“未知状态必须 fail-closed”和逐回合可对账要求。
- 下一步：先修状态存储、启动恢复、孤儿持仓/订单和部分成交回合记账；补测试；全量回归；提交后再重启干净 Shadow。
### 08:20-08:40 Live 状态恢复与停止对账加固

- 状态文件读取由“损坏即返回 null”改为 fail-closed：损坏 JSON、空文件、版本不支持、LIVE 缺少账户、重复 condition、缺失 favorite token、非法库存/订单快照都会拒绝加载。
- 状态所有权校验：持久化状态必须属于当前 LIVE 账户；启动前读取账户全部活跃订单，任何不在状态文件中的订单都会以 `unmanaged_open_orders` 拒绝启动。
- 持仓校验：通过 Data API 读取账户当前正数持仓 conditionId；未登记持仓或状态中有库存但账户无对应持仓时拒绝启动；同一 condition 出现多个资产也拒绝。
- 孤儿处理：不在本轮发现列表的活动订单先撤销，再读取终止快照确认无成交；孤儿库存、撤单失败、仍有成交或无法确认状态全部 fail-closed。
- 停止清理：撤单后必须读取最终订单快照；部分成交先写入库存和 cycle，再清除 activeOrder；查询失败或订单仍活跃时保留 `activeOrder*`。
- 部分卖出记账：`EventMakerCycleRecord` 现在累计同一入场后的全部卖出股数和 PnL，而不是只记录最后一次卖出。
- 持久化新增 `favoriteTokenId`，恢复时校验 conditionId + favorite token 完全一致；逐笔 fills 会重放并核对 inventory、realizedPnl、completedCycles 和 cycle records。
- 风险预算现在计入未成交买单敞口，避免多个未成交买单绕过 `maxTotalInventoryNotional`。
- 协程作用域由 `SupervisorJob` 改为普通 `Job`：持久化或对账子任务失败会终止整个 LIVE 运行，不再由主循环继续报价。
- 状态写入失败不再吞掉；运行中写入失败会触发退出清理；停止时写入失败会写入 `stop_state_persist_failed`。

### 08:41 稳健性交叉核验

- 新增 `scripts/analyze-event-maker-robustness.ps1`，对完整池结果做标签/价格桶 × 训练/测试分段、市场和自然月 cluster bootstrap、集中度和参数选择脆弱性分析。
- 新增报告 `docs/zh/event-maker-robustness-crosscheck-2026-10-19.md`，并在主回测报告增加第 13 节摘要。
- 结果：全部市场 ROI +0.807%，95% cluster bootstrap 区间 [-0.647%, +1.904%]；自然月聚类区间 [-0.730%, +1.898%]；测试市场区间 [-1.545%, +1.931%]；均包含 0。
- 113 个训练期正收益配置中，41 个测试期非正（36.3%）；选中配置测试排名第 15。结论仍是“可继续 Shadow，不可实盘”。

### 08:45 Shadow 审计脚本自测

- `scripts/audit-event-maker-shadow.ps1` 增加逐笔 fill 重放，核对 inventory、realizedPnl、completedCycles、cycle 数量及 cycle PnL 总和。
- 构造临时有效证据：脚本 exit 0；将 cycle PnL 从 0.10 改为 0.04 后，脚本 exit 1，输出 `cyclePnlReplayMismatch:condition:0.04+0!=0.1`。
- 临时目录在 `backend/build/research/` 内校验绝对路径后删除。
- 复测历史 Shadow smoke：`fill=0`、`activeOrders=0`、`duplicateTradeRows=0`，审计通过。

### 08:46 回归与当前下一步

- 定向测试：`.\gradlew.bat test --tests "com.wrbug.polymarketbot.service.eventmaker.*" --console=plain`，BUILD SUCCESSFUL。
- 全量测试：97 suites / 473 tests / 0 failures / 0 errors / 2 skipped。
- 当前没有运行中的 Shadow 或回测进程；v2 等待任务在第 12 轮仍为 0 个活动通过市场，已因安全修复停止。
- 下一步：提交并推送本轮安全修复和文档；随后用新代码重启干净等待+Shadow。严格条件不变，不通过降低门槛制造 fill。
### 09:00 最终安全收口与回归

- 状态 schema 升到 version 2；新增状态级 `cumulativeRealizedPnl`，跨进程携带已完成市场的已实现 PnL。
- 风险策略、status 和状态写入统一使用“跨进程累计已实现 PnL + 当前库存浮亏”，避免重启后损失上限归零。
- conditionId 在账户持仓对账前统一小写，避免大小写差异造成误判。
- 新增 realized PnL baseline 单元测试：已有累计 1.25、恢复市场 0.75 时，baseline 为 0.50，不重复计数。
- 全量测试：97 suites / 474 tests / 0 failures / 0 errors / 2 skipped；eventmaker/research eventmaker 93 tests。
- 提交：`596504e fix: harden event maker live state recovery`；推送：`6bd6850..596504e main -> main`。
- 当前无 Shadow/Backtest 进程；下一步为重启新代码等待+Shadow。
### 09:06 v3 干净等待 + Shadow 重启

- 提交并推送：`596504e fix: harden event maker live state recovery`（`6bd6850..596504e`）。
- 新后台任务：PowerShell 包装进程 PID `6296`，JavaExec PID `7788`；启动 `2026-10-19T09:06:26+08:00`。
- 输出目录：`backend/build/research/event-maker-shadow-watch-v3-20261019/`。
- 参数保持严格：等待最多 180 分钟、60 秒重试、选出后 Shadow 120 分钟；0.97～0.99、24h >=100,000、30m/2h 活动门槛、`minSpreadTicks=2`、成交因子 1。
- 首次检查：attempt=1，`qualifiedMarkets=6`、`activityPassedMarkets=0`、`selectedMarkets=0`，拒绝原因 `recent_trades_30m_below_min=5`、`recent_notional_30m_below_min=1`；`logIssues=0`、`stderrBytes=0`，状态 waiting。
- `scripts/check-event-maker-watch.ps1` 默认目录已切到 v3。
- 运行期间禁止编译、测试或任何会重写 classpath 的任务。
### 09:15 持仓数量精确对账

- 账户级持仓校验从 conditionId 集合升级为 conditionId + 持仓数量映射。
- 同 condition 多资产继续拒绝；未登记持仓、缺失持仓和数量不一致都会以 `open_position_state_mismatch` 阻止 LIVE。
- 新增数量不一致测试：状态库存 5.00、账户持仓 3.00 时拒绝启动。
- 全量回归：97 suites / 475 tests / 0 failures / 0 errors / 2 skipped；eventmaker/research eventmaker 94 tests。
- v3 Shadow 已于 09:10 因本次安全修复停止；待提交后再次重启。
### 09:22 v4 严格等待 + Shadow 重启

- 持仓数量精确对账提交并推送：`3d30ba5 fix: reconcile event maker position sizes`（`76448e5..3d30ba5`）。
- 新后台任务：PowerShell 包装进程 PID `15340`，JavaExec PID `21804`；输出 `backend/build/research/event-maker-shadow-watch-v4-20261019/`。
- 参数未放宽：等待最多 180 分钟、60 秒重试、选出后 Shadow 120 分钟；0.97～0.99、24h >=100,000、30m/2h 活动门槛、`minSpreadTicks=2`、成交因子 1。
- 首次检查：attempt=1，qualified=7、activityPassed=0、selected=0；拒绝原因 `recent_trades_30m_below_min=5`、`recent_trades_2h_below_min=1`、`recent_notional_30m_below_min=1`；`logIssues=0`、`stderrBytes=0`，状态 waiting。
- `scripts/check-event-maker-watch.ps1` 默认目录已切到 v4。
- 运行期间禁止编译、测试或重写 classpath。
### 09:28 启动一档价差独立实验臂（只读）

- 背景：正式主臂要求 `minSpreadTicks=2`，历史上唯一通过活动过滤的市场因价差 1 tick 被拒；计划中已把“一档价差 + 0.5 美分偏移”列为必须单独记录的实验臂。
- 做法：不改代码、不重编译。从运行中的主臂 JavaExec 命令行提取 classpath，直接用同一份已编译类启动第二个只读 Shadow。
- 主臂（正式）：PID `15340/21804`，`minSpreadTicks=2`，输出 `event-maker-shadow-watch-v4-20261019/`。
- 实验臂（独立）：Java PID `26508`，唯一变量 `minSpreadTicks=1`，其余参数与主臂完全一致；输出 `event-maker-shadow-experiment-spread1-20261019/`。
- 实验臂首轮：qualified=7、activityPassed=0、selected=0，拒绝原因与主臂一致；说明当前空窗是活动门槛，不是价差门槛。
- 规则：实验臂结果单独存档，不混入正式验证，不用于宣称策略可实盘；两臂共享只读公共数据，均不签名、不下单。
- 运行期间仍然禁止编译、测试或任何重写 classpath 的任务。
### 09:32 长窗口重启：12 小时等待 + 12 小时 Shadow

- 原因：回测显示 time-to-fill 中位数 3.27 小时、P90 14.40 小时；旧 2 小时窗口在结构上几乎不可能产生足够 cycle，无法满足“至少 20 个真实盘口 cycle”的上线门槛。
- 动作：停止 v4 主臂与一档实验臂（PID 15340/21804/26508），用同一份已编译类重启两臂，等待窗口 180 → 720 分钟，Shadow 窗口 120 → 720 分钟；其余参数完全不变。
- 主臂：Java PID `25560`，`minSpreadTicks=2`，输出 `event-maker-shadow-watch-v5-20261019/`。
- 实验臂：Java PID `15356`，`minSpreadTicks=1`，输出 `event-maker-shadow-experiment-spread1-v2-20261019/`。
- 两臂首轮均为 qualified=7、activityPassed=0、selected=0；拒绝原因 `recent_trades_30m_below_min=5`、`recent_trades_2h_below_min=1`、`recent_notional_30m_below_min=1`；stderr 为空。
- `scripts/check-event-maker-watch.ps1` 默认目录已切到 v5。
- 说明：延长的是观察窗口，不是放宽策略门槛；价格带、活动门槛、价差、成交因子、post-only 约束全部未变。
### 10:0x 两臂重启（12h 等待 + 12h Shadow，直接 java -cp）

- 动作：用 v4 目录保存的 classpath 直接以 java.exe -cp 启动，不经过 Gradle，避免任何重编译；两臂参数与 09:32 计划一致。
- 主臂：Java PID `19092`（wrapper 26604），`minSpreadTicks=2`，输出 `backend/build/research/event-maker-shadow-watch-v5-20261019/`。
- 实验臂：Java PID `20972`（wrapper 25812），唯一变量 `minSpreadTicks=1`，输出 `backend/build/research/event-maker-shadow-experiment-spread1-v2-20261019/`。
- 两臂首轮：qualified=7、activityPassed=0、selected=0；拒绝原因 `recent_trades_30m_below_min=6`、`recent_trades_2h_below_min=1`；stderr 为空；状态 waiting。
- 说明：等待期只读公共数据，不签名、不下单；运行期间禁止编译/测试。

### 10:2x REST 冒烟发现并修复两个实盘安全缺陷

- 缺陷 1：`POST /api/event-maker/stop` 实测超过 60 秒未返回。根因是 `stop()` 先 `cancelAndJoin()`，而运行中的 discovery/trade poll 卡在 OkHttp 同步调用里（callTimeout 60s），期间不会执行撤单。违反“一键停止”验收标准。
- 修复 1：`LivePaperDataClient` 记录在途 Call，`close()` 先 `call.cancel()` 中断阻塞请求并把客户端置为不可用；`EventMakerStrategyService.stop()` 先关闭 WS/数据客户端、取消 runScope，再等待 run job 完成 NonCancellable 的撤单与持久化清理。
- 修复 1b：`runLive`/`runShadow` 的 finally 清理包在 `withContext(NonCancellable)` 中，确保被取消时仍会撤单、对账、持久化。
- 缺陷 2：Spring REST 路径的 `event-maker.proxy-url` 默认为空，直连 Polymarket 导致 `Gamma keyset discovery failed ... Connect timed out`。
- 修复 2：`effectiveProxyUrl()` 在未显式配置时回退到数据库系统代理（`ProxyConfigProvider`，仅 HTTP/已启用/有 host+port）；显式配置优先。
- 新增测试 `EventMakerStrategyStopTest`：stop 中断在途 discovery（<10s，实测 46ms）、系统代理回退、显式代理优先。
- 全量回归：98 suites / 478 tests / 0 failures / 0 errors / 2 skipped。
- 提交并推送：`69649e5 fix: make event maker stop abort in-flight network calls and honor system proxy`（`1191da0..69649e5 main -> main`）。
- REST 复验：带 `--event-maker.proxy-url=http://127.0.0.1:10808` 重启 Spring 后，discovery 正常（qualified=6、activityPassed=1），`stop` 实测 20ms 返回。
- 两臂已用新代码重启：主臂 Java PID `26412`（spread=2，等待中，拒绝原因 `spread_below_min=1`）；实验臂 Java PID `17596`（spread=1，已选中 1 个市场进入 Shadow，WS_OPEN）。

### 11:2x 证据完整性修复：逐笔 flush + 周期 state.json

- 缺陷：`trades.csv` 只在 8KB 缓冲写满时落盘，实测文件比实际交易滞后数小时，且缓冲边界会产生半行（实验臂文件末尾 `1792359617000,0x1`）；`state.json` 只在运行结束时写，崩溃即丢失全部状态证据。
- 修复：`EventMakerShadowCli.applyTrade` 每笔成交后 `tradesWriter.flush()`；`writeSnapshots` 每个快照周期调用新的 `writeState()`，运行结束仍写最终状态。
- 全量测试：98 suites / 478 tests / 0 failures / 0 errors / 2 skipped。
- 提交并推送：`b2846a8 fix: flush shadow trade log per trade and persist state on snapshot ticks`（`9c09780..b2846a8 main -> main`）。
- 两臂已用新代码重启：主臂 Java PID `21704`（wrapper 20276）；实验臂 Java PID `14376`（wrapper 26188）。
- 说明：15 分钟 flush 冒烟因活动门槛真实波动超时（9 轮无活动通过），未产生 fill；不通过放宽门槛制造成交。

### 12:2x-12:40 WS 断线重连缺陷修复（真实缺陷，受控实验验证）

- 背景：补 WS 网络层测试时发现验收矩阵声称的“断线恢复”只覆盖引擎级订单对账，WebSocket 客户端自身的重连逻辑零测试。
- 缺陷：`LivePaperWsClient` 未实现 `onClosing` 回执。服务器主动关闭连接时，OkHttp 会等满 60 秒关闭超时才会触发 `onClosed`，期间无盘口/成交推送，实盘中 Polymarket 重启 WS 即形成约 60 秒行情盲区。
- 修复：新增 `onClosing` 处理，立即回执关闭帧，使 `onClosed` 与重连立即发生；`wsUrl`/`reconnectDelayMs` 改为可注入以便测试。
- 受控实验：先移除 `onClosing` → 测试失败（客户端不重连）；恢复后 → 测试通过。证明修复必要且有效。
- 新增 `LivePaperWsClientReconnectTest`：真实 Java-WebSocket 服务端 + OkHttp 客户端，断言订阅载荷、掉线状态、二次 WS_OPEN 与重订阅；连跑 3 次稳定通过。
- 排查中的一次误报：首版测试的 `TestWsServer.onMessage` 覆盖方法递归调用自身导致 StackOverflowError 并杀死测试服务端，已修正为 `messageHandler` 委托。
- 全量测试：100 suites / 483 tests / 0 failures / 0 errors / 2 skipped。
- 提交并推送：`ca2c461 fix: reconnect market websocket promptly on server-initiated close`（`db2c3b1..ca2c461 main -> main`）。
- 两臂已用含该修复的代码重启：主臂 Java PID `12916`（wrapper 4476）；实验臂 Java PID `16788`（wrapper 18916）。重启前两臂各 34 轮、无 fill、无错误。
- 说明：重启原因是两臂启动（11:59）早于本轮编译（12:33），避免 classpath 污染；不是放宽门槛。

### 12:38-12:45 markout 收尾证据修复

- 缺陷：Shadow 运行结束的 finally 块不调用 `writeDueMarkouts`，已到期但尚未处理的 markout 被静默丢弃；接近结束的 fill 会丢失 60s/300s/1800s 行，导致 markout 证据不完整。
- 修复：关闭时先 `writeDueMarkouts(shutdownAt)` 冲刷所有已到期行；剩余未到期数量记录到 `summary.droppedPendingMarkouts`，不再静默。
- 审计脚本：`audit-event-maker-shadow.ps1` 读取并输出 `droppedPendingMarkouts`，大于 0 时写入 warnings；对旧 summary（无该字段）保持向后兼容。
- 全量测试：100 suites / 483 tests / 0 failures / 0 errors / 2 skipped。
- 提交并推送：`a3a3b47 fix: flush due markouts at shadow shutdown and report dropped markouts`（`13daf9c..a3a3b47 main -> main`）。
- 两臂已用含该修复的代码重启：主臂 Java PID `17176`（wrapper 22444）；实验臂 Java PID `25992`（wrapper 7188）。重启前两臂各 2 轮、无 fill、无错误。

### 13:0x-13:5x 结算窗口保真度修复与 7 天窗口回测

- 缺陷：回测固定"结算前 24 小时"交易窗口，而 Shadow 只有 `minRemainingMinutes=120` 下限、无上限。把发现窗口对齐到 24 小时后，严格条件下合格市场数为 **0**（当前 ≥100k 活跃市场最近也要 95 小时结算）——24 小时窗口在真实盘口里结构上无法产生样本。
- 动作 1：`LivePaperDataClient` 新增 `maxRemainingMinutes` 上限参数；`EventMakerConfig`/DTO/CLI/Gradle task 全链路打通；`EventMakerShadowCli` 新增 `--max-remaining-minutes`。
- 动作 2：`EventMakerBacktestCli` 的 `entryHorizonSeconds` 参数化（`--entry-horizon-seconds` / `-PresearchEntryHorizonSeconds`），并修正此前只改抓取窗口、未改网格配置窗口的 bug。
- 动作 3：用 7 天窗口重跑完整池（4,189 市场 / 864 配置），结果与 24 小时窗口对比：
  - 训练期正收益配置 113 → **338**；其中测试期非正 41（36.3%）→ **0（0%）**。
  - 选中配置 `p0.985-0.995_off1.0_spread2.0_q10.0_c1_r300`：训练 ROI 1.951%、测试 ROI 1.963%、36 回合、0 结算亏损。
  - 50bps 双边费用后仍 +1.00%（24h 窗口为负）。
  - Cluster bootstrap 全部/训练/测试/自然月 95% 区间均不含 0。
- **尾部风险审计（关键，不能只看 ROI）**：`breakEvenLossRate=1.918%`，即 36 回合中出现 1 次结算归零即抹平全部利润；实际观测 0 次，但按 rule-of-three，n=36 时亏损率 95% 上界约 8.33%，**远高于盈亏平衡线**。0 次亏损是"没看到"，不是"已证明不会发生"。
- 价格带风险画像：0.985-0.995 的 246 个正收益配置全部零结算亏损；0.97-0.99 的 92 个全部带结算亏损。属风险画像差异，不是收益差异。
- 结论：7 天窗口在方向上优于 24 小时窗口，**但仍不足以支持真实资金**；Shadow 窗口放宽到 7 天有回测依据，价格带/活动门槛/价差/队列/成交因子/post-only 约束全部未变。
- 报告：`docs/zh/event-maker-horizon-7d-backtest-2026-10-19.md`。
- 全量测试：100 suites / 484 tests / 0 failures / 0 errors / 2 skipped。
- 提交并推送：`e5af609`（发现窗口上限）、`8b4303e`（回测窗口参数化 + 7 天研究）。
- 两臂已用 `--max-remaining-minutes 10080` 重启：主臂 Java PID `25008`（wrapper 15040）；实验臂 Java PID `17756`（wrapper 17580）。首轮 qualified=1（此前 24h 上限下为 0）。

### 14:0x-14:5x 结算窗口稳定性交叉核验（重要：否证 7 天窗口的乐观解读）

- 动机：7 天窗口报告显示"训练期正收益配置 338 个、测试期非正 0 个（0% 失败）"，比 24 小时窗口（36.3% 失败）强得多。**单窗口的完美一致性本身可疑**，必须检验是否只是窗口选择产物。
- 动作：补跑 **14 天窗口**完整池（4,189 市场 / 864 配置），并做三窗口逐配置交叉核验。
- 三窗口失败率：24h = 36.3%（113 个候选）、7d = **0.0%**（338 个）、14d = **39.3%**（486 个）。**7 天的 0% 没有复现。**
- 同一配置跨窗口崩塌证据（`p0.985-0.995_off1.0_spread2.0_q10.0_c1_r300`）：
  - 24h：75 成交、4 结算亏损、训练 -7.04%、测试 -0.61%、回撤 289.82
  - 7d：36 成交、0 结算亏损、训练 +1.95%、测试 **+1.96%**、回撤 0
  - 14d：30 成交、1 结算亏损、训练 +2.01%、测试 **-6.58%**、回撤 100
  - 结论：**一次结算亏损即把 +1.96% 打成 -6.58%**，正是 7 天报告里 rule-of-three 预警的情形，且已实际发生。
- 当前 Shadow 配置 `p0.97-0.99_off0.5_spread2.0_q1.0_c3_r300` 跨窗口：24h 测试 +0.26%、7d +1.97%、14d +1.96%，三窗口均为正，但 24h/7d 均出现结算亏损、回撤 100~156。
- 跨三窗口同时为正的配置只有 **93 个（10.8%）**，最稳定一族为 `p0.97-0.99_off1.0_spread2.0_q*_c*_r300`；但即使该族，rule-of-three 95% 上界（2.88%，n=104）仍**高于**盈亏平衡线（2.02%），样本量不足。
- 文档：新增 `docs/zh/event-maker-horizon-stability-2026-10-19.md`；并在 7 天报告头部与结论节加显式否证提示，避免后续误读。
- 结论：**不能因为 7 天窗口好看就宣称策略可实盘**；Shadow 需积累真实 fill/cycle/markout，尤其要观察到若干次结算事件，才能把尾部亏损率约束到盈亏平衡线以下。

### 14:3x-15:0x 回测成交模型方向保真度修复（重大）

- 缺陷：回测的成交模型**不检查成交方向**——只要价格穿过限价就累计成交量。而 Shadow 引擎要求**反向成交**（挂单 BUY 只能被 taker SELL 成交，挂单 SELL 只能被 taker BUY 成交）。这使回测系统性高估成交，是"回测有 fill、Shadow 无 fill"的结构性原因之一。
- 证据：Data API `/trades` 明确返回 `side` 字段（BUY/SELL），此前 `EventMakerTrade` 直接丢弃。
- 修复：
  - `EventMakerTrade` 新增 `side` 与 `takerIsBuy`/`takerIsSell`；`side` 缺失的成交**不参与撮合**（宁可少算，不多算）。
  - `EventMakerResearchDataClient` 解析并保留 `side`。
  - `EventMakerBacktestEngine` 买入累计要求 `trade.takerIsSell`，卖出累计要求 `trade.takerIsBuy`。
- 新增测试：`same side taker flow does not fill a resting maker order`、`prints without a known taker side never fill`；既有 33 处测试成交样本全部补上显式方向。
- 全量测试：100 suites / 486 tests / 0 failures / 0 errors / 2 skipped。
- 影响量化（同一配置 `p0.985-0.995_off1.0_spread2.0_q10.0_c1_r300`，7 天窗口）：成交市场 36 → 27、结算 4 → 5、训练 ROI 1.95% → 1.97%、测试 ROI 1.96% → 2.02%。方向修复后结论方向不变，但成交数下降约 25%，说明此前的回测确实偏乐观。
- 当前 Shadow 配置 `p0.97-0.99_off0.5_spread2.0_q1.0_c3_r300`（side-aware 7d）：97 成交、107 回合、5 次结算其中 1 次亏损、训练 +0.23%、测试 +1.98%、回撤 100、`breakEvenLossRate=1.95%`。
- 提交并推送：`6557968 fix: make backtest fill model taker-side aware to match live engine`。
- 说明：两臂在修复期间已停止（主臂 60 轮无活动通过），修复后需用新代码重启。

### 15:0x-15:2x 活动门槛 favorite 作用域修复

- 缺陷：活动门槛（30m/2h 成交笔数、成交额、买卖各边）在 `getRecentTrades(market)` 上统计，而该方法返回**两个 outcome** 的成交。策略只挂 favorite token，因此非 favorite 的成交被错误计入可交易流动性。
- 实证：以当前唯一合格市场为例，2 小时窗口内 SELL 成交**全部在非 favorite outcome** 上（oi=1），favorite（oi=0）只有 BUY。门槛却把非 favorite 的 SELL 当成 favorite 的卖方流动性。
- 修复：活动统计前先按 `favoriteTokenId` 过滤，只统计真正可撮合的那一侧 token。
- 新增测试：`activity gate only counts prints on the favorite token`（非 favorite 的 10 笔成交不能通过门槛）、`activity gate counts favorite token prints`（favorite 上 3 笔含双边可通过）。
- 全量测试：100 suites / 488 tests / 0 failures / 0 errors / 2 skipped。
- 提交并推送：`194baf0 fix: scope activity gate to the quoted favorite token`（`2f718ae..194baf0 main -> main`）。
- 两臂已重启：主臂 Java PID `2424`（wrapper 16648）；实验臂 Java PID `21272`（wrapper 24584）。
- 行为变化观察：首轮拒绝原因由 `recent_trades_30m_below_min` 变为 `recent_notional_30m_below_min`，说明门槛已按 favorite token 重新评估（此前被非 favorite 成交抬高）。

### 15:3x-16:0x LIVE 事件循环限流炸弹修复（严重实盘缺陷）

- 缺陷：LIVE 事件循环在**每一个 WS 事件**上都执行 `runEngineTick`，而它内部先 `engine.reconcile`——对每个活跃订单发一次真实 CLOB `getOrder` REST 调用，并且每次都 `persistLive` 写盘。
- 实测事件速率：历史 Shadow 运行 `bookUpdates/duration` 为 **7.28 次/秒**（2h 运行 52,426 次盘口更新）。按 3 个活跃订单估算：**约 22 次 REST/秒、每小时 78,639 次**——必然触发 CLOB 限流；且每次都在 `stateMutex` 内执行，阻塞报价路径。
- 修复：
  - 事件循环改为只调用新的 `runQuoteTick`（仅 `engine.quote`，**零 REST**）。
  - 订单对账保留在专用 `reconcileJob`，按 `reconcileSeconds` 节流（默认 2s）。
  - `persistLive` 改为：订单状态变化立即写；无变化时最多每 5 秒写一次（`LIVE_PERSIST_INTERVAL_MS`），消除磁盘抖动。
- 新增测试 `quote does not perform order lookups`：断言 `quote()` 期间 `getOrder` 调用次数为 0；`FakeExecutionPort` 增加 `getOrderCount` 计数。
- 全量测试：100 suites / 489 tests / 0 failures / 0 errors / 2 skipped。
- 提交并推送：`dbacd9e fix: stop reconciling live orders on every websocket event`（`8ac8207..dbacd9e main -> main`）。
- 两臂已重启：主臂 Java PID `13976`（wrapper 8584）；实验臂 Java PID `22292`（wrapper 23760）。

### 16:0x-16:5x 活动门槛一致性核验（回测 vs Shadow，关键结论）

- 缺口：此前回测**没有建模 Shadow/Live 的活动门槛**（30m/2h 笔数、成交额、买卖各边）。实盘只在通过门槛的市场报价，两者不一致意味着回测的 fill 不代表真实可交易集合。
- 动作：把活动门槛原样加进回测（同阈值、同口径、且只统计 favorite token，与 `194baf0` 修复后一致），做对照实验。
- 对照结果（7 天窗口 / 864 配置，门槛 = 30m≥3/≥50/各≥1，2h≥20/≥500/各≥3）：
  - 无门槛：训练期正收益 284 个、测试非正 0、最优训练 1.98% / 测试 1.97%、成交 47
  - **有门槛：训练期正收益 192 个、测试非正 0、最优训练 1.96% / 测试 1.96%、成交 45**
  - 候选数下降约 32%，但训练/测试一致性与 ROI 水平基本不变 → **edge 在真实门槛下存活**。
- 当前 Shadow 配置（有门槛）：合格 260、成交 37、回合 44、结算 0、训练 +1.96%、测试 +1.98%、回撤 0。
- 尾部风险（仍不能上线）：`breakEvenLossRate=1.93%`，成交 37、观测 0 次亏损，rule-of-three 95% 上界 = 3/37 ≈ **8.11%**，远高于盈亏平衡线。
- 新增能力：回测 CLI/Gradle 支持 `--min-recent-*` 门槛参数（`-PresearchMinRecent*`）。
- 新增测试：`activity gate blocks quoting when the trailing window is too thin`、`activity gate allows quoting when the trailing window is active enough`。
- 全量测试：100 suites / 491 tests / 0 failures / 0 errors / 2 skipped。
- 提交并推送：`0228de4 feat: apply live activity gate inside the backtest`。
- 报告：`docs/zh/event-maker-activity-gate-consistency-2026-10-19.md`。
- 结论：回测与实盘选市场逻辑的最后一个已知结构性差异已消除；候选池缩小是真实约束，不是发现故障；仍不能实盘，需 Shadow 积累真实成交与结算事件。

### 16:1x-16:2x Spring REST 路径等待市场修复（可用性缺口）

- 缺陷：Spring REST 路径的 `prepareMarkets` 一次性发现失败就抛 `no_eligible_markets` 并永久停止；而 CLI 路径有 `waitForMarketMinutes` + `discoveryRetrySeconds` 重试。严格条件下市场空窗可持续数小时，因此通过 REST 启动的 SHADOW **结构上必然失败**。
- 修复：`prepareMarkets` 改为在 `waitForMarketMinutes` 预算内循环重试，每次间隔 `discoveryRetrySeconds`；`discoverAndSelect` 返回 null 而非抛异常；`EventMakerConfig`/DTO 新增 `waitForMarketMinutes`、`discoveryRetrySeconds`（默认 0 / 60，保持向后兼容的 fail-fast）。
- 新增测试 `rest path keeps waiting instead of failing on the first empty scan`：本地 HTTP fixture 第一次返回空、第二次返回可交易市场；断言 start 成功、discovery 至少重试 2 次、最终采纳市场。
- 全量测试：100 suites / 492 tests / 0 failures / 0 errors / 2 skipped。
- 提交并推送：`5c2b9cb fix: let the REST path wait for an eligible market instead of failing fast`（`58d5d2b..5c2b9cb main -> main`）。
- REST 实测（Spring PID 13384，dev profile）：`start` 221ms 返回 `running=true`（异步等待，不再阻塞）；日志出现 `waiting for an eligible market (attempt=1/2)`；`stop` 39ms 返回 `running=false`，等待期间一键停止仍即时。
- 两臂已用新代码重启：主臂 Java PID `19804`（wrapper 13916）；实验臂 Java PID `5444`（wrapper 23824）。

### 16:2x-16:4x 轮询放大与重复成交修复（效率/正确性）

- 缺陷 1：两个轮询任务每 `pollSeconds`（默认 3s）调用 `getRecentTrades(market)`，该接口固定 `limit=200` 且无时间下界，**每轮重发同一批历史成交**。实测 2 小时运行：2400 轮 × 200 条 ≈ 48 万条事件，而真实成交仅 1,799 条，**放大 267 倍**。
- 缺陷 2：重复成交虽然被 `seenTrades` 去重计数，但**仍然触发一次完整 engine tick**（对账 + 报价），即重复输入仍在消耗报价路径。
- 修复：
  - `getRecentTrades` 新增可选 `sinceTimestampMs`，带时间下界请求（`start` 参数）。
  - 两个轮询任务各自维护 `lastSeen`（按 conditionId 记录已转发的最新时间戳），只请求增量。
  - 两个事件循环新增 `duplicateTrade` 标记：重复成交**跳过 engine tick**（Shadow 跳过 `runEngineTick`，Live 跳过 `runQuoteTick`）。
- 新增测试 `incremental trade fetch sends a start bound`：断言请求携带 `start=1700000000`。
- 全量测试：100 suites / 493 tests / 0 failures / 0 errors / 2 skipped。
- 提交并推送：`eb11bd6 perf: poll trades incrementally and skip ticks on duplicate prints`（`5569894..eb11bd6 main -> main`）。
- 两臂已重启：主臂 Java PID `21596`（wrapper 21396）；实验臂 Java PID `6804`（wrapper 4776）。

### 16:4x-17:0x CLI 路径轮询放大修复（补齐上一轮遗漏）

- 缺口：上一轮（`eb11bd6`）只修了 Spring 路径的轮询放大与重复 tick，**CLI 路径（两臂实际运行的入口）仍每 3 秒重发全部 200 条历史成交**，且重复成交仍触发完整 engine tick。
- 修复（`EventMakerShadowCli`）：
  - 轮询任务维护 `lastSeen`（按 conditionId），只请求增量（`getRecentTrades(market, since)`）。
  - `applyTrade` 改为返回是否重复；重复成交**跳过 engine tick**（仍会 `writeDueMarkouts` 并检查 deadline，不丢 markout 与超时语义）。
- 全量测试：100 suites / 493 tests / 0 failures / 0 errors / 2 skipped。
- 提交并推送：`ff47918 perf: apply incremental polling and duplicate-tick skip to the shadow CLI`（`d9f0891..ff47918 main -> main`）。
- 两臂已重启：主臂 Java PID `11108`（wrapper 22544）；实验臂 Java PID `23440`（wrapper 10476）。
- 说明：至此 Spring 与 CLI 两条 Shadow 入口的轮询/去重语义一致。

### 17:0x-17:2x 日亏损预算语义修复（风控正确性）

- 缺陷：`maxDailyLoss` 名为"日亏损"，但 `shouldHalt` 比较的是 `status.realizedPnl + status.unrealizedPnl`——即**整个运行周期**的累计 PnL，从未按日重置。长跑中一次早期亏损会永久锁死策略，与配置语义不符。
- 修复：
  - `EventMakerLiveEngine` 新增 `dailyPnlBaseline`，风险快照的 `dailyPnl` 改为扣除该基线。
  - `EventMakerStrategyService` 新增 `currentDayKey`/`rollDailyPnlBaseline`：UTC 日切换时把基线推进到当时的已实现 PnL，并记录日志。
  - 两条事件循环的停机检查改为 `dailyPnlSinceBaseline(engine, states)`，即"今日"盈亏。
  - `initialRealizedPnl` 由 private 改为可读，供基线计算使用。
- 新增测试 `daily pnl baseline scopes the loss budget to the current day`：推进基线后不得再报 `daily_loss_limit`。
- 全量测试：100 suites / 494 tests / 0 failures / 0 errors / 2 skipped。
- 提交并推送：`5483d02 fix: make the daily loss budget actually daily`（`f8cf592..5483d02 main -> main`）。
- 两臂已重启：主臂 Java PID `12368`（wrapper 23964）；实验臂 Java PID `8428`（wrapper 26264）。

### 17:3x-17:5x 实盘执行链路审计补漏（本轮）

在 Shadow 等待严格条件市场期间，逐段审计 `ClobEventMakerExecutionAdapter`、`EventMakerLiveEngine`、状态恢复和官方 CLOB 文档。依据官方 OpenAPI、`clob-client` 源码和真实 HTTP 探测，确认并修复以下缺陷：

| ID | 缺陷 | 修复 | 回归证据 |
|---|---|---|---|
| LIVE-1 | 单笔撤单使用不存在的 `DELETE /orders/{id}`，且未校验 `not_canceled` | 改为官方 `DELETE /order` + JSON `{orderID}`；只有 `canceled` 精确确认当前订单且无 `not_canceled` 才成功 | `ClobEventMakerExecutionAdapterTest` 端点/响应测试；真实无认证探测 `/order` 返回 401、`/orders/0x01` 返回 404 |
| LIVE-2 | 活跃订单分页只认 `null`，官方终端游标是 `LTE=`，会把终止页当重复游标并拒绝启动 | 使用官方 `INITIAL_CURSOR=MA==`，同时识别 `LTE=` | `active order terminal cursor stops pagination` |
| LIVE-3 | 撤单后直接清空本地订单，忽略撤单期间/撤单前已发生的部分成交 | 撤单后必须读取最终订单快照并先对账，再清除 activeOrder；查询失败保留 activeOrder | `requote cancellation reconciles a fill that races the cancel`、`partial fill cancellation reconciles a second fill during cancel`、`cancel lookup failure keeps the active order fail closed` |
| LIVE-4 | 下单返回 2xx 但无 body、成功却无 orderID、或 `success=false` 却带 orderID 时可能被当作普通拒绝并重复下单 | 统一归为 `EventMakerAmbiguousOrderException` 并停机；成功但状态未知时保留 orderID、尝试撤单并停机 | `successful placement without an order id is ambiguous`、`rejected placement carrying an order id is ambiguous` |
| LIVE-5 | 签名/派生异常会从 `place()` 直接抛出，绕过 engine 的 Result 风控路径 | 捕获签名异常并返回 `order_signing_failed` 预检失败 | `signing failure is returned as a preflight failure instead of escaping` |
| LIVE-6 | CLOB V2 合法终态 `INVALID`、`CANCELED_MARKET_RESOLVED` 以及 `unmatched` 未识别，可能错误停机 | 新增统一 `EventMakerOrderStatuses`，全链路使用同一状态集合并统一大小写 | `documented terminal statuses are accepted`、状态恢复测试 |
| LIVE-7 | 订单快照只校验 id/价格/数量，未校验 maker、market、asset，也未校验 `MATCHED` 必须全量成交 | 快照必须由当前 proxy 创建、condition/token 与选市场一致；`MATCHED` 但未满额直接 fail-closed | `order snapshot from a different maker is rejected`、`matched status with an unfilled remainder is rejected` |
| LIVE-8 | 重启后 `dailyPnlBaseline` 未重新锚定，历史累计亏损仍会占满新一天预算 | 运行开始和 UTC 日切换统一调用 `resetDailyPnlBaseline(states)` | `restored cumulative loss does not consume the new daily budget` |
| LIVE-9 | 撤单查询失败或下单预检失败会在每个 WS 事件重试，存在请求放大风险 | 每市场下单失败、每订单撤单确认失败均加入 5 秒退避 | `rejected placement is rate limited per market`、撤单失败退避断言 |
| LIVE-10 | Shadow 订单快照缺少 condition/token/maker，新增的严格快照校验会让 Shadow 撤单失败 | Shadow 订单补齐快照字段并保持模拟语义 | EventMaker 定向测试全部通过 |
| LIVE-11 | 孤儿订单已是终态时，撤单接口返回 `not_canceled` 会让启动恢复误判失败 | 撤单失败也继续查询最终快照；只有确认终态且无成交才接受 | `already terminal orphan is accepted even if cancel reports not confirmed` |
| LIVE-12 | 持久化状态中的小写订单状态会被旧大写白名单拒绝 | 状态存储改用统一状态校验，兼容 `live`/`canceled_market_resolved` 等大小写 | `load accepts lower-case documented order statuses` |

验证记录：

- 全量：`100 suites / 513 tests / 0 failures / 0 errors / 2 skipped`。
- 代码提交：`05277a3 fix: harden live CLOB execution and order recovery`；文档提交：`37474ec docs: record live CLOB execution audit`；已推送 `1c9664c..37474ec main -> main`。
- Spring 冒烟：PID `24064`，`/api/system/health=200`；REST `SHADOW start → status → stop` 全链路通过。
- 冒烟市场：`US x Iran ceasefire continues through October 22?`；日志出现 `BUY 0.95 shares=1.05` 的 Shadow 报价；`stop` 实测 `95ms`，无错误。
- 最新代码重启后二次 REST 冒烟：Spring PID `24064`、health=200、start/status/stop 均成功，`stop=16ms`，未产生订单。
- 两臂已用本轮代码重启：主臂 Java PID `25924`（wrapper `20468`，spread=2）；实验臂 Java PID `25556`（wrapper `22844`，spread=1）。均处于严格条件等待，输出目录已更新 `watch-process.json`。
- 当前未授权真实下单；没有产生任何 LIVE 订单。
- 结论：执行链路新增的已知结构性问题已修复，但策略尾部风险仍未约束，**不能因此宣布可实盘**。

## 2026-10-19 18:00-19:20 逆势流 Guard、全量回测与 Shadow 冒烟

### 目标与前置状态

- 目标：约束三笔结算归零暴露，并把风控接入回测、REST、CLI、Shadow 和 Live 共用引擎。
- 前置：两臂已停止；Spring 冒烟实例 PID `24064`；所有执行适配器修复已提交。
- 约束：不跟单目标地址；不使用 mock/Shadow 结果冒充真实实盘；未知订单状态继续 fail-closed。

### 实现

- 新增 `EventMakerAdverseFlowGuard.kt`：按 favorite token 维护滚动成交，计算 signed taker flow 与首尾价格变化；窗口默认 3,600 秒，flow <= -0.4 且跌幅 >= 0.5 美分时触发。
- `EventMakerConfig` / REST DTO / Gradle 属性增加 `flowGuardWindowSeconds`、`flowGuardMaxSignedFlow`、`flowGuardMinPriceDropCents`。
- `EventMakerLiveEngine`：
  - 唯一成交先送入 Guard；
  - BUY 挂单遇到逆势流时撤单，撤单使用既有最终快照对账路径；
  - Guard 解除前不新增 BUY；
  - SELL 退出不被阻塞。
- Spring/Shadow 启动时用最近公共成交预热 Guard；CLI 任务支持 `shadowFlowGuard*` 和 `researchFlowGuard*` 参数。
- 回测在判断已挂 BUY 是否继续存活时只使用当前打印之前的信息，避免同笔成交未来函数；新增 `flowGuardTriggers` 和 CSV 列。
- 新增 `scripts/compare-event-maker-flow-guard.ps1`、`scripts/start-event-maker-shadow.ps1`。
- 修复 `LivePaperWsClientReconnectTest` 对同步列表的无锁迭代导致的 `ConcurrentModificationException`。

### 测试与验证

- Guard 单元测试：边界、正常双向流、未知方向、窗口过期、禁用开关。
- LiveEngine：逆势流撤 BUY，Guard 期间不重新下单。
- 回测：Buy 阻止、正常流成交、历史预热窗口、恢复报价。
- 全量：`101 suites / 525 tests / 0 failures / 0 errors / 2 skipped`。
- 命令：`.\gradlew.bat test --console=plain`。

### 回测结果

100 市场对照（Guard 3,600 秒）：

- 跨配置回合 3,955 -> 2,239。
- 正 PnL 配置 570 -> 744。
- 单市场亏损 <= -99 的配置 288 -> 0。
- 配置平均 ROI -5.317% -> +0.948%，中位数 +0.527% -> +0.899%。

全量 4,189 市场、864 配置：

- 跨配置回合 134,216 -> 96,105。
- 正 PnL 配置 101 -> 138。
- 单市场亏损 <= -99 的配置 864 -> 845。
- 配置平均 ROI -3.041% -> -2.563%，中位数 -2.147% -> -2.164%。
- 主配置 `p0.97-0.99_off0.5_spread2.0_q1.0_c3_r0`：PnL +224.37 -> +275.66，结算亏损 3 -> 2，测试 ROI +0.560% -> +0.397%。

尾部因果核对：

- China-Vietnam：成交前 1h flow -0.746、跌幅 0.815 美分，Guard 成功阻止。
- Iran May 25：原始入场可触发，但 Guard 改变报价时点后于更早的 1779741514 成交，当时前置 flow +0.126，仍亏 100。
- US–Iran Aug 31：成交前 flow +0.991；卖压与成交在同一笔 1,022.7 股 SELL 中，无法因果拦截，仍亏 100。

证据：

- `docs/zh/event-maker-flow-guard-tail-audit-2026-10-19.md`
- `docs/zh/event-maker-flow-guard-full-2026-10-19.md`
- `docs/zh/event-maker-flow-guard-100m-2026-10-19.md`
- `docs/zh/event-maker-flow-guard-600s-100m-2026-10-19.md`
- `backend/build/research/event-maker-flowguard-full-v2-20261019-*`

### Shadow 冒烟

- 严格 0.97–0.99 价格带当前仍无报价市场，连续 3 轮均为 `price_out_of_range`；没有放宽正式两臂条件。
- 独立宽价格带执行链路冒烟：1 个市场、5 分钟、201 笔唯一成交、690 次盘口更新、1 次报价、1 次停止撤单、0 fill、0 错误。
- `scripts/audit-event-maker-shadow.ps1` 返回 `passed=true`，`activeOrders=0`、`duplicateTradeRows=0`。
- 证据：`backend/build/research/event-maker-shadow-flowguard-smoke-wide-20261019/`。

### 当前进程

- 主臂 wrapper PID `11600`，Shadow Java PID `26308`，spread=2，输出 `backend/build/research/event-maker-shadow-guard-main-v2-20261019/`。
- 实验臂 wrapper PID `25864`，Shadow Java PID `22788`，spread=1，输出 `backend/build/research/event-maker-shadow-guard-spread1-v2-20261019/`。
- 正式参数首轮：主臂 qualified=2/activityPassed=0/selected=0；实验臂 qualified=2/activityPassed=0/selected=0；两臂 `logIssues=0`、`stderrBytes=0`。
- 轮询：`.\scripts\check-event-maker-watch.ps1 -OutputDir <目录>`；运行期间禁止编译和测试。

### 结论与下一步

- Guard 应保留，但只挡住三笔历史归零中的一笔；全量中位数 ROI 未改善，845/864 配置仍有单市场归零。
- 当前仍不能实盘，不能标记目标完成。
- 唯一剩余的真实 Live 阻断条件是用户授权与账户参数；在此之前继续 Shadow 收集 fill/markout/PnL 证据。