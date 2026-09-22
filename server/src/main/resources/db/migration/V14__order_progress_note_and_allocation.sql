-- =============================================================================
-- Flyway Database Migration: V14__order_progress_note_and_allocation.sql
-- Description: 订单级备注时间线 + 订单-批次分配（一单多批交付）
--   1. 新建 order_progress_note：订单级备注时间线（上下游实时可见）
--   2. 新建 order_batch_allocation：交付时从库存选批次分配给订单（支持一单多批）
--   3. purchase_order 加 handling_status：交接阶段状态（PENDING/IN_PROGRESS/COMPLETED）
-- =============================================================================

SET NAMES utf8mb4;

-- 14.1 订单级备注时间线（一个订单挂多条备注，买卖双方实时可见）
CREATE TABLE IF NOT EXISTS `order_progress_note` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `order_id` BIGINT UNSIGNED NOT NULL COMMENT '所属订单ID',
    `order_type` VARCHAR(16) NOT NULL COMMENT '订单类型(PURCHASE=采购单/SALES=销售单)',
    `note_text` VARCHAR(500) NOT NULL COMMENT '备注内容(如:正在调货/运输中/已完成)',
    `status_at` VARCHAR(32) NOT NULL COMMENT '该备注对应的订单状态(PROCESSING/SHIPPED/RECEIVED等)',
    `is_terminal_visible` TINYINT NOT NULL DEFAULT 1 COMMENT '终端是否可见(1=可见 0=仅企业内部)',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '记录方组织ID',
    `created_by` BIGINT UNSIGNED NOT NULL COMMENT '创建人用户ID',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记',
    PRIMARY KEY (`id`),
    KEY `idx_opn_order` (`order_id`, `order_type`, `is_deleted`),
    KEY `idx_opn_org` (`org_id`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单进度备注时间线';

-- 14.2 订单-批次分配（交付时从库存选批次分配给订单，一个订单可分多批=多分支）
CREATE TABLE IF NOT EXISTS `order_batch_allocation` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `order_id` BIGINT UNSIGNED NOT NULL COMMENT '所属采购订单ID(卖方交付给买方)',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '分配的批次ID(卖方库存中的批次)',
    `allocated_quantity` DECIMAL(14,3) NOT NULL COMMENT '本批次分配数量',
    `unit_code` VARCHAR(8) NOT NULL DEFAULT 'kg' COMMENT '单位(kg)',
    `allocation_order` INT NOT NULL DEFAULT 0 COMMENT '同订单内分配顺序(树状图分支序)',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '分配方组织ID(卖方)',
    `allocated_by` BIGINT UNSIGNED NOT NULL COMMENT '分配人用户ID',
    `allocated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC分配时间',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_oba_order_batch` (`order_id`, `batch_id`),
    KEY `idx_oba_batch` (`batch_id`),
    KEY `idx_oba_org` (`org_id`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单-批次分配(一单多批交付)';

-- 14.3 采购单加交接阶段状态字段（PENDING=待处理/IN_PROGRESS=交接中/COMPLETED=已交付）
ALTER TABLE `purchase_order`
    ADD COLUMN `handling_status` VARCHAR(32) NULL COMMENT '交接阶段状态(PENDING/IN_PROGRESS/COMPLETED)' AFTER `status`;

-- 14.4 回填存量采购单的 handling_status（基于当前 status 推导）
--   CONFIRMED 及以后但未 RECEIVED → IN_PROGRESS（已开始处理未交付）
--   RECEIVED → COMPLETED（已交付完成）
--   SUBMITTED/其他 → PENDING（刚下单待处理）
UPDATE `purchase_order` SET `handling_status` = 'PENDING' WHERE `handling_status` IS NULL;
UPDATE `purchase_order` SET `handling_status` = 'IN_PROGRESS'
    WHERE `handling_status` = 'PENDING' AND `status` IN ('CONFIRMED','PROCESSING','SHIPPED');
UPDATE `purchase_order` SET `handling_status` = 'COMPLETED'
    WHERE `status` = 'RECEIVED';

-- 14.5 加 CHECK 约束（MySQL 8.0.16+ 支持）
ALTER TABLE `purchase_order`
    ADD CONSTRAINT `chk_po_handling_status` CHECK (
        `handling_status` IN ('PENDING','IN_PROGRESS','COMPLETED')
    );
