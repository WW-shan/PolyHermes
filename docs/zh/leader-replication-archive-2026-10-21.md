# 跟单策略研究：收尾归档（2026-10-21）

> 状态：**研究完成并归档；实盘未启动。**
> 目标账户：`0x0260366a9add43c25d4f4d35d2210526c6750d88`
> 结论：**策略内核可复刻，收益量级不可复刻。** 几千 USDC 下机械上限约 **30% 年化**，目标账户的 100%+ 来自周转次数与奖励，不是更高的单笔胜率。

---

## 1. 最终可复现策略（core2-flat12）

`0.97–0.99 Geopolitics + 0.95–0.97 Politics | $500/档 | 8 档 | 3–7 天 | close 前 12h taker flatten | 2 tick 滑点 | 4c 目标`

| 资金 | 年化 | 年 PnL | 总 PnL(2.6y) | 成交 lots | 资金利用率 | 尾部亏损市场率 |
|---:|---:|---:|---:|---:|---:|---:|
| $2,000 | **35.1%** | $701 | $1,835 | 142 | 78% | 2.1% |
| $3,000 | **30.5%** | $914 | $2,391 | 185 | 68% | 3.5% |
| $4,000 | 27.5% | $1,101 | $2,881 | 218 | 59% | 3.2% |
| $5,000 | 23.8% | $1,189 | $3,111 | 236 | 52% | 4.6% |
| $6,000 | 20.7% | $1,244 | $3,255 | 251 | 45% | 4.4% |

- $3k 分年：2024 +$606 / 2025 +$796 / 2026 +$989，三年全正。
- 最大回撤：$59.31（约本金 2%）。
- 1% 平仓滑点压力：PnL $2,391 → $1,926（约 24.6%/年）。
- **绝对收益在 $5–6k 饱和，约 $1,200/年封顶。**

### 尾部审计（PASS）

| 腿 | 尾部市场 | 亏损市场 | 亏损率 | Wilson 95% | 盈亏平衡率 | 判定 |
|---|---:|---:|---:|---|---:|---|
| 0.97–0.99 Geopolitics | 52 | 1 | 1.92% | [0.34%, 10.12%] | 45.69% | PASS |
| 0.95–0.97 Politics | 18 | 2 | 11.11% | [3.10%, 32.80%] | 49.50% | PASS |

### 费率核验（关键）

- 官方 taker 公式：`fee = shares × feeRate × p × (1 − p)`；maker 为 0。
- 抽样当前 Politics 市场：`feeType=zero_fees`、`feeSchedule.rate=0`。
- 回测中 4 个历史 Politics 市场 CLOB `taker_base_fee=0`。
- 保守压力：即使按 4% 费率重算，$3k 年化仅从 **30.47% → 30.21%**，结论不变。
- 因此回测“零手续费”假设对这些市场成立；`--taker-fee-rate` 已加入脚本用于未来复核。

---

## 2. 为什么复刻不了他的 100%+

| 项目 | 目标账户 | 我们（$3k） |
|---|---:|---:|
| 单次周转 ROI | 0.532% | 2.0–2.5%（更高） |
| 年周转次数 | ~200–330x | 10–30x |
| Round trips | 4,747 / 232 天（20.5/天） | ~71 lots/年 |
| 单市场中位最大库存 | ~7,000 股 ≈ $6,800 | 单市场最多 $3,000（全部本金） |
| 奖励/返佣 | $2,872 / 232 天（约 22.6%/年） | 拿不到 volume tier |

**差距是规模与周转，不是信号质量。**

---

## 3. 已否定方向（避免重复踩坑）

- 机械止损：0.97–0.99 为 -1.88%，价格越低越差（0.5–0.8 为 -47.65%）。
- 低价格带 + 24h 动量过滤：0.5–0.8 为 -23.67%，0.8–0.9 为 -12.70%。
- 加入 `0.99–1.0` 两腿：成交多但 edge≈0，尾部亏损率升到 19%，年化降到 26.1%。
- 加入全部 6 个 band×tag：**-31.4%/年**，被 `0.97–0.99 Politics`（-3.9%）拖垮。
- 加入 `0.95–0.97 Geopolitics`（core3）：年化 31.3%，但单市场最大亏损 -$1,279、回撤 44%、2025 年为负 → 拒绝。
- 3 天短窗口：edge 变差，`0.99–1.0` 尾部亏损率 56–68%。
- 流动性奖励/paired-bid：5 秒对冲净 -$2.40，立即对冲净 -$1.64，安全门槛 0 个候选通过。
- 完整集套利：存在正收益但容量小、锁仓久、非原子执行风险大。
- Calendar spread：快照无正收益机会。

---

## 4. Shadow 与实盘状态

- 已启动 14 个 Shadow 臂（12 个旧臂 + 2 个 core2-flat12 新臂）。
- 全部 **WAITING，0 fills / 0 cycles / 0 settlements**。
- 原因：当前无合格市场。最近扫描 Geopolitics 1490 个市场 `eligibleNow=0`；Politics 同样 `qualified=0`。
- Gate 候选已切换为 `core2-flat12-2c`（两条腿合并评估）。
- **真实 Live 未执行**：需要账户、资金、API 凭证和明确授权。

---

## 5. 复现命令

```powershell
# 1) 生成 band lots（含费率压力参数）
node scripts/analyze-bond-ladder.cjs `
  --dataset backend/build/research/event-maker-full-v2-7d-20261019-dataset.json.gz `
  --output backend/build/research/bond-breadth-q2-500-mt8-flat12-20261021.json `
  --tranche-notional 500 --queue-factor 2 --max-tranches 8 `
  --horizon-days 7 --min-hours-to-close 72 --max-hours-to-close 168 `
  --flatten-hours-before-close 12 --flatten-slippage-ticks 2 `
  --levels-cents 0,0.5,1,2,3,4,5 --sell-target-cents 4 `
  --bands 0.95,0.97,0.99,1.0000001 `
  --lots-output backend/build/research/bond-breadth-lots-q2-500-mt8-flat12-20261021.json

# 2) 资金约束组合（core2）
node scripts/analyze-bond-portfolio-multi.cjs `
  --input backend/build/research/bond-breadth-lots-q2-500-mt8-flat12-20261021.json `
  --output backend/build/research/bond-portfolio-core2-flat12-3000-20261021.json `
  --capital 3000 --max-markets 12 --target-cents 4 `
  --pairs "0.97-0.99:Geopolitics,0.95-0.97:Politics"

# 3) 尾部审计
node scripts/audit-bond-tail-risk.cjs `
  --input backend/build/research/bond-core2-flat12-audit-input-20261021.json `
  --output backend/build/research/bond-core2-flat12-audit-20261021.json
```

## 6. 本次代码改动

- `scripts/analyze-bond-portfolio-multi.cjs`：多 band×tag 资金约束组合模拟。
- `scripts/analyze-bond-ladder.cjs`：新增 `--taker-fee-rate`，对 FLATTEN（taker）腿计费。
- `scripts/evaluate-bond-shadow-gate.cjs`：候选组切换为 `core2-flat12-2c`。
- `scripts/start-bond-shadow-suite.ps1`：新增 `core2-flat12-geo` / `core2-flat12-pol` 两个 Shadow 臂。

## 7. 归档说明

- OpenSpec 已完成变更已归档：
  - `openspec/changes/archive/2026-10-21-add-leader-pool`
  - `openspec/changes/archive/2026-10-21-add-leader-research-agent`
- 本文件与 `leader-replication-ceiling-2026-10-21.md` 为最终研究记录。
- 所有 Shadow 进程在收尾时停止；`backend/build/research/` 为本地回测证据（gitignored）。
