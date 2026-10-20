# 同事件日期单调性套利研究（2026-10-20）

> 目标：验证同一事件中“较早截止日期”和“较晚截止日期”市场之间是否存在可执行的价格约束套利。  
> 范围：Politics + Geopolitics。  
> 当前结论：**修正语义和最优数量后，点快照没有正收益机会；禁止据此实盘。**

## 1. 研究结构

对同一事件中的两个二元市场：

- `E_early`：较早截止日期；
- `E_late`：较晚截止日期。

日期单调性可能有两种方向：

| 事件语义 | 单调关系 | 保证收益组合 |
|---|---|---|
| 正向发生事件，例如 “Will X happen by date?” | `E_late => E_early` | 买 `NO(E_early)` + `YES(E_late)` |
| 持续/否定事件，例如 “ceasefire continues through date” | `E_early => E_late` | 买 `YES(E_early)` + `NO(E_late)` |

正确选择方向后，组合在所有结果状态下至少支付 1 美元。只有当两腿可执行 ask 成本加手续费后小于 1 时，才是数学套利。

## 2. 扫描器

新增：

- `scripts/scan-event-calendar-spread-arbitrage.mjs`
- `scripts/test/scan-event-calendar-spread-arbitrage.test.mjs`

核心安全条件：

1. 两个市场必须在同一 event；
2. 问题去掉日期后文本完全一致；
3. 两份 resolution description 去掉日期后文本完全一致；
4. 必须存在明确的日期单调措辞；
5. 只纳入 active、未关闭、可下单市场；
6. 按 `endDate` 判断较早/较晚；
7. 根据问题语义判断正向或持续方向；
8. 使用逐档 ask 深度、官方 taker fee、0.01 share 精度、最小下单量、盘口年龄和 gas buffer；
9. 使用边际利润最优数量，而不是累计利润仍大于零的最大数量。

测试命令：

```powershell
cd scripts
npm run test:complete-set
```

当前完整 Node 测试集：**27 tests / 0 failures**。

## 3. 发现并修复的两个假信号

### 3.1 “on DATE”独立日市场被误认为嵌套日期

第一版把：

- `Houthi military action against Saudi Arabia on October 5?`
- `Houthi military action against Saudi Arabia on October 14?`

当作嵌套日期，产生了 17 个假机会。

问题：这是两个独立的单日事件，不是 `by/through` 的累积事件。

修复：

- 只接受 `by / before / through / until / no later than / end of` 等明确单调措辞；
- 单独的 `on DATE` 不构成日期单调关系。

### 3.2 持续事件方向被反向

第二版把：

- `ceasefire continues through October 31?`
- `ceasefire continues through November 15?`

当成“越晚越容易发生”的正向事件，产生了 6 个假机会。

实际语义：

- 后一个市场是前一个市场的子集；
- 正确组合是 `YES(early) + NO(late)`；
- 不是 `NO(early) + YES(late)`。

修复：

- 问题文本优先识别持续/否定语义；
- resolution description 解析只在语义不明确时作为辅助；
- 明确区分 `E_late => E_early` 与 `E_early => E_late`；
- 增加正反向单元测试和 token 选择测试。

## 4. 最终点快照

最终产物：

- `backend/build/research/calendar-spread-20261020/scan-v8.json`
- `backend/build/research/calendar-spread-20261020/scan-v8.csv`

参数：

```text
tags=politics,geopolitics
minVolume24h=0
maxBookAgeMs=30000
minEdgeCents=0
minProfitUsd=0
gasUsd=0.02
maxPairs=5000
concurrency=4
```

结果：

| 指标 | 数值 |
|---|---:|
| 发现市场 | 1,735 |
| 事件 | 721 |
| 日期单调 pair | 327 |
| 完整评估 | 327 |
| 正收益套利 | 0 |
| 最低 top ask sum | 1.015 |
| 30 秒外盘口 | 101 |
| request error | 0 |

### 结论

- 当前快照所有日期单调 pair 的可执行 ask 合计都高于 1；
- 最低也约为 1.015，尚未扣除手续费和 gas；
- 因此没有任何可执行日期价差套利。

## 5. 重要限制

1. 当前只覆盖 Politics + Geopolitics。
2. 日期和语义识别依赖问题/description 文本；新措辞可能需要增加规则和人工复核。
3. 只做点快照，未做 24 小时连续监控。
4. 两腿非原子，一腿成交一腿失败仍会产生方向性暴露。
5. 资本需要持有到较晚市场结算，存在资金占用和 resolution 风险。
6. 不能把中间版本 v4/v6 的 6–7 个“机会”当结果，那些是持续事件方向错误导致的假信号。

## 6. 下一步

1. 先完成完整集/NegRisk 的 24 小时监控。
2. 若完整集监控仍无法稳定越过 0.5c，再启动日期单调 pair 的 24 小时监控。
3. 扩展到 Crypto/Finance 中明确的日期阈值市场。
4. 对任何重新出现的机会，人工核对 resolution rules 后才进入 mock 执行。