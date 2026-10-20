# adonpoli 真实策略拆解与债券型复刻回测（2026-10-20）

> 对象：`0x0260366a9add43c25d4f4d35d2210526c6750d88`（adonpoli / Noted-Shop）  
> 数据：Data API `positions` / `closed-positions` / `activity` / `trades` / `value`  
> 结论：他做的是**无对冲高概率债券 carry + maker 库存周转**，不是完整集套利。

## 1. 公开账户事实

- 当前组合价值：**20,092.56 USDC**
- 已平仓记录：**390**
- 累计买入额：**5,626,529 USDC**
- 已实现 PnL：**+29,919.56 USDC**
- 按累计买入额的单次周转 ROI：**0.532%**
- 盈利 / 亏损记录：**281 / 86**
- 近 16 个月中 **13 个月为正、3 个月为负**（2025-09 -694、2025-12 -3,332、2026-09 -276）

按买入价格带（已平仓）：

| 买入价 | 记录数 | 累计买入 | 已实现 PnL | ROI | 胜率 |
|---|---:|---:|---:|---:|---:|
| 0–0.5 | 73 | 290,990 | -1,414 | -0.49% | 27.4% |
| 0.5–0.8 | 40 | 604,980 | +11,498 | **+1.90%** | 55.0% |
| 0.8–0.9 | 33 | 715,541 | +1,311 | +0.18% | 75.8% |
| 0.9–0.95 | 29 | 489,574 | +3,542 | +0.72% | 79.3% |
| 0.95–0.97 | 34 | 510,115 | +1,148 | +0.22% | 85.3% |
| 0.97–0.99 | 56 | 1,098,307 | +8,954 | **+0.82%** | 96.4% |
| 0.99–1.00 | 125 | 1,917,022 | +4,881 | +0.25% | 86.4% |

关键：**0–0.5 的低价彩票是亏的；利润集中在 0.5–0.8 和 0.97–0.99。** 他不是靠猜冷门，而是靠高概率债券和周转。

## 2. 奖励与返佣

最近 5,500 条公开 activity 覆盖 232 天：

| 类型 | 笔数 | USDC |
|---|---:|---:|
| REWARD | 248 | 2,316.07 |
| MAKER_REBATE | 40 | 537.10 |
| YIELD | 18 | 8.66 |
| TAKER_REBATE | 1 | 10.00 |
| 合计 | 307 | **2,871.83** |

同期已平仓 PnL 约 16,136 USDC，奖励/返佣约占总收益 **15%**。奖励不是主因，但证明他长期提供被动挂单。

## 3. 为什么“0.53%”不是低收益

- 累计买入 5.63M / 组合价值 20.1k ≈ **280 次周转**
- 0.532% × 280 ≈ **149% 累计**（未计复利）
- 持仓分布（历史成交近似）：约 48% 在 1 天内平仓，43% 在 1–7 天
- 所以他的核心不是单笔高收益，而是**极高周转次数 × 很小的单笔价差**

## 4. 债券型复刻回测

使用仓库 `event-maker-full-v2-7d-20261019-dataset.json.gz`（4,189 个 Politics/Geopolitics 市场，7 天逐笔）。

新增 `BOND` grid profile：
- 价格带：0.90–0.95 / 0.95–0.97 / 0.97–0.99 / 0.99–0.999
- 入场：maker bid（offset 0/0.25/0.5c）
- 退出：`exitSpread=100c` 即不主动卖，持有到结算
- 队列：queueFactor 1/2/5/10/20
- 最大 1 个 cycle，订单 100 USDC

命令：

```powershell
cd D:\Project\PolyHermes\backend
.\gradlew.bat eventMakerBacktest `
  "-PresearchDataset=build/research/event-maker-full-v2-7d-20261019-dataset.json.gz" `
  "-PresearchMaxMarkets=5000" "-PresearchMinVolume=100000" `
  "-PresearchFrom=2024-01-01" "-PresearchTo=2026-10-01" `
  "-PresearchEntryHorizonSeconds=604800" "-PresearchGridProfile=bond" `
  "-PresearchOutput=build/research/event-maker-bond-20261020" --console=plain
```

结果（真实 taker SELL 流才成交，队列因子已计入）：

| 配置 | 可用 | 成交 | 周期 | 胜率 | PnL | ROI | 训练 | 测试 | 结算亏损 | MAE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.95–0.97, off0, q1 | 160 | 88 | 88 | 100% | +360.78 | **4.10%** | 4.05% | 4.13% | 0 | -92.6% |
| 0.95–0.97, off0, q2 | 160 | 79 | 79 | 100% | +321.16 | 4.07% | 3.98% | 4.11% | 0 | -66.8% |
| 0.97–0.99, off0, q1 | 260 | 170 | 170 | 100% | +308.17 | **1.81%** | 1.80% | 1.82% | 0 | -95.4% |
| 0.97–0.99, off0, q2 | 260 | 147 | 147 | 100% | +266.33 | 1.81% | 1.81% | 1.82% | 0 | -95.4% |

## 4.1 官方费率确认（2026-10-20）

官方来源（已实时抓取）：

- Fees: https://docs.polymarket.com/trading/fees
- Maker Rebates Program: https://docs.polymarket.com/programs/maker-rebates

官方 `Fees` 页面原文：

> **Makers are never charged fees. Only takers pay fees.**

同页费率表明确列出 **所有类别的 Maker Fee Rate 均为 0**：

| 类别 | Taker Fee Rate | Maker Fee Rate | Maker Rebate |
|---|---:|---:|---:|
| Crypto | 0.07 | 0 | 20% |
| Sports | 0.05 | 0 | 15% |
| Finance / Politics / Mentions / Tech | 0.04 | 0 | 25% |
| Economics / Culture / Weather / Other | 0.05 | 0 | 25% |
| Geopolitics | 0 | 0 | — |
| Combos | 0.05 | 0 | 0% |

官方 `Maker Rebates Program` 进一步说明：

- rebate 每天以 pUSD 发放，累计至少 1 pUSD 才支付；
- 返佣不是“每笔免手续费”，而是把某市场 taker fee 的一部分放入返佣池，再按各 maker 的 fee-equivalent 份额分配；
- 因此 `rebateRate=0.25` 表示该市场 taker fee 的 25% 进入返佣池，不表示每个 maker 自动拿回自己手续费的 25%。

实查当前 Gamma（2026-10-20）：

| 市场 | feesEnabled | feeType | takerOnly | rate | rebateRate |
|---|---:|---|---:|---:|---:|
| FC Barcelona 2026-10-20 | true | sports_fees_v3 | true | 0.05 | 0.15 |
| Flávio Bolsonaro 巴西总统 | true | politics_fees | true | 0.04 | 0.25 |
| Shakhtar Donetsk 欧冠冠军 | true | sports_fees_v2 | true | 0.03 | 0.25 |
| US x Iran ceasefire | false | — | — | — | — |

对策略的含义：

- **maker 挂单成交确实 0 手续费**；BOND 回测的 `makerFeeRate=0` 口径正确。
- 只有 post-only/resting maker 成交才享受 0 费；主动吃单、FAK/IOC 对冲或卖出属于 taker，必须按上表 taker rate 计费。
- BOND 的 `exitSpread=100c` 是持有到结算，不产生 taker 卖出手续费；若改成主动卖出，必须另算 taker fee。
- 下面的 100bps 表只是成本压力测试，不是当前 maker 费率。
- 回测未计 maker rebate 和 liquidity reward，因此没有高估手续费；但返佣份额在 Shadow/Live 实测前不能当作确定收益。

费用敏感性（0.95–0.97, off0, q1）：

| 成本压力（非实际 maker 费） | ROI |
|---|---:|
| 0 bps | 4.10% |
| 10 bps | 3.92% |
| 25 bps | 3.65% |
| 50 bps | 3.21% |
| 100 bps | **2.32%** |

证据：
- `backend/build/research/event-maker-bond-20261020-summary.csv`
- `backend/build/research/event-maker-bond-20261020-robust-cycles.csv`
- `backend/build/research/event-maker-bond-20261020-fee-sensitivity.csv`
- `backend/build/research/leader-strategy-decomposition-20261020.json`
## 5. 结论与风险

**可以复刻的部分：**
- 高概率债券（0.95–0.99）为主，不碰 0–0.5 的低价彩票；
- maker-only 挂单，靠 taker 流成交，不追价；
- 小单、多市场、分散事件；
- 1–7 天周转，部分持有到结算；
- 参与流动性奖励与 maker rebate。

**不能忽略的风险：**
- 回测样本内 0 次结算亏损，但 MAE 到 -92%～-95%，说明持仓过程中可能接近归零；他的 86 个亏损记录证明尾部真实存在。
- 成交率只有约 55%–65%（88/160、170/260），依赖队列和真实 taker 流；queueFactor 越大成交越少。
- 回测是 2024–2026 的样本内结果，必须经过 Shadow 真实盘口验证。

**下一步：**
1. 用 `BOND` 配置启动 Shadow：`0.95–0.97`、`exitSpread=100c`、`maxCycles=1`、order 100 USDC、多市场分散。
2. 记录真实 fill rate、平均成交价、MAE、结算亏损和奖励。
3. 至少 2–4 周、覆盖 50–100 个结算事件后，再考虑小额实盘。
