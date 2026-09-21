-- =============================================================================
-- Flyway Database Migration: V8__order_schema.sql
-- Description: 多角色订单域（B2B 采购进货单 + B2C 销售订单）
-- 本地演示分支新增，用于支撑 商家买成品 / 超市进货 / 加工厂进原料 的多角色下单与批次溯源联动
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 8.1 采购进货单主表（B2B：加工厂向供货方进原料、超市/商家向加工厂/批发进成品）
CREATE TABLE IF NOT EXISTS `purchase_order` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `order_no` VARCHAR(64) NOT NULL COMMENT '采购进货单业务唯一编号',
    `buyer_org_id` BIGINT UNSIGNED NOT NULL COMMENT '采购方/收货方企业ID',
    `seller_org_id` BIGINT UNSIGNED NOT NULL COMMENT '供货方/发货方企业ID',
    `order_type` VARCHAR(24) NOT NULL DEFAULT 'MATERIAL' COMMENT '采购类型(MATERIAL=原料/成品)',
    `status` VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED' COMMENT '状态(SUBMITTED/CONFIRMED/PROCESSING/SHIPPED/RECEIVED/CANCELLED)',
    `ordered_at` DATETIME(6) NOT NULL COMMENT '下单时间(UTC)',
    `expected_delivery_at` DATETIME(6) NULL COMMENT '期望交货时间(UTC)',
    `receipt_batch_id` BIGINT UNSIGNED NULL COMMENT '收货后关联的原料/成品批次ID',
    `amount_total` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '订单金额合计(演示值)',
    `currency_code` VARCHAR(8) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `note` VARCHAR(500) NULL COMMENT '采购备注说明',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_purchase_order_no` (`order_no`),
    KEY `idx_purchase_buyer_status` (`buyer_org_id`, `status`, `ordered_at`),
    KEY `idx_purchase_seller_status` (`seller_org_id`, `status`, `ordered_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购进货单主表';

-- 8.2 采购进货单明细表
CREATE TABLE IF NOT EXISTS `purchase_order_item` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `order_id` BIGINT UNSIGNED NOT NULL COMMENT '关联采购单ID',
    `product_id` BIGINT UNSIGNED NOT NULL COMMENT '货物产品ID',
    `quantity` DECIMAL(18,3) NOT NULL COMMENT '采购数量/重量',
    `unit_code` VARCHAR(16) NOT NULL DEFAULT 'kg' COMMENT '计量单位',
    `unit_price` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '单价(演示值)',
    `row_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '明细行金额',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    KEY `idx_purchase_item_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购进货单明细表';

-- 8.3 销售/客户订单主表（B2C：消费者向商家/超市下单；也可由商家进货后再分销）
CREATE TABLE IF NOT EXISTS `sales_order` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `order_no` VARCHAR(64) NOT NULL COMMENT '销售订单业务唯一编号',
    `seller_org_id` BIGINT UNSIGNED NOT NULL COMMENT '销售方企业ID',
    `customer_name` VARCHAR(64) NOT NULL COMMENT '客户称谓(脱敏)',
    `customer_phone` VARCHAR(32) NULL COMMENT '客户联系电话(脱敏)',
    `delivery_address` VARCHAR(255) NOT NULL COMMENT '收货地址',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PLACED' COMMENT '状态(PLACED/CONFIRMED/PROCESSING/SHIPPED/DELIVERED/CANCELLED)',
    `trace_code_id` BIGINT UNSIGNED NULL COMMENT '交付绑定的公开追溯码ID',
    `packed_package_no` VARCHAR(64) NULL COMMENT '交付分装包装号/溯源码',
    `placed_at` DATETIME(6) NOT NULL COMMENT '下单时间(UTC)',
    `delivered_at` DATETIME(6) NULL COMMENT '送达时间(UTC)',
    `amount_total` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '订单金额合计(演示值)',
    `currency_code` VARCHAR(8) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `note` VARCHAR(500) NULL COMMENT '订单备注说明',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sales_order_no` (`order_no`),
    KEY `idx_sales_seller_status` (`seller_org_id`, `status`, `placed_at`),
    KEY `idx_sales_trace_code` (`trace_code_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售/客户订单主表';

-- 8.4 销售订单明细表
CREATE TABLE IF NOT EXISTS `sales_order_item` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `order_id` BIGINT UNSIGNED NOT NULL COMMENT '关联销售单ID',
    `product_id` BIGINT UNSIGNED NOT NULL COMMENT '商品产品ID',
    `quantity` DECIMAL(18,3) NOT NULL COMMENT '购买数量/重量',
    `unit_code` VARCHAR(16) NOT NULL DEFAULT 'kg' COMMENT '计量单位',
    `unit_price` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '单价(演示值)',
    `row_amount` DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '明细行金额',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    KEY `idx_sales_item_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售订单明细表';

SET FOREIGN_KEY_CHECKS = 1;