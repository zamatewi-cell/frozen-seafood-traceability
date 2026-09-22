-- =============================================================================
-- Flyway Database Migration: V13__public_trace_code_multi_batch.sql
-- Description: 公开追溯码由「一批一码」演进为「终端下单生成、一码聚合多批」
--   1. public_trace_code.batch_id 改为可空（终端下单时创建的码尚无物理批次）
--   2. 移除 batch_id 唯一约束 uk_public_trace_code_batch，改为普通索引
--   3. 新增 source_order_id / source_order_type 关联生成该码的终端订单
--   4. 新建一码多批中间表 public_trace_code_batch
--   5. 将存量「批次→码」绑定回填进中间表，保证老数据在新聚合查询下继续可见
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 13.1 将 batch_id 改为可空（终端下单生成的码可以暂不关联任何批次）
ALTER TABLE `public_trace_code`
    MODIFY COLUMN `batch_id` BIGINT UNSIGNED NULL COMMENT '关联批次ID(可空:终端下单生成的码未定批)';

-- 13.2 移除一批一码唯一约束，保留普通索引以便按批检索
ALTER TABLE `public_trace_code`
    DROP INDEX `uk_public_trace_code_batch`;

-- 13.3 新增源代码关联字段（记录生成该码的终端订单）
ALTER TABLE `public_trace_code`
    ADD COLUMN `source_order_id` BIGINT UNSIGNED NULL COMMENT '生成该码的终端订单ID' AFTER `batch_id`,
    ADD COLUMN `source_order_type` VARCHAR(24) NULL COMMENT '源代码类型(SALES=销售订单)' AFTER `source_order_id`;

-- 13.4 一码多批中间表：一个公开溯源码可聚合多个物理批次
CREATE TABLE IF NOT EXISTS `public_trace_code_batch` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `trace_code_id` BIGINT UNSIGNED NOT NULL COMMENT '公开追溯码ID',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '聚合的物理批次ID',
    `batch_org_id` BIGINT UNSIGNED NOT NULL COMMENT '批次所属企业组织ID',
    `bind_role` VARCHAR(32) NULL COMMENT '绑定环节角色(如 RETAIL/RECEIVE)',
    `bound_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '绑定时间(UTC)',
    `bound_by` BIGINT UNSIGNED NULL COMMENT '绑定人用户ID',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ptcb_code_batch` (`trace_code_id`, `batch_id`),
    KEY `idx_ptcb_batch` (`batch_id`),
    KEY `idx_ptcb_code` (`trace_code_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公开追溯码-批次聚合中间表';

-- 13.5 回填存量绑定：把历史「一批一码」数据写入中间表，使旧码在新聚合查询下仍然完整
INSERT INTO `public_trace_code_batch` (`trace_code_id`, `batch_id`, `batch_org_id`, `bind_role`, `bound_by`)
SELECT `id`, `batch_id`, `org_id`, 'LEGACY', `created_by`
FROM `public_trace_code`
WHERE `batch_id` IS NOT NULL AND `is_deleted` = 0
ON DUPLICATE KEY UPDATE `batch_org_id` = VALUES(`batch_org_id`);

SET FOREIGN_KEY_CHECKS = 1;