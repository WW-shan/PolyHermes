## Why

PolyHermes 现在有 `Leader 管理` 和 `跟单配置`，但缺少中间的决策层：用户只能先保存 leader，再直接创建真实跟单配置。对于“小仓位、雨露均沾”的摊大饼策略，这会把“观察候选”和“真钱下单”混在一起，容易因为手动判断和配置过松扩大亏损。

这次变更要新增一个 `Leader 池` 入口，让用户先管理候选、观察、小额试跟、冷却和淘汰，再明确决定是否创建保守的小额跟单配置。

## What Changes

- 新增 `Leader 池` 页面入口，放在左侧 `跟单交易` 分组下，位于 `跟单配置` 与 `Leader 管理` 之间。
- 新增 leader 池能力，支持把已有 leader 加入池子，并维护候选、观察、小额试跟、冷却、淘汰等状态。
- 新增池子预算与风险概览，帮助用户看到试跟人数、建议最坏暴露、待处理风险等组合级信息。
- 新增从池子创建保守小额跟单配置的动作，默认使用固定金额、小日单数、小日亏损、价格区间和单 leader 持仓上限。
- 在 `Leader 管理` 中增加“加入 Leader 池”的辅助入口，避免用户来回复制地址。
- 保留现有 `Leader 管理`、`跟单配置`、统计页和风险安全带，不替换现有工作流。
- 不做自动加仓、不做自动启用大额跟单、不做 AI 预测评分。
- 不在 v1 自动抓取 Polymarket leaderboard。leaderboard 自动发现后续可接入池子，但不属于本次实现。

## Capabilities

### New Capabilities

- `leader-pool`: 管理用于小仓位摊大饼策略的 leader 候选池，包括状态流转、保守试跟配置创建、组合预算提示和与现有 leader/跟单配置的联动。

### Modified Capabilities

无。

## Impact

- 后端新增 leader 池实体、迁移、仓库、DTO、服务和控制器。
- 后端复用现有 `LeaderService`、`CopyTradingService`、`CopyTradingRepository`，避免重复实现 leader 校验和跟单配置创建逻辑。
- 前端新增 `LeaderPool` 页面、路由、菜单项、API service、类型定义和中英文菜单文案。
- 前端 `LeaderList` 增加“加入 Leader 池”操作。
- 数据库新增 `copy_trading_leader_pool` 表，不修改现有 leader 和跟单配置表的语义。
- 交易安全影响：本功能可能创建真实跟单配置，因此必须使用保守默认值、显式确认和非自动加仓策略。
