## 1. 后端数据模型与迁移

- [x] 1.1 新增 Flyway 迁移，创建 `leader_research_run` 表，记录运行状态、时间、耗时、来源统计、候选统计、错误类型、错误信息和 partial failure 标记。
- [x] 1.2 新增 Flyway 迁移，创建 `leader_research_candidate` 表，记录钱包地址、关联 leaderId、研究状态、来源、score、reason、risk flags、锁定字段和时间戳。
- [x] 1.3 新增 Flyway 迁移，创建 `leader_research_score` 表，记录 score version、总分和所有 copyability 分项。
- [x] 1.4 新增 Flyway 迁移，创建 `leader_research_event` 表，记录研究事件类型、candidateId、runId、原因、payload 摘要、通知状态和时间戳。
- [x] 1.5 新增 Flyway 迁移，创建 `leader_research_source_state` 表或等价字段，记录每个来源的最近成功、最近失败、错误类型、错误信息和 stale 状态。
- [x] 1.6 新增 Flyway 迁移，创建 `leader_activity_event` append-only 表，若当前 app 未持久化 raw activity event，则用于纸跟重放和去重。
- [x] 1.7 新增 Flyway 迁移，创建 `leader_paper_session` 表，记录 candidateId、状态、开始/结束时间、统计摘要和 PnL 摘要。
- [x] 1.8 新增 Flyway 迁移，创建 `leader_paper_trade` 表，记录 leader trade、模拟成交、filter reason、fill assumption、quote confidence 和 valuation status。
- [x] 1.9 新增 Flyway 迁移，创建 `leader_paper_position` 表，记录 session、market、outcome、数量、成本、当前估值、realized/unrealized PnL 和 valuation status。
- [x] 1.10 为 activity event、candidate、paper trade、paper position、run、event 和 source state 添加唯一键与查询索引，覆盖去重、分页、状态筛选和候选详情查询。
- [x] 1.11 新增 JPA entity 与 repository，覆盖所有 research、activity、paper 相关表。
- [x] 1.12 新增 research 状态、paper session 状态、valuation status、quote confidence、fill assumption、event type 和 source type 枚举。
- [x] 1.13 `leader_activity_event` 必须包含 `normalized_wallet`、`source_event_id`、`stable_event_key`、`event_time`、`raw_payload_hash`、`payload_summary`、`usable_for_discovery`、`unusable_reason`、`paper_processing_status`、`processing_attempts`、`paper_processing_started_at`、`paper_processed_at` 和 `last_processing_error`。
- [x] 1.14 `leader_research_candidate` 必须记录 agent ownership/provenance，区分 agent 创建候选、用户已有 leader、用户锁定候选和人工池子项，避免自动退休或覆盖用户资产。
- [x] 1.15 `leader_research_run` 必须支持单实例运行锁或等价约束，保证同一时间最多一个 research run 推进候选状态。
- [x] 1.16 为 `leader_activity_event(paper_processing_status, event_time)`、`leader_activity_event(normalized_wallet, event_time)`、`leader_paper_trade(session_id, leader_trade_id)`、`leader_research_candidate(research_state, last_source_seen_at)` 添加组合索引。
- [x] 1.17 为 paper trade 增加数据库唯一约束，至少覆盖 `session_id + leader_trade_id + side`，用数据库兜底防止重复模拟成交。
- [x] 1.18 所有新增表必须有可回滚的空表迁移策略；迁移不得修改现有真实订单热表语义。

## 2. Activity 事件持久化

- [x] 2.1 新增 `LeaderActivityIngestionService`，负责 research 专用 activity event 标准化、去重、持久化和不可用原因记录。
- [x] 2.2 保持现有 `PolymarketActivityWsService` 的真实跟单快路径不被 research 拖慢；不得依赖它当前的“已监听 leader 地址预过滤”来发现未知 leader。
- [x] 2.3 新增 bounded Data API backfill 来源，按 watchlist、已有 Leader 管理记录和 active research candidates 拉取 `/activity`，用于补齐历史 paper event。
- [x] 2.4 新增 research global activity capture 扩展点，解析全局 WS activity 时必须在地址过滤前标准化事件；该能力默认关闭，并受配置开关、每分钟写入上限、payload 截断和保留策略保护。
- [x] 2.5 如果 global capture 未启用，activity-derived source 必须在 source health 中明确显示 disabled/degraded，不得假装已经自动发现未知 leader。
- [x] 2.6 为 activity event 实现 source + eventId 幂等保存；缺失 eventId 时用 wallet、conditionId、asset、side、price、size、timestamp、transactionHash 的稳定 hash 生成 fallback key。
- [x] 2.7 对缺少钱包地址、市场、方向、价格或数量的 event 仍写入摘要，并标记不可用于 discovery/paper trading 的原因。
- [x] 2.8 确保 activity event 持久化失败不会阻断现有真实跟单处理链路；失败只写 research run/source error。
- [x] 2.9 新增 activity source health 更新，区分 Data API success empty、Data API failure、WS capture disabled、WS parse failure、write capped 和 stale。
- [x] 2.10 新增 activity event repository 查询方法，支持按钱包、时间窗口、未处理状态、来源、可发现状态和 stable key 查询。
- [x] 2.11 新增 activity ingestion 测试，覆盖正常保存、重复事件、缺失字段、fallback key、持久化失败隔离、write cap、WS capture disabled 和 Data API success empty。
- [x] 2.12 新增回归测试，证明 current copy-trading WS 的已知 leader 过滤不会阻断 research ingestion 对未知钱包的发现路径。

## 3. 候选来源与研究运行

- [x] 3.1 新增 `LeaderResearchJobService`，支持手动 runOnce 和定时运行，默认通过配置关闭自动调度。
- [x] 3.2 为研究任务实现 overlap guard，当前一 run 未结束时跳过或拒绝新 run，并写入 research event。
- [x] 3.3 新增 `LeaderResearchSourceService`，统一 watchlist、已有 Leader 管理记录和 activity-derived candidates；每个 source 返回 typed result，包含 candidates、source status、error class、error message 和 freshness。
- [x] 3.4 新增 watchlist 配置读取路径，支持配置钱包地址、备注和来源标签。
- [x] 3.5 实现 existing leaders 来源，从 `copy_trading_leaders` 创建或更新研究候选。
- [x] 3.6 实现 activity-derived 来源，从 `leader_activity_event` 中发现 fresh、usable、可归因钱包；如果 global capture 未启用，只能基于已持久化事件工作，并必须展示 source limitation。
- [x] 3.7 候选创建必须复用 `LeaderRepository` 的唯一地址语义；新 leader address 必须标准化小写并验证 42 位 EVM 地址格式。
- [x] 3.8 每个来源运行后更新 source state，区分 success with zero candidates、failure、stale、disabled、degraded 和 partial failure。
- [x] 3.9 公开 leaderboard 来源只保留接口/扩展点，不作为第一阶段启用来源；不得在实现中偷偷依赖未确认 endpoint。
- [x] 3.10 候选合并必须保留所有来源 evidence，不得因为同一钱包重复出现而覆盖更早来源、用户备注或锁定状态。
- [x] 3.11 新增 run-level dry-run 或 preview 能力，允许查看本次将新增/更新/冷却多少候选而不推进状态。
- [x] 3.12 新增候选来源测试，覆盖空结果、失败结果、disabled source、重复候选、无效地址、已有 leader 复用和用户锁定保护。
- [x] 3.13 新增研究运行测试，覆盖成功、partial failure、overlap guard、run record 完整性、source limitation 和 preview 不产生状态副作用。
- [x] 3.14 新增生产失败场景测试：某个 source 超时或抛异常时，其他 source 仍能产出候选，且不会删除或降级已有候选。

## 4. 纸跟账本与模拟成交

- [x] 4.1 新增 `LeaderPaperTradingService`，根据候选和 activity event 启动或更新 paper session。
- [x] 4.2 `LeaderPaperTradingService` 必须按 batch claim `leader_activity_event`，通过状态从 `NEW`/`RETRYABLE` 原子更新到 `PROCESSING` 后再处理，避免并发 run 重复模拟成交。
- [x] 4.3 对进入 `PAPER` 状态的候选，处理 BUY event 并记录 paper trade 与 paper position。
- [x] 4.4 对进入 `PAPER` 状态的候选，处理 SELL event 并按持仓匹配规则更新 paper position 和 realized PnL。
- [x] 4.5 将现有 `CopyTradingFilterService` 抽出或复用为 paper-safe evaluator；paper 场景不得调用真实账户持仓检查来决定模拟持仓，必须使用 paper position provider。
- [x] 4.6 为 research 定义保守 paper config，包含 fixed amount、max daily loss、max daily orders、min/max price、max position value、support sell 和 market end guard。
- [x] 4.7 每笔 paper trade 保存 leader price、leader size、simulated price、simulated size、simulated amount、fill assumption、quote confidence、quote source、quote timestamp、filter result 和 filter reason。
- [x] 4.8 对 quote/valuation 不可用的持仓标记 `UNAVAILABLE` 或 `UNKNOWN`，并确保不计为 confirmed zero。
- [x] 4.9 `copyable PnL` 必须区分 realized PnL、available unrealized PnL、unknown valuation exposure 和 confirmed zero exposure；unknown/unavailable 不得伪装成亏到 0。
- [x] 4.10 为 paper trade 实现 leaderTradeId 幂等去重，重复 activity event 不得重复产生纸跟。
- [x] 4.11 处理失败必须增加 `processing_attempts`、记录 `last_processing_error`，超过阈值进入 `FAILED` 并写 research event，不得无限重试卡住整轮 run。
- [x] 4.12 新增 paper session 汇总计算，包含交易数、过滤数、open exposure、realized/unrealized/copyable PnL、max drawdown、unknown valuation exposure、confirmed zero exposure 和 filtered ratio。
- [x] 4.13 新增纸跟服务测试，覆盖 BUY、SELL、过滤、重复 event、unknown valuation、confirmed zero、PnL 计算、max drawdown、event claim 并发、失败重试和 FAILED 隔离。

## 5. Copyability 评分与研究状态机

- [x] 5.1 新增 `LeaderResearchScoringService`，计算并保存所有 copyability 分项和总分。
- [x] 5.2 定义 `score_version = research-copyability-v1`，写明每个分项的 0-100 取值范围、权重、缺数据策略和总分计算公式。
- [x] 5.3 v1 默认权重建议：profit signal 20、repeatability 15、liquidity fit 10、entry price fit 10、slippage risk 10、drawdown risk 10、holding period fit 5、market type risk 5、exit liquidity risk 5、data freshness 5、filter pass rate 5。
- [x] 5.4 实现收益信号封顶逻辑，避免单次大赢主导评分；样本量不足时总分必须被 cap，且不能进入 `TRIAL_READY`。
- [x] 5.5 实现 repeatability、liquidity fit、entry price fit、slippage risk、holding period fit、market type risk、drawdown risk、exit liquidity risk、data freshness 和 filter pass rate 分项。
- [x] 5.6 对 quote unknown、valuation unavailable、source stale、filtered ratio 过高和 critical risk flag 定义明确扣分或晋升阻断规则。
- [x] 5.7 新增 `LeaderResearchStateMachine`，实现 `DISCOVERED -> CANDIDATE -> PAPER -> TRIAL_READY -> COOLDOWN/RETIRED` 迁移。
- [x] 5.8 状态迁移必须在事务中保存 rule version、oldState、newState、trigger reason、runId 和 research event。
- [x] 5.9 实现 `CANDIDATE -> PAPER` 默认阈值：score >= 60、未锁定、未退休、source 48 小时内新鲜。
- [x] 5.10 实现 `PAPER -> TRIAL_READY` 默认阈值：纸跟 >= 7 天、交易 >= 10、copyable PnL > 0、最大回撤不低于 -15%、UNKNOWN quote 暴露 <= 20%、过滤比例 < 50%、无 critical risk flag。
- [x] 5.11 实现 `PAPER -> COOLDOWN` 默认阈值：最大回撤 < -20%、10 笔后 copyable PnL < -5、quote/source stale > 72 小时或 thin liquidity exit risk。
- [x] 5.12 实现 cooldown 恢复和退休规则。
- [x] 5.13 实现 locked 候选保护，自动任务不得改变锁定候选的状态、试跟建议、建议配置或冷却/退休状态。
- [x] 5.14 新增评分和状态机测试，覆盖全部迁移、边界阈值、锁定保护、source stale、rule version、缺数据 cap、单次大赢 cap 和 critical risk 晋升阻断。

## 6. Leader 池联动与真钱安全边界

- [x] 6.1 新增 research 与 `copy_trading_leader_pool` 的映射服务，确保研究状态与 Leader 池状态分离。
- [x] 6.2 当研究候选进入 `CANDIDATE` 或更高状态且需要展示时，保守创建或更新 Leader 池项。
- [x] 6.3 `TRIAL_READY` 只能在 Leader 池展示“建议小额试跟” badge，不得自动把 Leader 池状态改为 `TRIAL` 或 `ACTIVE`。
- [x] 6.4 自动任务不得增加 existing pool 的 suggested fixed amount、suggested max daily loss 或 suggested max daily orders。
- [x] 6.5 自动任务不得覆盖用户手工 notes；研究摘要必须写入独立字段或 append-only event。
- [x] 6.6 新增 research 专用手动审批服务，从 `TRIAL_READY` 候选创建禁用试跟配置；不得原样复用当前 `LeaderPoolService.createTrialConfig`，除非先重构出 disabled-only 安全 helper。
- [x] 6.7 research approval request DTO 不允许暴露 `enabled`、`enableImmediately` 或放宽风控字段；后端组装 `CopyTradingCreateRequest` 时必须强制 `enabled=false`。
- [x] 6.8 如果客户端篡改提交 `enabled=true`、更大 fixed amount、更高 max daily loss、更多 max daily orders 或更宽 price range，后端必须拒绝或忽略，并写入 safety research event。
- [x] 6.9 创建禁用试跟配置前检查同账户同 leader 是否已有跟单配置，默认拒绝重复创建；双击提交必须只创建一条配置。
- [x] 6.10 用户明确创建 disabled trial config 成功后，Leader 池才可以从 WATCH/建议状态进入 `TRIAL`；仅 `TRIAL_READY` recommendation 不得进入 `TRIAL`。
- [x] 6.11 创建禁用试跟配置成功后写入 research event，并提供跳转 `跟单配置` 的信息。
- [x] 6.12 新增安全测试，覆盖客户端篡改 `enabled=true`、高 fixed amount、放宽风控、locked 候选、重复配置、双击创建、TRIAL_READY 不自动 TRIAL 和 disabled config 创建后 copy_trading.enabled 仍为 false。

## 7. 后端 API 与错误处理

- [x] 7.1 新增 `LeaderResearchController`，提供研究运行、候选列表、候选详情、paper session、source health、research events 和手动创建禁用试跟配置 API。
- [x] 7.2 所有 research API 必须走现有受保护路由与鉴权边界。
- [x] 7.3 新增 research DTO，覆盖 run summary、candidate summary/detail、score components、paper session、paper trade、paper position、source health、event 和 approval response。
- [x] 7.4 新增 research 错误码和中英繁 i18n 文案，覆盖 run overlap、candidate not found、candidate locked、not trial ready、source unavailable、paper valuation unavailable、real money activation forbidden。
- [x] 7.5 服务内部使用命名错误或明确 result 类型，不得只用 catch-all Exception 表达业务失败。
- [x] 7.6 所有源失败、解析失败、状态迁移失败、纸跟失败和审批失败必须写入 research event 或 run error。
- [x] 7.7 新增 controller 测试，覆盖所有 API 的成功路径、参数错误、权限/保护边界、业务错误和真钱启用拒绝。
- [x] 7.8 手动 run 和创建禁用试跟配置 API 必须有重复提交保护；重复请求返回已有 run/config 或明确拒绝，不得产生重复副作用。
- [x] 7.9 候选详情 API 必须分页返回 paper trades 和 research events，不得一次性返回完整历史。
- [x] 7.10 API 错误响应必须让前端能区分 `disabled source`、`degraded source`、`valuation unknown`、`candidate locked` 和 `real money activation forbidden`。

## 8. 前端类型、API 与操作台

- [x] 8.1 在 `frontend/src/types` 中新增 Leader Research 类型，覆盖状态、source health、score components、paper session/trade/position、events 和 approval request/response。
- [x] 8.2 在 `frontend/src/services/api.ts` 新增 `leaderResearch` API 分组。
- [x] 8.3 新增 `LeaderResearch` 页面和受保护路由。
- [x] 8.4 在左侧菜单合适位置新增 `Leader Research` 或 `Leader 雷达` 入口，避免和现有 `Leader 池` 混淆。
- [x] 8.5 操作台顶部展示 Today summary：trial-ready 数、新候选数、新纸跟数、冷却数、source stale 数。
- [x] 8.6 实现 Run Status 模块，展示最近运行、耗时、状态、来源统计和失败信息。
- [x] 8.7 实现 Pending Decisions 模块，展示 `TRIAL_READY` 候选和手动创建禁用试跟配置入口。
- [x] 8.8 实现 Candidate Detail，展示来源历史、score components、paper session summary、risk flags、research events 和 Leader 池映射状态。
- [x] 8.9 实现 Paper Sessions/Trades 展示，明确展示 valuation status、quote confidence 和 filtered reason。
- [x] 8.10 实现 Source Health 模块，区分 success empty、failure、stale 和 degraded。
- [x] 8.11 创建禁用试跟配置前，UI 必须展示 fixed amount、max daily loss、max daily orders、price range 和 max position value，并要求确认。
- [x] 8.12 UI 不得把 `TRIAL_READY` 文案表现成已经真实试跟；必须明确“建议小额试跟，待你确认”。
- [x] 8.13 新增 zh-CN、zh-TW、en 文案。
- [x] 8.14 确保移动端操作台至少可查看 summary、pending decisions 和 candidate detail，不出现不可操作表格。
- [x] 8.15 UI 必须展示 activity source limitation：global capture disabled、Data API backfill stale、source degraded 等状态，避免用户误以为系统已经全网自动发现。
- [x] 8.16 创建禁用试跟配置按钮必须防双击，提交中禁用，并在重复配置错误时跳转或提示已有配置。
- [x] 8.17 Candidate Detail 必须把 `UNKNOWN`、`UNAVAILABLE`、`NO_MATCH`、`CONFIRMED_ZERO` 用不同文案和颜色展示，不能都显示成 0。
- [x] 8.18 Leader 池或 Leader Research 的 `TRIAL_READY` badge 必须和真实 `TRIAL` 状态视觉区分，避免用户误以为真钱已开始。

## 9. 通知与内部事件

- [x] 9.1 新增 research event 查询与标记通知状态能力。
- [x] 9.2 第一阶段在操作台展示内部事件，不强依赖外部通知渠道。
- [x] 9.3 新增通知摘要生成服务，基于 research event 聚合新增候选、纸跟达标、建议试跟、冷却、source failure 和 stale data。
- [x] 9.4 外部通知发送失败时必须保留 research event，并记录通知失败原因。
- [x] 9.5 如果接入现有 Telegram 通知，必须避免硬编码新渠道 secret，并复用现有通知配置读取路径。
- [x] 9.6 新增通知测试，覆盖事件生成、摘要生成、发送失败和事件保留。
- [x] 9.7 通知摘要必须包含 safety-relevant 事件：source disabled/degraded、valuation unknown、approval rejected、duplicate approval 和 real money activation forbidden。
- [x] 9.8 通知事件必须可去重，避免同一候选每轮 research run 重复推送相同 `TRIAL_READY` 或 source failure。

## 10. 性能、索引与数据保留

- [x] 10.1 候选列表、paper trade 列表、research event 列表必须分页。
- [x] 10.2 研究任务每次运行只处理 active candidates 和新 activity events，不得全量重算全部历史。
- [x] 10.3 为所有详情查询路径增加 repository 查询或聚合，避免 N+1 查询。
- [x] 10.4 增加 paper/activity 数据保留策略配置，默认保留足够研究窗口但避免无限增长。
- [x] 10.5 新增性能相关测试或验证脚本，至少覆盖 100 个候选、1 万条 activity event、1 万条 paper trade 的列表和一次研究运行。
- [x] 10.6 research run 必须使用 cursor/checkpoint 记录上次处理到的 event time 或 stable key；失败重跑时只回看有限窗口。
- [x] 10.7 paper processing 每批必须有 batch size 上限、运行时间上限和写入上限，避免一次 run 长时间占用数据库。
- [x] 10.8 global activity capture 如果启用，必须有每分钟写入上限、payload 最大长度、失败熔断和可观测计数。
- [x] 10.9 候选详情必须用聚合 DTO 或批量查询组装 score、paper summary、source health、pool mapping，禁止对列表中每个 candidate 单独查多张表。

## 11. 文档与运维

- [x] 11.1 新增中文文档，说明 Leader Research Agent、Leader 池、Leader 管理和跟单配置的区别。
- [x] 11.2 文档明确：研究代理可以自动纸跟和推荐，但绝不会自动启用真钱跟单。
- [x] 11.3 文档说明研究状态和 Leader 池状态映射，特别是 `TRIAL_READY` 不等于 `TRIAL`。
- [x] 11.4 新增运维说明，覆盖开启/关闭 research job、查看 source health、排查 unknown valuation、排查重复 event 和回滚。
- [x] 11.5 更新相关 README 或 docs 索引，指向 Leader Research 文档。
- [x] 11.6 文档明确 activity-derived discovery 的真实覆盖范围：watchlist/existing leader Data API backfill、global capture 是否启用、public leaderboard 尚未接入。
- [x] 11.7 运维说明必须包含 kill switch：关闭 scheduled research、关闭 global activity capture、隐藏 approval endpoint 或前端入口。

## 12. 验证

- [x] 12.1 运行后端 research 相关单元测试和 controller 测试。
- [x] 12.2 运行已有 Leader 池、跟单配置、PnL 统计相关测试，确保未破坏现有安全边界。
- [x] 12.3 运行前端 TypeScript 构建。
- [x] 12.4 运行前端 lint。
- [x] 12.5 手动验证 research job 默认关闭、手动 run、source health、候选列表、候选详情、纸跟详情和 pending decisions。
- [x] 12.6 手动验证创建禁用试跟配置后 `copy_trading.enabled=false`，且不会自动启用。
- [x] 12.7 手动验证 source failure 不会删除候选、不会降级 locked 候选、不会把 unknown valuation 展示成 confirmed zero。
- [x] 12.8 在本地或测试环境验证重复 activity event 不会产生重复 paper trade。
- [x] 12.9 运行 `./gradlew test --tests "*LeaderResearch*"`，覆盖 research service、state machine、paper trading、approval safety 和 controller。
- [x] 12.10 运行 `./gradlew test --tests "*LeaderPool*" --tests "*CopyTrading*" --tests "*Pnl*"`，确保现有 Leader 池、跟单配置和 PnL 语义未回退。
- [x] 12.11 使用 gstack/browser 或 Playwright 进行前端安全 QA，覆盖 `TRIAL_READY` 文案、disabled approval modal、双击提交、source degraded、valuation unknown 和移动端关键路径。
- [x] 12.12 使用 `/qa` 时优先加载 `/Users/codyhhchen/.gstack/projects/WrBug-PolyHermes/codyhhchen-feat-real-pnl-statistics-eng-review-test-plan-20260504-210925.md` 作为主测试输入。
- [x] 12.13 验证当前 `PolymarketActivityWsService` 的已知 leader 过滤不会阻断 research ingestion 的未知钱包发现路径。
- [x] 12.14 验证 research job kill switch 生效：关闭后不再推进状态、不再处理 paper events，但页面仍可读历史数据。

## 13. 一次性实施顺序与合并策略

- [x] 13.1 第一批实现数据 spine：迁移、entity、repository、enum、source state、run record、activity event 和 processing status。
- [x] 13.2 第二批实现 ingestion 与 source：Data API backfill、optional global capture、watchlist、existing leaders、activity-derived candidates 和 source health。
- [x] 13.3 第三批实现 paper trading：event claim、paper config、BUY/SELL、filter evaluator、valuation、PnL、drawdown 和重试隔离。
- [x] 13.4 第四批实现 scoring/state machine：score v1 公式、状态迁移、locked protection、cooldown/retire 和 research events。
- [x] 13.5 第五批实现 Leader 池联动和 disabled approval safety：pool mapping、recommendation badge、disabled-only config creation、duplicate protection 和 safety tests。
- [x] 13.6 第六批实现 API 和前端操作台：run status、candidate list/detail、paper sessions、source health、pending decisions、approval modal 和 i18n。
- [x] 13.7 第七批实现通知、运维文档、kill switch、性能脚本和全量 QA。
- [x] 13.8 每一批都必须先补对应测试再合并到下一批；不得把 safety tests 延后到最后。
- [x] 13.9 如果并行工作，数据 spine 是共享依赖；paper trading、frontend shell、notification summary 可以并行，但 approval safety 必须等 DTO/服务边界稳定后再接。

## 14. Review 整改任务

- [x] 14.1 修复 locked candidate 安全边界：`LeaderResearchStateMachine.advance()` 对锁定候选必须完全跳过状态、score、badge、summary、Leader 池映射和建议配置写入，不得调用会产生副作用的 `syncCandidate`；补充 locked candidate 不变更 pool/candidate/poolId 的回归测试。
- [x] 14.2 修复 `DISCOVERED` 候选污染 Leader 池：状态机或 pool mapping 只能在候选达到 `CANDIDATE` 或更高状态时创建/更新 `copy_trading_leader_pool`，原始 `DISCOVERED` 钱包不得出现在 Leader 池；补充 `DISCOVERED` 不建池、`CANDIDATE+` 才建池的测试。
- [x] 14.3 修复创建禁用试跟配置的并发重复问题：为同账户同 leader 恢复数据库级唯一保护或引入等价事务锁/幂等键，确保双击、并发请求和重试最多创建一条 copy-trading config；补充并发审批测试和迁移回归测试。
- [x] 14.4 修复 Data API backfill 失败被误报为 source success：`backfillWalletActivities` 失败必须向 `captureSource` 返回 typed failure/partial failure，source health 和 research run 必须显示 failure/degraded，而不是 SUCCESS；补充 Data API failure、success empty、partial failure 的 source health 测试。
- [x] 14.5 修复前端 approval preview 硬编码风险参数：确认弹窗必须展示后端返回或候选池建议的 `fixedAmount`、`maxDailyLoss`、`maxDailyOrders`、`minPrice`、`maxPrice`、`maxPositionValue`，不得写死 1 USDC/5 USDC/10 orders/0.10-0.80；补充 UI 数据映射测试或浏览器 QA。
- [x] 14.6 修复 candidate 搜索只搜当前页：后端搜索必须在数据库查询层按 wallet/address/source/status 等条件过滤并返回正确 total，不得先分页再内存过滤；补充分页外命中、空结果和 total 计数测试。
- [x] 14.7 修复 candidate list N+1 查询：列表 DTO 组装必须批量加载 leader、pool mapping、score/paper summary 等依赖，禁止每个 candidate 单独查多张表；补充 50+ 候选列表查询数量或性能回归测试。
- [x] 14.8 补齐 Review 发现对应的验证任务：完成后必须运行前端 build/lint、后端 `LeaderResearch` 相关测试、Leader 池/跟单配置/PnL 回归测试，并在本机没有 Java Runtime 时先安装或切换到可运行 Java 的环境再执行后端测试。
- [x] 14.9 完成未勾选的安全和 QA 任务后再关闭 change：至少覆盖 activity source health、候选来源、研究运行、纸跟、评分状态机、approval safety、controller、通知、数据保留、手动 QA 和浏览器 QA；不得只修 review 代码而保留关键验证项未完成。
