-- V25: 修复因 @TableLogic 导致收货入库时分配记录未被逻辑删除的问题
-- 之前 transferAllocatedBatchesToBuyer 用 update() 设置 is_deleted=1,
-- 但 MyBatis-Plus 的 @TableLogic 会忽略 update 中该字段的修改,
-- 导致分配记录仍 is_deleted=0,占用可用量

-- 清理所有已收货(RECEIVED)采购订单的分配记录
UPDATE `order_batch_allocation` a
    INNER JOIN `purchase_order` p ON a.order_id = p.id
SET a.is_deleted = 1,
    a.updated_at = NOW(6)
WHERE p.status = 'RECEIVED'
  AND a.is_deleted = 0;
