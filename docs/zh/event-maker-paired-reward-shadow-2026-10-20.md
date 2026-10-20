# Event Maker 配对流动性奖励 Shadow 结果（2026-10-20）

> 策略：YES/NO 两边 post-only BUY，达到 `rewardsMinSize`，单边成交后等待或立即用 taker IOC 对冲  
> 代码：`PairedRewardShadowCli.kt`、`PairedRewardQuotePlanner.kt`  
> 数据源：Polymarket Gamma + CLOB + WebSocket  
> 结论：**当前市场条件下不可行，禁止实盘。**

## 1. 核心结果

### 1.1 5 秒对冲宽限（3 分钟 smoke）

- 选择 7 个 0.90–0.955 奖励市场，日奖励池合计 6,653 USDC；
- 13 次报价周期，6 次 requote；
- 1 次 maker 成交，0 次自然配对，1 次对冲；
- 估计奖励：**0.0068 USDC**；
- 配对/对冲 PnL：**-2.4022 USDC**；
- 净结果：**-2.3954 USDC**。

实际成交：

| 时间 | 动作 | 价格 | 股数 | 说明 |
|---|---|---:|---:|---|
| 1792465004557 | MAKER BUY NO | 0.091 | 200 | NO bid 被 taker SELL 打中 |
| 1792465010028 | HEDGE BUY YES | 0.918 | 200 | 5 秒后 YES ask 已上移，含 0.2c 滑点 |
| 合计 | 配对成本 | **1.009** | 200 | 价差损失 3.6 USDC，taker fee 0.6022 |

5 秒宽限期间，YES ask 从可对冲水平上移约 0.7–0.9c，单边逆向选择已经超过奖励。

### 1.2 立即对冲（10 分钟 smoke）

- 7 个市场，34 次报价周期，27 次 requote；
- 1 次 maker 成交，0 次自然配对，1 次立即对冲；
- 估计奖励：**0.2100 USDC**；
- 配对/对冲 PnL：**-1.8486 USDC**；
- 净结果：**-1.6386 USDC**。

实际成交：

| 时间 | 动作 | 价格 | 股数 | 说明 |
|---|---|---:|---:|---|
| 1792465639026 | MAKER BUY NO | 0.095 | 200 | NO bid 成交 |
| 1792465638978 | HEDGE BUY YES | 0.911 | 200 | 立即对冲，仍有 0.2c 滑点/取整 |
| 合计 | 配对成本 | **1.006** | 200 | taker fee 0.6486，净亏 1.8486 |

立即对冲把损失从 -2.40 降到 -1.85，但没有改变结构：**一次单边成交仍远大于十分钟奖励**。

### 1.3 安全对冲成本门槛

新增预交易门槛：

```text
若 YES 成交，立即买 NO 的最坏成本 = yesBid + noAsk + slippage
若 NO 成交，立即买 YES 的最坏成本 = noBid + yesAsk + slippage
只有 max(两边最坏成本) <= maxHedgePairCost 才允许报价
```

使用 `maxHedgePairCost=1.0`、`slippage=0.2c` 重新扫描：

- 87 个 0.90–0.955 奖励候选；
- **0 个通过安全门槛**；
- 结果：不报价、不成交、不产生奖励；
- 这不是工具失败，而是当前没有满足“单边成交后可无损对冲”条件的市场。

## 2. 为什么奖励覆盖不了

当前观察到的典型盘口：

```text
YES bid 0.903 / ask 0.909
NO  bid 0.091 / ask 0.097
```

两边 bid 配对：

```text
0.903 + 0.091 = 0.994
```

如果两边自然成交，配对收益约 0.6%。但：

- 单边成交后立即买对手 ask：`0.091 + 0.909 = 1.000`；
- 加上 0.2c 滑点：**1.002**；
- 再加 taker fee：约 **1.006**；
- 一次 200 股单边成交，损失约 1.2–1.8 USDC；
- 同期奖励仅 0.0068–0.21 USDC。

奖励是“按分钟份额、按日结算”，不是每笔成交的返佣。当前 0.90–0.95 市场里，奖励相对单边选择权损失太小。

**自然配对**可以赚钱，但两个 smoke 都没有出现自然配对；反而先出现单边成交。不能把“希望另一边也很快成交”当作风险模型。

## 3. 门槛结果

新增 `PairedRewardQuotePlanner` 安全检查：

| 检查 | 作用 |
|---|---|
| `rewardsMinSize` | 两边订单必须达到奖励最低股数 |
| `maxSpreadCents` | 报价必须在奖励最大价差内 |
| `maxPairCostPerShare` | 两边 bid 配对的自然成本上限 |
| `maxHedgePairCostPerShare` | 单边成交后立即 taker 对冲的最坏成本上限 |
| `hedgeSlippageCents` | 把不可见的执行滑点纳入事前检查 |
| post-only clamp | 报价不得穿过 best ask |
| taker fee | 记录 IOC hedge 的真实费用 |

## 4. 最终判定

| 项目 | 结论 |
|---|---|
| 0.97–0.99 单边 maker | 已证伪，不实盘 |
| 0.90–0.95 奖励 farming | 当前市场下被单边对冲成本证伪，不实盘 |
| 目标地址跟单 | 不跟 |
| 当前存在可安全执行的 reward 市场 | **0 个（按 1.0 对冲成本 + 0.2c 滑点门槛）** |
| 实盘 | **禁止** |

## 5. 后续只能在这三种结构性变化后重开

1. **自然配对率显著提高**：需要更长采样证明两边成交时间差很小，且没有逆向选择。
2. **盘口结构改善**：`yesBid + noAsk + slippage <= 1.0` 或 `noBid + yesAsk + slippage <= 1.0` 的市场实际存在。
3. **改变收益结构**：例如持有库存做 maker hedge、返佣/rebate、或其他非二元尾部风险策略；不能仅靠调价格带。

在这三类证据出现前，继续调 `maxPairCost`、`requoteSeconds`、`hedgeGraceMs` 只会重复“看起来正期望、实际被一次单边成交打穿”的循环。

## 6. 产物

- 5 秒宽限：`backend/build/research/paired-reward-shadow-smoke-20261020/`
- 立即对冲：`backend/build/research/paired-reward-shadow-smoke-immediate-20261020/`
- 安全门槛：`backend/build/research/paired-reward-shadow-gate-20261020/`
- 代码：`PairedRewardShadowCli.kt`
- 回归：105 suites / 579 tests / 0 failures / 0 errors / 2 skipped