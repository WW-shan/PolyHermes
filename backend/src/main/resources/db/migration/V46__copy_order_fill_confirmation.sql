-- 跟单下单成交确认与并发控制
-- 1. copy_order_tracking 增加乐观锁版本号与下单请求数量：
--    status = 'pending' 表示已下单但成交未确认（quantity/remaining_quantity 为 0，不计入可卖数量），
--    requested_quantity 记录请求数量，用于待确认期间的仓位额度预占。
ALTER TABLE copy_order_tracking
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    ADD COLUMN requested_quantity DECIMAL(20, 8) NULL COMMENT '下单请求数量（待确认订单用于额度预占）',
    ADD INDEX idx_copy_order_tracking_status_created (status, created_at),
    ADD INDEX idx_copy_order_tracking_ct_leader_trade (copy_trading_id, leader_buy_trade_id);

-- 2. sell_match_record 增加成交确认状态与成交价查询退避字段：
--    fill_status = 'PENDING' 表示卖单已下但成交未确认（已预占 tracking 数量），'FILLED' 表示已按实际成交量核销。
ALTER TABLE sell_match_record
    ADD COLUMN fill_status VARCHAR(20) NOT NULL DEFAULT 'FILLED' COMMENT '成交状态：PENDING=待确认，FILLED=已确认，UNCONFIRMED=无法确认',
    ADD COLUMN price_query_attempts INT NOT NULL DEFAULT 0 COMMENT '成交价查询失败次数',
    ADD COLUMN last_price_query_at BIGINT NULL COMMENT '最近一次成交价查询时间（毫秒）',
    ADD INDEX idx_sell_match_record_fill_status (fill_status, created_at),
    ADD INDEX idx_sell_match_record_price_pending (price_updated, fill_status);
