# Event Maker Live 执行链路验证报告

> 日期：2026-10-19 +08:00<br>
> 范围：Live 下单、撤单、订单查询、部分成交、未知状态、持久化恢复、账户级对账和停止清理<br>
> 性质：mock/单元/引擎级验证；**不是实际资金实盘验证**<br>
> 计划：[event-maker-delivery-plan.md](event-maker-delivery-plan.md)<br>
> 运行手册：[event-maker-runbook.md](event-maker-runbook.md)

## 1. 结论

当前 Live 执行链路已通过以下验证：

- 请求强制 `postOnly=true`、`orderType=GTC`；
- 下单前校验 tick、最小订单量、USDC 余额/授权、CTF 库存/授权；
- 订单状态查询作为成交权威来源；
- 部分成交后撤单、库存更新和完整回合记账；
- 订单 404、非法快照、未知状态、连续 3 次查询失败时 fail-closed；
- 下单结果不确定时 fail-closed，错配订单会先尝试撤销；
- 停止时先撤单，再读取最终订单快照；部分成交先记账，无法确认时保留 `activeOrder*`；
- 状态文件损坏、版本不支持、账户不匹配、重复 condition、缺失 favorite token 或非法快照会拒绝启动；
- 启动前比对账户全部活跃订单，发现未登记订单即拒绝启动；
- 启动前比对账户正数持仓 conditionId 和数量，未登记持仓、数量不一致或状态库存不在链上即拒绝启动；
- 孤儿订单先撤销并确认无成交；孤儿库存、撤单失败或状态未知全部阻止重启；
- 逐笔 fill 重放核对库存、已实现 PnL、完成回合数和 cycle PnL。

**仍未完成：使用真实账户和真实资金的小资金 Live smoke。**  
没有账户、资金和明确授权时，不能宣称 Live 已完整实盘验证。

## 2. 测试矩阵

| 场景 | 测试 | 结果 |
|---|---|---|
| post-only/GTC | `ClobEventMakerExecutionAdapterTest` | 通过 |
| 余额不足拒绝下单 | `ClobEventMakerExecutionAdapterTest` | 通过 |
| 订单 404 | `ClobEventMakerExecutionAdapterTest` | 通过 |
| 非法订单快照 | `ClobEventMakerExecutionAdapterTest` | 通过 |
| 撤单 404 | `ClobEventMakerExecutionAdapterTest` | 通过 |
| 活跃订单分页、去重、重复 cursor fail-closed | `ClobEventMakerExecutionAdapterTest` | 通过 |
| 账户持仓 conditionId/数量查询与多资产拒绝 | `ClobEventMakerExecutionAdapterTest`、`EventMakerStrategyServiceSafetyTest` | 通过 |
| tick / 最小订单量 / 资金 / 授权 | `EventMakerExecutionGuardsTest` | 通过 |
| 完整买入卖出回合 | `EventMakerLiveEngineTest` | 通过 |
| 部分成交后撤单 | `EventMakerLiveEngineTest` | 通过 |
| 未知状态停机 | `EventMakerLiveEngineTest` | 通过 |
| 订单丢失停机 | `EventMakerLiveEngineTest` | 通过 |
| 连续查询失败停机 | `EventMakerLiveEngineTest` | 通过 |
| 停止时对账部分成交后再清除订单 | `EventMakerLiveEngineTest` | 通过 |
| 停止查询失败保留活跃订单 | `EventMakerLiveEngineTest` | 通过 |
| 恢复后撤单 | `EventMakerLiveEngineTest` | 通过 |
| 持久化保存/加载/恢复与损坏拒绝 | `EventMakerStateStoreTest`、`EventMakerLiveStateTest` | 通过 |
| 部分卖出跨多订单 cycle PnL 汇总 | `EventMakerLiveStateTest` | 通过 |
| 状态文件 favorite token / fill ledger 校验 | `EventMakerLiveStateTest` | 通过 |
| 孤儿订单、孤儿库存、未确认撤单 | `EventMakerStartupRecoveryTest` | 通过 |
| Shadow 队列与成交模拟 | `EventMakerShadowExecutionPortTest` | 通过 |
| LIVE 开关、账户必填、错账户状态、未登记订单/持仓 | `EventMakerStrategyServiceSafetyTest` | 通过 |

## 3. 订单状态机

Live 状态机只接受以下订单状态：

```text
LIVE, OPEN, MATCHED, FILLED, CANCELLED, CANCELED, EXPIRED, REJECTED
```

其他状态、空状态、非法价格、非法数量、成交数量超过原始数量、价格与原订单不一致或原始数量不一致都会触发：

```text
order_state_unknown:...
```

并停止新报价。

部分成交处理：

1. 从 CLOB `getOrder` 读取 `size_matched`；
2. 只把新增匹配量计入库存；
3. 同一入场后的所有部分卖出累计股数和 PnL；
4. 若订单仍活跃且有部分成交，立即撤销剩余数量；
5. 撤单后读取最终快照，部分成交先记账；
6. 撤单失败或快照无法确认时 fail-closed，保留订单和库存。

## 4. 持久化内容

状态文件默认：

```text
backend/data/event-maker-state.json
```

每个市场保存：

- `conditionId`、`favoriteTokenId`；
- 状态级 `cumulativeRealizedPnl`，跨进程保留已完成市场的已实现 PnL；
- 活跃订单 ID、方向、价格、原始数量、已成交数量、状态、创建时间；
- `inventoryShares`、`entryPrice`、`entryAt`；
- `realizedPnl`、`completedCycles`；
- `currentEntryOrderId`、`currentEntryShares`；
- `fills`：逐笔成交；
- `cycles`：逐回合 entry/exit 价格、数量、PnL、持有时间。

写入使用临时文件 + 原子替换。读取时校验版本、字段合法性、账户归属和 favorite token；恢复时重放 `fills`，要求库存、realized PnL、完成回合数和 cycle 记录完全一致。文件不包含私钥和 API 凭证。

## 5. 账户级启动对账

LIVE 启动在报价前执行：

1. 读取并校验 `event-maker-state.json`；
2. 确认状态所有权与当前 `accountId` 一致；
3. 分页读取账户全部活跃订单；
4. 任何活跃订单 ID 不在状态文件 `activeOrderId` 集合中，返回 `unmanaged_open_orders`；
5. 读取账户当前正数持仓 conditionId；
6. 账户持仓与状态库存集合必须完全一致，否则返回 `open_position_state_mismatch`；
7. 同一 condition 出现多个正数资产时返回 `multiple_position_assets`；
8. 不在本轮发现列表的孤儿订单先撤销并读取终止快照；孤儿库存直接阻断启动。

该校验要求使用专用账户。复制交易或其他策略留下的订单/持仓会导致 Event Maker 拒绝启动，这是刻意的 fail-closed 行为。

## 6. 停止与异常退出

正常停止、日亏损停机、未知状态停机和运行异常退出都会进入清理路径：

```text
停止报价 -> 撤销已知活跃订单 -> 读取最终订单快照 -> 对账部分成交 -> 确认终态后清除订单 -> 持久化 -> 关闭数据连接
```

如果撤单或最终快照查询失败：

- 不清除 `activeOrder*`；
- 已观察到的部分成交先写入 fills/inventory/cycle；
- 写入 `stop_state_unconfirmed`、`stop_order_still_active` 或 `stop_reconcile_failed`；
- 下次启动按恢复订单处理；账户级活跃订单/持仓检查不通过时拒绝 LIVE。

## 7. 复现命令

```powershell
cd D:\Project\PolyHermes\backend

.\gradlew.bat test --tests "com.wrbug.polymarketbot.service.eventmaker.*" --console=plain
.\gradlew.bat test --console=plain
```

当前结果（2026-10-19 09:00）：

```text
eventmaker/research eventmaker：94 tests，0 failures
全量：97 suites / 475 tests，0 failures，0 errors，2 skipped
```

## 8. 剩余风险

1. 真实 CLOB / Data API 返回字段和分页行为仍需小资金 smoke 验证。
2. 实际账户的 USDC/pUSD、授权和代理钱包类型可能与测试账户不同。
3. 下单网络超时后订单 ID 可能未知；当前会停机，账户活跃订单检查只能在下一次启动时阻止未登记订单继续运行。
4. 当前账户级校验要求使用专用账户；复制交易或其他策略的活跃订单/持仓会导致 Event Maker 拒绝启动，这是刻意 fail-closed。
5. 公共成交模拟无法完全还原真实队列位置，Shadow fill 只能作为近似。
6. 尚未验证真实 maker fee、rebate、流动性奖励和结算/赎回流程。
7. `maxDailyLoss` 使用跨进程累计已实现 PnL 加当前浮亏，不是自然日重置口径；这是更保守的限制，但需要真实 smoke 验证。
8. 实际小资金 smoke 需要用户提供账户并明确授权。