-- ============================================
-- V49: 加密价差策略触发记录幂等与结算口径
-- 1. (strategy_id, period_start_unix) 唯一：下单前先插入 pending 记录占位，冲突即视为本周期已触发
-- 2. transaction_hashes：下单响应中的成交交易哈希，结算时按哈希精确聚合成交
-- 3. settlement_source：结算数据来源（TX_HASH=按交易哈希精确, TIME_WINDOW=按时间窗聚合可能不精确, ESTIMATED=activity 长期不可用时的估算）
-- ============================================

-- 历史重复数据：每个 (strategy_id, period_start_unix) 只保留最早一条（id 最小），其余删除；可重复执行
DELETE t1 FROM crypto_tail_strategy_trigger t1
    JOIN crypto_tail_strategy_trigger t2
      ON t1.strategy_id = t2.strategy_id
     AND t1.period_start_unix = t2.period_start_unix
     AND t1.id > t2.id;

-- 唯一约束（若已存在则跳过）
SET @uk_exists = (SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                  WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'crypto_tail_strategy_trigger'
                  AND CONSTRAINT_TYPE = 'UNIQUE'
                  AND CONSTRAINT_NAME = 'uk_crypto_tail_trigger_strategy_period'
                  LIMIT 1);
SET @sql = IF(@uk_exists IS NULL,
              'ALTER TABLE crypto_tail_strategy_trigger ADD UNIQUE KEY uk_crypto_tail_trigger_strategy_period (strategy_id, period_start_unix)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE crypto_tail_strategy_trigger
    ADD COLUMN transaction_hashes VARCHAR(2000) DEFAULT NULL COMMENT '下单响应的成交交易哈希（逗号分隔），结算时按哈希过滤成交' AFTER order_id,
    ADD COLUMN settlement_source VARCHAR(20) DEFAULT NULL COMMENT '结算数据来源: TX_HASH, TIME_WINDOW, ESTIMATED' AFTER settled_at;
