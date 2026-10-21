## Context

PolyHermes 当前把跟单流程拆成两层：

- `copy_trading_leaders` 保存可被跟单的钱包地址，前端入口是 `Leader 管理`。
- `copy_trading` 保存真实跟单配置，前端入口是 `跟单配置`。

这两层之间缺少“候选池/配仓台”。用户如果想用小仓位摊大饼，只能靠脑子或外部笔记维护候选状态，然后手动创建真实跟单配置。这对真钱交易不够安全：观察、试跟、冷却、淘汰没有独立状态，配置也容易沿用过松默认值。

本设计新增 `Leader 池`，把它定位为 `Leader 管理` 与 `跟单配置` 中间的决策层。它不替代现有 leader 地址库，也不替代真实跟单配置；它负责帮助用户决定“谁进入候选、谁小额试跟、谁冷却或淘汰”。

## Goals / Non-Goals

**Goals:**

- 新增左侧菜单入口 `跟单交易 -> Leader 池`，路径为 `/leader-pool`。
- 支持把已有 leader 加入池子，并维护池子状态。
- 支持从 `Leader 管理` 一键加入池子。
- 支持从池子创建保守小额跟单配置。
- 在页面上展示池子人数、试跟人数、估算最坏暴露、待处理风险。
- 复用现有 leader、账户、跟单配置和统计能力，避免重复业务逻辑。
- 用显式确认和保守默认值保护真钱交易安全。

**Non-Goals:**

- 不接入 Polymarket leaderboard 自动发现。
- 不自动启用大额跟单。
- 不自动加仓。
- 不做 AI 预测评分。
- 不做复杂多账户资金托管。
- 不修改真实 PnL 统计口径。统计中仍必须区分真实归零仓位和行情/持仓数据不可用。

## Decisions

### 1. 新增独立 `copy_trading_leader_pool` 表

采用独立表，不把池子字段塞进 `copy_trading_leaders`。

原因：

- `copy_trading_leaders` 是地址库，leader 可以存在但不在当前策略池中。
- 池子有状态、来源、建议配置、冷却时间、复核时间，这些是策略决策数据，不是 leader 基础资料。
- 后续 leaderboard radar、手动观察、亏损诊断都可以写入同一池子表，而不污染 leader 地址库。

建议迁移：

```text
backend/src/main/resources/db/migration/V41__create_copy_trading_leader_pool.sql
```

建议字段：

```text
id BIGINT AUTO_INCREMENT PRIMARY KEY
leader_id BIGINT NOT NULL
status VARCHAR(20) NOT NULL
source VARCHAR(50) NOT NULL DEFAULT 'MANUAL'
source_rank INT NULL
score DECIMAL(20, 8) NULL
reason TEXT NULL
notes TEXT NULL
suggested_fixed_amount DECIMAL(20, 8) NOT NULL DEFAULT 1.00000000
suggested_max_daily_orders INT NOT NULL DEFAULT 10
suggested_max_daily_loss DECIMAL(20, 8) NOT NULL DEFAULT 5.00000000
suggested_min_price DECIMAL(20, 8) NULL DEFAULT 0.10000000
suggested_max_price DECIMAL(20, 8) NULL DEFAULT 0.80000000
suggested_max_position_value DECIMAL(20, 8) NULL DEFAULT 5.00000000
last_reviewed_at BIGINT NULL
last_promoted_at BIGINT NULL
cooldown_until BIGINT NULL
locked BOOLEAN NOT NULL DEFAULT FALSE
created_at BIGINT NOT NULL
updated_at BIGINT NOT NULL
```

约束与索引：

```text
UNIQUE KEY uk_leader_pool_leader_id (leader_id)
INDEX idx_leader_pool_status (status)
INDEX idx_leader_pool_source (source)
FOREIGN KEY (leader_id) REFERENCES copy_trading_leaders(id) ON DELETE CASCADE
```

v1 池子是全局策略池，不按账户拆分。账户只在创建小额试跟配置时选择；同一个 leader 在池子里只出现一次，避免把“候选观察状态”拆成多份互相打架的笔记。若后续需要多账户独立池，再新增 `account_id` 并迁移为 `UNIQUE(account_id, leader_id)`，本次不做。

### 2. 状态机后端完整、前端 v1 精简

后端状态枚举预留：

```text
CANDIDATE
WATCH
PAPER
TRIAL
ACTIVE
COOLDOWN
RETIRED
```

前端 v1 展示：

```text
候选
观察
小额试跟
冷却
淘汰
```

原因：

- 后端预留可以让后续 radar 或自动规则不需要迁移状态字段。
- 前端不一次暴露太多状态，避免用户每天面对一堆概念。
- 锁定是独立布尔字段 `locked`，不是状态。这样 `TRIAL + locked`、`COOLDOWN + locked` 都能表达，不会把“交易阶段”和“自动任务是否可改”混在一起。

状态更新规则：

- `RETIRED` 不删除 leader 地址，只让池子项退出关注。
- `COOLDOWN` 可以设置 `cooldownUntil`。
- `locked=true` 的池子项不允许被后续自动任务改状态。v1 没有自动任务，但字段先保留。

状态流：

```text
             +---------+
             | RETIRED |
             +---------+
                  ^
                  |
CANDIDATE -> WATCH -> TRIAL -> ACTIVE
     |          |        |
     +----------+--------+
                |
                v
           COOLDOWN

locked=true 是覆盖层，不是状态：
任何状态 + locked=true => 后续自动任务不得改状态
```

### 3. 创建试跟配置必须复用 `CopyTradingService.createCopyTrading`

新增 `LeaderPoolService.createTrialConfig`，内部组装 `CopyTradingCreateRequest`，然后调用现有 `CopyTradingService.createCopyTrading`。

原因：

- 现有服务已经处理账户、leader、模板、监听更新、字段校验。
- 不复制跟单创建逻辑，避免两套规则不同步。

默认请求建议：

```text
enableImmediately = false
confirm = false
enabled = false
copyMode = FIXED
fixedAmount = suggestedFixedAmount 默认 1
maxOrderSize = suggestedFixedAmount 默认 1
minOrderSize = 1
maxDailyOrders = suggestedMaxDailyOrders 默认 10
maxDailyLoss = suggestedMaxDailyLoss 默认 5
priceTolerance = 1
minPrice = 0.10
maxPrice = 0.80
maxPositionValue = 5
supportSell = true
pushFailedOrders = true
pushFilteredOrders = true
configName = "Leader池-<leaderName或地址后6位>"
```

`enabled=false` 是推荐默认。若前端允许立即启用，必须弹出确认，并明确展示固定金额、最大日单数、最大日亏损和最大持仓。

后端也必须执行同一条安全规则：当 `enableImmediately=true` 时，`confirm` 必须为 `true`，否则拒绝创建。真钱交易不能只靠前端弹窗保护。

创建前还必须检查同一 `accountId + leaderId` 是否已经存在跟单配置。默认拒绝重复创建，并返回已有配置提示。PolyHermes 允许同一 leader 多个跟单配置，但 Leader 池的一键试跟不应该制造重复配置；真要高级配置，用户应该走 `跟单配置` 页面手动创建。

建议配置必须在后端校验，不只靠前端输入框：

```text
suggestedFixedAmount > 0
suggestedMaxDailyOrders between 1 and 100
suggestedMaxDailyLoss > 0
suggestedMinPrice / suggestedMaxPrice in [0, 1] when present
suggestedMinPrice <= suggestedMaxPrice when both present
suggestedMaxPositionValue > 0 when present
```

这是“雨露均沾”的安全边界：小额可以分散，但不能因为一个坏输入把单个 leader 变成隐形重仓。

### 4. 新增 leader 池 API，路径归在 copy-trading 下

新增控制器：

```text
backend/src/main/kotlin/com/wrbug/polymarketbot/controller/copytrading/leaderpool/LeaderPoolController.kt
```

API：

```text
POST /api/copy-trading/leader-pool/list
POST /api/copy-trading/leader-pool/add
POST /api/copy-trading/leader-pool/update-status
POST /api/copy-trading/leader-pool/update-plan
POST /api/copy-trading/leader-pool/create-trial-config
POST /api/copy-trading/leader-pool/remove
```

`list` 响应应聚合：

- leader 基础信息。
- 池子状态和建议配置。
- 该 leader 的跟单配置数量。
- 该 leader 是否已有启用中的跟单配置。
- 估算最坏暴露字段。

聚合必须使用批量查询或聚合查询，不能对每个池子项逐个查 leader 和跟单配置。计划新增 repository 方法，例如：

```text
LeaderRepository.findAllById(...)
CopyTradingRepository.findByLeaderIdIn(...)
CopyTradingRepository.countByLeaderIdInGrouped(...) 或用 findByLeaderIdIn 后内存分组
```

这保持 v1 足够简单，同时避免池子 50 人时打出 100 多次 SQL。软件会在你最懒得排查的时候提醒你什么叫 N+1。

主要数据流：

```text
Leader 管理
  -> POST /leader-pool/add
  -> LeaderPoolService.addToPool
  -> copy_trading_leader_pool

Leader 池页面
  -> POST /leader-pool/list
  -> LeaderPoolService.getPoolList
  -> batch read leaders + copy_trading
  -> summary cards + table

Leader 池创建小额试跟
  -> POST /leader-pool/create-trial-config
  -> LeaderPoolService.createTrialConfig
  -> CopyTradingService.createCopyTrading
  -> copy_trading
  -> pool.status = TRIAL only after success
```

### 5. 前端页面独立，Leader 管理只放轻入口

新增页面：

```text
frontend/src/pages/LeaderPool.tsx
```

修改路由：

```text
frontend/src/App.tsx
```

修改菜单：

```text
frontend/src/components/Layout.tsx
```

菜单位置：

```text
跟单交易
  跟单配置
  Leader 池
  Leader 管理
  跟单模板
  回测
```

`LeaderList.tsx` 只新增“加入 Leader 池”操作。不要把池子状态和预算管理塞进 Leader 管理页。

`LeaderPool.tsx` 不默认逐行查询 leader 余额。Leader 管理页当前有逐个加载余额的重型交互，但池子页的核心任务是筛选、配仓和创建小额试跟配置；默认列表只展示后端一次返回的聚合字段，profile 和详情可以按需打开。

### 6. 预算概览只做提示，不做资金托管

页面顶部显示：

```text
池子人数
试跟中人数
估算最坏暴露
待处理风险
```

估算方式：

```text
TRIAL/ACTIVE 状态的池子项数量 * suggestedMaxPositionValue
```

如果某个池子项没有 `suggestedMaxPositionValue`，使用默认 5。

v1 可以先不持久化“总试验预算”。页面可用默认 50 显示提示，或提供前端输入但不存库。若实现持久化预算，优先使用现有系统配置能力，不新增复杂账户资金模型。

### 7. 错误态与空态

前端必须覆盖：

- 池子为空：提示从 `Leader 管理` 加入 leader，或在池子页选择已有 leader 加入。
- leader 已在池子：加入动作返回明确提示，不创建重复项。
- leader 不存在：提示先添加 leader。
- 没有账户：创建试跟配置前提示先添加账户。
- 创建配置失败：展示后端错误，不更新池子状态。
- 创建配置成功：池子状态更新为 `TRIAL`，并给出跳转到 `跟单配置` 的入口。

### 8. 调度与自动化

本次不新增定时任务。

后续 leaderboard radar 可以把候选写入 `copy_trading_leader_pool`，但必须遵守：

- 不自动创建真实跟单配置。
- 不改 `locked=true` 的池子项。
- 不自动把 `COOLDOWN` 或 `RETIRED` 改回试跟。

### 9. 可观测性

后端日志至少记录：

- leader 加入池子。
- 池子状态变化。
- 创建试跟配置成功/失败。
- 被拒绝的危险操作，例如重复加入、leader 不存在、账户不存在。

未来如果接入审计表，可把这些操作迁入审计事件。本次不强制新增审计表。

### 10. 错误码与国际化

Leader 池需要独立错误码，不要全部塞进 `BUSINESS_ERROR`。建议在现有错误码体系中新增：

```text
LEADER_POOL_NOT_FOUND(4251)
LEADER_POOL_ALREADY_EXISTS(4252)
LEADER_POOL_DUPLICATE_TRIAL_CONFIG(4253)
LEADER_POOL_CONFIRM_REQUIRED(4254)
SERVER_LEADER_POOL_LIST_FAILED(5451)
SERVER_LEADER_POOL_UPDATE_FAILED(5452)
SERVER_LEADER_POOL_CREATE_TRIAL_FAILED(5453)
```

这些编号避开当前 `ErrorCode` 中已经存在的 `4601` 冲突。实现时必须确认没有新增重复 code。

同时补齐 `messages_zh_CN.properties`、`messages_zh_TW.properties`、`messages_en.properties`。前端需要能看到明确错误，而不是一个泛泛的“业务逻辑错误”。

## Risks / Trade-offs

- [Risk] 用户误以为加入池子等于已经跟单 → Mitigation：页面文案明确区分 `观察`、`小额试跟` 和真实 `跟单配置`，创建配置后给出配置状态。
- [Risk] 一键创建配置触发真钱交易 → Mitigation：默认 `enabled=false`；若支持立即启用，必须二次确认。
- [Risk] 双击或多标签重复创建试跟配置 → Mitigation：前端提交时进入 loading 并禁用按钮；后端按 `accountId + leaderId` 检查已有配置，默认拒绝重复创建。
- [Risk] 池子状态和跟单配置状态不一致 → Mitigation：list 响应聚合当前跟单配置数量和启用状态；不把池子状态当作真实交易状态。
- [Risk] 同一 leader 重复加入池子 → Mitigation：数据库唯一约束 `leader_id`，服务层返回幂等提示。
- [Risk] 并发加入同一 leader 时先查再写仍然冲突 → Mitigation：保留数据库唯一约束，并捕获唯一约束异常，返回“已在池子中”的明确结果。
- [Risk] 建议配置输入为负数、超大值或无效价格区间 → Mitigation：后端校验所有建议配置字段，拒绝危险配置，不只依赖前端表单。
- [Risk] 池子列表照搬 Leader 管理逐行余额查询导致页面变慢 → Mitigation：池子列表默认只用后端聚合字段，不逐行拉余额。
- [Risk] 页面诱导追涨 → Mitigation：v1 不做收益率排行榜主导，不做 AI 评分；重点展示状态、预算、风险和保守配置。
- [Risk] 预算只是估算，不等于真实账户风险 → Mitigation：页面文案使用“估算最坏暴露”，不展示为账户余额或保证金。
- [Risk] 后续自动 radar 覆盖人工判断 → Mitigation：预留 `locked` 字段，自动任务不得修改锁定项，不得复活冷却/淘汰项。

## Migration Plan

1. 新增 Flyway 迁移 `V41__create_copy_trading_leader_pool.sql`。
2. 部署后 Flyway 自动创建新表，不改动现有表数据。
3. 新增 API 和前端入口上线后，池子默认为空。
4. 用户可以从现有 `Leader 管理` 手动加入 leader。
5. 回滚时可隐藏前端菜单和 API；新表保留不会影响现有跟单流程。
6. 若必须数据库回滚，可删除 `copy_trading_leader_pool` 表；不会影响 `copy_trading_leaders` 和 `copy_trading`。

## Locked v1 Decisions

- v1 不持久化“总试验预算”。页面只展示默认 50 的试验预算提示和估算最坏暴露，不新增账户资金模型。
- v1 UI 不提供“创建后立即启用”开关。池子创建的小额试跟配置默认 `enabled=false`；`enableImmediately` 与 `confirm` 只作为后端防御和后续扩展预留。
- v1 UI 只暴露“候选、观察、小额试跟、冷却、淘汰”。`PAPER` 和 `ACTIVE` 保留在后端枚举中，第一版不进入筛选标签和主操作流。
