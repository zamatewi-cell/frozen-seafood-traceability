-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V23
-- 说明:
--   历史已收货入库(RECEIVED)的采购订单,其 order_batch_allocation 分配记录
--   未被释放,导致买方库存可用量(available = quantity - allocated)为0。
--   本脚本将所有 RECEIVED 状态采购订单的分配记录逻辑删除,释放可用量。
-- =============================================================================

UPDATE `order_batch_allocation` a
    INNER JOIN `purchase_order` p ON a.order_id = p.id
SET a.is_deleted = 1,
    a.updated_at = NOW(6)
WHERE p.status = 'RECEIVED'
  AND a.is_deleted = 0;
