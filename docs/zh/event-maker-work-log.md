# Event Maker 工作日志

> **重要：早期按 leg 计数的 Wilson PASS（如 20.22）已被 20.39 的市场聚类审计推翻。当前以 20.39 和交付计划顶部状态为准。**


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
### 提交与最终运行检查

- 提交：`d768950 feat: add causal adverse-flow guard to event maker`。
- 推送：`c8fb838..d768950 main -> main`。
- 正式参数两臂已运行 5 轮：主臂和实验臂均为 qualified=2、activityPassed=0、selected=0，拒绝原因 `recent_trades_30m_below_min=2`。
- 两臂 `logIssues=0`、`stderrBytes=0`，未降低活动门槛。
- 进程：主臂 wrapper `11600` / Java `26308`；实验臂 wrapper `25864` / Java `22788`。
## 22:35 数据缓存、执行一致性修复与跨窗口候选复核

### 本轮目标

- 补全候选策略的稳健性证明，不再用单窗口结果直接启动 Shadow。
- 修复研究数据抓取和回测执行模型中会导致假结论的缺口。
- 生成可重复的数据集、跨窗口报告和 Shadow 启动参数。
- 在证据充分后重启正式 Shadow，并提交本轮代码与文档。

### 数据与缓存

- 新增 `EventMakerDatasetStore`：gzip JSON 数据快照，写入采用临时文件 + 原子替换，避免中断时损坏旧缓存。
- CLI 增加 `--dataset`、`--refresh-dataset`、`--focus-config`、`--grid-profile`，以及可覆盖更大窗口的缓存复用规则。
- 生成 `backend/build/research/event-maker-full-v2-7d-20261019-dataset.json.gz`，4,189 个市场，7 天成交历史。
- 抓取失败不再静默跳过月份或写入空成交：Gamma 发现失败直接中止，单市场成交重试 3 次后仍失败也中止整次运行。
- 未传代理时显式使用 `Proxy.NO_PROXY`；本机 DNS 污染时通过 `-PresearchProxy=http://127.0.0.1:10808` 运行。

### 回测执行模型修复

- 发现回测允许挂单存活数天，而 Shadow/Live 在 `maxOrderAgeSeconds=1800` 时撤单。旧结果中的 3d/6d 结算归零来自这种“过期挂单迟到成交”的失真。
- 回测现在在成交前检查最大挂单年龄；买单过期直接撤单，卖单过期撤单后必须等待后续打印才能重新排队。
- 买单 requote 仅在目标价真的变化时发生，和 Live `priceChanged` 逻辑对齐。
- 新增 `--max-order-age-seconds` / `-PresearchMaxOrderAgeSeconds`，默认 1,800。
- 新增两条单元测试：过期买单不得成交；过期卖单不得在同一打印上成交。

### 候选结果

候选：`p0.97-0.99_off0.0_spread1.0_q20.0_c1_r300`

24h 窗口（7d 数据集重放）：

- eligible=566，filled=93，SELL=89，SETTLE=4。
- 结算亏损 0，胜率 100%，PnL +97.8690，ROI +1.0524%。
- 训练 27 回合 ROI +1.0378%；测试 66 回合 ROI +1.0583%。
- 最差市场 +1.0111，最大回撤 0，MAE -92.02。
- 平均持仓 9.53 小时，平均成交等待 280 秒，Guard 触发 408 次。

8 个入场窗口：

| 窗口 | 回合 | 结算亏损 | ROI | 训练 ROI | 测试 ROI |
|---|---:|---:|---:|---:|---:|
| 12h | 63 | 0 | +1.033% | +1.038% | +1.030% |
| 1d | 93 | 0 | +1.052% | +1.038% | +1.058% |
| 2d | 96 | 0 | +1.061% | +1.047% | +1.070% |
| 3d | 77 | 0 | +1.090% | +1.123% | +1.067% |
| 4d | 87 | 0 | +1.108% | +1.097% | +1.110% |
| 5d | 75 | 0 | +1.130% | +1.074% | +1.170% |
| 6d | 78 | 0 | +1.164% | +1.171% | +1.160% |
| 7d | 61 | 0 | +1.095% | +1.106% | +1.080% |

全窗口交集（8 窗口均零结算亏损、总 ROI/训练/测试为正、训练成交 >=20）只有 2 个配置；正式候选的最差窗口 ROI 为 +1.033%，是两者中更高的。

### Guard A/B 与费用

同数据、同最大挂单年龄的 24h A/B：

- 无 Guard：101 回合，1 次结算亏损，PnL +5.05，ROI +0.05%，测试 ROI -0.39%，最差 -100。
- Guard：93 回合，0 次结算亏损，PnL +97.87，ROI +1.052%，测试 ROI +1.058%，最差 +1.011。

费用压力（24h）：

- 0bps +1.052%
- 10bps +0.856%
- 25bps +0.561%
- 50bps +0.069%
- 100bps -0.914%

### 参数邻域

`candidate-neighborhood` 共 1,008 个配置：

- q15–q50 全部 0 结算亏损；q10 仍有 1 次。q20 不是孤立尖峰。
- spread 0.75/1.0/1.25/1.5c 全部 0 结算亏损；1.75/2.0c 各有 1 次。
- offset 0/0.25/0.5/1.0c 全部 0 结算亏损，但 offset 越大成交越少。
- 24h 窗口下相邻价格带 `0.95-0.97`、`0.985-0.995` 也都是 0 结算亏损，但跨窗口收益低于正式候选。

### 尾部风险边界

- 24h 只有 93 个零亏损回合；rule-of-three 95% 上界约 3.23%，高于 1.041% 的盈亏平衡线。
- 按该粗略标准需要约 289 个零亏损独立回合。
- 8 个窗口复用同一批市场和成交，不能把回合数直接相加。
- 因此结论是“可进入 Shadow”，不是“已经证明实盘安全”。

### 测试

- 全量测试：`102 suites / 530 tests / 0 failures / 0 errors / 2 skipped`。
- 命令：`.\gradlew.bat test --console=plain`。
- 定向 event-maker 测试：140 tests 通过。

### 证据

- `docs/zh/event-maker-safe-candidate-2026-10-19.md`
- `backend/build/research/event-maker-guard-age1800-horizon-{12h,1d,2d,3d,4d,5d,6d,7d}-candidate-20261019-summary.csv`
- `backend/build/research/event-maker-candidate-neighborhood-age1800-20261019-summary.csv`
- `backend/build/research/event-maker-no-guard-age1800-horizon-1d-candidate-20261019-summary.csv`
- `backend/build/research/event-maker-guard-age1800-horizon-*-focus-robustness.md`

### 当前边界

- 仍不能真实实盘。
- 仍不能跟单目标地址。
- 真实 Live smoke 仍需要账户、资金上限、明确授权和时间窗口。
### 22:51 正式候选 Shadow 启动

- 输出目录：`backend/build/research/event-maker-safe-candidate-shadow-main-v2-20261019/`。
- wrapper PID `5600`，Shadow Java PID `8300`。
- 参数：spread=2 ticks、price 0.97–0.99、maxMarkets=18、wait=720m、run=720m、minRemaining=720m、maxRemaining=10080m、fillVolumeFactor=20、maxCyclesPerMarket=1、entryOffset=0、exitSpread=1c、maxOrderAge=1800s、Guard 3600s/-0.4/0.5c。
- 首轮发现：qualified=1、activityPassed=1、selected=1。
- 选中市场：`Israel x Iran ceasefire continues through October 21?`。
- 盘口：bid 0.976、ask 0.978；Shadow BUY 0.976、25.61 shares，`shadow-1`，状态 LIVE（仅 Shadow，不下真实订单）。
- `check-event-maker-watch.ps1`：`logIssues=0`、`stderrBytes=0`。
- Shadow 运行期间禁止编译和测试；后续按 `summary.json` 和审计脚本验收。
### 22:55 提交与推送

- 提交：`93e4ccc feat: align event maker research with live execution`。
- 推送：`9fd75e5..93e4ccc main -> main`。
- 内容：数据快照/原子写入、严格抓取、回测最大挂单年龄一致性、8 窗口候选回测、参数邻域、Shadow 参数、全量 530 tests 和运行文档。
- 提交后 Shadow 继续运行，运行期间不重新编译。
- 提交后清理：停止 Spring 冒烟实例 PID 24064；停止 Docker 容器 polyhermes-mysql（保留容器和数据，可随时 docker start）。当前仅保留正式 Shadow。

### 23:06 监控状态语义修复

- 发现 check-event-maker-watch.ps1 在 Shadow 已选中市场并挂单时仍输出 state=waiting。
- 现已读取 state.json：有市场时输出 state=running，并显示 activeOrders、fills、cycles、inventoryShares、phases；有 state 文件但无市场时输出 state=discovering。
- 当前实跑输出：state=running markets=1 activeOrders=1 fills=0 cycles=0 inventoryShares=0 phases=BUY_PENDING。
- Shadow 于 22:59 完成一次真实 Shadow 重挂：取消 shadow-1（reason=requote），新挂 shadow-2 BUY 0.975 / 25.64 shares。

### 23:13 Shadow 审计增强

- `audit-event-maker-shadow.ps1` 新增 `events.jsonl` 与 summary/execution diagnostics 的一致性检查。
- 检查项：quotesPlaced、cancels、重复 orderId、已挂订单缺失 diagnostics、撤销未挂订单。
- 用临时 fixture 验证：匹配场景 `passed=true`；将 summary.quotesPlaced 改为 3 后审计按预期报 `events.placed=2 summary.quotesPlaced=3` 并非零退出。
- fixture 已清理，未进入仓库。

### 23:20-23:30 日亏损跨重启修复与 v2 Shadow 中止

- 审计发现：`5483d02` 只保证单进程内跨 UTC 日重置，但同日重启时 `resetDailyPnlBaseline(states)` 会把基线设为当前累计 PnL，导致当天已发生亏损被遗忘，可能绕过 `maxDailyLoss`。
- 修复：
  - 新增 `EventMakerDailyPnlBudget`：`currentDay` 使用 UTC epoch day；`resolve` 在同日复用持久化 baseline，跨日或缺失时重锚到当前累计已实现 PnL。
  - state schema 升到 v3，新增 `dailyPnlDay` 和 `dailyPnlBaseline`；缺失、负数 day 或非有限 baseline 均 fail-closed。
  - 启动恢复改为使用持久化日锚点；对账任务、WS 报价 tick 和停止清理在应用成交之前移动日锚点，防止零点后第一笔亏损被误算入新日基线。
  - 跨日重置后立即触发状态写入，避免崩溃窗口。
- 测试：
  - 新增 `EventMakerDailyPnlBudgetTest` 4 条：同日沿用、跨日重锚、缺失/非法锚点、非有限累计 PnL。
  - `EventMakerStateStoreTest` 新增缺失日锚点和非有限 baseline 拒绝测试，并验证 v3 日锚点 round-trip。
  - 全量：`103 suites / 538 tests / 0 failures / 0 errors / 2 skipped`。
- v2 Shadow（PID 8300 / wrapper 5600）于 23:20 主动停止：运行 29m、1 个市场、2 次 Shadow 挂单、1 次 requote、0 fill、无真实订单。Gradle wrapper 因强制终止记录 exit -1；该目录不作为最终 Shadow 验收。
- 下一步：提交本修复后，在新目录 `event-maker-safe-candidate-shadow-main-v3-20261019/` 用最终代码重启正式候选 Shadow。
### 23:42 v3 Shadow 启动

- 修复提交：`c39b00a fix: persist daily loss budget across restarts`，已推送 `fc2e244..c39b00a`。
- v3 输出：`backend/build/research/event-maker-safe-candidate-shadow-main-v3-20261019/`。
- wrapper PID `23852`，Java PID `20988`，启动 `2026-10-19 23:41:45 +08:00`。
- 首两轮均为 qualified=2、activityPassed=0、selected=0，原因 `recent_trades_30m_below_min=2`。
- 当前 waiting；日志 0 异常，stderr 为空；严格活动门槛和价格带保持不变。
### 23:55 对账停机与估值边界补强

- 发现 `reconcileJob` 没有消费 `applyEngineResult` 的 halted 返回值；若没有后续 WS 事件，未知订单状态或日亏损触发后可能继续保留活动订单。现关闭 feed channel，统一进入 cancel/reconcile/persist 清理。
- 初始 tick 同样检查 engine halted 和日亏损，避免初始化后继续挂单。
- 无买盘时 `markToMarket` 不再返回 0 浮亏，改为 bid=0 的最坏估值，并补单测。
- 系统时间回拨时不重置日亏损锚点，保留持久化未来日直到时间追上，并补单测。
- 全量测试：103 suites / 538 tests / 0 failures / 0 errors / 2 skipped。
- v3 Shadow 在 23:55 主动停止；运行期间严格门槛下 5 轮均 activityPassed=0、selected=0，未产生 Shadow 订单或真实订单。
### 00:04 v4 Shadow 启动

- 修复提交：`127dff3 fix: close event maker halt and mark-to-market gaps`，已推送 `0b232f7..127dff3`。
- v4 输出：`backend/build/research/event-maker-safe-candidate-shadow-main-v4-20261020/`。
- wrapper PID `21352`，Java PID `17312`，启动 `2026-10-20 00:03:45 +08:00`。
- 首两轮 qualified=1、activityPassed=0、selected=0；原因 `sell_trades_30m_below_min=1` / `recent_trades_30m_below_min=1`。
- 当前 waiting，日志 0 异常，stderr 为空；严格门槛不变。
### 00:20 初始下单阶段清理边界

- 发现 `runLive` 的旧 try/finally 从主事件循环才开始，而初始 reconcile/quote 已经可能真实下单；首次 `persistLive` 失败会绕过撤单清理。
- 已把 try/finally 前移到 `ws.start()` 之前，覆盖 WS 启动、子任务、初始对账/下单、初始持久化和主循环。
- 外层 run job 失败 catch 现在取消 runScope 并关闭 feed、WS、data client。
- 全量测试：103 suites / 538 tests / 0 failures / 0 errors / 2 skipped。
- v4 Shadow 运行约 15m，11 轮严格发现均 activityPassed=0、selected=0，随后主动停止；未产生 Shadow 订单或真实订单。
### 00:22 v5 Shadow 启动

- 修复提交：`c570151 fix: cover initial live setup in cleanup`，已推送 `2b5ee12..c570151`。
- v5 输出：`backend/build/research/event-maker-safe-candidate-shadow-main-v5-20261020/`。
- wrapper PID `3076`，Java PID `23220`，启动 `2026-10-20 00:21:30 +08:00`。
- 首轮 qualified=1、activityPassed=0、selected=0；原因 `recent_trades_30m_below_min=1`。
- 当前 waiting，日志 0 异常，stderr 为空；门槛不变。
### 00:30 运行手册参数对齐

- Shadow 请求示例更新为正式候选：price 0.97–0.99、activity 30m/2h 门槛、remaining 720–10080m、entryOffset 0、exitSpread 1c、fillFactor 20、maxCycles 1、orderNotional 25。
- Live 示例改为单市场最小 smoke 参数，明确不是放宽门槛；`maxMarkets=1`、`maxTotalInventoryNotional=25`、`maxDailyLoss=5`，并要求用户先提供账户、资金、时间窗口、市场范围和明确授权。
### 00:50 Guard 审计语义与实验臂执行证据

- 正式 v5 在第 17 轮选中 Israel x Iran，bid 0.970 / ask 0.975、5 ticks、队列 6.87；Guard 连续阻止，未挂单。
- 实验臂（仅 minSpreadTicks=1）产生 shadow-1 BUY 0.972 / 25.72 股，queue 5.74，5 秒后 adverse_flow 撤单，0 fill。
- 实验期间出现 WS/Data API 握手瞬态，自动重连后恢复。
- 发现 `flow_guard_blocked` 被写入 `lastErrors`，会使最终审计误报；已改为事件 `blocked`，`lastErrors` 只保留真正的异常。
- `audit-event-maker-shadow.ps1` 只拒绝非预期 `lastErrors`；fixture 正向/负向均验证通过。
- 全量测试：103 suites / 538 tests / 0 failures / 0 errors / 2 skipped。
- v5 正式臂与实验臂于 00:50 主动停止；v6 待提交后启动。
### 00:54 v6 Shadow 启动

- 修复提交：`e321f4a fix: separate expected shadow guard blocks from errors`，已推送 `2c1162f..e321f4a`。
- v6 输出：`backend/build/research/event-maker-safe-candidate-shadow-main-v6-20261020/`。
- wrapper PID `1252`，Java PID `22320`，监控 PID `8120`，启动 `2026-10-20 00:53:09 +08:00`。
- 首轮 qualified=2、activityPassed=0、selected=0；原因 `recent_trades_30m_below_min=2`。
- 当前 waiting，日志 0 异常，stderr 为空；门槛不变，运行期间不再改代码。
### 00:58 回测产物完整性复核

- 数据集：`event-maker-full-v2-7d-20261019-dataset.json.gz`，49,553,423 bytes，SHA-256 `9B8A3F84A3CA47A135ACBE277E018021EC4A2C22DCC7B953E635F07238624424`。
- 八窗口 summary 各 864 配置；参数邻域 1,008；无 Guard 对照 864。
- 按正式筛选口径复算，八窗口交集为 2 个配置，与候选报告一致。
### 01:20 v6 正式候选挂单与重挂

- 01:01 选中 Israel x Iran，bid 0.972 / ask 0.975、3 ticks、queue 15.05。
- shadow-1：BUY 0.972 / 25.72，LIVE；01:08 因 requote 撤销。
- shadow-2：BUY 0.971 / 25.74，queue 65.04；01:13 因 requote 撤销。
- 当前 bid 0.966 低于 minPrice 0.97，状态 FLAT，不追单；0 fill、0 cycle、0 inventory。
- logIssues=0、stderrBytes=0；门槛不变。
### 01:24 v6 第三次重挂

- bid 回到 0.972 后挂 shadow-3 BUY 0.972 / 25.72，queue 5.0。
- 当前 BUY_PENDING、0 fill、0 cycle、0 inventory。
- 未放宽价格带、活动门槛、post-only、队列或亏损限制。

### 02:05 Event Maker 结算与赎回闭环

- 修复 Live 只在 SELL 时结束 cycle、市场直接结算时无法回收资金的问题。
- 新增 `SETTLE` cycle：市场出现终局 0/1 outcome 后按 `exitPrice × shares` 记账，P&L 与回测的结算语义一致。
- 结算记录在链上赎回前先持久化；即使进程在赎回调用后崩溃，重启也能根据 pending settlement 和链上持仓完成对账，不会静默丢失仓位。
- 新增 `PENDING / REDEEMED / ALREADY_ABSENT` 三种赎回状态；支持 Builder Relayer 赎回、交易哈希持久化和启动恢复重试。
- Live 启动时若 Builder API Key 未配置，直接以 `settlement_builder_api_key_missing` 拒绝启动，避免先建仓后才发现无法赎回。
- 终局检查不再只依赖 `endDate`，每 30 秒也会查询 Gamma 终局价格；这覆盖了 Gamma 提前结算但 endDate 仍在未来的市场。
- 恢复持久化新增 favorite token、favorite index、negRisk、endDate 和 tickSize；重启后可为 discovery 不再返回的持仓重建仅用于结算的市场元数据。
- 状态 schema 升级为 v4；恢复时重放 fills + settlements，校验库存、cycle、settlement、已实现 PnL 和赎回记录。
- 外部只读核对：Gamma closed 市场 `0x7ba05d259657e856a103b43c85357dcd2d5446fc056230b0e4eb20420af076d6` 返回 `outcomePrices=["0","1"]`，字段解析路径已覆盖。
- 全量测试：`103 suites / 555 tests / 0 failures / 0 errors / 2 skipped`。
- 仍未完成：使用真实账户、Builder API Key 和真实资金执行一次赎回 smoke；本轮只验证到 adapter/engine/startup 的 mock 与单元边界。
### 01:26 v6 shadow-3 requote 撤销

- `shadow-3` BUY 0.972 / 25.72 于 01:26 因 requote 撤销。
- v6 当前 FLAT，activeOrders=0、0 fill、0 cycle、0 inventory；`logIssues=0`、`stderrBytes=0`。
- 三次挂单、三次 requote 撤单均为严格门槛下的正常执行链路，不构成成交验收。
### 03:05 v6 继续重挂，仍无成交

- 02:21 `shadow-4` BUY 0.970 / 25.77，queue 25.0；02:30 requote 撤销。
- 02:30 `shadow-5` BUY 0.971 / 25.74，queue 20.03；02:35 requote 撤销。
- 02:35 `shadow-6` BUY 0.970 / 25.77，queue 485.03；02:56 requote 撤销。
- 累计 6 次挂单、6 次 requote 撤单；当前 FLAT、0 fill、0 cycle、0 inventory。
- 未放宽正式门槛；正式配置为 `shadowFillVolumeFactor=20`，`shadow-6` 在 queue 485.03 下仍未达到保守成交确认量，不能靠降低 factor 伪造成交。
### 03:10 v6 0 fill 的直接原因核验

- 以公开成交逐笔复算六笔 Shadow 订单的存活窗口。
- 只有 `shadow-5` 在存活期内出现 1 笔可穿越 SELL：215.00 股；先消耗 queue 20.03 后剩 194.97 股。
- 正式 `shadowFillVolumeFactor=20`，`shadow-5` 需要累计 `25.74 × 20 = 514.80` 股才确认成交，194.97 < 514.80，因此 0 fill。
- 其他五笔订单在存活窗口内没有价格可穿越且方向为 SELL 的公开成交。
- 结论：0 fill 与 q20 保守成交模型一致，不是执行器漏记；不能用降低 factor、放宽价格带或追价来伪造 fill。
### 03:15 Shadow 成交缺口复核脚本

- 新增 `scripts/analyze-event-maker-shadow-fills.ps1`，从 events 和 trades 重建订单存活窗口、queue 消耗、q20 确认量和实际 fill。
- `audit-event-maker-shadow.ps1` 已自动调用该脚本；模型应成交但 state 无成交时审计失败。
- v6 复算结果：6 笔订单只有 `shadow-5` 出现可穿越 SELL 215.00 股；queue 20.03 后剩 194.97，q20 需要 514.80，故模型与实际均为 0 fill。
- 报告：`backend/build/research/event-maker-safe-candidate-shadow-main-v6-20261020/fill-analysis.json`。
### 03:20-03:45 结算账本审计与部分卖出后结算修复

- 核对审计脚本时发现两个问题：`warnings` 被重复初始化，导致 fill-analysis 告警可能丢失；PowerShell 回放只按 SELL 成交闭环，未识别新增的 SETTLE cycle。
- 审计脚本已补上 settlement 回放：校验 settlement 身份、outcome/favorite/negativeRisk、结算股数、结算 PnL、settlement 与 cycle 的对应关系，以及部分卖出后 `已卖出 + 剩余可赎回 = 总买入`。
- Kotlin 修复：旧 `recordSettlement` 在部分卖出后只记录剩余仓位的 PnL 和股数；现在 `settlement.shares` 保持为链上可赎回剩余股数，`cycle.shares` 为总买入股数，`cycle.pnl` 为部分卖出已实现 PnL 加剩余结算 PnL，`realizedPnl` 只增加结算部分。
- 新增 `EventMakerLiveStateTest` 回归：买入 10、部分卖出 4、剩余 6 到期结算；settlement PnL=0.24、cycle PnL=0.28、cycle shares=10，并验证重启恢复。
- 审计临时 fixture 正反例通过：正常场景 `passed=true`；篡改 settlement PnL 或 cycle shares 后退出码为 1；fixture 已清理。
- 隔离 worktree 全量回归：103 suites / 556 tests / 0 failures / 0 errors / 2 skipped。
- v6 Shadow 继续运行；截至 03:38 仍为 FLAT、6 次挂单、0 fill、0 cycle、0 inventory，`logIssues=0`、`stderrBytes=0`。v6 使用启动时构建产物，本轮 Kotlin 修改未替换运行中的产物。

### 03:50 v6 shadow-7 / shadow-8 继续执行证据

- `shadow-7`：03:23:44 BUY 0.973 / 25.69，queue 6.18；03:28:44 requote 撤销，0 fill。
- `shadow-8`：03:45:38 BUY 0.970 / 25.77，queue 105.70；03:50:39 requote 撤销，0 fill。
- v6 累计 8 次 Shadow 挂单、8 次 requote 撤单；当前 FLAT、activeOrders=0、0 fill、0 cycle、0 inventory。
- `logIssues=0`、`stderrBytes=0`；没有放宽价格带、活动门槛、post-only、q20 或队列限制。
- 真实 Shadow fill/cycle 仍未出现，因此不能把 Shadow 长测标记为成交验收通过。

### 04:05 结算/赎回瞬态失败计数修复

- 结算快照失败计数和赎回持仓失败计数原先只在最终结算/赎回成功时清零；成功读取但尚未决议/不可赎回时会保留旧失败数，后续瞬态失败可能提前触发 halt。
- 现在成功读取 settlement snapshot 即清零 `settlementFailures`；成功读取 redemption position 即清零 `redemptionFailures`。
- 新增 `EventMakerLiveEngineTest` 两条回归：结算快照失败计数重置、赎回持仓失败计数重置。
- 隔离 worktree 全量测试：103 suites / 558 tests / 0 failures / 0 errors / 2 skipped。
- v6 Shadow 继续运行，04:02 为 FLAT、0 fill、0 cycle、0 inventory；未触碰运行中 v6 的构建产物。
### 04:15 结算身份与赎回回执核验

- 发现 `getSettlementSnapshot` 只把 market 作为查询上下文，没有强制校验账户持仓的 `negativeRisk` 与市场元数据一致。
- 改为 `expectedNegRisk = market.negRisk`，negRisk 不一致时 fail-closed，避免用错赎回 adapter。
- `redeemSettlement` 增加回执内容核对：交易必须包含相同 market/outcome，且赎回 quantity 必须等于 settlement.shares；只有交易哈希不再足够。
- 新增 adapter 反例测试：negRisk 不匹配、赎回数量不匹配均拒绝。
- 隔离 worktree 全量测试：103 suites / 560 tests / 0 failures / 0 errors / 2 skipped。
- v6 在 04:11 仍正常运行，FLAT、0 fill、0 cycle、0 inventory；本轮未替换运行中 v6 的构建产物。
### 04:45 审计脚本旧产物兼容

- 旧版 Shadow 的 `execution-diagnostics.json` 没有 `fillVolumeFactor`；`audit-event-maker-shadow.ps1` 原先回退 `-1`，导致旧产物无法复核。
- 改为缺字段时使用保守默认 `1.0`；正式运行仍以 `watch-process.json` 的 `shadowFillVolumeFactor=20` 为准。
- 对全部历史 Shadow 目录执行审计回归：最终干净产物通过，早期中断目录因 activeOrders 非零按预期失败。
- v6 在 04:44 仍正常运行，未放宽任何正式门槛。
### 04:55 Shadow 逐单成交股数校验

- 发现审计的数量比较无法区分“模型整单成交”与“实际部分成交”。
- 新增逐单校验：模型 `modelFill=true` 时实际成交股数必须等于订单原始股数；实际超过订单股数或模型未成交却实际成交分别失败/告警。
- 合成 fixture 验证：模型 full、实际 0 时输出 `fillModelShareMismatch` 且审计退出 1；fixture 已删除。
- v6 继续运行，未改变 q20、价格带或任何正式门槛。
### 05:10 已赎回状态必须带交易哈希

- 状态校验原先允许 `REDEEMED` 结算没有交易哈希，重启后无法审计链上赎回。
- 现在 `EventMakerStateStore` 对 `REDEEMED && transactionHash.isNullOrBlank()` fail-closed。
- 新增 `EventMakerStateStoreTest` 反例并跑通全量：103 suites / 561 tests / 0 failures / 0 errors / 2 skipped。
- v6 继续运行，未触碰其构建产物。
### 05:35 两项错误尝试已回滚

- 曾尝试增加 `cumulativeRealizedPnl >= sum(markets.realizedPnl)` 校验；复核发现历史市场退出 `markets` 列表后该关系不成立，会误伤合法重启状态。提交 `500aa71` 已由 `802b8a1` 回滚。
- 曾尝试新增 `NO_PAYOUT` 状态让输方仓位跳过链上赎回；复核确认输方仓位仍需在链上赎回以清仓，否则重启时会被判定为未管理持仓。未提交的改动已回滚。
- 当前保留的规则是：所有已结算持仓都先持久化，再走真实赎回；链上已无仓位才标记 `ALREADY_ABSENT`。

### 07:00-08:30 活动门槛口径复核：旧八窗口结论被推翻

- 复核三笔归零案例时发现回测 `markout60sPnl` 用 `entryTimestamp + 60_000L`，而研究数据集时间戳是 epoch 秒，实际算的是 16.7 小时。修复为 `+60L`，并新增回归测试；全量 103 suites / 562 tests / 0 failures 通过。
- 用数据集逐笔复算：278 回合基线配置真 60 秒 markout 为 -283.28（均值 -1.019、145 个负），旧 CSV 是 +109.06（均值 +0.392、21 个负），93 个回合全部不一致。
- 进一步定位到更严重的问题：2026-10-19 22:12 生成的八窗口产物**没有传活动门槛**。用当前代码不传门槛重跑 1d，与旧文件逐行完全一致（93 回合、408 次 Guard、+97.87）；传入 Shadow/Live 正式门槛后变成 68 回合、1 次结算亏损、PnL -31.18。
- 带正式门槛重跑 12h–7d：12h 43 回合 0 亏损 +1.026%；1d 68 回合 **1 亏损 -0.459%**（测试 -0.808%）；2d 60 回合 0 亏损 +1.024%；3d 49/+1.056%；4d 47/+1.040%；5d 37/+1.048%；6d 35/+1.076%；7d 31/+1.054%。
- 按原筛选口径（每窗口零结算亏损、总/训练/测试 ROI > 0、训练成交 ≥20）复算，全窗口通过配置为 **0 个**，不是旧记录里的 2 个。
- 止损可行性复核：-2c 止损触发 125 次、其中 122 次 whipsaw，总 PnL 从 +224.37 变为 -356.48。旧报告“成交后瞬间归零、止损无用”的因果解释错误，但“不能靠止损解决尾部”的结论仍成立。
- 新增报告 `docs/zh/event-maker-activity-gate-recheck-2026-10-20.md`；候选报告与交付计划已标记旧结论作废。
- v6 Shadow 继续运行，仅作执行链路证据；未放宽任何正式门槛。当前策略不得实盘、不得跟单。

### 08:30-09:00 结算前尾部窗口规则

- 发现并修复的方向：在剩余时间不足 N 分钟时撤掉 BUY 且不再新挂，属于事前暴露控制，不是止损。
- 1d 阈值扫描：2h/4h/6h 拦不住那笔在剩余 6.65h 成交的亏损；6h 反而移除 5 笔盈利、PnL 更差；8h 移除 10 笔盈利 + 1 笔 -100，PnL 从 -31.18 变 +58.65，ROI +1.029%；12h 为 +52.25 / +1.024%。
- 8h 规则八窗口：均为 0 结算亏损，ROI +1.025%..+1.076%，训练/测试全正；但 6/8 窗口训练成交 <20（12h 仅 4），且全 864 配置中没有配置能在每个窗口都达到训练成交 ≥20 且零亏损。
- 参数族对比：12h/1d/2d 出现过亏损的配置数分别从 652/238/288 降到 451/33/144；3d/4d/6d 无变化（322/298/262），说明还有别的非时间型尾部事件，规则只是部分缓解。
- 实现：回测 `minRemainingSeconds`、Live/Shadow `tailWindowMinutes`，触发原因 `tail_window`；新增 4 条测试；全量 103 suites / 566 tests / 0 failures / 0 errors / 2 skipped。
- 明确弱点：8h 门槛紧邻唯一已观察亏损的成交时点（6.65h），存在过拟合风险；不能用它证明尾部已消除。
- 下一步：v6 结束后启动 `shadowTailWindowMinutes=480` 的 v7 前瞻 Shadow。

### 09:10 结构性 EV 复核：7/8 窗口无条件负期望

- 用数据集统计每个入场窗口内 `[0.97,0.99)` favorite 的最终失败率：12h 2.85%、1d 1.60%、2d 1.67%、3d 1.66%、4d 2.56%、5d 2.58%、6d 2.36%、7d 0%（216 个市场）。
- 候选每赢一回合约 +1.02%、输一回 -100%，盈亏平衡亏损率约 1.01%。除 7d 小样本外，无条件 EV 全部为负（-0.59% 到 -1.86%/回合）。
- 1d 的 57 个成交在 p=1.60% 下出现 0 亏损的概率约 39.9%，所以“0 结算亏损”不构成 edge 证据。
- 结论：这是结构问题，不是参数问题。必须找到事前可识别、能把成交池失败率压到 1% 以下的筛选，或提高每回合收益/改变收益结构；在此之前不能实盘。
- 新增可复现脚本 `scripts/analyze-event-maker-tail-base-rate.cjs` 与 `event-maker-tail-base-rate-20261020.json`；详见 `event-maker-tail-window-2026-10-20.md` 第 9 节。

### 09:20 成交假设稳健性终判

- 宽退出网格 1,440 配置 × 8 窗口，全部带活动门槛、Guard、maxOrderAge 1800s、8h 尾部规则。
- 12h 窗口 0 个配置通过（8h 尾部规则只剩约 4h 报价，训练成交不足 20）。
- 1d–7d 交叉：只有 q1 的 10 个配置全部通过（1d 训练 ROI +1.76%..+2.09%，测试 +1.71%..+1.92%）；q2 最好 6/7；q5/q10 最好 6/7；**q20 最好 3/7；q50 为 0**。
- 结合基础失败率：0.97–0.99 价格带失败率 1.60%–2.85%，每回合 1c 收益约 1.0%，覆盖不了；3c 宽退出把收益提到约 2.9%，但更多仓位拖到结算。
- 结论：**当前策略族的正收益依赖宽松成交假设；在保守 q20 下没有跨窗口稳健配置，不能实盘。**
- 下一步：要么用真实 Shadow 成交校准 fill factor（v6 目前 0 fill，无法校准），要么找到能把成交池失败率压到 1% 以下的事前筛选，要么更换收益结构。
- 报告：`docs/zh/event-maker-fill-robustness-verdict-2026-10-20.md`。

### 10:30 容量终判：高价带正期望但经济上不可行

- 2d 高价带：98 回合 / 2.75 年 ≈ 35.7 回合/年；每回合 EV 0.1382 USDC（100 名义本金）；年化 EV 约 4.93 USDC。
- 单次 -100 占 1% 资金时（总资金 10,000），年化只有 0.049%；占 10% 资金时（总资金 1,000），年化 0.493%。
- 要达到 10% 年化，需要把 edge/仓位放大约 203 倍，单次归零会超过 100% 资金，直接爆仓。
- 结论：**Event Maker 策略族（0.97–0.99 和 0.99–0.999）都不适合真实资金。**
- 报告：`docs/zh/event-maker-high-prob-band-2026-10-20.md` 第 9 节。

### 10:35 流动性奖励复核

- 新增 `scripts/analyze-event-maker-liquidity-rewards.mjs`，按官方 `S(v,s)` / `Q_min` / `Q_epoch` 公式计算 paired-bid 份额；默认拒绝过期、低成交、薄竞争和过期 book，并输出 JSON/CSV。
- 扫描 Politics + Geopolitics 各 1,000 个 active 市场，识别 692 个有正日奖励的市场；当前 0.97–0.99 没有可交易奖励池，0.90+ 奖励市场集中在 0.905–0.955。
- 过滤 `volume24h>=1000`、剩余 `>=24h`、book 新鲜后，0.90+ 仅 7 个市场：日池 662、估计日奖励 0.2266、资金 267.36、加权日收益 0.0847%（简单年化 30.9%）。该数字不可当作稳定年化，市场即将结算且样本仅 7 个。
- 全价格带 207 个市场估计日奖励 173.73 / 资金 16,898，但中低价市场方向风险远高于 0.90+，不能直接使用。
- 目标地址公开奖励：446 天累计 4,195.28 USDC；最近 30 天 70.35、最近 7 天 5.49；当前仓位约 20,084 USDC。奖励不是其主要收益来源。
- 结论：流动性奖励不能救活 0.97–0.99 单边 maker；唯一待验证方向是 0.90–0.95 双边 post-only BUY + 单边成交立即对冲。RWD-3～RWD-5 未完成前禁止实盘。
- 报告：`docs/zh/event-maker-liquidity-rewards-2026-10-20.md`。

### 11:05 奖励计算器与配对报价器落地

- 新增 `LiquidityRewardCalculator`：官方二次评分、Q_one/Q_two/Q_min、单边/双边规则和 0.10/0.90 边界都有测试。
- `LivePaperMarket` 与 `LivePaperDataClient` 已解析 Gamma 的 active reward allocation、`rewardsMinSize`、`rewardsMaxSpread`、`holdingRewardsEnabled`。
- 新增 `PairedRewardQuotePlanner`：在 YES/NO 两个 outcome 上生成 post-only BUY，自动使用奖励最小 size，拒绝穿价和配对成本超限。
- 回归：105 suites / 578 tests / 0 failures / 0 errors / 2 skipped。
- 下一步：真正的双 token Shadow + 单边成交 IOC hedge。当前代码未改变 v6 行为，也未开启实盘。

### 11:45 配对奖励 Shadow 终判

- 新增 `PairedRewardShadowCli.kt`、Gradle `pairedRewardShadow` 任务；订阅 YES/NO 两个 token，双边模拟 post-only BUY，单边成交后 grace 或立即 taker IOC hedge。
- 5 秒宽限 smoke：7 市场、13 报价周期、1 maker fill、0 natural pair、1 hedge；奖励 0.0068，配对亏损 -2.4022，净 -2.3954。
- 立即对冲 smoke：7 市场、34 报价周期、1 maker fill、0 natural pair、1 hedge；奖励 0.2100，配对亏损 -1.8486，净 -1.6386。
- 两次均证明：bid 配对虽然约 0.994，但单边成交后买对手 ask + 0.2c 滑点约 1.006，taker fee 后单次损失远超同期奖励。
- 新增安全门槛 `maxHedgePairCostPerShare` / `hedgeSlippageCents`；按 1.0 + 0.2c 扫描 87 个候选，0 个通过，正确结果是“不报价”。
- 结论：0.90–0.95 paired reward farming 当前不可行；0.97–0.99 单边 maker 亦不可行；不跟单、不实盘。报告：`docs/zh/event-maker-paired-reward-shadow-2026-10-20.md`。- 修复 `CopyTradingFullChainE2ETest` 的异步竞态：`processedTrades.size` 直接断言改为先 awaitCondition，避免 SELL 异步落库尚未完成时误报；全量回归恢复为 105 suites / 579 tests / 0 failures。
### 12:10 完整集/NegRisk 套利扫描器加固与全量扫描

- 新增并重构 `scripts/scan-event-complete-set-arbitrage.mjs`：按逐档深度吃单，使用价格相关 taker fee、每腿最小下单量、0.01 share 精度、盘口新鲜度、交叉盘口检查、事件完整性和 gas buffer。
- 新增 `scripts/monitor-event-complete-set-arbitrage.mjs`：Gamma 顶层价格发现候选，CLOB 可执行盘口快速轮询，机会出现后延迟 1 秒二次确认，并记录 watchlist、JSONL 快照和 episode。
- 新增 15 个 Node 测试：`cd scripts; npm run test:complete-set`，15 tests / 0 failures。
- 全量 snapshot：1,739 个市场、821 个组、582 个完整评估、239 个不完整/关闭组；ask/bid 原始正收益均为 0，可执行套利均为 0；最低 top ask sum 0.983，最高 top bid sum 0.999。
- 0.983 的 NegRisk 候选因 4 腿合计约 2.28 USDC taker fee，扣费后为负；证明“价格接近 1”不等于“有 edge”。
- 连续监控 smoke：126 个市场、20 个组、10 个 proxy 候选、5 个 watchlist，15 秒无 request error，无可确认机会。
- 报告：`docs/zh/event-maker-complete-set-arbitrage-2026-10-20.md`。
- v6 Shadow 至 12:06 仍存活：fills=0、cycles=0、inventory=0、phase=FLAT、logIssues=0、stderr=0；预计 12:53 结束。
### 13:30 最优数量修复、完整集候选与跨类别扫描

- v6 Shadow 于 12:53 正常结束；审计通过：12h、8 quotes、8 cancels、0 fills、0 cycles、0 PnL、0 duplicate，`shadow-audit.json passed=true`。由于 0 fill，不能据此证明 maker edge。
- 发现并修复 `maxPositiveShares` 的核心错误：旧算法选“累计利润仍为正的最大数量”，会把浅档正 edge 一直加到深档后磨平。现改为逐档边际利润最优化；新增回归测试。
- 修正后 Politics + Geopolitics 全量扫描（1,739 市场 / 820 组 / 510 完整评估 / 72 预过滤）：发现 1 个正收益 NegRisk 完整集候选。
- 候选：`Who wins the Senate pending Hormuz Agreement by Oct 23?`，3 outcomes，top ask sum 0.987，fee 1.6757，gas 0.02，310 shares，净利润 1.6289，edge 0.52545c/share，盘口年龄 997ms。
- 人工间隔 1 秒复核 3 次：top ask sum 0.986，1,520.04 shares，净利润 5.34478，edge 0.35162c；稳定存在但低于 0.5c 正式门槛。不能实盘。
- 24h 完整集监控 v3 已启动：PID `22668`，watchlist 30，poll 15s，discovery 10m；截至 13:27 poll=14、errorRows=0，Hormuz 候选持续出现但 askActionable=false（below_edge_buffer，当前约 0.474c）。
- 新增日期单调性扫描器与相关测试；修正两个假信号：`on DATE` 独立日市场不能当嵌套日期；`continues through` 是持续/否定方向而非正向发生方向。
- 日期单调性最终快照：1,735 市场 / 721 事件 / 327 pairs / 0 正收益，最低 top ask sum 1.015。
- 跨类别完整集扫描：
  - Crypto + Finance：1,980 市场 / 1,000 组 / 851 完整评估 / 2 个正收益但均 stale / 0 actionable。
  - Sports：991 市场 / 500 组 / 410 完整评估 / 0 正收益 / 0 actionable。
- 当前仍不实盘；完整集候选必须连续 24h 记录并越过 0.5c 二次确认门槛。
### 13:55 NegRisk NO basket 与首个门槛确认

- 扩展完整集扫描：新增 NegRisk “全买 NO”方向，组合 payout 为 `outcomes - 1`；新增 `noBasket` 结果、监控 episode 和 CSV 字段。
- 最新 Politics + Geopolitics 扫描：1,740 市场 / 821 组 / 510 完整评估；1 个 ask-side 机会，0 个 NO-basket 机会。
- Hormuz 候选快照：top ask sum 0.986，496.62 shares，净利润 3.0022，edge 0.60453c/share。
- 24h 监控 v4 启动：PID `15632`，watchlist 30；首次二次确认 `confirmed=true`，确认时 age 2,449ms，496.60 shares，净利润 3.02476，edge 0.60909c。
- 这是首个二次确认后仍高于 0.5c 的真实候选，但三腿非原子、失败腿回吐风险未解决，仍禁止实盘。
### 14:35 非原子执行风险、Mock 执行器与实时复核

- 新增 `complete-set-basket-risk.mjs`：YES/NO 支付矩阵、逐腿部分成交、撤单后最终股数、bid 深度不足、hold/flatten/sell-excess 三种失败策略，以及 `maxLossUsd` / `maxCapitalUsd` 双预算。
- 新增 `analyze-complete-set-execution-risk.mjs`：只读拉取真实盘口，按实际深度成本生成风险报告；没有签名或提交订单的代码路径。
- 新增 mock-only 顺序执行器：每次只提交一腿，确认完整成交后才继续；部分成交先撤单，再按 bid 回滚，保留未卖出库存并标记 `unhedged`。live/mock 之外的 adapter 直接拒绝。
- 执行顺序改为便宜腿优先。原因是失败发生在后段时，已暴露的未对冲成本更小；这不是成交率优化，必须继续用真实延迟数据验证。
- 实时 Hormuz 复核（14:22 +08:00）：3 outcomes，top ask sum 0.99，546.57 shares，entry/exit 各 0.2c 滑点，完整成交净 PnL -1.541957 USDC，最坏观测损失 40.9809 USDC，判定 `reject_no_profitable_complete_fill`。最小 5 share 也为 -0.027157 USDC。
- 即使忽略额外 entry slippage，之前约 0.3179c/share 的完整利润也只有约 1.74 USDC，而两腿成交、第三腿失败的最坏观测损失约 41 USDC，风险收益比仍超过 20 倍。
- ask-side 扫描结果已内嵌 `executionRisk`；监控 compact row、confirmation、episode 会记录 risk capital、worst observed loss、worst fills，并新增 confirmation pass rate 与 episode 时长统计。
- Node 回归：50 tests / 50 pass / 0 fail。
- 24h 监控 v4 继续运行：PID 15632，poll 92，lastPollErrors 0，confirmed=1，openEpisodes=1。
- 当前仍禁止真实资金；下一步是等待 24h 完成审计，并重跑带 NO-basket 的跨类别扫描。
### 15:25 盘口新鲜度修复、OpenAI 连续确认与真实年化

- 发现并修复关键漏单：CLOB `timestamp` 被误当作抓取时间，导致安静但有效的盘口被判 stale。现在 `fetchedAt` 决定 actionable 新鲜度，`sourceUpdatedAt` 仅作参考；交叉/缺失盘口仍 fail-closed。
- 修复后全类别扫描 staleEvaluations=0。
- OpenAI IPO Closing Market Cap：7 个完整互斥档位，top ask sum 0.92；v3 监控从 06:50:49Z 到 07:20:48Z 连续 74 次轮询 actionable，最近 edge 4.3657c，未出现请求错误或二次确认失败。
- 26.03 股：完整净利 1.1364 USDC，投入 24.8936 USDC，terminal ROI 4.565%，最晚结算 2028-01-01，max hold 437.9 天，最晚结算年化 3.791%。
- 20 股：完整净利 0.6040 USDC，投入 19.3960 USDC，terminal ROI 3.114%，最晚结算年化 2.589%。
- NegRisk 文档确认 `convertPositions` 是 NO -> 其它 YES + collateral；买入全部 YES 需要持有到结算，因此 OpenAI 不是可立即换回现金的翻仓套利。
- Mock：全成交 +1.1974；最后腿失败并回滚 -2.4115；最后腿失败且无买盘 -13.8822。
- 全类别扫描：4,712 市场 / 1,337 组 / 1,028 完整评估 / 3 个 ask 正收益 / 1 个 actionable / 0 个 NO-basket actionable；最佳最晚结算年化 3.791%。
- 结论：策略数学上能赚钱，但当前是低收益、小容量、长资金占用；继续寻找短周期、大容量、可立即转换的结构。Node 回归 54/54。
### 15:52 全市场扫描改用官方 keyset 并启动全量

- 本轮目标：不再只扫热门 tag，覆盖当前全部 active/closed=false 市场，找出短结算、大容量、可交易的真实完整集机会。
- 发现：Gamma 传统 `/markets` 的 offset 在 2100 条后返回 422（`offset too large, use /markets/keyset for deeper pagination`）；旧分页不能支持“全市场”结论。
- 动作：`discoverTag` 与新增 `discoverAllMarkets` 统一改用 `/markets/keyset` 的 `next_cursor`/`after_cursor`，按稳定 `order=id` 翻页；遇到重复 cursor 直接失败，不静默漏市场。
- 测试：新增 all-markets 参数、tag keyset、分页去重、重复 cursor fail-closed 用例；`node --test scripts/test` 为 **58 tests / 58 pass / 0 fail**。
- 冒烟：300 市场拉取 299 个可规范化市场、209 组，完整 CLOB 链路正常；输出 `backend/build/research/complete-set-all-markets-smoke-20261020.json`。
- 提交：`89a5c03 feat: scan all complete-set markets with keyset pagination`。
- 进行中：全市场扫描 maxMarkets/maxGroups=100000、minVolume=0、book age 30s、并发 12；输出目录 `backend/build/research/complete-set-all-markets-20261020`。
- 监控：Politics+Geopolitics v6 与 Crypto+Finance v3 均继续存活；Crypto+Finance 在 06:50–07:53Z 的 147/148 个可用快照中，OpenAI 7 档完全集均可套利，ask top sum 0.920–0.937，edge 3.56–4.80c，但容量仅约 26 股且资金最长锁至 2028-01-01。
### 17:00-17:45 全市场批量盘口扫描与实时顺序 paper 执行

- 优化：CLOB 单本 `/book` 改为官方 `POST /books` 批量预取（最多 500 token/批），按组分块、评估后释放缓存。全市场扫描从 75 分钟以上未完成降到 **14.8 分钟**。
- 全市场结果：**99,956 市场 / 60,954 组 / 42,070 完整评估 / 315 个盘口批次 / 0 预取错误**。
- 正收益 ask：4 个；actionable：3 个；bid-side / NO-basket actionable：0。
- 候选：
  - World Cup 2030：807.22 股，完整利润 24.3442，资金 782.8758，最坏持有损失 378.0934；资金占用最长可到 2027 年底/2030 endDate。
  - OpenAI：26.03 股，完整利润 1.3462，最晚年化 4.527%，但容量仅约 25 USDC。
  - Next leader：10 股，利润 0.0651，22 腿，容量太小。
  - Bitcoin vs Gold vs S&P 500：0.3132c，未达 0.5c 门槛。
- 新增 `scripts/paper-execute-complete-set.mjs`：逐腿重新读取真实盘口、1 秒间隔模拟 taker 成交、失败后按实时 bid 模拟平仓；无签名、无下单。
- Paper 正常执行全部通过：
  - World Cup 100 股：+4.0520 USDC；
  - OpenAI 20 股：+1.0333 USDC；
  - Next leader 10 股：+0.1334 USDC（22/22 腿）。
- Paper 故障注入：
  - World Cup 最后一腿失败：按 bid 平仓后 -18.8467 USDC；完全无买盘持有约 -45.6985 USDC；
  - OpenAI 最后一腿失败：前 6 腿全部平仓后 -1.9831 USDC。
- 结论：能赚，但没有可规模化、高收益、短周期的候选；完整利润明显小于非原子失败损失。
- 报告：`docs/zh/complete-set-all-market-scan-2026-10-20.md`。
- 测试：Node 全套回归 68 tests / 68 pass（含 batch 与 paper 执行纯函数测试）。
- 24h 监控 v6/v3 仍运行；尚未到 2026-10-21 06:50Z 结束时间。

### 21:20-21:27 24h 监控中断与脱离会话续跑

- 现场核对：原 v6 PID 23984、v3 PID 6804 已不在运行；最后写入为 `2026-10-20T13:17:44Z` / `13:17:31Z`，实际只跑了约 6.45 小时，不是计划的 24 小时。原因是原进程由 PTY 会话启动，会话结束后被一并终止。
- 中断前有效数据：
  - Politics+Geopolitics v6：889 次 poll，确认 2 次，全部为 `Next leader out of power before 2027?`，最大 edge 1.5686c，最大确认利润 2.0863 USDC；失败原因 `below_edge_buffer=7`、`request_error=2`。
  - Crypto+Finance v3：799 次 poll，确认 11 次：OpenAI 6 次、Anthropic IPO 估值 3 次、Bitcoin vs Gold vs S&P 2 次；最大 edge 5.8798c，最大确认利润 4.4402 USDC；失败原因 `below_edge_buffer=8`、`no_positive_after_fees_or_depth=2`。
- 新增 `scripts/start-complete-set-monitors.ps1`：显式设置代理环境变量，用 `Start-Process -WindowStyle Hidden` 脱离会话启动两套监控，自动避开已存在的 output dir，并写 `complete-set-monitor-pids-*.json`。
- 续跑参数：`durationMs=62,648,742`，目标仍是原始终点 `2026-10-21T06:50:16Z`。
- 续跑进程：v6 PID `11804`，目录 `complete-set-monitor-24h-v6-resume-20261020-212607`；v3 PID `23072`，目录 `complete-set-monitor-24h-crypto-finance-v3-resume-20261020-212607`。
- 续跑验证：两套均已进入正常轮询，watchlist=30、errorRows=0；v3 首个轮询即再次确认 OpenAI（5.6984c / 1.4833 USDC）与 Bitcoin vs Gold vs S&P（1.4877c / 3.3920 USDC）。
- 结论：这不是策略失败，而是监控进程生命周期问题；已改为可脱离会话的启动方式，避免重复。
### 21:35-21:46 纠正策略认知：adonpoli 是债券 carry，不是完整集套利

- 公开账户事实（Data API）：
  - 组合价值 20,092.56 USDC；
  - 390 个已平仓，累计买入 5,626,529 USDC，已实现 +29,919.56，单次周转 ROI 0.532%；
  - 281 盈 / 86 亏；16 个月中 13 个月为正、3 个月为负。
- 价格带（已平仓）：0–0.5 ROI -0.49%、0.5–0.8 +1.90%、0.8–0.9 +0.18%、0.9–0.95 +0.72%、0.95–0.97 +0.22%、0.97–0.99 +0.82%、0.99–1.00 +0.25%。
- 最近 5,500 条 activity 覆盖 232 天：REWARD 2,316 + MAKER_REBATE 537 + YIELD 8.66 + TAKER_REBATE 10 ≈ 2,872 USDC，约占同期总收益 15%。
- 关键纠正：0.532% 不是年化，而是**每次周转**；按 5.63M / 20.1k ≈ 280 次周转，累计约 149%。他的核心是“高概率债券 + maker 成交 + 高周转 + 分散”，不是完整集套利。

### 21:40 BOND 回测 profile 与结果

- 在 `EventMakerBacktestCli.kt` 新增 `BOND("bond")` grid profile：
  - 价格带 0.90–0.95 / 0.95–0.97 / 0.97–0.99 / 0.99–0.999；
  - `exitSpread=100c` 表示不主动卖、持有到结算；
  - queueFactor 1/2/5/10/20；maxCycles=1。
- 命令：`gradlew eventMakerBacktest -PresearchGridProfile=bond -PresearchDataset=...7d... -PresearchEntryHorizonSeconds=604800`。
- 结果（真实 taker SELL 流才成交）：
  - 0.95–0.97, off0, q1：160 eligible / 88 filled，PnL +360.78，ROI **4.10%**，train 4.05% / test 4.13%，0 结算亏损，MAE -92.6%；
  - 0.97–0.99, off0, q1：260 / 170，PnL +308.17，ROI **1.81%**，train 1.80% / test 1.82%，0 结算亏损，MAE -95.4%；
  - maker fee 100bps 下 0.95–0.97 仍为 **2.32%**。
- 证据：`backend/build/research/event-maker-bond-20261020-summary.csv`、`...-fee-sensitivity.csv`。
- 报告：`docs/zh/leader-bond-strategy-replication-2026-10-20.md`。

### 21:44 BOND Shadow 启动

- 新增 `scripts/start-bond-shadow.ps1`，用隐藏后台 Gradle 进程启动。
- 配置：0.95–0.99、exitSpread=100c、maxCycles=1、order 100 USDC、minVolume=10k、maxRemaining=30d、5 个市场。
- 首次发现：12 个合格市场，选中 5 个，WS 订阅 10 个 token；进程 PID 24440，输出 `backend/build/research/event-maker-bond-shadow-20261020-v2`。
- 运行 120 分钟；目标是观察真实盘口下的挂单、成交、MAE 和结算行为。
### 21:50 官方费率核实：maker 确实 0 费

- 官方文档 `Trading Fees` 原文：
  - `feeSchedule.takerOnly`: When `true`, fees are charged to the taker side only, and **makers pay no fee**.
  - `feeSchedule.rebateRate`: Fraction of taker fees rebated back to the resting maker. Example: `0.25` = 25% rebate.
- 实查 Gamma：
  - FC Barcelona 2026-10-20：feesEnabled=true，sports_fees_v3，takerOnly=true，rate=0.05，rebateRate=0.15；
  - Flávio Bolsonaro 巴西总统：feesEnabled=true，politics_fees，takerOnly=true，rate=0.04，rebateRate=0.25；
  - US x Iran ceasefire：feesEnabled=false，完全无手续费。
- 因此：
  - maker 实际费率 = 0；之前 BOND 回测的 `makerFeeRate=0` 口径正确；
  - 100bps 费用表只是压力测试，不是实际 maker 费率；
  - maker rebate（politics 25%、sports v3 15%）和 liquidity reward 未计入回测，所以回测结果偏保守；
  - 新增 `EventMakerFeeModelTest` 锁定 makerFeeRate 默认 0、负值拒绝，测试通过。
- 报告已更新：`docs/zh/leader-bond-strategy-replication-2026-10-20.md` 第 4.1 节。

### 22:10 复核官方费率：maker 全类别 0 费，rebate 是池子分成

- 官方来源：`https://docs.polymarket.com/trading/fees`、`https://docs.polymarket.com/programs/maker-rebates`。
- `Fees` 原文：**Makers are never charged fees. Only takers pay fees.**
- 费率表：所有类别 `Maker Fee Rate = 0`；taker 费率按类别为 0.04/0.05/0.07，Geopolitics 与部分 `feesEnabled=false` 市场完全免费。
- `rebateRate` 是 taker fee 进入 maker 返佣池的比例（Crypto 20%、Sports 15%、其余多数 25%），再按 maker 的 fee-equivalent 份额分配；不是每笔自动返还自己手续费的固定比例。
- 实查 Gamma：politics `takerOnly=true/rate=0.04/rebateRate=0.25`，sports_v3 `0.05/0.15`，sports_v2 `0.03/0.25`，US x Iran ceasefire `feesEnabled=false`。
- 结论：BOND 的 maker 成交 `makerFeeRate=0` 正确；主动 taker 卖出/对冲必须另计 taker fee；100bps 仅压力测试；rebate 未计入回测，但也不能当成确定收益。
- 范围区分：以上为预测市场 CLOB；Polymarket Perps 另按 30 天交易量分层，`$0` 档 maker 0.0125%、taker 0.04%，不适用于本项目。
- 已更新 `docs/zh/leader-bond-strategy-replication-2026-10-20.md` 第 4.1 节。

### 22:20 BOND 全价格带与统计显著性复核

- 目标：不再只看 0.95–0.97/0.97–0.99 的样本内正收益，补看 0.90–0.95、全 eligible 成交和置信区间。
- 队列约束模型（Gradle BOND，off0/q1）：
  - 0.90–0.95：193 eligible / 114 filled，ROI **-1.23%**，10 次结算亏损；
  - 0.95–0.97：160 / 88，ROI +4.10%，0 次结算亏损；
  - 0.97–0.99：260 / 170，ROI +1.81%，0 次结算亏损；
  - 0.99–0.999：329 / 233，ROI +0.40%，0 次结算亏损。
- 新增 `scripts/analyze-leader-bond-style.cjs`：按“每个 eligible 都在参考价成交”的上限假设重算，7d hold、maker fee 0：
  - 0.90–0.95：n=193，10.36% 亏损率，平均 ROI -3.39%，盈亏平衡 7.22%；
  - 0.95–0.97：n=159，1.89% 亏损率，平均 ROI +2.17%，盈亏平衡 3.98%，95% CI 0.64%–5.40%；
  - 0.97–0.99：n=261，0.38% 亏损率，平均 ROI +1.54%，盈亏平衡 1.90%，95% CI 0.07%–2.14%；
  - 0.99–1.0：n=349，0% 亏损，平均 ROI +0.51%，95% CI 0%–1.09%。
- 结论：0.90–0.95 明确负期望；0.95–0.99 只有边界性/未证明的正期望，4.10% 不能当成稳定收益；0.5–0.8 的账户收益无法由持有模型复刻。
- 证据：`backend/build/research/leader-bond-style-backtest-20261020-repro.json`、`event-maker-bond-20261020-summary.csv`。
- 已更新报告 `leader-bond-strategy-replication-2026-10-20.md` 第 4.2/5 节和交付计划第 18 节。
- BOND Shadow PID 24440 继续运行；当前 5 个市场、4 FLAT、1 BUY_PENDING、0 fill。

### 23:05 BOND 7 天续跑已武装

- 新增 `scripts/continue-bond-validation.ps1`：等待当前 2h BOND Shadow（wrapper 24440 / Java 7304）退出，先跑全量 Gradle 测试，通过后启动 7 天 BOND Shadow。
- 后台 continuation wrapper PID `22292`；日志 `backend/build/research/bond-continuation-20261020.log`，测试日志 `bond-continuation-tests-20261020.log`。
- 7 天 Shadow 输出目录：`backend/build/research/event-maker-bond-shadow-7d-20261020`；参数 0.95–0.99、exitSpread=100c、maxCycles=1、order 100 USDC、最多 20 市场。
- 目的：在 2h 0 fill 的基础上，积累真实盘口挂单、成交、结算和 MAE；不降低 post-only/队列门槛。

### 23:20 与目标账户真实成交对账

- 目标账户已平仓 ROI（`leader-strategy-decomposition-20261020.json`）：0.5–0.8 +1.90%、0.8–0.9 +0.18%、0.9–0.95 +0.72%、0.95–0.97 +0.225%、0.97–0.99 +0.815%、0.99–1 +0.255%。
- 目标账户 matched-flow ROI（`leader-trade-flow-20261020.json`）：0.95–0.97 +1.24%、0.97–0.99 +0.001%、整体 **-0.2055%**。
- 对比我们的 BOND：0.95–0.97 队列 +4.10% 是目标已平仓 ROI 的约 18 倍；全 eligible +2.17% 仍约 10 倍。
- 结论：当前 BOND 是理想化假设模型，不是已验证的目标策略复刻；不能把 4.10% 当作实盘收益。目标账户的利润更可能来自持仓到结算、库存管理、奖励/返佣和选品，而不是简单 round-trip。
- 7 天 Shadow 仍继续，但验收改为“真实成交/库存/结算账本 + 接近 0.2%–0.8% 单次 ROI”；若出现单笔远高于 1%，先怀疑成交假设。
- 已更新报告 4.3 节、交付计划 18.1 节。

### 23:25 目标账户收益结构再判断

- 逐笔持有期：中位数 3.4 天、p90 32.1 天；36% 股数 1 天内、35% 1–7 天、29% >7 天。
- matched round-trip ROI -0.2055%；最大盈利多为 geopolitics 重定价（0.23→0.976、0.057→0.999、0.70→0.83），最大亏损集中在 0.8–0.99。
- 结论：目标账户更像事件驱动 + maker 执行 + 大库存周转，不是纯被动债券做市；当前 BOND 只是另一个理想化假设。
- 报告新增 4.4 节；交付计划新增 18.2 节。下一步若继续复刻，必须做逐笔库存账本和事件选择研究。

### 23:35 BOND Shadow 运行手册

- 新增 `docs/zh/event-maker-bond-runbook-2026-10-20.md`：配置、启动/停止、产物、审计清单、已知限制和 Live 门槛。
- 明确写入：当前 BOND 是理想化假设，不是已验证复刻；目标账户 0.95–0.97 已平仓 ROI 约 +0.225%，队列回测 +4.10% 明显高估。
- 手册要求每次运行结束必须核对 fill、MAE、结算亏损、重复成交、未知状态和 maker rebate/liquidity reward。

### 23:40 BOND Shadow 离线审计脚本

- 新增 `scripts/audit-bond-shadow.cjs`，读取 `state.json`/`summary.json`，汇总 markets、phases、activeOrders、inventory、cycles、deployed、realizedPnl/ROI、平均持有时间和 worst/best cycle。
- 自动标记：无 fill/cycle、重复成交、lastErrors、ROI>1%、有库存但无活跃订单。
- 已对当前 2h Shadow 运行：5 市场、4 FLAT、1 BUY_PENDING、0 cycles，flag=`NO_FILLS_OR_CYCLES`。
- 运行方式：`npm run audit:bond-shadow -- --dir <output> --output <output>\audit.json`。

### 23:34 修正 7 天续跑的 flow guard 口径

- 发现：BOND 回测默认 `flowGuardWindowSeconds=0`，但 `EventMakerShadowCli` 默认 3600；当前 2h Shadow 实际带 flow guard，和回测不一致。
- 修改 `scripts/start-bond-shadow.ps1`：新增 `FlowGuardWindowSeconds` 参数并传入 `shadowFlowGuardWindowSeconds`。
- 修改 `scripts/continue-bond-validation.ps1`：7 天臂默认 `FlowGuardWindowSeconds=0`，与 BOND 回测口径一致。
- 原 continuation PID 22292 已停止，新 PID 9368 已武装；日志记录 `flowGuard=0`。
- 当前形成 A/B：2h 臂 guard=3600（更保守），7d 臂 guard=0（与回测一致）；不降低其他门槛。

### 00:01 2h BOND Shadow 结束与 7d 启动

- 2h Shadow（PID 24440）正常结束，`summary.json`：
  - duration 7200s，markets=5，trades=1023，bookUpdates=50982；
  - quotesPlaced=5，cancels=5，fills=0，completedCycles=0；
  - realized/unrealized/total PnL=0，openInventory=0，lastErrors=[]；
  - duplicateTradeEvents=8762（WS + Data API 双路重复投递；engine 按 key 去重，未产生重复 fill）。
- 2h 离线审计：`audit.json` flag=`NO_FILLS_OR_CYCLES`，duplicateFillKeys=0。
- 全量 Gradle 测试：`BUILD SUCCESSFUL in 21s`。
- 7d Shadow 已启动：PID 10868（wrapper），8 个市场、16 个 token，输出 `backend/build/research/event-maker-bond-shadow-7d-20261020`；flowGuard=0，与 BOND 回测口径一致。
- 续跑脚本误报 `long shadow start failed`，原因是 PowerShell 脚本后的 `$LASTEXITCODE` 不可靠；已改为 `$?`，不影响实际已启动的 7d 进程。

### 00:10 逐笔库存账本复核

- 新增 `scripts/snapshot-leader-ledger.mjs`：抓取 trades、positions、closed-positions、activity，输出 `backend/build/research/leader-ledger-raw-20261021.json`。
- 新增 `scripts/analyze-leader-ledger.mjs`：按 asset FIFO，分离 matched round-trip、settlement、open mark-to-market，输出 `leader-ledger-analysis-20261021.json`。
- 快照：5,155 条 TRADE activity（2026-03-01 至 2026-10-16）、390 closed positions、5 open positions。
- 结果：matched PnL +14,342、ROI +0.748%；settlement PnL +3,637；all closed ROI +0.802%；全周期 closed API ROI +0.532%。
- 价格带 matched ROI：0.9–0.95 +1.39%、0.95–0.97 +1.26%、0.97–0.99 +0.85%、0.99–1 +0.31%、0.5–0.8 +0.94%。
- 结论修正：上一版只用 1,034 条 trades 得到 matched ROI -0.2055%，样本不完整；全量 activity 账本显示 matched flow 为正，但 BOND 回测 +4.10% 仍约为目标 0.95–0.97 matched ROI 的 3.3 倍。
- 目标账户更像高周转、多价格带的事件交易/做市混合策略；单靠 0.95–0.99 持有无法完整复刻。

### 00:45 目标账户类别归因

- 按标题关键词分组：Geopolitics matched PnL +13,112 / ROI +0.825%（2,587 盈 / 608 亏）；Politics +2,481 / +1.33%（344 / 56）。
- Other +2,563 / +0.815%；Macro/Finance +148 / +0.19%；Crypto -186 / -0.35%；Sports -140 / -0.69%。
- 结论：利润几乎全部来自 Politics/Geopolitics；Crypto/Sports 在样本内为负。可学习的第一步是收缩市场选择到政治/地缘事件，而不是继续调 0.95–0.99 价格带。
- 已更新报告 4.5 节、交付计划 18.4 节。

### 01:00 BOND_SCALP profile 与主动退出回测

- 新增 `GridProfile.BOND_SCALP`（0.90–0.999、off 0/0.25c、exit +1/+2/+3c、q1/2/5、cycles 3/10、requote 300s）。
- 0.95–0.97 off0 +3c q2 c3：79/90 filled，ROI +3.20%，train +3.19% / test +3.20%，0 结算亏损，MAE -66.8%。
- 0.97–0.99 off0.25 +3c q2 c3：78/79 filled，ROI +2.20%，train +2.13% / test +2.28%，0 结算亏损，MAE -95.3%。
- 全 eligible +3c：0.95–0.97 n159 平均 +1.82% / 亏损率 1.26%；0.97–0.99 n261 平均 +1.44% / 亏损率 0.38%；0.90–0.95 仍为 -1.58%，排除。
- 证据：`event-maker-bond-scalp-20261021-*.csv`、`leader-bond-style-backtest-20261021-repro.json`。

### 01:05 停止 BOND hold 7d，启动 BOND_SCALP 7d

- 原 BOND hold Shadow（exitSpread=100c）与目标账户行为不符，手动停止；停止时 0 fill / 0 cycle，标记 `NO_FILLS_OR_CYCLES`。
- 2026-10-21 00:57 启动首轮 BOND_SCALP 7d Shadow：0.95–0.99、+3c、maxCycles=3、fillVolumeFactor=2、flowGuard=0、最多 20 市场。
- 01:07 审计首轮：8 市场、1 active order、0 fill / 0 cycle；发现 requote=120s、maxOrderAge=3600s 与回测的 300s / 1800s 不一致，停止首轮。
- 01:08 用修正参数重启（`...-20261021-v2`）：requote=300s、maxOrderAge=1800s，其余相同。
- 启动选中 8 市场 / 16 token，WS_OPEN、WS_SUBSCRIBED，已挂 maker BUY。
- 进程：wrapper PID 2476、Gradle java 11084、app java 10360；输出目录 `event-maker-bond-scalp-shadow-7d-20261021-v2`。
- 启动自动审计 watcher：`scripts/watch-bond-shadow.ps1`，PID 19216；等 wrapper/app 退出后自动写 `audit-final.json`。

### 01:10 官方费率二次复核：maker 确实 0 费

- 官方新地址 `https://docs.polymarket.com/trading/fees`（旧地址 308 跳转），文档 dateModified 2026-10-20T03:05:04Z。
- 原文：**Makers are never charged fees. Only takers pay fees.** 全类别 Maker Fee Rate = 0。
- Gamma 的 `makerBaseFee=1000` 是旧字段，不是实际 maker 费；实际看 `feeSchedule.takerOnly=true`。
- Geopolitics `feesEnabled=false`，taker 也免费；rebate 是每日 pUSD 返佣池，不是每笔返现。
- 已更新报告 4.1.1 节。
### 01:25 BOND_SCALP 统计显著性与实盘执行补测

- 新增 `scripts/analyze-bond-scalp-risk.cjs`，输出 `bond-scalp-risk-20261021.json`。
- 队列模型 90 cycles：0 结算亏损，ROI +3.20%，但亏损率 95% CI 上限 4.09% > 盈亏平衡 3.10% → 未证明。
- 全 eligible +3c：0.95–0.97 n159 亏损率 1.26% CI [0.35%, 4.47%] vs 3.03%；0.97–0.99 n261 0.38% CI [0.07%, 2.14%] vs 1.80%；两者均未证明。
- 60s markout 中位≈0、50% 为负；成交时间中位 148s / p90 1284s；MAE 中位 -2.0 / p10 -35.8 USDC。
- `EventMakerLiveEngineTest` 新增：maxCycles 3 后停止开仓；SELL 退出不被 adverse flow 拦截。
- 全量测试 BUILD SUCCESSFUL。
- 结论：回测正收益不等于统计显著，继续 Shadow，不上实盘。
### 01:30 退出价差复核：+1c 主复刻，+3c 上行变体

- 目标账户价差中位 +1c、p90 +3c；此前把 +3c 当主配置，实际是 p90，不是中位。
- 队列模型：+1c 0.95–0.97 +1.08% / 0.97–0.99 +1.05%，与 leader matched ROI +1.26% / +0.85% 几乎一致；+3c 为 +3.20% / +2.20%，约 2.5–3 倍，偏乐观。
- +1c 的卖出/结算结构更接近高周转：0.95–0.97 132/1，0.97–0.99 100/1；+3c 结算明显增多。
- 01:29 启动 +1c 7 天 Shadow（10 市场 / 20 token，requote 300s，maxOrderAge 1800s，output `...-1c-shadow-7d-20261021`），wrapper PID 24864、app PID 5196；watcher PID 23192。
- 与 01:08 的 +3c 臂并行做 A/B，比较真实 fill、退出价、结算占比和 ROI。
### 01:32 两个 Shadow 臂的持续监控

- 新增 `scripts/monitor-bond-shadows.ps1`，每 30 分钟审计两个 Shadow 目录，写 `bond-shadow-audit-series.jsonl`。
- monitor PID 17676；首条记录：+3c 8 市场 / 1 active / 0 fill；+1c 10 市场 / 1 active / 0 fill。
- 目的：提前发现 0 fill、重复 fill key、异常 ROI，不必等 7 天。
### 01:33 完整集 24h 监控自动终审

- 新增 `scripts/watch-complete-set-monitors.ps1`，PID 9708（01:40 因脚本升级重启）。
- 等 PID 11804 / 23072 退出后自动审计两个 `monitor-summary.json` 与 `opportunities.jsonl`，写 `complete-set-monitor-final-audit.json`。
- 判定：有 actionable 才是 ACTIONABLE_FOUND；否则 NO_ACTIONABLE_OPPORTUNITY；同时保留 errorRows 与 lastPollErrors 供区分瞬态错误。
### 01:40 +1c focus 回测与目标账户行为对账

- 跑 +1c focus 回测：160 eligible / 79 filled / 133 cycles，132 SELL + 1 SETTLE，0 结算亏损，ROI +1.08%，train +1.05% / test +1.10%。
- +1c 持有期中位 0.69 天 / p90 2.49 天；+3c 为 3.65 / 6.35 天；目标账户为 2.02 / 10.96 天。
- +1c 负 markout 占比 52.6%；+3c 50.0%；两者都有逆选择。
- +1c 亏损率 CI 上限 2.81% > 盈亏平衡 1.07% → 仍未证明；+3c 4.09% > 3.10% → 仍未证明。
- 结论：+1c 复刻 ROI 但周转过快且无 18.4% 止损；+3c 复刻持有期但 ROI 偏高；目标账户是动态退出 + 主动止损，需 Shadow A/B。
### 01:48 记录回测与 Shadow 入场口径差异

- 回测入场用最后成交价向下取整；Shadow 用 bestBid + 1 tick。
- 实例：bid 0.976 / ask 0.980，Shadow BUY @0.977；回测可能假设 0.977–0.980。
- 结论：回测 fill rate 不能直接外推，Shadow 实测为准；已写入报告 4.6.9。
### 01:52 完整集监控 actionable 假阳性

- crypto/finance 监控 16 条机会中 5 条 `best.actionable=true`，但全部 `askRiskWorstPnlUsd < 0`。
- Bitcoin vs Gold vs S&P：worst fills 228/228/0，worst PnL -59.52；OpenAI IPO：worst PnL -13.96。
- 原因：negRisk 篮子只成交 2/3 腿，第 3 腿 0 股，属于裸风险，不是完整集套利。
- 更新 `watch-complete-set-monitors.ps1`：区分 riskFreeActionable / nakedRiskActionable；watcher 重启为 PID 9708。
### 02:00 修复 Shadow 只开 1 个市场的风险上限 bug

- 发现旧臂虽选中 8–10 市场，但 `maxTotalInventoryNotional` 硬编码 100、`maxDailyLoss` 硬编码 5，orderNotional=100 → 永远只有 1 个 active BUY。
- 这违背多市场分散前提，也是长时间 0 fill 的直接原因之一。
- 修复：ShadowCli/Gradle/start 脚本暴露两个参数；新增 `EventMakerShadowCliOptionsTest`；全量测试通过。
- 旧臂审计 `NO_FILLS_OR_CYCLES` 并停止。
- 重启 cap=1000：+3c 与 +1c 均 10 市场 / 10 active BUY；watcher 21160 / 26520，monitor 26068。
### 02:15 首次 Shadow 成交

- 修复 cap 后，+3c 与 +1c 臂各在 `US x Iran ceasefire continues through October 22?` 成交：BUY @0.953，104.93 股。
- 成交后 LONG；best ask 未到 entry+1c/+3c，卖出限价暂未挂出。
- 说明多市场挂单（10/10 active）和成交模拟已跑通；单样本不足以下结论。
- 02:17 快照：bid 0.939 / ask 0.944，entry 0.953，unrealized PnL -1.47 USDC；+1c/+3c 退出均未触发，首笔成交已出现逆选择浮亏。
### 02:30 目标账户亏损退出分析

- worst round trips：-13% 到 -24%，持有 30 分钟到 8 天，不是机械 -1c/-2c 止损。
- 更像事件逻辑破坏后的主动离场；机械小止损会过早砍仓，可能破坏 edge。
- 固定 +1c/+3c 模型只能复刻正常盈利路径，无法复刻事件判断 + 大止损。
### 02:40 活动门槛实测：当前选不出市场

- 13 个合格市场；3/50/1 门槛 activityPassed=1 但 price_out_of_range → 0 selected；1/10/1 同样 0 selected。
- favorite token 30 分钟双边流量极薄，多数成交在便宜 No 侧。
- 首笔成交 markout：60s -1.05、300s -1.99 USDC，逆选择明显。
- 结论：ungated 两臂继续基线，等活动窗口再测 gated 版本。
### 02:45 监控增强：持续记录 markout

- `monitor-bond-shadows.ps1` 新增 markout60Count/mean、markout300Mean、markout60NegativeShare。
- 首条记录：两个臂 markout60=-1.05、markout300=-1.99、负占比 100%。
- monitor 重启 PID 21168；后续每 30 分钟记录一次。
### 02:55 宽价格带回测：低价格带机械不可行

- 新增 SCALP_WIDE profile（0.5–0.999，108 配置）+ 单测；4,189 市场回测完成。
- 最佳 ROI：0.5–0.8 -9.67%（75 结算亏损）、0.8–0.9 -5.77%（24）、0.9–0.95 -0.19%、0.95–0.97 +3.22%、0.97–0.99 +2.23%、0.99–0.999 +0.70%。
- 结论：低价格带的正收益是 leader 的事件判断，不是机械 scalp；继续聚焦 0.95–0.99。
### 03:00 入场质量对账与被动挂单臂

- 新增 leader entryTiming：买入前 1h 各 bucket 的 60s markout 全为正（+0.13% ~ +0.72%）。
- 我们首笔 Shadow 60s markout 约 -1.0%，说明 improveTicks=1 可能过于激进。
- start-bond-shadow.ps1 新增 ImproveTicks；启动被动臂 improveTicks=0、entryOffset=0.25c，8 市场 / 8 active。
- watcher 25484，monitor 20600；三臂 A/B 比较 fill rate 与 markout。
### 03:10 退出结构：+1c/+3c 双目标组合

- 目标账户价差分布：中位 +1c、p90 +3c、18.4% 负；单一固定退出无法匹配。
- 50/50 组合：ROI ≈ 2.14%，持有中位 ≈ 2.17 天，接近 leader 的 2.02 天。
- 组合比单臂更接近他的中位/p90，但仍缺 18.4% 负价差退出。
- 实操：资金分两半跑两个目标，或同市场 ladder（先卖一半 +1c，剩余 +3c）。
### 03:20 挂单位置 A/B：aggressive / join-bid / passive

- aggressive（bid+1tick）：10 市场 / 9 active / 已成交 / 60s markout -1.0%。
- join-bid（bid）：5 市场 / 5 active / 0 fill；3 个市场 entry_queue_too_deep。
- passive（bid-0.25c）：8 市场 / 8 active / 0 fill。
- 结论：成交速度与入场质量 trade-off；等 join-bid/passive 成交数据。
- monitor 重启 PID 10748，四臂；watcher 18188（join-bid）。
### 03:30 四臂 Shadow 与完整集监控状态

- 四臂：+3c/+1c aggressive 各 1 fill（60s markout -1.05）；join-bid 5/5 active 0 fill；passive 8/8 active 0 fill。
- 首笔市场最新 bid 0.945 / ask 0.946，entry 0.953，浮亏 -0.84，未触发退出。
- crypto/finance 24h monitor：27 opportunities / 11 actionable，全部 worstPnl < 0（部分成交裸风险）；politics 0。
- 结论：无风险自由完整集；继续等 join-bid/passive 成交数据。
### 03:35 新增四臂状态报告脚本

- 新增 `scripts/report-bond-shadow-status.cjs`，一键汇总四臂 markets/active/fills/cycles/inventory/realizedPnl/markout60/markout300/neg60。
- 当前：aggressive 两臂各 1 fill（markout -1.05 / -1.99，neg60 100%）；join-bid 5/5 active 0 fill；passive 8/8 active 0 fill。
- 输出 `bond-shadow-status-20261021.md`，后续可直接重跑。
### 03:45 被动挂单并没有改善 markout

- 对比 off0 vs off0.25（+1c q2 c3）：off0 markout -0.477、133 cycles；off0.25 markout -0.658、94 cycles、MAE 更深。
- 更被动买价只在下跌时成交，天然逆向选择；被动化不是解法。
- leader 的正 markout 更可能来自事件判断/信息优势或主动吃单。
- join-bid/passive 两臂继续跑，但预期不宜过高。
### 03:55 按买入规模看 markout

- 新增 analyze-leader-trade-size.cjs；2,923 笔买入。
- markout：<100 +0.219%、100–500 +0.227%、500–2000 +0.481%、>=2000 +0.284%，全部为正。
- 我们约 100 股，落在 100–500 桶；他 +0.227%，我们首笔 -1.0%。
- 结论：机械 maker 无法复刻入场质量，alpha 更可能来自事件判断/信息优势。
### 04:05 形成最终评估文档

- 新增 `docs/zh/leader-strategy-final-assessment-2026-10-21.md`。
- 结论：机械复刻只能覆盖 0.95–0.999 窄切片，统计未证明；目标账户正 markout 来自事件判断/信息优势；低价格带机械为负。
- 建议：不建议大资金；最多小资金跑 +1c 主复刻，严格日亏损；继续 Shadow 2–4 周。
### 04:10 markout 按市场特征分组：所有子集都为负

- 新增 `scripts/analyze-shadow-markout-by-market.cjs`，对 +1c focus 的 133 cycles 按 tag/volume/price 分组。
- Geopolitics: n=101, markout -0.446, neg 50.5%；Politics: n=32, -0.575, neg 59.4%。
- >=500k: n=116, -0.488；100k-500k: n=17, -0.406；<0.95: -1.128；0.95-0.97: -0.419。
- 结论：没有任何子集能选出正 markout；机械 maker 的负 markout 是系统性的，不是选品问题。
### 04:25 扩大市场池：minVolume 1000 / 28 市场

- 原 13 个合格市场，成交机会少；新增 wide-universe 臂 minVolume=1000，发现 38 个、选中 28 个、26 active。
- 参数：MaxMarkets=30、cap=3000、+1c、bid+1tick。
- 目的：验证成交机会不足是否是瓶颈，并比较低成交量市场 markout。
- watcher 26652，monitor 26684（五臂）。

### 04:35 首笔成交的逆向选择成本

- 首笔 BUY @0.953；60s 后 mid≈0.944，逆向选择成本约 0.9c。
- 按 0.944 公允价：EV ≈ 0.944×0.047 - 0.056×0.953 ≈ -0.9%/股。
- +1c 目标 0.963 需要从 0.944 回升 1.9c；没有信息优势时很难。
- 结论：首笔不是“暂时浮亏”，而是按当时公允价已负 EV；进一步支持机械 maker 的逆选择问题。
### 04:40 目标账户当前持仓快照

- 5 个 open positions 全部为 No/favorite，价格带 0.84–0.95，仓位 $2,500–$10,000。
- Bab el-Mandeb Oct 31 No 0.9499→0.976（+2.74%）；Dec 31 No 0.85→0.84（-1.18%）仍在持有。
- 结论：真实操作是跨 band、大仓位、动态持有；我们的 $100 模型只覆盖很小一部分。

### 2026-10-21 官方费率实时复核：maker 0 费

- 实时抓取官方 Fees 页（`dateModified=2026-10-20T03:05:04.124Z`）：“Makers are never charged fees. Only takers pay fees.”
- 官方费率表所有类别 Maker Fee Rate = 0；Geopolitics 的 taker 也 = 0。
- Gamma 按 volume 排序前 100 个开放市场抽样：98 个带 `feeSchedule`，98/98 均为 `takerOnly=true`（另 2 个无 `feeSchedule`，即免费）；rate 仅 0（20 个）/ 0.05（78 个），没有 maker 费字段。
- `makerBaseFee=1000` / `takerBaseFee=1000` 是历史遗留字段，不能用于成本计算。
- 含义：maker 0 费确认；BOND 回测 `makerFeeRate=0` 正确；BOND_SCALP 主动卖出按 taker 计费；负 markout / 逆向选择才是主要风险，手续费不是瓶颈。
### 04:10 机械止损研究：价格/时间/流止损全部破坏 edge

- 新增 `scripts/analyze-bond-stop-loss.cjs`，测试 0.5c–25c 价格止损、1–7 天时间止损。
- all-eligible 0.95–0.97 +3c：无止损 +1.82%；5c -0.28%、10c -1.17%、15c -0.71%；1c -0.70%、2c -0.47%、3c -0.23%。
- queue 模型 0.95–0.97 Geopolitics +3c：无止损 +3.17%；1 天 +0.49%、2 天 +0.86%、3 天 +1.77%。
- 结论：机械止损会把最终会回升的仓位提前砍掉；目标账户的选择性止损不是固定规则。

### 04:15 按 tag 分解：0.95–0.97 只有 Geopolitics 为正

- 新增 `scripts/analyze-bond-by-tag.cjs` 和 `scripts/analyze-bond-queue-by-tag.cjs`。
- queue 模型 0.95–0.97：Geopolitics +1c +1.08% / +3c +3.17%（60/106 成交，69 cycles，0 结算亏损）；Politics +1c -1.85% / +3c -1.14%。
- 0.97–0.99：Geopolitics +1c +1.06% / +3c +1.82%；Politics +1c +1.04% / +3c +1.81%。
- 结论：0.95–0.97 只做 Geopolitics；0.97–0.99 两个 tag 都可做。

### 04:20 目标账户真实收益结构：74.5% 胜率、平均亏损仅 -$7.99

- 重算 `leader-ledger-raw-20261021.json`：4,747 matched round trips，matched PnL +$14,342，ROI +0.748%。
- Geopolitics：3,472 trips，胜率 74.5%，平均盈利 +$6.95，平均亏损 -$7.99，盈亏平衡亏损率 46.5%，实际亏损率 25.5%，ROI +0.825%。
- Politics：470 trips，胜率 73.2%，ROI +1.33%。
- 183 个交易日：151 正 / 28 负；最好 +$827.80，最差 -$640.27；已实现最大回撤约 $1,022.73。
- 结论：他是高频 favorite scalping/做市，不是 hold-to-settlement；收益稳定但单笔很小。

### 04:25 新一轮 Shadow：5 个 LIVE 臂 + tag 过滤

- 新增 `--tags` 过滤（`LivePaperDataClient` / `EventMakerShadowCli` / `build.gradle.kts` / `start-bond-shadow.ps1`），并有单测；全量 `gradlew test` BUILD SUCCESSFUL。
- 启动 0.97–0.99 的 +1c / +3c 两臂；启动 Geopolitics-only 0.95–0.97 +3c 臂。
- 当前 LIVE：3c-cap1000、1c-cap1000、1c-97099、3c-97099、3c-geo-9597。
- 退役：joinbid/passive（DEAD，0 fill）、wide-universe（0 fill，扩池无效）。
- `report-bond-shadow-status.cjs` 增加 PID 存活检查，输出 LIVE/STALE/DEAD；`monitor-bond-shadows.ps1` 默认监控 5 个 LIVE 臂。
- 详细报告：`docs/zh/bond-strategy-robustness-2026-10-21.md`；交付计划新增第 20 节。
### 04:30 新臂首批成交：Geopolitics-only markout 明显更好

- `3c-cap1000` / `1c-cap1000`：各 2 fills（同一 0.953 市场 + 新增 0.97 Geopolitics 市场），inventory 208.02；markout60 从 -1.05 改善到 -0.58，markout300 -1.20。
- `3c-geo-9597`（Geopolitics only）：1 fill @ 0.97，103.09 股；markout60 -0.10、markout300 -0.41，明显好于全 tag 臂的首笔 -1.05/-1.99。
- `1c-97099` / `3c-97099`：仍 0 fill。
- 结论：首批数据支持“0.95–0.97 只做 Geopolitics”的假设，但样本只有 1 笔，不能下结论；继续跑。
### 04:35 目标账户持有时间：亏损单只比盈利单短一点

- matched round trips：盈利 3,511 笔，持有中位 1.88 天 / 均值 5.32 天；亏损 872 笔，中位 1.51 天 / 均值 3.06 天。
- 按持有时间分桶的 PnL：<1h +$504、1–6h +$1,224、6–24h +$2,711、1–3d +$3,787、3–7d +$3,882、>7d +$2,233；3–7d mean ROI +1.04%，>7d +1.45%。
- 结论：他是“让盈利单跑几天、只砍真正坏掉的亏损单”，不是固定时间止损；这解释了为什么机械时间止损会砍掉赢家。

### 04:40 24h 动量过滤暂无法测试

- 目标账户 24h 跌 >2c 的入场：676 笔，胜率 83.6%，mean ROI +1.72%，是最强子集。
- 但当前 7 天数据集只包含入场前约 0.7–0.9 小时的成交，没有 24h 盘前历史；24h 动量过滤结果为空。
- 代码已保留 `--entry-momentum-min/max-cents`，等有更长盘前数据集再验证。

### 04:45 Shadow 当前：5 fills，Geopolitics-only markout 最好

- `3c-cap1000` / `1c-cap1000`：各 2 fills，inventory 208.02；markout60 -0.58、markout300 -1.20。
- `3c-geo-9597`：1 fill @ 0.97，103.09 股；markout60 -0.10、markout300 -0.41。
- `1c-97099` / `3c-97099`：仍 0 fill。
- 结论：0.95–0.97 Geopolitics 的入场质量明显好于全 tag；但样本仍只有 1 笔，继续跑。
### 07:05 Shadow 中期：9 fills、0 cycles、未实现 -$3.93

- `3c/1c-cap1000`：各 3 fills，inventory 309.43，markout60 -0.45 / markout300 -1.20，未实现 -$1.45。
- `1c/3c-97099`：各 1 fill，inventory 101.41，markout60 -0.20，未实现 -$0.20。
- `3c-geo-9597`：1 fill，inventory 103.09，markout60 -0.10 / markout300 -0.41，未实现 -$0.62。
- 合计 9 fills、0 cycles、未实现 **-$3.93**（约 -0.44% 已投入名义本金）。
- 结论：所有臂的 markout 仍为负，尚无 +1c/+3c 退出；继续等 cycle，暂不能判断策略有效。
### 07:20 1h 动量过滤：Geopolitics 无显著改善

- 0.95–0.97 Geopolitics +3c：全部 +3.17%；1h 0..+1c +3.16%；1h -1..0 +3.17%；1h >+1c +3.13%；1h -2..-1c +3.39%（仅 2 笔）。
- 0.97–0.99 Geopolitics +3c：全部 +1.82%；1h 0..+1c +1.81%；1h -1..0 +1.83%。
- 0.95–0.97 Politics：1h -1..0 为 +3.11%（仅 6 笔），但 1h 0..+1c 为 -2.90%，不稳定。
- 结论：1h 动量过滤不能稳定改善 Geopolitics；目标账户最强的 24h 跌 >2c 子集无法用当前数据集验证（盘前历史太短）。
### 07:15 目标账户 inventory：多档建仓、多档卖出

- 新增 `scripts/analyze-leader-inventory.mjs`，重算 126 个 asset / 5,155 笔 TRADE。
- 82.5% 的 asset 有买有卖；每 asset 买入次数中位 7.5、卖出中位 3；最大持仓中位 ~7,000 股、平均 ~3,323 股；最终持仓中位 ~0.009 股（基本平掉）。
- 持有时间中位 70.5 小时（~2.9 天）。
- 结论：他不是一次性买入持有，而是多档库存周转/做市；单笔 BOND_SCALP 只覆盖很小一部分。

### 07:20 多档 ladder 回测：0.97–0.99 Geopolitics +3c 最优

- 新增 `scripts/analyze-bond-ladder.cjs`：参考价下方 0/0.5/1/2c 四档 maker BUY，每档 $100，成交后挂 +target maker SELL，卖出后回补。
- 0.97–0.99 Geopolitics +3c：263 lots，263 buys / 235 sells，fill rate 76.2%，mean ROI **+2.34%**，CI [2.25%, 2.42%]，0 结算亏损，平均最大库存 ~$213。
- 0.97–0.99 Geopolitics +2c +1.89%；+1c +1.03%。
- 0.95–0.97 Geopolitics +3c ladder 仅 -0.07%（单档 +3.17% 更好）。
- 0.97–0.99 Politics +3c -0.04%；0.99–1.0 只有 +0.33%。
- 结论：0.97–0.99 Geopolitics +3c ladder 是目前最好的机械候选；0.95–0.97 继续用单档 touch。

### 07:25 资源集中到 5 个 LIVE 臂

- 退役全 tag 的 `3c/1c-cap1000`、`1c/3c-97099`（共 9 fills，markout 全负，未实现 -$3.93）；输出目录保留。
- 启动 4 个 Geopolitics-only 0.97–0.99 +3c offset 臂：off00 / off05 / off10 / off20，合起来近似 4 档 ladder。
- 保留 `3c-geo-9597`（0.95–0.97 Geopolitics 单档 +3c）。
- 当前 LIVE：`3c-geo-9597`、`3c-geo-97099-off00/05/10/20`。
- 注意：4 个 offset 臂是独立进程，不是同一引擎的原子 ladder 账本；真实实盘前需要把 ladder 做进同一个引擎。
### 07:30 ladder 臂改为 maxCycles=10

- 4 个 Geopolitics 0.97–0.99 offset 臂重启，`maxCyclesPerMarket=10`，以接近 ladder 的持续回补；每臂最多 $400 库存。
- 当前实盘 Geopolitics 0.97–0.99 池只有 8 个 qualified，单臂选中 2–3 个市场；回测的 160 个 eligible 是 7 天累计，不是同时可交易。
- 结论：容量受当前事件供给限制；live 样本会比回测小很多，不能用回测 160 市场估算收益。

### 07:50 taker-entry 代码与 Shadow 链路完成

- 修复 Shadow CLI 的 `takerEntry` 配置漏传；此前 `--taker-entry` 已解析但未写入 `EventMakerConfig`。
- `EventMakerMarketSelector` 支持 taker 模式：用 best ask 做价格带过滤、允许 1 tick spread、entry queue=0。
- `EventMakerShadowExecutionPort` 对 taker 单立即生成 `FILLED`，新增 `drainImmediateFills()`；CLI 在 engine tick 后记录 60s/300s/1800s markout。
- `EventMakerQuotePlanner.planSell` 增加回退：当 `exitImproveTicks` 把卖价压到 entry 以下时，改用 `entry + exitSpreadCents`。
- `start-bond-shadow.ps1` 修正 taker 参数传递为 `-PshadowTakerEntry=true`，并新增 `-ExitImproveTicks`。
- 新增 planner / selector / shadow port / engine 单测；全量 `gradlew test` BUILD SUCCESSFUL。

### 07:52 官方费率直连复核

- 2026-10-21 请求 `https://docs.polymarket.com/trading/fees`，HTTP 200。
- 原文：**Makers are never charged fees. Only takers pay fees.**
- 费率表所有类别 Maker Fee Rate = 0；Geopolitics Taker Fee Rate = 0。
- `makerBaseFee=1000` 仍是历史遗留字段，不参与成本计算。

### 07:53 首个 taker Shadow A/B 启动

- 臂：`event-maker-bond-scalp-3c-geo-97099-taker00c-shadow-7d-20261021`。
- 0.97–0.99 / Geopolitics / +3c / taker at ask / slippage 0 / exitImproveTicks 0 / maxCycles 10 / $100 per order / cap $400。
- 首轮：2 笔 BUY 立即成交（0.985×101.52、0.981×101.93），SELL 已挂 0.999；inventory 203.45，0 cycles。
- 60s markout -0.30、负占比 100%（2 笔）；说明立即成交、对账、退出挂单、markout 四条链路跑通，但样本远不足。
- monitor 重启并加入 taker 臂；status 报告默认 6 臂。

### 08:05 年度外样本复核：纠正“7 天样本”口径

- 核对数据集元数据：`event-maker-full-v2-7d-20261019-dataset.json.gz` 的 `fromDate=2024-01-01`、`toDate=2026-10-01`、`entryHorizonSeconds=604800`，共 4,189 个市场。**“7d”是每个市场入场前 7 天窗口，不是整个数据集只有 7 天。**
- `scripts/analyze-bond-ladder.cjs` 新增 `byYear` 分组。
- 0.97–0.99 Geopolitics +3c ladder：263 lots，mean ROI +2.34%，95% CI [2.25%, 2.42%]；2024 25 lots +1.77%、2025 89 lots +2.40%、2026 149 lots +2.40%，三年均为正、0 结算亏损。
- 队列敏感性：queueFactor=2/3/4 分别为 263/246/240 lots，mean ROI 均为 +2.34%。
- 尾部压力：95% Wilson 亏损率上界 1.44%，盈亏平衡亏损率约 2.29%；假设真实亏损率 1% 时期望 ROI +1.32%，2% 时 +0.29%，3% 时 -0.73%。
- 结论修正：0.97–0.99 Geopolitics 在 2024–2026 年度外样本中为正；目标账户同 band 实际 ROI 仅 +0.852%，因此 **+2c 是首选折中（+1.89%）**，+1c 保守（+1.03%），+3c（+2.34%）只作上限。0 次尾部亏损不能外推为 0%，真实 L2/延迟/部分成交仍未验证。
- 证据：`backend/build/research/bond-ladder-periods-20261021.json`、`bond-ladder-q3b-20261021.json`、`bond-ladder-q4b-20261021.json`。

### 08:15 Shadow 参数对齐首选候选

- 根据年度/尾部复核，把首选从 +3c 下调为 **0.97–0.99 Geopolitics +2c ladder**：+1c 保守、+2c 首选、+3c 上限。
- 停掉 `off10` 的 +3c 配置，重启为 **+2c**（entryOffset=1c，fillVolumeFactor=2，maxCycles=10，cap=$400）。
- 停掉 `off00` 的 factor=2，重启为 **fillVolumeFactor=1**，作为更宽松成交对照；其余 off05/off20 仍为 +3c、factor=2。
- taker00c 仍为 +3c taker at ask，用于对比“主动吃单 vs maker 挂单”的 markout。
- 当前 6 臂均 LIVE；继续观察 fill、60s/300s markout、cycle 和结算。

### 08:20 增加 +2c taker 对照臂

- 启动 `event-maker-bond-scalp-2c-geo-97099-taker-shadow-7d-20261021`：0.97–0.99 / Geopolitics / +2c / taker at ask / slippage 0 / exitImproveTicks 0 / maxCycles 10 / $100 / cap $400。
- 首轮 2 笔立即成交（0.985、0.981），SELL 0.999；inventory 203.45；60s markout -0.30（2 笔，负占比 100%）。
- 当前 7 个 LIVE 臂：4 个 maker offset、1 个 +2c maker（off10）、2 个 taker（+3c taker00c、+2c taker）。
- 注意：0.97–0.99 的 +2c/+3c 卖价在 entry 接近 0.99 时都会被 0.999 上限截断，所以 +2c 与 +3c 的差异只在较低 entry 上体现。

### 08:25 当前盘口与目标账户实际入场对账

- 目标账户在 `Bab el-Mandeb Strait effectively closed by October 31?` 的实际交易：2026-10-09 买入 No @0.93、2026-10-16 买入 @0.95、2026-10-15 卖出 @0.96；2026-10-21 当前 bid/ask 约 0.979/0.981，确实高于他这次低吸交易的卖价。
- 但这是**低价格带**的交易，不能直接外推到 0.97–0.99。按 leader 的 0.97–0.99 closed lots 分桶：24h 0..+1c 有 288 笔、胜率 99.7%、mean ROI +0.86%；-1..0c 62 笔、83.9%、+0.78%；>+1c 137 笔、37.2%、+0.23%；**没有 24h<-2c 的入场**。
- 因此当前 +0.2c 的 24h 动量落在 0.97–0.99 的最佳桶（0..+1c），对 **0.97–0.99 策略不是追高**；真正要过滤的是 >+1c 的弱桶。
- 24h 下跌 >2c 的低吸子集主要属于 0.95–0.97：29 笔、胜率 100%、mean ROI +1.75%。这个过滤应放到 0.95–0.97 单独测试，不应套在 0.97–0.99 上。

### 08:45 实现 24h 动量入场过滤并启动等待臂

- Shadow CLI 新增 `--entry-momentum-min-cents` / `--entry-momentum-max-cents` / `--entry-momentum-lookback-seconds`；Gradle 与 `start-bond-shadow.ps1` 同步支持，并新增 `-WaitForMarketMinutes`。
- 过滤逻辑：用当前 favorite best bid 与 lookback 窗口内第一笔 favorite 成交比较，输出 `momentum-filter.json`；负值表示下跌。
- 冒烟结果（2026-10-21 08:45，7 个 Geopolitics 市场，24h lookback）：
  - Israel x Iran ceasefire：+2.1c（上涨，不是低吸）
  - Kharg Island：-0.1c
  - Strait of Hormuz：+0.2c
  - US-Iran Final Nuclear Deal：+1.0c
  - Farsi/Hengam/Hormuz/Kharg：0.0c
  - Bab el-Mandeb：+0.2c
  - Russia x Ukraine ceasefire：-1.3c
  - 没有市场满足“24h 下跌 >2c”，所以 0 selected。对 0.97–0.99 来说这是**预期结果**，因为 leader 在该 band 本来就没有 24h<-2c 入场。
- 因此把 24h 下跌过滤移到 **0.95–0.97** 单独测试；0.97–0.99 主策略改用“过滤 24h >+1c 的弱桶”，而不是要求下跌。
- 原 `dip24h` 等待臂（0.97–0.99）会停止，改启动 0.95–0.97 dip 臂；0.97–0.99 主臂保留 +2c taker/maker。
- 全量 `gradlew test`：BUILD SUCCESSFUL。

### 09:00 动量分桶纠偏与两个新臂

- 用 `entryTimingByBand` 复核后确认：24h 下跌 >2c 的低吸子集不属于 0.97–0.99，而属于 0.95–0.97。
  - 0.97–0.99：24h 0..+1c 288 笔 / 99.7% / +0.86%；>+1c 137 笔 / 37.2% / +0.23%；<-2c 0 笔。
  - 0.95–0.97：24h <-2c 29 笔 / 100% / +1.75%；-1..0c 77 笔 / 100% / +1.50%。
- 因此停止 0.97–0.99 的 dip24h 臂，改启动 **0.95–0.97 dip24h** 等待臂；当前 5 个市场 0 selected，最接近的是 Russia x Ukraine -1.8c。
- 0.97–0.99 新增 `mom1` 臂：过滤 24h >+1c 的弱桶，只允许 ≤+1c；当前选中 Bab el-Mandeb（+0.2c），1 笔 taker 成交 @0.981，SELL 0.999，60s markout -0.20。
- 对比：未过滤 taker2c 的 60s markout -0.30；`mom1` 的 -0.20 略好，但只有 1 笔，不能下结论。
- 当前有 8 个带 state 的 LIVE 臂 + 1 个 0.95–0.97 dip 等待臂。
- 全量 `gradlew test`：BUILD SUCCESSFUL。

### 09:05 过滤后的 maker 臂

- `off10` 重启为：0.97–0.99 / Geopolitics / +2c maker / entryOffset=1c / **24h 动量 ≤+1c**。
- 当前选中 1 个市场（Bab el-Mandeb，+0.2c），已挂 BUY @0.97；这样可与未过滤的 off00/off05/off20 以及 taker2c/mom1 对比。
- 9597 dip24h 等待臂仍在等待 ≤-2c；当前 5 个 0.95–0.97 市场最接近 -1.8c，0 selected。
- 当前 8 个带 state 的 LIVE 臂 + 1 个等待臂。
### 09:25 官方费率与 Shadow 结算闭环

- 再次直连 `https://docs.polymarket.com/trading/fees.md` 复核：Maker Fee Rate 全类别为 0，官方原文为 “Makers are never charged fees. Only takers pay fees.”。
- Geopolitics 同时是 Taker Fee Rate = 0、Maker Rebate = 0；Politics 是 Maker 0 / Taker 0.04 / Maker Rebate 25%。因此 Geopolitics 的 maker/taker A/B 不是手续费差异，而是成交质量、逆向选择与 markout 差异。
- 修复 Shadow 长期运行不结算的问题：`EventMakerShadowCli` 现在在 reconcile 后调用 `settleResolved` 和 `redeemPendingRedemptions`，只对已到期且有虚拟库存的市场检查 Gamma 终态。
- `LivePaperDataClient.getMarketResolution` 新增：Gamma `closed=true` 查询、`outcomePrices` 唯一 1/0 校验、未找到/未结算时返回 unresolved 而不是制造失败。
- `EventMakerShadowExecutionPort` 新增 settlement resolver；Shadow 的“链上仓位”在记账后按 `ALREADY_ABSENT` 关闭，不伪造交易哈希。
- `summary.json` 新增 `settlements`、`settlementLosses`；`events.jsonl` 新增 `settlements` / `redemptions`；状态报告新增结算列。
- 新增 Shadow 胜/负结算集成测试：验证 SETTLE cycle、realized PnL、库存归零、虚拟赎回关闭。
- 全量 `gradlew test`：BUILD SUCCESSFUL。
### 09:22 新结算代码上线与 Shadow 重启

- 归档重启前数据：`backend/build/research/archive/bond-shadow-pre-settlement-20261021-0930.zip`。
- 停掉旧 10 个 Shadow 进程，使用新代码全部重启；监控 PID 14956 已用新脚本重启。
- 新进程独立验证：10/10 臂均为 LIVE，`events.jsonl` 已出现 `settlements: []` / `redemptions: []` 字段，stderr 无异常。
- 重启后首批成交：
  - taker00c 与 taker2c 各 2 fills；
  - 1c/2c mom1 taker 各 1 fill @0.981，SELL 0.991/0.999；
  - 0.95–0.97 dip24h 臂首次成交 1 笔 @0.95，SELL 0.97，说明该过滤条件确实能等到市场；
  - 所有 cycle 仍为 0，结算链路等待市场到期后验证。
- 当前不进入真实资金；继续积累 fill、markout、cycle 和 SETTLE 样本。
### 09:36 Shadow 长测运行方式优化与审计补强

- 新增 `writeEventMakerShadowClasspath` Gradle 任务，将运行时 classpath 写入 `backend/build/research/eventmaker-shadow-classpath.txt`。
- `start-bond-shadow.ps1` 改为直接启动 `java @java.args`，不再为每个 Shadow 臂保留约 450 MB 的 Gradle daemon；单臂实测约 115–120 MB。
- 10 个 Shadow 臂已全部切换为直接 JVM；Gradle daemon 已停止，节省约 4.5 GB 内存。
- `execution-diagnostics.json` 改为每 10 秒随快照刷新，状态报告可实时读取 crossing trades / queue consumed。
- `audit-bond-shadow.cjs` 新增 settlements、settlementLosses、pendingSettlements、duplicateSettlementIds、missingSettlementCycles、settlementRealizedPnl 审计字段与 flags。
- monitor 的 `bond-shadow-audit-series.jsonl` 新增 settlements / settlementLosses / pendingSettlements。
- 当前 10/10 Shadow 臂 LIVE；另有 7 fills，SETTLE 仍为 0，等待真实到期样本。
### 09:40 自动验收闸门

- 新增 `scripts/evaluate-bond-shadow-gate.cjs`，只读扫描全部 Shadow state/markout。
- 默认门槛：候选臂运行 ≥24h、候选成交 ≥20、全臂结算 ≥50；结算亏损率 Wilson 95% 上界必须低于盈亏平衡亏损率；60s markout ROI 不得低于 -1%。
- 当前判定：`WAIT`；blockers 为 `TOTAL_SETTLEMENTS_BELOW_50`、`RUN_TIME_BELOW_MIN`、`FILLS_BELOW_MIN`、`NO_SETTLEMENTS`。
- 当前 60s markout ROI：taker 约 -0.36%，mom1 约 -0.31%，dip24h 约 -1.05%；佐证当前 taker 入场仍有逆向选择，不能上真钱。
- 证据：`backend/build/research/bond-shadow-gate-20261021.json`。
### 10:00 3–7 天窗口复核与 Shadow 切换

- 目标账户 0.97–0.99 分桶：3–7d mean ROI +1.14%、7–14d +0.56%、14–30d +0.50%、>30d +0.06%；最后 3d 不是利润来源。
- 数据集 maker ladder 3–7d / Geopolitics / +2c：
  - q2 241 lots +1.87%，q3 221 lots +1.88%，q4 216 lots +1.88%，均 0 结算亏损；
  - 年度 q2：2024 +1.455%、2025 +1.917%、2026 +1.904%，全部 100% 胜率。
- 最后 3 天 q2：0.97–0.99 +2c 为 -0.24%（12 结算亏损）；0.95–0.97 +2c 为 -7.46%（44 结算亏损）。
- 因此所有 Shadow 臂切换到 `minRemainingMinutes=4320`、`maxRemainingMinutes=10080`、`WaitForMarketMinutes=10080`；当前 Gamma 最近窗口约 170h，10/10 进程 LIVE/WAITING。
- 证据：`backend/build/research/bond-ladder-3to7d-20261021.json`、`bond-ladder-3to7d-q3-20261021.json`、`bond-ladder-3to7d-q4-20261021.json`、`leader-time-to-close-20261021.json`。
### 10:08 分布式 ladder 对齐

- 现有引擎每个市场只维护一个 active order；历史 +2c ladder 的多档同时挂单不能由单臂直接复现。
- 因此把四个 maker 臂对齐成分布式 ladder：off00/off05/off10/off20 分别对应 bid 下方 0/0.5/1/2c，统一用 +2c 目标、fillVolumeFactor=2、24h 动量 ≤+1c、3–7 天窗口。
- 评估时必须把这四臂聚合看，不能把单臂的 +1.88% 当成单臂收益。
- 其余 taker 臂只作为执行对照；当前 10/10 进程 LIVE/WAITING，等待市场进入 3–7 天窗口。
### 10:10 单笔规模与容量验证

- 3–7d / 0.97–0.99 Geo / +2c q2：$100 fill 73.1%、+1.87%；$1,000 fill 39.4%、+1.95%；$5,000 fill 18.1%、+1.89%。
- 年化量级约 $164（$100）、$914（$1,000）、$1,615（$5,000），均按 2.75 年数据集折算；容量有限，不能按高频策略预期。
- 新增 `event-maker-bond-scalp-2c-geo-97099-off10-size1k-shadow-7d-20261021`，$1,000 单档、3–7d、动量≤+1c，用于验证真实规模 fill。
- 当前共有 11 个 Shadow 臂，全部 WAITING。
### 10:12 一键 suite 启动与恢复

- 新增 `scripts/start-bond-shadow-suite.ps1`，统一定义 11 个 Shadow 臂的配置，支持：
  - `-DryRun`：打印配置不启动；
  - `-Restart`：按各臂 `run.json` 的 PID 停止旧 JVM 后重启；
  - 自动重启 monitor（默认 30 分钟审计一次）。
- `-Restart` 已实跑：11 个 JVM + monitor 全部启动，当前全部 WAITING；启动记录写入 `backend/build/research/bond-shadow-suite-start-*.json`。
- 这样后续长测恢复不再依赖手工复制一长串参数，避免配置漂移。
### 20.20 2026-10-21 官方 Maker 费率与重启恢复

- 再次直连官方 `https://docs.polymarket.com/trading/fees.md` 复核（HTTP 200），原文为：
  - **“Makers are never charged fees. Only takers pay fees.”**
  - 所有类别的 **Maker Fee Rate = 0**；Geopolitics 的 **Taker Fee Rate 也是 0**。
  - Politics Taker 0.04；maker rebate 是返佣计划，不代表 maker 付费。
  - Gamma 返回的 `makerBaseFee=1000` 是历史遗留字段，不是当前实际 maker 费率。
- 结论：当前 0.97–0.99 Geopolitics maker ladder 在官方费率口径下没有 maker 手续费；但仍有价差、逆向选择、成交概率和队列风险，不能把“0 手续费”误解成“无风险套利”。
- Shadow 重启恢复已实现：
  - `state.json` 改为完整 `EventMakerPersistedState`，不再只写 `markets`；
  - 新增 `recovery.json` 保存完整市场元数据，支持仓位离开 3–7 天窗口后继续恢复、卖出或结算；
  - 重启时从 ledger 恢复 fills / cycles / settlements / inventory / realized PnL；
  - 进程死亡后旧 active order 只清除队列状态，不丢已成交库存；
  - 重启后 outside-window 的 flat 市场不允许重新开仓，但有库存的市场仍允许退出；
  - 订单 ID 增加进程前缀，避免跨重启的 `shadow-1` 冲突；
  - events / snapshots / trades / markouts / discovery-attempts 改为续写，不再截断历史；
  - gate 使用持久化的 `startedAt`，并把 off00/05/10/20 聚合成一个分布式 ladder 候选组，而不是要求单臂独立过门槛。
- 新增测试：
  - `EventMakerShadowRecoveryTest`：sidecar 往返、市场合并、partial inventory + active order + cycle + settlement 恢复；
  - `EventMakerLiveEngineTest`：outside-window 禁止新开仓但允许库存退出；
  - `EventMakerShadowExecutionPortTest`：跨实例订单 ID 唯一。
- 当前 gate 仍为 `WAIT`：候选组等待 3–7 天市场、0 fills、0 settlements；这是样本不足，不是策略已通过。
### 20.21 2026-10-21 10:50 候选 ETA 与等待证据

- 新增 `scripts/report-bond-candidate-eta.ps1`：扫描当前 Geopolitics 市场，输出：
  - 当前满足 3–7d + 0.97–0.99 + volume≥10k + non-NegRisk 的候选；
  - 未来会进入窗口的高价候选及预计等待小时数；
  - 价格已到 0.97–0.99 但已进入最后 3 天的市场。
- 当前扫描 1,493 个 Geopolitics 市场：
  - **eligibleNow = 0**
  - **nextFutureCandidates = 0**
  - highPriceTooClose = 1（Israel x Iran ceasefire through October 21，约 25.2h，属于最后 3 天禁区）
- 证据：`backend/build/research/bond-candidate-eta-20261021.json`
- `monitor-bond-shadows.ps1` 已改为：
  - 每轮写 `candidate_eta` 记录；
  - 无 state 但进程存活时写 `WAITING`，而不是静默跳过；
  - 支持 `-Once` 做单轮自检。
- 新增后台 monitor PID 31176；11 个 Shadow 臂仍全部存活且 WAITING。
### 20.22 2026-10-21 10:56 尾部风险统计审计

- 新增 `scripts/audit-bond-tail-risk.cjs`，对 ladder 结果计算 Wilson 95% 亏损率上界、整笔归零盈亏平衡率与上界余量。
- 3–7d 0.97–0.99 Geopolitics 结果：
  - +2c q2：241 lots，0 结算亏损，亏损率上界 1.569%，盈亏平衡 1.835%，余量 **+0.266% → PASS**。
  - +2c q3：221 lots，上界 1.709%，盈亏平衡 1.841%，余量 +0.133% → PASS。
  - +2c q4：216 lots，上界 1.747%，盈亏平衡 1.847%，余量 +0.100% → PASS。
  - +1c q2：上界 1.307%，盈亏平衡 1.019%，余量 -0.288% → FAIL。
  - +3c q2：上界 1.615%，盈亏平衡 2.260%，余量 +0.645% → PASS。
- 结论：+2c q2 主策略在当前历史样本和“整笔归零”保守假设下通过统计边界；但这不等于真实 SETTLE 或 Shadow gate 通过。
- 证据：`backend/build/research/bond-tail-risk-audit-20261021.json`、`bond-tail-risk-audit-q3-20261021.json`、`bond-tail-risk-audit-q4-20261021.json`。
### 20.23 2026-10-21 11:00 规模放大的尾部风险审计

- $100 单档：241 lots，亏损率 Wilson 上界 1.569%，盈亏平衡 1.835% → PASS。
- $1,000 单档：129 lots，上界 2.892%，盈亏平衡 1.913% → FAIL（0 亏损但样本不足）。
- $5,000 单档：47 lots，上界 7.556%，盈亏平衡 1.855% → FAIL（样本严重不足）。
- 结论：Live smoke 只能从 $100 单档开始；$1k/$5k 仍需约 200 个独立成交 lot 才具备统计意义，不能因 ROI 看起来相同就放大仓位。
### 20.24 2026-10-21 11:01 修正 maker ladder 的隐性 tick 偏移

- 发现 suite 的 maker 臂继承了 `ImproveTicks=1`、`ExitImproveTicks=1`：
  - 在 0.001 tick 市场上，入口实际变成 +0.1c / -0.4c / -0.9c / -1.9c，而不是 -0c / -0.5c / -1c / -2c；
  - 出口也可能比 entry+2c 目标更早成交；
  - 这与历史 ladder 模型不一致。
- 已修正 `start-bond-shadow-suite.ps1`：
  - `ImproveTicks = 0`
  - `ExitImproveTicks = 0`
- 已重启全部 11 个 Shadow 臂和 monitor；`run.json` 已核对：
  - off00/05/10/20 = offset 0/0.5/1/2c
  - improveTicks=0，exitImproveTicks=0
  - 3c 臂和 $1k 臂也已对齐。
- 当前没有 state / 成交，所以这次修正没有污染已有样本。
- 新增回归测试：0.001 tick 下 0/0.5/1/2c 必须映射为 0.980/0.975/0.970/0.960；`EventMakerQuotePlannerTest` 通过。
### 20.25 2026-10-21 11:08 Momentum 过滤口径对齐

- 发现主 maker 臂之前继承了 `EntryMomentumMaxCents=1`，而 3–7d ladder 主回测并未应用该过滤；这会缩小候选池并降低统计样本。
- 新增加 causal momentum 过滤到 `analyze-bond-ladder.cjs`，用 entry 前 24h 第一笔 favorite 成交对比 entry 时 favorite 价格。
- 回测对比（3–7d、0.97–0.99、Geopolitics、+2c、q2）：
  - 不过滤：160 eligible → 241 lots，mean ROI +1.87%，0 结算亏损，Wilson 上界 1.569%，余量 +0.266%。
  - 过滤 24h ≤+1c：131 eligible → 208 lots，mean ROI +1.87%，0 结算亏损，Wilson 上界 1.813%，余量仅 +0.020%。
- 结论：主 maker ladder **不做 24h 动量过滤**，以匹配历史主回测并保留 241 lots 的统计样本；`mom1` taker 臂继续独立测试过滤版本。
- 已将 off00/05/10/20 和 $1k 臂的 `EntryMomentumMaxCents` 改为 999；`mom1-taker*` 保持 1。已重启 11 臂并逐臂核对 `run.json`。
### 20.26 2026-10-21 11:12 并发市场数与 MaxMarkets

- 新增 `scripts/analyze-bond-concurrency.cjs`，验证 3–7d 候选的历史并发度。
- 0.97–0.99 Geopolitics：160 个候选，最大并发 17，时间加权平均并发 0.805。
- 因此 suite 的 `MaxMarkets=20` 不会截断历史主策略候选。
- 证据：`backend/build/research/bond-concurrency-20261021.json`。
### 20.27 2026-10-21 11:15 MaxCycles 校验

- ladder 回测新增 `maxLotsPerMarket` / `marketsOver10Lots` / `marketsOver20Lots`。
- 0.97–0.99 Geopolitics +2c q2：241 lots / 117 markets，单市场最大 7 lots，超过 10 的市场为 0。
- suite 的 `MaxCyclesPerMarket=10` 足够覆盖历史主策略，不会提前截断。
### 20.28 2026-10-21 11:20 闸门回归与状态语义

- 修复 `evaluate-bond-shadow-gate.cjs` 的零亏损尾部统计：
  - 零亏损时用 `avgWin / (avgWin + 1)` 作为整笔归零的盈亏平衡率，避免样本不足被误判 PASS。
- 增加 fatal blocker：
  - `SETTLEMENT_LOSS_CI_LOWER_ABOVE_LIMIT`
  - `SETTLEMENT_LOSS_CI_LOWER_ABOVE_BREAK_EVEN`
  - `NO_WINNING_SETTLEMENTS`
  - `MEMBER_MISSING_STATE`
- 状态语义：
  - 样本不足 / 等待市场 / 上界尚未收敛 → `WAIT`
  - 已观察到亏损显著高于盈亏平衡、全部结算亏损或成员缺失 → `FAIL`
- 新增 `scripts/test-bond-shadow-gate.cjs` 回归测试，构造 3 组状态：
  - 1000 个零亏损结算 → PASS
  - 100 个零亏损结算 → WAIT
  - 100 个全亏损结算 → FAIL
- 结果：`bond gate regression: 4/4 PASS`。
### 20.29 2026-10-21 11:28 计划状态校正

- 交付计划已把旧的 12h–7d 八窗口结论标记为“已由 3–7d 主窗口 + Wilson 审计替代”，不再作为验收依据。
- Shadow fill 任务状态更新为“11 臂已启动、全部 WAITING、尚无 state/fill”。
- 尾部风险项保留未勾选，但记录 q2 的统计边界：上界 1.569% < 盈亏平衡 1.835%。
- 全量回归更新为 **109 suites / 609 tests / 0 failures / 0 errors / 2 skipped**。
- Live smoke 仍未执行，清单已链接到交付计划。
### 20.30 2026-10-21 11:33 实际 CLI 重启恢复烟测

- 新增 `scripts/smoke-bond-shadow-resume.ps1`，自动构造一个带 10 股库存、1 条历史 BUY fill、旧 active SELL 的 Shadow state/recovery。
- 实际启动 `EventMakerShadowCli` 1 分钟，验证：
  - `summary.resumed=true`
  - `recoveredInventoryShares=10.0`
  - `abandonedRecoveredOrders=1`
  - 重启后 `activeOrderId=null`
  - `inventoryShares=10.00`
  - `fills=1`
  - `haltedReason=null`，`lastErrors=[]`
- 输出：`resume smoke PASS`。
- 证据目录：`backend/build/research/resume-smoke-latest/`（state、recovery、summary、logs）。
- 这验证了实际 JVM 路径，而不只是单元测试里的状态机恢复。
### 20.31 2026-10-21 11:51 启动成交恢复与幽灵结算 fail-closed

- 审计发现两个实盘边界：
  1. 已知活动订单在 JVM 停机期间成交时，旧启动检查会因仓位数量不一致直接拒绝，且只有 active order、没有库存的未发现市场不会重建状态。
  2. 已解析市场若仓位查询返回 `null`，旧逻辑会直接按 1/0 记结算，可能产生幽灵结算和虚增 PnL。
- 修复：
  - `start()` / `runLive()` 在任何仓位检查前，先按持久化 `activeOrderId` 查询权威订单快照并对账，允许停机期间正常成交。
  - 未发现市场只要有 active order 或 inventory 就重建恢复市场；BUY 仍受 outside-window 禁止新开仓，SELL 可继续退出。
  - `projectPersistedState` 同步更新 `cumulativeRealizedPnl`，避免停机期间 SELL 已实现 PnL 被重启基线抵消。
  - 已解析市场 `position=null` 时重试 3 次，仍缺失则 `settlement_position_missing` 停机，不记结算。
  - Shadow CLI 从虚拟库存构造 settlement position，与 Live 的 fail-closed 语义保持一致。
- 回归测试：
  - 停机期间 BUY 成交 5 股 -> inventory=5、activeOrder=null、不误撤单。
  - 停机期间 SELL 成交 5 股 -> realized/cumulative +0.05、cycle 完成。
  - 结算仓位缺失 -> 0 settlement，连续 3 次后 halt。
  - Shadow winning/losing SETTLE 路径通过。
- 全量回归：**109 suites / 611 tests / 0 failures / 0 errors / 2 skipped**。
- 11 个 Shadow 臂已于 2026-10-21 11:50:05–11:50:16 +08:00 用新代码重启，全部 `WAITING`，`0 fills / 0 settlements`，Gate 仍为 `WAIT`。
- 启动证据：`backend/build/research/bond-shadow-suite-start-20261021-115019.json`。
### 20.32 2026-10-21 11:58 结算仓位链上/索引双源对账

- `ClobEventMakerExecutionAdapter` 的结算路径现在同时读取 Data API position 和链上 CTF `balanceOf`：
  - 已解析市场记账前，两边股数必须严格相等；否则 `settlement_position_chain_mismatch` fail-closed。
  - 链上有仓但 Data API 缺失：`settlement_position_index_missing`，不允许盲记。
  - 赎回恢复阶段如果链上余额为 0，即使 Data API 仍返回旧仓位，也按已赎回成功处理，避免索引延迟导致误停。
  - 链上余额查询失败：`settlement_chain_balance_unavailable`，同样 fail-closed。
- 新增适配器回归：
  - 链上 10 / API 10 -> 正常快照；
  - 链上 9 / API 10 -> mismatch；
  - 链上 10 / API 缺失 -> index missing；
  - 赎回恢复链上 0 / API 仍为 10 -> ALREADY_ABSENT 路径。
- 全量回归：**109 suites / 614 tests / 0 failures / 0 errors / 2 skipped**。
### 20.33 2026-10-21 12:40 单引擎原子多档 ladder

- 审计确认：历史回测每个市场同时挂 4 档，但旧 Live/Shadow 引擎每个市场只有一个 active order；4 个独立进程只能近似，无法原子共享库存和风险。
- 已实现：
  - `EventMakerConfig.ladderOffsetsCents`；
  - 单进程内每市场多 leg，各自维护 active order、inventory、fills、cycles；
  - `EventMakerPersistedMarket.legOffsetCents`，状态库允许同 condition 的不同 offset；
  - 旧单档与多档混用、重复 offset 均拒绝；
  - 仓位检查按 condition 汇总；
  - 结算按 condition 汇总仓位，每 leg 单独记账；
  - 赎回按 condition 聚合成一次调用；
  - `maxMarkets` 按 distinct condition 计数；
  - `runQuoteTick` / `runEngineTick` 现在真正传入 discovered-market 白名单，恢复市场卖空后不能重新开 BUY；
  - Shadow CLI 新增 `--ladder-offsets-cents`，启动脚本新增 `-LadderOffsetsCents`；
  - snapshot CSV 增加 `leg_offset_cents`，报告按 leg 统计 unrealized。
- Shadow suite 收敛为 8 臂，主候选为 `ladder4`：0.97–0.99、3–7d、0/0.5/1/2c、每档 $100、总库存 $400。旧 off00/05/10/20 已停止并从 Gate/报告/监控默认路径移除。
- 回归：
  - 4 leg 同时挂出 0.980/0.975/0.970/0.960；
  - 多 leg 重启恢复；
  - 多 leg 仓位汇总；
  - 多 leg 一次结算、一次聚合赎回；
  - 旧 state 投影去重；
  - Shadow CLI 选项解析；
  - discovered whitelist 阻止恢复市场重新开仓。
- 全量：**109 suites / 622 tests / 0 failures / 0 errors / 2 skipped**。
- 当前 8 臂全部 WAITING，`0 fills / 0 settlements`，Gate `WAIT`。
- 启动证据：`backend/build/research/bond-shadow-suite-start-20261021-124019.json`。
### 20.34 2026-10-21 12:55 官方 Maker 费率与多档收口

- 重新核对官方 `https://docs.polymarket.com/trading/fees.md`（2026-10-21）：
  - 原文：**“Makers are never charged fees. Only takers pay fees.”**
  - 表格中所有类别 `Maker Fee Rate = 0`；Geopolitics `Taker Fee Rate = 0`、`Maker Rebate = 0`。
  - `fee = C × feeRate × p × (1 - p)` 只对 taker 生效；Gamma 的 `makerBaseFee=1000` 不是实际费率。
  - 结论：当前 ladder 回测与 Shadow 把 maker 双边成交按 0 费处理是正确的，不需要给 maker 加手续费缓冲。
- 修复多档收口缺口：
  - requote 的 desired quote 改为使用每个 leg 自己的 `state.config`，避免同一引擎用顶层 offset 错误重挂其它档；
  - persisted leg 的 offset 不在当前配置中时，禁止重新开 BUY，只允许继续退出；
  - REST `EventMakerStartRequest` 暴露 `ladderOffsetsCents`，与 CLI 对齐。
- 回归：
  - 4 档同盘挂单与 requote 不再互相污染；
  - 旧 offset leg 不能重新开仓；
  - REST DTO 映射；
  - 单档与多档 CLI 重启烟测均通过（多档恢复 2 leg / 15 shares / 2 fills）。
- 全量：**109 suites / 624 tests / 0 failures / 0 errors / 2 skipped**。
- 8 臂已于 2026-10-21 12:50:36 +08:00 用新代码重启，全部 `WAITING`，`0 fills / 0 settlements`，Gate `WAIT`。
- 启动证据：`backend/build/research/bond-shadow-suite-start-20261021-125036.json`。
### 20.35 2026-10-21 13:05 当前代码回测复跑

- 用当前代码重新运行 3–7d、0.97–0.99 Geopolitics、0/0.5/1/2c、+2c、每档 $100、max 4 tranches 的 ladder 回测（q2/q3/q4）。
- q2：241 lots / 117 filled markets / mean ROI +1.870% / 0 settlement loss / leg-level Wilson 上界 1.569%；**市场聚类后只有 9 个结算市场，上界 29.915% → FAIL（见 20.39）**。
- q3：221 lots / 110 filled markets / mean ROI +1.876% / 0 settlement loss；**市场聚类后 10 个结算市场，上界 27.754% → FAIL（见 20.39）**。
- q4：216 lots / 105 filled markets / mean ROI +1.882% / 0 settlement loss；**市场聚类后 12 个结算市场，上界 24.250% → FAIL（见 20.39）**。
- q2 分年：2024 +1.455%、2025 +1.917%、2026 +1.904%，全部为正。
- 证据：`backend/build/research/bond-ladder-current-q2-20261021.json`、`-q3-`、`-q4-`、`bond-tail-risk-current-q2-20261021.json`。
- 结论修正：点估计仍为正，但 leg-level 统计不能证明尾部风险；按市场聚类后统计证据不足，真实 Shadow 仍 0 fill，Gate WAIT，不能据此上真钱。
### 20.36 2026-10-21 13:12 Shadow 多档成交量共享修复

- 审计发现 `EventMakerShadowExecutionPort.onTrade` 对每个可成交挂单都重新使用整笔 `trade.size`，多档时会重复计算同一笔 taker 成交量，虚增 fill。
- 已改为：同一笔 print 的成交量按价格优先、时间优先在多个 leg 之间共享；
  - BUY 挂单按价格从高到低；
  - SELL 挂单按价格从低到高；
  - 队列消耗、`fillVolumeFactor` 累计阈值和剩余成交量使用同一份 volume。
- 这与 `scripts/analyze-bond-ladder.cjs` 的回测口径一致；此前回测会递减 `remaining`，Shadow 不会。
- 新增 5 条回归：
  - 单笔 SELL print 在两条 BUY leg 间按 10/5 分配；
  - 单笔 BUY print 在两条 SELL leg 间按最优价分配；
  - `fillVolumeFactor=2` 时同一 print 的成交量不会被每档重复使用；
  - 同价挂单按真实放置顺序 FIFO，而不是按订单 ID 字符串排序；
  - 多档中一档部分成交只撤该档，另一档保持活动。
- 全量回归：**109 suites / 632 tests / 0 failures / 0 errors / 2 skipped**。
- 单档与多档 CLI 重启烟测复跑通过：单档 10 shares / 1 fill；多档 2 legs / 15 shares / 2 fills。
- 8 个 Shadow 臂已于 2026-10-21 13:45:58 +08:00 重启加载新撮合代码；全部 `WAITING`，0 fills / 0 settlements，Gate `WAIT`。
- 启动证据：`backend/build/research/bond-shadow-suite-start-20261021-134558.json`。
### 20.37 2026-10-21 13:40 REST 参数对齐与错误码

- `EventMakerStartRequest` 补齐 `takerEntry` 和 `takerEntrySlippageTicks`，REST 与 CLI 配置字段对齐。
- 参数校验异常（如 ladder 与 taker 同时开启）现在映射为 `PARAM_ERROR`，不再误报 `SERVER_ERROR`。
- 新增两条控制器回归：taker 参数映射、ladder+taker 非法组合返回参数错误。
- 全量回归：**109 suites / 632 tests / 0 failures / 0 errors / 2 skipped**。
### 20.38 2026-10-21 13:48 REST Shadow 结算持久化修复

- REST `SHADOW` 路径在结算/赎回后仍调用 `persistLive`，会以 `accountId=0` 尝试写 `mode=LIVE` 状态，触发 `live_account_missing`，导致 Shadow 结算 tick 失败。
- `runEngineTick` 新增 `persistState` 参数：REST Shadow 传 `false`，Live 传 `true`；只有 Live 才写状态文件。
- 新增回归：Shadow 结算 tick 生成 settlement，但不会创建 LIVE 状态文件，也不抛异常。
- 全量回归：**109 suites / 632 tests / 0 failures / 0 errors / 2 skipped**。
- 8 臂已用新代码重启，全部 WAITING；启动证据 `backend/build/research/bond-shadow-suite-start-20261021-134558.json`。
### 20.39 2026-10-21 13:55 市场聚类尾部审计

- 发现 pseudo-replication：同一市场的 4 条 ladder leg 共享同一个结算结果，却被当作 241 个独立 settlement 样本。
- `analyze-bond-ladder.cjs` 增加按 `conditionId` 聚类的 `settleMarkets`、`settleLossMarkets`、`marketLossCi95`、`marketBreakEvenLossRate`。
- q2/q3/q4 市场级结果：
  - q2：9 个结算市场，0 亏损，Wilson 上界 29.915%，盈亏平衡 2.075% → **FAIL**。
  - q3：10 个结算市场，0 亏损，Wilson 上界 27.754%，盈亏平衡 2.254% → **FAIL**。
  - q4：12 个结算市场，0 亏损，Wilson 上界 24.250%，盈亏平衡 2.310% → **FAIL**。
- `audit-bond-tail-risk.cjs` 输出 market-cluster verdict；leg-level verdict 只保留作诊断。
- `evaluate-bond-shadow-gate.cjs` 改为按 marketId 聚类 settlement，50 个门槛现在指 50 个独立结算市场；同时要求 `min-cycles=50` 且 `realizedRoi >= 0`。`report-bond-shadow-status.cjs` 增加 `settlementMarkets`。
- Gate 回归 4/4 PASS；当前 Gate WAIT（0 settlement markets）。
- 证据：`bond-ladder-cluster-q2/q3/q4-20261021.json`、`bond-tail-risk-cluster-q2/q3/q4-20261021.json`。
### 20.40 2026-10-21 14:00 close 前 24h 强制平仓实验

- 在 `analyze-bond-ladder.cjs` 增加 `--flatten-hours-before-close` 和 `--flatten-slippage-ticks`，仅做回测实验，不改变运行中的 Shadow。
- q2 不强制平仓：+1.870%、9 个结算市场、最差 lot +1.011%。
- close 前 24h 强制平仓、2 tick 滑点：+1.726%、75 个 FLATTEN lot / 62 个市场、0 平仓亏损、最差 lot +0.101%。
- 分年：2024 +1.088%、2025 +1.782%、2026 +1.791%，均为正。
- 结论：强制平仓变体可能显著降低结算尾部风险，但需要新增 Live taker SELL 执行路径和新 Shadow 臂验证；当前不能把它当作已实盘验证。
- 证据：`bond-flatten24-q2-20261021.json`、`bond-flatten24-s1-q2-20261021.json`、`bond-flatten24-s2-q2-20261021.json`。
### 20.41 2026-10-21 14:37 close 前 24h 强制平仓执行路径

- 新增可选配置 `flattenBeforeCloseMinutes` / `flattenSlippageTicks`，默认 0 关闭，不改变现有策略。
- `EventMakerQuoteIntent` 增加 `takerExit`；planner 新增 bid 下方 slippage 的 marketable SELL。
- Live adapter 对 taker entry/exit 使用 `postOnly=false`；普通 maker 仍强制 post-only。
- Engine 在 flatten 窗口内先撤普通挂单，再下 taker SELL；15 秒未成交会撤单重试；Shadow 端口对 taker exit 立即成交。
- Cycle 增加 `FLATTEN` 类型，持久化 `forcedFlatten`；gate/report 把 `SETTLE` 和 `FLATTEN` 都作为 tail exit，并按 `marketId` 聚类。
- Shadow suite 新增 `flatten24` 臂：0.97–0.99 Geopolitics、0/0.5/1/2c、每档 $100、close 前 1440 分钟平仓、2 tick 滑点。
- 全量回归：**109 suites / 637 tests / 0 failures / 0 errors / 2 skipped**；Gate 回归 **5/5 PASS**。
- 9 个 Shadow 臂（含 flatten24）于 14:36:07 +08:00 重启，全部 WAITING；尚无 fill/settlement 样本。
- 启动证据：`backend/build/research/bond-shadow-suite-start-20261021-143607.json`。

### 20.42 2026-10-21 14:42 当前 Gate 与候选市场复核

- 重跑 `evaluate-bond-shadow-gate.cjs` 与 `report-bond-shadow-status.cjs`：9 个 Shadow 臂全部 `WAITING`，0 fills / 0 cycles / 0 settlements / 0 settlement markets，Gate 结果为 `WAIT`。
- 重跑 `report-bond-candidate-eta.ps1`：扫描 1,492 个 Geopolitics 市场，符合 3–7 天、0.97–0.99、非 NegRisk、24h 成交量 ≥ 10,000 的当前候选为 **0**，未来进入该窗口的候选也为 **0**。
- 当前仅有两个高价市场但都太接近结算：Israel x Iran ceasefire 21.27h 后关闭（24h 量 89,207.9），US x Yemen ceasefire 17.27h 后关闭（24h 量 5,996.55）；均不应作为主策略样本。
- 因此“现在”的历史回测结论仍是正的点估计，但实盘影子还没有任何可验证成交样本；必须等待新的 3–7 天 Geopolitics 市场进入窗口。
### 20.43 2026-10-21 14:50 当前目标：继续收口回测与实盘策略验证

- 目标：按交付计划完成剩余验收项，直到回测、Shadow 与执行链路都经证据验证；真实资金 Live smoke 仍需用户账户和显式授权。
- 前置状态：flatten24 代码已实现但尚未提交；9 个 Shadow 臂在运行，全部 WAITING；历史回测点估计为正、市场级统计仍 FAIL。
- 本轮先做：复核 flatten 实现边界 → 全量单元/集成测试 → Gate 回归 → 重跑回测产物对照 → 检查 Shadow 进程和候选市场。
- 约束：运行中的 Shadow 不重新编译；如需加载新代码，必须停止旧进程并记录干净重启证据。
### 20.44 2026-10-21 14:54 规模复核：为什么目标账户赚得多而当前回测只有 $450

- 目标账户公开事实：累计买入 **$5,626,529.09**，已实现 PnL **+$29,919.56**，390 个已平仓记录，单次周转 ROI **0.532%**；公开成交中 2,868 笔买入有 markout，按股数分组的中位最大库存约 **7,000 股**，对应 0.97–0.99 价格约 **$6.8k** 库存。
- 当前 $100 ladder：241 lots、117/160 成交市场、fill rate 73.1%、mean ROI +1.870%、PnL **$450.55**、平均最大库存 **$198.46**。这不是目标账户的规模，而是最小单档验证。
- 当前代码重跑规模敏感性（q2、0.97–0.99、+2c、4 档）：
  - $1,000/tranche：129 lots、63 个成交市场、fill rate 39.4%、mean ROI +1.950%、PnL **$2,515.70**、平均最大库存 $2,009.89。
  - $5,000/tranche：47 lots、29 个成交市场、fill rate 18.1%、mean ROI +1.890%、PnL **$4,442.63**、平均最大库存 $8,133.00。
- 结论：从 $100 放大到 $5,000，PnL 约 9.9 倍，但 fill rate 从 73.1% 降到 18.1%，不是线性放大；目标账户的绝对收益来自**更大库存 + 更多市场/轮次 + 事件选择**，不是靠把同一小样本无限加仓。
- 全价格带简单 ladder（$100、q2、3–7d、max 8 tranches）复核：0.5–0.8 Geopolitics +2c **-15.33%**，0.8–0.9 **-8.31%**，0.9–0.95 **-5.20%**；即使 close 前 24h 平仓仍为负。只有 0.97–0.99 Geopolitics 为正。目标账户在低价格带的利润不能由当前 hold/ladder 模型复刻，必须另做事件选择与主动退出。
- 全量回归（强制重跑）：**109 suites / 637 tests / 0 failures / 0 errors / 2 skipped**。Gate 回归 **5/5 PASS**。
- 9 个 Shadow 臂已停止旧进程、测试后于 14:54:54 用当前代码干净重启；当前全部 WAITING、0 fills / 0 settlements，Gate `WAIT`。
- 证据：`bond-capacity-1k-q2-current-20261021.json`、`bond-capacity-5k-q2-current-20261021.json`、`bond-broad-band-q2-100-20261021.json`、`bond-broad-band-q2-100-flatten24-s2-20261021.json`、`bond-shadow-suite-start-20261021-145454.json`。
### 20.45 2026-10-21 15:09 按用户体量收敛到 $500/档的 flatten24 策略

- 用户体量：几千 USDC，不需要复刻目标账户的大库存和高周转；目标是资金适配、尾部可控、可执行。
- 新回测统计口径：`SETTLE` 与 `FLATTEN` 都作为 tail exit，按 `conditionId` 聚类；强制平仓的无亏损样本使用 1% 滑点压力损失计算盈亏平衡，不再套用“结算归零”的 -100% 回退。
- 0.97–0.99 Geopolitics、+2c、q2、4 档、close 前 24h 平仓、2 tick 滑点、不同单档仓位：

| 单档 | lots | fill rate | mean ROI | 总 PnL | 平均最大库存 | tail markets | tail losses | tail CI95 上界 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| $250 | 201 | 58.8% | +1.735% | $871.82 | $519 | 57 | 0 | 6.31% |
| **$500** | **169** | **49.4%** | **+1.759%** | **$1,486.57** | **$1,037** | **53** | **0** | **6.76%** |
| $750 | 144 | 43.1% | +1.764% | $1,904.60 | $1,507 | 48 | 0 | 7.41% |
| $1,000 | 129 | 39.4% | +1.771% | $2,285.17 | $2,010 | 44 | 0 | 8.03% |

- $500、+2c、flatten24：tail 聚类 53 个市场、0 亏损、1% 压力损失下盈亏平衡率 58.20%，Wilson 上界 6.76% → **PASS**。
- $500、+3c、flatten24：165 lots、64 个 tail 市场、mean ROI +2.006%、总 PnL $1,654.71、tail CI 上界 5.66% → **PASS**；收益更高但更依赖 +3c 成交。
- 结论：在几千 USDC 体量下，首选 **$500/档、4 档、最大库存 $2,000、0.97–0.99 Geopolitics、+2c、24h flatten**；保留 $1,000 现金缓冲。+3c 作为收益增强对照臂。
- 新增 Shadow 臂：`smallcap-flatten24-2c` 与 `smallcap-flatten24-3c`，均为 $500/档、max inventory $2,000、max daily loss $100、max markets 3。Gate 主候选已切换为 `smallcap-500-flatten24-2c`。
- 当前 11 个 Shadow 臂已干净重启，全部 WAITING，0 fills / 0 settlements；Gate 仍为 WAIT。历史统计 PASS 不等于真实 Shadow 或 Live 通过。
- 回归：`test-bond-tail-audit.cjs` 2/2 PASS；`test-bond-shadow-gate.cjs` 5/5 PASS。
- 证据：`bond-smallcap-500-flat24-q2-tailcluster-20261021.json`、`bond-smallcap-500-flat24-q2-tailaudit-20261021.json`、`bond-smallcap-750-flat24-q2-tailcluster-20261021.json`、`bond-shadow-suite-start-20261021-150907.json`。
### 20.46 2026-10-21 15:14 提交与推送

- 提交 `a49377b feat: implement pre-close flatten execution`：Live/Shadow 强制平仓执行路径、taker SELL、FLATTEN cycle、状态持久化与 637 tests。
- 提交 `cea36fb feat: add small-cap flatten24 validation`：tail exit 聚类、1% flatten 滑点压力、$500/档小资金候选、两个新 Shadow 臂、Gate 主候选切换和文档。
- 已推送 `cfff747..cea36fb main -> main`；工作区 clean。
### 20.47 2026-10-21 15:20 为什么绝对收益差这么多：周转率分解

- 目标账户：累计买入 $5,626,529、已实现 PnL $29,919.56、约 2,868 笔买入、平均单笔约 $1,962、单次周转 ROI 0.532%、组合价值约 $20k。
- 当前小资金策略（$500/档、flatten24、+2c）：累计买入约 $84,500（169 × $500）、PnL $1,486.57、单次周转 ROI 1.759%、组合库存约 $2k。
- 差距不是单笔收益率：我们的单次 ROI 反而是目标的约 3.3 倍；差距来自吞吐量：
  - 目标成交笔数约 2,868，我们是 169，约 17 倍；
  - 目标平均单笔约 $1,962，我们是 $500，约 3.9 倍；
  - 累计买入额约 66.7 倍，PnL 约 20.1 倍。
- 目标账户用更多市场、更大单笔和更多轮次把 0.532% 的小 edge 放大；我们把一个更高的 1.759% edge 限制在很窄的 0.97–0.99 / 3–7d / Geopolitics 池里，成交供给不足。
- 不能直接用低价格带补成交量：当前被动 ladder 在 0.5–0.8 Geopolitics 为 -15.33%、0.8–0.9 为 -8.31%、0.9–0.95 为 -5.20%；目标账户在这些 band 的利润来自事件选择、主动退出和库存管理，不是简单挂单持有。
- 目标近期 REWARD + MAKER_REBATE 约 $2,872（约占同期收益 15%）；Geopolitics 没有 maker rebate，这部分也不能直接复制。
- 结论：在 $2k 体量下，当前策略的目标是“可执行、尾部可控的小 edge”，不是复制目标账户的绝对收益。要提高绝对收益，必须先提高合格市场数和真实成交轮次，或建立事件选择/主动退出模型；不能只把 $500 放大到 $5,000。
### 20.48 2026-10-21 15:28 机械止损 A/B：不能复刻目标账户的主动退出

- 给回测增加实验参数 `--stop-loss-cents`：当后续成交价跌破 entry - N cents 时按该成交价 taker 退出，并重新武装同一档位。
- $500/档、q2、3–7d、+2c、最大 4 档、2c 机械止损：
  - `0.97–0.99 Geopolitics`：309 lots，其中 190 次 STOP，mean ROI **-1.88%**，PnL 为负；
  - `0.95–0.97 Geopolitics`：600 lots，519 次 STOP，mean ROI **-9.45%**；
  - `0.9–0.95 Geopolitics`：**-26.17%**；
  - `0.8–0.9 Geopolitics`：**-36.56%**；
  - `0.5–0.8 Geopolitics`：**-47.65%**。
- 原因：成交打印触发止损后又在同一价格带被重新挂单成交，形成反复止损/反向选择；目标账户的 18.4% 负价差退出不是这种机械 N-cents 止损。
- 结论：**不部署机械止损**；保留 close 前 24h flatten 作为尾部控制，继续寻找基于事件/盘口持续性的主动退出，而不是简单价格止损。
- 默认 `--stop-loss-cents=0`，不改变现有 $500 flatten24 基准；基准复跑仍为 169 lots、+1.759%、PnL $1,486.57。
- 证据：`bond-broad-stop2-q2-500-20261021.json`、`bond-smallcap-500-flat24-q2-nostop-recheck-20261021.json`。
### 20.49 2026-10-21 15:47 提高年化的参数研究：6 档 + q1 + +4c

- 目标：在总库存不超过 $3,000–$4,000 的前提下提高资金周转和绝对收益，而不是只放大单档。
- 关键变化：ladder 从 4 档扩展到 6 档（0/0.5/1/2/3/4/5c），目标从 +2c 提高到 +4c，并使用 q1 队列假设；仍保留 24h flatten、2 tick 滑点和 3–7d 窗口。
- 回测结果（0.97–0.99 Geopolitics、q1、6 档、+4c、24h flatten）：

| 单档 | 最大库存 | lots | fill rate | mean ROI | 总 PnL | 平均最大库存 | tail markets | tail 亏损 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| $500 | $2,000 | 195 | 58.8% | +2.046% | **$1,994** | $1,037 | 69 | 0 |
| $650 | $2,600 | 184 | 58.8% | +2.060% | **$2,463** | $1,344 | 68 | 0 |
| $700 | $2,800 | 182 | 58.8% | +2.063% | **$2,628** | $1,448 | 67 | 0 |
| $750 | $3,000 | 175 | 53.1% | +2.059% | **$2,703** | $1,543 | 67 | 0 |
| $700 | $3,500 | 193 | 58.8% | +2.186% | **$2,953** | $1,530 | 67 | 0 |
| $750 | $3,750 | 186 | 58.8% | +2.194% | **$3,061** | $1,635 | 67 | 0 |

- 相比原 $500/q2/+2c 的 $1,486，$650/q1/+4c 提升到 **$2,463**（约 +66%）；$750/q1/5 档提升到 **$3,061**。
- 代价：q1 是更乐观的队列假设；如果真实队列接近 q2，收益会回落到约 $1,700–$2,000。必须用 Shadow 验证，不能把 q1 当已实现。
- 新增 Shadow 臂 `smallcap-flatten24-q1-4c`：$650/档、6 档、max inventory $2,600、max daily loss $130、max markets 3、FillVolumeFactor=1、+4c、24h flatten。
- 该臂已于 15:47:37 干净重启，当前 WAITING、0 fills；Gate 仍以保守的 q2/$500 候选为主，q1 臂只做研究对照。
- 证据：`bond-opt-500-t4-l6-q1-flat24-20261021.json`、`bond-opt-650-q1-l6-mt4-t4-flat24-20261021.json`、`bond-opt-750-q1-l6-mt4-t4-flat24-20261021.json`、`bond-opt-750-q1-l6-mt5-t4-flat24-20261021.json`、`bond-shadow-suite-start-20261021-154737.json`。
### 20.50 2026-10-21 15:55 按资金档位的最优参数

- 统一条件：0.97–0.99 Geopolitics、3–7d、6 档（0/0.5/1/2/3/4/5c）、+4c、q1、24h flatten、2 tick。
- 资金档位与历史结果：

| 可用资金 | 单档/最大档数 | 最大库存 | lots | mean ROI | 2.75 年 PnL | 年化约 | tail 市场 |
|---:|---|---:|---:|---:|---:|---:|---:|
| $2,000 | $500 × 4 | $2,000 | 195 | +2.046% | $1,994 | $725 | 69 |
| $3,000 | $750 × 4 | $3,000 | 175 | +2.059% | $2,703 | $983 | 67 |
| $4,000 | $1,000 × 4 | $4,000 | 164 | +2.050% | $3,361 | $1,222 | 64 |
| $4,500 | $750 × 6 | $4,500 | 195 | +2.290% | $3,349 | $1,218 | 67 |

- 以上均为 q1 队列假设；如果真实成交接近 q2，$500/4 档会回落到约 $1,681，$750/4 档约 $2,100。q1 必须由 Shadow 验证。
- 当前新增 `smallcap-flatten24-q1-4c` 臂：$650/档、4 档、最大库存 $2,600，历史 q1 结果 $2,463；它是 q1 假设的实测探针。
- 结论：在不增加尾部风险的前提下，6 档 + +4c + q1 比原来的 4 档 + +2c + q2 明显提高资金效率；但只有 Shadow 的真实 fill/markout 才能决定 q1 是否成立。
### 20.51 2026-10-21 15:57 组合资金约束复核：per-market 汇总会高估

- 新增 `scripts/analyze-bond-portfolio.cjs`：按每笔 lot 的 entryTs/exitTs 做资金占用模拟，限制总资本、最大并发市场数，统计真正可执行的 lots/PnL/tail 市场。
- 以 $500/档、q1、6 档、+4c、24h flatten 为例：
  - $3,000、max 6 市场：132 lots、PnL **$1,244**、年化约 **$593**、58 个 tail 市场、0 tail 亏损；
  - $3,000、$250/档、max 8 档/12 市场：234 lots、PnL **$1,344**、年化约 **$641**、77 个 tail 市场、0 tail 亏损；
  - $4,000、$500/档、max 8 市场：156 lots、PnL **$1,553**、年化约 **$740**、64 个 tail 市场；
  - $6,000：约 125 lots、PnL **$1,973**、年化约 **$941**、51 个 tail 市场。
- 关键结论：此前按单市场独立汇总的 $2,703/$3,061 无法在 $3k 账户同时持仓；组合约束下 $3k 的真实年化约 **$600–$650**，$4k 约 **$700–$740**。这就是为什么绝对收益看起来一直不够。
- 若要达到 $1,000+/年的历史水平，需要约 **$6k+** 资金，或提高风险/周转；单纯加杠杆式放大单档会造成并发资金不足和成交率下降。
- 下一步：保留 q1 Shadow 臂验证真实成交；同时把组合约束纳入 Gate 的收益展示，避免再用 per-market 汇总误导。
- 证据：`bond-portfolio-500-q1-cap3000-m6-20261021.json`、`bond-portfolio-250-q1-t4-cap3000-m12-20261021.json`、`bond-portfolio-500-q1-cap4000-m8-20261021.json`、`bond-portfolio-750-q1-cap6000-20261021.json`。
### 20.52 2026-10-21 16:02 低价格带动量过滤仍不能复刻

- 按目标账户 24h 动量 `0..+1c` 的强子集，测试 0.5–0.8 Geopolitics、6 档、+4c、q1、$500、24h flatten：
  - 271 lots、fill rate 69.8%、mean ROI **-23.67%**、24/24 tail 市场全部亏损。
- 0.8–0.9 同样为负（-12.70%）；0.9–0.95 为 -2.13%。
- 说明：目标账户在 0.5–0.8 的 +1.9% 收益不是“价格带 + 动量过滤 + 被动 ladder”能复刻的，必须有更强的事件选择或主动退出。我们不会为了追求年化把资金投入到这类未验证的负期望策略。
- 结论：当前小资金最稳的仍是 0.97–0.99 Geopolitics；要提高年化只能增加并发资金/市场数或寻找新的 alpha，不能靠放宽价格带。
### 20.53 2026-10-21 16:10 目标账户选品特征分析（train/test）

- 新增 `scripts/analyze-leader-selection.cjs`，用 390 个已平仓头寸做时间切分：前 70% train、后 30% test，特征包括价格带、类别、outcome、买入规模。
- 基线：train 273 笔 ROI 0.469%、win 67.0%；test 117 笔 ROI 0.618%、win 83.8%。
- 同时在 train/test 都为正且样本足够的规则：
  - `band=0.97–0.99 & size>=2k`：train 21 笔 ROI **0.812%**、win 100%；test 24 笔 ROI **0.805%**、win 91.7%。
  - `category=geopolitics & size>=2k`：train 40 笔 ROI 0.510%、win 80%；test 62 笔 ROI 0.776%、win 91.9%。
  - `band=0.99–1.0 & category=geopolitics`：train 20 笔 ROI 0.378%；test 18 笔 ROI 0.512%。
- 没有任何低价格带静态规则在 train/test 同时为正；0.5–0.8、0.8–0.9 的利润无法由静态价格/类别/规模规则解释。
- 结论：目标账户最稳定的静态 edge 仍然是 **0.97–0.99 Geopolitics + 较大单笔**；这与我们的方向一致，但我们的成交容量受队列和资金限制。低价格带 alpha 是动态的，必须做主动退出/事件判断，不能靠静态筛选复刻。
- 证据：`leader-selection-analysis-20261021.json`。