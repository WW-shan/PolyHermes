# BOND Carry Shadow 运行与审计手册（2026-10-20）

> 定位：**理想化 BOND carry 假设的 paper/shadow 验证，不是已验证的目标账户复刻。**  
> 目标账户真实 0.95–0.97 已平仓 ROI 约 +0.225%，我们的队列回测 +4.10% 明显高估；在真实成交和库存账本证明前，禁止实盘。

## 1. 配置

| 参数 | 值 |
|---|---|
| 价格带 | 0.95–0.99 |
| 入场 | post-only maker BUY，entryOffset=0，improveTicks=1 |
| 退出 | exitSpread=100c，持有到结算；不主动 taker 卖 |
| maxCycles | 1 |
| order notional | 100 USDC |
| minSpreadTicks | 1 |
| maxQueueAheadShares | 500 |
| fillVolumeFactor | 1 |
| maxMarkets | 20 |
| minVolume | 10,000 |
| maxRemainingMinutes | 43,200（30 天） |
| staleBookSeconds | 30 |
| maxOrderAgeSeconds | 3,600 |

## 2. 启动

短测 2 小时：

```powershell
.\scripts\start-bond-shadow.ps1 `
  -Minutes 120 -MinPrice 0.95 -MaxPrice 0.99 `
  -MaxMarkets 20 -MinVolume 10000 `
  -Output build/research/event-maker-bond-shadow-20261020-v2
```

7 天续跑（当前已武装，等待 2h PID 24440/7304 退出）：

```powershell
.\scripts\continue-bond-validation.ps1
```

脚本会先跑全量 Gradle 测试，通过后才启动 7 天 Shadow。

## 3. 产物

输出目录下：

- `state.json`：每个市场的 phase、库存、挂单、cycles、settlements；
- `trades.csv`：真实盘口逐笔成交；
- `snapshots.csv`：盘口快照；
- `markouts.csv`：成交后 markout；
- `events.jsonl`：下单/撤单/成交/结算事件；
- `summary.json`：运行结束摘要；
- `market-discovery.json` / `market-selection.json`：市场发现与选中原因。

## 4. 审计清单

每次运行结束必须回答：

1. 选中了多少市场，实际挂单多少，fill 多少，fill rate 多少；
2. 平均入场价、平均成交股数、平均持有时间；
3. 每笔 MAE、最差 markout、是否有结算亏损；
4. 实际 ROI 是否落在目标账户同 band 的 0.2%–0.8% 附近；若远高于 1%，先怀疑成交/队列假设；
5. 是否有重复成交、未知状态、stale book、订单状态错乱；
6. 是否记录了 maker rebate / liquidity reward；未记录则不能把它们算入收益；
7. 输出文件、命令、测试结果和工作日志是否同步更新。

## 5. 停止

```powershell
Stop-Process -Id 24440 -Force
```

停止后检查 `state.json` 是否无活跃订单，并在工作日志记录停止原因和最后状态。

## 6. 已知限制

- 7 天历史数据集只有 trade prints，没有完整 order book，因此回测的队列和成交是近似；
- Shadow 只验证 paper 成交，不验证真实签名、真实资金和链上赎回；
- 未计 maker rebate / liquidity reward；
- 目标账户的真实 edge 更可能来自事件选择、库存管理和退出纪律，不是单纯价格带。

## 7. 结论门槛

只有同时满足以下条件，才讨论小额 Live：

- 至少 2–4 周 Shadow；
- 覆盖 50–100 个结算事件；
- 真实 fill rate、MAE、结算亏损和 ROI 可解释；
- 亏损率置信区间下界高于盈亏平衡率；
- 无重复成交、未知状态、订单状态错乱；
- 用户提供账户、资金上限和明确实盘授权。