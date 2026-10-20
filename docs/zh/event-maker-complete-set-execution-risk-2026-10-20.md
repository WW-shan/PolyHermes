# 完整集非原子执行风险与 Mock 执行器（2026-10-20）

> 范围：Polymarket Binary / NegRisk 完整集 ask-side 买入，以及 NegRisk 全 NO basket  
> 结论先行：**执行链路已经可以在 mock 中闭环，但当前候选仍不具备真实资金条件。** 多腿订单不是原子 basket，最坏损失只能由资金上限约束，不能由理论 edge 约束。

## 1. 本次新增

| 文件 | 作用 |
|---|---|
| `scripts/complete-set-basket-risk.mjs` | 非原子执行风险模型 + mock-only 顺序执行器 |
| `scripts/analyze-complete-set-execution-risk.mjs` | 读取真实盘口，生成只读风险报告 |
| `scripts/test/complete-set-basket-risk.test.mjs` | 风险模型、部分成交、撤单、回滚、资金/亏损预算测试 |
| `scripts/test/analyze-complete-set-execution-risk.test.mjs` | CLI 参数与安全默认值测试 |
| `scripts/scan-event-complete-set-arbitrage.mjs` | ask-side 结果内嵌执行风险摘要 |
| `scripts/monitor-event-complete-set-arbitrage.mjs` | 监控 episode/确认记录风险字段与确认统计 |

真实下单入口没有被接入。mock 执行器在 `mode !== "mock"` 或 `adapter.mode !== "mock"` 时直接拒绝运行。

## 2. 风险模型

### 2.1 结算支付矩阵

对 n 个互斥 outcome：

- YES basket：每个 outcome 的 YES 在对应结果下支付 1，因此完整一组支付 1。
- NegRisk NO basket：恰好一个 NO 归零，其余 n-1 个支付 1，因此完整一组支付 `n - 1`。
- Binary complete set：YES + NO 的支付矩阵与 YES basket 相同，完整一组支付 1。

模型对每个可能 winner 计算支付，不把“价格之和小于 1”直接当成已实现利润。

### 2.2 独立订单与部分成交

每腿允许出现：

- 完全成交；
- 部分成交后撤单；
- 拒绝/超时/零成交；
- 撤单期间又成交一部分；
- 失败后按当前 bid 平仓；
- bid 深度不足，留下未对冲库存。

对最多 6 腿，模型穷举每腿 `0 / 25% / 50% / 75% / 100%` 成交状态。超过 6 腿时使用结构化压力场景，并明确标记 `exhaustive=false`，默认 fail-closed。

### 2.3 顺序执行

顺序执行器每次只提交一腿，等待终态并确认成交股数后才继续：

1. 按每腿实际现金成本升序排列，便宜腿先、昂贵腿后；
2. 任一腿没有完整成交，立即停止后续下单；
3. 对已成交库存尝试 IOC/FAK 式平仓；
4. 记录每笔订单、撤单、最终成交、平仓结果和剩余库存；
5. 只有所有腿完整成交才报告 locked profit。

“便宜腿先”不是提高成交率，而是降低失败发生在后段时已经暴露的未对冲成本。它会牺牲部分抢单速度，是否采用必须在真实延迟数据上继续验证。

### 2.4 两类预算

- `maxLossUsd`：可接受的最坏损失。
- `maxCapitalUsd`：单次最多投入现金。

两者分开检查。完整集套利即使完整成交利润很小，失败时仍可能占用并损失远大于利润的库存，因此不能只看 edge。

## 3. 当前 Hormuz 候选复核

只读报告：`backend/build/research/complete-set-risk-hormuz-20261020.json`  
生成时间：`2026-10-20T06:22:14.412Z`（14:22 +08:00）

参数：

- 3 outcomes；
- `targetShares=546.57`；
- `maxLossUsd=10`；
- `maxCapitalUsd=25`；
- entry slippage `0.2c/share`；
- exit slippage `0.2c/share`；
- gas `0.02 USDC`。

结果：

| 指标 | 数值 |
|---|---:|
| top ask sum | 0.9900 |
| 完整成交净 PnL | **-1.541957 USDC** |
| 每 share edge | **-0.2821c** |
| 投入现金 | 548.1120 USDC |
| 穷举场景数 | 13 |
| 最坏观测损失 | 40.9809 USDC |
| 最坏 fills | `[546.57, 546.57, 0]` |
| 最坏策略 | hold to resolution |
| 判定 | `reject_no_profitable_complete_fill` |

结论：

1. 当前盘口加上保守滑点后，完整成交已经不赚钱。
2. 即使忽略 entry slippage，之前约 `0.3179c/share` 的 edge 对应约 1.74 USDC 完整利润，而两腿成交、第三腿失败的最坏观测损失约 41 USDC，风险收益比仍超过 20 倍。
3. 最小 5 share 复核同样为负：完整成交 `-0.027157 USDC`，gas 和费用吃掉了薄 edge。

## 4. Mock 执行器验证

已覆盖并通过：

- 三腿完整成交，报告 locked profit；
- 第二腿部分成交，撤单后最终股数正确，第三腿未提交；
- 已成交库存按 bid 平仓；
- bid 不足时保留剩余库存并标记 `unhedged`；
- 亏损预算不足时在提交任何 mock 订单前拒绝；
- 资金上限不足时在提交任何 mock 订单前拒绝；
- 真实 `mode` / `live` adapter 被拒绝；
- 重复 token fail-closed；
- 便宜腿优先并同步置换支付矩阵列。

测试命令：

```powershell
cd D:\Project\PolyHermes\scripts
node --test test
```

最新结果：**50 tests / 50 pass / 0 fail**。

## 5. 当前判定

- 三腿执行链路已经不是“未实现”，mock 端到端闭环已完成。
- 当前候选仍不能进入真实资金 smoke：
  - 完整成交没有正收益；
  - 多腿失败的最坏损失远大于利润；
  - 真实账户、资金上限和授权仍未提供。
- 24h 监控继续保留，不降低 0.5c 正式门槛。
- 只有同时满足以下条件才讨论 live smoke：
  1. 二次确认后 edge 稳定高于 0.5c；
  2. `maxCapitalUsd` 能覆盖全部腿且可接受全部损失；
  3. mock 场景中 `unhedged=0` 或剩余库存损失低于预算；
  4. 用户明确提供账户、资金上限、时间窗口、市场范围和实盘授权。
## 6. 盘口新鲜度修复：之前确实漏掉了 OpenAI

原实现把 CLOB book 的 `timestamp` 当作抓取时间，超过 10–15 秒就标记 `stale_book`。Polymarket 的这个字段更接近“盘口最后一次变化时间”；一个无人交易的盘口可以 30 分钟不变化，但 REST 返回的仍然是当前有效盘口。

修复：

- `parseBook` 同时记录 `sourceUpdatedAt`（最后变化时间）和 `fetchedAt`（实际抓取时间）；
- actionable 判定只使用 `fetchedAt` 的新鲜度；
- `sourceUpdatedAt` 超过 5 分钟只记录到 `sourceStaleBook`，不再直接拒绝；
- 交叉盘口、缺失盘口、未来抓取时间仍然 fail-closed。

修复后全类别扫描 `staleEvaluations=0`，OpenAI 候选从误判 `stale_book` 变为 `actionable`。

## 7. OpenAI 候选：能赚钱，但资金效率很低

事件：`openai-ipo-closing-market-cap-554`  
结构：7 个互斥且完整的市值档位：

- `<500B`
- `500–750B`
- `750B–1T`
- `1T–1.25T`
- `1.25–1.5T`
- `1.5T+`
- `No IPO by Dec 31, 2027`

实时盘口：

| 指标 | 数值 |
|---|---:|
| top ask sum | 0.9200 |
| max positive shares | 26.03 |
| 26.03 股完整净利 | 1.1364 USDC |
| 投入现金 | 24.8936 USDC |
| terminal ROI | 4.565% |
| 最晚结算日 | 2028-01-01 |
| max hold days | 437.9 天 |
| 最晚结算年化 | **3.791%** |
| 20 股完整净利 | 0.6040 USDC |
| 20 股投入现金 | 19.3960 USDC |
| 20 股 terminal ROI | 3.114% |
| 20 股最晚结算年化 | **2.589%** |

监控证据：

- v3 Crypto+Finance 监控从 `06:50:49Z` 到 `07:20:48Z` 连续 74 次轮询；
- `askTop` 一直为 `0.92`；
- 最近 edge 为 `4.3657c/share`；
- `actionable=true`；
- 没有出现 `stale_book`、请求错误或二次确认失败。

结论：

- 这是**真实的、可持续数十秒到数十分钟的正收益候选**，不是假信号。
- 但 YES basket 没有文档化的“立即换回 collateral”路径。NegRisk 的 `convertPositions` 是 `NO -> 其它 YES + collateral`；买入全部 YES 后需要持有到事件结算。
- 因此它不是高频翻仓套利，而是**最长约 438 天、年化约 2.6%–3.8%、容量约 25 USDC 的低收益 carry trade**。
- 如果 OpenAI 提前 IPO 并结算，实际年化会提高；但这是不确定的提前结算收益，不能按稳定高频策略估值。

## 8. Mock 结果

对 26.03 股 OpenAI basket 运行 5 个场景：

| 场景 | 结果 |
|---|---:|
| 全部 7 腿成交 | +1.1974 USDC |
| 第二腿部分成交后回滚 | -0.2823 USDC，failed_closed |
| 最后一腿失败并按 bid 回滚 | -2.4115 USDC，failed_closed |
| 第二腿部分成交且无买盘 | -1.1828 USDC，unhedged |
| 最后一腿失败且无买盘 | **-13.8822 USDC，unhedged** |

因此“能不能赚”必须同时看：

1. 全部成交的 terminal profit；
2. 失败腿是否能在 bid 深度内回滚；
3. 资金锁定期；
4. 单次最坏损失。

当前 OpenAI 的 1.14 USDC 利润对应约 14 USDC 的最坏库存损失，且资金最长锁定 438 天。它不是不能赚钱，而是**收益/资金占用不划算**。

## 9. 全类别扫描结果

参数：

- tags: politics, geopolitics, crypto, finance, sports；
- 4,712 个市场；
- 1,337 个组；
- 1,028 个完整评估；
- 3 个 ask-side 正收益候选；
- 1 个 actionable；
- 0 个 bid-side / NO-basket actionable；
- 最佳最晚结算年化：**3.791%**（OpenAI）。

三个正收益候选：

| 事件 | edge | 容量 | terminal ROI | 最晚结算年化 | 状态 |
|---|---:|---:|---:|---:|---|
| OpenAI IPO Closing Market Cap | 4.3657c | 26.03 | 4.565% | 3.791% | actionable |
| Bitcoin vs. Gold vs. S&P 500 in 2026 | 0.3132c | 135 | 0.314% | 1.583% | below edge |
| Hormuz Senate pending agreement | 0.0531c | 115.29 | 0.053% | 1.310% | below edge |

结论：当前完整集套利方向**数学上能赚，但没有发现高收益、短周期、大容量的结构**。OpenAI 是低收益长期占用资金的套利，不是可规模化的量化策略。

## 10. 当前动作

- Politics+Geopolitics v6 24h 监控继续运行；
- Crypto+Finance v3 24h 监控继续运行；
- 两套监控都使用修复后的 `fetchedAt` 新鲜度语义；
- 不降低 0.5c 门槛；
- 如果后续出现短结算日、可立即 convert/merge、容量更大的候选，再评估实盘；
- 若要把当前 OpenAI carry trade 真实执行，需要账户、资金上限、授权、时间窗口和市场范围；在此之前只能继续用真实盘口和 mock 验证。