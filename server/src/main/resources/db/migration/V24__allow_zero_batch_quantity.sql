-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V24
-- 说明:
--   batch.quantity 原 CHECK 约束要求 > 0,但原料加工消耗完时
--   quantity 归零并置 CLOSED 状态,此时 update 违反约束导致 500 错误。
--   本脚本将约束改为 >= 0,允许批次数量归零表示耗尽。
-- =============================================================================

ALTER TABLE `batch` DROP CONSTRAINT `chk_batch_quantity`;
ALTER TABLE `batch`
    ADD CONSTRAINT `chk_batch_quantity` CHECK (`quantity` >= 0);
