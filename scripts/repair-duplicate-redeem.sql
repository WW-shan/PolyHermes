-- ============================================================
-- 修复 issue #61：账户链上自动卖出/赎回记录重复写入
--
-- 默认是 DRY RUN，不会修改任何业务数据。
-- 确认下面的预览结果无误后，把 @confirm_repair 改成 1 再执行。
--
-- 安全措施：
-- 1. 只处理 sell_order_id 以 AUTO_WS_ 开头、且 10 秒内产生的重复组；
-- 2. 修复前自动保存 sell_match_record / sell_match_detail / copy_order_tracking 备份；
-- 3. 业务数据更新放在事务中，任一步失败会整体回滚；
-- 4. 默认保留每组最早的一条记录，删除其余重复记录；
-- 5. 同步回滚重复明细造成的 matched_quantity 多算和 remaining_quantity 少算。
--
-- 建议先执行 scripts/diagnose-duplicate-redeem.sql。
-- ============================================================

SET @confirm_repair = 0;  -- 确认后改成 1

-- ------------------------------------------------------------
-- 预览 1：将被认定为重复的记录组
-- ------------------------------------------------------------
SELECT
    s.copy_trading_id,
    s.market_id,
    s.outcome_index,
    s.total_matched_quantity,
    s.sell_price,
    s.total_realized_pnl,
    COUNT(*) AS duplicate_rows,
    GROUP_CONCAT(s.id ORDER BY s.id) AS record_ids,
    FROM_UNIXTIME(MIN(s.created_at) / 1000) AS first_created_at,
    FROM_UNIXTIME(MAX(s.created_at) / 1000) AS last_created_at
FROM sell_match_record s
WHERE s.sell_order_id LIKE 'AUTO_WS_%'
GROUP BY
    s.copy_trading_id,
    s.market_id,
    s.outcome_index,
    s.total_matched_quantity,
    s.sell_price,
    s.total_realized_pnl
HAVING COUNT(*) > 1
   AND MAX(s.created_at) - MIN(s.created_at) <= 10000
ORDER BY duplicate_rows DESC, s.copy_trading_id, s.market_id, s.outcome_index;

-- ------------------------------------------------------------
-- 预览 2：实际会被删除的记录和会被回滚的持仓数量
-- ------------------------------------------------------------
SELECT
    s.id AS duplicate_record_id,
    s.copy_trading_id,
    s.market_id,
    s.outcome_index,
    s.total_matched_quantity,
    s.sell_price,
    s.total_realized_pnl,
    FROM_UNIXTIME(s.created_at / 1000) AS created_at,
    d.tracking_id,
    d.matched_quantity AS duplicated_detail_quantity
FROM sell_match_record s
JOIN (
    SELECT
        copy_trading_id,
        market_id,
        outcome_index,
        total_matched_quantity,
        sell_price,
        total_realized_pnl,
        MIN(id) AS keep_id
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
) g
    ON s.copy_trading_id = g.copy_trading_id
   AND s.market_id = g.market_id
   AND (s.outcome_index <=> g.outcome_index)
   AND s.total_matched_quantity = g.total_matched_quantity
   AND s.sell_price = g.sell_price
   AND s.total_realized_pnl = g.total_realized_pnl
LEFT JOIN sell_match_detail d ON d.match_record_id = s.id
WHERE s.sell_order_id LIKE 'AUTO_WS_%'
  AND s.id <> g.keep_id
ORDER BY s.copy_trading_id, s.market_id, s.outcome_index, s.id;

-- ------------------------------------------------------------
-- 修复过程（默认 DRY RUN；将 @confirm_repair 设为 1 后执行）
-- ------------------------------------------------------------
DROP PROCEDURE IF EXISTS repair_issue61_duplicate_redeem;
DELIMITER $$
CREATE PROCEDURE repair_issue61_duplicate_redeem()
BEGIN
    DROP TEMPORARY TABLE IF EXISTS tmp_issue61_duplicate_records;
    CREATE TEMPORARY TABLE tmp_issue61_duplicate_records (
        id BIGINT NOT NULL PRIMARY KEY
    ) ENGINE = InnoDB;

    INSERT INTO tmp_issue61_duplicate_records (id)
    SELECT s.id
    FROM sell_match_record s
    JOIN (
        SELECT
            copy_trading_id,
            market_id,
            outcome_index,
            total_matched_quantity,
            sell_price,
            total_realized_pnl,
            MIN(id) AS keep_id
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
    ) g
        ON s.copy_trading_id = g.copy_trading_id
       AND s.market_id = g.market_id
       AND (s.outcome_index <=> g.outcome_index)
       AND s.total_matched_quantity = g.total_matched_quantity
       AND s.sell_price = g.sell_price
       AND s.total_realized_pnl = g.total_realized_pnl
    WHERE s.sell_order_id LIKE 'AUTO_WS_%'
      AND s.id <> g.keep_id;

    IF @confirm_repair = 1 THEN
        -- 备份：只备份本次会修改的行，避免无意义地复制全表。
        CREATE TABLE IF NOT EXISTS sell_match_record_backup_issue61 LIKE sell_match_record;
        INSERT IGNORE INTO sell_match_record_backup_issue61
        SELECT s.*
        FROM sell_match_record s
        JOIN tmp_issue61_duplicate_records d ON d.id = s.id;

        CREATE TABLE IF NOT EXISTS sell_match_detail_backup_issue61 LIKE sell_match_detail;
        INSERT IGNORE INTO sell_match_detail_backup_issue61
        SELECT d.*
        FROM sell_match_detail d
        JOIN tmp_issue61_duplicate_records r ON r.id = d.match_record_id;

        CREATE TABLE IF NOT EXISTS copy_order_tracking_backup_issue61 LIKE copy_order_tracking;
        INSERT IGNORE INTO copy_order_tracking_backup_issue61
        SELECT DISTINCT t.*
        FROM copy_order_tracking t
        JOIN (
            SELECT DISTINCT d.tracking_id
            FROM sell_match_detail d
            JOIN tmp_issue61_duplicate_records r ON r.id = d.match_record_id
        ) affected ON affected.tracking_id = t.id;

        START TRANSACTION;

        -- 回滚重复明细造成的数量变化：matched_quantity 减回去，remaining_quantity 加回来。
        UPDATE copy_order_tracking t
        JOIN (
            SELECT
                t2.id,
                GREATEST(0, t2.matched_quantity - x.duplicate_quantity) AS new_matched_quantity,
                LEAST(t2.quantity, t2.remaining_quantity + x.duplicate_quantity) AS new_remaining_quantity
            FROM copy_order_tracking t2
            JOIN (
                SELECT d.tracking_id, SUM(d.matched_quantity) AS duplicate_quantity
                FROM sell_match_detail d
                JOIN tmp_issue61_duplicate_records r ON r.id = d.match_record_id
                GROUP BY d.tracking_id
            ) x ON x.tracking_id = t2.id
        ) calc ON calc.id = t.id
        SET
            t.matched_quantity = calc.new_matched_quantity,
            t.remaining_quantity = calc.new_remaining_quantity,
            t.status = CASE
                WHEN calc.new_matched_quantity <= 0 THEN 'filled'
                WHEN calc.new_remaining_quantity <= 0 THEN 'fully_matched'
                ELSE 'partially_matched'
            END,
            t.updated_at = CAST(UNIX_TIMESTAMP() * 1000 AS UNSIGNED);

        -- sell_match_detail 由外键 ON DELETE CASCADE 一并删除。
        DELETE FROM sell_match_record
        WHERE id IN (SELECT id FROM tmp_issue61_duplicate_records);

        COMMIT;

        SELECT
            'APPLIED' AS result,
            COUNT(*) AS deleted_duplicate_records
        FROM tmp_issue61_duplicate_records;
    ELSE
        SELECT
            'DRY RUN: 未修改业务数据。确认无误后把 @confirm_repair 改为 1 再执行。' AS result,
            COUNT(*) AS duplicate_records_would_be_deleted
        FROM tmp_issue61_duplicate_records;
    END IF;

    DROP TEMPORARY TABLE IF EXISTS tmp_issue61_duplicate_records;
END$$
DELIMITER ;

CALL repair_issue61_duplicate_redeem();
DROP PROCEDURE repair_issue61_duplicate_redeem;

-- 修复后复核：应为 0 行
SELECT
    s.copy_trading_id,
    s.market_id,
    s.outcome_index,
    s.total_matched_quantity,
    s.sell_price,
    s.total_realized_pnl,
    COUNT(*) AS remaining_duplicate_rows
FROM sell_match_record s
WHERE s.sell_order_id LIKE 'AUTO_WS_%'
GROUP BY
    s.copy_trading_id,
    s.market_id,
    s.outcome_index,
    s.total_matched_quantity,
    s.sell_price,
    s.total_realized_pnl
HAVING COUNT(*) > 1
   AND MAX(s.created_at) - MIN(s.created_at) <= 10000;
