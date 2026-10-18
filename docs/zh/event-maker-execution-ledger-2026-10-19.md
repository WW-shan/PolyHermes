# Event Maker 执行台账（2026-10-19）

> 最后更新：2026-10-19 06:57 +08:00
> 目的：把目标、计划、实际动作、命令、结果、证据和恢复步骤持续写入仓库，避免依赖聊天记忆。
> 当前状态：**进行中，不得标记完成**
> 权威计划：[event-maker-delivery-plan.md](event-maker-delivery-plan.md)
> 逐轮日志：[event-maker-work-log.md](event-maker-work-log.md)
> 运行手册：[event-maker-runbook.md](event-maker-runbook.md)
> 正式回测：[event-maker-robust-backtest-2026-10-19.md](event-maker-robust-backtest-2026-10-19.md)

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
| OBS-1 | 干净长测真实盘口 Shadow | 等待候选发现修复 | 先排除 Gamma 分页/候选截断导致的假空窗 |
| DISC-1 | 修复 Gamma 市场发现分页与候选截断 | 进行中 | 分页抓取、价格带提前过滤、阶段计数和回归测试 |
| BT-1 | 审核 1,000 市场稳健回测样本、分布和异常 | 已完成 | 正式报告、full/full-v2 CSV |
| BT-2 | 费用、队列和年份样本外敏感性测试 | 已完成 | `event-maker-queue-grid-full-v2-20261019-fee-sensitivity.csv`、正式报告 |
| BT-3 | 扩大到完整候选池，验证零结算亏损是否偶然 | 已完成 | 4,187 市场、864 配置、full/full-v2 CSV |
| CODE-1 | 审查报价、队列保留、诊断、过滤器和 Shadow 成交模型 | 已完成 | 代码修改、定向测试、全量 445 tests |
| DOC-2 | 更新计划、日志、运行手册和正式报告 | 已完成 | 文档 diff、`git diff --check` |
| TEST-1 | event-maker/research 定向测试和全量测试 | 已完成 | 95 suites / 445 tests / 0 failures / 2 skipped |
| GIT-1 | 提交并推送全部已验证改动 | 已完成 | `06d168f`、`main -> main` |
| OBS-2 | 分析 Shadow 完成结果 | 部分完成 | 10min/1min 冒烟通过；旧 2h/12h 无效或已停止 |
| LIVE-1 | 真实小资金 Live smoke | 阻塞 | 需要 `accountId`、资金上限、明确授权、时间与市场范围 |

## 3. 最近后台进程与状态

| 进程 | 会话 ID | 开始时间 | 预计结束 | 输出目录 | 当前状态 |
|---|---:|---|---|---|---|
| 旧被动入场 2h/12h | 47525 / 58042 | 04:50 / 04:52 | 已结束 | `event-maker-shadow-passive-2c-*` | 旧 2h 收尾类冲突无效；旧 12h 已停止；均 0 fill |

轮询方式：使用统一执行会话 `write_stdin`，不要重复启动同名 Shadow。

已选市场与初始挂单：

| 市场 | BUY 价 | 初始队列 |
|---|---:|---:|
| Israel x Iran ceasefire continues through October 21 | 0.960 | 0 |
| Xi Jinping out before 2027 | 0.963 | 0 |
| Will China invade Taiwan by end of 2026 | 0.968 | 0 |

截至 06:52：旧 2h 收尾类冲突无效，旧 12h 已停止；新版 10min/1min 冒烟通过但 0 fill；当前无可交易的 0.97～0.99 市场。

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