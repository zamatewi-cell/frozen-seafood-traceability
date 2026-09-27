-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V15
-- 任务编号: Phase B PB4 (QUARANTINED 隔离收货、InspectionReport、质量结论放行与告警处置结论)
-- 业务基线: docs/BUSINESS_CONTRACT_V1.1.md §2.10 / §2.11 / §4.2 / §7.3 / §10.2 步骤 5–7 / §10.3 / §13 步骤 5–11 / §14
-- 说明:
--   1. 前置条件 (fail-fast，不伪造任何业务数据)：V1 占位表 inspection_report 必须为空 —— V1 以来没有任何应用代码写入该表，
--      存量行缺少提交身份、幂等与告警关联等契约事实，且其 institution_verified 暗示本系统核验机构真实性（契约 §2.10 明确不负责），
--      不能被自动推断。
--   2. transfer：
--      - 新增隔离收货事实：隔离场所（复合外键保证属于接收方）、隔离原因、隔离登记时间与操作人；实收数量与到货时间沿用既有列；
--      - 状态增加 QUARANTINED；未结束交接排他（open_batch_id）同时覆盖 QUARANTINED：隔离期间批次不能再发起交接；
--      - 重建生命周期形状 CHECK：QUARANTINED 必须有到货时间、实收数量（差异须有原因）与全部隔离事实、尚无接收决定；
--        ACCEPTED / REJECTED 的隔离事实要么全空（直接决定）要么全有（经隔离后决定），经隔离后拒收保留实收数量；
--      - 新增 (id, batch_id, receiver_org_id) 唯一键，供检验报告以复合外键证明"隔离接收方为该批次提交证据"。
--   3. 以契约对象重建 inspection_report（占位表已确认为空）：针对 Batch 的结构化检验报告，追加式；提交身份为批次当前责任组织
--      （CURRENT_ORG）或该批次隔离交接的接收方（QUARANTINE_RECEIVER，复合外键）；可关联告警（复合外键保证批次属于该告警的受影响批次）；
--      结论只有 PASS / FAIL；不含机构资质核验字段；组织内报告编号唯一与幂等键唯一。
--   4. alert_action：新增 RELEASE_BATCH（质量结论放行某个受影响批次，必须引用来源于本告警的放行风险转换与该批次的检验报告）
--      与 RESOLVE（处置结论）；同一告警同一批次最多放行一次。
--   5. batch_risk_transition：ALERT 来源增加质量管理员依据告警放行 FROZEN → NORMAL（必须有操作人）。
--   6. 不回填任何历史行（既有交接的隔离事实为空，满足新形状）；不引入 Recall 结构；不修改 V1–V14 文件。
-- =============================================================================

-- 0. 前置条件校验：任一条件不满足时向 NOT NULL 列写入 NULL，迁移立即失败。
CREATE TEMPORARY TABLE `tmp_v15_precondition` (
    `check_name` VARCHAR(64) NOT NULL COMMENT '前置条件名称',
    `passed` CHAR(1) NOT NULL COMMENT '通过标记'
);
INSERT INTO `tmp_v15_precondition` (`check_name`, `passed`)
VALUES ('inspection_report_placeholder_empty', IF((SELECT COUNT(*) FROM `inspection_report`) = 0, 'Y', NULL));
DROP TEMPORARY TABLE `tmp_v15_precondition`;

-- 1. transfer：隔离收货事实与复合外键目标唯一键
ALTER TABLE `transfer`
    ADD COLUMN `quarantine_site_id` BIGINT UNSIGNED NULL COMMENT '隔离场所ID(属于接收方)' AFTER `rejection_reason`,
    ADD COLUMN `quarantine_reason` VARCHAR(500) NULL COMMENT '隔离收货原因' AFTER `quarantine_site_id`,
    ADD COLUMN `quarantined_recorded_at` DATETIME(6) NULL COMMENT '隔离收货系统登记时间' AFTER `quarantine_reason`,
    ADD COLUMN `quarantined_by` BIGINT UNSIGNED NULL COMMENT '隔离收货操作人用户ID' AFTER `quarantined_recorded_at`,
    ADD CONSTRAINT `fk_transfer_quarantine_site` FOREIGN KEY (`quarantine_site_id`, `receiver_org_id`) REFERENCES `site` (`id`, `org_id`),
    ADD CONSTRAINT `uk_transfer_id_batch_receiver` UNIQUE (`id`, `batch_id`, `receiver_org_id`);

-- 2. transfer：状态与未结束交接排他覆盖 QUARANTINED
ALTER TABLE `transfer`
    DROP CHECK `chk_transfer_status`;

ALTER TABLE `transfer`
    ADD CONSTRAINT `chk_transfer_status` CHECK (`status` IN ('DRAFT', 'PENDING', 'QUARANTINED', 'ACCEPTED', 'REJECTED')),
    MODIFY COLUMN `open_batch_id` BIGINT UNSIGNED GENERATED ALWAYS AS (
        CASE
            WHEN `is_legacy` = 0 AND `status` IN ('DRAFT', 'PENDING', 'QUARANTINED') AND `is_deleted` = 0 THEN `batch_id`
            ELSE NULL
        END
    ) STORED COMMENT '未结束交接排他批次ID(DRAFT/PENDING/QUARANTINED)';

-- 3. transfer：重建生命周期形状 CHECK
ALTER TABLE `transfer`
    DROP CHECK `chk_transfer_decision_shape`;

ALTER TABLE `transfer`
    ADD CONSTRAINT `chk_transfer_decision_shape` CHECK (
        `is_legacy` = 1 OR (
            (`status` = 'DRAFT'
                AND `shipped_at` IS NULL AND `submitted_recorded_at` IS NULL AND `submitted_by` IS NULL
                AND `received_at` IS NULL AND `decision_recorded_at` IS NULL AND `decided_by` IS NULL
                AND `received_quantity` IS NULL AND `difference_reason` IS NULL AND `rejection_reason` IS NULL
                AND `quarantine_site_id` IS NULL AND `quarantine_reason` IS NULL
                AND `quarantined_recorded_at` IS NULL AND `quarantined_by` IS NULL) OR
            (`status` = 'PENDING'
                AND `shipment_id` IS NOT NULL
                AND `shipped_at` IS NULL AND `submitted_recorded_at` IS NOT NULL AND `submitted_by` IS NOT NULL
                AND `received_at` IS NULL AND `decision_recorded_at` IS NULL AND `decided_by` IS NULL
                AND `received_quantity` IS NULL AND `difference_reason` IS NULL AND `rejection_reason` IS NULL
                AND `quarantine_site_id` IS NULL AND `quarantine_reason` IS NULL
                AND `quarantined_recorded_at` IS NULL AND `quarantined_by` IS NULL) OR
            (`status` = 'QUARANTINED'
                AND `shipment_id` IS NOT NULL
                AND `shipped_at` IS NULL AND `submitted_recorded_at` IS NOT NULL AND `submitted_by` IS NOT NULL
                AND `received_at` IS NOT NULL AND `decision_recorded_at` IS NULL AND `decided_by` IS NULL
                AND `received_quantity` IS NOT NULL AND `received_quantity` > 0 AND `rejection_reason` IS NULL
                AND ((`received_quantity` = `quantity` AND `difference_reason` IS NULL)
                     OR (`received_quantity` <> `quantity` AND `difference_reason` IS NOT NULL))
                AND `quarantine_site_id` IS NOT NULL AND `quarantine_reason` IS NOT NULL
                AND CHAR_LENGTH(TRIM(`quarantine_reason`)) > 0
                AND `quarantined_recorded_at` IS NOT NULL AND `quarantined_by` IS NOT NULL) OR
            (`status` = 'ACCEPTED'
                AND `shipment_id` IS NOT NULL
                AND `shipped_at` IS NULL AND `submitted_recorded_at` IS NOT NULL AND `submitted_by` IS NOT NULL
                AND `received_at` IS NOT NULL AND `decision_recorded_at` IS NOT NULL AND `decided_by` IS NOT NULL
                AND `received_quantity` IS NOT NULL AND `received_quantity` > 0 AND `rejection_reason` IS NULL
                AND ((`received_quantity` = `quantity` AND `difference_reason` IS NULL)
                     OR (`received_quantity` <> `quantity` AND `difference_reason` IS NOT NULL))
                AND ((`quarantine_site_id` IS NULL AND `quarantine_reason` IS NULL
                        AND `quarantined_recorded_at` IS NULL AND `quarantined_by` IS NULL)
                     OR (`quarantine_site_id` IS NOT NULL AND `quarantine_reason` IS NOT NULL
                        AND `quarantined_recorded_at` IS NOT NULL AND `quarantined_by` IS NOT NULL))) OR
            (`status` = 'REJECTED'
                AND `shipment_id` IS NOT NULL
                AND `shipped_at` IS NULL AND `submitted_recorded_at` IS NOT NULL AND `submitted_by` IS NOT NULL
                AND `received_at` IS NOT NULL AND `decision_recorded_at` IS NOT NULL AND `decided_by` IS NOT NULL
                AND `rejection_reason` IS NOT NULL
                AND ((`quarantine_site_id` IS NULL AND `quarantine_reason` IS NULL
                        AND `quarantined_recorded_at` IS NULL AND `quarantined_by` IS NULL
                        AND `received_quantity` IS NULL AND `difference_reason` IS NULL)
                     OR (`quarantine_site_id` IS NOT NULL AND `quarantine_reason` IS NOT NULL
                        AND `quarantined_recorded_at` IS NOT NULL AND `quarantined_by` IS NOT NULL
                        AND `received_quantity` IS NOT NULL AND `received_quantity` > 0
                        AND ((`received_quantity` = `quantity` AND `difference_reason` IS NULL)
                             OR (`received_quantity` <> `quantity` AND `difference_reason` IS NOT NULL)))))
        )
    );

-- 4. 以契约对象重建 inspection_report（占位表已确认为空）
DROP TABLE `inspection_report`;

CREATE TABLE `inspection_report` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '被检验批次ID',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '提交组织ID',
    `submitter_role` VARCHAR(24) NOT NULL COMMENT '提交身份(CURRENT_ORG:批次当前责任组织/QUARANTINE_RECEIVER:隔离交接接收方)',
    `transfer_id` BIGINT UNSIGNED NULL COMMENT '隔离交接ID(QUARANTINE_RECEIVER 必填)',
    `alert_id` BIGINT UNSIGNED NULL COMMENT '关联告警ID(可选，批次必须是该告警的受影响批次)',
    `report_no` VARCHAR(64) NOT NULL COMMENT '检验报告编号(组织内唯一)',
    `institution_name` VARCHAR(128) NOT NULL COMMENT '检验检测机构名称(本系统不核验其资质与真实性)',
    `inspected_at` DATETIME(6) NOT NULL COMMENT '检验完成业务时间',
    `items_summary` VARCHAR(500) NOT NULL COMMENT '检测项目摘要',
    `conclusion` VARCHAR(16) NOT NULL COMMENT '检验结论(PASS/FAIL)',
    `data_source` VARCHAR(16) NOT NULL COMMENT '数据来源(MANUAL/SIMULATED)',
    `actor_user_id` BIGINT UNSIGNED NOT NULL COMMENT '提交人用户ID',
    `idempotency_key` VARCHAR(128) NOT NULL COMMENT '客户端防重幂等键',
    `request_hash` CHAR(64) NOT NULL COMMENT '请求规范语义哈希',
    `recorded_at` DATETIME(6) NOT NULL COMMENT '系统登记UTC时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_inspection_org_report_no` (`org_id`, `report_no`),
    UNIQUE KEY `uk_inspection_org_idempotency` (`org_id`, `idempotency_key`),
    UNIQUE KEY `uk_inspection_id_batch` (`id`, `batch_id`),
    KEY `idx_inspection_batch` (`batch_id`, `id`),
    KEY `idx_inspection_alert_batch` (`alert_id`, `batch_id`, `id`),
    CONSTRAINT `chk_inspection_submitter_role` CHECK (
        (`submitter_role` = 'CURRENT_ORG' AND `transfer_id` IS NULL) OR
        (`submitter_role` = 'QUARANTINE_RECEIVER' AND `transfer_id` IS NOT NULL)
    ),
    CONSTRAINT `chk_inspection_conclusion` CHECK (`conclusion` IN ('PASS', 'FAIL')),
    CONSTRAINT `chk_inspection_source` CHECK (`data_source` IN ('MANUAL', 'SIMULATED')),
    CONSTRAINT `chk_inspection_text` CHECK (
        CHAR_LENGTH(TRIM(`report_no`)) > 0 AND CHAR_LENGTH(TRIM(`institution_name`)) > 0
        AND CHAR_LENGTH(TRIM(`items_summary`)) > 0
    ),
    CONSTRAINT `chk_inspection_time` CHECK (`inspected_at` <= `recorded_at` + INTERVAL 5 MINUTE),
    CONSTRAINT `fk_inspection_batch` FOREIGN KEY (`batch_id`) REFERENCES `batch` (`id`),
    CONSTRAINT `fk_inspection_org` FOREIGN KEY (`org_id`) REFERENCES `organization` (`id`),
    CONSTRAINT `fk_inspection_quarantine_transfer` FOREIGN KEY (`transfer_id`, `batch_id`, `org_id`)
        REFERENCES `transfer` (`id`, `batch_id`, `receiver_org_id`),
    CONSTRAINT `fk_inspection_alert_batch` FOREIGN KEY (`alert_id`, `batch_id`) REFERENCES `alert_batch` (`alert_id`, `batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批次检验报告(追加式，教学演示证据)';

-- 5. alert_action：放行与处置结论
ALTER TABLE `alert_action`
    ADD COLUMN `batch_id` BIGINT UNSIGNED NULL COMMENT '放行批次ID(RELEASE_BATCH)' AFTER `action`,
    ADD COLUMN `risk_transition_id` BIGINT UNSIGNED NULL COMMENT '放行风险转换ID(RELEASE_BATCH)' AFTER `batch_id`,
    ADD COLUMN `inspection_report_id` BIGINT UNSIGNED NULL COMMENT '放行依据检验报告ID(RELEASE_BATCH)' AFTER `risk_transition_id`,
    ADD COLUMN `released_batch_id` BIGINT UNSIGNED GENERATED ALWAYS AS (
        CASE WHEN `action` = 'RELEASE_BATCH' THEN `batch_id` ELSE NULL END
    ) STORED COMMENT '同一告警同一批次最多放行一次' AFTER `inspection_report_id`,
    ADD CONSTRAINT `uk_alert_action_release` UNIQUE (`alert_id`, `released_batch_id`),
    ADD CONSTRAINT `fk_alert_action_batch` FOREIGN KEY (`alert_id`, `batch_id`) REFERENCES `alert_batch` (`alert_id`, `batch_id`),
    ADD CONSTRAINT `fk_alert_action_transition` FOREIGN KEY (`risk_transition_id`, `alert_id`)
        REFERENCES `batch_risk_transition` (`id`, `source_alert_id`),
    ADD CONSTRAINT `fk_alert_action_report` FOREIGN KEY (`inspection_report_id`, `batch_id`) REFERENCES `inspection_report` (`id`, `batch_id`);

ALTER TABLE `alert_action`
    DROP CHECK `chk_alert_action_action`;

ALTER TABLE `alert_action`
    ADD CONSTRAINT `chk_alert_action_action` CHECK (`action` IN ('ACKNOWLEDGE', 'RELEASE_BATCH', 'RESOLVE')),
    ADD CONSTRAINT `chk_alert_action_shape` CHECK (
        (`action` IN ('ACKNOWLEDGE', 'RESOLVE')
            AND `batch_id` IS NULL AND `risk_transition_id` IS NULL AND `inspection_report_id` IS NULL) OR
        (`action` = 'RELEASE_BATCH'
            AND `batch_id` IS NOT NULL AND `risk_transition_id` IS NOT NULL AND `inspection_report_id` IS NOT NULL)
    );

-- 6. batch_risk_transition：告警质量结论放行（FROZEN → NORMAL，必须有操作人）
ALTER TABLE `batch_risk_transition`
    DROP CHECK `chk_brt_source_shape`;

ALTER TABLE `batch_risk_transition`
    ADD CONSTRAINT `chk_brt_source_shape` CHECK (
        (`source_type` = 'MANUAL' AND `actor_user_id` IS NOT NULL AND `source_alert_id` IS NULL) OR
        (`source_type` = 'ALERT' AND `source_alert_id` IS NOT NULL AND (
            (`actor_user_id` IS NULL AND `from_status` = 'NORMAL' AND `to_status` = 'FROZEN') OR
            (`actor_user_id` IS NOT NULL AND `from_status` = 'FROZEN' AND `to_status` = 'NORMAL')))
    );
