-- =============================================================================
-- Flyway Database Migration: V17__purchase_order_trace_and_cancel.sql
-- Description:
--   1. purchase_order 加 trace_code_id/public_trace_id:终端订单收货入库时生成码
--   2. purchase_order 加取消请求字段:买方/卖方发起,对方或管理员审核
-- =============================================================================

ALTER TABLE `purchase_order`
    ADD COLUMN `trace_code_id` BIGINT NULL COMMENT '生成的公开溯源码ID' AFTER `receipt_batch_id`,
    ADD COLUMN `public_trace_id` VARCHAR(26) NULL COMMENT '26位Base32公开溯源码' AFTER `trace_code_id`,
    ADD COLUMN `cancel_request_role` VARCHAR(16) NULL COMMENT '取消发起方角色(BUYER/SELLER)' AFTER `reject_reason`,
    ADD COLUMN `cancel_request_reason` VARCHAR(500) NULL COMMENT '取消原因' AFTER `cancel_request_role`,
    ADD COLUMN `cancel_request_status` VARCHAR(16) NULL COMMENT '取消请求状态(PENDING/APPROVED/REJECTED)' AFTER `cancel_request_reason`,
    ADD COLUMN `cancel_request_at` DATETIME(6) NULL COMMENT '取消请求时间(UTC)' AFTER `cancel_request_status`;

ALTER TABLE `purchase_order`
    ADD CONSTRAINT `chk_po_cancel_status` CHECK (
        `cancel_request_status` IS NULL OR `cancel_request_status` IN ('PENDING','APPROVED','REJECTED')
    );
ALTER TABLE `purchase_order`
    ADD CONSTRAINT `chk_po_cancel_role` CHECK (
        `cancel_request_role` IS NULL OR `cancel_request_role` IN ('BUYER','SELLER')
    );
