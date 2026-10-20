# adonpoli 真实策略拆解与债券型假设回测（2026-10-20，非已验证复刻）

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

## 4.1 官方费率确认（2026-10-20 首次，2026-10-21 复核）

官方来源（已实时抓取）：

- Fees: https://docs.polymarket.com/trading/fees （旧地址 `/polymarket-learn/trading/fees` 已 308 跳转到此）
- Market Details / Trading Fees: https://docs.polymarket.com/market-data/market-details
- Maker Rebates Program: https://docs.polymarket.com/programs/maker-rebates

官方 `Fees` 页面原文（文档 dateModified = 2026-10-20T03:05:04Z）：

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

> 范围说明：以上为 Polymarket 预测市场 CLOB。Polymarket Perps 是另一套按 30 天交易量分层的 maker/taker 费率（$0 档 maker 0.0125%），不适用于本项目。

### 4.1.1 2026-10-21 复核：maker 确实 0 费

**结论：maker 挂单成交完全不收费，这一点已由官方文档和实时 Gamma 双重确认。**

关键坑：Gamma 市场对象里仍能看到 `makerBaseFee=1000` / `takerBaseFee=1000` 这类**历史遗留字段**，它们不是实际生效的 maker 费。实际生效的是新的 `feeSchedule`：

| 市场 | feesEnabled | feeSchedule |
|---|---:|---|
| FC Barcelona 2026-10-20（Sports） | true | `{"rate":0.05,"exponent":1,"takerOnly":true,"rebateRate":0.15}` |
| Shakhtar Donetsk（Sports） | true | `{"rate":0.03,"exponent":1,"takerOnly":true,"rebateRate":0.25}` |
| US x Iran ceasefire（Geopolitics） | false | null（无任何费用） |

- `takerOnly=true` 是官方“只有 taker 付费、maker 不付费”的机器可读字段，因此 **post-only/resting maker 成交 = 0 手续费**。
- 计算成本时必须读 `feeSchedule`，**不能**用 `makerBaseFee=1000`；后者只是旧字段。
- Geopolitics 市场 `feesEnabled=false`，taker 也免费；其他类别只有主动吃单（taker）按 `rate × p × (1-p)` 计费。

官方 `Maker Rebates Program` 进一步说明：

- rebate 每天以 pUSD 发放，累计至少 1 pUSD 才支付；
- 返佣不是“每笔免手续费”，而是把某市场 taker fee 的一部分放入返佣池，再按各 maker 的 fee-equivalent 份额分配；
- 因此 `rebateRate=0.25` 表示该市场 taker fee 的 25% 进入返佣池，不表示每个 maker 自动拿回自己手续费的 25%。

对策略的含义：

- **maker 挂单成交确实 0 手续费**；BOND / BOND_SCALP 回测的 `makerFeeRate=0` 口径正确。
- 只有 post-only/resting maker 成交才享受 0 费；主动吃单、FAK/IOC 对冲或卖出属于 taker，必须按上表 taker rate 计费。
- BOND 的 `exitSpread=100c` 是持有到结算，不产生 taker 卖出手续费；BOND_SCALP 的主动卖出退出若要立即成交，应按 taker 费做敏感性分析。
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
- `backend/build/research/leader-bond-style-backtest-20261020-repro.json`
- `backend/build/research/leader-bond-style-backtest-20261020-band-summary.csv`
- `scripts/analyze-leader-bond-style.cjs`
## 4.2 全价格带与尾部复核（2026-10-20 22:20）

前面的 4.10% 是“队列约束成交模型”的结果：160 个合格市场只成交 88 个，且这 88 个恰好全部是最终赢家。这个数字不能直接当作稳定收益，必须同时看 0.90–0.95、全 eligible 成交和统计置信区间。

### 4.2.1 队列约束模型（Gradle BOND，off0，q1）

| 价格带 | eligible | filled | fill rate | ROI | train / test | 结算亏损 | 盈亏平衡亏损率 | MAE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.90–0.95 | 193 | 114 | 59.1% | **-1.23%** | -0.81% / -1.54% | 10 | 7.63% | -100% |
| 0.95–0.97 | 160 | 88 | 55.0% | +4.10% | +4.05% / +4.13% | 0 | 3.94% | -92.6% |
| 0.97–0.99 | 260 | 170 | 65.4% | +1.81% | +1.80% / +1.82% | 0 | 1.78% | -95.4% |
| 0.99–0.999 | 329 | 233 | 70.8% | +0.40% | +0.45% / +0.36% | 0 | 0.40% | -83.9% |

结论：**0.90–0.95 不能做**，10 次结算亏损把 ROI 拉到负值；0.95–0.99 在样本内为正，但尾部亏损仍可达 -92%～-95%。

### 4.2.2 全 eligible 成交上限模型

新增可复现脚本 `scripts/analyze-leader-bond-style.cjs`，假设每个 eligible 市场都在入场参考价成交（比队列模型更乐观的成交假设，但把 3 个亏损样本也纳入），7 天持有到结算、maker 费率 0：

| 价格带 | n | wins | 亏损率 | 平均 ROI | 最差 | 盈亏平衡亏损率 | 亏损率 95% Wilson CI |
|---|---:|---:|---:|---:|---:|---:|---:|
| 0.90–0.95 | 193 | 173 | 10.36% | **-3.39%** | -100% | 7.22% | 6.81%–15.46% |
| 0.95–0.97 | 159 | 156 | 1.89% | **+2.17%** | -100% | 3.98% | 0.64%–5.40% |
| 0.97–0.99 | 261 | 260 | 0.38% | **+1.54%** | -100% | 1.90% | 0.07%–2.14% |
| 0.99–1.0 | 349 | 349 | 0% | +0.51% | +0.10% | — | 0%–1.09% |

统计判断：

- **0.95–0.97**：平均 ROI +2.17%，但 3/159 亏损的 95% 置信区间上限 5.40%，高于 3.98% 的盈亏平衡亏损率，**尚不能证明 edge 在 95% 置信水平上为正**。
- **0.97–0.99**：1/261 亏损，CI 上限 2.14%，略高于 1.90% 盈亏平衡率，属于**边界性正期望**，仍需更多结算样本。
- **0.99–1.0**：0/349 亏损，但平均 ROI 仅 +0.51%，CI 上限 1.09%，收益很薄，容易被子样本偏差和手续费/滑点吃掉。
- **0.90–0.95**：亏损率 10.36%，远高于 7.22% 盈亏平衡率，**明确为负期望**。

### 4.2.3 结论

1. 0.95–0.99 是目前唯一值得继续 Shadow 的区间；0.90–0.95 不应投入真实资金。
2. 4.10% 是队列约束模型的样本内结果，不是稳定年化，也不是“每笔都能赚 4%”；全 eligible 模型只有 +2.17%，且置信区间未完全排除负期望。
3. 他的 0.5–0.8 价格带账户 ROI 为 +1.90%，但我们的 7 天持有模型在 0.5–0.8 为负（全 eligible -10.26%）。这说明他的 0.5–0.8 利润来自主动周转/退出或更强的选品，**不是单纯“买入并持有到结算”能复刻的**。
4. 继续做 2–4 周 Shadow，至少覆盖 50–100 个结算事件；在置信区间下界高于盈亏平衡率之前，不进入真实资金。

## 4.3 与目标账户真实成交对账（关键校验）

目标账户公开数据（`leader-strategy-decomposition-20261020.json`、`leader-trade-flow-20261020.json`）给出的真实结果，和我们回测差很多：

| 价格带 | 目标账户已平仓 ROI | 目标账户 matched-flow ROI | 我们的队列回测 ROI | 我们的全 eligible ROI |
|---|---:|---:|---:|---:|
| 0.5–0.8 | +1.90% | +0.74% | 未建模 | -10.26% |
| 0.8–0.9 | +0.18% | -5.66% | 未建模 | -10.37% |
| 0.90–0.95 | +0.72% | +0.73% | -1.23% | -3.39% |
| 0.95–0.97 | **+0.225%** | **+1.24%** | **+4.10%** | **+2.17%** |
| 0.97–0.99 | **+0.815%** | **+0.001%** | **+1.81%** | **+1.54%** |
| 0.99–1.0 | +0.255% | +0.099% | +0.40% | +0.51% |

关键事实：

- 目标账户整体单次周转 ROI 约 **0.532%**，390 个已平仓、281 盈 / 86 亏；他的核心是**高周转 × 小价差**，不是每笔 4%。
- 我们的 0.95–0.97 队列模型 +4.10%，是目标账户同 band 已平仓 ROI 的约 **18 倍**；0.97–0.99 的 +1.81% 也约为其 2.2 倍。这个差距不能忽略。
- `leader-trade-flow` 的 matched round-trip PnL 整体为 **-0.2055%**，说明仅靠“买入后卖出”的 matched flow 并不赚钱；他的正收益更可能来自持仓到结算、库存管理、奖励/返佣和选品，而不是简单的被动 bond carry。
- 0.5–0.8 是目标账户已平仓 ROI 最高的 band（+1.90%），但我们的 7 天持有模型为 -10.26%。这说明他的 0.5–0.8 利润不是“买入并持有”能复刻的，必须有主动退出/周转或更强的选品。

因此：

1. 当前 BOND 回测是一个**理想化假设模型**，不是已证明的目标策略复刻；不能把 4.10% 当作可实盘收益。
2. 7 天 Shadow 仍然值得跑，但它的目的应是验证“真实盘口下能否以接近目标账户的 0.2%–0.8% 单次 ROI 成交”，而不是追求 4%。
3. 如果 Shadow 出现单笔远高于 1% 的收益，应优先怀疑成交假设/队列假设，而不是庆祝策略成功。
4. 要真正复刻目标账户，下一步必须做**逐笔库存账本**：买入时间、卖出时间、剩余库存、结算赎回、奖励/返佣、现金流，和 Data API 的 closed-position 对账；仅靠价格带回测不够。

## 4.4 目标账户收益结构再判断
> 注：本节基于仅 1,034 条 trades 的旧样本；4.5 用 5,155 条 activity TRADE 修正了 matched flow 和持有期。以下保留为“样本偏差”记录。

`leader-trade-flow-20261020.json` 的逐笔配对和持有期数据表明，目标账户不是单纯的“高概率债券 carry”：

- 旧样本持有期：中位数 **3.4 天**，p90 **32.1 天**；全量 activity 账本见 4.5（2.02 天 / 10.96 天）。
- 旧样本 matched round-trip ROI **-0.2055%**；全量 activity 账本见 4.5（+0.748%）。
- 旧样本 open inventory 约 **1.13M shares / 1.04M cost**；全量 activity 账本见 4.5（351k shares / 344k cost），仍说明他承载大量库存。
- 最大盈利 round trip 多集中在 geopolitics：例如 0.23 → 0.976、0.057 → 0.999、0.70 → 0.83、0.57 → 0.73；这些是事件重定价，不是 0.95–0.99 的 2–3c carry。
- 最大亏损 round trip 也集中在 0.8–0.99：例如 0.879 → 0.01、0.98 → 0.93、0.87 → 0.722、0.89 → 0.78。高概率并非无风险。

因此更准确的判断是：

> 目标账户更像**事件驱动 + maker 执行 + 大库存周转**的混合策略，而不是纯被动债券做市。  
> 他的 edge 主要来自事件选择、库存管理和退出纪律；仅复刻“0.95–0.99 挂买单并持有到结算”无法解释他的收益，也无法复制他的 0.5–0.8 和 geopolitics 利润。

这也是为什么我们的 BOND 回测虽然样本内为正，但不能直接当作“他的策略复刻”。下一步要么做逐笔库存账本并尝试学习他的事件选择/退出，要么承认当前策略只是另一个低收益假设，不应投入真实资金。

## 4.5 逐笔库存账本复核（2026-10-21）

新增：

- `scripts/snapshot-leader-ledger.mjs`：抓取 trades、positions、closed-positions、activity；
- `scripts/analyze-leader-ledger.mjs`：按 asset 做 FIFO 库存账本，分离 matched round-trip、settlement、open mark-to-market。

本次快照：5,155 条 TRADE activity（2026-03-01 至 2026-10-16）、390 个 closed positions、5 个 open positions。

| 买入价格带 | matched ROI | 盈利/亏损笔数 |
|---|---:|---:|
| 0–0.5 | +17.61% | 68 / 5 |
| 0.5–0.8 | +0.94% | 623 / 239 |
| 0.8–0.9 | +0.69% | 1,137 / 308 |
| 0.9–0.95 | +1.39% | 438 / 128 |
| 0.95–0.97 | **+1.26%** | 376 / 45 |
| 0.97–0.99 | **+0.85%** | 720 / 131 |
| 0.99–1.0 | +0.31% | 471 / 19 |

按事件类别（基于标题关键词，仅作研究分组）：

| 类别 | matched PnL | matched ROI | 盈利/亏损笔数 |
|---|---:|---:|---:|
| Geopolitics | +13,112 | +0.825% | 2,587 / 608 |
| Politics | +2,481 | +1.33% | 344 / 56 |
| Other | +2,563 | +0.815% | 717 / 149 |
| Macro / Finance | +148 | +0.19% | 27 / 22 |
| Crypto | -186 | -0.35% | 100 / 35 |
| Sports | -140 | -0.69% | 58 / 5 |

结论：利润几乎全部来自 **Geopolitics + Politics**；Crypto/Sports 在样本内为负。可学习的第一步不是继续调 0.95–0.99，而是把市场选择收缩到政治/地缘事件，并研究他在这些事件中的进出时机。
账本汇总：

- matched PnL：**+14,342 USDC**，matched ROI **+0.748%**；
- settlement PnL（可匹配的 closed lots）：+3,637 USDC；
- all closed PnL：+17,979 USDC，ROI +0.802%；
- 全周期 closed-position API：+29,919 USDC，5.63M 买入，ROI +0.532%（覆盖更早历史）；
- 持有期中位数 **2.02 天**，p90 **10.96 天**；约 38% <1 天、41% 1–7 天、21% >7 天。

结论：

1. 目标账户的 matched trade flow **是正的**（+0.748%），不是上一版只用 1,034 条 trades 时的 -0.2055%；上一版样本不完整。
2. 他的 edge 分布在多个价格带：0.9–0.97 最强，0.5–0.8 也正；0.99–1 仍有 +0.31%。不是单一“高概率债券 carry”。
3. 我们的 BOND 0.95–0.97 队列回测 +4.10%，仍是目标账户同 band matched ROI +1.26% 的约 3.3 倍，是 closed-position ROI +0.225% 的约 18 倍；仍明显高估。
4. 更准确的画像：**高周转、多价格带的事件交易/做市混合策略**，依靠事件选择、库存周转和退出纪律。当前 0.95–0.99 持有模型只是其中一个窄切片，不能称为完整复刻。

## 4.6 BOND_SCALP 主动退出回测（2026-10-21）

背景：目标账户 matched round-trip 价差中位数 +1.0c、p90 +3.0c，且 18.4% 是负价差主动退出；纯 hold-to-settlement 的 BOND 不能复刻这种周转。因此新增 `GridProfile.BOND_SCALP`：

- 价格带：0.90–0.95、0.95–0.97、0.97–0.99、0.99–0.999
- entry offset：0 / 0.25c
- exit：+1c / +2c / +3c（limit sell，不持有到结算）
- queueFactor：1 / 2 / 5
- maxCycles：3 / 10
- requote：300s

### 4.6.1 队列模型结果（Gradle BOND_SCALP）

| 配置 | 成交/循环 | fill rate | ROI | train / test | 结算亏损 | MAE | 盈亏平衡亏损率 |
|---|---|---:|---:|---:|---:|---:|---:|
| 0.95–0.97, off0, +3c, q2, c3 | 79/90 | 49.4% | **+3.20%** | +3.19% / +3.20% | 0 | -66.8% | 3.10% |
| 0.97–0.99, off0.25, +3c, q2, c3 | 78/79 | 30.0% | **+2.20%** | +2.13% / +2.28% | 0 | -95.3% | 2.15% |

- 0.95–0.97 的 q5 版本 ROI 更高（+3.22%），但 fill rate 只有 26.3%，成交假设更脆弱，不作为首选。
- 队列模型 90 个 cycle 0 结算亏损、100% 胜率，明显优于目标账户约 81% 胜率；**这是模型乐观，不是实盘保证**。

### 4.6.2 全 eligible 成交上限模型（更保守的成交假设）

`scripts/analyze-leader-bond-style.cjs` 新增 +3c exit 后：

| 价格带 | n | 平均 ROI | 亏损率 | 卖出/结算 | 盈亏平衡亏损率 |
|---|---:|---:|---:|---:|---:|
| 0.95–0.97, +3c | 159 | +1.82% | 1.26% | 157 / 2 | 3.03% |
| 0.97–0.99, +3c | 261 | +1.44% | 0.38% | 254 / 7 | 1.80% |
| 0.90–0.95, +3c | 193 | -1.58% | 4.66% | 184 / 9 | 3.14% |

结论：

1. +3c 主动退出在 0.95–0.99 两个 band 都为正，且比 hold 的收益/风险结构更接近目标账户的 +1c/+3c 行为。
2. 0.90–0.95 即使主动退出仍为负（-1.58%），继续排除。
3. 队列模型 +3.20% / +2.20% 仍高于全 eligible 模型 +1.82% / +1.44%，也高于目标账户 matched ROI +1.26% / +0.85%；差距来自成交假设，需 Shadow 实测。
4. taker 卖出成本敏感性（0.95–0.97, q2, +3c）：maker 0bps 3.20% → 10bps 3.00% → 25bps 2.71% → 50bps 2.21% → 100bps 1.22%。若退出必须立即吃单，100bps 仍为正但收益腰斩。

证据：

- `backend/build/research/event-maker-bond-scalp-20261021-summary.csv`
- `backend/build/research/event-maker-bond-scalp-20261021-robust-cycles.csv`
- `backend/build/research/event-maker-bond-scalp-20261021-fee-sensitivity.csv`
- `backend/build/research/event-maker-bond-scalp-20261021-top-cycles.csv`
- `backend/build/research/leader-bond-style-backtest-20261021-repro.json`

### 4.6.3 当前 Shadow

- 首轮 BOND_SCALP 7d Shadow（00:57，`...-20261021`）在启动约 10 分钟后停止：0 fill / 0 cycle，`audit-stopped.json` 标记 `NO_FILLS_OR_CYCLES`。停止原因是发现它与回测参数不一致（requote 120s、maxOrderAge 3600s），先统一参数再跑。
- 2026-10-21 01:08 重启 BOND_SCALP 7d Shadow（`...-20261021-v2`）：0.95–0.99、+3c、maxCycles=3、fillVolumeFactor=2、flowGuard=0、**requote 300s、maxOrderAge 1800s**、最多 20 市场，与回测配置一致。
- 启动时选中 8 个市场 / 16 个 token，WS 已订阅；已挂出第一个 maker BUY。
- 进程：wrapper PID 2476、Gradle java 11084、app java 10360；输出目录 `backend/build/research/event-maker-bond-scalp-shadow-7d-20261021-v2`。
- 7 天后用 `scripts/audit-bond-shadow.cjs` 审计 fill rate、平均入场/退出价、MAE、结算亏损、重复 fill key 和真实 ROI；单笔 ROI 若远高于 1%，先怀疑队列/成交假设。

## 5. 结论与风险

**可借鉴的部分（尚未验证可盈利）：**
- 目标账户实际是多价格带事件交易 + 0.95–0.99 债券切片；不能只看单一价格带；
- maker-only 挂单，靠 taker 流成交，不追价；
- 小单、多市场、分散事件；
- 1–7 天周转，部分持有到结算；
- 参与流动性奖励与 maker rebate。

**不能忽略的风险：**
- 0.95–0.97 的队列模型虽然 0 次结算亏损，但全 eligible 模型有 3/159 次亏损，95% CI 上限 5.40% 高于 3.98% 盈亏平衡率；目前不能证明 edge 在统计上显著为正。
- 0.97–0.99 的 95% CI 上限 2.14% 也略高于 1.90% 盈亏平衡率，只能算边界性正期望。
- 0.90–0.95 在队列模型为 -1.23%、全 eligible 模型为 -3.39%，10% 左右亏损率高于 7.22% 盈亏平衡率，明确不能做。
- 回测 MAE 到 -92%～-100%，持仓过程中可能接近归零；他的 86 个亏损记录证明尾部真实存在。
- 成交率依赖队列和真实 taker 流：BOND hold 约 55%–71%（88/160、170/260、233/329）；BOND_SCALP 队列模型只有 49.4%（0.95–0.97）/ 30.0%（0.97–0.99）；queueFactor 越大成交越少。
- 回测是 2024–2026 的样本内结果，必须经过 Shadow 真实盘口验证。

**结论升级：** 当前 BOND 只是理想化假设模型，不是已验证的目标账户复刻；目标账户 0.95–0.97 已平仓 ROI +0.225%、matched ROI +1.26%；我们的队列回测 +4.10%，分别约 18 倍 / 3.3 倍。7 天 Shadow 只用于验证真实盘口成交和库存账本，不能据此直接实盘。

**下一步：**
1. 只对 `0.95–0.99` 跑 BOND_SCALP Shadow：`exitSpread=+3c`、`maxCycles=3`、order 100 USDC、多市场分散；不碰 0.90–0.95。
2. 记录真实 fill rate、平均成交价、MAE、结算亏损、maker rebate 和 liquidity reward。
3. 至少 2–4 周、覆盖 50–100 个结算事件；在亏损率置信区间下界高于盈亏平衡率之前，不进入真实资金。
4. 0.5–0.8 价格带的账户收益不能由当前“买入持有”模型复刻，若要做必须另建主动周转/退出模型。