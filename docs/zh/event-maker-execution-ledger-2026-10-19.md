# Event Maker 执行台账（2026-10-19）

> 最后更新：2026-10-20 00:00 +08:00
> 目的：把目标、计划、实际动作、命令、结果、证据和恢复步骤持续写入仓库，避免依赖聊天记忆。
> 当前状态：**进行中，不得标记完成**
> 权威计划：[event-maker-delivery-plan.md](event-maker-delivery-plan.md)
> 逐轮日志：[event-maker-work-log.md](event-maker-work-log.md)
> 运行手册：[event-maker-runbook.md](event-maker-runbook.md)
> 正式回测：[event-maker-robust-backtest-2026-10-19.md](event-maker-robust-backtest-2026-10-19.md)
> 稳健性核验：[event-maker-robustness-crosscheck-2026-10-19.md](event-maker-robustness-crosscheck-2026-10-19.md)
> 正式候选：[event-maker-safe-candidate-2026-10-19.md](event-maker-safe-candidate-2026-10-19.md)
> Live 验证：[event-maker-live-execution-validation-2026-10-19.md](event-maker-live-execution-validation-2026-10-19.md)

## 1. 当前目标

> 开始按照计划全部完成，直到回测和实盘策略都测试完全没有问题为止。

完成边界：

1. 策略、回测、Shadow、Live mock 和文档可复现。
2. 历史回测通过样本外、费用和队列敏感性审查。
3. Shadow 至少产生可解释的真实盘口 fill、cycle、markout 和 PnL。
4. 没有未知订单状态、重复成交、状态错乱或无法对账的问题。
5. 真实小资金 Live smoke 只有在账户、资金和用户明确授权齐备后才执行。
6. 任何一项未满足，都不能称为“实盘策略完全没有问题”。

## 2. 当前任务队列

| ID | 任务 | 状态 | 验收证据 |
|---|---|---|---|
| DOC-1 | 建立可恢复执行台账，记录计划、动作和证据 | 已完成 | 本文件、计划、工作日志 |
| OBS-1 | 干净长测真实盘口 Shadow | 运行中 | v4 wrapper 21352 / Java 17312；00:03 启动；当前 waiting |
| DISC-1 | 修复 Gamma 市场发现分页与候选截断 | 已完成 | keyset 分页、价格带预筛、候选诊断、5 个测试 |
| BT-1 | 审核 1,000 市场稳健回测样本、分布和异常 | 已完成 | 正式报告、full/full-v2 CSV |
| BT-2 | 费用、队列和年份样本外敏感性测试 | 已完成 | `event-maker-queue-grid-full-v2-20261019-fee-sensitivity.csv`、正式报告 |
| BT-3 | 扩大到完整候选池，验证零结算亏损是否偶然 | 已完成 | 4,187 市场、864 配置、full/full-v2 CSV |
| CODE-1 | 审查报价、队列保留、诊断、过滤器和 Shadow 成交模型 | 已完成 | 代码修改、定向测试、全量 445 tests |
| DOC-2 | 更新计划、日志、运行手册和正式报告 | 已完成 | 文档 diff、`git diff --check` |
| TEST-1 | event-maker/research 定向测试和全量测试 | 已完成 | 95 suites / 445 tests / 0 failures / 2 skipped |
| GIT-1 | 提交并推送全部已验证改动 | 已完成 | `06d168f`、`main -> main` |
| OBS-2 | 分析 Shadow 完成结果 | 部分完成 | 10min/1min 冒烟通过；旧 2h/12h 无效或已停止 |
| SAFE-1 | 状态文件、账户级订单/持仓、孤儿和停止对账加固 | 已完成 | 97 suites / 475 tests；Live 验证报告 |
| BT-4 | 标签/价格桶 × 期间、cluster bootstrap、参数选择脆弱性 | 已完成 | 交叉核验报告、分析脚本 |
| GIT-3 | 提交并推送本轮安全修复与文档 | 已完成 | `596504e fix: harden event maker live state recovery`、`main -> main` |
| OBS-4 | 正式候选 Shadow 长测 | v4 运行中 | wrapper 21352 / Java 17312；严格门槛等待中 |
| RISK-DAY-1 | 日亏损锚点跨重启持久化 | 已完成 | UTC day + baseline 写入 state v3；103 suites / 538 tests 通过 |
| LIVE-1 | 真实小资金 Live smoke | 阻塞 | 需要 `accountId`、资金上限、明确授权、时间与市场范围 |

## 3. 最近后台进程与状态

| 进程 | PID | 开始时间 | 预计结束 | 输出目录 | 当前状态 |
|---|---:|---|---|---|---|
| 正式候选 Shadow v4 | 21352 / 17312 | 00:03 | 等待最多 720m；选出后 Shadow 720m | `backend/build/research/event-maker-safe-candidate-shadow-main-v4-20261020/` | waiting；首两轮 qualified=1、activityPassed=0、selected=0；日志无异常 |
| 正式候选 Shadow v3 | 23852 / 20988 | 23:41 | 23:55 主动中止 | `backend/build/research/event-maker-safe-candidate-shadow-main-v3-20261019/` | 5 轮 qualified=2、activityPassed=0、selected=0；不作为最终证据 |
| 正式候选 Shadow v2 | 5600 / 8300 | 22:51 | 23:20 主动中止 | `backend/build/research/event-maker-safe-candidate-shadow-main-v2-20261019/` | 29m、2 次挂单、1 次 requote、0 fill；不作为最终证据 |
| v5 等待+Shadow 主臂 | 25560 | 09:32 | 等待最多 720m；选出后 Shadow 720m | `backend/build/research/event-maker-shadow-watch-v5-20261019/` | waiting；严格参数；窗口按 time-to-fill 分布延长 |
| 一档价差实验臂 | 15356 | 09:32 | 同上 | `backend/build/research/event-maker-shadow-experiment-spread1-v2-20261019/` | waiting；唯一变量 `minSpreadTicks=1`；不混入正式验证 |
| v3 等待+Shadow 任务 | 6296 / 7788 | 09:06 | 09:10 已停止 | `backend/build/research/event-maker-shadow-watch-v3-20261019/` | 因持仓数量精确对账修复停止 |
| v2 等待+Shadow 任务 | 7652 / 14836 | 08:05 | 08:18 已停止 | `backend/build/research/event-maker-shadow-watch-v2-20261019/` | 12 次发现均为 0 活动通过；因安全修复停止 |
| 旧被动入场 2h/12h | 旧会话 | 04:50 / 04:52 | 已结束 | `event-maker-shadow-passive-2c-*` | 历史记录；2h 收尾类冲突无效；12h 已停止；均 0 fill |

轮询方式：运行 `.\scripts\check-event-maker-watch.ps1`；不要重复启动同一输出目录的 Shadow。

历史旧运行的市场与初始挂单（仅供追溯）：

| 市场 | BUY 价 | 初始队列 |
|---|---:|---:|
| Israel x Iran ceasefire continues through October 21 | 0.960 | 0 |
| Xi Jinping out before 2027 | 0.963 | 0 |
| Will China invade Taiwan by end of 2026 | 0.968 | 0 |

截至当前：新的后台任务正在等待严格条件市场；旧运行的市场列表不再代表当前挂单。

## 4. 本轮动作记录

### 05:01 恢复与基线核对

- 读取目标状态，确认目标仍为 active。
- 轮询会话 47525、58042，两个 Shadow 进程均存活。
- 执行 `git status --short`、`git diff --check`、`git log -5 --oneline`。
- 当前分支 `main`，最后提交 `179f2b2 docs: finalize event maker execution ledger`。
- 发现工作日志有反引号加 n 的字面量，计划检查点仍停留在 03:07；后续已修复。

### 05:07 扩大回测样本

- 发现 1,000 市场候选配置的 41 个回合全部 SELL、零结算亏损，存在明显样本偶然性。
- 启动完整候选池回测：4,187 市场、864 配置。
- 输出：`backend/build/research/event-maker-queue-grid-full-20261019-*`。

### 05:19 完整池结果

- 4,187/4,187 市场成交历史抓取成功，失败 0。
- 样本内最高总 ROI 配置 `p0.97-0.99_off1.0_spread2.0_q1.0_c1_r0`：
  - 可交易 450、成交 179、SELL/SETTLE 177/2。
  - 总 PnL +164.99、ROI +0.92%、训练 +0.74%、测试 +1.06%。
  - 2 次结算亏损，每次 -100。
- 训练期唯一选参 `p0.97-0.99_off0.5_spread2.0_q1.0_c3_r0`：
  - 成交市场 240、回合 278，SELL/SETTLE 266/12。
  - 总 PnL +224.37、ROI +0.81%、训练 +1.10%、测试 +0.56%。
- 队列压力：off1/q1 配置 q1/q10/q20/q50 的 ROI 为 +0.92%/+0.41%/+0.006%/-0.77%。

### 05:25 方法修正与 Shadow 成交模型修正

- 回测参数选择改为只用训练期，删除测试集选参泄漏。
- 最大回撤改为按回合退出时间计算。
- 增加最大浮亏、time-to-fill、60 秒 markout、结算亏损数、盈亏平衡亏损率。
- 增加 0/10/25/50/100bps maker fee 敏感性输出。
- Shadow 新增 `shadowFillVolumeFactor`，默认 1；0 仅保留为旧乐观对照。
- Shadow markout 扩展为 60/300/1800 秒，并记录盘口年龄。
- tick spread 计算改用 HALF_UP，避免浮点边界误判。
- 修复 `factor=0` 旧部分成交语义的测试回归。

### 05:32 修正后完整池复跑完成

- 输出：`backend/build/research/event-maker-queue-grid-full-v2-20261019-*`。
- 训练期选参配置仍是 `p0.97-0.99_off0.5_spread2.0_q1.0_c3_r0`。
- 278 回合中 3 次结算亏损；最大已实现回撤 146.49 USDC。
- 平均最大浮亏 -6.99 USDC，P10 最大浮亏 -11.35 USDC。
- 60 秒 markout：278 个有效值，平均 +0.392 USDC，21 个为负。
- 50bps 双边 maker fee 下总 ROI -0.18%、测试 ROI -0.43%。
- 全量测试：95 suites / 445 tests / 0 failures / 0 errors / 2 skipped。
- CLI 冒烟发现 `printSummary` 参数顺序错位并修复；20 市场冒烟通过。
- 代码审查发现 `@Component` 误标在 `EventMakerShadowFill`，已移到 `EventMakerShadowExecutionPort` 并增加回归测试。
- 10 分钟保守 Shadow 冒烟发现退出时活跃订单未撤；修复后 1 分钟复测 `activeOrders=0`、`CANCELLED`、`FLAT`、0 错误。

## 5. 关键命令

```powershell
cd D:\Project\PolyHermes
git status --short
git diff --check
git log -3 --oneline
```

```powershell
cd D:\Project\PolyHermes\backend
.\gradlew.bat test --console=plain
```

```powershell
cd D:\Project\PolyHermes\backend
.\gradlew.bat eventMakerBacktest `
  "-PresearchProxy=http://127.0.0.1:10808" `
  "-PresearchMaxMarkets=5000" `
  "-PresearchMinVolume=100000" `
  "-PresearchFrom=2024-01-01" `
  "-PresearchTo=2026-10-01" `
  "-PresearchConcurrency=6" `
  "-PresearchOutput=build/research/event-maker-queue-grid-full-v2-20261019" `
  --console=plain
```

## 6. 证据与提交规则

1. 每个任务结束必须写实际命令、结果摘要和证据路径。
2. 代码行为变化必须补测试，并记录测试命令与失败/修复过程。
3. 不在没有真实 fill 和可解释 PnL 时宣称策略可实盘。
4. 不在没有账户、资金和明确授权时真实下单。
5. 提交前必须检查 `git diff --check`、全量测试和工作区状态。
6. 临时文件、无用容器、无用数据库和一次性环境必须在使用后清理并记录。

## 7. 恢复顺序

1. 读本文件第 2、3、4 节。
2. 读 `event-maker-delivery-plan.md` 的当前检查点和下一步。
3. 读 `event-maker-work-log.md` 末尾。
4. 执行 `git status --short`、`git diff --check`、`git log -3 --oneline`。
5. 轮询已有 Shadow 会话，禁止重复启动。
6. 从任务队列第一个非阻塞未完成项继续。

### 06:10 提交与推送

- 提交：`06d168f feat: harden event maker shadow and full-pool validation`。
- 推送：`origin/main`，`179f2b2..06d168f main -> main`。
- 全量测试：95 suites / 445 tests / 0 failures / 0 errors / 2 skipped。
- 当前仅剩真实盘口 Shadow 填充验证和需要用户授权的 Live smoke。

### 06:52 长测失效处理

- 旧 2h 在结束 diagnostics 时 `NoSuchMethodError`，无有效 summary；旧 12h 已停止。
- 最终代码 10min/1min 冒烟通过，退出 activeOrders=0。
- 当前 0.97～0.99 无可交易候选，唯一活跃 candidate 为 0.95；未降低价格带。
- 当前没有运行中的 Shadow。
- 后续干净长测启动后禁止重编译/跑测试。

### 06:57 建立本轮可恢复计划与动作记录

- 用户要求：先把计划、行为动作、命令、结果和证据写入仓库文档，不能依赖聊天记忆。
- 恢复核对：读取目标，目标仍为 active；执行 `git status --short`、`git diff --check`、`git log -5 --oneline`；工作区干净，`git diff --check` 无输出，最新提交 `5994b4c docs: record shadow validation limits`。
- 进程核对：执行 `Get-CimInstance Win32_Process -Filter "Name='java.exe'"`；PID 18348 为 Kotlin compile daemon、PID 21120 为 Gradle daemon；没有 eventMakerShadow/eventMakerBacktest 进程。
- 文档核对：已读执行台账、交付计划、运行手册和工作日志尾部；确认当前正式证据、旧长测失效原因、Shadow 冒烟结果和 Live smoke 授权边界。
- 根因假设：`LivePaperDataClient.discoverMarkets()` 每个 tag 只抓一页 100 条，按 24h volume 取前 40 个后才做活跃度和价格带筛选；可能把真正符合 0.97～0.99 的活跃市场截掉。
- 本轮计划：1) 先记录计划和动作；2) 为 Gamma 发现增加分页和阶段诊断；3) 将价格带/资格过滤前移，避免先截断 40 个；4) 增加可注入数据源测试；5) 定向与全量测试；6) 检查真实候选；7) 仅在合格市场出现时启动干净 Shadow；8) 分析 fill/cycle/markout/PnL；9) 整理证据、文档、清理临时产物并提交；10) 未经账户、资金上限和明确授权不执行真实下单。
- 安全边界：不降低价格带、不放宽 post-only、不改变风控来制造 fill；长 Shadow 启动后禁止重编译或跑测试。
### 07:15 候选发现修复与定向测试

- 已修改：`LivePaperDataClient` 分页扫描、价格带预筛、去掉前 40 截断、活动阈值短路、发现阶段统计和可注入 base URL。
- 已修改：Shadow 输出 `market-discovery.json`，`summary.json` 增加 `marketDiscovery`；Gradle 增加 `shadowDiscoveryPages`；Spring 策略服务传入价格带。
- 已新增：`LivePaperDataClientDiscoveryTest` 4 个测试，覆盖分页、价格带截断、活动过滤和页数上限诊断。
- 已执行：`.\gradlew.bat test --tests "com.wrbug.polymarketbot.research.livepaper.*" --tests "com.wrbug.polymarketbot.research.eventmaker.*" --tests "com.wrbug.polymarketbot.service.eventmaker.*" --console=plain`。
- 结果：BUILD SUCCESSFUL；定向测试通过，包含新测试 4/4。
- 下一步：全量测试 → 提交 → 真实网络短 Shadow 发现 → 分析候选和盘口 → 长测。
### 07:17 全量测试

- 命令：`.\gradlew.bat test --console=plain`。
- 结果：96 suites / 449 tests / 0 failures / 0 errors / 2 skipped。
- 证据：`backend/build/test-results/test/`。
### 07:26 keyset 分页与真实候选结果

- 官方 OpenAPI 确认 `/markets/keyset` + `after_cursor`；传统 `/markets` 的 offset 在约 2,000 条后失败，已切换为 keyset。
- 发现输出：`backend/build/research/event-maker-shadow-keyset-v3-20261019/market-discovery.json`。
- 真实结果：Politics 1 页/100 条、Geopolitics 1 页/100 条；两标签均到达 `volume_floor_reached`，在 100,000 USDC 24h 成交额门槛以上已完整扫描。
- 候选：价格预筛 6 个，活动过滤检查 6 个，通过 1 个；唯一通过者价差 1 tick，被 `spread_below_min` 拒绝。
- 结论：当前严格条件下没有可报价市场；不是发现截断。禁止降低价格带、活动门槛或 `minSpreadTicks` 来制造 fill。
- 测试：5/5 发现测试通过；全量 96 suites / 450 tests / 0 failures / 0 errors / 2 skipped。
- 当前没有运行中的 Shadow 或 Backtest 进程。
### 07:29 提交 keyset 修复

- 提交：`7b0bf7b feat: use keyset pagination for market discovery`。
- 推送：`origin/main`，`a2e14c7..7b0bf7b main -> main`。
- 全量测试：96 suites / 450 tests / 0 failures / 0 errors / 2 skipped。
- 当前无 Shadow/Backtest 进程；等待满足严格条件的真实市场。
### 07:32 清理记录

- 仅清理 `backend/build/research/` 内 9 个失效/重复目录，逐项校验绝对路径位于研究输出根目录。
- 保留 keyset v3 诊断、v2 Shadow 冒烟和完整池/费用敏感性正式证据。
- 本次没有删除源码、正式报告、测试结果或 Git 跟踪文件。
### 07:36 自动等待模式计划

- 目标：避免手动反复启动短 Shadow；增加等待合格市场的正式模式。
- 新参数：`shadowWaitForMarketMinutes`、`shadowDiscoveryRetrySeconds`。
- 新证据：`discovery-attempts.jsonl`、最新 `market-discovery.json`/`market-selection.json`、超时 `market-watch-timeout.json`。
- 启动合格市场后才进入原有 Shadow 引擎；等待阶段不下单、不连接私有账户。
### 07:43 自动等待模式完成

- 新增参数：`shadowWaitForMarketMinutes`、`shadowDiscoveryRetrySeconds`。
- 新增证据：`discovery-attempts.jsonl`、`market-watch-timeout.json`；summary 增加 `discoveryAttempts`。
- 实际冒烟：0 分钟等待 1 次尝试；1 分钟等待/15 秒重试完成 5 次尝试，均无活动通过候选并正确超时。
- 冒烟目录：`event-maker-shadow-wait-smoke-20261019`、`event-maker-shadow-wait-loop-smoke-20261019`。
- 全量测试：96 suites / 450 tests / 0 failures / 0 errors / 2 skipped。
- 下一步：提交后启动长等待+Shadow，持续检查证据；等待期间禁止重编译。
### 07:50 后台等待与 2 小时 Shadow 启动计划

- 任务：最多等待 180 分钟，60 秒重试；选出市场后运行 120 分钟 Shadow。
- 证据目录：`backend/build/research/event-maker-shadow-watch-20261019/`。
- 约束：仅公共数据，不真实下单；进程运行期间禁止编译和测试。
- 完成后按 `summary.json`、`execution-diagnostics.json`、`markouts.csv`、`state.json` 和 attempt 日志逐项验收。
### 07:47 后台等待任务进程

| 项目 | 值 |
|---|---|
| 包装进程 | PowerShell PID 3764 |
| Gradle 启动进程 | cmd.exe PID 10628 |
| JavaExec Shadow | java.exe PID 24888 |
| 输出目录 | `backend/build/research/event-maker-shadow-watch-20261019/` |
| 等待上限 | 180 分钟 |
| 重试间隔 | 60 秒 |
| 选出后 Shadow | 120 分钟 |
| 首次结果 | qualified=6, activityPassed=0, selected=0, volume_floor_reached |

恢复规则：先检查 PID 3764/24888 和 `watch-process.json`，再读 `discovery-attempts.jsonl`、`stdout.log`、`stderr.log`。进程运行期间禁止编译和测试。
### 08:04 一档价差过滤差异记录

- `spread` 回测字段是目标卖出价差，不是实时 `minSpreadTicks`。
- 当前保留 `minSpreadTicks=2`；一档市场仅作为未来独立实验臂，不用于本轮正式 fill 证据。
### 08:10 长等待停止与 attempt 审计增强

- 停止 PID 3764/10628/24888；停止前 15 轮均无活动通过候选，无 fill、无真实订单。
- 新增 `activityRejectionReasons` 到每轮 attempt 记录；真实 0 分钟冒烟显示 `recent_trades_30m_below_min=6`。
- 全量测试 96 suites / 450 tests / 0 failures / 0 errors / 2 skipped。
- 下一步：提交后重启后台等待任务。
### 08:05 重启增强后的后台等待任务

- 新输出目录：`backend/build/research/event-maker-shadow-watch-v2-20261019/`。
- 包装进程：PowerShell PID `7652`；JavaExec：java.exe PID `14836`。
- 首轮已记录 `qualified=6 activityPassed=0 selected=0 activityRejections={recent_trades_30m_below_min=6}`。
- 参数仍为严格条件：0.97～0.99、24h >=100,000、30m/2h 活动门槛、`minSpreadTicks=2`、成交因子 1。
- 进程运行期间禁止编译和测试；轮询使用 `.\scripts\check-event-maker-watch.ps1`。
### 08:18-08:46 状态恢复安全加固记录

- 08:18 停止只读等待任务 PID 7652/14836；停止前 12 轮发现，最近一轮 qualified=6、activityPassed=0、selected=0，原因为 `recent_trades_30m_below_min=6`。
- 修复状态读取：损坏 JSON、空文件、版本不支持、错账户、重复 condition、缺失 favorite token、非法订单/库存均 fail-closed。
- 新增账户级启动对账：活跃订单必须全部在状态文件中；正数持仓 conditionId 必须与状态库存集合一致；同 condition 多资产拒绝。
- 新增孤儿恢复：孤儿订单撤销并确认终态无成交；孤儿库存或无法确认状态阻止启动。
- 新增停止对账：撤单后读取最终订单快照，部分成交先写入库存/cycle；无法确认时保留 activeOrder。
- 修复部分卖出 cycle 记账：跨多个卖出订单累计 shares 和 PnL。
- 新增 favoriteTokenId 持久化和 fill ledger 重放校验。
- 风险预算计入未成交买单；协程作用域改为普通 Job，持久化/对账失败会终止整个 LIVE 运行。
- 新增稳健性交叉核验脚本和报告；全部市场、训练、测试、自然月 cluster bootstrap 95% 区间均包含 0。
- 全量测试：97 suites / 475 tests / 0 failures / 0 errors / 2 skipped。
- 已提交并推送 `596504e fix: harden event maker live state recovery`（`6bd6850..596504e main -> main`）。
- 当前无 Shadow/Backtest 进程。下一步：重启新代码等待任务 → 分析 Shadow evidence → 处理真实 Live smoke 阻塞项。
### 10:2x-10:40 REST 冒烟、stop 修复与两臂重启（69649e5）

| 项目 | 值 |
|---|---|
| MySQL | Docker 容器 `polyhermes-mysql`（mysql:8.2，127.0.0.1:3306，库 polyhermes） |
| Spring | java PID `22676`，dev profile，端口 8000，`--event-maker.proxy-url=http://127.0.0.1:10808` |
| Flyway | 50 条迁移全部成功，31 张表 |
| REST 验证 | 登录 → start SHADOW → status → stop 全链路通过；stop 实测 20ms（修复前 >60s 超时） |
| 修复提交 | `69649e5 fix: make event maker stop abort in-flight network calls and honor system proxy`（已推送） |
| 全量测试 | 98 suites / 478 tests / 0 failures / 0 errors / 2 skipped |
| 主臂 | Java PID `26412`（wrapper 4640），spread=2，输出 `event-maker-shadow-watch-v5-20261019/`，等待中（`spread_below_min=1`） |
| 实验臂 | Java PID `17596`（wrapper 19668），spread=1，输出 `event-maker-shadow-experiment-spread1-v2-20261019/`，已选中 1 个市场、WS_OPEN、挂出 Shadow 买单 25.74 @0.971 |

恢复规则：先看 `watch-process.json` 与 `stdout.log`；实验臂若产生 fill/cycle，运行 `.\scripts\audit-event-maker-shadow.ps1 -OutputDir <目录>`。运行期间禁止编译/测试。

### 10:45-10:55 REST 路径 LIVE 负向验证（fail-closed）

| 用例 | 请求 | 实际结果 |
|---|---|---|
| LIVE 未显式开启 | `mode=LIVE, accountId=999` | `live_trading_disabled`，未启动 |
| LIVE 缺账户 | `mode=LIVE, liveTradingEnabled=true` | `live_account_required`，未启动 |
| LIVE 账户不存在/无凭证 | `mode=LIVE, liveTradingEnabled=true, accountId=999` | `account_not_found_or_credentials_missing`，未启动 |

- 三次请求后 `status.running=false`，未产生任何订单。
- SHADOW 通过 REST 全链路验证：discovery 走代理成功（qualified=6、activityPassed=1）、start/status/stop 正常、stop 20ms 返回。
- 真实 Live smoke 仍等待用户提供：可用 `accountId`、资金上限、明确下单授权、时间窗口、市场范围。

### 17:3x-17:5x 实盘执行适配器补漏（可恢复入口）

本轮在 Shadow 等待期间审计真实 CLOB 执行路径，已将以下问题纳入代码和回归测试：

1. 官方单笔撤单为 `DELETE /order` + `{"orderID":"..."}`，不是 `DELETE /orders/{id}`；成功必须由 `canceled` 精确确认，`not_canceled` 视为失败。
2. CLOB 分页初始游标 `MA==`、终止游标 `LTE=`；活跃订单遍历必须在 `LTE=` 停止。
3. 撤单与部分成交存在竞态：撤单后必须读取最终订单快照并先记账，不能直接清空 activeOrder。
4. 下单 2xx 无 body、成功无 orderID、或拒绝响应带 orderID 均属于结果不确定，必须停机并保留恢复线索，不能盲目重试。
5. 签名异常必须在适配器内转换成 Result，不能逃逸到事件循环。
6. 状态集合补齐 CLOB V2：`LIVE`、`INVALID`、`CANCELED_MARKET_RESOLVED`、`CANCELED`、`MATCHED`，并兼容 `unmatched`、小写响应和旧状态。
7. 快照必须核对 proxy maker、conditionId、asset/token；`MATCHED` 但未满额时 fail-closed。
8. 重启后的每日亏损基线必须重新锚定，不能把历史累计亏损算进当天。
9. 下单/撤单确认失败增加 5 秒退避，避免每个 WS 事件放大 REST 请求。
10. Shadow 快照补齐 token/condition/maker 字段；孤儿订单即使撤单返回 `not_canceled`，仍以最终订单快照确认终态。

验证：

- 全量测试：`100 suites / 513 tests / 0 failures / 0 errors / 2 skipped`。
- 提交并推送：`05277a3 fix: harden live CLOB execution and order recovery`、`37474ec docs: record live CLOB execution audit`（`1c9664c..37474ec main -> main`）。
- Spring：PID `24064`，dev profile，端口 8000，健康检查 200。
- 最新代码重启后二次 REST 冒烟：start/status/stop 成功，`stop=16ms`，未产生订单。
- REST SHADOW 冒烟：start → status → stop 成功；选中 `US x Iran ceasefire continues through October 22?`，Shadow 报价 `BUY 0.95 shares=1.05`；stop `95ms`，无错误。
- 两臂已用本轮代码重启：主臂 Java PID `25924`（wrapper `20468`，spread=2）；实验臂 Java PID `25556`（wrapper `22844`，spread=1）；`watch-process.json` 已更新。
- 本轮没有真实 Live 下单；仍缺用户授权、`accountId`、资金上限、时间窗口和市场范围。

恢复规则：若继续 Shadow，先看对应 `watch-process.json` 与 `stdout.log`；运行期间禁止编译/测试。若进入 Live，必须先确认上述快照/撤单路径在真实账户上通过小资金 smoke，再讨论扩大资金。

### 18:00-19:20 逆势流 Guard 与尾部因果审计

| 项目 | 结果 |
|---|---|
| Guard 默认参数 | 3,600 秒；signed flow <= -0.4；价格跌幅 >= 0.5 美分 |
| 触发行为 | 撤 BUY、Guard 期间禁止新 BUY；SELL 退出不受影响 |
| 回测无未来函数 | 已挂订单仅使用当前打印之前的成交判断，避免同笔成交事后过滤 |
| 全量测试 | 101 suites / 525 tests / 0 failures / 0 errors / 2 skipped |
| 全量 A/B | 4,189 市场、864 配置；回合 134,216 -> 96,105；正 PnL 配置 101 -> 138 |
| 尾部结果 | 单市场亏损 <= -99 的配置 864 -> 845；主配置结算亏损 3 -> 2 |
| 历史三笔归零 | China-Vietnam 被拦截；Iran May 25 和 US–Iran Aug 31 仍各亏 100 |
| 主配置 | `p0.97-0.99_off0.5_spread2.0_q1.0_c3_r0` PnL +224.37 -> +275.66，测试 ROI +0.560% -> +0.397% |
| Shadow 冒烟 | 5 分钟、1 市场、201 唯一成交、1 报价、1 撤单、0 fill、0 错误，审计 passed=true |
| 完整报告 | `docs/zh/event-maker-flow-guard-tail-audit-2026-10-19.md` |

当前 Shadow 两臂：

| 臂 | wrapper PID | Java PID | spread | 输出 |
|---|---:|---:|---:|---|
| 主臂 | 11600 | 26308 | 2 | `backend/build/research/event-maker-shadow-guard-main-v2-20261019/` |
| 实验臂 | 25864 | 22788 | 1 | `backend/build/research/event-maker-shadow-guard-spread1-v2-20261019/` |

正式参数首轮两臂均 qualified=2、activityPassed=0、selected=0；拒绝原因为 `recent_trades_2h_below_min` / `recent_trades_30m_below_min`；均 `logIssues=0`、`stderrBytes=0`。运行期间禁止编译/测试。

恢复与验证：

1. `.\scripts\check-event-maker-watch.ps1 -OutputDir <目录>`
2. 结束后运行 `.\scripts\audit-event-maker-shadow.ps1 -OutputDir <目录>`
3. 对比报告用 `.\scripts\compare-event-maker-flow-guard.ps1 -Baseline <前缀> -Guard <前缀>`

结论：Guard 是部分尾部防御，不是完整解决方案；当前不能实盘。真实 Live smoke 仍缺 `accountId`、资金上限、明确下单授权、时间窗口和市场范围。
- 提交并推送：`d768950 feat: add causal adverse-flow guard to event maker`（`c8fb838..d768950 main -> main`）。
- 正式参数两臂运行 5 轮仍无活动通过市场；主臂/实验臂均为 qualified=2、activityPassed=0、selected=0，最近拒绝原因 `recent_trades_30m_below_min=2`。
### 20:04-22:40 数据缓存、执行一致性与跨窗口候选复核

- 20:04 曾按旧候选参数启动一次 Shadow；完成首批发现后确认跨窗口证据不足，在任何订单产生前停止，仅留下等待日志。
- 新增 7 天研究数据集 `backend/build/research/event-maker-full-v2-7d-20261019-dataset.json.gz`，4,189 个市场。
- 抓取完整性：Gamma 月页失败不再静默跳过；单市场成交 3 次重试失败会中止整次抓取；数据集写入使用临时文件 + 原子替换。
- 回测一致性：补上 `maxOrderAgeSeconds=1800` 的买单/卖单撤单逻辑。旧回测允许挂单存活数天，导致 3d/6d 窗口出现虚假结算归零。
- 正式候选：`p0.97-0.99_off0.0_spread1.0_q20.0_c1_r300`。
- 12h/1d/2d/3d/4d/5d/6d/7d 八个窗口全部 0 结算亏损，ROI 范围 +1.033%..+1.164%，训练/测试均为正。
- 864 个配置中，8 窗口全部满足零结算亏损、正收益和训练成交 >=20 的只有 2 个；正式候选的最差窗口 ROI 最高。
- 24h 同数据 A/B：无 Guard 为 101 回合、1 次结算亏损、ROI +0.05%、测试 -0.39%；有 Guard 为 93 回合、0 次结算亏损、ROI +1.052%、测试 +1.058%。
- 24h 费用压力：50bps ROI +0.069%，100bps ROI -0.914%。
- 全量测试：`102 suites / 530 tests / 0 failures / 0 errors / 2 skipped`。
- 报告：`docs/zh/event-maker-safe-candidate-2026-10-19.md`。
- 尾部边界：24h 样本 93 个零亏损回合，rule-of-three 95% 上界约 3.23%，仍高于 1.041% 盈亏平衡线；约需 289 个零亏损独立回合才能接近证明门槛。
- Shadow 尚不能替代真实 Live smoke。真实账户、资金上限、明确下单授权、时间窗口和市场范围仍未提供。
### 22:51 正式候选 Shadow 运行

| 项目 | 当前值 |
|---|---|
| wrapper PID | `5600` |
| Shadow Java PID | `8300` |
| 输出 | `backend/build/research/event-maker-safe-candidate-shadow-main-v2-20261019/` |
| 首轮发现 | qualified=1、activityPassed=1、selected=1 |
| 市场 | `Israel x Iran ceasefire continues through October 21?` |
| 盘口 | bid `0.976` / ask `0.978` |
| Shadow 挂单 | BUY `0.976`，`25.61` shares，`shadow-1`，状态 `LIVE` |
| 真实下单 | 无；这是只读 Shadow |
| 日志 | `logIssues=0`、`stderrBytes=0` |

运行中禁止编译和测试。轮询：`.\scripts\check-event-maker-watch.ps1 -OutputDir "backend/build/research/event-maker-safe-candidate-shadow-main-v2-20261019"`。
### 22:55 提交与推送

- 提交：`93e4ccc feat: align event maker research with live execution`。
- 推送：`9fd75e5..93e4ccc main -> main`。
- 内容：数据快照/原子写入、严格抓取、回测最大挂单年龄一致性、8 窗口候选回测、参数邻域、Shadow 参数、全量 530 tests 和运行文档。
- 提交后 Shadow 继续运行，运行期间不重新编译。
- 提交后清理：Spring 冒烟 PID 24064 已停止；MySQL 容器 polyhermes-mysql 已停止但未删除，保留数据卷；当前只保留 Shadow wrapper 5600 / Java 8300。

- 23:06 修复监控脚本状态语义：state=running 现在表示已选中市场并处于挂单/库存阶段；state=discovering 表示已建立 state 文件但尚未选中市场。
- 当前实跑：markets=1、activeOrders=1、fills=0、cycles=0、inventoryShares=0、phase=BUY_PENDING；shadow-1 因 requote 撤单，shadow-2 BUY 0.975 / 25.64 shares。

- 23:13 审计脚本增加事件一致性：events placed/canceled 与 summary、diagnostics 交叉核对；通过临时 fixture 的正向和负向测试。

### 23:30 日亏损跨重启修复与 v2 Shadow 中止

- 审计发现 `maxDailyLoss` 虽已在单进程内按 UTC 日重置，但同日重启会把日预算基线重新设为当前累计 PnL，从而遗忘当天已发生亏损。
- 已在 state schema v3 新增 `dailyPnlDay` 和 `dailyPnlBaseline`：同日重启沿用锚点，跨日重锚到当前累计已实现 PnL。
- 对账任务、WS 报价 tick 和停止清理会在任何可能应用成交的操作之前同步日锚点，避免零点后的第一笔亏损被新日基线吞掉；持久化只写已确定锚点，缺失或非有限锚点 fail-closed。
- 新增 `EventMakerDailyPnlBudgetTest` 4 条测试；`EventMakerStateStoreTest` 增加缺失锚点、非有限 baseline 和 v3 round-trip 覆盖。
- 全量测试：103 suites / 538 tests / 0 failures / 0 errors / 2 skipped。
- v2 Shadow PID 8300 / wrapper 5600 于 23:20 主动停止；运行 29m、2 次 Shadow 挂单、1 次 requote、0 fill、无真实订单。强制停止使 Gradle 记录 exit -1，v2 产物不作为最终验收。
- 下一步：提交本修复，使用最终代码在新 v3 输出目录重启正式候选 Shadow。
### 23:42 v3 Shadow 启动

- 提交 `c39b00a` 已推送，最终代码启动 v3 Shadow。
- wrapper PID `23852`，Shadow Java PID `20988`，启动 `2026-10-19 23:41:45 +08:00`。
- 输出：`backend/build/research/event-maker-safe-candidate-shadow-main-v3-20261019/`。
- 首两轮发现：qualified=2、activityChecked=2、activityPassed=0、selected=0；拒绝原因 `recent_trades_30m_below_min=2`。
- 当前状态：waiting、logIssues=0、stderrBytes=0。未降低任何门槛。
### 23:55 对账停机、无买盘估值与时钟回拨收口

- 对账任务原先忽略 `applyEngineResult` 的 halted 返回值，安静市场可能在没有 WS 事件时继续运行。现改为关闭 feed channel，进入统一清理路径。
- 初始对账触发 engine halt 或日亏损时同样立即关闭 feed，不再继续等待事件。
- `markToMarket` 在无买盘时由“浮亏 0”改为按 bid=0 的最坏情况估值，避免日亏损上限被无买盘状态绕过。
- UTC 日锚点增加时钟回拨保护：持久化日大于当前日时保留未来锚点，不重置日亏损预算。
- 全量测试：103 suites / 538 tests / 0 failures / 0 errors / 2 skipped。
- v3 Shadow 于 23:55 主动停止；5 轮均 qualified=2、activityPassed=0、selected=0，未产生 Shadow 订单或真实订单。
### 00:04 v4 Shadow 启动

- 提交 `127dff3` 已推送，最终代码启动 v4 Shadow。
- wrapper PID `21352`，Shadow Java PID `17312`，启动 `2026-10-20 00:03:45 +08:00`。
- 输出：`backend/build/research/event-maker-safe-candidate-shadow-main-v4-20261020/`。
- 首两轮：qualified=1、activityPassed=0、selected=0；拒绝原因 `sell_trades_30m_below_min=1` / `recent_trades_30m_below_min=1`。
- 当前 waiting、logIssues=0、stderrBytes=0；未降低门槛。