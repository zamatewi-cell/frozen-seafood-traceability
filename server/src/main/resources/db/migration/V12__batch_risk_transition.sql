-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V12
-- 任务编号: Phase B PB1 (风险状态核心：人工 FROZEN ⇄ NORMAL)
-- 业务基线: docs/BUSINESS_CONTRACT_V1.1.md §4.2 / §4.3 / §5.9 / §10.2 / §14，docs/DEMO_MVP_ROADMAP.md
-- 说明:
--   1. 只新建追加式批次风险状态转换台账 batch_risk_transition；不修改 batch，不回填任何历史数据，不修改 V1–V11。
--      batch.risk_status 的全部运行期变更必须在同一事务内写入且只写入一行本台账（应用层唯一写入口 BatchRiskService）。
--   2. 本迁移只允许 PB1 的人工转换：NORMAL → FROZEN（风险冻结）与 FROZEN → NORMAL（解除冻结），
--      来源类型只允许 MANUAL，且 MANUAL 必须有操作人。RECALLED 相关转换、ALERT / RECALL 来源及其类型化来源外键
--      由后续迁移与代码同时扩展（PB3：source_alert_id；PB5：source_recall_id），本迁移不预留多态 source_ref_id。
--   3. flow_status 为转换时的流转状态快照（只允许 ACTIVE / CLOSED）：风险转换从不改变流转状态，DRAFT 不参与风险转换。
--   4. org_id 为转换时批次当前责任组织：batch.org_id 在交接接受时会变化，因此只引用 organization，
--      "等于转换时 batch.org_id" 由应用层在持有批次行锁时保证（复合外键会阻断此后的交接接受）。
--   5. actor_user_id 不建外键：与 sale.created_by / audit_log.actor_user_id 一致，账号表不被业务台账外键引用。
--   6. 幂等：UNIQUE (org_id, idempotency_key) + request_hash（规范化请求语义哈希），冻结与解除共用同一键空间。
--   7. 追加式：不含 version / updated_* / is_deleted；应用层不提供任何 UPDATE / DELETE 语句。
--   8. 不引入温度、Alert、QUARANTINED、InspectionReport 或 Recall 结构，不引用 V1 占位表 alert / recall。
--   9. fail-fast：使用不带 IF NOT EXISTS 的 CREATE TABLE。若库中已存在同名表（形状未知或过期），迁移立即失败且
--      不会记录为成功，绝不静默接受一张结构不受本迁移控制的台账。
-- =============================================================================

CREATE TABLE `batch_risk_transition` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '批次ID',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '转换时批次当前责任组织ID',
    `flow_status` VARCHAR(16) NOT NULL COMMENT '转换时流转状态快照(ACTIVE/CLOSED，转换不改变流转状态)',
    `from_status` VARCHAR(16) NOT NULL COMMENT '转换前风险状态',
    `to_status` VARCHAR(16) NOT NULL COMMENT '转换后风险状态',
    `source_type` VARCHAR(16) NOT NULL COMMENT '转换来源类型(PB1 仅 MANUAL)',
    `actor_user_id` BIGINT UNSIGNED NULL COMMENT '人工转换操作人用户ID(MANUAL 必填)',
    `reason` VARCHAR(500) NOT NULL COMMENT '转换原因(去除首尾空白后非空)',
    `idempotency_key` VARCHAR(128) NOT NULL COMMENT '客户端防重幂等键',
    `request_hash` CHAR(64) NOT NULL COMMENT '请求规范语义哈希',
    `occurred_at` DATETIME(6) NOT NULL COMMENT '转换生效UTC时间(服务端生成)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '系统登记UTC时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_brt_org_idempotency` (`org_id`, `idempotency_key`),
    KEY `idx_brt_batch` (`batch_id`, `id`),
    CONSTRAINT `chk_brt_flow_status` CHECK (`flow_status` IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT `chk_brt_transition` CHECK (
        (`from_status` = 'NORMAL' AND `to_status` = 'FROZEN')
        OR (`from_status` = 'FROZEN' AND `to_status` = 'NORMAL')
    ),
    CONSTRAINT `chk_brt_source_type` CHECK (`source_type` IN ('MANUAL')),
    CONSTRAINT `chk_brt_source_shape` CHECK (`source_type` <> 'MANUAL' OR `actor_user_id` IS NOT NULL),
    CONSTRAINT `chk_brt_reason` CHECK (CHAR_LENGTH(TRIM(`reason`)) > 0),
    CONSTRAINT `fk_brt_batch` FOREIGN KEY (`batch_id`) REFERENCES `batch` (`id`),
    CONSTRAINT `fk_brt_org` FOREIGN KEY (`org_id`) REFERENCES `organization` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批次风险状态转换台账(追加式)';
