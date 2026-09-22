# Issue #61：重复赎回记录修复说明

## 适用范围

该修复只针对以下历史数据问题：

- 同一笔链上 `REDEEM` 被 `UnifiedOnChainWsService` 多次回调。
- 同一跟单关系、同一市场、同一 outcome 在 10 秒内产生多条 `AUTO_WS_%` 自动卖出记录。
- 这些重复记录导致 `sell_match_record`、`sell_match_detail`、`copy_order_tracking.matched_quantity` 和已实现盈亏重复累计。

修复代码部署后，新交易会通过 `accountId + txHash` 内存去重和数据库唯一约束阻止再次写入。历史数据需要按本文执行一次清理。

## 执行前必须做的备份

先对生产数据库做完整快照。不要只依赖脚本自动创建的局部备份表：

```bash
mysqldump -h <host> -P <port> -u <user> -p \
  --single-transaction --routines --triggers \
  <database> > polyhermes-before-issue61-$(date +%Y%m%d-%H%M%S).sql
```

确认备份文件非空并完成校验后，再继续。

## 第一步：只读诊断

执行：

```bash
mysql -h <host> -P <port> -u <user> -p <database> \
  < scripts/diagnose-duplicate-redeem.sql
```

重点检查：

- `duplicate_rows` 是否大于 1。
- `created_at_span_ms` 是否在 10 秒以内。
- `record_ids`、市场、outcome、数量和盈亏是否确实属于同一笔链上交易。
- 受影响跟单关系的多算盈亏和多算数量。

如果只是用户在不同时间进行了两次正常卖出，不应把它们当作重复记录。

## 第二步：修复脚本 dry-run

脚本默认 `@confirm_repair = 0`，只预览、不修改业务数据：

```bash
mysql -h <host> -P <port> -u <user> -p <database> \
  < scripts/repair-duplicate-redeem.sql
```

确认输出中的 `duplicate_record_id`、`duplicated_detail_quantity` 与诊断结果一致。

## 第三步：执行修复

编辑修复脚本副本，只修改这一行：

```sql
SET @confirm_repair = 1;
```

然后对生产库执行。脚本会：

1. 自动保存三张局部备份表：
   - `sell_match_record_backup_issue61`
   - `sell_match_detail_backup_issue61`
   - `copy_order_tracking_backup_issue61`
2. 回滚重复明细造成的 `matched_quantity` 多算和 `remaining_quantity` 少算。
3. 保留每组最早的一条卖出记录，删除其余重复记录。
4. 在事务中提交业务数据修改。

脚本是幂等的：修复完成后再次执行，应为删除 `0` 条。

## 第四步：复核

脚本结尾的复核查询应返回 `0` 行。再确认：

- 被保留的自动卖出记录数量和盈亏只计算一次。
- 受影响订单的 `matched_quantity + remaining_quantity` 没有超过原始买入数量。
- 跟单统计页面的已实现盈亏恢复到合理范围。

如果结果异常，优先使用完整数据库快照恢复；局部备份表用于核对被删除的具体行，不应替代完整快照。

## 已完成的验证

该脚本已在 MySQL 8.2 临时数据库中验证：

- 全量 Flyway migration 可正常执行。
- dry-run 能识别 3 条记录中的 2 条重复记录，且不修改数据。
- 确认执行后数量和状态正确回滚，明细随外键级联删除。
- 三张局部备份表均成功写入。
- 再次执行删除 `0` 条，具备幂等性。
