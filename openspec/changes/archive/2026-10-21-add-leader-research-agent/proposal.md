## Why

当前 `Leader 池` 已经解决了“候选、观察、小额试跟、冷却、淘汰”的人工决策层，但用户的真实目标更靠前：系统自动发现优秀且可复制的 leader，先用纸跟证据验证，再让用户决定是否创建真实小额跟单配置。

如果只搬运排行榜，系统会把“leader 自己赚钱”误当成“用户也能跟着赚钱”。本变更要把 PolyHermes 从手动候选池升级为 paper-first 的 Leader Research Agent：自动发现、自动纸跟、自动解释、自动建议，但真钱启用必须手动确认。

## What Changes

- 新增 Leader Research Agent 能力，提供多源候选发现、研究运行记录、源健康状态和候选评分。
- 新增独立纸跟账本，记录模拟买卖、模拟持仓、过滤原因、估值状态、quote confidence 和纸跟 PnL。
- 新增研究状态机，支持 `DISCOVERED`、`CANDIDATE`、`PAPER`、`TRIAL_READY`、`COOLDOWN`、`RETIRED` 等研究状态，并与现有 Leader 池状态分离。
- 新增可解释 copyability score，按收益信号、可重复性、流动性适配、入场价格适配、滑点风险、持仓周期、市场类型风险、回撤风险、退出流动性风险、数据新鲜度和过滤通过率拆分。
- 新增 Leader Research 操作台，展示运行状态、候选详情、纸跟证据、待处理决策和数据源健康。
- 新增研究事件与通知摘要，让系统主动提示新增候选、纸跟达标、建议小额试跟、冷却、数据失败和数据过期。
- 新增手动审批路径：用户从研究候选详情确认后，系统只能创建 `enabled=false` 的保守真实跟单配置；研究代理不得自动启用真钱跟单。
- 第一阶段候选来源仅包含 watchlist、已有 `Leader 管理` 记录和已持久化 activity 事件；公开 leaderboard 接入保留为后续能力，等端点契约和失败行为确认后再启用。
- 不做自动真钱启用、不做自动加仓、不做自动放大固定金额、不做黑盒 AI 评分、不删除用户已有 leader 或跟单配置。

## Capabilities

### New Capabilities

- `leader-research-agent`: 自动研究 leader 的完整能力，包括候选发现、纸跟账本、copyability 评分、研究状态机、操作台、通知事件和手动创建禁用试跟配置。

### Modified Capabilities

无。

## Impact

- 后端新增 research 相关实体、Flyway 迁移、Repository、DTO、Service、Controller、定时任务和错误码。
- 后端需要复用现有 `copy_trading_leaders`、`copy_trading_leader_pool`、`copy_trading`、`CopyTradingService`、`LeaderPoolService` 和 activity 监听链路。
- 后端需要新增 append-only activity event 持久化能力，如果当前实时 activity 事件没有落库，则纸跟必须先基于该事件表运行。
- 前端新增 Leader Research 操作台页面、路由、菜单入口、API service、类型定义和中文/英文/繁中文文案。
- 前端 Leader 池需要能展示研究推荐 badge 或跳转研究候选详情，但不得把 `TRIAL_READY` 直接表现成真实 `TRIAL`。
- 通知系统先复用内部 `leader_research_event` 和控制台提示；外部通知渠道在事件流稳定后再接入现有通知配置抽象。
- 交易安全影响：本变更会靠近真钱配置创建路径，因此所有真实配置创建必须后端强制 `enabled=false`，并通过测试证明研究代理没有任何自动启用真钱的路径。
