-- ============================================================
-- 诊断 issue #61：账户链上自动卖出/赎回记录是否被重复写入
-- 只读脚本，不做任何修改，可安全执行。
-- 仅用于旧版本升级排查；全新部署、没有历史 AUTO_WS_ 记录时无需执行。
-- 建议在修复版本部署前先跑一遍，确认受影响的跟单关系。
-- ============================================================

-- 1) 重复的账户链上自动卖出记录
--    判定条件：同一跟单关系 + 同一市场 + 同一 outcome + 数量/价格/盈亏完全相同，
--    且这些行在 10 秒内产生。加入时间窗是为了避免把用户两次正常卖出误判为重复。
SELECT
    copy_trading_id,
    market_id,
    outcome_index,
    total_matched_quantity,
    sell_price,
    total_realized_pnl,
    COUNT(*)                      AS duplicate_rows,
    GROUP_CONCAT(id ORDER BY id)  AS record_ids,
    GROUP_CONCAT(DATE_FORMAT(FROM_UNIXTIME(created_at / 1000), '%Y-%m-%d %H:%i:%s.%f') ORDER BY id) AS created_ats,
    MAX(created_at) - MIN(created_at) AS created_at_span_ms
FROM sell_match_record
WHERE sell_order_id LIKE 'AUTO_WS_%'
GROUP BY
    copy_trading_id,
    market_id,
    outcome_index,
    total_matched_quantity,
    sell_price,
    total_realized_pnl
HAVING COUNT(*) > 1
   AND MAX(created_at) - MIN(created_at) <= 10000
ORDER BY duplicate_rows DESC, copy_trading_id, market_id, outcome_index;


-- 2) 受影响跟单关系的「重复计算」总额（多算的已实现盈亏）
SELECT
    copy_trading_id,
    COUNT(*)                                       AS affected_market_groups,
    SUM((duplicate_rows - 1) * total_realized_pnl)  AS overstated_realized_pnl,
    SUM((duplicate_rows - 1) * total_matched_quantity) AS overstated_matched_quantity
FROM (
    SELECT
        copy_trading_id, market_id, outcome_index, total_realized_pnl, total_matched_quantity,
        COUNT(*) AS duplicate_rows
    FROM sell_match_record
    WHERE sell_order_id LIKE 'AUTO_WS_%'
    GROUP BY copy_trading_id, market_id, outcome_index, total_matched_quantity, sell_price, total_realized_pnl
    HAVING COUNT(*) > 1
       AND MAX(created_at) - MIN(created_at) <= 10000
) dup
GROUP BY copy_trading_id
ORDER BY overstated_realized_pnl DESC;


-- 3) 抽样查看明细（含每条记录关联的持仓回滚信息，供人工核对）
SELECT
    s.id                AS sell_record_id,
    s.copy_trading_id,
    s.market_id,
    s.total_matched_quantity,
    s.total_realized_pnl,
    DATE_FORMAT(FROM_UNIXTIME(s.created_at / 1000), '%Y-%m-%d %H:%i:%s') AS created_at,
    d.id                AS detail_id,
    d.tracking_id,
    d.matched_quantity  AS detail_matched_qty
FROM sell_match_record s
LEFT JOIN sell_match_detail d ON d.match_record_id = s.id
WHERE s.sell_order_id LIKE 'AUTO_WS_%'
  AND s.copy_trading_id IN (
      SELECT copy_trading_id FROM (
          SELECT copy_trading_id
          FROM sell_match_record
          WHERE sell_order_id LIKE 'AUTO_WS_%'
          GROUP BY copy_trading_id, market_id, outcome_index, total_matched_quantity, sell_price, total_realized_pnl
          HAVING COUNT(*) > 1
             AND MAX(created_at) - MIN(created_at) <= 10000
      ) x
  )
ORDER BY s.copy_trading_id, s.market_id, s.id;


-- 4) 历史 AUTO_WS 记录缺少 source_tx_hash 的数量
--    修复脚本会从 leader_sell_trade_id 的 AUTO_WS_<txHash> 回填这些记录，
--    使旧交易重放时也能命中数据库唯一约束。
SELECT
    COUNT(*) AS missing_source_tx_hash_rows,
    MIN(created_at) AS earliest_created_at,
    MAX(created_at) AS latest_created_at
FROM sell_match_record
WHERE sell_order_id LIKE 'AUTO_WS_%'
  AND source_tx_hash IS NULL
  AND leader_sell_trade_id LIKE 'AUTO_WS_0x%'
  AND CHAR_LENGTH(leader_sell_trade_id) > 8;
