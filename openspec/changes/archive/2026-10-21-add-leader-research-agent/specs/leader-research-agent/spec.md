## ADDED Requirements

### Requirement: 提供 Leader Research 操作台
系统 SHALL 在受保护的 Web 应用中提供 Leader Research 操作台，用于查看自动研究运行状态、候选、纸跟证据、待处理决策和数据源健康。

#### Scenario: 打开操作台
- **WHEN** 已登录用户打开 Leader Research 页面
- **THEN** 系统 MUST 展示最近研究运行状态、候选概览、待处理决策、纸跟概览和数据源健康

#### Scenario: 研究功能未启用
- **WHEN** 研究 job 未启用或尚未运行
- **THEN** 页面 MUST 展示明确空态，并 MUST NOT 暗示系统已经完成 leader 研究

#### Scenario: 数据源部分失败
- **WHEN** 部分候选来源失败但其他来源可用
- **THEN** 页面 MUST 展示 degraded 状态，并 MUST 继续展示可用来源产生的候选和证据

### Requirement: 记录研究运行
系统 SHALL 为每次自动或手动研究运行记录 run record，确保研究结果可追溯、可排查且不会静默失败。

#### Scenario: 成功运行研究任务
- **WHEN** 研究任务完成一次正常运行
- **THEN** 系统 MUST 保存运行状态、开始时间、结束时间、耗时、来源统计、候选统计和成功状态

#### Scenario: 研究任务部分失败
- **WHEN** 研究任务中某个来源或阶段失败但整体任务仍可继续
- **THEN** 系统 MUST 将 run 标记为 partial failure，并 MUST 保存失败来源、错误类型和错误信息

#### Scenario: 防止并发运行
- **WHEN** 上一次研究任务仍在运行时触发新的研究任务
- **THEN** 系统 MUST 跳过新的任务或拒绝新的任务，并 MUST 记录 overlap 事件

### Requirement: 多源发现候选
系统 SHALL 支持从多个来源发现 leader 候选，并将候选标准化为统一研究候选记录。

#### Scenario: 从 watchlist 发现候选
- **WHEN** 系统运行 watchlist 来源
- **THEN** 系统 MUST 为 watchlist 中的有效钱包创建或更新研究候选

#### Scenario: 从已有 Leader 管理记录发现候选
- **WHEN** 系统运行已有 leader 来源
- **THEN** 系统 MUST 为 `copy_trading_leaders` 中的有效 leader 创建或更新研究候选

#### Scenario: 从 activity 事件发现候选
- **WHEN** 系统从已持久化 activity event 中识别可归因的钱包
- **THEN** 系统 MUST 创建或更新研究候选，并 MUST 记录来源为 activity-derived

#### Scenario: 来源返回空结果
- **WHEN** 某个来源成功运行但没有发现候选
- **THEN** 系统 MUST 记录该来源成功且候选数为 0，不得把它当作失败

#### Scenario: 来源失败
- **WHEN** 某个来源超时、限流、解析失败或返回异常
- **THEN** 系统 MUST 更新来源健康状态，并 MUST NOT 因本次失败删除候选或自动降级候选

### Requirement: 持久化 activity event
系统 SHALL 持久化用于纸跟的 activity event，确保纸跟可以重放、去重和排查。

#### Scenario: 保存新的 activity event
- **WHEN** 系统接收到包含交易者、市场、方向、价格、数量和时间的 activity event
- **THEN** 系统 MUST 保存该事件，并 MUST 记录 source、event id、钱包地址、市场、方向、价格、数量、时间戳和原始 payload 摘要

#### Scenario: 重复 activity event
- **WHEN** 系统再次接收到相同 source 和 event id 的 activity event
- **THEN** 系统 MUST 不创建重复事件，并 MUST 保持纸跟处理幂等

#### Scenario: activity event 缺少必需字段
- **WHEN** activity event 缺少钱包地址、市场、方向、价格或数量
- **THEN** 系统 MUST 标记该事件不可用于纸跟，并 MUST 记录原因

### Requirement: 建立独立纸跟账本
系统 SHALL 使用独立纸跟账本记录模拟买卖、模拟持仓、过滤结果和纸跟 PnL，不得使用真实订单跟踪表伪装纸跟。

#### Scenario: 启动纸跟 session
- **WHEN** 研究候选达到进入纸跟的条件
- **THEN** 系统 MUST 创建 paper session，并 MUST 将候选研究状态更新为 `PAPER`

#### Scenario: 记录纸跟买入
- **WHEN** 已进入纸跟的候选出现符合过滤条件的 BUY activity event
- **THEN** 系统 MUST 记录 paper trade 和 paper position，并 MUST 保存模拟价格、模拟数量、fill assumption 和 quote confidence

#### Scenario: 记录纸跟卖出
- **WHEN** 已进入纸跟的候选出现可匹配的 SELL activity event
- **THEN** 系统 MUST 更新对应 paper position，并 MUST 记录 realized paper PnL

#### Scenario: 记录被过滤交易
- **WHEN** 候选交易因价格区间、流动性、风控或数据缺失被过滤
- **THEN** 系统 MUST 记录 filtered paper trade 或 research event，并 MUST 保存 filter reason

#### Scenario: 估值不可用
- **WHEN** 纸跟持仓无法获得可靠 quote 或 position valuation
- **THEN** 系统 MUST 将 valuation status 标记为 `UNAVAILABLE` 或 `UNKNOWN`，并 MUST NOT 将其计为 confirmed zero

### Requirement: 计算可解释 copyability score
系统 SHALL 为研究候选计算可解释的 copyability score，并保存分项分数、总分、版本和原因。

#### Scenario: 保存分项分数
- **WHEN** 系统计算候选评分
- **THEN** 系统 MUST 保存收益信号、可重复性、流动性适配、入场价格适配、滑点风险、持仓周期、市场类型风险、回撤风险、退出流动性风险、数据新鲜度和过滤通过率

#### Scenario: 保存评分版本
- **WHEN** 系统保存候选评分
- **THEN** 系统 MUST 保存 score version，以便后续解释历史评分

#### Scenario: 单次大赢不能主导评分
- **WHEN** 候选只有单次异常盈利但样本量不足
- **THEN** 系统 MUST 限制收益信号对总分的影响，并 MUST 通过样本量或可重复性降低晋升概率

### Requirement: 自动推进研究状态
系统 SHALL 基于透明规则自动推进研究状态，并记录每次状态变化的原因。

#### Scenario: 候选进入纸跟
- **WHEN** 候选分数达到阈值、来源新鲜、未退休且未锁定
- **THEN** 系统 MUST 将研究状态从 `CANDIDATE` 推进为 `PAPER`，并 MUST 记录规则版本和原因

#### Scenario: 纸跟达标进入试跟建议
- **WHEN** 候选纸跟至少 7 天、模拟交易至少 10 笔、copyable PnL 为正、最大回撤不低于 -15%、UNKNOWN quote 暴露不超过 20%、过滤比例低于 50% 且无 critical risk flag
- **THEN** 系统 MUST 将研究状态推进为 `TRIAL_READY`，并 MUST 生成试跟建议事件

#### Scenario: 纸跟风险进入冷却
- **WHEN** 候选最大回撤低于 -20%、10 笔后 copyable PnL 小于 -5、quote 或 source stale 超过 72 小时，或出现 thin liquidity exit risk
- **THEN** 系统 MUST 将研究状态推进为 `COOLDOWN`，并 MUST 记录风险原因

#### Scenario: 冷却后恢复候选
- **WHEN** 候选冷却截止时间已过、来源重新新鲜且未锁定
- **THEN** 系统 MAY 将研究状态恢复为 `CANDIDATE`，并 MUST 记录恢复原因

#### Scenario: 多次冷却后退休
- **WHEN** 候选经历 3 次冷却周期或 30 天没有新鲜来源
- **THEN** 系统 MAY 将研究状态推进为 `RETIRED`，并 MUST 记录退休原因

### Requirement: 尊重锁定候选
系统 SHALL 支持锁定研究候选或 Leader 池项，避免自动任务覆盖人工判断。

#### Scenario: 锁定候选
- **WHEN** 用户锁定某个研究候选
- **THEN** 自动任务 MUST NOT 改变该候选的研究状态、试跟建议、建议配置或冷却/退休状态

#### Scenario: 锁定候选仍可更新证据
- **WHEN** 锁定候选出现新的来源证据或纸跟证据
- **THEN** 系统 MAY 追加只读证据，但 MUST NOT 改变人工锁定的决策字段

### Requirement: 保守增强 Leader 池
系统 SHALL 允许研究代理保守地增强现有 Leader 池记录，但不得替代 Leader 池的人工作业语义。

#### Scenario: 研究候选同步到 Leader 池
- **WHEN** 候选进入 `CANDIDATE` 或更高研究状态且需要展示给用户
- **THEN** 系统 MAY 创建或更新对应 Leader 池项，并 MUST 保留 Leader 池作为候选决策层

#### Scenario: 试跟建议不等于真实试跟状态
- **WHEN** 候选研究状态为 `TRIAL_READY`
- **THEN** 系统 MUST NOT 自动把 Leader 池状态改为 `TRIAL` 或 `ACTIVE`

#### Scenario: 不自动放大建议配置
- **WHEN** 自动任务更新已有 Leader 池记录
- **THEN** 系统 MUST NOT 自动增加 suggested fixed amount、suggested max daily loss 或 suggested max daily orders

#### Scenario: 不覆盖手工备注
- **WHEN** 自动任务写入研究摘要
- **THEN** 系统 MUST NOT 覆盖用户手工 notes，只能追加或写入独立研究摘要字段

### Requirement: 手动创建禁用试跟配置
系统 SHALL 支持用户从试跟建议手动创建禁用的保守真实跟单配置，并禁止研究代理自动启用真钱。

#### Scenario: 创建禁用试跟配置
- **WHEN** 用户从 `TRIAL_READY` 候选详情点击创建禁用试跟配置并确认
- **THEN** 后端 MUST 通过现有跟单配置服务创建 `enabled=false` 的保守 `FIXED` 配置

#### Scenario: 后端强制禁用状态
- **WHEN** 创建禁用试跟配置请求包含 `enabled=true` 或其他试图立即启用的字段
- **THEN** 后端 MUST 忽略或拒绝这些字段，并 MUST NOT 创建启用中的真实跟单配置

#### Scenario: 创建前展示风险参数
- **WHEN** 用户确认创建禁用试跟配置
- **THEN** UI MUST 展示 fixed amount、max daily loss、max daily orders、price range 和 max position value

#### Scenario: 已存在同账户同 leader 配置
- **WHEN** 用户尝试为同一账户和 leader 创建禁用试跟配置且配置已存在
- **THEN** 系统 MUST 拒绝默认创建，并 MUST 展示已有配置提示

### Requirement: 记录研究事件和通知摘要
系统 SHALL 为关键研究动作记录事件，并在事件流稳定后提供通知摘要。

#### Scenario: 记录关键研究事件
- **WHEN** 系统发现新候选、启动纸跟、晋升试跟建议、进入冷却、退休、来源失败或 valuation stale
- **THEN** 系统 MUST 写入 leader research event，并 MUST 包含候选、事件类型、原因和时间

#### Scenario: 操作台展示待处理事件
- **WHEN** 存在试跟建议或数据源失败事件
- **THEN** 操作台 MUST 展示这些事件，并 MUST 提供跳转到候选详情或源健康详情的入口

#### Scenario: 外部通知失败
- **WHEN** 外部通知渠道发送失败
- **THEN** 系统 MUST 保留 research event，并 MUST 标记通知失败，不得丢失研究事件

### Requirement: 提供研究 API
系统 SHALL 提供受保护的 Leader Research API，用于运行研究、查看运行状态、查看候选、查看纸跟详情、查看事件和创建禁用试跟配置。

#### Scenario: 手动触发研究运行
- **WHEN** 用户请求手动运行研究任务
- **THEN** 后端 MUST 校验权限和 overlap guard，并 MUST 返回 run record 或明确跳过原因

#### Scenario: 查询候选列表
- **WHEN** 前端请求候选列表
- **THEN** 后端 MUST 返回候选研究状态、来源、总分、关键风险、纸跟摘要和待处理决策状态

#### Scenario: 查询候选详情
- **WHEN** 前端请求候选详情
- **THEN** 后端 MUST 返回来源历史、score components、paper session、paper trades、paper positions、research events 和 Leader 池映射信息

#### Scenario: 查询源健康
- **WHEN** 前端请求数据源健康
- **THEN** 后端 MUST 返回每个来源的最近成功时间、最近失败时间、错误类型、错误信息和 stale 状态

### Requirement: 保证研究任务可观测和可回滚
系统 SHALL 提供研究任务的日志、指标、运行记录和开关，确保上线后可以诊断、暂停和回滚。

#### Scenario: 研究 job 默认关闭
- **WHEN** 新版本首次部署
- **THEN** 研究 job SHOULD 默认为关闭或可配置关闭，直到人工验证通过

#### Scenario: 记录结构化日志
- **WHEN** 研究任务运行、候选状态变化、纸跟交易记录、源失败或手动审批发生
- **THEN** 后端 MUST 记录包含 runId、candidateId、source、eventId、oldState、newState 或 copyTradingId 的结构化日志

#### Scenario: 关闭研究 job
- **WHEN** 运维关闭研究 job 配置
- **THEN** 系统 MUST 停止自动研究运行，但 MUST 保留已有研究数据和页面查询能力
