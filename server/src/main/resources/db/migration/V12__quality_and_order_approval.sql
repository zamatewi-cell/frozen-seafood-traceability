-- =============================================================================
-- Flyway Database Migration: V12__quality_and_order_approval.sql
-- Description: 补充 质检流程 与 采购单审批 业务能力（业务流程完善 v2）
--   1. 质检单表 quality_inspection：出厂质检 / 到货验收质检 / 原料入库质检 / 抽检
--   2. purchase_order 增加 供货方审批字段 (approved_by/approved_at/reject_reason)
--   3. purchase_order 增加排产批次关联字段 receipt_batch_id（对上游下单联动）
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 12.1 质检单表
CREATE TABLE IF NOT EXISTS `quality_inspection` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `inspection_no` VARCHAR(64) NOT NULL COMMENT '质检单业务唯一编号(QCxxxx)',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '被检批次ID',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '质检企业ID',
    `inspection_type` VARCHAR(24) NOT NULL DEFAULT 'SPOT' COMMENT '质检类型(SOURCE_RECEIVE原料入库/OUTGOING出厂/ARRIVAL到货验收/SPOT抽检)',
    `related_transfer_id` BIGINT UNSIGNED NULL COMMENT '到货验收关联的交接单ID',
    `inspector_id` BIGINT UNSIGNED NULL COMMENT '质检员用户ID(质管)',
    `inspector_name` VARCHAR(64) NULL COMMENT '质检员姓名/账号',
    `result` VARCHAR(16) NOT NULL DEFAULT 'INSPECTING' COMMENT '判定结果(INSPECTING待检/PASS合格/FAIL不合格)',
    `summary` VARCHAR(500) NULL COMMENT '质检结论说明',
    `checklist_json` TEXT NULL COMMENT '检验指标清单(演示用JSON)',
    `checked_at` DATETIME(6) NULL COMMENT '判定时间(UTC)',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_quality_inspection_no` (`inspection_no`),
    KEY `idx_qc_batch` (`batch_id`, `org_id`),
    KEY `idx_qc_transfer` (`related_transfer_id`),
    KEY `idx_qc_result` (`org_id`, `result`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='质检单';

-- 12.2 采购进货单增加供货方审批字段（审批/驳回 + 排产批次关联）
ALTER TABLE `purchase_order`
    ADD COLUMN `approved_by` BIGINT UNSIGNED NULL COMMENT '审批人(供货方)用户ID' AFTER `note`,
    ADD COLUMN `approved_at` DATETIME(6) NULL COMMENT '供货方审批时间(UTC)' AFTER `approved_by`,
    ADD COLUMN `reject_reason` VARCHAR(500) NULL COMMENT '供货方驳回原因' AFTER `approved_at`;

SET FOREIGN_KEY_CHECKS = 1;