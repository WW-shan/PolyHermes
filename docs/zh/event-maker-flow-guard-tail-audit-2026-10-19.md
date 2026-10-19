# Event Maker 逆势流 Guard 与尾部风险审计（2026-10-19）

> 目的：验证“逆势成交流 + 价格下跌”Guard 是否能约束结算归零，并判断是否足以支持真实资金。<br>
> 结论：Guard 有防御价值，但没有解决全部尾部风险；当前策略仍不能实盘。

## 1. 实现定义

- 配置：`flowGuardWindowSeconds=3600`、`flowGuardMaxSignedFlow=-0.4`、`flowGuardMinPriceDropCents=0.5`。
- 成交流：仅统计 favorite token 的已知 taker 方向，`signedFlow=(buySize-sellSize)/(buySize+sellSize)`。
- 价格变化：窗口内第一笔到最后一笔成交价，跌幅达到 0.5 美分才触发。
- 实盘/Shadow：观察公共成交后，若触发则撤销当前 BUY，并在 Guard 解除前禁止新 BUY；SELL 退出不受影响。
- 回测：正在成交的订单只用该笔打印之前的信息判断是否撤单，避免用同一笔成交做事后过滤。启动时可用最近成交预热窗口。
- 代码：`EventMakerAdverseFlowGuard.kt`、`EventMakerBacktestEngine.kt`、`EventMakerLiveEngine.kt`。
- 测试：Guard 边界、未知方向、窗口过期、禁用开关、Buy 撤单/恢复、回测无未来函数。全量 `101 suites / 525 tests / 0 failures / 0 errors / 2 skipped`。

## 2. 三笔历史归零的因果检查

数据：`backend/build/research/tail-loss-features.json`，以及三份 7 天成交证据。

| 市场 | 原始成交价 | 成交前 1h flow | 成交前 1h 价格变化 | Guard 结果 |
|---|---:|---:|---:|---|
| China-Vietnam trade deal before June? | 0.967 | -0.746 | -0.815 美分 | 成交前已有逆势流，Guard 阻止；Guard 回测不再出现该市场亏损 |
| Will the Iran ceasefire continue through May 25? | 0.969 | -0.426 | -0.026 美分 | 原始入场可触发，但 Guard 改变了报价时点；Guard 在 1779741514 更早成交，当时前 1h flow 为 +0.126，未能阻止，仍亏 100 |
| US ceasefire against Iran continues through August 31? | 0.983 | -0.697（包含成交本身） | -0.048 美分 | 成交前 1h flow 为 +0.991；卖压只出现在触发成交的同一笔 1,022.7 股 SELL 中，因果上无法事前拦截，仍亏 100 |

关键限制：Guard 不是价格保险。它能撤掉“已经被观察到的坏环境”中的剩余买单，但不能阻止第一笔大额卖单直接吃掉已经挂在盘口的订单。

## 3. 全量 4,189 市场 A/B

基线：`event-maker-queue-grid-full-v2-20261019-summary.csv`。<br>
Guard：`event-maker-flowguard-full-v2-20261019-summary.csv`。<br>
复现：`scripts/compare-event-maker-flow-guard.ps1`。<br>
完整表：`docs/zh/event-maker-flow-guard-full-2026-10-19.md`。

| 指标 | 基线 | Guard |
|---|---:|---:|
| 配置数 | 864 | 864 |
| 成交市场数（跨配置累计） | 116,130 | 85,680 |
| 回合数（跨配置累计） | 134,216 | 96,105 |
| 正 PnL 配置数 | 101 | 138 |
| 单市场亏损 <= -99 的配置数 | 864 | 845 |
| 配置平均 ROI | -3.041% | -2.563% |
| 配置 ROI 中位数 | -2.147% | -2.164% |
| Guard 触发次数 | 0 | 226,055 |

Guard 让部分配置少亏，主流配置的平均 ROI 改善，但中位数几乎不变，845/864 个配置仍然出现过单市场归零。这说明它只是减少了部分暴露，不是完整的尾部解决方案。

## 4. 重点配置

| 配置 | 基线回合 | Guard 回合 | 基线 PnL | Guard PnL | 基线测试 ROI | Guard 测试 ROI | 结论 |
|---|---:|---:|---:|---:|---:|---:|---|
| `p0.97-0.99_off0.5_spread2.0_q1.0_c3_r0` | 278 | 252 | +224.37 | +275.66 | +0.560% | +0.397% | PnL 改善；结算亏损 3 -> 2；测试 ROI 下降 |
| `p0.97-0.99_off0.5_spread1.0_q2.0_c3_r0` | 324 | 266 | +28.63 | +72.13 | -0.062% | -0.347% | PnL 改善；测试 ROI 仍为负且变差 |
| `p0.97-0.99_off1.0_spread2.0_q5.0_c1_r0` | 137 | 94 | +78.61 | -9.23 | +0.787% | -1.515% | Guard 误杀/改价后由盈利转亏损 |

这些结果不支持“Guard 后策略已经稳定”的结论，也说明不能只挑一个改善的配置上线。

## 5. 参数敏感性

100 市场对照：

- 600 秒窗口：跨配置回合 2,211，正 PnL 配置 684，平均 ROI +0.868%，无单市场归零配置，Guard 触发 2,584 次。
- 3,600 秒窗口：跨配置回合 2,239，正 PnL 配置 744，平均 ROI +0.948%，无单市场归零配置，Guard 触发 6,037 次。
- 两个窗口都能消除该 100 市场子样本中的归零；3,600 秒的平均/中位 ROI 略好，因此本轮保留 3,600 秒。

证据：

- `backend/build/research/event-maker-flowguard-600s-100m-20261019-summary.csv`
- `backend/build/research/event-maker-flowguard-100m-20261019-summary.csv`
- `docs/zh/event-maker-flow-guard-600s-100m-2026-10-19.md`
- `docs/zh/event-maker-flow-guard-100m-2026-10-19.md`

## 6. Shadow 冒烟

严格 0.97–0.99 价格带运行时，当前无报价市场，因此另跑只用于执行链路的宽价格带 Shadow：

- 命令：`eventMakerShadow`，1 个市场、5 分钟、`minPrice=0.90`、`minSpreadTicks=1`、Guard 3,600 秒。
- 结果：201 笔唯一成交、690 次盘口更新、1 次报价、1 次停止撤单、0 fill、0 错误。
- 审计：`scripts/audit-event-maker-shadow.ps1` 返回 `passed=true`，`activeOrders=0`、`duplicateTradeRows=0`。
- 证据：`backend/build/research/event-maker-shadow-flowguard-smoke-wide-20261019/`。
- 该冒烟不用于证明收益，也不放宽正式 0.97–0.99 两臂条件。

## 7. 最终判断

- Guard 应该保留，作为执行层防御，默认 `3600s / -0.4 / 0.5c`。
- 它不是完整的尾部风险模型；已有证据显示第一笔大额卖单仍可能让挂单亏损 100。
- 回测、Shadow、执行链路和测试链路已经闭环，但策略经济性仍未达到真实资金标准。
- 真实 Live smoke 仍需要用户提供 `accountId`、资金上限、明确下单授权、时间窗口和市场范围；在此之前不得宣称策略可实盘。