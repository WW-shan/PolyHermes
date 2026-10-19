# Event Maker 活动门槛一致性核验（回测 vs Shadow）

> 日期：2026-10-19 +08:00
> 数据：Polymarket 公共成交历史、Gamma 市场元数据
> 样本：完整候选池、864 配置、7 天入场窗口
> 性质：事后一致性审查，不是新参数选择
> 上游：[7 天窗口回测](event-maker-horizon-7d-backtest-2026-10-19.md)、[窗口稳定性核验](event-maker-horizon-stability-2026-10-19.md)

## 1. 为什么做这一轮

此前的回测**没有建模 Shadow/Live 的活动门槛**（30m/2h 成交笔数、成交额、买卖各边）。而实盘发现流程只在通过该门槛的市场里报价。两者不一致意味着：回测里成交的市场，实盘可能根本不会去挂单——回测的 fill 无法代表真实可交易集合。

本轮把活动门槛原样加进回测（同一组阈值、同一统计口径、且**只统计 favorite token**，与 ``194baf0`` 修复后的实盘一致），做对照实验。

## 2. 对照结果（7 天窗口，864 配置）

门槛参数（与两臂实际运行一致）：
``30m ≥3 笔 / ≥50 USDC / 买卖各 ≥1；2h ≥20 笔 / ≥500 USDC / 买卖各 ≥3``

| 指标 | 无门槛 | 有门槛 |
|---|---:|---:|
| 训练期正收益配置 | 284 | **192** |
| 其中测试期非正 | 0 | **0** |
| 最优配置训练 ROI | 1.98% | **1.96%** |
| 最优配置测试 ROI | 1.97% | **1.96%** |
| 最优配置成交市场 | 47 | **45** |

**结论：edge 在真实活动门槛下存活。** 通过门槛后候选数从 284 降到 192（约 -32%），但训练/测试一致性和 ROI 水平基本不变。

## 3. 当前 Shadow 配置在门槛下的表现

配置 ``p0.97-0.99_off0.5_spread2.0_q1.0_c3_r300``（两臂正在跑的同族）：

| 指标 | 有门槛 |
|---|---:|
| 合格市场 | 260 |
| 成交市场 | 37 |
| 回合 | 44 |
| 结算次数 | 0 |
| 训练 ROI | +1.96% |
| 测试 ROI | +1.98% |
| 最大回撤 | 0.00 |
| 最差单市场 | +1.63 |

## 4. 尾部风险（仍然不能上线）

- 盈亏平衡亏损率 ``breakEvenLossRate = 1.93%``；
- 成交市场 37 个、观测 0 次结算亏损；
- rule-of-three 95% 上界 = 3/37 ≈ **8.11%**，**远高于**盈亏平衡线。

**0 次亏损是样本不足，不是风险已被约束。** 这与[窗口稳定性核验](event-maker-horizon-stability-2026-10-19.md)的结论一致：7 天窗口选出的配置在 14 天窗口曾出现单次结算亏损把 +1.96% 打成 -6.58%。

## 5. 结论

1. **活动门槛不破坏 edge**：加上真实门槛后，训练/测试仍全为正，ROI 水平不变。这消除了"回测与实盘选到不同市场"的最后一个已知结构性差异。
2. **门槛显著缩小候选池**（-32%），解释了实盘中"合格市场很少"的现象——这是真实约束，不是发现逻辑故障。
3. **仍不能实盘**：尾部亏损率上界（8.11%）远高于盈亏平衡线（1.93%），需要 Shadow 在真实盘口积累足够成交与结算事件。
4. **下一步**：让两臂在门槛下持续运行，收集真实 fill/cycle/markout，特别是结算事件。

## 6. 复现命令

```powershell
cd D:\Project\PolyHermes\backend
.\gradlew.bat eventMakerBacktest `
  "-PresearchProxy=http://127.0.0.1:10808" `
  "-PresearchMaxMarkets=5000" `
  "-PresearchMinVolume=100000" `
  "-PresearchEntryHorizonSeconds=604800" `
  "-PresearchConcurrency=8" `
  "-PresearchMinRecentTrades=3" `
  "-PresearchMinRecentNotional=50" `
  "-PresearchMinRecentTradesPerSide=1" `
  "-PresearchMinRecentTrades2h=20" `
  "-PresearchMinRecentNotional2h=500" `
  "-PresearchMinRecentTradesPerSide2h=3" `
  "-PresearchOutput=build/research/event-maker-gated-7d-full-20261019"
```