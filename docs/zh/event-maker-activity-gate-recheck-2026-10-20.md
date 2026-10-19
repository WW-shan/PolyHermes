# Event Maker 活动门槛口径复核（2026-10-20）

> 触发原因：复核三笔历史归零案例的 markout 时，发现回测的 `markout60sPnl` 使用了 `entryTimestamp + 60_000L`，而研究数据集时间戳是 **epoch 秒**。修正后重跑，进一步发现旧八窗口产物与"正式 Shadow 使用的活动门槛"不一致。
> 结论：**旧“八窗口零结算亏损”结论对实际 Shadow/Live 配置不成立；候选策略不能进入真实资金。**
> 性质：研究口径纠错，不是参数调优，也没有放宽任何正式门槛。

## 1. 两个独立问题

### 1.1 markout 时间单位 bug

`EventMakerBacktestEngine.enrichCycles`（隔离 worktree 分支 `fix/backtest-markout-units`）：

```kotlin
// 修复前
val markoutTarget = cycle.entryTimestamp + 60_000L
// 修复后
val markoutTarget = cycle.entryTimestamp + 60L
```

- 研究数据集的 `EventMakerTrade.timestamp`、`entryTimestamp`、`closedTimestamp` 全部是 **epoch 秒**（Data API 层 `timestamp * 1000L` 只发生在 Shadow/Live 的 `LiveTradePrint`）。
- 因此“+60_000L”实际是 **+16.67 小时**，不是 60 秒。
- 已加回归测试 `markout uses the first print at or after 60 seconds, not 60_000 seconds`。
- Shadow/Live 的 markout 路径使用毫秒并显式 `horizonSeconds * 1_000L`，没有这个问题。

复算影响（从数据集直接重算，而非读旧 CSV）：

| 产物 | 旧 CSV “60 秒 markout” | 真 60 秒 markout |
|---|---:|---:|
| 278 回合基线配置 | 总和 +109.06，均值 +0.392，负 21 个 | **总和 -283.28，均值 -1.019，负 145 个** |
| 93 回合正式候选 | 全部与真值不同 | 真值总和 -33.31，均值 -0.358，负 53 个 |
| 两笔真实 SETTLE 亏损 | -99.90 | Iran-May25 **-0.92**；US-Iran-Aug31 **-6.47** |

- 旧报告“成交后价格几乎立即跌到接近 0”的因果解释是错的：那是 16.7 小时后的价格。
- 真实路径是：China-Vietnam 在成交那一笔就直接跌到 0.142；Iran-May25 有约 30 分钟反应时间；US-Iran-Aug31 在 2–5 分钟内跌到 0.1 以下。

### 1.2 活动门槛未进入八窗口产物

- 正式 Shadow 参数包含活动门槛：30m ≥3 笔 / ≥50 USDC / 买卖各 ≥1；2h ≥20 笔 / ≥500 USDC / 买卖各 ≥3。
- 回测代码自 `0228de4` 起支持同名门槛，但 2026-10-19 22:12 生成的八窗口产物是在 **未传** `-PresearchMinRecent*` 参数的情况下跑出来的。
- 决定性核对：用当前代码、**不传门槛**重跑 1d，产物与旧八窗口文件逐行完全一致（93 回合、408 次 Guard 触发、PnL +97.8690、ROI +1.0524%）；传入正式门槛后同一配置变成 68 回合、1 次结算亏损、PnL -31.1818。
- 也就是说，旧八窗口只验证了“无活动门槛”的回测口径，而真正会被 Shadow/Live 选中的市场子集不同。

## 2. 带正式门槛的八窗口重跑

同一 7 天数据集、同一候选 `p0.97-0.99_off0.0_spread1.0_q20.0_c1_r300`、Guard 3600s/-0.4/0.5c、maxOrderAge 1800s、活动门槛与 Shadow 完全一致：

| 入场窗口 | 成交/回合 | 结算亏损 | PnL | ROI | 训练 ROI | 测试 ROI | Guard 触发 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 12h | 43 | 0 | +44.11 | +1.026% | +1.019% | +1.029% | 56 |
| 1d | 68 | **1** | **-31.18** | **-0.459%** | +1.018% | **-0.808%** | 94 |
| 2d | 60 | 0 | +61.46 | +1.024% | +1.019% | +1.028% | 116 |
| 3d | 49 | 0 | +51.75 | +1.056% | +1.021% | +1.078% | 82 |
| 4d | 47 | 0 | +48.87 | +1.040% | +1.081% | +1.020% | 107 |
| 5d | 37 | 0 | +38.79 | +1.048% | +1.023% | +1.062% | 56 |
| 6d | 35 | 0 | +37.67 | +1.076% | +1.068% | +1.087% | 37 |
| 7d | 31 | 0 | +32.67 | +1.054% | +1.022% | +1.087% | 52 |

唯一亏损回合：`Will the Iran ceasefire continue through May 25?`，入场 0.984，SETTLE 0，PnL -100。

按原筛选口径（每个窗口零结算亏损、总/训练/测试 ROI > 0、训练成交市场 ≥20）复算：

- **8 个窗口全通过的配置：0 个**（旧无门槛产物下是 2 个）。
- 即使只看 1d，正式候选也从“0 亏损、+1.052%、测试 +1.058%”变成“1 亏损、-0.459%、测试 -0.808%”。
- 训练期 ROI 仍为正（+1.018%），但样本外为负。这不是可以靠继续调参掩盖的差异。

## 3. 止损可行性（顺带结论）

用同一份逐笔数据，对 278 回合基线配置和 93 回合候选配置分别测试成交后按 print 价格触发止损：

| 止损（相对入场） | 触发次数 | 总 PnL | 亏损回合 | whipsaw |
|---|---:|---:|---:|---:|
| 无止损（实际） | 0 | +224.37 | 3 | - |
| -1c | 177 | -368.15 | 177 | 174 |
| -2c | 125 | -356.48 | 125 | 122 |
| -5c | 60 | -278.17 | 60 | 57 |
| -10c | 29 | -284.68 | 29 | 26 |

- 止损把绝大多数正常波动打掉；三笔尾部里只有 Iran-May25 有约 30 分钟反应时间。
- **不能靠成交后止损解决第一笔大额卖单尾部风险**，这个结论在修正 markout 后仍然成立；但旧报告给出的“瞬间归零”理由不成立，正确理由是止损会大量误杀。

## 4. 对当前状态的影响

- 正式候选的历史证据链需要重做：markout 修复、活动门槛口径修正、八窗口重跑。
- “八窗口全部零结算亏损”不再成立，候选策略**不能进入真实资金**。
- v6 Shadow 仍在严格门槛下运行（0 fill），其证据只覆盖执行链路，不能替代经济性验证。
- 任何“跟单目标地址”或“放宽价格带/活动门槛/q20”的做法都不会被这次修正支持。

## 5. 复现

```powershell
cd D:\Project\PolyHermes-markout-fix\backend

# 单窗口（1d）带正式门槛
.\gradlew.bat eventMakerBacktest `
  "-PresearchDataset=D:\Project\PolyHermes\backend\build\research\event-maker-full-v2-7d-20261019-dataset.json.gz" `
  "-PresearchMaxMarkets=5000" `
  "-PresearchMinVolume=100000" `
  "-PresearchFrom=2024-01-01" `
  "-PresearchTo=2026-10-01" `
  "-PresearchEntryHorizonSeconds=86400" `
  "-PresearchMaxOrderAgeSeconds=1800" `
  "-PresearchFlowGuardWindowSeconds=3600" `
  "-PresearchFlowGuardMaxSignedFlow=-0.4" `
  "-PresearchFlowGuardMinPriceDropCents=0.5" `
  "-PresearchMinRecentTrades=3" `
  "-PresearchMinRecentNotional=50" `
  "-PresearchMinRecentTradesPerSide=1" `
  "-PresearchMinRecentTrades2h=20" `
  "-PresearchMinRecentNotional2h=500" `
  "-PresearchMinRecentTradesPerSide2h=3" `
  "-PresearchFocusConfig=p0.97-0.99_off0.0_spread1.0_q20.0_c1_r300" `
  "-PresearchOutput=build/research/event-maker-gated-markoutfix-horizon-1d-20261020" `
  --console=plain
```

产物位于被 Git 忽略的 `backend/build/research/`；仓库只提交结论、命令和筛选口径。