-- V28: 质检事件类型整改
-- 修复问题: 质检通过生成PACK事件(标签"分装与包装"), 且每次质检生成多条(每个清单项一条)
-- 整改为: 新增QUALITY_CHECK类型, 每次质检合并为一条事件, 标签"质检通过"
-- 使时间线呈现: 捕捞→质检→加工→质检→批发→质检 的交替流程

-- 1. 修改CHECK约束, 新增 QUALITY_CHECK 事件类型
ALTER TABLE `trace_event`
    DROP CONSTRAINT `chk_trace_event_type`;
ALTER TABLE `trace_event`
    ADD CONSTRAINT `chk_trace_event_type` CHECK (`event_type` IN (
        'SOURCE', 'PURCHASE', 'PROCESS', 'FREEZE', 'PACK',
        'WAREHOUSE_IN', 'WAREHOUSE_OUT', 'TRANSPORT', 'ARRIVAL', 'SALE',
        'QUALITY_CHECK'
    ));

-- 2. 删除旧的质检PACK事件(每个清单项一条的冗余事件, idempotency_key 以 quality-checklist- 开头)
DELETE FROM `trace_event`
WHERE `idempotency_key` LIKE 'quality-checklist-%';

-- 3. 根据 quality_inspection 表回填 QUALITY_CHECK 事件, 每次质检一条
--    occurred_at 取 checklist_passed_at(清单全部通过时间)
--    summary 包含环节和清单项数
INSERT INTO `trace_event` (batch_id, org_id, event_type, occurred_at, recorded_at, operator_id, data_source, status, idempotency_key, summary, created_by, created_at, version, is_deleted)
SELECT q.batch_id, q.org_id, 'QUALITY_CHECK',
       COALESCE(q.checklist_passed_at, q.checked_at, q.created_at),
       COALESCE(q.checklist_passed_at, q.checked_at, q.created_at),
       COALESCE(q.inspector_id, q.created_by, 1), 'MANUAL', 'SUBMITTED',
       CONCAT('quality-qc-', q.id),
       CONCAT('质检通过: 环节', q.inspection_stage, ', ', JSON_LENGTH(q.checklist_json), '项清单全部通过'),
       COALESCE(q.created_by, 1), q.created_at, 0, 0
FROM `quality_inspection` q
WHERE q.result = 'PASS' AND q.is_deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM `trace_event` e
      WHERE e.idempotency_key = CONCAT('quality-qc-', q.id) AND e.is_deleted = 0
  );
