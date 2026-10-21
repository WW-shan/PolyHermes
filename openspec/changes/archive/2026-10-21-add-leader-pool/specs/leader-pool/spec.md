## ADDED Requirements

### Requirement: 提供 Leader 池入口
系统 SHALL 在受保护的 Web 应用中提供 `Leader 池` 页面入口，用于管理小仓位摊大饼策略的 leader 候选池。

#### Scenario: 左侧菜单展示 Leader 池
- **WHEN** 已登录用户打开任意受保护页面
- **THEN** 左侧 `跟单交易` 菜单中 MUST 展示 `Leader 池`，并位于 `跟单配置` 与 `Leader 管理` 之间

#### Scenario: 打开 Leader 池页面
- **WHEN** 用户点击左侧菜单中的 `Leader 池`
- **THEN** 系统 MUST 导航到 `/leader-pool` 并展示 Leader 池页面

### Requirement: 管理池子成员
系统 SHALL 支持把已有 leader 加入 Leader 池，并保证同一个 leader 在池子中最多只有一个池子项。

#### Scenario: 从 Leader 管理加入池子
- **WHEN** 用户在 `Leader 管理` 页面点击某个 leader 的 `加入 Leader 池`
- **THEN** 系统 MUST 为该 leader 创建池子项，并默认状态为 `CANDIDATE`

#### Scenario: 重复加入同一 leader
- **WHEN** 用户尝试把已经在池子中的 leader 再次加入池子
- **THEN** 系统 MUST 不创建重复池子项，并 MUST 返回明确提示

#### Scenario: 并发重复加入同一 leader
- **WHEN** 两个请求同时把同一个 leader 加入池子
- **THEN** 系统 MUST 最多创建一个池子项，并 MUST 对冲突请求返回已在池子中的明确提示

#### Scenario: 加入不存在的 leader
- **WHEN** 用户请求把不存在的 leader 加入池子
- **THEN** 系统 MUST 拒绝请求，并 MUST 返回 leader 不存在的错误

### Requirement: 维护池子状态
系统 SHALL 支持维护 leader 池状态，用于区分候选、观察、小额试跟、冷却和淘汰。

#### Scenario: 更新池子状态
- **WHEN** 用户把某个池子项从 `CANDIDATE` 更新为 `WATCH`、`TRIAL`、`COOLDOWN` 或 `RETIRED`
- **THEN** 系统 MUST 保存新状态并更新该池子项的更新时间

#### Scenario: 冷却状态包含冷却截止时间
- **WHEN** 用户把某个池子项设置为 `COOLDOWN` 并提供冷却截止时间
- **THEN** 系统 MUST 保存 `cooldownUntil`

#### Scenario: 淘汰不删除 leader 地址
- **WHEN** 用户把某个池子项设置为 `RETIRED`
- **THEN** 系统 MUST 保留对应 `copy_trading_leaders` 记录，不得删除 leader 地址

### Requirement: 展示池子概览
系统 SHALL 在 Leader 池页面展示组合级概览，帮助用户理解小仓位策略的风险暴露。

#### Scenario: 展示统计卡片
- **WHEN** 用户打开 Leader 池页面
- **THEN** 页面 MUST 展示池子人数、试跟中人数、估算最坏暴露和待处理风险

#### Scenario: 估算最坏暴露
- **WHEN** 池子中存在 `TRIAL` 或 `ACTIVE` 状态项
- **THEN** 系统 MUST 使用这些项的建议最大持仓值合计估算最坏暴露

#### Scenario: 池子为空
- **WHEN** 池子没有任何成员
- **THEN** 页面 MUST 展示空态，并 MUST 提示用户可以从 `Leader 管理` 加入 leader

### Requirement: 展示池子列表信息
系统 SHALL 在 Leader 池列表中展示池子状态、leader 基础信息、建议配置和现有跟单配置状态。

#### Scenario: 列表展示 leader 和状态
- **WHEN** 用户打开 Leader 池页面且池子中存在成员
- **THEN** 列表 MUST 展示 leader 名称或地址、池子状态、来源、建议固定金额、建议每日最大单数、建议每日最大亏损和最后复核时间

#### Scenario: 展示现有跟单配置状态
- **WHEN** 某个池子 leader 已经存在跟单配置
- **THEN** 列表 MUST 展示该 leader 的跟单配置数量，并 MUST 标明是否存在启用中的跟单配置

#### Scenario: 打开外部 profile
- **WHEN** 用户点击池子项中的 Polymarket profile 操作
- **THEN** 系统 MUST 打开该 leader 对应的 Polymarket profile 地址

#### Scenario: 池子列表不默认逐行查询余额
- **WHEN** 用户打开 Leader 池页面且池子中存在多个成员
- **THEN** 页面 MUST NOT 为每个池子项默认逐行请求 leader 余额接口

### Requirement: 创建保守小额试跟配置
系统 SHALL 支持从 Leader 池为某个 leader 创建保守的小额跟单配置，并复用现有跟单配置创建逻辑。

#### Scenario: 创建默认禁用的试跟配置
- **WHEN** 用户从池子项点击创建小额试跟配置并完成确认
- **THEN** 系统 MUST 创建 `FIXED` 模式跟单配置，默认固定金额为 1，默认启用状态为禁用

#### Scenario: 使用保守风控默认值
- **WHEN** 系统从池子创建小额试跟配置
- **THEN** 创建请求 MUST 使用保守默认值，包括每日最大单数 10、每日最大亏损 5、价格容忍度 1、最低价格 0.10、最高价格 0.80、最大持仓 5

#### Scenario: 创建成功后更新池子状态
- **WHEN** 小额试跟配置创建成功
- **THEN** 系统 MUST 把池子项状态更新为 `TRIAL`，并 MUST 记录最后晋升时间

#### Scenario: 创建失败不更新状态
- **WHEN** 小额试跟配置创建失败
- **THEN** 系统 MUST 保留原池子状态，并 MUST 向用户展示失败原因

### Requirement: 保护真钱交易安全
系统 SHALL 防止 Leader 池功能在没有明确用户动作的情况下扩大真实交易风险。

#### Scenario: 不自动创建跟单配置
- **WHEN** 用户仅把 leader 加入池子或更新池子状态
- **THEN** 系统 MUST NOT 自动创建真实跟单配置

#### Scenario: 不自动启用大额跟单
- **WHEN** 系统从池子创建小额试跟配置
- **THEN** 系统 MUST NOT 自动创建大额配置，并 MUST 使用保守小额参数

#### Scenario: 立即启用需要确认
- **WHEN** UI 提供创建后立即启用选项
- **THEN** UI MUST 在提交前展示固定金额、每日最大单数、每日最大亏损和最大持仓，并 MUST 要求用户确认

#### Scenario: 后端拒绝未确认的立即启用
- **WHEN** 创建试跟配置请求要求立即启用但没有提供确认标记
- **THEN** 后端 MUST 拒绝请求，并 MUST NOT 创建跟单配置

### Requirement: 支持更新建议配置
系统 SHALL 支持用户更新池子项的建议固定金额和建议风控参数，但不得直接修改已有真实跟单配置。

#### Scenario: 更新建议配置
- **WHEN** 用户更新池子项的建议固定金额、每日最大单数、每日最大亏损、价格区间或最大持仓
- **THEN** 系统 MUST 保存这些建议字段，并 MUST 更新池子项的更新时间

#### Scenario: 建议配置不影响已有跟单
- **WHEN** 用户只更新池子项建议配置
- **THEN** 系统 MUST NOT 修改任何已有 `copy_trading` 记录

#### Scenario: 拒绝无效建议配置
- **WHEN** 用户提交负数固定金额、无效每日最大单数、负数每日亏损、无效价格区间或负数最大持仓
- **THEN** 系统 MUST 拒绝保存，并 MUST 保留原建议配置不变

### Requirement: 提供后端 API
系统 SHALL 提供 Leader 池后端 API，用于列表查询、加入池子、状态更新、建议配置更新、创建试跟配置和移除池子项。

#### Scenario: 查询池子列表
- **WHEN** 前端请求 `/api/copy-trading/leader-pool/list`
- **THEN** 后端 MUST 返回池子项列表、汇总信息和每个池子项的 leader/跟单配置聚合信息

#### Scenario: 池子列表使用批量聚合
- **WHEN** 后端生成池子列表响应
- **THEN** 后端 MUST 使用批量查询或聚合查询获取 leader 与跟单配置信息，不得对每个池子项逐条查询 leader 和跟单配置

#### Scenario: 创建试跟配置 API
- **WHEN** 前端请求 `/api/copy-trading/leader-pool/create-trial-config`
- **THEN** 后端 MUST 校验池子项、账户和 leader，并 MUST 通过现有跟单配置服务创建配置

#### Scenario: 已存在同账户同 leader 跟单配置
- **WHEN** 用户为某个账户和 leader 创建试跟配置且该账户已存在该 leader 的跟单配置
- **THEN** 系统 MUST 拒绝默认试跟创建，并 MUST 返回已有配置提示

#### Scenario: 移除池子项
- **WHEN** 用户请求移除某个池子项
- **THEN** 系统 MUST 只移除池子项，不得删除 leader 地址，不得删除已有跟单配置

### Requirement: 记录关键操作日志
系统 SHALL 对 Leader 池关键操作记录后端日志，便于排查交易相关行为。

#### Scenario: 记录加入和状态变化
- **WHEN** leader 被加入池子或池子状态发生变化
- **THEN** 后端 MUST 记录包含 leaderId、池子项 ID 和目标状态的日志

#### Scenario: 记录试跟配置创建结果
- **WHEN** 系统尝试从池子创建小额试跟配置
- **THEN** 后端 MUST 记录创建成功或失败日志，并包含 leaderId、accountId 和错误原因
