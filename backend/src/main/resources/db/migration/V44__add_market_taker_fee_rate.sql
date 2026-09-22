-- 市场 taker 手续费率（来自 Gamma feeSchedule.rate）
-- 背景：Gamma 已不再返回 market.category，且 sports_fees_v2 费率为 0.03、sports_fees_v3 为 0.05，
-- 仅按分类推导会算错；这里持久化市场真实费率，NULL 表示历史数据尚未获取。
ALTER TABLE markets ADD COLUMN taker_fee_rate DECIMAL(12,8) NULL;
