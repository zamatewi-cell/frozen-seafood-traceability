-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V4
-- 任务编号: GitHub Issue #13
-- 说明: 补充批次操作与物料平衡记录表的创建/提交防重幂等键、同组织唯一约束与 MySQL 8.4 物理 CHECK 约束
-- =============================================================================

-- 1. 批次操作主表：
--   1.1 移除原全局唯一幂等键索引
--   1.2 扩展创建幂等键至 128 字符并添加组织内唯一索引
--   1.3 新增提交幂等键字段及组织内唯一索引
--   1.4 修改状态默认值为 DRAFT
--   1.5 补充操作类型与状态物理 CHECK 约束
ALTER TABLE `batch_operation`
    DROP INDEX `uk_op_idempotency`;

ALTER TABLE `batch_operation`
    MODIFY COLUMN `idempotency_key` VARCHAR(128) NOT NULL COMMENT '创建防重幂等键',
    ADD COLUMN `submission_idempotency_key` VARCHAR(128) NULL COMMENT '提交防重幂等键' AFTER `idempotency_key`,
    ALTER COLUMN `status` SET DEFAULT 'DRAFT',
    ADD CONSTRAINT `uk_op_org_idempotency` UNIQUE (`org_id`, `idempotency_key`),
    ADD CONSTRAINT `uk_op_org_submission_idempotency` UNIQUE (`org_id`, `submission_idempotency_key`),
    ADD CONSTRAINT `chk_op_type` CHECK (`operation_type` IN ('MERGE', 'SPLIT', 'PROCESS', 'REPACK')),
    ADD CONSTRAINT `chk_op_status` CHECK (`status` IN ('DRAFT', 'SUBMITTED', 'CORRECTED'));

-- 2. 批次操作明细项目表：补充角色枚举、正数量、kg 单位与折算数量物理 CHECK 约束
ALTER TABLE `batch_operation_item`
    ADD CONSTRAINT `chk_item_role` CHECK (`role` IN ('INPUT', 'OUTPUT', 'LOSS', 'WASTE', 'SAMPLE')),
    ADD CONSTRAINT `chk_item_quantity` CHECK (`quantity` > 0),
    ADD CONSTRAINT `chk_item_unit_code` CHECK (`unit_code` = 'kg'),
    ADD CONSTRAINT `chk_item_normalized_quantity` CHECK (`normalized_quantity` > 0);

-- 3. 批次谱系父子关系图谱边表：补充关系类型与非自环物理 CHECK 约束
ALTER TABLE `batch_relation`
    ADD CONSTRAINT `chk_relation_type` CHECK (`relation_type` IN ('TRANSFORM', 'SPLIT', 'MERGE')),
    ADD CONSTRAINT `chk_relation_no_self_loop` CHECK (`parent_batch_id` <> `child_batch_id`);
