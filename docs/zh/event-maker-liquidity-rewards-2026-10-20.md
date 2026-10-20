# Event Maker 流动性奖励复核（2026-10-20）

> 研究对象：Polymarket Politics / Geopolitics 正式奖励市场  
> 数据快照：`2026-10-20T02:31:44Z`  
> 工具：`scripts/analyze-event-maker-liquidity-rewards.mjs`  
> 产物：`backend/build/research/event-maker-liquidity-rewards-20261020/reward-snapshot-filtered.json`

## 1. 结论先行

1. **流动性奖励是真实收益来源，但不能救活原来的 0.97–0.99 单边 BUY maker。**
   - 当前扫描的 692 个有效奖励市场中，0.97–0.99 价格带没有可交易奖励池；扫描到的 0.90 以上奖励市场集中在 0.905–0.955。
   - 原策略的 `orderNotional=25` 通常达不到 `rewardsMinSize`，且 0.99 以上必须双边才有分数。
2. **把价格带下移到 0.90–0.95 后，奖励收入存在，但绝对收益很小。**
   - 过滤后 7 个 0.90+ 市场：日奖励池 662 USDC，估计我们日奖励 0.2266 USDC，占用资金 267.36 USDC。
   - 表面日收益 0.0847%，简单年化约 30.9%；但每个市场将在数周内结算，年化数字不代表可持续容量。
   - 我们的估计依赖当前订单簿竞争；奖励每分钟随机采样一次，实际到账会波动。
3. **奖励策略的真正风险不是价差，而是单边成交后的方向暴露。**
   - 0.90–0.95 的 paired bid 只有在两边都成交时才接近无方向风险。
   - 如果只有一边成交，另一边未成交，尾部一次 -90%~-95% 可以吞掉几个月奖励。
   - 因此下一阶段不能直接上实盘；必须先做“单边成交后立即对冲”的 Shadow/paper 验证。
4. **目标地址的奖励占比不高。**
   - 公开 activity 的 `REWARD` 记录：2025-07-19 至 2026-10-17 共 446 天，累计 4,195.28 USDC。
   - 最近 30 天 70.35 USDC，最近 7 天 5.49 USDC；当前公开仓位约 20,084 USDC。
   - 说明 adonpoli 的主要收益仍来自成交、库存周转和价格变化，奖励只是辅助，不是可单独复制的印钞机。

## 2. 官方公式与本次实现

官方流动性奖励按每个 UTC 日、每分钟随机采样一次订单簿：

```text
S(v,s) = ((v - s) / v)^2 * b
Q_one  = Σ S(v,Spread) * BidSize + Σ S(v,Spread) * AskSize(complement)
Q_two  = Σ S(v,Spread) * AskSize + Σ S(v,Spread) * BidSize(complement)
Q_min  = max(min(Q_one,Q_two), max(Q_one/c,Q_two/c))   # 中点 0.10–0.90
Q_min  = min(Q_one,Q_two)                              # 中点 <0.10 或 >0.90，必须双边
Q_norm = Q_min / Σ Q_min
Q_epoch = Σ over 1,440 samples
Q_final = Q_epoch / Σ Q_epoch
reward = Q_final * market_rewards
```

本次计算：

- 扫描 Politics + Geopolitics 各 1,000 个 active 市场；
- 识别 692 个当日有正 `rewardsDailyRate` 的市场；
- 过滤 `volume24h >= 1,000`、剩余时间 `>= 24h`、双边订单簿新鲜、对手 Q_min 有效；
- 对 500 个高奖励池市场抓取两个 outcome 的 CLOB book；
- 以 `rewardsMinSize` 作为默认双边订单 size；
- 以完整可见订单簿聚合为一个对手，计算 `Q_min`。由于 `Σ min(a_i,b_i) <= min(Σa_i,Σb_i)`，这种方法会低估我们的份额，属于保守近似；
- 使用 post-only join best bid，不假设更好队列位置。

**仍然存在的偏差：**

- 当前快照不能代表全天 1,440 次采样；
- 订单簿 level 是聚合 size，无法识别单个 maker 是否满足 `rewardsMinSize`；
- 未计入真实成交、库存、gas、taker hedge 成本；
- 奖励池可能提前结束或调整；
- 0.90+ 的高概率市场样本只有 7 个，统计量很小。

## 3. 过滤后结果

### 3.1 全部价格带

| 指标 | 数值 |
|---|---:|
| 分析市场 | 207 |
| 有效估计 | 205 |
| 日奖励池 | 20,211 USDC |
| 估计日奖励 | 173.73 USDC |
| 占用资金 | 16,898 USDC |
| 加权日收益 | 1.028% |
| 简单年化 | 375% |

这个数字**不能直接采用**：中低价市场虽然奖励高，但方向风险、单边成交和结算尾部风险远高于 0.90+；不能用奖励收益覆盖一次错误方向的重仓。

### 3.2 0.90+ 高概率带（唯一值得继续验证的风险受限区间）

| 市场 | 日池 | 中点 | 最低 size | 估计份额 | 估计日奖励 | 资金 | 日收益 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Michigan governor (Dem) | 100 | 0.9275 | 80 | 0.12% | 0.1224 | 79.60 | 0.154% |
| US x Yemen ceasefire Oct 31 | 300 | 0.9550 | 20 | 0.02% | 0.0455 | 19.80 | 0.230% |
| Arizona governor (Dem) | 40 | 0.9250 | 60 | 0.08% | 0.0337 | 59.40 | 0.057% |
| Government shutdown+House | 3 | 0.9095 | 20 | 0.48% | 0.0144 | 19.66 | 0.073% |
| House control | 214 | 0.9150 | 50 | 0.00% | 0.0050 | 49.50 | 0.010% |
| Montana Senate (Rep) | 3 | 0.9200 | 20 | 0.13% | 0.0039 | 19.60 | 0.020% |
| Florida Senate (Rep) | 2 | 0.9050 | 20 | 0.09% | 0.0019 | 19.80 | 0.009% |
| **合计** | **662** | - | - | - | **0.2266** | **267.36** | **0.085%** |

解释：

- 这不是“每天稳定赚 0.085%”。市场很快结算，奖励池只在存续期支付；资金可复用，但需要持续找新市场。
- 以当前 7 个市场估算，满仓运行约 30% 简单年化；一次未对冲的 0.95 单边成交，亏损约 95% 该市场资金。
- 仅靠 reward，不足以支持“无风险高收益”的结论。

## 4. 代表性市场：US x Yemen ceasefire

快照：

- 中点约 0.955；
- `rewardsMinSize=20`，`rewardsMaxSpread=6.5c`；
- 日奖励池 300 USDC；
- YES best bid 0.95 / best ask 0.96；
- NO best bid 0.04 / best ask 0.05；
- 双边各挂 20 股时，资金约 19.80 USDC，估计日奖励 0.0455 USDC。

关键风险：

- 若 YES 0.95 bid 成交而 NO 0.04 bid 未成交，持仓是 20 股 YES，方向暴露约 19 USDC；
- 立即用 NO ask 0.05 对冲，则总成本约 1.00，价差收益接近 0，奖励还需覆盖 taker 成本和滑点；
- 双边都成交时，0.95 + 0.04 = 0.99，结算收回 1.00，另有约 0.20 USDC 的配对价差；
- 因此策略必须是“**双边挂单 + 单边成交后快速对冲**”，不能只做 reward quote 不管库存。

## 5. 与目标地址的关系

adonpoli 当前公开仓位主要是 NO：

- 约 11,800 股 NO @ 0.843；
- 5,000 股 NO @ 0.900；
- 3,000 股 NO @ 0.950；
- 3,000 股 NO @ 0.850。

这更像是“在高概率方向做库存和价格收敛”，而不是纯双边 reward farming。公开奖励收入约 2 USDC/天，相对 20,000 USDC 仓位只有约 0.01%/天，无法解释其全部收益。

## 6. 下一步（必须完成，不能跳过）

| ID | 任务 | 验收 |
|---|---|---|
| RWD-1 | 实现 reward score 的 Kotlin 生产计算器与单元测试 | 与官方 worked example / 本脚本结果一致 |
| RWD-2 | 实现 paired-bid Shadow：YES/NO 两边 post-only BUY、按 rewardsMinSize 定量 | 能持续记录两边订单、成交、奖励估计 |
| RWD-3 | 单边成交后自动 IOC hedge，记录 hedge 滑点和延迟 | 不留下未对冲方向暴露 |
| RWD-4 | 0.90–0.95 价格带 72h 前瞻 Shadow | 至少覆盖 7 个市场、真实 book 更新、0 未处理错误 |
| RWD-5 | 统计 reward 实际/估计偏差与单边成交频率 | 给出可复现报告，再决定是否小资金实盘 |

## 7. 当前判定

- **不跟单 adonpoli。**
- **不启用 0.97–0.99 单边 maker 实盘。**
- **流动性奖励只作为 0.90–0.95 hedged paired-bid 的研究方向。**
- 在 RWD-1 到 RWD-4 完成前，reward farming 也不能实盘。