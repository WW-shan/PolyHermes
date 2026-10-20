# 完整集与 NegRisk 套利扫描/连续监控（2026-10-20）

> 研究范围：Polymarket Politics + Geopolitics  
> 目标：验证“同一事件所有互斥结果的可执行 ask 之和 < 1”是否存在真实、可复现、扣除成本后仍为正的机会。  
> 结论先行：**当前快照没有可执行机会；禁止据此实盘。** 24 小时连续监控仍需完成。

## 1. 本次实现

新增：

- `scripts/scan-event-complete-set-arbitrage.mjs`
- `scripts/monitor-event-complete-set-arbitrage.mjs`
- `scripts/test/scan-event-complete-set-arbitrage.test.mjs`
- `scripts/test/monitor-event-complete-set-arbitrage.test.mjs`

扫描器支持两种结构：

1. **Binary complete set**
   - 买入同一 condition 的 YES ask + NO ask；
   - 或铸造一对完整集后卖出 YES bid + NO bid。
2. **NegRisk complete set**
   - 对事件的每一个 outcome 买入 YES ask；
   - 只有事件全部 outcome 都 active、未关闭、可下单、NegRisk id 一致时才评估。

## 2. 与旧版扫描器的关键差异

旧版只做了“顶层价格相加”。新版补齐了以下会造成假机会的口径：

| 项目 | 处理 |
|---|---|
| 手续费 | 使用官方 `fee = shares × feeRate × p × (1-p)`；费率未知时查询 `/fee-rate`，不会静默按 0 处理 |
| 深度 | 按盘口逐档吃单，不假设顶层 size 无限 |
| 最小下单量 | 每一腿都要求满足 `orderMinSize` 和 book `min_order_size` |
| 数量精度 | 默认按 0.01 share 向下取整，不输出无法提交的碎股 |
| 盘口过期 | 默认 15 秒；过期盘口可计算但不会标记为 actionable |
| 交叉盘口 | best bid >= best ask 时拒绝为 actionable |
| 缺失盘口 | 标记 `missing_ask` / `missing_bid`，不填零 |
| 事件完整性 | NegRisk 必须拿到完整事件市场，不能只用 tag 发现到的一部分市场 |
| 重复数据 | 拒绝重复 condition id 或 token id |
| 链上成本 | 增加 merge/mint gas buffer，默认各 0.02 USDC |
| 多腿风险 | 不假设独立订单原子成交；bid-side 明确标记 `requiresMint` 和多腿数量 |
| 安全边际 | 默认要求扣除成本后至少 0.5c/share 的 edge，且总利润至少 0.01 USDC |

测试命令：

```powershell
cd scripts
npm run test:complete-set
```

结果：**15 tests / 0 failures**。

## 3. 全量快照结果

> **2026-10-20 13:20 更正：** 本章数字来自旧的最大数量算法。该算法会把仓位一直加到深档，把本来为正的薄 edge 磨平。修正后的结果见第 8 节。

执行时间：2026-10-20 11:50 +08:00  
产物：

- `backend/build/research/complete-set-arb-20261020-v2/scan-full.json`
- `backend/build/research/complete-set-arb-20261020-v2/scan-full.csv`

参数：

```text
tags=politics,geopolitics
maxMarketsPerTag=1000
maxGroups=1000
maxOutcomesPerGroup=256
minVolume24h=100
minEdgeCents=0.5
maxBookAgeMs=30000
concurrency=8
```

结果：

| 指标 | 数值 |
|---|---:|
| 发现市场 | 1,739 |
| 事件/市场组 | 821 |
| 完成评估 | 582 |
| 不完整或已关闭 NegRisk 组 | 239 |
| 原始 ask-side 正收益（扣费后） | 0 |
| 可执行 ask-side 套利 | 0 |
| 原始 bid-side mint 套利 | 0 |
| 可执行 bid-side mint 套利 | 0 |
| 最低 top ask sum | 0.983 |
| 最高 top bid sum | 0.999 |
| 盘口超过 30 秒的评估数 | 161 |

### 最接近的候选为什么仍不赚钱

`Who wins the Senate pending oil below $85 before the midterms?`：

- 4 个 NegRisk outcome；
- top ask sum = **0.983**；
- 但所有腿合计约 385 shares；
- 买入费约 **2.28 USDC**；
- 毛价差只有约 **0.00003 USDC**；
- 扣费后为负，因此不是套利。

另一个 3 outcome 事件：

- top ask sum = 0.99；
- 毛价差约 0.00002 USDC；
- fee 约 2.58 USDC；
- 同样为负。

这说明当前看到的是“价格加起来接近 1”，不是“可以免费拿 1”。Polymarket 的 taker fee 在 0.5 附近并非固定小常数，而是 `p(1-p)` 曲线；多腿同时吃单会把表面价差吃掉。

## 4. 连续监控器

连续监控器不是每轮全量重扫，而是两级过滤：

1. 每 10 分钟用 Gamma 顶层价格发现候选；
2. 每 15 秒重新拉取候选组的 CLOB 可执行盘口；
3. 首次出现正收益后，等待 1 秒重新拉盘确认；
4. 只有第二次仍满足手续费、深度、新鲜度和安全边际，才写入 confirmed opportunity；
5. 记录 watchlist、每次快照、episode 起止时间和确认结果。

产物：

```text
backend/build/research/complete-set-monitor-<timestamp>/
  run.json
  monitor-summary.json
  watchlist.json
  snapshots.jsonl
  opportunities.jsonl
```

24 小时运行命令：

```powershell
$env:HTTPS_PROXY='http://127.0.0.1:10808'
$env:HTTP_PROXY='http://127.0.0.1:10808'
$env:NODE_USE_ENV_PROXY='1'
node scripts/monitor-event-complete-set-arbitrage.mjs `
  --max-markets-per-tag 1000 `
  --max-groups 2000 `
  --min-volume 1000 `
  --poll-interval-ms 15000 `
  --discovery-interval-ms 600000 `
  --duration-ms 86400000 `
  --max-watch-groups 100 `
  --candidate-ask-sum 1.02 `
  --candidate-bid-sum 0.99 `
  --min-edge-cents 0.5 `
  --max-book-age-ms 10000 `
  --confirm-delay-ms 1000 `
  --output-dir backend/build/research/complete-set-monitor-24h-20261020
```

短时 smoke 已通过：

- 126 个发现市场；
- 20 个事件组；
- 10 个 proxy 候选；
- watchlist 5 个；
- 15 秒内正常完成，无 request error；
- 没有出现可确认机会。

## 5. 重要限制

以下边界尚未被证明：

1. 当前只覆盖 Politics + Geopolitics，Sports/Crypto/Finance 尚未全量扫描。
2. 仅有点快照 + 短时 smoke，不能代表全天机会频率。
3. NegRisk 的链上转换/兑换路径、实际 gas、nonce 顺序和多腿失败恢复尚未做真实小额验证。
4. `bid-side mint + sell both` 天然是多腿非原子操作；即使数学上为正，也可能因为一腿成交一腿失败而反向暴露。
5. 本次没有真实下单，因此不能算 Live 验证。
6. CLOB book 的 timestamp 是盘口快照时间，不是订单必然可成交的保证；实际执行还需要 post-only / IOC 行为验证。

## 6. 判定

**当前不可实盘。**

- 不能因为 top ask sum < 1 就认为赚钱；
- 不能把 stale book、缺失深度或未知费率当作可执行机会；
- 不能把 0.5c 以下的薄 edge 当作可覆盖多腿执行风险的收益。

## 7. 下一步

1. 运行 24 小时连续监控，统计：
   - 候选出现次数；
   - 每次 episode 持续时间；
   - 1 秒后仍成立的比例；
   - 扣除 fee 后最大 edge；
   - 可成交容量。
2. 若 24 小时仍为 0，扩展到 Sports/Crypto 等不同费用和盘口结构。
3. 研究关联市场约束（例如同一事件的嵌套日期、资格条件和范围市场），寻找不止“完整集”一种结构性关系。
4. 只有连续监控出现至少 0.5c 可确认 edge，且多腿失败损失可界定时，才进入 mock 执行和最小资金 smoke。
## 8. 2026-10-20 13:20 关键修正与重新扫描

### 8.1 最优数量算法错误

旧实现寻找的是“累计利润仍大于 0 的最大股数”，而不是“利润最大的股数”。当顶层有正 edge、深档有负 edge 时，旧实现会一直吃到累计利润接近 0，最终错误地判定整个机会不赚钱。

已改为逐档边际利润最优化：

- ask-side：只要下一档 `price + fee < 1` 就继续；
- bid-side：只要下一档 `price - fee > 1` 就继续；
- 遇到第一档不满足边际条件立刻停止；
- 最后再按 0.01 share 向下取整，并检查最小下单量。

新增回归测试覆盖“前 10 股有 edge、后 10,000 股会把 edge 磨平”的情况。

### 8.2 修正后的完整集全量扫描

执行时间：2026-10-20 13:01 +08:00  
产物：

- `backend/build/research/complete-set-arb-20261020-v3/scan-prefilter.json`
- `backend/build/research/complete-set-arb-20261020-v3/scan-prefilter.csv`

参数：

```text
tags=politics,geopolitics
markets=1,739
groups=820
maxBookAgeMs=30000
prefilterAskSumMax=1.10
prefilterBidSumMin=0.90
minEdgeCents=0
concurrency=4
```

结果：

| 指标 | 数值 |
|---|---:|
| 完整评估 | 510 |
| Gamma 预过滤 | 72 |
| 不完整/关闭 | 238 |
| 正收益 ask 套利 | 1 |
| 正收益 bid mint 套利 | 0 |
| 最低 top ask sum | 0.982 |
| 最高 top bid sum | 0.999 |
| 盘口过期评估 | 140 |

### 8.3 唯一正收益候选

`Who wins the Senate pending Hormuz Agreement by Oct 23?`

三个互斥结果：

1. Hormuz agreement + Republicans win Senate；
2. Hormuz agreement + Democrats win Senate；
3. No Hormuz agreement。

该事件是完整的 NegRisk 分区。快照结果：

| 指标 | 数值 |
|---|---:|
| top ask sum | 0.987 |
| 最大股数 | 310.00 |
| 现金成本 | 306.67538 |
| taker fee | 1.67571392 |
| gas buffer | 0.02 |
| 净利润 | 1.62890608 |
| edge | **0.52545c/share** |
| 盘口年龄 | 997ms |
| 状态 | actionable |

### 8.4 1 秒级人工复核

随后连续 3 次、间隔 1 秒重新拉取可执行盘口：

| 次数 | top ask sum | 最大股数 | 净利润 | edge |
|---|---:|---:|---:|---:|
| 1 | 0.986 | 1,520.04 | 5.34478 | 0.35162c |
| 2 | 0.986 | 1,520.04 | 5.34478 | 0.35162c |
| 3 | 0.986 | 1,520.04 | 5.34478 | 0.35162c |

结论：

- 该机会在数秒内确实存在且可复现；
- 但 edge 从 0.525c 回落到 **0.352c**，低于正式 0.5c 安全门槛；
- 三腿独立成交，不是原子 basket；
- 因此 **不能因为数学上为正就直接实盘**。

### 8.5 当前判定

- 这是目前第一个真实的、可复现的完整集候选，不是旧报告里的“完全为零”。
- 但在 0.5c 门槛下，人工复核样本未通过。
- 正式 0.5c 门槛不放宽。
- 24 小时监控将记录该事件是否反复出现、能否持续到第二次确认、以及容量与盘口年龄。