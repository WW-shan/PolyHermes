# Event Maker Live 执行链路验证报告

> 日期：2026-10-19 +08:00  
> 范围：Live 下单、撤单、订单查询、部分成交、未知状态、持久化恢复和停止清理  
> 性质：mock/单元/引擎级验证；**不是实际资金实盘验证**  
> 计划：[event-maker-delivery-plan.md](event-maker-delivery-plan.md)  
> 运行手册：[event-maker-runbook.md](event-maker-runbook.md)

## 1. 结论

当前 Live 执行链路已通过以下验证：

- 请求强制 `postOnly=true`、`orderType=GTC`；
- 下单前校验 tick、最小订单量、USDC 余额/授权、CTF 库存/授权；
- 订单状态查询作为成交权威来源；
- 部分成交后撤单、库存更新和回合记账；
- 订单 404、非法快照、未知状态、连续 3 次查询失败时 fail-closed；
- 下单结果不确定时 fail-closed；
- 停止和异常退出时尝试撤销所有活跃订单；
- 撤单失败时保留活跃订单状态，不清零库存；
- 活跃订单、库存、逐笔 fill、逐回合 cycle 持久化并可恢复；
- 启动时发现不在当前市场集合中的孤儿订单会先撤销，失败则拒绝启动。

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
| tick / 最小订单量 / 资金 / 授权 | `EventMakerExecutionGuardsTest` | 通过 |
| 完整买入卖出回合 | `EventMakerLiveEngineTest` | 通过 |
| 部分成交后撤单 | `EventMakerLiveEngineTest` | 通过 |
| 未知状态停机 | `EventMakerLiveEngineTest` | 通过 |
| 订单丢失停机 | `EventMakerLiveEngineTest` | 通过 |
| 连续查询失败停机 | `EventMakerLiveEngineTest` | 通过 |
| 恢复后撤单 | `EventMakerLiveEngineTest` | 通过 |
| 持久化保存/加载/恢复 | `EventMakerStateStoreTest`、`EventMakerLiveStateTest` | 通过 |
| Shadow 队列与成交模拟 | `EventMakerShadowExecutionPortTest` | 通过 |
| LIVE 开关与账户必填 | `EventMakerStrategyServiceSafetyTest` | 通过 |

## 3. 订单状态机

Live 状态机只接受以下订单状态：

```text
LIVE, OPEN, MATCHED, FILLED, CANCELLED, CANCELED, EXPIRED, REJECTED
```

其他状态、空状态、非法价格、非法数量、成交数量超过原始数量都会触发：

```text
order_state_unknown:...
```

并停止新报价。

部分成交处理：

1. 从 CLOB `getOrder` 读取 `size_matched`；
2. 只把新增匹配量计入库存；
3. 若订单仍活跃且有部分成交，立即撤销剩余数量；
4. 撤单成功后把状态转为 `LONG` 或 `FLAT`；
5. 撤单失败则 fail-closed，保留订单和库存。

## 4. 持久化内容

状态文件默认：

```text
backend/data/event-maker-state.json
```

每个市场保存：

- `activeOrderId`、方向、价格、原始数量、已成交数量、状态、创建时间；
- `inventoryShares`、`entryPrice`、`entryAt`；
- `realizedPnl`、`completedCycles`；
- `currentEntryOrderId`、`currentEntryShares`；
- `fills`：逐笔成交；
- `cycles`：逐回合 entry/exit 价格、数量、PnL、持有时间。

写入使用临时文件 + 原子替换。读取时校验版本。文件不包含私钥和 API 凭证。

## 5. 停止与异常退出

正常停止、日亏损停机、未知状态停机和运行异常退出都会进入清理路径：

```text
停止报价 -> 取消所有已知活跃订单 -> 仅在成功时清除订单状态 -> 持久化 -> 关闭数据连接
```

如果撤单失败：

- 不调用 `onOrderCanceled()`；
- 保留 `activeOrder*` 字段；
- 写入 `stop_cancel_failed:...`；
- 下次启动会把它当作恢复订单处理。

## 6. 复现命令

```powershell
cd D:\Project\PolyHermes\backend

.\gradlew.bat test --tests "com.wrbug.polymarketbot.service.eventmaker.*" --console=plain
.\gradlew.bat test --console=plain
```

当前结果：

```text
event-maker：45 tests，0 failures
全量：428 tests，0 failures，2 skipped
```

> 注：以上数字来自 2026-10-19 03:10 的最终全量测试。

## 7. 剩余风险

1. 真实 CLOB 返回字段可能和 mock 不完全一致，需要小资金 smoke 验证。
2. 实际账户的 USDC/pUSD、授权和代理钱包类型可能与测试账户不同。
3. 下单网络超时后订单可能已创建，当前策略会停机但不会自动补查成交。
4. 公共成交模拟无法完全还原真实队列位置，Shadow fill 只能作为近似。
5. 尚未验证真实 maker fee、rebate、流动性奖励和结算流程。
6. 实际小资金 smoke 需要用户提供账户并明确授权。