-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V17
-- 任务编号: Phase B 独立评审修复（同一批次处于多个未处置风险事项时的逐告警放行结论）
-- 业务基线: docs/BUSINESS_CONTRACT_V1.1.md §2.11 / §4.2 / §10.2 步骤 7 / §13 步骤 10 / §14
-- 说明:
--   1. alert_action 的 RELEASE_BATCH 表示“本告警依据关联本告警的合格检验报告，对该批次形成放行结论”。批次风险状态只有在
--      该结论解除了批次最后一个未解除的风险事项（其他未处置告警、尚未解除的人工风险冻结）时，才经风险核心 FROZEN → NORMAL，
--      此时 risk_transition_id 引用这次放行转换；其他风险事项仍未解除时只记录结论，risk_transition_id 为空，批次保持 FROZEN
--      （契约 §4.2：FROZEN 表示等待调查或质量结论；§13 步骤 10：调查合格时才恢复 NORMAL）。
--   2. 只放宽 chk_alert_action_shape：RELEASE_BATCH 仍必须有批次与依据检验报告，放行转换改为可空。
--      既有行全部满足新约束（放宽，不回填）；外键 fk_alert_action_transition（非空时仍必须引用来源于同一告警的转换）、
--      fk_alert_action_batch、fk_alert_action_report 与唯一约束 uk_alert_action_release（同一告警同一批次最多一个放行结论）不变。
--   3. 不修改 V1–V16 文件；不改动任何其他表。
-- =============================================================================

ALTER TABLE `alert_action`
    DROP CHECK `chk_alert_action_shape`;

ALTER TABLE `alert_action`
    ADD CONSTRAINT `chk_alert_action_shape` CHECK (
        (`action` IN ('ACKNOWLEDGE', 'RESOLVE')
            AND `batch_id` IS NULL AND `risk_transition_id` IS NULL AND `inspection_report_id` IS NULL) OR
        (`action` = 'RELEASE_BATCH'
            AND `batch_id` IS NOT NULL AND `inspection_report_id` IS NOT NULL)
    );
