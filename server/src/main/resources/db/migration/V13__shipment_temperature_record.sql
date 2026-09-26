-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V13
-- 任务编号: Phase B PB2 (Shipment 在途温度记录：单点登记与单点判定)
-- 业务基线: docs/BUSINESS_CONTRACT_V1.1.md §2.9 / §10.1 / §10.2 步骤 1 / §13 步骤 1 / §14 / §15.9，
--           docs/DEMO_MVP_ROADMAP.md §2.2（Phase B：Shipment 温度记录），docs/adr/ADR-006-temperature-ingestion.md
-- 说明:
--   1. 前置条件 (fail-fast，不伪造任何业务数据)：temperature_record 表必须为空 —— V1 以来没有任何应用代码写入该表，
--      存量行只可能来自手工 SQL，缺少登记组织、操作人、幂等键与判定依据快照，不能被自动推断。前置条件在任何 DDL 之前校验，
--      不满足时迁移立即失败、不被记录为成功，且不改动任何表。
--   2. PB2 只收敛 Shipment 在途温度这一种形状：必须绑定 shipment_id、batch_id 必须为空、stage_code 固定为 TRANSPORT、
--      温标固定 CELSIUS、数据来源只允许 MANUAL / SIMULATED（IMPORT 为 ADR-006 的 P1，DEVICE 需真实设备接入与身份校验，均不开放）。
--      仓储 (Batch + Site) 温度不在 PB2 范围内，由后续迁移与代码同时扩展本表约束。
--   3. 登记组织 org_id 必须是运输任务的承运组织：复合外键 (shipment_id, org_id) → shipment(id, carrier_org_id)。
--   4. 判定依据快照：evaluation 取值 NORMAL / HIGH / LOW / MISSING_CONTEXT，删除 V1 的 DEFAULT 'NORMAL'（默认值会伪造合规结论）。
--      stage_code 始终非空且固定为 TRANSPORT；MISSING_CONTEXT 时 rule_stage_id、rule_lower_limit、rule_upper_limit、
--      rule_allowed_duration_seconds 四者均为空；NORMAL / HIGH / LOW 时四者均非空，且 evaluation 必须与快照上下限一致
--      （上下限本身属于范围内）。rule_allowed_duration_seconds 是登记时匹配环节允许越界时长的历史快照（与
--      temperature_rule_stage.allowed_duration_seconds 同类型）：规则环节没有数据库级不可变约束，后续持续超温判定必须使用
--      本快照，不得重新读取可能已被改动的规则环节；rule_stage_id 外键只作为来源追溯。
--      复合外键 (rule_stage_id, stage_code) → temperature_rule_stage(id, stage_code) 保证匹配到的规则环节确为 TRANSPORT。
--      单点判定只说明一次测量是否落在当时适用规则范围内，不等于持续超温（契约 §10.1 / §15.9）。
--   5. 幂等：UNIQUE (org_id, idempotency_key) + request_hash（规范化请求语义哈希）。
--      契约未把 (shipment_id, measured_at) 定义为身份或唯一性规则：同一时间点可以存在多条测量（不同探头 / 来源），
--      因此不建立测量时间唯一约束，列表按 (measured_at, id) 稳定排序。
--   6. 追加式：is_deleted 固定为 0；应用层不提供任何 UPDATE / DELETE 语句，登记后判定结果不追溯改写（ADR-006）。
--   7. measured_at 为 DATETIME(6)：应用层先把请求时间规范化为 UTC 微秒精度，再用于哈希、校验、规则匹配与持久化；
--      measured_at 不得晚于系统登记时间 recorded_at 超过 5 分钟（服务端时钟偏差容忍）。
--   8. shipment 仅新增复合外键目标唯一键 (id, carrier_org_id)，temperature_rule_stage 仅新增 (id, stage_code)；二者由主键
--      天然唯一，不改变任何既有数据与行为。同一运输任务只装载同一产品属于 Demo MVP 应用层限制，不写入数据库永久约束。
--   9. 本迁移不引入 Alert、持续超温判定、QUARANTINED、InspectionReport 或 Recall 结构，不修改 V1–V12。
-- =============================================================================

-- 0. 前置条件校验：任一条件不满足时向 NOT NULL 列写入 NULL，迁移立即失败（在任何 DDL 之前）。
CREATE TEMPORARY TABLE `tmp_v13_precondition` (
    `check_name` VARCHAR(64) NOT NULL COMMENT '前置条件名称',
    `passed` CHAR(1) NOT NULL COMMENT '通过标记'
);
INSERT INTO `tmp_v13_precondition` (`check_name`, `passed`)
VALUES ('temperature_record_empty', IF((SELECT COUNT(*) FROM `temperature_record`) = 0, 'Y', NULL));
DROP TEMPORARY TABLE `tmp_v13_precondition`;

-- 1. 复合外键目标唯一键（主键天然唯一，不改变数据）
ALTER TABLE `shipment`
    ADD CONSTRAINT `uk_shipment_id_carrier` UNIQUE (`id`, `carrier_org_id`);

ALTER TABLE `temperature_rule_stage`
    ADD CONSTRAINT `uk_stage_id_stage_code` UNIQUE (`id`, `stage_code`);

-- 2. temperature_record：登记组织 / 操作人、判定依据快照、幂等；删除伪造合规结论的默认值
ALTER TABLE `temperature_record`
    ADD COLUMN `org_id` BIGINT UNSIGNED NOT NULL COMMENT '登记组织ID(运输任务承运组织)' AFTER `shipment_id`,
    ADD COLUMN `actor_user_id` BIGINT UNSIGNED NOT NULL COMMENT '登记操作人用户ID' AFTER `org_id`,
    MODIFY COLUMN `stage_code` VARCHAR(32) NOT NULL COMMENT '环节代码(PB2 固定 TRANSPORT)',
    MODIFY COLUMN `measured_at` DATETIME(6) NOT NULL COMMENT '测量业务UTC时间(微秒精度)',
    MODIFY COLUMN `recorded_at` DATETIME(6) NOT NULL COMMENT '系统登记UTC时间(服务端生成)',
    MODIFY COLUMN `data_source` VARCHAR(16) NOT NULL COMMENT '数据来源(PB2 仅 MANUAL/SIMULATED)',
    MODIFY COLUMN `evaluation` VARCHAR(16) NOT NULL COMMENT '单点判定(NORMAL/HIGH/LOW/MISSING_CONTEXT，不等于持续超温)',
    ADD COLUMN `rule_lower_limit` DECIMAL(6,2) NULL COMMENT '判定依据快照：匹配规则环节温度下限' AFTER `rule_stage_id`,
    ADD COLUMN `rule_upper_limit` DECIMAL(6,2) NULL COMMENT '判定依据快照：匹配规则环节温度上限' AFTER `rule_lower_limit`,
    ADD COLUMN `rule_allowed_duration_seconds` INT UNSIGNED NULL COMMENT '判定依据快照：匹配规则环节允许越界时长(秒)' AFTER `rule_upper_limit`,
    ADD COLUMN `idempotency_key` VARCHAR(128) NOT NULL COMMENT '客户端防重幂等键' AFTER `evaluation`,
    ADD COLUMN `request_hash` CHAR(64) NOT NULL COMMENT '请求规范语义哈希' AFTER `idempotency_key`;

-- 3. temperature_record：唯一键、形状 CHECK 与外键
ALTER TABLE `temperature_record`
    ADD CONSTRAINT `uk_temp_org_idempotency` UNIQUE (`org_id`, `idempotency_key`),
    ADD CONSTRAINT `chk_temp_binding` CHECK (`shipment_id` IS NOT NULL AND `batch_id` IS NULL),
    ADD CONSTRAINT `chk_temp_stage` CHECK (`stage_code` = 'TRANSPORT'),
    ADD CONSTRAINT `chk_temp_unit` CHECK (`unit_code` = 'CELSIUS'),
    ADD CONSTRAINT `chk_temp_source` CHECK (`data_source` IN ('MANUAL', 'SIMULATED')),
    ADD CONSTRAINT `chk_temp_range` CHECK (`temperature` >= -80.00 AND `temperature` <= 60.00),
    ADD CONSTRAINT `chk_temp_device_no` CHECK (`device_no` IS NULL OR CHAR_LENGTH(TRIM(`device_no`)) > 0),
    ADD CONSTRAINT `chk_temp_time` CHECK (`measured_at` <= `recorded_at` + INTERVAL 5 MINUTE),
    ADD CONSTRAINT `chk_temp_not_deleted` CHECK (`is_deleted` = 0),
    ADD CONSTRAINT `chk_temp_evaluation` CHECK (`evaluation` IN ('NORMAL', 'HIGH', 'LOW', 'MISSING_CONTEXT')),
    ADD CONSTRAINT `chk_temp_evaluation_shape` CHECK (
        (`evaluation` = 'MISSING_CONTEXT'
            AND `rule_stage_id` IS NULL AND `rule_lower_limit` IS NULL AND `rule_upper_limit` IS NULL
            AND `rule_allowed_duration_seconds` IS NULL)
        OR (`evaluation` IN ('NORMAL', 'HIGH', 'LOW')
            AND `rule_stage_id` IS NOT NULL AND `rule_lower_limit` IS NOT NULL AND `rule_upper_limit` IS NOT NULL
            AND `rule_allowed_duration_seconds` IS NOT NULL
            AND `rule_lower_limit` <= `rule_upper_limit`
            AND ((`evaluation` = 'NORMAL' AND `temperature` >= `rule_lower_limit` AND `temperature` <= `rule_upper_limit`)
                 OR (`evaluation` = 'HIGH' AND `temperature` > `rule_upper_limit`)
                 OR (`evaluation` = 'LOW' AND `temperature` < `rule_lower_limit`)))
    ),
    ADD CONSTRAINT `fk_temp_shipment_carrier` FOREIGN KEY (`shipment_id`, `org_id`) REFERENCES `shipment` (`id`, `carrier_org_id`),
    ADD CONSTRAINT `fk_temp_org` FOREIGN KEY (`org_id`) REFERENCES `organization` (`id`),
    ADD CONSTRAINT `fk_temp_rule_stage` FOREIGN KEY (`rule_stage_id`, `stage_code`) REFERENCES `temperature_rule_stage` (`id`, `stage_code`);
