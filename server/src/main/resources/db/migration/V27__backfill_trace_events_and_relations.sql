-- V27: 回填溯源事件和批次谱系关系
-- 修复问题: 加工方法未创建batch_relation, 订单流转未创建trace_event
-- 本迁移为历史数据补全溯源链路

-- 1. 为加工批次(PROCESSING)回填批次谱系关系: 从 origin_text 解析源批次号
-- origin_text 格式: "加工自批次 DS-1790085499770 (消耗900kg)"
INSERT INTO batch_relation (operation_id, parent_batch_id, child_batch_id, relation_type, created_at)
SELECT 0, parent.id, child.id, 'TRANSFORM', child.created_at
FROM batch child
INNER JOIN batch parent ON parent.batch_no = SUBSTRING_INDEX(
    TRIM(SUBSTRING_INDEX(child.origin_text, '(', 1)), ' ', -1
)
WHERE child.batch_type = 'PROCESSING'
  AND child.origin_text LIKE '加工自批次 %'
  AND child.is_deleted = 0
  AND parent.is_deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM batch_relation r
      WHERE r.parent_batch_id = parent.id AND r.child_batch_id = child.id
  );

-- 2. 为原料批次(SOURCE)回填 SOURCE 事件(如果不存在)
INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, operator_id, data_source, status, idempotency_key, summary, created_by, created_at, version, is_deleted)
SELECT b.id, b.org_id, 'SOURCE', b.created_at, b.created_at,
       COALESCE(b.created_by, 1), 'SIMULATED', 'SUBMITTED',
       CONCAT('sys-SOURCE-', b.id, '-', UNIX_TIMESTAMP(b.created_at)),
       CONCAT('原料采收: ', b.origin_text),
       COALESCE(b.created_by, 1), b.created_at, 0, 0
FROM batch b
WHERE b.batch_type IN ('SOURCE')
  AND b.is_deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM trace_event e
      WHERE e.batch_id = b.id AND e.event_type = 'SOURCE' AND e.is_deleted = 0
  );

-- 3. 为加工批次(PROCESSING)回填 PROCESS 事件(如果不存在)
INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, operator_id, data_source, status, idempotency_key, summary, created_by, created_at, version, is_deleted)
SELECT b.id, b.org_id, 'PROCESS', b.created_at, b.created_at,
       COALESCE(b.created_by, 1), 'SIMULATED', 'SUBMITTED',
       CONCAT('sys-PROCESS-', b.id, '-', UNIX_TIMESTAMP(b.created_at)),
       CONCAT('加工: ', b.origin_text),
       COALESCE(b.created_by, 1), b.created_at, 0, 0
FROM batch b
WHERE b.batch_type = 'PROCESSING'
  AND b.is_deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM trace_event e
      WHERE e.batch_id = b.id AND e.event_type = 'PROCESS' AND e.is_deleted = 0
  );

-- 4. 为已出货(SHIPPED/RECEIVED)采购订单的分配批次回填 WAREHOUSE_OUT 和 TRANSPORT 事件
-- 注意: 不限制 a.is_deleted, 因为 RECEIVED 订单的分配记录在收货时会被逻辑删除
INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, operator_id, data_source, status, idempotency_key, summary, created_by, created_at, version, is_deleted)
SELECT a.batch_id, p.seller_org_id, 'WAREHOUSE_OUT',
       DATE_SUB(p.updated_at, INTERVAL 1 SECOND), DATE_SUB(p.updated_at, INTERVAL 1 SECOND),
       COALESCE(p.updated_by, 1), 'SIMULATED', 'SUBMITTED',
       CONCAT('sys-WAREHOUSE_OUT-', a.batch_id, '-', UNIX_TIMESTAMP(p.updated_at)),
       CONCAT('订单出库: ', p.order_no),
       COALESCE(p.updated_by, 1), p.updated_at, 0, 0
FROM order_batch_allocation a
INNER JOIN purchase_order p ON a.order_id = p.id
WHERE p.status IN ('SHIPPED', 'RECEIVED')
  AND NOT EXISTS (
      SELECT 1 FROM trace_event e
      WHERE e.batch_id = a.batch_id AND e.event_type = 'WAREHOUSE_OUT' AND e.is_deleted = 0
  );

INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, operator_id, data_source, status, idempotency_key, summary, created_by, created_at, version, is_deleted)
SELECT a.batch_id, p.seller_org_id, 'TRANSPORT',
       p.updated_at, p.updated_at,
       COALESCE(p.updated_by, 1), 'SIMULATED', 'SUBMITTED',
       CONCAT('sys-TRANSPORT-', a.batch_id, '-', UNIX_TIMESTAMP(p.updated_at)),
       CONCAT('冷链运输: ', p.order_no),
       COALESCE(p.updated_by, 1), p.updated_at, 0, 0
FROM order_batch_allocation a
INNER JOIN purchase_order p ON a.order_id = p.id
WHERE p.status IN ('SHIPPED', 'RECEIVED')
  AND NOT EXISTS (
      SELECT 1 FROM trace_event e
      WHERE e.batch_id = a.batch_id AND e.event_type = 'TRANSPORT' AND e.is_deleted = 0
  );

-- 5. 为已收货(RECEIVED)采购订单的分配批次回填 ARRIVAL 和 WAREHOUSE_IN 事件
INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, operator_id, data_source, status, idempotency_key, summary, created_by, created_at, version, is_deleted)
SELECT a.batch_id, p.buyer_org_id, 'ARRIVAL',
       DATE_ADD(p.updated_at, INTERVAL 1 SECOND), DATE_ADD(p.updated_at, INTERVAL 1 SECOND),
       COALESCE(p.updated_by, 1), 'SIMULATED', 'SUBMITTED',
       CONCAT('sys-ARRIVAL-', a.batch_id, '-', UNIX_TIMESTAMP(p.updated_at)),
       CONCAT('到货验收: ', p.order_no),
       COALESCE(p.updated_by, 1), p.updated_at, 0, 0
FROM order_batch_allocation a
INNER JOIN purchase_order p ON a.order_id = p.id
WHERE p.status = 'RECEIVED'
  AND NOT EXISTS (
      SELECT 1 FROM trace_event e
      WHERE e.batch_id = a.batch_id AND e.event_type = 'ARRIVAL' AND e.is_deleted = 0
  );

INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, operator_id, data_source, status, idempotency_key, summary, created_by, created_at, version, is_deleted)
SELECT a.batch_id, p.buyer_org_id, 'WAREHOUSE_IN',
       DATE_ADD(p.updated_at, INTERVAL 2 SECOND), DATE_ADD(p.updated_at, INTERVAL 2 SECOND),
       COALESCE(p.updated_by, 1), 'SIMULATED', 'SUBMITTED',
       CONCAT('sys-WAREHOUSE_IN-', a.batch_id, '-', UNIX_TIMESTAMP(p.updated_at)),
       CONCAT('入库: ', p.order_no),
       COALESCE(p.updated_by, 1), p.updated_at, 0, 0
FROM order_batch_allocation a
INNER JOIN purchase_order p ON a.order_id = p.id
WHERE p.status = 'RECEIVED'
  AND NOT EXISTS (
      SELECT 1 FROM trace_event e
      WHERE e.batch_id = a.batch_id AND e.event_type = 'WAREHOUSE_IN' AND e.is_deleted = 0
  );
