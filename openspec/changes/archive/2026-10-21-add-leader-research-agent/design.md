## Context

PolyHermes 当前已有三层跟单工作流：

- `Leader 管理` 保存可被跟单的钱包地址，对应 `copy_trading_leaders`。
- `Leader 池` 管理人工候选、观察、小额试跟、冷却、淘汰，对应 `copy_trading_leader_pool`。
- `跟单配置` 管理真实账户、leader 和风控参数，对应 `copy_trading`。

这个结构已经把“地址库”和“真钱跟单”拆开，但仍需要用户自己去找 leader、判断是否值得观察、跟踪表现、决定是否小额试跟。用户的目标不是手动维护池子，而是让系统自动发现优秀且可复制的 leader，先纸跟验证，再由用户授权真钱动作。

本设计把新增能力定位为 Leader Research Agent。它是研究代理，不是自动交易代理。它可以自动发现、自动纸跟、自动建议、自动冷却；它不能自动启用真钱跟单。

## Goals / Non-Goals

**Goals:**

- 提供多源候选发现，第一阶段支持 watchlist、已有 Leader 管理记录和 activity-derived candidates。
- 为研究运行、候选、分数、事件、纸跟 session、纸跟交易、纸跟持仓建立独立数据模型。
- 持久化原始或规范化 activity event，使纸跟可以基于可重放事件运行，而不是复用真实订单表。
- 用透明规则计算 copyability score，并保存分项分数和 score version。
- 自动推进研究状态，但研究状态必须与 Leader 池状态分离。
- 在不锁定的情况下，自动化只能保守地增强 Leader 池记录，不能自动改成真实 `TRIAL` 或 `ACTIVE`。
- 提供操作台展示运行状态、候选详情、纸跟表现、待处理决策和源健康。
- 提供手动审批动作，创建保守的 `enabled=false` 真实跟单配置。
- 为源失败、quote 不可用、valuation unknown、重复事件、锁定候选、非法真钱启用等关键路径提供可见错误和测试。

**Non-Goals:**

- 不自动启用真钱跟单。
- 不自动加仓、不自动增加 fixed amount、不自动放宽风控参数。
- 不做黑盒 AI/ML 评分。
- 不在第一阶段依赖公开 leaderboard 端点。
- 不复用 `copy_order_tracking` 作为纸跟账本。
- 不把 unknown valuation 当成 confirmed zero。
- 不替换现有 Leader 池、Leader 管理、跟单配置和真实 PnL 统计。

## Decisions

### 1. 研究状态独立于 Leader 池状态

研究状态回答“系统证明了什么”，Leader 池状态回答“用户当前怎么处理这个 leader”。两者不能合并。

研究状态：

```text
DISCOVERED -> CANDIDATE -> PAPER -> TRIAL_READY
                         \-> COOLDOWN -> RETIRED
```

Leader 池状态：

```text
CANDIDATE -> WATCH -> TRIAL -> ACTIVE
          \-> COOLDOWN -> RETIRED
```

映射规则：

```text
research DISCOVERED
  -> 可以只存在于 leader_research_candidate，不强制创建 Leader 池项

research CANDIDATE
  -> 展示给用户时可创建或更新 Leader 池 CANDIDATE

research PAPER
  -> Leader 池保持 WATCH，或后续 UI 暴露 PAPER

research TRIAL_READY
  -> Leader 池保持 WATCH，并展示“建议小额试跟” badge
  -> 不能自动变成 Leader 池 TRIAL

research COOLDOWN
  -> 未锁定时可以同步 Leader 池 COOLDOWN

research RETIRED
  -> 只有 agent 拥有且未锁定的候选，才可同步 Leader 池 RETIRED
```

`TRIAL_READY` 只是建议。只有用户创建了真实禁用试跟配置后，Leader 池才可以进入 `TRIAL`。

### 2. 纸跟必须独立建账

`enabled=false` 的真实跟单配置不会产生模拟成交、模拟持仓、过滤原因、quote confidence 和纸跟 PnL。把它伪装成纸跟会让后续晋升建议没有证据链。

新增建议表：

```text
leader_paper_session
leader_paper_trade
leader_paper_position
```

每笔纸跟交易至少记录：

```text
leader_trade_id
leader_price
leader_size
simulated_price
simulated_size
simulated_amount
fill_assumption: LEADER_PRICE | BEST_ASK_AT_EVENT | MID_PRICE | UNKNOWN
quote_confidence: HIGH | MEDIUM | LOW | UNKNOWN
quote_source
quote_timestamp
filter_result: PASSED | FILTERED
filter_reason
valuation_status: AVAILABLE | NO_MATCH | UNAVAILABLE | CONFIRMED_ZERO
```

`UNAVAILABLE` 和 `UNKNOWN` 不得计为真实归零。这个约束必须沿用到纸跟 PnL 和前端展示。

### 3. 第一阶段先做可重放事件和内部来源

第一阶段候选来源：

```text
watchlist
已有 Leader 管理记录
已持久化 activity event 中可归因的钱包
```

公开 leaderboard 保留为后续来源，不作为第一阶段 apply-ready 的依赖。原因是 leaderboard 端点可能不稳定或未文档化，不能让第一版核心闭环被外部端点卡住。

如果当前 app 没有持久化 raw activity event，需要新增 append-only 表：

```text
leader_activity_event
```

推荐唯一键：

```text
source + event_id
```

如果 event_id 缺失，必须生成稳定 fallback key，避免重启或重放时重复纸跟。

### 4. 评分必须可解释且可版本化

不要只保存一个总分。保存分项分数、总分、score version 和 reason。

分项：

```text
profit_signal
repeatability
liquidity_fit
entry_price_fit
slippage_risk
holding_period_fit
market_type_risk
drawdown_risk
exit_liquidity_risk
data_freshness
filter_pass_rate
```

研究状态迁移必须记录 rule version，避免以后调整阈值后无法解释旧决策。

默认阈值：

```text
DISCOVERED -> CANDIDATE
  条件：候选来自可信来源，地址格式有效

CANDIDATE -> PAPER
  条件：score >= 60，未锁定，未退休，source 48 小时内新鲜

PAPER -> TRIAL_READY
  条件：
    纸跟时间 >= 7 天
    模拟交易数 >= 10
    copyable PnL > 0
    最大回撤 >= -15%
    UNKNOWN quote 暴露占比 <= 20%
    filtered-trade ratio < 50%
    无 critical risk flag

PAPER -> COOLDOWN
  条件：
    最大回撤 < -20%
    或 10 笔后 copyable PnL < -5
    或 quote/source stale > 72 小时
    或存在 thin_liquidity_exit_risk

COOLDOWN -> CANDIDATE
  条件：cooldown_until 已过，source 重新新鲜，未锁定

COOLDOWN -> RETIRED
  条件：3 次冷却周期，或 30 天无新鲜来源
```

这些阈值是第一版保守默认值，不是金融真理；后续可配置。

### 5. 后端强制真钱边界

研究代理可以推荐小额试跟，但不能启用真钱。

手动审批流：

```text
用户打开候选详情
  -> 点击“创建禁用试跟配置”
  -> 前端展示 fixed amount、max daily loss、max daily orders、price range、max position value
  -> 用户确认
  -> 后端组装 CopyTradingCreateRequest
  -> 后端强制 enabled=false
  -> 返回 created copyTrading
  -> 用户必须去 跟单配置 页面手动启用
```

后端必须忽略或拒绝任何客户端传入的 `enabled=true`、更大 fixed amount 或更松风控参数。安全边界在后端，不在弹窗。

### 6. 操作台先做“晨报式”决策界面

第一版页面不做复杂量化终端。优先回答：

```text
今天有什么变化？
哪些候选需要我决策？
为什么推荐？
证据是什么？
风险是什么？
我能安全点击什么？
```

页面模块：

```text
Run Status
Candidate Detail
Paper Sessions
Pending Decisions
Source Health
```

### 7. 调度必须有 overlap guard 和 run record

新增 `LeaderResearchJobService` 使用现有 Spring `@Scheduled` 模式，但默认关闭或可配置开启。

每次运行必须创建 `leader_research_run`，记录：

```text
status
started_at
finished_at
duration
source counts
candidate counts
error class
error message
partial failure flag
```

如果上一次运行还未结束，新运行 MUST 跳过并记录 overlap 事件，不能并发推进状态。

## Risks / Trade-offs

- [纸跟精度看起来比实际更高] -> 每笔纸跟交易必须保存 fill assumption、quote confidence 和 valuation status；UI 必须展示 unknown/partial 状态。
- [source 失败导致错误降级候选] -> source failure 只更新 source health，不得静默删除候选或降低 score。
- [自动状态机误伤人工决策] -> `locked=true` 的候选和池子项不得被自动化改变状态、建议配置或 trial recommendation。
- [通知系统 Telegram-first] -> 第一阶段先落 `leader_research_event` 和控制台提示，外部通知渠道在事件流稳定后再复用/扩展通知配置。
- [表数量增加导致复杂度上升] -> 用里程碑拆分：先数据 spine，再纸跟，再评分状态机，再 UI/通知，再手动审批。
- [数据量增长影响性能] -> 为 event、candidate、paper trade、paper position、run、event 表添加唯一键和查询索引，并对详情列表分页。

## Migration Plan

1. 新增研究与纸跟相关 Flyway 迁移，所有表为空表创建，不修改现有真实订单热表。
2. 部署后端，但 research job 默认关闭。
3. 部署前端操作台，若 job 未启用则展示禁用/空态。
4. 在本地或测试环境手动运行 research job，验证 run record、source health、candidate、paper session 和 paper trade。
5. 开启 watchlist + existing leaders 来源，不启用 public leaderboard。
6. 确认内部 research events 稳定后再接外部通知渠道。
7. 最后启用手动“创建禁用试跟配置”入口。

Rollback:

- 如果 job 出错，关闭 research job 配置并保留数据表。
- 如果 UI 出错，隐藏菜单或回滚前端路由。
- 如果迁移失败，在启用 job 前停止部署；不要在热回滚中删除已有研究数据。
