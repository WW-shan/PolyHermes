# Event Maker 强安全候选报告（2026-10-19）

> 候选：`p0.97-0.99_off0.0_spread1.0_q20.0_c1_r300`<br>
> 数据：4,189 个 Politics / Geopolitics 已结算市场，10 万美元成交额下限，2024-01-01 至 2026-10-01。<br>
> 执行模型：post-only、queue factor 20x、max order age 1,800 秒、requote 300 秒、取消后必须在后续成交打印才可重新排队。<br>
> Guard：3,600 秒滚动窗口，signed flow <= -0.4 且价格跌幅 >= 0.5 美分时撤买。<br>
> 结论：~~通过 12h–7d 的八窗口回测，可以进入严格 Shadow；统计尾部仍未证明，不能直接实盘。~~
>
> **2026-10-20 更正（优先于本文旧数字）**：八窗口产物生成时漏传了 Shadow/Live 实际使用的活动门槛；补上门槛后，正式候选在 1d 窗口出现 1 次结算亏损、ROI -0.459%、测试期 -0.808%，八窗口全通过配置为 0。本文第 3–4 节与结尾的“零结算亏损 / 可进入 Shadow”结论作废。详见 [活动门槛口径复核](event-maker-activity-gate-recheck-2026-10-20.md)。当前策略不得实盘、不得跟单。

## 1. 候选参数

| 参数 | 值 |
|---|---:|
| 参考价格带 | 0.97 <= price < 0.99 |
| entry offset | 0.0 美分 |
| maker exit spread | 1.0 美分 |
| queue factor | 20x |
| max cycles / market | 1 |
| requote | 300 秒 |
| max order age | 1,800 秒 |
| post-only | 是 |
| taker fallback | 禁止 |
| 订单金额 | 25 USDC（Shadow） |

候选只做近结算热门结果一侧的被动 maker，买入后挂 1 美分价差卖出；不追单、不扫单、不跟目标地址。

## 2. 回测方法修正

本轮发现并修复了两个会显著改变结论的问题：

1. **旧 24h 数据集的入场前历史不足**：只抓了入场前 1 小时，导致很多市场没有参考价；改用 7 天成交数据集重放 12h/1d/2d/.../7d 窗口。
2. **回测没有模拟 Live 的最大挂单年龄**：旧回测允许买单挂 50 小时后成交，而 Shadow/Live 会在 1,800 秒撤单。现在回测在成交前执行同样撤单，并要求撤单后至少等到下一笔打印才能重新排队。

修正后，之前 3d/6d 的结算归零消失，说明它们来自“过期挂单在坏市场里迟到成交”的回测失真。这个修正也补了两条单元测试。

## 3. 标准 24 小时结果

以 7 天数据集重放 24h 入场窗口：

| 指标 | 数值 |
|---|---:|
| eligible markets | 566 |
| filled markets | 93 |
| cycles | 93 |
| SELL 回合 | 89 |
| SETTLE 回合 | 4 |
| 结算亏损 | **0** |
| 胜率 | **100.000%** |
| 总 PnL | **+97.8690** |
| 总 ROI | **+1.0524%** |
| 最差市场 PnL | **+1.0111** |
| 最大回撤 | **0** |
| 平均持仓 | 9.53 小时 |
| 平均成交等待 | 280 秒 |
| max adverse excursion | -92.02（期间 mark-to-market，不是已实现亏损） |
| break-even loss rate | 1.041% |
| 训练期 | 27 回合，ROI +1.0378% |
| 测试期 | 66 回合，ROI +1.0583% |
| Guard 触发 | 408 次 |

回合 PnL 区间为 `+1.011 .. +2.564`：89 个 SELL 合计 `+90.736`，4 个 SETTLE 合计 `+7.133`。虽然实现收益全部为正，但 MAE 仍接近 `-92`，说明持仓期间可能经历接近归零的 mark-to-market，不能把“零已实现亏损”理解为无风险。

## 4. 跨窗口稳定性

同一候选、同一数据快照、同一 Guard 和最大挂单年龄，在 8 个入场窗口重放：

| 入场窗口 | 合格市场 | 成交/回合 | 结算亏损 | PnL | ROI | 训练回合 / ROI | 测试回合 / ROI |
|---|---:|---:|---:|---:|---:|---:|---:|
| 12h | 455 | 63 | 0 | +65.09 | +1.033% | 22 / +1.038% | 41 / +1.030% |
| 1d | 566 | 93 | 0 | +97.87 | +1.052% | 27 / +1.038% | 66 / +1.058% |
| 2d | 599 | 96 | 0 | +101.82 | +1.061% | 39 / +1.047% | 57 / +1.070% |
| 3d | 603 | 77 | 0 | +83.91 | +1.090% | 31 / +1.123% | 46 / +1.067% |
| 4d | 626 | 87 | 0 | +96.37 | +1.108% | 35 / +1.097% | 52 / +1.110% |
| 5d | 586 | 75 | 0 | +84.76 | +1.130% | 31 / +1.074% | 44 / +1.170% |
| 6d | 562 | 78 | 0 | +90.83 | +1.164% | 42 / +1.171% | 36 / +1.160% |
| 7d | 260 | 61 | 0 | +66.77 | +1.095% | 33 / +1.106% | 28 / +1.080% |

所有窗口的最差市场 PnL 都是 `+1.011`，最大回撤为 0，结算亏损为 0。最低窗口 ROI 是 12h 的 `+1.033%`。

## 5. 全窗口配置交集

在 864 个配置中，要求：

- 8 个窗口全部 `settled_losses = 0`
- 每个窗口总 ROI > 0
- 每个窗口训练 ROI > 0、测试 ROI > 0
- 每个窗口训练成交市场 >= 20

只有 **2 个配置**通过：

| 配置 | 最差窗口 ROI | 说明 |
|---|---:|---|
| `p0.97-0.99_off0.0_spread1.0_q20.0_c1_r300` | **+1.033%** | 当前正式候选 |
| `p0.97-0.99_off0.0_spread0.5_q20.0_c1_r300` | +0.540% | 更保守但收益约减半 |

候选在 8 个窗口的 ROI 范围是 `+1.033% .. +1.164%`，没有出现某个窗口转负。

## 6. Guard A/B（同数据、同执行模型）

24h 窗口，只切换 Guard：

| 指标 | 无 Guard | Guard |
|---|---:|---:|
| cycles | 101 | 93 |
| SELL | 96 | 89 |
| SETTLE | 5 | 4 |
| 结算亏损 | **1** | **0** |
| PnL | +5.05 | +97.87 |
| ROI | +0.05% | +1.052% |
| 最差市场 PnL | -100.00 | +1.0111 |
| 最大回撤 | 100 | 0 |
| 训练 ROI | +1.04% | +1.038% |
| 测试 ROI | **-0.39%** | **+1.058%** |

Guard 不只是删除一个亏损回合，还把测试期从负收益翻正。它不是统计意义上的尾部保证，但对当前候选是必要的执行层保护。

## 7. 费用压力

24h 窗口：

| Maker fee / side | ROI | 最差市场 PnL |
|---:|---:|---:|
| 0 bps | +1.052% | +1.011 |
| 10 bps | +0.856% | +0.810 |
| 25 bps | +0.561% | +0.509 |
| 50 bps | +0.069% | +0.006 |
| 100 bps | -0.914% | -0.999 |

50 bps 已接近盈亏平衡，100 bps 明确亏损。3d/7d 窗口在 50 bps 下仍为正（约 +0.124% / +0.164%），但没有额外安全垫。

## 8. 参数邻域

`candidate-neighborhood` 网格共 1,008 个配置。固定候选价格带、offset=0、spread=1、cycles=1、requote=300 时：

| queue factor | filled | settled losses | ROI | 最差市场 |
|---:|---:|---:|---:|---:|
| 10 | 127 | 1 | +0.251% | -100.00 |
| 15 | 107 | 0 | +1.049% | +1.011 |
| 20 | 93 | 0 | +1.052% | +1.011 |
| 25 | 85 | 0 | +1.052% | +1.011 |
| 30 | 74 | 0 | +1.061% | +1.011 |
| 40 | 65 | 0 | +1.091% | +1.011 |
| 50 | 55 | 0 | +1.101% | +1.012 |

- q15–q50 是连续安全区；q20 不是孤立尖峰。
- 固定 q20 时，spread `0.75 / 1.0 / 1.25 / 1.5` 均为 0 结算亏损；spread `1.75 / 2.0` 各出现 1 次结算亏损。
- 固定 q20/spread1，offset `0 / 0.25 / 0.5 / 1.0` 均为 0 结算亏损，但 offset 越大成交越少。
- 在 24h 窗口，邻接价格带 `0.95-0.97` 和 `0.985-0.995` 在 q20/spread1 下也均为 0 结算亏损；不过它们的跨窗口/费用表现不如正式候选。

## 9. 尾部风险证明

- 24h 窗口 93 个零亏损回合，rule-of-three 的 95% 上界约为 `3 / 93 = 3.23%`。
- 候选盈亏平衡亏损率约为 1.041%，所以 3.23% 的上界仍高于可承受线。
- 要把上界压到 1.041% 以下，需要约 `3 / 0.01041 = 289` 个零亏损独立回合。
- 8 个窗口使用的是同一批市场/成交，不能把 63+93+96+... 相加当作独立样本。
- 因此当前证据支持“进入 Shadow”，不支持“尾部已证明安全”。

## 10. 选择偏差边界

候选是在完整研究样本中按“跨窗口零结算亏损、训练/测试方向一致、参数邻域连续”筛出的。虽然后续没有用测试集反选参数，但候选不是预注册假设，2026 分段不是完全盲测。全窗口交集只有 2 个配置，降低了单点过拟合概率，但不能替代真正未参与研究的前瞻 Shadow。

## 11. Shadow 计划

正式 Shadow 参数：

```text
price 0.97..0.99
minSpreadTicks 2
entryOffsetCents 0
exitSpreadCents 1
fillVolumeFactor 20
maxCyclesPerMarket 1
maxOrderAgeSeconds 1800
requoteSeconds 300
flowGuard 3600s / -0.4 / 0.5c
orderNotional 25
minRemainingMinutes 720
maxRemainingMinutes 10080
```

Shadow 必须记录真实排队、部分成交、撤单竞态、markout、min/max remaining window 和最终撤单对账；回测结果不能替代真实 Live smoke。

## 12. 复现产物

```text
backend/build/research/event-maker-full-v2-7d-20261019-dataset.json.gz
backend/build/research/event-maker-guard-age1800-horizon-12h-candidate-20261019-summary.csv
backend/build/research/event-maker-guard-age1800-horizon-1d-candidate-20261019-summary.csv
backend/build/research/event-maker-guard-age1800-horizon-2d-candidate-20261019-summary.csv
backend/build/research/event-maker-guard-age1800-horizon-3d-candidate-20261019-summary.csv
backend/build/research/event-maker-guard-age1800-horizon-4d-candidate-20261019-summary.csv
backend/build/research/event-maker-guard-age1800-horizon-5d-candidate-20261019-summary.csv
backend/build/research/event-maker-guard-age1800-horizon-6d-candidate-20261019-summary.csv
backend/build/research/event-maker-guard-age1800-horizon-7d-candidate-20261019-summary.csv
backend/build/research/event-maker-candidate-neighborhood-age1800-20261019-summary.csv
backend/build/research/event-maker-no-guard-age1800-horizon-1d-candidate-20261019-summary.csv
```

数据集核验（2026-10-20）：

```text
file: event-maker-full-v2-7d-20261019-dataset.json.gz
bytes: 49,553,423
sha256: 9B8A3F84A3CA47A135ACBE277E018021EC4A2C22DCC7B953E635F07238624424
```

复核结果：

- 八窗口 summary 均为 864 个配置；
- 参数邻域为 1,008 个配置；
- 同数据无 Guard 对照为 864 个配置；
- 按“每窗口零结算亏损、总/训练/测试 ROI > 0、训练成交市场 >= 20”复算，八窗口交集为 2 个配置，与报告一致。

这些文件位于被 Git 忽略的 `backend/build/`；仓库只提交可复查的报告、命令和结论。

## 13. 最终判断

- 可以继续做严格 Shadow，不应跟单目标地址。
- 在至少约 289 个零亏损独立回合，或引入可解释、可执行的尾部限制前，不能开启真实资金。
- 当前 Live 的硬阻断仍是用户账户、资金上限、明确授权和真实小资金 smoke；不是靠继续调参可以替代的。