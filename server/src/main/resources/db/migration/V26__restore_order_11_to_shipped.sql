-- V26: 将订单 PO202609221356184122 (id=11) 从 RECEIVED 恢复到 SHIPPED 状态
-- 恢复批次归属权到卖方(org 2)、恢复分配记录, 使买方可重新点击确认收货

-- 1. 订单状态恢复为 SHIPPED
UPDATE `purchase_order`
SET status = 'SHIPPED',
    handling_status = 'IN_PROGRESS',
    updated_at = NOW(6)
WHERE id = 11 AND is_deleted = 0;

-- 2. 恢复该订单的分配记录(is_deleted 0->0, 实际是把 V25 删掉的恢复)
UPDATE `order_batch_allocation`
SET is_deleted = 0,
    updated_at = NOW(6)
WHERE order_id = 11 AND is_deleted = 1;

-- 3. 批次归属权从买方(org 3)转回卖方(org 2), 并递增版本号
UPDATE `batch`
SET org_id = 2,
    version = version + 1,
    updated_at = NOW(6)
WHERE id IN (9314, 9315, 9316) AND org_id = 3 AND is_deleted = 0;
