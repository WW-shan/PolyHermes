# BOND Live Smoke 检查清单（2026-10-21）

> 目的：在用户明确提供账户、资金和授权后，用最小真实仓位验证下单、成交、撤单、对账和停止链路。  
> 当前状态：**未执行**。Shadow gate 仍为 `WAIT`。本文档不是实盘授权。

## 1. 进入 Live 前必须满足

- [ ] `node scripts/evaluate-bond-shadow-gate.cjs` 输出 **PASS**，不是 `WAIT`。
- [ ] 主候选 `atomic-4level-2c-maker-ladder`（`ladder4`）有足够的真实 Shadow 成交/结算样本。
- [ ] 结算亏损率 Wilson 95% 上界低于盈亏平衡率。
- [ ] 60s / 300s markout 不低于 gate 阈值。
- [ ] 最近 24h 无重复成交、未知订单状态、未解释的 active order。
- [ ] 用户明确提供账户 ID、API 凭证、资金上限、授权时间窗口和市场范围。
- [ ] 代理钱包已部署，私钥可解密，USDC/allowance/gas 可用。
- [ ] 账户没有未管理的活动订单或持仓。

## 2. 首次 Live smoke 建议参数

| 参数 | 建议 |
|---|---|
| 单笔 notional | **$100** |
| 最大总库存 | **$100–$200** |
| 最大市场数 | **1–2** |
| MaxCyclesPerMarket | **1** |
| 日亏损上限 | **$100**（或用户指定更小值） |
| 订单类型 | post-only GTC |
| 价格带 | 0.97–0.99 Geopolitics |
| 时间窗口 | close 前 3–7 天 |
| 24h momentum filter | 主 maker 臂关闭；独立 mom1 臂才开启 |
| Taker fallback | 禁止 |

> 不建议首次就使用 $1,000 或 $5,000。历史 $1k/$5k 的零亏损样本不足以把尾部 CI 压到盈亏平衡以下。

## 3. 启动步骤

1. 确认 gate PASS 并保存 JSON 证据。
2. 启动 backend，确认账户/密钥/代理钱包校验通过。
3. 用只读接口检查账户余额、开放订单、持仓。
4. 只选择一个 3–7 天窗口、0.97–0.99、非 NegRisk、tick=0.001 的 Geopolitics 市场。
5. 先让策略保持 `liveTradingEnabled=false`，确认候选选择与参数。
6. 用户明确授权后，再打开 live 开关，单市场、单笔。
7. 记录订单 ID、post-only 状态、价格、数量、创建时间。
8. 观察成交/部分成交/撤单；任何状态不确定立即停止。
9. 停止后确认：
   - active orders = 0 或全部在 state 中；
   - 持仓与 state inventory 一致；
   - 最后一次对账无未知状态；
   - realized/unrealized PnL 可解释；
   - 无重复成交键。

## 4. 立即中止条件

- gate 不是 PASS；
- state/account/token/condition 不匹配；
- 账户有状态文件之外的活动订单或正数持仓；
- 订单不是 post-only 或出现 taker fallback；
- 盘口过期、价格跳变、队列/成交状态无法确认；
- 单日亏损达到上限；
- API/签名/撤单连续失败；
- 用户要求停止。

## 5. 证据文件

- `state.json` / `recovery.json`
- `summary.json` / `events.jsonl`
- `orders` / `fills` / `snapshots` / `markouts`
- 账户开放订单和持仓快照
- gate JSON
- 用户授权与资金上限记录

## 6. 完成标准

只有真实小资金订单完成“下单 → 成交/撤单 → 对账 → 停止”闭环，并且状态、持仓、PnL 和证据一致，才可以把 Live smoke 标记为完成。  
在完成之前，不把回测或 Shadow 结果当作实盘通过。