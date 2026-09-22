-- V29: 回填 QUALITY_CHECK 事件的 details_json (质检清单项详情)
-- V28 回填时只设了 summary, details_json 为空
-- 本迁移从 quality_inspection.checklist_json 提取清单项, 写入 trace_event.details_json
-- idempotency_key 格式为 quality-qc-{inspectionId}, 用 REPLACE 提取 ID

UPDATE trace_event e
INNER JOIN quality_inspection q ON q.id = CAST(REPLACE(e.idempotency_key, 'quality-qc-', '') AS UNSIGNED)
SET e.details_json = JSON_OBJECT(
    'stage', q.inspection_stage,
    'inspectionNo', q.inspection_no,
    'itemCount', JSON_LENGTH(q.checklist_json),
    'items', q.checklist_json
)
WHERE e.event_type = 'QUALITY_CHECK'
  AND e.is_deleted = 0
  AND e.details_json IS NULL
  AND e.idempotency_key LIKE 'quality-qc-%';
