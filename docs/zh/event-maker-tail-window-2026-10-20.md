# Event Maker 结算前尾部窗口规则（2026-10-20）

> 触发原因：活动门槛口径修正后，正式候选在 1d 窗口出现 1 次 -100 结算亏损，八窗口全通过配置为 0。
> 本轮测试一个有因果依据的**事前不报价**规则：距离市场关闭不足 N 分钟时，撤掉挂着的买单且不再新挂买单。
> 性质：结构性风险控制 + 参数敏感性测试，不是事后止损，也没有使用任何未来信息。

## 1. 规则定义

- 只使用公开的 `market.endTimestamp` 和当前时间，不使用结算结果、不看未来成交。
- 剩余时间 `< tailWindow` 时：
  - 撤掉所有 resting BUY；
  - 不再新挂 BUY；
  - SELL 退出不受限制（我们仍然需要能卖出库存）。
- `tailWindow = 0` 表示关闭，行为与旧版完全一致。
- 回测在成交判定**之前**执行撤单，保证同一笔打印不能填补一个已经被撤掉的订单。
- Shadow/Live 引擎使用同一规则；触发原因记录为 `tail_window`。

## 2. 为什么不是止损

同一份逐笔数据上的止损测试（278 回合基线）：

| 止损 | 触发 | 总 PnL | 亏损回合 | whipsaw |
|---|---:|---:|---:|---:|
| 无（实际） | 0 | +224.37 | 3 | - |
| -2c | 125 | -356.48 | 125 | 122 |
| -5c | 60 | -278.17 | 60 | 57 |

止损会把正常价格波动打掉。尾部规则的作用是**事前减少暴露**，两者不是一回事。

## 3. 1d 窗口阈值扫描

正式候选 `p0.97-0.99_off0.0_spread1.0_q20.0_c1_r300`，活动门槛、Guard、maxOrderAge 全部保持正式值：

| 尾部窗口 | 回合 | 结算亏损 | PnL | ROI | 训练 ROI | 测试 ROI |
|---:|---:|---:|---:|---:|---:|---:|
| 0（旧） | 68 | 1 | -31.18 | -0.459% | +1.018% | -0.808% |
| 2h | 68 | 1 | -31.18 | -0.459% | +1.018% | -0.808% |
| 4h | 68 | 1 | -31.18 | -0.459% | +1.018% | -0.808% |
| 6h | 63 | 1 | -36.26 | -0.576% | +1.020% | -0.841% |
| **8h** | **57** | **0** | **+58.65** | **+1.029%** | **+1.019%** | **+1.030%** |
| **12h** | **51** | **0** | **+52.25** | **+1.024%** | **+1.019%** | **+1.025%** |

关键观察：

- 那笔亏损 (`Will the Iran ceasefire continue through May 25?`) 是在剩余 **6.65 小时**时成交的，所以 2h/4h/6h 都拦不住。
- 6h 窗口反而更差：它移除了 5 笔盈利成交，却没有移除亏损。
- 8h 和 12h 都能移除该亏损；两者 ROI 接近（+1.03% / +1.02%），不是一个孤立尖峰。
- 8h 移除了 11 个回合：10 笔盈利（合计约 +10.17）和 1 笔 -100。净改善约 +89.83。

**必须说清楚的弱点**：8h 的门槛正好高于唯一一笔已观察亏损的成交时点（6.65h）。这是一个明确的过拟合风险，不能把“8h 有效”当作统计证明。

## 4. 八窗口重跑（tailWindow=8h）

| 入场窗口 | 回合 | 结算亏损 | PnL | ROI | 训练 ROI | 测试 ROI | 训练成交数 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 12h | 26 | 0 | +26.76 | +1.029% | +1.019% | +1.031% | 4 |
| 1d | 57 | 0 | +58.65 | +1.029% | +1.019% | +1.030% | 8 |
| 2d | 55 | 0 | +56.37 | +1.025% | +1.019% | +1.028% | 20 |
| 3d | 47 | 0 | +49.70 | +1.058% | +1.020% | +1.081% | 18 |
| 4d | 45 | 0 | +46.81 | +1.040% | +1.085% | +1.020% | 14 |
| 5d | 34 | 0 | +35.72 | +1.051% | +1.022% | +1.066% | 12 |
| 6d | 35 | 0 | +37.67 | +1.076% | +1.068% | +1.087% | 20 |
| 7d | 31 | 0 | +32.67 | +1.054% | +1.022% | +1.087% | 16 |

- 八个窗口全部 0 结算亏损，总/训练/测试 ROI 全为正。
- 但 **6/8 个窗口的训练成交数低于原先要求的 20**（12h 只有 4）。因此这不能算“满足原筛选口径的八窗口通过”，只能算“在薄样本上方向一致”。
- 全 864 配置中没有配置能在每个窗口都达到训练成交 ≥20 且零亏损（最多 7/8）。

## 5. 规则对整个参数族的效果

每个窗口内，864 个配置中出现过结算亏损的配置数：

| 窗口 | 无尾部规则 | 8h 尾部规则 | 变化 |
|---|---:|---:|---:|
| 12h | 652 | 451 | -201 |
| 1d | 238 | 33 | -205 |
| 2d | 288 | 144 | -144 |
| 3d | 322 | 322 | 0 |
| 4d | 298 | 298 | 0 |
| 5d | 0 | 0 | 0 |
| 6d | 262 | 262 | 0 |
| 7d | 0 | 0 | 0 |

结论：

- 尾部规则在 **12h / 1d / 2d** 明显减少亏损暴露；
- 在 3d / 4d / 6d **没有任何改善**，说明还有别的、非时间型的尾部事件；
- 所以它是**部分缓解**，不是尾部风险通解。

## 6. 实现与测试

- `EventMakerConfig.minRemainingSeconds`（回测）与 `EventMakerConfig.tailWindowMinutes`（Shadow/Live）。
- 回测在成交前撤单；Shadow/Live 在活跃订单路径撤单，并在 FLAT 阶段禁止新挂 BUY。
- 新增测试：
  - 回测：`does not fill a buy inside the configured tail window`、`tail window zero keeps legacy behavior`
  - Live：`resting buy is cancelled inside the tail window and not replaced`、`flat state does not quote inside the tail window`
- 全量：**103 suites / 566 tests / 0 failures / 0 errors / 2 skipped**。

## 7. 当前判断

- 尾部规则是**目前唯一一个有因果依据、且在短窗口明显降低亏损暴露的改动**。
- 它不能证明策略可以实盘：样本薄、只覆盖少量独立尾部事件、3d/4d/6d 仍有其他亏损。
- 下一步必须用 `tailWindowMinutes=480` 跑新的前瞻 Shadow，并积累独立回合；不能再靠调阈值或换价格带制造好看的曲线。
- 真实 Live smoke 仍然需要账户、资金上限和明确授权。

## 8. 复现

```powershell
cd D:\Project\PolyHermes-tail-window\backend
.\gradlew.bat eventMakerBacktest `
  "-PresearchDataset=D:\Project\PolyHermes\backend\build\research\event-maker-full-v2-7d-20261019-dataset.json.gz" `
  "-PresearchMaxMarkets=5000" `
  "-PresearchMinVolume=100000" `
  "-PresearchFrom=2024-01-01" `
  "-PresearchTo=2026-10-01" `
  "-PresearchEntryHorizonSeconds=86400" `
  "-PresearchMaxOrderAgeSeconds=1800" `
  "-PresearchMinRemainingSeconds=28800" `
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
  "-PresearchOutput=build/research/event-maker-tail8h-horizon-1d-20261020" `
  --console=plain
```