-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V14
-- 任务编号: Phase B PB3 (持续超温 → Shipment 级 Alert → 受影响批次自动风险冻结)
-- 业务基线: docs/BUSINESS_CONTRACT_V1.1.md §2.11 / §4.2 / §10.1 / §10.2 步骤 2–4 / §13 步骤 2–4 / §14
-- 说明:
--   1. 前置条件 (fail-fast，不伪造任何业务数据)：V1 占位表 alert 必须为空 —— V1 以来没有任何应用代码写入该表，
--      存量行只可能来自手工 SQL，其缺少持续超温片段、判定依据快照与受影响批次等契约必需事实，不能被自动推断。
--   2. 以契约对象重建 alert（V1 占位形状为批次级可空批次 / 运输单、无约束类型与状态、无外键、可软删除）：
--      - 只承载 Shipment 级在途持续超温告警（TEMP_OVER_UPPER / TEMP_UNDER_LOWER），归属组织为运输任务发货方
--        （持续超温发生时受影响批次的当前责任组织）；复合外键保证 org_id 等于运输任务发货方；
--      - 持续超温片段：片段首条越界记录、达到允许时长的记录（复合外键保证二者属于同一运输任务）、业务时间与持续秒数；
--      - 判定依据快照：规则环节与上下限、允许越界时长，全部复制自片段温度记录的持久化快照，不随规则后续变化；
--      - 生命周期 OPEN → ACKNOWLEDGED → RESOLVED 的形状 CHECK（确认人即处置负责人）；告警从不删除（无 is_deleted）；
--      - UNIQUE (shipment_id, episode_start_record_id)：同一越界片段最多一条告警。
--   3. 新建 alert_batch：告警创建时经 Shipment → Transfer → Batch 确定的受影响批次快照（同一事务内写入），
--      记录快照时风险状态与自动冻结产生的风险转换（复合外键保证该转换来源于本告警）。
--   4. 新建 alert_action：告警人工处置动作追加式台账与幂等记录（PB3 只有 ACKNOWLEDGE）。
--   5. batch_risk_transition：新增类型化来源外键 source_alert_id 与 ALERT 来源；ALERT 来源在 PB3 只允许系统自动冻结
--      NORMAL → FROZEN（无操作人）。不回填任何历史行：存量行均为 MANUAL 且 source_alert_id 为 NULL，满足新 CHECK。
--   6. 本迁移不引入 QUARANTINED、InspectionReport、Recall 或任何 TraceEvent 结构；不修改 V1–V13 文件。
-- =============================================================================

-- 0. 前置条件校验：任一条件不满足时向 NOT NULL 列写入 NULL，迁移立即失败。
CREATE TEMPORARY TABLE `tmp_v14_precondition` (
    `check_name` VARCHAR(64) NOT NULL COMMENT '前置条件名称',
    `passed` CHAR(1) NOT NULL COMMENT '通过标记'
);
INSERT INTO `tmp_v14_precondition` (`check_name`, `passed`)
VALUES ('alert_placeholder_empty', IF((SELECT COUNT(*) FROM `alert`) = 0, 'Y', NULL));
DROP TEMPORARY TABLE `tmp_v14_precondition`;

-- 1. 复合外键目标唯一键
ALTER TABLE `shipment`
    ADD CONSTRAINT `uk_shipment_id_sender` UNIQUE (`id`, `sender_org_id`);

ALTER TABLE `temperature_record`
    ADD CONSTRAINT `uk_temp_id_shipment` UNIQUE (`id`, `shipment_id`);

-- 2. 以契约对象重建 alert（占位表已确认为空）
DROP TABLE `alert`;

CREATE TABLE `alert` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `alert_no` VARCHAR(64) NOT NULL COMMENT '告警业务编号(服务端生成)',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '归属组织ID(运输任务发货方，即受影响批次当时的责任组织)',
    `shipment_id` BIGINT UNSIGNED NOT NULL COMMENT '运输任务ID(Shipment 级告警)',
    `alert_type` VARCHAR(32) NOT NULL COMMENT '告警类型(TEMP_OVER_UPPER/TEMP_UNDER_LOWER)',
    `severity` VARCHAR(16) NOT NULL COMMENT '严重级别(LOW/MEDIUM/HIGH/CRITICAL)',
    `status` VARCHAR(16) NOT NULL COMMENT '状态(OPEN/ACKNOWLEDGED/RESOLVED)',
    `reason` VARCHAR(500) NOT NULL COMMENT '告警原因(服务端生成)',
    `stage_code` VARCHAR(16) NOT NULL COMMENT '业务环节(TRANSPORT)',
    `episode_start_record_id` BIGINT UNSIGNED NOT NULL COMMENT '越界片段首条温度记录ID',
    `sustained_record_id` BIGINT UNSIGNED NOT NULL COMMENT '达到允许越界时长的温度记录ID',
    `episode_started_at` DATETIME(6) NOT NULL COMMENT '越界片段开始业务时间(首条越界测量时间)',
    `sustained_at` DATETIME(6) NOT NULL COMMENT '达到持续超温条件的业务时间',
    `duration_seconds` INT UNSIGNED NOT NULL COMMENT '达到条件时已持续越界秒数',
    `rule_stage_id` BIGINT UNSIGNED NOT NULL COMMENT '判定依据：规则环节ID(快照)',
    `rule_lower_limit` DECIMAL(6,2) NOT NULL COMMENT '判定依据：温度下限(快照)',
    `rule_upper_limit` DECIMAL(6,2) NOT NULL COMMENT '判定依据：温度上限(快照)',
    `rule_allowed_duration_seconds` INT UNSIGNED NOT NULL COMMENT '判定依据：允许连续越界秒数(快照)',
    `triggered_at` DATETIME(6) NOT NULL COMMENT '告警创建UTC系统时间',
    `acknowledged_at` DATETIME(6) NULL COMMENT '确认UTC系统时间',
    `acknowledged_by` BIGINT UNSIGNED NULL COMMENT '确认人(处置负责人)用户ID',
    `resolved_at` DATETIME(6) NULL COMMENT '处置完成UTC系统时间',
    `resolved_by` BIGINT UNSIGNED NULL COMMENT '处置完成人用户ID',
    `resolution` VARCHAR(500) NULL COMMENT '处置结论',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_alert_no` (`alert_no`),
    UNIQUE KEY `uk_alert_shipment_episode` (`shipment_id`, `episode_start_record_id`),
    KEY `idx_alert_org_status` (`org_id`, `status`, `id`),
    CONSTRAINT `chk_alert_type` CHECK (`alert_type` IN ('TEMP_OVER_UPPER', 'TEMP_UNDER_LOWER')),
    CONSTRAINT `chk_alert_severity` CHECK (`severity` IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT `chk_alert_status` CHECK (`status` IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT `chk_alert_reason` CHECK (CHAR_LENGTH(TRIM(`reason`)) > 0),
    CONSTRAINT `chk_alert_stage` CHECK (`stage_code` = 'TRANSPORT'),
    CONSTRAINT `chk_alert_episode` CHECK (
        `sustained_at` >= `episode_started_at`
        AND `duration_seconds` >= `rule_allowed_duration_seconds`
        AND `rule_lower_limit` < `rule_upper_limit`
    ),
    CONSTRAINT `chk_alert_lifecycle_shape` CHECK (
        (`status` = 'OPEN'
            AND `acknowledged_at` IS NULL AND `acknowledged_by` IS NULL
            AND `resolved_at` IS NULL AND `resolved_by` IS NULL AND `resolution` IS NULL) OR
        (`status` = 'ACKNOWLEDGED'
            AND `acknowledged_at` IS NOT NULL AND `acknowledged_by` IS NOT NULL
            AND `resolved_at` IS NULL AND `resolved_by` IS NULL AND `resolution` IS NULL) OR
        (`status` = 'RESOLVED'
            AND `acknowledged_at` IS NOT NULL AND `acknowledged_by` IS NOT NULL
            AND `resolved_at` IS NOT NULL AND `resolved_by` IS NOT NULL
            AND `resolution` IS NOT NULL AND CHAR_LENGTH(TRIM(`resolution`)) > 0)
    ),
    CONSTRAINT `fk_alert_org` FOREIGN KEY (`org_id`) REFERENCES `organization` (`id`),
    CONSTRAINT `fk_alert_shipment_sender` FOREIGN KEY (`shipment_id`, `org_id`) REFERENCES `shipment` (`id`, `sender_org_id`),
    CONSTRAINT `fk_alert_episode_start` FOREIGN KEY (`episode_start_record_id`, `shipment_id`)
        REFERENCES `temperature_record` (`id`, `shipment_id`),
    CONSTRAINT `fk_alert_sustained_record` FOREIGN KEY (`sustained_record_id`, `shipment_id`)
        REFERENCES `temperature_record` (`id`, `shipment_id`),
    CONSTRAINT `fk_alert_rule_stage` FOREIGN KEY (`rule_stage_id`) REFERENCES `temperature_rule_stage` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Shipment 级在途持续超温告警(质量处置工作项)';

-- 3. 风险台账：类型化告警来源
ALTER TABLE `batch_risk_transition`
    ADD COLUMN `source_alert_id` BIGINT UNSIGNED NULL COMMENT '来源告警ID(source_type=ALERT 必填)' AFTER `source_type`,
    ADD CONSTRAINT `fk_brt_source_alert` FOREIGN KEY (`source_alert_id`) REFERENCES `alert` (`id`),
    ADD CONSTRAINT `uk_brt_id_source_alert` UNIQUE (`id`, `source_alert_id`);

ALTER TABLE `batch_risk_transition`
    DROP CHECK `chk_brt_source_type`,
    DROP CHECK `chk_brt_source_shape`;

ALTER TABLE `batch_risk_transition`
    ADD CONSTRAINT `chk_brt_source_type` CHECK (`source_type` IN ('MANUAL', 'ALERT')),
    ADD CONSTRAINT `chk_brt_source_shape` CHECK (
        (`source_type` = 'MANUAL' AND `actor_user_id` IS NOT NULL AND `source_alert_id` IS NULL) OR
        (`source_type` = 'ALERT' AND `source_alert_id` IS NOT NULL
            AND `actor_user_id` IS NULL AND `from_status` = 'NORMAL' AND `to_status` = 'FROZEN')
    );

-- 4. 受影响批次快照（告警创建事务内写入，经 Shipment → Transfer → Batch 确定）
CREATE TABLE `alert_batch` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `alert_id` BIGINT UNSIGNED NOT NULL COMMENT '告警ID',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '受影响批次ID',
    `transfer_id` BIGINT UNSIGNED NOT NULL COMMENT '确定受影响关系的交接ID',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '快照时批次责任组织ID',
    `risk_status_before` VARCHAR(16) NOT NULL COMMENT '快照时批次风险状态',
    `freeze_transition_id` BIGINT UNSIGNED NULL COMMENT '本告警自动冻结产生的风险转换ID(快照时 NORMAL 才有)',
    `created_at` DATETIME(6) NOT NULL COMMENT '快照UTC时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_alert_batch` (`alert_id`, `batch_id`),
    KEY `idx_alert_batch_batch` (`batch_id`, `alert_id`),
    CONSTRAINT `chk_alert_batch_risk_before` CHECK (`risk_status_before` IN ('NORMAL', 'FROZEN', 'RECALLED')),
    CONSTRAINT `chk_alert_batch_freeze_shape` CHECK (
        (`risk_status_before` = 'NORMAL' AND `freeze_transition_id` IS NOT NULL) OR
        (`risk_status_before` <> 'NORMAL' AND `freeze_transition_id` IS NULL)
    ),
    CONSTRAINT `fk_alert_batch_alert` FOREIGN KEY (`alert_id`) REFERENCES `alert` (`id`),
    CONSTRAINT `fk_alert_batch_batch` FOREIGN KEY (`batch_id`) REFERENCES `batch` (`id`),
    CONSTRAINT `fk_alert_batch_transfer` FOREIGN KEY (`transfer_id`) REFERENCES `transfer` (`id`),
    CONSTRAINT `fk_alert_batch_org` FOREIGN KEY (`org_id`) REFERENCES `organization` (`id`),
    CONSTRAINT `fk_alert_batch_freeze` FOREIGN KEY (`freeze_transition_id`, `alert_id`)
        REFERENCES `batch_risk_transition` (`id`, `source_alert_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='告警受影响批次快照';

-- 5. 告警人工处置动作追加式台账与幂等记录
CREATE TABLE `alert_action` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `alert_id` BIGINT UNSIGNED NOT NULL COMMENT '告警ID',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '操作组织ID',
    `action` VARCHAR(24) NOT NULL COMMENT '动作(ACKNOWLEDGE)',
    `actor_user_id` BIGINT UNSIGNED NOT NULL COMMENT '操作人用户ID',
    `note` VARCHAR(500) NULL COMMENT '说明',
    `idempotency_key` VARCHAR(128) NOT NULL COMMENT '客户端防重幂等键',
    `request_hash` CHAR(64) NOT NULL COMMENT '请求规范语义哈希',
    `occurred_at` DATETIME(6) NOT NULL COMMENT '动作生效UTC时间(服务端生成)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_alert_action_org_idempotency` (`org_id`, `idempotency_key`),
    KEY `idx_alert_action_alert` (`alert_id`, `id`),
    CONSTRAINT `chk_alert_action_action` CHECK (`action` IN ('ACKNOWLEDGE')),
    CONSTRAINT `chk_alert_action_note` CHECK (`note` IS NULL OR CHAR_LENGTH(TRIM(`note`)) > 0),
    CONSTRAINT `fk_alert_action_alert` FOREIGN KEY (`alert_id`) REFERENCES `alert` (`id`),
    CONSTRAINT `fk_alert_action_org` FOREIGN KEY (`org_id`) REFERENCES `organization` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='告警处置动作台账(追加式)';
