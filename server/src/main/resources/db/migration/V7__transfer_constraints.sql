-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V7
-- 任务编号: GitHub Issue #21 (FR-TRANSFER-001)
-- 说明:
--   1. 修复 V1 全局 uk_transfer_idempotency 索引的租户作用域问题；
--   2. 为 transfer 凭证表补充发货业务时间可空化、默认 DRAFT 状态、提交及决定审计时间与操作人列；
--   3. 建立 open_batch_id 虚拟生成列与唯一索引，实现同一批次同一时刻最多存在一个未结束交接（DRAFT/PENDING）的排他预留；
--   4. 补充状态枚举、正数量、kg计量单位、企业组织不同及各阶段决定字段形状的 MySQL 8.4 物理 CHECK 约束；
--   5. 补充更新时间降序、主键降序的稳定查询覆盖索引；
--   6. 建立多动作统一幂等记录表 transfer_idempotency。
-- =============================================================================

-- 1. 移除 V1 中在 transfer 表上的全局唯一幂等索引
ALTER TABLE `transfer`
    DROP INDEX `uk_transfer_idempotency`;

-- 2. 调整字段形态并新增提交与接收决定跟踪字段及历史兼容标记
ALTER TABLE `transfer`
    MODIFY COLUMN `idempotency_key` VARCHAR(128) NOT NULL COMMENT '防重复交接幂等键',
    MODIFY COLUMN `shipped_at` DATETIME(6) NULL COMMENT '发货业务确认时间',
    ALTER COLUMN `status` SET DEFAULT 'DRAFT',
    ADD COLUMN `submitted_recorded_at` DATETIME(6) NULL COMMENT '发送提交系统登记时间' AFTER `shipped_at`,
    ADD COLUMN `submitted_by` BIGINT UNSIGNED NULL COMMENT '发送提交操作人用户ID' AFTER `submitted_recorded_at`,
    ADD COLUMN `decision_recorded_at` DATETIME(6) NULL COMMENT '接收决定系统登记时间' AFTER `received_at`,
    ADD COLUMN `decided_by` BIGINT UNSIGNED NULL COMMENT '接收决定操作人用户ID' AFTER `decision_recorded_at`,
    ADD COLUMN `is_legacy` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '历史存量数据标记(1:V7迁移前历史旧数据, 0:V7及之后新规数据)' AFTER `is_deleted`;

-- 3. 将现存所有历史行标记为 legacy，避免历史合法数据被新生命周期 shape CHECK 阻断
UPDATE `transfer` SET `is_legacy` = 1;

-- 4. 建立未结束交接（DRAFT/PENDING）排他虚拟生成列与唯一约束
ALTER TABLE `transfer`
    ADD COLUMN `open_batch_id` BIGINT UNSIGNED GENERATED ALWAYS AS (
        CASE
            WHEN `is_legacy` = 0 AND `status` IN ('DRAFT', 'PENDING') AND `is_deleted` = 0 THEN `batch_id`
            ELSE NULL
        END
    ) STORED COMMENT '未结束交接排他批次ID' AFTER `batch_id`,
    ADD CONSTRAINT `uk_transfer_open_batch` UNIQUE (`open_batch_id`);

-- 5. 补充状态、数量、单位、双方组织不同以及生命周期形状物理 CHECK 约束
ALTER TABLE `transfer`
    ADD CONSTRAINT `chk_transfer_status` CHECK (`status` IN ('DRAFT', 'PENDING', 'ACCEPTED', 'REJECTED')),
    ADD CONSTRAINT `chk_transfer_quantity` CHECK (`quantity` > 0),
    ADD CONSTRAINT `chk_transfer_received_quantity` CHECK (`received_quantity` IS NULL OR `received_quantity` > 0),
    ADD CONSTRAINT `chk_transfer_unit_code` CHECK (`unit_code` = 'kg'),
    ADD CONSTRAINT `chk_transfer_diff_org` CHECK (`sender_org_id` <> `receiver_org_id`),
    ADD CONSTRAINT `chk_transfer_decision_shape` CHECK (
        `is_legacy` = 1 OR (
            (`status` = 'DRAFT' AND `shipped_at` IS NULL AND `submitted_recorded_at` IS NULL AND `submitted_by` IS NULL AND `received_at` IS NULL AND `decision_recorded_at` IS NULL AND `decided_by` IS NULL AND `received_quantity` IS NULL AND `difference_reason` IS NULL AND `rejection_reason` IS NULL) OR
            (`status` = 'PENDING' AND `shipped_at` IS NOT NULL AND `submitted_recorded_at` IS NOT NULL AND `submitted_by` IS NOT NULL AND `received_at` IS NULL AND `decision_recorded_at` IS NULL AND `decided_by` IS NULL AND `received_quantity` IS NULL AND `difference_reason` IS NULL AND `rejection_reason` IS NULL) OR
            (`status` = 'ACCEPTED' AND `shipped_at` IS NOT NULL AND `submitted_recorded_at` IS NOT NULL AND `submitted_by` IS NOT NULL AND `received_at` IS NOT NULL AND `decision_recorded_at` IS NOT NULL AND `decided_by` IS NOT NULL AND `received_quantity` IS NOT NULL AND `received_quantity` > 0 AND `rejection_reason` IS NULL AND ((`received_quantity` = `quantity` AND `difference_reason` IS NULL) OR (`received_quantity` <> `quantity` AND `difference_reason` IS NOT NULL))) OR
            (`status` = 'REJECTED' AND `shipped_at` IS NOT NULL AND `submitted_recorded_at` IS NOT NULL AND `submitted_by` IS NOT NULL AND `received_at` IS NOT NULL AND `decision_recorded_at` IS NOT NULL AND `decided_by` IS NOT NULL AND `received_quantity` IS NULL AND `difference_reason` IS NULL AND `rejection_reason` IS NOT NULL)
        )
    );

-- 6. 补充稳定列表排序与组织隔离复合索引
ALTER TABLE `transfer`
    ADD KEY `idx_transfer_sender_updated` (`sender_org_id`, `updated_at` DESC, `id` DESC),
    ADD KEY `idx_transfer_receiver_updated` (`receiver_org_id`, `updated_at` DESC, `id` DESC),
    ADD KEY `idx_transfer_batch_status` (`batch_id`, `status`);

-- 7. 新建企业间整批交接多动作统一幂等记录表
CREATE TABLE IF NOT EXISTS `transfer_idempotency` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '所属组织ID',
    `idempotency_key` VARCHAR(128) NOT NULL COMMENT '客户端防重幂等键',
    `action` VARCHAR(32) NOT NULL COMMENT '操作类型(CREATE/SUBMIT/ACCEPT/REJECT)',
    `transfer_id` BIGINT UNSIGNED NOT NULL COMMENT '关联交接主表ID',
    `request_hash` VARCHAR(64) NOT NULL COMMENT '请求规范语义哈希',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_transfer_idem_org_key` (`org_id`, `idempotency_key`),
    KEY `idx_transfer_idem_org_transfer` (`org_id`, `transfer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业间整批交接统一多动作幂等记录表';
