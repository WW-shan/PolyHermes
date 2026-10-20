# 全市场完整集扫描与实时盘口顺序执行验证（2026-10-20）

> 范围：Polymarket 当前全部 `active=true&closed=false` 市场  
> 数据：Gamma keyset 分页、CLOB `/books` 批量盘口、公开事件规则  
> 执行验证：只读实时盘口、模拟 taker 成交，无签名、无下单

## 1. 结论

1. **全市场确实存在正收益完整集，但没有发现“高收益、大容量、短周期”的结构。**
2. 99,956 个市场中，60,954 个可评估组，只有 4 个 ask-side 正收益候选，其中 3 个越过 0.5c 门槛。
3. 最大绝对利润候选是 World Cup 2030，完整成交约 +24.34 USDC；但需要约 783 USDC 资金，逐腿失败时最坏库存损失约 378 USDC，资金最长可锁到 2027 年底甚至事件 `endDate` 的 2030 年。
4. OpenAI 的最晚结算年化约 4.53%，是当前年化最高者，但容量只有约 26 股（约 25 USDC），完整利润约 1.35 USDC。
5. 三个候选都通过了实时盘口顺序模拟；但模拟只证明“在那个时刻，1 秒腿间隔下盘口尚存”，不能等同于真实成交。

## 2. 全市场扫描

命令：

```powershell
node scripts/scan-event-complete-set-arbitrage.mjs `
  --all-markets --max-markets-per-tag 100000 --max-groups 100000 `
  --min-volume 0 --max-book-age-ms 30000 --concurrency 12 `
  --book-batch-size 500 `
  --output backend/build/research/complete-set-all-markets-20261020/scan.json `
  --csv backend/build/research/complete-set-all-markets-20261020/scan.csv
```

结果：

| 指标 | 数值 |
|---|---:|
| 活跃市场 | **99,956** |
| 分组 | **60,954** |
| 完整评估 | **42,070** |
| 预过滤 | 16,506 |
| 不完整/已关闭组 | 2,377 |
| 请求错误 | 1（该 token 无 orderbook） |
| ask-side 正收益 | 4 |
| ask-side actionable | **3** |
| bid-side actionable | 0 |
| NO-basket actionable | 0 |
| stale 评估 | 0 |
| 批量盘口批次 | 315 |
| 批量预取错误 | 0 |
| 总耗时 | 886.6 秒 |

证据：`backend/build/research/complete-set-all-markets-20261020/scan.json`、`scan.csv`。

> 唯一请求错误是 Gold 合约中一个 `bestBid=null`、liquidity=0.1 的 token，CLOB 明确返回“无 orderbook”；不是漏掉的可用盘口。

## 3. 三个 actionable 候选

| 事件 | 档位数 | top ask sum | 最大股数 | 完整利润 | 占用资金 | terminal ROI | 最长锁仓 | 最晚年化 | 最坏持有损失 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| OpenAI IPO Closing Market Cap | 7 | 0.9200 | 26.03 | **1.3463** | 24.6838 | 5.454% | 437.8 天 | **4.527%** | 14.1268 |
| World Cup 2030: Location of Final | 5 | 0.9280 | 807.22 | **24.3442** | 782.8758 | 3.110% | 未定/最长约 438 天 | n/a（按 438 天约 2.49%） | 378.0934 |
| Next leader out of power before 2027? | 22 | 0.9890 | 10.00 | **0.0651** | 9.9349 | 0.655% | 72.8 天 | 3.328% | 5.2349 |
| Bitcoin vs. Gold vs. S&P 500 in 2026 | 3 | 0.9800 | 135.00 | 0.4228 | 134.5772 | 0.314% | 72.8 天 | 1.585% | 低于 0.5c 门槛 |

World Cup 的事件顶层 `endDate` 是 2030-07-22，但规则写明：若 2027-12-31 前没有正式宣布决赛城市，则解析为 “Other”。因此保守的最大锁仓应按约 438 天计；真实结算时间取决于 FIFA 公告。

## 4. 实时盘口顺序执行

新增脚本：`scripts/paper-execute-complete-set.mjs`。

它按“便宜腿优先”逐腿重新读取公开盘口，每腿间隔 1 秒，模拟吃 ask；若某腿失败，等待 0.5 秒后按公开 bid 模拟平仓。它不是实盘成交，但比一次性静态快照多验证了执行过程中盘口是否消失。

### 4.1 World Cup，目标 100 股

正常执行：

- 状态：`paper_complete`
- 5/5 腿全部成交
- 入场成本：95.9280 USDC
- 模拟完整净利：**+4.0520 USDC**
- 未触发 100 USDC 亏损上限
- 证据：`backend/build/research/paper-world-cup-2030-target100-20261020.json`

注入最后一条腿失败：

- 前 4 腿成交；按公开 bid 平仓后剩 50 股 Madrid 未卖出
- 入场成本：45.6785 USDC
- 平仓回收：26.8517 USDC
- 最坏结算 PnL：**-18.8467 USDC**
- 若完全无买盘并持有到结算：约 **-45.6985 USDC**
- 证据：`backend/build/research/paper-world-cup-2030-target100-fail-last-20261020.json`

### 4.2 OpenAI，目标 20 股

正常执行：

- 状态：`paper_complete`
- 7/7 腿全部成交
- 入场成本：18.9467 USDC
- 模拟完整净利：**+1.0333 USDC**

注入最后一条腿失败：

- 前 6 腿成交并全部按 bid 平仓
- 模拟 PnL：**-1.9831 USDC**

证据：`backend/build/research/paper-openai-ipo-target20-20261020.json`、`paper-openai-ipo-target20-fail-last-20261020.json`。

### 4.3 Next leader，目标 10 股

- 状态：`paper_complete`
- 22/22 腿全部成交
- 入场成本：9.8466 USDC
- 模拟完整净利：**+0.1334 USDC**
- 22 条腿、容量仅 10 股；利润不足以覆盖操作复杂度和尾部风险。

证据：`backend/build/research/paper-next-leader-target10-20261020.json`。

## 5. 收益/风险判断

| 候选 | 完整利润 | 失败平仓损失 | 利润/失败损失 | 判断 |
|---|---:|---:|---:|---|
| World Cup 100 股 | +4.05 | -18.85 | 0.22 | 容量可以，但回报/风险差 |
| OpenAI 20 股 | +1.03 | -1.98 | 0.52 | 单位风险相对最好，但容量太小 |
| Next leader 10 股 | +0.13 | 最坏约 -1.75 至 -5.23 | 0.03～0.08 | 不值得执行 |

因此：

- **能赚，但不是值得投入大量资金的策略。**
- 真正的限制不是“有没有价差”，而是价差太薄、资金占用太长、非原子执行导致失败损失远大于完整利润。
- 在真实资金前，下一步应是继续记录这些候选的持续时间和可成交深度；不能把本次 paper 结果当成实盘盈利证明。
