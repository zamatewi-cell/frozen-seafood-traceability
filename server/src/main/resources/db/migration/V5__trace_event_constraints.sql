-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V5
-- 任务编号: GitHub Issue #15
-- 说明: 追溯事件表的幂等防重键、防分叉更正唯一索引、查询复合索引与 MySQL 8.4 物理 CHECK 约束
-- 约束设计说明:
--   1. MySQL 8.4 明确禁止 CHECK 约束引用 AUTO_INCREMENT 主键列，因此防自更正由应用层与插入时天然 ID 隔离保障；
--   2. uk_trace_event_corrects 复合唯一索引在数据库底层彻底杜绝多版本分叉更正。
-- =============================================================================

-- 1. 追溯事件主表：
--   1.1 移除原有非唯一更正索引
--   1.2 新增创建/更正防重幂等键字段与组织内唯一索引
--   1.3 新增更正原因说明字段
--   1.4 新增防分叉更正唯一约束 (每条事件最多被更正一次)
--   1.5 新增组织/批次/业务时间/系统时间/主键稳定查询复合索引
ALTER TABLE `trace_event`
    DROP INDEX `idx_event_corrects`;

ALTER TABLE `trace_event`
    ADD COLUMN `idempotency_key` VARCHAR(128) NOT NULL COMMENT '创建/更正防重幂等键' AFTER `status`,
    ADD COLUMN `correction_reason` VARCHAR(500) NULL COMMENT '更正原因说明' AFTER `corrects_event_id`,
    ADD CONSTRAINT `uk_trace_event_org_idempotency` UNIQUE (`org_id`, `idempotency_key`),
    ADD CONSTRAINT `uk_trace_event_corrects` UNIQUE (`corrects_event_id`),
    ADD KEY `idx_trace_event_org_batch_time` (`org_id`, `batch_id`, `occurred_at`, `recorded_at`, `id`);

-- 2. 追溯事件主表：补充事件类型、数据来源、状态与更正形状物理 CHECK 约束
ALTER TABLE `trace_event`
    ADD CONSTRAINT `chk_trace_event_type` CHECK (`event_type` IN (
        'SOURCE', 'PURCHASE', 'PROCESS', 'FREEZE', 'PACK',
        'WAREHOUSE_IN', 'WAREHOUSE_OUT', 'TRANSPORT', 'ARRIVAL', 'SALE'
    )),
    ADD CONSTRAINT `chk_trace_event_data_source` CHECK (`data_source` IN (
        'MANUAL', 'IMPORT', 'SIMULATED', 'DEVICE'
    )),
    ADD CONSTRAINT `chk_trace_event_status` CHECK (`status` IN (
        'SUBMITTED', 'CORRECTED'
    )),
    ADD CONSTRAINT `chk_trace_event_correction_shape` CHECK (
        (`corrects_event_id` IS NULL AND `correction_reason` IS NULL) OR
        (`corrects_event_id` IS NOT NULL AND `correction_reason` IS NOT NULL)
    );
