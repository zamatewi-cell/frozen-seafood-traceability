-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V6
-- 任务编号: GitHub Issue #17
-- 说明: 补充对外公开追溯码绑定表的一批一码唯一约束、组织隔离索引、状态与停用形状 CHECK 约束，
--       并新建公开追溯码操作统一幂等绑定记录表 (public_trace_code_idempotency)
-- =============================================================================

-- 1. 移除对外公开追溯码绑定表原有非唯一批次索引
ALTER TABLE `public_trace_code`
    DROP INDEX `idx_trace_batch`;

-- 2. 安全三步平滑迁移 org_id，可靠保留存量数据的租户归属（不得修改 V1-V5）
--    第 1 步：添加可空 org_id 列
ALTER TABLE `public_trace_code`
    ADD COLUMN `org_id` BIGINT UNSIGNED NULL COMMENT '所属组织ID' AFTER `batch_id`;

--    第 2 步：按 batch_id 回填存量数据的 org_id（严禁使用 0 或猜测默认值填充）
UPDATE `public_trace_code` ptc
    INNER JOIN `batch` b ON ptc.`batch_id` = b.`id`
    SET ptc.`org_id` = b.`org_id`;

--    第 3 步：收紧为 NOT NULL（若存在未关联批次的孤儿记录，则直接报错中止迁移）
ALTER TABLE `public_trace_code`
    MODIFY COLUMN `org_id` BIGINT UNSIGNED NOT NULL COMMENT '所属组织ID';

-- 3. 建立一批一码唯一索引与组织隔离索引
--    （不设字段级 action 幂等列，统一由独立幂等表收敛，避免双轨语义）
ALTER TABLE `public_trace_code`
    ADD CONSTRAINT `uk_public_trace_code_batch` UNIQUE (`batch_id`),
    ADD KEY `idx_trace_code_org_batch` (`org_id`, `batch_id`),
    ADD KEY `idx_trace_code_org_status` (`org_id`, `status`);

-- 4. 补充状态枚举与生命周期停用形状物理 CHECK 约束
ALTER TABLE `public_trace_code`
    ADD CONSTRAINT `chk_public_trace_code_status` CHECK (`status` IN ('ACTIVE', 'DISABLED', 'RECALLED')),
    ADD CONSTRAINT `chk_public_trace_code_disable_shape` CHECK (
        (`status` = 'ACTIVE' AND `disabled_at` IS NULL) OR
        (`status` = 'DISABLED' AND `disabled_at` IS NOT NULL) OR
        (`status` = 'RECALLED')
    );

-- 5. 新建公开追溯码统一操作幂等记录表
--    统一在 (org_id, idempotency_key) 上建立唯一约束，结构化保存 action、batch_id、语义 request_hash、
--    public_trace_code_id 与时间，实现不同 key 命中已有资源也能持久绑定的刚性保证。
CREATE TABLE IF NOT EXISTS `public_trace_code_idempotency` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '所属组织ID',
    `idempotency_key` VARCHAR(128) NOT NULL COMMENT '客户端防重幂等键',
    `action` VARCHAR(32) NOT NULL COMMENT '操作类型(ACTIVATE/DISABLE)',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '目标批次ID',
    `public_trace_code_id` BIGINT UNSIGNED NOT NULL COMMENT '关联公开追溯码ID',
    `request_hash` VARCHAR(64) NOT NULL COMMENT '请求语义哈希',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ptc_idem_org_key` (`org_id`, `idempotency_key`),
    KEY `idx_ptc_idem_batch` (`org_id`, `batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公开追溯码统一操作幂等记录表';
