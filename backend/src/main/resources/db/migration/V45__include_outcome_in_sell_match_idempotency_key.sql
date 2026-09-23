-- 同一笔链上交易可以包含同一市场的多个 outcome 转移；
-- outcomeIndex 必须参与幂等键，否则第二个 outcome 会被错误跳过。
ALTER TABLE sell_match_record
    DROP INDEX uk_sell_match_record_tx_market,
    ADD UNIQUE KEY uk_sell_match_record_tx_market_outcome
        (copy_trading_id, source_tx_hash, market_id, outcome_index);
