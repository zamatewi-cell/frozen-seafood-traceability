-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V16
-- 任务编号: Phase B PB5 (模拟召回：正反向影响范围、RECALLED 风险终态、召回关闭)
-- 业务基线: docs/BUSINESS_CONTRACT_V1.1.md §2.12 / §4.2 / §4.3 / §5 / §9.2 / §10.2 步骤 7 / §13 步骤 8–14 / §14 / §15
-- 说明:
--   1. 前置条件 (fail-fast，不伪造任何业务数据)：V1 占位表 recall 与 recall_batch 必须为空 —— V1 以来没有任何应用代码写入，
--      存量行缺少召回发起人、影响范围事实与风险转换关联等契约事实，不能被自动推断；也不存在以 RECALLED 结束的风险台账行。
--   2. 以契约对象重建 recall：由当前责任组织的质量管理员发起的模拟召回案件（不表示真实法定召回）；生命周期 IN_PROGRESS → CLOSED，
--      关闭时必须给出受控的公开处置结论（DESTROYED / RETURNED，消费者页面只显示固定文案）与内部处置总结；
--      可引用来源告警；组织内发起 / 关闭幂等键唯一；召回从不删除。
--   3. 以契约对象重建 recall_batch：召回影响范围快照（创建事务内写入）——
--      SEED（发起组织持有、本次转为 RECALLED 的批次）、DESCENDANT（正向谱系后续批次：发起组织持有的同时转为 RECALLED 或已是 RECALLED，
--      其他组织持有的只通知持有方）、ANCESTOR（反向谱系上游批次，只用于溯源调查），并记录快照时的持有组织、流转 / 风险状态、
--      声明 / 剩余 / 已售数量、未结束交接与运输状态、公开追溯码是否启用；RECALLED 行经复合外键必须引用来源于本召回的风险转换。
--      open_transfer_id 是同一事务内读取的快照引用，刻意不建外键：外键检查会在召回持有批次行锁之后对交接行加共享锁，
--      与交接接受的锁顺序 shipment → transfer → batch 相反，并发时必然死锁。
--   4. batch_risk_transition：新增类型化来源外键 source_recall_id 与 RECALL 来源；转换对增加 NORMAL / FROZEN → RECALLED，
--      且只有 RECALL 来源（必须有操作人）可以转为 RECALLED；RECALLED 为风险终态，不存在从 RECALLED 出发的转换。
--   5. public_trace_code 不变：公开页面的模拟召回提示只由批次风险状态决定，不写码状态 RECALLED。
--   6. 不回填；不修改 V1–V15 文件。
-- =============================================================================

-- 0. 前置条件校验：任一条件不满足时向 NOT NULL 列写入 NULL，迁移立即失败。
CREATE TEMPORARY TABLE `tmp_v16_precondition` (
    `check_name` VARCHAR(64) NOT NULL COMMENT '前置条件名称',
    `passed` CHAR(1) NOT NULL COMMENT '通过标记'
);
INSERT INTO `tmp_v16_precondition` (`check_name`, `passed`)
VALUES ('recall_placeholder_empty', IF((SELECT COUNT(*) FROM `recall`) = 0, 'Y', NULL));
INSERT INTO `tmp_v16_precondition` (`check_name`, `passed`)
VALUES ('recall_batch_placeholder_empty', IF((SELECT COUNT(*) FROM `recall_batch`) = 0, 'Y', NULL));
DROP TEMPORARY TABLE `tmp_v16_precondition`;

-- 1. 以契约对象重建 recall / recall_batch（占位表已确认为空；recall_batch 先删）
DROP TABLE `recall_batch`;
DROP TABLE `recall`;

CREATE TABLE `recall` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `recall_no` VARCHAR(64) NOT NULL COMMENT '模拟召回案件编号(服务端生成)',
    `owner_org_id` BIGINT UNSIGNED NOT NULL COMMENT '发起组织ID(被召回批次的当前责任组织)',
    `source_alert_id` BIGINT UNSIGNED NULL COMMENT '来源告警ID(可选)',
    `reason` VARCHAR(500) NOT NULL COMMENT '发起原因',
    `status` VARCHAR(16) NOT NULL COMMENT '状态(IN_PROGRESS/CLOSED)',
    `started_at` DATETIME(6) NOT NULL COMMENT '发起UTC时间',
    `started_by` BIGINT UNSIGNED NOT NULL COMMENT '发起人(质量管理员)用户ID',
    `closed_at` DATETIME(6) NULL COMMENT '关闭UTC时间',
    `closed_by` BIGINT UNSIGNED NULL COMMENT '关闭人用户ID',
    `public_disposition` VARCHAR(16) NULL COMMENT '公开处置结论(DESTROYED/RETURNED，消费者页面显示固定文案)',
    `result_summary` VARCHAR(1000) NULL COMMENT '内部处置总结(不公开)',
    `idempotency_key` VARCHAR(128) NOT NULL COMMENT '发起幂等键',
    `request_hash` CHAR(64) NOT NULL COMMENT '发起请求规范语义哈希',
    `close_idempotency_key` VARCHAR(128) NULL COMMENT '关闭幂等键',
    `close_request_hash` CHAR(64) NULL COMMENT '关闭请求规范语义哈希',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_recall_no` (`recall_no`),
    UNIQUE KEY `uk_recall_org_idempotency` (`owner_org_id`, `idempotency_key`),
    UNIQUE KEY `uk_recall_org_close_idempotency` (`owner_org_id`, `close_idempotency_key`),
    KEY `idx_recall_org_status` (`owner_org_id`, `status`, `id`),
    CONSTRAINT `chk_recall_status` CHECK (`status` IN ('IN_PROGRESS', 'CLOSED')),
    CONSTRAINT `chk_recall_reason` CHECK (CHAR_LENGTH(TRIM(`reason`)) > 0),
    CONSTRAINT `chk_recall_lifecycle_shape` CHECK (
        (`status` = 'IN_PROGRESS'
            AND `closed_at` IS NULL AND `closed_by` IS NULL AND `public_disposition` IS NULL AND `result_summary` IS NULL
            AND `close_idempotency_key` IS NULL AND `close_request_hash` IS NULL) OR
        (`status` = 'CLOSED'
            AND `closed_at` IS NOT NULL AND `closed_at` >= `started_at` AND `closed_by` IS NOT NULL
            AND `public_disposition` IN ('DESTROYED', 'RETURNED')
            AND `result_summary` IS NOT NULL AND CHAR_LENGTH(TRIM(`result_summary`)) > 0
            AND `close_idempotency_key` IS NOT NULL AND `close_request_hash` IS NOT NULL)
    ),
    CONSTRAINT `fk_recall_owner_org` FOREIGN KEY (`owner_org_id`) REFERENCES `organization` (`id`),
    CONSTRAINT `fk_recall_source_alert` FOREIGN KEY (`source_alert_id`) REFERENCES `alert` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模拟召回案件(教学演练，不表示真实法定召回)';

-- 2. 风险台账：类型化召回来源与 RECALLED 终态
ALTER TABLE `batch_risk_transition`
    ADD COLUMN `source_recall_id` BIGINT UNSIGNED NULL COMMENT '来源召回ID(source_type=RECALL 必填)' AFTER `source_alert_id`,
    ADD CONSTRAINT `fk_brt_source_recall` FOREIGN KEY (`source_recall_id`) REFERENCES `recall` (`id`),
    ADD CONSTRAINT `uk_brt_id_source_recall` UNIQUE (`id`, `source_recall_id`);

ALTER TABLE `batch_risk_transition`
    DROP CHECK `chk_brt_transition`,
    DROP CHECK `chk_brt_source_type`,
    DROP CHECK `chk_brt_source_shape`;

ALTER TABLE `batch_risk_transition`
    ADD CONSTRAINT `chk_brt_transition` CHECK (
        (`from_status` = 'NORMAL' AND `to_status` = 'FROZEN')
        OR (`from_status` = 'FROZEN' AND `to_status` = 'NORMAL')
        OR (`from_status` = 'NORMAL' AND `to_status` = 'RECALLED')
        OR (`from_status` = 'FROZEN' AND `to_status` = 'RECALLED')
    ),
    ADD CONSTRAINT `chk_brt_source_type` CHECK (`source_type` IN ('MANUAL', 'ALERT', 'RECALL')),
    ADD CONSTRAINT `chk_brt_source_shape` CHECK (
        (`source_type` = 'MANUAL' AND `actor_user_id` IS NOT NULL AND `source_alert_id` IS NULL AND `source_recall_id` IS NULL
            AND `to_status` <> 'RECALLED') OR
        (`source_type` = 'ALERT' AND `source_alert_id` IS NOT NULL AND `source_recall_id` IS NULL AND (
            (`actor_user_id` IS NULL AND `from_status` = 'NORMAL' AND `to_status` = 'FROZEN') OR
            (`actor_user_id` IS NOT NULL AND `from_status` = 'FROZEN' AND `to_status` = 'NORMAL'))) OR
        (`source_type` = 'RECALL' AND `source_recall_id` IS NOT NULL AND `source_alert_id` IS NULL
            AND `actor_user_id` IS NOT NULL AND `to_status` = 'RECALLED')
    );

-- 3. 召回影响范围快照
CREATE TABLE `recall_batch` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `recall_id` BIGINT UNSIGNED NOT NULL COMMENT '召回ID',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '范围批次ID',
    `scope_role` VARCHAR(16) NOT NULL COMMENT '范围角色(SEED/DESCENDANT/ANCESTOR)',
    `depth` INT NOT NULL COMMENT '谱系距离(SEED 0，DESCENDANT 为正，ANCESTOR 为负)',
    `holder_org_id` BIGINT UNSIGNED NOT NULL COMMENT '快照时持有(当前责任)组织ID',
    `flow_status` VARCHAR(16) NOT NULL COMMENT '快照时流转状态',
    `risk_status_before` VARCHAR(16) NOT NULL COMMENT '快照时风险状态',
    `action` VARCHAR(24) NOT NULL COMMENT '处置动作(RECALLED/ALREADY_RECALLED/NOTIFY_HOLDER/TRACE_ONLY)',
    `risk_transition_id` BIGINT UNSIGNED NULL COMMENT '本召回写入的 RECALLED 转换ID',
    `declared_quantity` DECIMAL(18,3) NOT NULL COMMENT '声明数量',
    `remaining_quantity` DECIMAL(18,3) NOT NULL COMMENT '快照时剩余数量(库存与在途)',
    `sold_quantity` DECIMAL(18,3) NOT NULL COMMENT '快照时已终端销售数量',
    `unit_code` VARCHAR(16) NOT NULL COMMENT '计量单位',
    `open_transfer_id` BIGINT UNSIGNED NULL COMMENT '快照时未结束交接ID(在途 / 待接收 / 隔离；快照引用，不建外键)',
    `open_transfer_status` VARCHAR(16) NULL COMMENT '快照时未结束交接状态',
    `shipment_status` VARCHAR(16) NULL COMMENT '快照时该交接的运输状态',
    `public_code_active` TINYINT(1) NOT NULL COMMENT '快照时公开追溯码是否启用',
    `created_at` DATETIME(6) NOT NULL COMMENT '快照UTC时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_recall_batch` (`recall_id`, `batch_id`),
    KEY `idx_recall_batch_batch` (`batch_id`, `recall_id`),
    KEY `idx_recall_batch_holder` (`holder_org_id`, `recall_id`),
    CONSTRAINT `chk_recall_batch_role_action` CHECK (
        (`scope_role` = 'SEED' AND `depth` = 0 AND `action` = 'RECALLED') OR
        (`scope_role` = 'DESCENDANT' AND `depth` > 0 AND `action` IN ('RECALLED', 'ALREADY_RECALLED', 'NOTIFY_HOLDER', 'TRACE_ONLY')) OR
        (`scope_role` = 'ANCESTOR' AND `depth` < 0 AND `action` = 'TRACE_ONLY')
    ),
    CONSTRAINT `chk_recall_batch_transition_shape` CHECK (
        (`action` = 'RECALLED' AND `risk_transition_id` IS NOT NULL AND `risk_status_before` IN ('NORMAL', 'FROZEN')) OR
        (`action` = 'ALREADY_RECALLED' AND `risk_transition_id` IS NULL AND `risk_status_before` = 'RECALLED') OR
        (`action` IN ('NOTIFY_HOLDER', 'TRACE_ONLY') AND `risk_transition_id` IS NULL)
    ),
    CONSTRAINT `chk_recall_batch_status` CHECK (
        `flow_status` IN ('DRAFT', 'ACTIVE', 'CLOSED') AND `risk_status_before` IN ('NORMAL', 'FROZEN', 'RECALLED')
    ),
    CONSTRAINT `chk_recall_batch_quantities` CHECK (
        `declared_quantity` >= 0 AND `remaining_quantity` >= 0 AND `sold_quantity` >= 0
        AND `remaining_quantity` <= `declared_quantity` AND `sold_quantity` <= `declared_quantity`
    ),
    CONSTRAINT `chk_recall_batch_transfer_shape` CHECK (
        (`open_transfer_id` IS NULL AND `open_transfer_status` IS NULL AND `shipment_status` IS NULL) OR
        (`open_transfer_id` IS NOT NULL AND `open_transfer_status` IN ('DRAFT', 'PENDING', 'QUARANTINED'))
    ),
    CONSTRAINT `fk_recall_batch_recall` FOREIGN KEY (`recall_id`) REFERENCES `recall` (`id`),
    CONSTRAINT `fk_recall_batch_batch` FOREIGN KEY (`batch_id`) REFERENCES `batch` (`id`),
    CONSTRAINT `fk_recall_batch_holder` FOREIGN KEY (`holder_org_id`) REFERENCES `organization` (`id`),
    CONSTRAINT `fk_recall_batch_transition` FOREIGN KEY (`risk_transition_id`, `recall_id`)
        REFERENCES `batch_risk_transition` (`id`, `source_recall_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模拟召回影响范围快照(正向 / 反向谱系与数量事实)';
