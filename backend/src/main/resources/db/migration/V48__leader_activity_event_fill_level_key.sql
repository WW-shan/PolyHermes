-- 研究 activity 去重键改为按成交粒度：同一 tx 可能包含同一钱包的多笔成交（不同 asset/side/size/price），
-- 原 (source, source_event_id) 唯一键只含 txHash，会把同一 tx 的后续成交全部丢弃。
-- stable_event_key 改由应用层按 hash(wallet, tx, asset, side, size, price) 生成，唯一约束仍由 uk_leader_activity_event_stable_key 保证。
ALTER TABLE leader_activity_event DROP INDEX uk_leader_activity_event_source_event;
ALTER TABLE leader_activity_event ADD INDEX idx_leader_activity_event_source_event (source, source_event_id);
