# Event Maker 完整池回测与策略审计

> 日期：2026-10-19 +08:00
> 数据：Polymarket 公共成交历史、Gamma 市场元数据
> 样本：完整候选池 4,187 个市场
> 配置：864 组
> 性质：离线研究，不是实盘收益证明
> 计划：[event-maker-delivery-plan.md](event-maker-delivery-plan.md)
> 工作日志：[event-maker-work-log.md](event-maker-work-log.md)
> 执行台账：[event-maker-execution-ledger-2026-10-19.md](event-maker-execution-ledger-2026-10-19.md)
> 提交：`06d168f feat: harden event maker shadow and full-pool validation`

## 1. 结论

这套策略可以复刻成研究/Shadow 系统，但当前还不能用于真实资金。

完整池结果：

- 训练期唯一选参配置：`p0.97-0.99_off0.5_spread2.0_q1.0_c3_r0`。
- 240 个成交市场、278 个回合、266 次 SELL、12 次 SETTLE、3 次结算亏损。
- 总 PnL `+224.37 USDC`，总 ROI `+0.81%`。
- 训练 ROI `+1.10%`，测试 ROI `+0.56%`。
- 最大已实现回撤 `146.49 USDC`，最差单笔浮亏 `-100 USDC`。
- 50bps 双边 maker fee 下总 ROI 转负为 `-0.18%`。
- 队列因子从 1 提高到 50 后，同族配置 ROI 从约 `+0.92%` 降到 `-0.77%`。

因此，真正决定这套策略能否赚钱的不是“看到热门市场就挂单”，而是：

1. 能否稳定拿到 maker 队列优先权；
2. 能否识别并限制会结算归零的尾部风险；
3. 能否把交易成本压到接近零；
4. 能否在真实盘口中获得可解释的 fill 和 markout。

当前证据不支持真实下单，也不支持继续跟单。

## 2. 数据与方法

- 市场：Polymarket Politics tag `2`、Geopolitics tag `100265`，二元、非 negRisk、已结算市场。
- 时间：2024-01-01 至 2026-10-01。
- 最低 24 小时成交额：100,000 USDC。
- 完整候选池：4,187 个市场。
- 成交历史抓取成功：4,187/4,187，失败 0。
- 参数网格：864 组。
- 单市场订单金额：100 USDC。
- 交易窗口：结算前 24 小时。
- 入场：favorite 参考成交价减去 `entryOffsetCents`。
- 出场：入场价加 `exitSpreadCents` 的 maker 卖单；未卖出按 0/1 结算。
- 队列模型：累计反向成交量达到 `queueFactor × 订单股数` 后才成交。
- 训练/测试切分：2026-01-01。
- 选参规则：**只用训练期** 成交数不少于 20、训练 ROI 为正的配置中选训练 ROI 最高者；测试期只用于最终报告，不参与选参。
- 基准费率：maker fee 0；另做 10/25/50/100bps 双边费用压力。
- 公共成交数据没有完整 L2 和历史队列，因此回测仍是近似，不是成交保证。

## 3. 完整池正式结果

### 3.1 训练期选出的配置

配置：

```text
p0.97-0.99_off0.5_spread2.0_q1.0_c3_r0
```

| 指标 | 结果 |
|---|---:|
| 可交易市场 | 450 |
| 成交市场 | 240 |
| fill rate | 53.3% |
| 总回合 | 278 |
| SELL / SETTLE | 266 / 12 |
| 结算亏损数 | 3 |
| 胜率 | 98.9% |
| 总 PnL | +224.37 USDC |
| 总 ROI | +0.81% |
| 训练成交市场 / ROI | 111 / +1.10% |
| 测试成交市场 / ROI | 129 / +0.56% |
| 最大已实现回撤 | 146.49 USDC |
| 最差单笔浮亏 | -100.00 USDC |
| 平均最大浮亏 | -6.99 USDC |
| 最大浮亏 P10 | -11.35 USDC |
| time-to-fill 中位数 | 3.27 小时 |
| time-to-fill P90 | 14.40 小时 |
| 持有中位数 | 10.74 小时 |
| 持有 P90 | 19.84 小时 |
| 60 秒 markout 总和 | +109.06 USDC |
| 60 秒 markout 平均 | +0.392 USDC |
| 60 秒 markout 为负的回合 | 21 / 278 |

### 3.2 样本内最高总 ROI 配置

配置：

```text
p0.97-0.99_off1.0_spread2.0_q1.0_c1_r0
```

| 指标 | 结果 |
|---|---:|
| 成交市场 / 回合 | 179 / 179 |
| SELL / SETTLE | 177 / 2 |
| 结算亏损数 | 2 |
| 总 PnL / ROI | +164.99 USDC / +0.92% |
| 训练 ROI | +0.74% |
| 测试 ROI | +1.06% |
| 最大已实现回撤 | 100.00 USDC |
| 盈亏平衡亏损率 | 约 2.02% |
| 实际亏损率 | 1.12% |

这个配置总 ROI 略高，但只有 179 个回合，且同样依赖极低尾部亏损率；不能因为它是样本内最高值就直接采用。

### 3.3 与直接持有对比

训练期选出的配置：

- 相同成交市场上，策略 PnL `+224.37 USDC`，ROI 约 `+0.935%`。
- 相同成交市场直接持有到结算的 PnL `+140.60 USDC`，ROI 约 `+0.586%`。
- 策略在这些市场上确实通过卖出规避了一部分结算风险。

但在全部 450 个可交易市场上：

- 策略总 ROI `+0.81%`。
- 全部市场直接持有的汇总 ROI 约 `+1.11%`。
- 两者资金占用不同，不能直接等同；它说明策略的收益并不明显高于简单持有高胜率 favorite。

## 4. 尾部风险

三个结算亏损回合：

| 市场 | 入场价 | 结算价 | PnL |
|---|---:|---:|---:|
| China-Vietnam trade deal before June? | 0.967 | 0.000 | -100.00 |
| Will the Iran ceasefire continue through May 25? | 0.969 | 0.000 | -100.00 |
| US ceasefire against Iran continues through August 31? | 0.983 | 0.000 | -100.00 |

关键统计：

- 实际亏损率：`3 / 278 = 1.079%`。
- 平均盈利约 `1.907 USDC`，一次 -100 USDC 亏损约等于抹掉 52 次盈利。
- 盈亏平衡亏损率约 `1.87%`。
- 3/278 亏损率的 Wilson 95% 区间约为 `[0.37%, 3.12%]`；上界高于盈亏平衡线。
- 因此，样本内正 ROI 不能证明真实期望一定为正。

这正是高胜率、负偏态策略的典型结构：大多数回合赚 2 美分左右，少数回合亏掉接近全部本金。

## 5. 队列敏感性

固定 `0.97~0.99 / off1.0 / spread2.0 / c1 / r0`，只改变队列因子：

| 队列因子 | 成交市场 | 回合 | SETTLE | 总 ROI | 训练 ROI | 测试 ROI |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 179 | 179 | 2 | +0.92% | +0.74% | +1.06% |
| 2 | 165 | 165 | 3 | +0.83% | +0.60% | +0.99% |
| 5 | 137 | 137 | 4 | +0.57% | +0.27% | +0.79% |
| 10 | 123 | 123 | 5 | +0.41% | +0.06% | +0.65% |
| 20 | 99 | 99 | 6 | +0.006% | -0.25% | +0.21% |
| 50 | 71 | 71 | 10 | -0.77% | -1.05% | -0.54% |

结论：

- 队列越深，能成交的订单越偏向真正大幅下跌的市场。
- 这带来 adverse selection，结算亏损数随队列加深而增加。
- 真实 L2 队列一旦比回测假设更深，策略 edge 会消失。
- 不能把“队列因子 1~10 仍为正”解释为真实可成交。

## 6. 费用敏感性

对训练期选出的配置做双边 maker fee 压力测试：

| 每边 maker fee | 总 ROI | 训练 ROI | 测试 ROI | 总 PnL |
|---:|---:|---:|---:|---:|
| 0 bps | +0.81% | +1.10% | +0.56% | +224.37 |
| 10 bps | +0.61% | +0.90% | +0.36% | +169.47 |
| 25 bps | +0.31% | +0.61% | +0.06% | +87.11 |
| 50 bps | -0.18% | +0.11% | -0.43% | -50.16 |
| 100 bps | -1.17% | -0.87% | -1.42% | -324.70 |

结论：2 美分价差不足以吸收 50bps 双边费用。只有在接近零费用、且没有额外滑点和失败的情况下才略微为正。

补充说明（2026-10-19 07:58）：

- 项目当前按 Polymarket 官方协议口径处理：maker 不收费，taker fee = `shares × feeRate × p × (1-p)`，只有 FAK/市价成交才计费；实现与测试见 `PolymarketTradingFee.kt`、`PolymarketTradingFeeTest.kt`。
- 策略所有订单为 post-only maker，因此 `0 bps` 是当前基线；10/25/50/100bps 是费用恶化压力测试，不是当前 maker 收费事实。
- 这不会消除策略风险：队列优先权、adverse selection、结算归零尾部、真实 markout 和流动性奖励不稳定仍然没有得到实盘证明。
- 若未来协议对 maker 收费、post-only 被穿透或订单实际以 taker 成交，必须重新计算净收益。

## 7. 年份、标签与集中度

训练期选出配置：

| 年份 | 回合 | PnL | SETTLE | 亏损回合 |
|---|---:|---:|---:|---:|
| 2024 | 24 | +45.60 | 3 | 0 |
| 2025 | 105 | +96.35 | 4 | 1 |
| 2026 | 149 | +82.43 | 5 | 2 |

| 标签 | 回合 | PnL | 亏损回合 |
|---|---:|---:|---:|
| Geopolitics | 175 | +128.98 | 2 |
| Politics | 103 | +95.39 | 1 |

- 覆盖 31 个自然月，最大单月 33 个回合。
- 前 10% 回合贡献约 25.84% 的 PnL，收益没有集中在极少数回合。
- 但结果仍然由两个高波动标签驱动，且 2025、2026 都出现了结算归零亏损。

## 8. 为什么 1,000 市场结果是假象

1,000 市场子样本中，早期最佳配置曾显示：

- 41 个回合全部 SELL，零结算亏损。
- ROI +2.06%。
- 训练/测试各 20+ 个成交市场。

扩大到 4,187 市场后，同一配置降为：

- 179 个回合，2 个结算亏损。
- ROI +0.92%。
- 队列因子 50 时 ROI 为 -0.77%。

原因是 1,000 市场子样本恰好没有抽到足够多的结算归零事件。零亏损样本不能证明尾部风险不存在；样本扩大后，负偏态立刻显现。

## 9. Shadow 验证状态

曾启动两组真实盘口 Shadow，但旧长测因运行中重编译产生类版本冲突，不能作为有效证据：

- 2h：`backend/build/research/event-maker-shadow-passive-2c-2h-20261019/`
- 12h：`backend/build/research/event-maker-shadow-passive-2c-12h-20261019/`

初始选中：

- Israel x Iran ceasefire continues through October 21：BUY 0.960；
- Xi Jinping out before 2027：BUY 0.963；
- Will China invade Taiwan by end of 2026：BUY 0.968。

截至 2026-10-19 06:52：

- 旧 2h 进程结束时 `NoSuchMethodError`，无有效 `summary.json`。
- 旧 12h 进程仍为 0 fill，已主动停止，避免结束时同类错误。
- 用最终代码重启 2h 时，0.97～0.99 价格带无合格市场，未启动。
- 当前不能对真实成交质量下结论，也不降低价格条件制造 fill。

新代码已加入：

- `shadowFillVolumeFactor`：默认 1，要求队列清空后累计反向成交达到 1 倍订单股数；0 仅为旧乐观对照。
- 60/300/1800 秒 markout，并记录盘口年龄。
- `execution-diagnostics.json` 记录每笔订单的队列、穿越成交和队列消耗。

### 9.1 新版 Shadow 冒烟

10 分钟保守冒烟：

- 1 个市场、201 笔唯一公共成交、35,599 次轮询重复被去重、348 次盘口更新。
- 1 次报价、0 fill、0 错误。
- 发现退出时模拟订单仍为 LIVE；已修复为退出前 `cancelAll`。

1 分钟复测：

- 200 笔唯一成交、1 次报价、1 次退出撤单、0 fill、0 错误。
- `execution-diagnostics.json` 中 `activeOrders=0`，订单状态 `CANCELLED`。
- 最终快照回到 `FLAT`，撤单原因 `stop`。

## 10. 已知限制

1. 没有完整 L2 队列重建；公共成交无法还原我们下单时的真实排队位置。
2. Shadow 的成交仍是近似，不能证明真实订单一定成交。
3. 没有真实撤单竞争、网络延迟、订单拒绝和签名失败对收益的影响。
4. 回测最大回撤按已实现回合计算；最大浮亏另行记录，但仍不是完整组合权益曲线。
5. 没有自动新闻/事件流，无法模拟突发事件下的信息劣势。
6. 没有真实账户和资金，小资金 Live smoke 尚未执行。
7. 864 组参数搜索仍有多重比较偏差，训练期选参只能降低、不能消除该偏差。

## 11. 最终判断

- 可以保留 `Event Maker` 执行框架、Shadow、队列诊断和风险控制代码。
- 不能把当前 `0.97~0.99 + 1 美分入场偏移 + 2 美分退出` 直接投入真实资金。
- 不应跟单该地址；跟单只能复制公开成交，复制不到他的队列位置、撤单速度和库存管理。
- 下一步唯一有价值的验证是：用保守成交因子和真实盘口跑出足够多的 fill，并证明 60/300/1800 秒 markout、回合 PnL 和尾部亏损可解释。
- 在没有这些证据前，只能 SHADOW，不能 LIVE。

## 12. 复现

完整池修正版命令：

```powershell
cd D:\Project\PolyHermes\backend
.\gradlew.bat eventMakerBacktest `
  "-PresearchProxy=http://127.0.0.1:10808" `
  "-PresearchMaxMarkets=5000" `
  "-PresearchMinVolume=100000" `
  "-PresearchFrom=2024-01-01" `
  "-PresearchTo=2026-10-01" `
  "-PresearchConcurrency=6" `
  "-PresearchOutput=build/research/event-maker-queue-grid-full-v2-20261019" `
  --console=plain
```

输出：

- `backend/build/research/event-maker-queue-grid-full-v2-20261019-summary.csv`
- `backend/build/research/event-maker-queue-grid-full-v2-20261019-robust-markets.csv`
- `backend/build/research/event-maker-queue-grid-full-v2-20261019-robust-cycles.csv`
- `backend/build/research/event-maker-queue-grid-full-v2-20261019-fee-sensitivity.csv`
- `backend/build/research/event-maker-queue-grid-full-v2-20261019-top-markets.csv`
- `backend/build/research/event-maker-queue-grid-full-v2-20261019-top-cycles.csv`

全量测试：

```powershell
cd D:\Project\PolyHermes\backend
.\gradlew.bat test --console=plain
```

结果：95 suites、445 tests、0 failures、0 errors、2 skipped。
