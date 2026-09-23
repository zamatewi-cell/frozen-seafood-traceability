-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V11
-- 任务编号: Phase A Slice 5 (终端 Sale)
-- 业务基线: docs/BUSINESS_CONTRACT_V1.1.md §2.13 / §5 / §9.1 / §11，docs/DEMO_MVP_ROADMAP.md §5.4
-- 说明:
--   1. 新建不可变终端销售台账 sale：一条 Sale 只对应一个 Batch，由当前责任 RETAILER 在本组织 Site(STORE) 提交；
--      不包含消费者身份、支付、订单、SKU、发票或 ERP 字段；台账追加式不可修改，不含 version / updated_* / is_deleted；
--      status 仅 SUBMITTED（契约未定义销售撤销，未来如需撤销必须新增迁移）。
--   2. 幂等：UNIQUE (org_id, idempotency_key) + request_hash（规范化请求语义哈希）。
--   3. site 增加 UNIQUE (id, org_id)，作为 sale(site_id, org_id) 复合外键目标，数据库层保证销售场所属于销售组织
--      （场所类型 STORE 与启用状态由应用层校验）。
--   4. batch.first_sale_id：该批次第一次有效 Sale 的写一次标记（不是数量列，剩余量仍由已提交记录派生）：
--      - 复合外键 (first_sale_id, id, org_id) → sale(id, batch_id, org_id)：只能指向同一批次、同一组织的 Sale；
--        副作用：已开始销售的批次 org_id 在数据库层也无法再变更（与"首次 Sale 后禁止交接"一致）；
--      - CHECK：已开始销售的批次不能同时被批次操作全量消耗；已开始销售的批次不能是 DRAFT。
--      sale.batch_id → batch.id 与 batch.first_sale_id → sale 形成有意的循环引用；
--      创建顺序安全：批次已存在 → 插入 Sale → 同一事务内回写 first_sale_id。
--   5. 不回填：历史批次 first_sale_id 保持 NULL；本迁移不引入处置、召回、温度或 QUARANTINED 相关结构。
-- =============================================================================

-- 1. site：复合外键目标唯一键
ALTER TABLE `site`
    ADD CONSTRAINT `uk_site_id_org` UNIQUE (`id`, `org_id`);

-- 2. 终端销售台账
CREATE TABLE IF NOT EXISTS `sale` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '销售组织ID(批次当前责任 RETAILER)',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '销售批次ID',
    `site_id` BIGINT UNSIGNED NOT NULL COMMENT '销售门店场所ID(本组织 STORE)',
    `quantity` DECIMAL(18,3) NOT NULL COMMENT '销售数量',
    `unit_code` VARCHAR(16) NOT NULL DEFAULT 'kg' COMMENT '计量单位',
    `occurred_at` DATETIME(6) NOT NULL COMMENT '销售业务发生UTC时间',
    `status` VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED' COMMENT '状态(SUBMITTED)',
    `idempotency_key` VARCHAR(128) NOT NULL COMMENT '客户端防重幂等键',
    `request_hash` CHAR(64) NOT NULL COMMENT '请求规范语义哈希',
    `created_by` BIGINT UNSIGNED NOT NULL COMMENT '提交操作人用户ID',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '系统登记UTC时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sale_org_idempotency` (`org_id`, `idempotency_key`),
    UNIQUE KEY `uk_sale_id_batch_org` (`id`, `batch_id`, `org_id`),
    KEY `idx_sale_batch_time` (`batch_id`, `status`, `occurred_at`, `id`),
    CONSTRAINT `chk_sale_quantity` CHECK (`quantity` > 0),
    CONSTRAINT `chk_sale_unit_code` CHECK (`unit_code` = 'kg'),
    CONSTRAINT `chk_sale_status` CHECK (`status` IN ('SUBMITTED')),
    CONSTRAINT `fk_sale_batch` FOREIGN KEY (`batch_id`) REFERENCES `batch` (`id`),
    CONSTRAINT `fk_sale_org` FOREIGN KEY (`org_id`) REFERENCES `organization` (`id`),
    CONSTRAINT `fk_sale_site_org` FOREIGN KEY (`site_id`, `org_id`) REFERENCES `site` (`id`, `org_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='终端销售数量台账(追加式)';

-- 3. batch：第一次有效 Sale 写一次标记
ALTER TABLE `batch`
    ADD COLUMN `first_sale_id` BIGINT UNSIGNED NULL COMMENT '第一次有效终端销售ID(写一次；非空后禁止交接与批次操作)' AFTER `consumed_by_operation_id`;

ALTER TABLE `batch`
    ADD CONSTRAINT `fk_batch_first_sale` FOREIGN KEY (`first_sale_id`, `id`, `org_id`)
        REFERENCES `sale` (`id`, `batch_id`, `org_id`),
    ADD CONSTRAINT `chk_batch_sale_consumption_exclusive`
        CHECK (NOT (`first_sale_id` IS NOT NULL AND `consumed_by_operation_id` IS NOT NULL)),
    ADD CONSTRAINT `chk_batch_sale_not_draft`
        CHECK (`first_sale_id` IS NULL OR `flow_status` IN ('ACTIVE', 'CLOSED'));
