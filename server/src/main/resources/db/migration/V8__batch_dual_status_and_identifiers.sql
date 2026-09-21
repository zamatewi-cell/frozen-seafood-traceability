-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V8
-- 任务编号: Phase 0 Task 1A (FR-BATCH-DUAL-STATUS-AND-IDENTIFIERS)
-- 说明:
--   1. 批次主表 batch 引入 trace_batch_no (服务端生成全局唯一) 与 external_batch_no (企业可选外部批号，可空可重复)；
--   2. 将单维 status 重构为 flow_status (流转状态) 与 risk_status (风险状态) 双维正交状态模型；
--   3. 引入 creation_org_id (创建组织)，把"创建幂等域"与"当前责任组织"彻底解耦：
--      batch.org_id 会在 Transfer ACCEPTED 时转移给接收方，不能继续充当创建幂等键的作用域；
--   4. 平滑迁移历史数据：
--      - external_batch_no 回填历史 batch_no 原值；
--      - creation_org_id 回填迁移时刻的 org_id；
--      - 历史 trace_batch_no 采用 SHA2(secretPepper || domainSeparator || historicalBatchId, 256)
--        不可逆确定性摘要回填，格式为 TB-H- 前缀加 64 位十六进制散列；
--        secretPepper 由 Flyway 回调在同一连接上注入会话变量 @trace_batch_no_history_pepper，
--        本文件只引用固定变量名，因此 V8 的 Flyway checksum 不随 pepper 变化；
--      - 状态映射规则：DRAFT -> DRAFT/NORMAL; ACTIVE -> ACTIVE/NORMAL; CLOSED -> CLOSED/NORMAL;
--                      FROZEN -> ACTIVE/FROZEN; RECALLED -> ACTIVE/RECALLED;
--        旧 RECALLED 只证明存在召回风险，并不能证明数量已耗尽或流转生命周期已结束，
--        故流转维度保持 ACTIVE，由 risk_status=RECALLED 负责阻断正常业务写入 (见 DEMO_MVP_ROADMAP.md §4.1)；
--   5. 移除历史 uk_batch_org_no 唯一键、历史 uk_batch_org_idempotency 唯一键、
--      历史 chk_batch_status 检查约束以及依赖旧 status 的索引；
--   6. 物理移除旧 batch_no 与 status 列；
--   7. 增加 trace_batch_no 唯一索引，增加 UNIQUE(creation_org_id, creation_idempotency_key)，
--      增加 flow_status/risk_status 各自值域枚举 CHECK 约束与状态组合 CHECK 约束，
--      物理层明确拒绝 DRAFT+FROZEN 与 DRAFT+RECALLED 非法状态组合；
--   8. PublicTraceCode (对外公开追溯码绑定表) 保持结构与字段稳定，不改动、不重建。
-- =============================================================================

-- 0. 前置条件：历史摘要 pepper 必须已由 Flyway 回调注入当前连接的会话变量。
--    缺失或空白时通过 NOT NULL 约束立即失败，绝不回退到任何公开默认值。
--    此处只写入存在性标记 'Y'，绝不把 pepper 本身落盘。
CREATE TEMPORARY TABLE `tmp_v8_pepper_precondition` (
    `pepper_present` CHAR(1) NOT NULL COMMENT '历史摘要 pepper 存在性标记'
);
INSERT INTO `tmp_v8_pepper_precondition` (`pepper_present`)
VALUES (IF(TRIM(IFNULL(@trace_batch_no_history_pepper, '')) = '', NULL, 'Y'));
DROP TEMPORARY TABLE `tmp_v8_pepper_precondition`;

-- 1. 为 batch 表新增双编号、双状态与创建组织列 (初始允许 NULL 以便执行存量数据回填)
ALTER TABLE `batch`
    ADD COLUMN `creation_org_id` BIGINT UNSIGNED NULL COMMENT '创建该批次的组织ID(不可变，仅用于创建幂等域与审计，不代表当前责任组织)' AFTER `org_id`,
    ADD COLUMN `trace_batch_no` VARCHAR(128) NULL COMMENT '服务端全局唯一追溯批次号' AFTER `product_id`,
    ADD COLUMN `external_batch_no` VARCHAR(64) NULL COMMENT '企业外部业务批次号(可空可重复)' AFTER `trace_batch_no`,
    ADD COLUMN `flow_status` VARCHAR(16) NULL COMMENT '批次流转状态(DRAFT/ACTIVE/CLOSED)' AFTER `status`,
    ADD COLUMN `risk_status` VARCHAR(16) NULL COMMENT '批次质量与风险状态(NORMAL/FROZEN/RECALLED)' AFTER `flow_status`;

-- 2. 存量历史批次数据平滑回填
-- 2.1 external_batch_no 回填历史 batch_no 原值
-- 2.2 creation_org_id 回填迁移时刻的 org_id (历史上没有独立记录创建组织，此为唯一可得的确定性基线)
-- 2.3 历史 trace_batch_no 使用 pepper + domain separation 字符串 + 历史主键 id 的 SHA-256 确定性摘要生成，
--     pepper 来自会话变量，绝不写入本文件，从而既不可离线枚举，又保证同 pepper 跨库结果完全一致
-- 2.4 状态映射:
--     DRAFT    -> flow_status: DRAFT,  risk_status: NORMAL
--     ACTIVE   -> flow_status: ACTIVE, risk_status: NORMAL
--     CLOSED   -> flow_status: CLOSED, risk_status: NORMAL
--     FROZEN   -> flow_status: ACTIVE, risk_status: FROZEN
--     RECALLED -> flow_status: ACTIVE, risk_status: RECALLED
UPDATE `batch` SET
    `external_batch_no` = `batch_no`,
    `creation_org_id` = `org_id`,
    `trace_batch_no` = CONCAT('TB-H-', SHA2(CONCAT(@trace_batch_no_history_pepper, 'domain:seafood:trace:batch:v8:history:', CAST(`id` AS CHAR)), 256)),
    `flow_status` = CASE `status`
        WHEN 'DRAFT' THEN 'DRAFT'
        WHEN 'ACTIVE' THEN 'ACTIVE'
        WHEN 'CLOSED' THEN 'CLOSED'
        WHEN 'FROZEN' THEN 'ACTIVE'
        WHEN 'RECALLED' THEN 'ACTIVE'
    END,
    `risk_status` = CASE `status`
        WHEN 'DRAFT' THEN 'NORMAL'
        WHEN 'ACTIVE' THEN 'NORMAL'
        WHEN 'CLOSED' THEN 'NORMAL'
        WHEN 'FROZEN' THEN 'FROZEN'
        WHEN 'RECALLED' THEN 'RECALLED'
    END;

-- 3. 移除历史索引、历史唯一键与历史 CHECK 约束
--    uk_batch_org_idempotency 以会转移的 org_id 为作用域，必须让位给 creation_org_id 作用域
ALTER TABLE `batch`
    DROP INDEX `uk_batch_org_no`,
    DROP INDEX `uk_batch_org_idempotency`,
    DROP INDEX `idx_batch_product_status`,
    DROP INDEX `idx_batch_status`,
    DROP CHECK `chk_batch_status`;

-- 4. 物理移除历史冗余列 batch_no 与 status
ALTER TABLE `batch`
    DROP COLUMN `batch_no`,
    DROP COLUMN `status`;

-- 5. 将新字段调整为非空并设置默认值，建立 trace_batch_no 与创建幂等域唯一约束
--    creation_idempotency_key 可为 NULL，MySQL 唯一索引对 NULL 不做去重，
--    因此"未携带创建幂等键的历史批次"行为与 V3 完全一致
ALTER TABLE `batch`
    MODIFY COLUMN `creation_org_id` BIGINT UNSIGNED NOT NULL COMMENT '创建该批次的组织ID(不可变，仅用于创建幂等域与审计，不代表当前责任组织)',
    MODIFY COLUMN `trace_batch_no` VARCHAR(128) NOT NULL COMMENT '服务端全局唯一追溯批次号',
    MODIFY COLUMN `flow_status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '批次流转状态(DRAFT/ACTIVE/CLOSED)',
    MODIFY COLUMN `risk_status` VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '批次质量与风险状态(NORMAL/FROZEN/RECALLED)',
    ADD CONSTRAINT `uk_batch_trace_batch_no` UNIQUE (`trace_batch_no`),
    ADD CONSTRAINT `uk_batch_creation_org_idempotency` UNIQUE (`creation_org_id`, `creation_idempotency_key`);

-- 6. 补充 flow_status / risk_status 各自枚举域 CHECK 约束及合法状态组合 CHECK 约束 (拒绝 DRAFT+FROZEN 与 DRAFT+RECALLED)
ALTER TABLE `batch`
    ADD CONSTRAINT `chk_batch_flow_status` CHECK (`flow_status` IN ('DRAFT', 'ACTIVE', 'CLOSED')),
    ADD CONSTRAINT `chk_batch_risk_status` CHECK (`risk_status` IN ('NORMAL', 'FROZEN', 'RECALLED')),
    ADD CONSTRAINT `chk_batch_status_combination` CHECK (
        NOT (`flow_status` = 'DRAFT' AND `risk_status` IN ('FROZEN', 'RECALLED'))
    );

-- 7. 创建组织外键：creation_org_id 不可变且必须指向真实组织。
--    uk_batch_creation_org_idempotency 的最左前缀即 creation_org_id，已满足外键所需索引。
ALTER TABLE `batch`
    ADD CONSTRAINT `fk_batch_creation_org` FOREIGN KEY (`creation_org_id`) REFERENCES `organization` (`id`);

-- 8. 补充新字段与业务场景高频组合查询索引
ALTER TABLE `batch`
    ADD KEY `idx_batch_org_flow` (`org_id`, `flow_status`),
    ADD KEY `idx_batch_product_flow_risk` (`product_id`, `flow_status`, `risk_status`),
    ADD KEY `idx_batch_flow_risk` (`flow_status`, `risk_status`),
    ADD KEY `idx_batch_external_batch_no` (`external_batch_no`);
