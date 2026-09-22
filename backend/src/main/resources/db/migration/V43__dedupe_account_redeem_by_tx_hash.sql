-- ============================================
-- V43: 账户链上卖出/赎回按交易哈希幂等（修复 issue #61）
-- ============================================
-- 背景：UnifiedOnChainWsService 对每一条链上 log 通知都会触发一次账户回调。
-- 同一笔链上 REDEEM 往往会产生多条 Transfer log，于是同一个交易哈希被处理多次，
-- 导致 sell_match_record 被重复写入、订单 matchedQuantity 被重复累加、已实现盈亏翻倍。
--
-- 本迁移新增 source_tx_hash 作为幂等键，并加唯一约束做最终防线。
-- 历史数据 source_tx_hash 为 NULL，MySQL 唯一索引允许多个 NULL，因此不影响存量数据。

ALTER TABLE sell_match_record
    ADD COLUMN source_tx_hash VARCHAR(100) NULL COMMENT '链上交易哈希（账户链上自动卖出/赎回幂等键）' AFTER leader_sell_trade_id,
    ADD INDEX idx_sell_match_record_tx_hash (source_tx_hash);

-- 同一跟单关系 + 同一笔链上交易 + 同一市场只允许一条记录
ALTER TABLE sell_match_record
    ADD UNIQUE KEY uk_sell_match_record_tx_market (copy_trading_id, source_tx_hash, market_id);
