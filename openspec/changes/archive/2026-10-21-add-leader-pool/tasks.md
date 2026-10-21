## 1. 后端数据模型

- [x] 1.1 新增 Flyway 迁移 `V41__create_copy_trading_leader_pool.sql`，创建 `copy_trading_leader_pool` 表、`leader_id` 唯一约束、状态/来源索引和 leader 外键。
- [x] 1.2 新增 `LeaderPool` JPA 实体，字段覆盖状态、来源、建议配置、复核时间、晋升时间、冷却时间、锁定标记和时间戳。
- [x] 1.3 新增 `LeaderPoolRepository`，支持按 leaderId 查询、判断是否存在、按状态查询、按创建时间排序和删除池子项。
- [x] 1.4 新增池子状态常量或枚举，覆盖 `CANDIDATE`、`WATCH`、`PAPER`、`TRIAL`、`ACTIVE`、`COOLDOWN`、`RETIRED`；锁定使用独立 `locked` 布尔字段，不作为状态。
- [x] 1.5 扩展 `CopyTradingRepository` 或新增查询方法，支持按 leaderId 批量查询/聚合跟单配置，避免 Leader 池列表 N+1 查询。

## 2. 后端 DTO 与服务

- [x] 2.1 新增 Leader 池请求/响应 DTO，包括列表请求、加入请求、状态更新请求、建议配置更新请求、创建试跟配置请求和移除请求。
- [x] 2.2 为创建试跟配置请求增加 `enableImmediately` 与 `confirm` 字段；当 `enableImmediately=true` 且未确认时后端必须拒绝创建。
- [x] 2.3 新增 `LeaderPoolService.addToPool`，校验 leader 存在、处理重复加入，并默认创建 `CANDIDATE` 状态池子项。
- [x] 2.4 在 `addToPool` 中捕获数据库唯一约束冲突，并返回“已在池子中”的明确业务结果，覆盖并发重复加入。
- [x] 2.5 新增 `LeaderPoolService.getPoolList`，使用批量查询聚合 leader 信息、池子状态、建议配置、跟单配置数量、是否存在启用跟单和汇总概览。
- [x] 2.6 新增 `LeaderPoolService.updateStatus`，支持状态更新、冷却截止时间保存、更新时间刷新，并确保淘汰不删除 leader 地址。
- [x] 2.7 新增 `LeaderPoolService.updatePlan`，只更新建议固定金额、每日最大单数、每日最大亏损、价格区间和最大持仓，不修改已有 `copy_trading`。
- [x] 2.8 新增 `LeaderPoolService.createTrialConfig`，先检查同一 `accountId + leaderId` 是否已有跟单配置，默认拒绝重复创建。
- [x] 2.9 `createTrialConfig` 复用 `CopyTradingService.createCopyTrading` 创建默认禁用的保守 `FIXED` 小额跟单配置。
- [x] 2.10 创建试跟配置成功后更新池子项为 `TRIAL` 并记录 `lastPromotedAt`；失败时保持原状态并返回错误。
- [x] 2.11 在 Leader 池服务中记录加入池子、状态变化、建议配置更新、试跟配置创建成功/失败的关键日志。
- [x] 2.12 在 `updatePlan` 和 `createTrialConfig` 中校验建议配置安全边界：固定金额大于 0、每日最大单数 1-100、每日最大亏损大于 0、价格区间在 0-1 且最小价不大于最大价、最大持仓大于 0。
- [x] 2.13 新增 Leader 池专用错误码和中英繁三套 i18n 文案，使用不冲突编号 `4251-4254` 与 `5451-5453`，覆盖池子不存在、已存在、重复试跟、立即启用未确认和服务失败。

## 3. 后端 API

- [x] 3.1 新增 `LeaderPoolController`，路径为 `/api/copy-trading/leader-pool`。
- [x] 3.2 实现 `POST /list`，返回池子列表和汇总概览。
- [x] 3.3 实现 `POST /add`，支持从已有 leader 加入池子。
- [x] 3.4 实现 `POST /update-status`，支持候选、观察、小额试跟、冷却、淘汰等状态更新。
- [x] 3.5 实现 `POST /update-plan`，支持更新建议配置字段。
- [x] 3.6 实现 `POST /create-trial-config`，支持创建保守小额试跟配置。
- [x] 3.7 实现 `POST /remove`，只移除池子项，不删除 leader 地址，不删除已有跟单配置。
- [x] 3.8 为重复加入、并发唯一约束冲突、leader 不存在、账户不存在、池子项不存在、立即启用未确认、重复试跟配置、创建配置失败等错误返回明确业务错误。

## 4. 前端 API 与类型

- [x] 4.1 在 `frontend/src/types/index.ts` 新增 Leader 池状态、池子项、汇总、请求和响应类型。
- [x] 4.2 在 `frontend/src/services/api.ts` 新增 `leaderPool` API 分组，覆盖 list、add、updateStatus、updatePlan、createTrialConfig、remove。
- [x] 4.3 确保前端类型包含跟单配置数量、是否有启用跟单、建议最大持仓和估算暴露字段。

## 5. 前端入口与页面

- [x] 5.1 新增 `frontend/src/pages/LeaderPool.tsx` 页面。
- [x] 5.2 在 `frontend/src/App.tsx` 添加受保护路由 `/leader-pool`。
- [x] 5.3 在 `frontend/src/components/Layout.tsx` 的 `跟单交易` 子菜单中加入 `Leader 池`，位置在 `跟单配置` 和 `Leader 管理` 之间。
- [x] 5.4 更新 `getInitialOpenKeys` 和路径变化逻辑，使 `/leader-pool` 自动展开 `跟单交易` 菜单。
- [x] 5.5 在 `frontend/src/locales/zh-CN/common.json`、`zh-TW/common.json`、`en/common.json` 增加 Leader 池菜单和页面基础文案。

## 6. Leader 池页面交互

- [x] 6.1 页面顶部展示池子人数、试跟中人数、估算最坏暴露和待处理风险统计卡片。
- [x] 6.2 页面列表展示 leader 名称或地址、状态、来源、建议固定金额、建议每日最大单数、建议每日最大亏损、跟单配置状态和最后复核时间。
- [x] 6.3 支持按状态筛选池子项，至少覆盖全部、候选、观察、小额试跟、冷却、淘汰。
- [x] 6.4 支持从页面中把已有 leader 加入池子，并处理空列表、重复加入和 leader 不存在错误。
- [x] 6.5 支持更新池子状态，冷却状态允许填写冷却截止时间。
- [x] 6.6 支持编辑建议配置，保存后只更新池子建议字段。
- [x] 6.7 支持打开 leader 的 Polymarket profile。
- [x] 6.8 支持从池子项创建小额试跟配置，提交前展示固定金额、每日最大单数、每日最大亏损、价格区间和最大持仓确认信息。
- [x] 6.9 创建试跟配置提交期间按钮进入 loading/disabled 状态，防止双击重复提交。
- [x] 6.10 创建试跟配置成功后刷新池子列表，并提供跳转到 `跟单配置` 的入口。
- [x] 6.11 创建试跟配置失败时展示后端错误，不在前端假更新状态。
- [x] 6.12 Leader 池列表不默认逐行查询 leader 余额，避免照搬 `Leader 管理` 的重型余额加载路径。

## 7. Leader 管理联动

- [x] 7.1 在 `frontend/src/pages/LeaderList.tsx` 操作列增加 `加入 Leader 池` 操作。
- [x] 7.2 点击加入后调用 Leader 池 add API，并对已加入池子的 leader 展示明确提示。
- [x] 7.3 加入成功后提示用户可以前往 `Leader 池` 继续设置观察或小额试跟。

## 8. 测试

- [x] 8.1 新增后端服务测试：成功加入池子、重复加入不创建重复项、leader 不存在返回错误。
- [x] 8.2 新增后端服务测试：并发/唯一约束重复加入被转换为明确“已在池子中”结果。
- [x] 8.3 新增后端服务测试：状态更新、冷却截止时间保存、淘汰不删除 leader 地址。
- [x] 8.4 新增后端服务测试：建议配置更新不修改已有 `copy_trading`。
- [x] 8.5 新增后端服务测试：无效建议配置被拒绝且不修改原池子项。
- [x] 8.6 新增后端服务测试：创建试跟配置使用保守默认值、默认禁用、成功后状态变为 `TRIAL`。
- [x] 8.7 新增后端服务测试：创建试跟配置失败时池子状态不变。
- [x] 8.8 新增后端服务测试：同账户同 leader 已有跟单配置时默认拒绝重复试跟创建。
- [x] 8.9 新增后端服务测试：立即启用但未确认时拒绝创建跟单配置。
- [x] 8.10 新增后端服务测试：池子列表使用批量查询路径，避免每个池子项逐条查 leader 和跟单配置。
- [x] 8.11 新增后端控制器测试或集成测试，覆盖 list、add、update-status、update-plan、create-trial-config、remove 的主要成功和失败路径。
- [x] 8.12 前端至少通过 TypeScript 构建验证，确保新增页面、类型、API 和菜单没有编译错误。
- [x] 8.13 手动或自动验证前端双击创建试跟配置不会发起重复提交。
- [x] 8.14 手动或自动验证 Leader 池列表不会默认逐行请求 leader 余额接口。

## 9. 文档与验证

- [x] 9.1 新增或更新中文文档，说明 `Leader 池` 与 `Leader 管理`、`跟单配置` 的区别。
- [x] 9.2 文档中明确 `Leader 池` 不会自动加仓、不自动启用大额跟单、不自动抓取 leaderboard。
- [x] 9.3 运行后端测试，至少覆盖 Leader 池相关测试。
- [x] 9.4 运行前端构建。
- [x] 9.5 运行前端 lint。
- [x] 9.6 手动验证页面入口、空态、加入池子、状态更新、建议配置更新和创建小额试跟配置流程。
