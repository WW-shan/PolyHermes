# Event Maker 统一 Shadow 长测报告

> 日期：2026-10-19 +08:00
> 引擎：`EventMakerLiveEngine` + `EventMakerQuotePlanner` + `EventMakerRiskPolicy` + `EventMakerShadowExecutionPort`
> 性质：真实公共盘口/公共成交上的 Shadow 测试，不签名、不下单、不接触资金
> 计划：[event-maker-delivery-plan.md](event-maker-delivery-plan.md)
> 工作日志：[event-maker-work-log.md](event-maker-work-log.md)

## 1. 一句话结论

**执行链路稳定，但在当前选中的市场里没有获得任何 maker 成交。**
因此当前策略只能证明“能安全运行、能对账、能审计”，不能证明“能赚钱”，更不能用于真实资金。

## 2. 运行配置

所有运行都使用：

- `post-only` 报价，不允许 taker fallback；
- `orderNotional=25 USDC`；
- `minPrice=0.90`、`maxPrice=0.99`；
- `requoteSeconds=120`；
- `maxCyclesPerMarket=3`；
- 公共盘口 + 公共成交；
- 按 transaction hash、token、side、size 去重；
- 成交模拟：先消耗前方队列，再按公共 taker 成交量累计；新版本默认还要求达到 `shadowFillVolumeFactor × 订单股数` 后才成交。

## 3. 长测结果

| 运行 | 时长 | 市场 | 唯一成交 | 盘口更新 | 报价 | 撤单 | 成交 | 回合 | 错误 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 基线，不筛价差 | 7,200s | 8 | 1,799 | 52,426 | 240 | 236 | 0 | 0 | 0 |
| 最小 2 tick 价差，6 市场 | 3,600s | 6 | 1,214 | 21,600 | 180 | 174 | 0 | 0 | 0 |
| 最小 2 tick 价差，更宽候选池 | 2,700s | 7 | 1,405 | 21,028 | 161 | 154 | 0 | 0 | 0 |
| 最小 2 tick + `improveTicks=2` | 1,800s | 7 | 1,406 | 14,656 | 105 | 98 | 0 | 0 | 0 |

所有运行的 `haltedReason` 均为空，`lastErrors` 为空。

证据目录：

- `backend/build/research/event-maker-shadow-2h-20261019/`
- `backend/build/research/event-maker-shadow-filtered-1h-20261019/`
- `backend/build/research/event-maker-shadow-filtered-wide-45m-20261019/`
- `backend/build/research/event-maker-shadow-aggressive-30m-20261019/`

## 4. 审计检查

### 4.1 成交去重

对 2 小时基线：

- `trades.csv` 行数：1,799；
- 按 `transaction_hash|token_id|side|size` 分组的重复键：**0**；
- `duplicateTradeEvents=2,559,995` 表示轮询/WS 重复事件被去重，不是重复成交。

### 4.2 订单事件

2 小时基线：

- 报价事件：240；
- 唯一报价订单：240；
- 撤单事件：236；
- 唯一撤单订单：236；
- 引擎错误：0；
- 未知订单状态：0。

### 4.3 状态文件

2 小时基线结束时：

- `fills=0`；
- `cycles=0`；
- `openInventoryShares=0`；
- 4 个 Shadow 活跃订单保留在状态文件中（Shadow 结束不执行真实撤单）。

## 5. 为什么没有成交

### 5.1 一档价差市场无法用 post-only 改善价格

高概率市场常见盘口：

```text
best bid = 0.979
best ask = 0.980
tick = 0.001
```

买方想改善 1 tick 就会变成 `0.980`，等于 best ask，属于跨价，会被 post-only 拒绝。
因此只能挂在 `0.979`，排在现有买单后面。

实测盘口队列示例：

- 某市场 best bid 0.979，队列约 8,389 股；
- 另一市场 best bid 0.973，队列约 1,760 股。

2 小时内这些价位的公共 taker 卖出量不足以清空前方队列，所以没有成交。

### 5.2 宽价差市场有报价空间，但 taker 流不足

筛选 2 tick 以上价差后，订单可以放在 spread 内，但选中的市场在 30～60 分钟内没有出现足够的、方向和价格都匹配的 taker 成交。

这不是订单状态机错误，而是策略选择和市场流动性问题。

### 5.3 提高改善 tick 数没有解决问题

`improveTicks=2` 运行 30 分钟，105 笔报价、98 次撤单，仍然 0 成交。
继续提高改善 tick 数会靠近甚至跨过 best ask，不能靠这个参数强行制造 maker 成交。

## 6. 与历史回测的关系

历史回测的结论同样是负面的：

- 162 组配置中，训练/测试都有足够成交市场的稳健正收益配置为 0；
- 最高 ROI 配置只有 1 个成交市场，样本不可用；
- 0.985～0.995 区间的高胜率结构会被一次结算亏损吃掉。

本次 Shadow 进一步说明：

- 即使执行链路正确，当前报价逻辑在真实盘口上也可能长时间没有 fill；
- 不能把“没有报错”误认为“策略有效”；
- 不能把历史回测的小样本正收益当成上线依据。

## 7. 当前判断

**不建议用这套策略实盘，也不建议跟单。**

如果继续研究，下一步优先级应是：

1. 采集更多市场，尤其是 2～5 tick 价差且有真实 taker 流的市场；
2. 同时报价买卖两边，而不是只做买入后等待卖出；
3. 记录更长时间的 markout（1m/5m/30m），判断逆向选择；
4. 加入真实 maker fee、rebate、流动性奖励；
5. 用逐笔订单簿重建队列，而不是只按公共成交消耗；
6. 只有在多个独立时间段出现正期望、正 markout 和可接受回撤后，才考虑极小资金实盘。

## 8. 复现命令

```powershell
cd D:\Project\PolyHermes\backend

.\gradlew.bat eventMakerShadow `
  "-PshadowProxy=http://127.0.0.1:10808" `
  "-PshadowMinutes=120" `
  "-PshadowMaxMarkets=8" `
  "-PshadowMinVolume=20000" `
  "-PshadowMinRemainingMinutes=120" `
  "-PshadowAllowNegRisk=true" `
  "-PshadowMaxQueue=100000" `
  "-PshadowMinSpreadTicks=2" `
  "-PshadowImproveTicks=1" `
  "-PshadowSnapshotSeconds=10" `
  "-PshadowOutput=build/research/event-maker-shadow-repro" `
  --console=plain
```

## 9. 最终结论

> **框架完成，执行安全测试通过；策略 edge 未通过。**
> **当前不应实盘，也不应跟单。**
## 10. 2026-10-19 后续更新

- 完整池回测见 [event-maker-robust-backtest-2026-10-19.md](event-maker-robust-backtest-2026-10-19.md) 和 [执行台账](event-maker-execution-ledger-2026-10-19.md)。
- 新启动 2h/12h 被动入场 Shadow，使用 1 美分 dip、2 美分退出目标；截至 05:22 选中市场启动后没有新公共成交，两组均为 0 fill。
- 新代码默认 `shadowFillVolumeFactor=1`，并输出 60/300/1800 秒 markout、盘口年龄和 `execution-diagnostics.json`。
- 这些运行尚未完成，不能把 0 fill 解释为策略有效，也不能据此实盘。


## 附录：2026-10-19 07:26 keyset 候选发现复核

- 运行目录：`backend/build/research/event-maker-shadow-keyset-v3-20261019/`。
- Politics 与 Geopolitics 各扫描 1 页 100 条；两标签均在 `volume24hr < 100,000` 处停止，`stoppedReason=volume_floor_reached`。
- 价格预筛候选 6 个，活动过滤通过 1 个。
- 唯一通过者：`Israel x Iran ceasefire continues through October 21?`；30m 6 笔/1009.10 USDC，2h 21 笔/15381.04 USDC；best bid 0.975、best ask 0.976，价差 1 tick。
- `marketSelection selected=0 reasons={spread_below_min=1}`，所以本次未进入 Shadow 报价阶段，也没有 fill、cycle、markout 或 PnL。
- 结论：当前是策略条件匹配空窗，不是市场发现故障；不放宽门槛制造成交。