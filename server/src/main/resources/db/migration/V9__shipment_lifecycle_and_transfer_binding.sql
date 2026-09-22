-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V9
-- 任务编号: Phase A Slice 2 (Transfer + Shipment)
-- 业务基线: docs/BUSINESS_CONTRACT_V1.1.md §7 / §11，docs/DEMO_MVP_ROADMAP.md §5.1 / §5.2
-- 说明:
--   1. 前置条件 (fail-fast，不伪造任何业务数据)：
--      a) shipment 表必须为空 —— V1 以来没有任何应用代码写入该表，存量行只可能来自手工 SQL，
--         其缺少发送/接收组织等契约必需事实，不能被自动推断；
--      b) 不存在任何未绑定 Shipment 的 PENDING transfer（包括 V7 之前的 legacy 行）—— 此类交接在新契约下
--         永远无法满足 "Shipment DELIVERED 才能 ACCEPT/REJECT"，且 PENDING 会持续阻断该批次的批次操作，
--         必须先由人工决定处置，迁移不替其伪造结论。
--   2. shipment 表补齐 PLANNED 生命周期：发送/接收组织、PLANNED 时 loaded_at 可空、
--      发运/到达/取消的操作人与系统登记时间、状态与生命周期形状 CHECK、组织与场所外键；
--      (承运组织与发送/接收方是否不同不做数据库永久约束，见第 2 节注释)；
--   3. 新建 shipment_idempotency 多动作统一幂等记录表；
--   4. transfer 表：
--      - 无 Shipment 的历史终态交接 (ACCEPTED/REJECTED) 标记为 is_legacy = 1 (Shipment 契约前历史)；
--      - transfer.shipment_id 外键；
--      - (shipment_id, sender_org_id, receiver_org_id) 复合外键，数据库层保证同一 Shipment 内所有 Transfer
--        具有相同发送方与接收方 (起点/终点属于 Shipment 自身，天然一致)；
--      - UNIQUE (shipment_id, batch_id)，数据库层保证同一 Batch 在同一 Shipment 中只出现一次；
--      - 重建 chk_transfer_decision_shape：非 DRAFT 新规交接必须绑定 Shipment，
--        shipped_at 不再由 Transfer 承担 (物理发运时间归属 shipment.loaded_at)。
--   5. 本迁移不引入 QUARANTINED、温度、告警、召回或销售相关结构。
-- =============================================================================

-- 0. 前置条件校验：任一条件不满足时向 NOT NULL 列写入 NULL，迁移立即失败。
CREATE TEMPORARY TABLE `tmp_v9_precondition` (
    `check_name` VARCHAR(64) NOT NULL COMMENT '前置条件名称',
    `passed` CHAR(1) NOT NULL COMMENT '通过标记'
);
INSERT INTO `tmp_v9_precondition` (`check_name`, `passed`)
VALUES ('shipment_table_empty', IF((SELECT COUNT(*) FROM `shipment`) = 0, 'Y', NULL));
INSERT INTO `tmp_v9_precondition` (`check_name`, `passed`)
VALUES ('no_unbound_pending_transfer',
        IF((SELECT COUNT(*) FROM `transfer` WHERE `status` = 'PENDING' AND `shipment_id` IS NULL) = 0, 'Y', NULL));
DROP TEMPORARY TABLE `tmp_v9_precondition`;

-- 1. shipment：补齐 PLANNED 生命周期所需字段
ALTER TABLE `shipment`
    ADD COLUMN `sender_org_id` BIGINT UNSIGNED NOT NULL COMMENT '发货方(当前责任)组织ID' AFTER `shipment_no`,
    ADD COLUMN `receiver_org_id` BIGINT UNSIGNED NOT NULL COMMENT '接收方组织ID(由目的场所所属组织确定)' AFTER `sender_org_id`,
    MODIFY COLUMN `vehicle_or_container_no` VARCHAR(64) NOT NULL COMMENT '车牌号或冷藏集装箱编号',
    MODIFY COLUMN `loaded_at` DATETIME(6) NULL COMMENT '装载发运业务时间(PLANNED 阶段为空，发运时写入)',
    MODIFY COLUMN `unloaded_at` DATETIME(6) NULL COMMENT '到达卸货业务时间(到达时写入)',
    ALTER COLUMN `status` SET DEFAULT 'PLANNED',
    ADD COLUMN `dispatched_recorded_at` DATETIME(6) NULL COMMENT '发运系统登记时间' AFTER `unloaded_at`,
    ADD COLUMN `dispatched_by` BIGINT UNSIGNED NULL COMMENT '确认发运的承运商操作人ID' AFTER `dispatched_recorded_at`,
    ADD COLUMN `delivered_recorded_at` DATETIME(6) NULL COMMENT '到达系统登记时间' AFTER `dispatched_by`,
    ADD COLUMN `delivered_by` BIGINT UNSIGNED NULL COMMENT '确认到达的承运商操作人ID' AFTER `delivered_recorded_at`,
    ADD COLUMN `cancelled_recorded_at` DATETIME(6) NULL COMMENT '取消系统登记时间' AFTER `delivered_by`,
    ADD COLUMN `cancelled_by` BIGINT UNSIGNED NULL COMMENT '取消运输任务的发货方操作人ID' AFTER `cancelled_recorded_at`,
    ADD COLUMN `cancel_reason` VARCHAR(500) NULL COMMENT '取消原因' AFTER `cancelled_by`;

-- 2. shipment：状态、参与方与生命周期形状 CHECK
ALTER TABLE `shipment`
    ADD CONSTRAINT `chk_shipment_status` CHECK (`status` IN ('PLANNED', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED')),
    -- 仅固化契约明确的 "发送方与接收方不同"。承运组织与发送/接收方是否必须不同，契约 v1.1 未作规定，
    -- 不在数据库层设永久约束；Demo MVP Phase A 在应用层要求承运方为独立的 CARRIER 组织 (阶段性限制)。
    ADD CONSTRAINT `chk_shipment_parties` CHECK (`sender_org_id` <> `receiver_org_id`),
    ADD CONSTRAINT `chk_shipment_sites` CHECK (`origin_site_id` <> `destination_site_id`),
    ADD CONSTRAINT `chk_shipment_lifecycle_shape` CHECK (
        (`status` = 'PLANNED'
            AND `loaded_at` IS NULL AND `unloaded_at` IS NULL
            AND `dispatched_recorded_at` IS NULL AND `dispatched_by` IS NULL
            AND `delivered_recorded_at` IS NULL AND `delivered_by` IS NULL
            AND `cancelled_recorded_at` IS NULL AND `cancelled_by` IS NULL AND `cancel_reason` IS NULL) OR
        (`status` = 'IN_TRANSIT'
            AND `loaded_at` IS NOT NULL AND `unloaded_at` IS NULL
            AND `dispatched_recorded_at` IS NOT NULL AND `dispatched_by` IS NOT NULL
            AND `delivered_recorded_at` IS NULL AND `delivered_by` IS NULL
            AND `cancelled_recorded_at` IS NULL AND `cancelled_by` IS NULL AND `cancel_reason` IS NULL) OR
        (`status` = 'DELIVERED'
            AND `loaded_at` IS NOT NULL AND `unloaded_at` IS NOT NULL AND `unloaded_at` >= `loaded_at`
            AND `dispatched_recorded_at` IS NOT NULL AND `dispatched_by` IS NOT NULL
            AND `delivered_recorded_at` IS NOT NULL AND `delivered_by` IS NOT NULL
            AND `cancelled_recorded_at` IS NULL AND `cancelled_by` IS NULL AND `cancel_reason` IS NULL) OR
        (`status` = 'CANCELLED'
            AND `loaded_at` IS NULL AND `unloaded_at` IS NULL
            AND `dispatched_recorded_at` IS NULL AND `dispatched_by` IS NULL
            AND `delivered_recorded_at` IS NULL AND `delivered_by` IS NULL
            AND `cancelled_recorded_at` IS NOT NULL AND `cancelled_by` IS NOT NULL AND `cancel_reason` IS NOT NULL)
    );

-- 3. shipment：组织与场所外键、复合外键目标唯一键、列表稳定排序索引
ALTER TABLE `shipment`
    ADD CONSTRAINT `fk_shipment_sender_org` FOREIGN KEY (`sender_org_id`) REFERENCES `organization` (`id`),
    ADD CONSTRAINT `fk_shipment_receiver_org` FOREIGN KEY (`receiver_org_id`) REFERENCES `organization` (`id`),
    ADD CONSTRAINT `fk_shipment_carrier_org` FOREIGN KEY (`carrier_org_id`) REFERENCES `organization` (`id`),
    ADD CONSTRAINT `fk_shipment_origin_site` FOREIGN KEY (`origin_site_id`) REFERENCES `site` (`id`),
    ADD CONSTRAINT `fk_shipment_destination_site` FOREIGN KEY (`destination_site_id`) REFERENCES `site` (`id`),
    ADD CONSTRAINT `uk_shipment_id_parties` UNIQUE (`id`, `sender_org_id`, `receiver_org_id`),
    ADD KEY `idx_shipment_sender_updated` (`sender_org_id`, `updated_at` DESC, `id` DESC),
    ADD KEY `idx_shipment_receiver_updated` (`receiver_org_id`, `updated_at` DESC, `id` DESC),
    ADD KEY `idx_shipment_carrier_updated` (`carrier_org_id`, `updated_at` DESC, `id` DESC);

-- 4. Shipment 多动作统一幂等记录表
CREATE TABLE IF NOT EXISTS `shipment_idempotency` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '操作组织ID',
    `idempotency_key` VARCHAR(128) NOT NULL COMMENT '客户端防重幂等键',
    `action` VARCHAR(32) NOT NULL COMMENT '操作类型(CREATE/BIND/DISPATCH/ARRIVE/CANCEL)',
    `shipment_id` BIGINT UNSIGNED NOT NULL COMMENT '关联运输任务ID',
    `request_hash` VARCHAR(64) NOT NULL COMMENT '请求规范语义哈希',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_shipment_idem_org_key` (`org_id`, `idempotency_key`),
    KEY `idx_shipment_idem_org_shipment` (`org_id`, `shipment_id`),
    CONSTRAINT `chk_shipment_idem_action` CHECK (`action` IN ('CREATE', 'BIND', 'DISPATCH', 'ARRIVE', 'CANCEL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='冷链运输任务统一多动作幂等记录表';

-- 5. transfer：无 Shipment 的历史终态交接归入 Shipment 契约前历史
ALTER TABLE `transfer`
    MODIFY COLUMN `is_legacy` TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '历史存量数据标记(1: V7 迁移前历史数据或 V9 Shipment 契约前的终态交接, 0: 新规数据)';

UPDATE `transfer`
SET `is_legacy` = 1
WHERE `is_legacy` = 0
  AND `status` IN ('ACCEPTED', 'REJECTED')
  AND `shipment_id` IS NULL;

-- 6. transfer：Shipment 绑定外键、同 Shipment 同发送/接收方复合外键、同 Shipment 同 Batch 唯一
ALTER TABLE `transfer`
    MODIFY COLUMN `shipped_at` DATETIME(6) NULL COMMENT '历史发货确认时间(仅历史数据；物理发运时间以 shipment.loaded_at 为准)',
    ADD CONSTRAINT `fk_transfer_shipment` FOREIGN KEY (`shipment_id`) REFERENCES `shipment` (`id`),
    ADD CONSTRAINT `fk_transfer_shipment_parties`
        FOREIGN KEY (`shipment_id`, `sender_org_id`, `receiver_org_id`)
        REFERENCES `shipment` (`id`, `sender_org_id`, `receiver_org_id`),
    ADD CONSTRAINT `uk_transfer_shipment_batch` UNIQUE (`shipment_id`, `batch_id`);

-- 7. transfer：重建生命周期形状 CHECK (提交后的新规交接必须绑定 Shipment)
ALTER TABLE `transfer`
    DROP CHECK `chk_transfer_decision_shape`;

ALTER TABLE `transfer`
    ADD CONSTRAINT `chk_transfer_decision_shape` CHECK (
        `is_legacy` = 1 OR (
            (`status` = 'DRAFT'
                AND `shipped_at` IS NULL AND `submitted_recorded_at` IS NULL AND `submitted_by` IS NULL
                AND `received_at` IS NULL AND `decision_recorded_at` IS NULL AND `decided_by` IS NULL
                AND `received_quantity` IS NULL AND `difference_reason` IS NULL AND `rejection_reason` IS NULL) OR
            (`status` = 'PENDING'
                AND `shipment_id` IS NOT NULL
                AND `shipped_at` IS NULL AND `submitted_recorded_at` IS NOT NULL AND `submitted_by` IS NOT NULL
                AND `received_at` IS NULL AND `decision_recorded_at` IS NULL AND `decided_by` IS NULL
                AND `received_quantity` IS NULL AND `difference_reason` IS NULL AND `rejection_reason` IS NULL) OR
            (`status` = 'ACCEPTED'
                AND `shipment_id` IS NOT NULL
                AND `shipped_at` IS NULL AND `submitted_recorded_at` IS NOT NULL AND `submitted_by` IS NOT NULL
                AND `received_at` IS NOT NULL AND `decision_recorded_at` IS NOT NULL AND `decided_by` IS NOT NULL
                AND `received_quantity` IS NOT NULL AND `received_quantity` > 0 AND `rejection_reason` IS NULL
                AND ((`received_quantity` = `quantity` AND `difference_reason` IS NULL)
                     OR (`received_quantity` <> `quantity` AND `difference_reason` IS NOT NULL))) OR
            (`status` = 'REJECTED'
                AND `shipment_id` IS NOT NULL
                AND `shipped_at` IS NULL AND `submitted_recorded_at` IS NOT NULL AND `submitted_by` IS NOT NULL
                AND `received_at` IS NOT NULL AND `decision_recorded_at` IS NOT NULL AND `decided_by` IS NOT NULL
                AND `received_quantity` IS NULL AND `difference_reason` IS NULL AND `rejection_reason` IS NOT NULL)
        )
    );
