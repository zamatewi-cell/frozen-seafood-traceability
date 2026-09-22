-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V22
-- 说明:
--   purchase_order.cancel_request_status 原 CHECK 约束仅允许 PENDING/APPROVED/REJECTED,
--   撤回取消请求功能需要 WITHDRAWN 状态(发起方撤回自己的 PENDING 请求)。
--   本脚本删除原约束并重建,加入 WITHDRAWN 值。
-- =============================================================================

ALTER TABLE `purchase_order` DROP CONSTRAINT `chk_po_cancel_status`;

ALTER TABLE `purchase_order`
    ADD CONSTRAINT `chk_po_cancel_status` CHECK (
        `cancel_request_status` IS NULL
        OR `cancel_request_status` IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')
    );
