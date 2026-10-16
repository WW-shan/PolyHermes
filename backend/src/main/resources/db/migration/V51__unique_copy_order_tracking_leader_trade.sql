-- ============================================
-- V51: 跟单买入跟踪表按 (copy_trading_id, leader_buy_trade_id) 唯一，补齐多实例去重
--
-- 背景：processed_trade 的 uk_leader_trade 只能保证“同一笔 Leader 成交最多被标记一次”，
--       而该标记写入发生在下单之后。多实例部署时，两个实例可能同时通过 processed_trade
--       的读检查并各自下单一次，唯一键只能拦住后写入的那条标记，拦不住重复下单。
--       copy_order_tracking 的 PENDING 记录在下单之前写入，为它补唯一键即可把并发
--       重复下单挡在数据库层。
--
-- 安全性：本迁移不删除任何历史数据（删除跟踪记录会破坏卖出核销关系）。
--         若检测到历史重复数据则跳过加键，由应用层 existsByCopyTradingIdAndLeaderBuyTradeId
--         检查兜底，迁移本身不会失败。
-- ============================================

-- 历史重复数据检测（同一配置 + 同一笔 Leader 买入成交出现多条跟踪记录）
SET @dup_count = (SELECT COUNT(*) FROM (
    SELECT copy_trading_id, leader_buy_trade_id
    FROM copy_order_tracking
    GROUP BY copy_trading_id, leader_buy_trade_id
    HAVING COUNT(*) > 1
) dup);

-- 唯一键是否已存在
SET @uk_exists = (SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                  WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'copy_order_tracking'
                  AND CONSTRAINT_TYPE = 'UNIQUE'
                  AND CONSTRAINT_NAME = 'uk_copy_order_tracking_ct_leader_trade'
                  LIMIT 1);

SET @sql = IF(@uk_exists IS NULL AND @dup_count = 0,
              'ALTER TABLE copy_order_tracking ADD UNIQUE KEY uk_copy_order_tracking_ct_leader_trade (copy_trading_id, leader_buy_trade_id)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
