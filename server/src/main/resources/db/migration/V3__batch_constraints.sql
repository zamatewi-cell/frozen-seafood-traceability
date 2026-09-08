-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V3
-- 任务编号: GitHub Issue #11
-- 说明: 补充追溯批次表的创建防重幂等键、同组织唯一约束与 MySQL 8.4 CHECK 双重业务约束
-- =============================================================================

-- 1. 追溯批次主表：补充创建防重幂等键字段与同组织幂等键唯一索引
ALTER TABLE `batch`
    ADD COLUMN `creation_idempotency_key` VARCHAR(128) NULL COMMENT '批次创建防重幂等键' AFTER `status`,
    ADD CONSTRAINT `uk_batch_org_idempotency` UNIQUE (`org_id`, `creation_idempotency_key`);

-- 2. 追溯批次主表：补充枚举值域、数量精度与保质期天数 CHECK 约束
ALTER TABLE `batch`
    ADD CONSTRAINT `chk_batch_batch_type` CHECK (`batch_type` IN ('SOURCE', 'PROCESSING', 'DISTRIBUTION', 'SALE')),
    ADD CONSTRAINT `chk_batch_origin_type` CHECK (`origin_type` IN ('DOMESTIC_CAPTURE', 'DOMESTIC_FARMED', 'IMPORT')),
    ADD CONSTRAINT `chk_batch_status` CHECK (`status` IN ('DRAFT', 'ACTIVE', 'FROZEN', 'RECALLED', 'CLOSED')),
    ADD CONSTRAINT `chk_batch_quantity` CHECK (`quantity` > 0),
    ADD CONSTRAINT `chk_batch_unit_code` CHECK (`unit_code` = 'kg'),
    ADD CONSTRAINT `chk_batch_shelf_life_days` CHECK (`shelf_life_days` IS NULL OR `shelf_life_days` > 0);
