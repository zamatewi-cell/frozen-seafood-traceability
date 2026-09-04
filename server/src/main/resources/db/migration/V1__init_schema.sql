-- =============================================================================
-- Flyway Database Migration: V1__init_schema.sql
-- Description: 冷冻海产品溯源系统 8 大业务域核心基准表结构初始化
-- Target Database: MySQL 8.4 LTS
-- Character Set: utf8mb4 (Collation: utf8mb4_0900_ai_ci)
-- Timezone: UTC (+00:00)
-- Specification:
--   1. 主键统一采用 BIGINT UNSIGNED (雪花算法 Snowflake ID)
--   2. 外部公开标识采用 public_id VARCHAR(40) 与 token_hash CHAR(64)
--   3. 统一审计字段: version BIGINT, created_at/updated_at DATETIME(6), created_by/updated_by BIGINT UNSIGNED
--   4. 统一软删除: is_deleted TINYINT(1) DEFAULT 0
--   5. 数量与重量采用 DECIMAL(18,3)，温度采用 DECIMAL(6,2)
--   6. 追溯事件与审计日志设计为追加式不可篡改结构
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =============================================================================
-- 1. 系统与配置域 (common)
-- =============================================================================

-- 1.1 系统基础配置表
CREATE TABLE IF NOT EXISTS `sys_config` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键(雪花算法预留自增)',
    `config_key` VARCHAR(64) NOT NULL COMMENT '配置键名',
    `config_value` TEXT NOT NULL COMMENT '配置内容',
    `config_desc` VARCHAR(255) NULL COMMENT '配置说明',
    `is_editable` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否支持页面修改(1:是, 0:否)',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统基础配置表';

-- =============================================================================
-- 2. 用户与租户域 (identity)
-- =============================================================================

-- 2.1 企业与组织表
CREATE TABLE IF NOT EXISTS `organization` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `org_no` VARCHAR(32) NOT NULL COMMENT '组织业务唯一编码',
    `name` VARCHAR(128) NOT NULL COMMENT '企业/组织全称',
    `org_type` VARCHAR(32) NOT NULL COMMENT '组织类型(SOURCE/PROCESSOR/WAREHOUSE/CARRIER/DISTRIBUTOR/RETAILER)',
    `credit_code` VARCHAR(32) NULL COMMENT '统一社会信用代码(脱敏)',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '组织状态(ACTIVE/INACTIVE)',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_org_no` (`org_no`),
    KEY `idx_org_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业组织与租户表';

-- 2.2 场所与设施表
CREATE TABLE IF NOT EXISTS `site` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '所属组织ID',
    `site_no` VARCHAR(32) NOT NULL COMMENT '组织内场所编号',
    `name` VARCHAR(128) NOT NULL COMMENT '场所名称',
    `site_type` VARCHAR(32) NOT NULL COMMENT '场所类型(PORT/FARM/FACTORY/COLD_STORE/LOGISTICS_HUB/STORE)',
    `address_text` VARCHAR(255) NULL COMMENT '详细地址说明',
    `timezone` VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '所在时区',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '场所状态(ACTIVE/INACTIVE)',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_site_org_no` (`org_id`, `site_no`),
    KEY `idx_site_org_status` (`org_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='生产加工仓储与销售场所表';

-- 2.3 系统用户表
CREATE TABLE IF NOT EXISTS `app_user` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '所属组织ID',
    `username` VARCHAR(64) NOT NULL COMMENT '登录用户名',
    `display_name` VARCHAR(64) NOT NULL COMMENT '用户显示昵称',
    `password_hash` VARCHAR(255) NOT NULL COMMENT '加盐哈希密码(PBKDF2/BCrypt)',
    `job_type` VARCHAR(32) NULL COMMENT '岗位职责类型',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '用户状态(ACTIVE/INACTIVE/LOCKED)',
    `last_login_at` DATETIME(6) NULL COMMENT '最后登录时间',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`),
    KEY `idx_user_org` (`org_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统操作员与用户表';

-- 2.4 系统角色表
CREATE TABLE IF NOT EXISTS `role` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `role_code` VARCHAR(32) NOT NULL COMMENT '角色标识唯一代码',
    `name` VARCHAR(64) NOT NULL COMMENT '角色名称',
    `scope_type` VARCHAR(32) NOT NULL COMMENT '数据权限范围类型(ALL/ORG_ONLY/SITE_ONLY)',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态(ACTIVE/INACTIVE)',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统角色定义表';

-- 2.5 用户角色关联表
CREATE TABLE IF NOT EXISTS `user_role` (
    `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `role_id` BIGINT UNSIGNED NOT NULL COMMENT '角色ID',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '分配时间',
    PRIMARY KEY (`user_id`, `role_id`),
    KEY `idx_user_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户与角色关联表';

-- =============================================================================
-- 3. 主数据与规则域 (masterdata)
-- =============================================================================

-- 3.1 海产品基础信息表
CREATE TABLE IF NOT EXISTS `product` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `product_code` VARCHAR(32) NOT NULL COMMENT '产品全局统一编码',
    `public_name` VARCHAR(128) NOT NULL COMMENT '消费者公开名称',
    `scientific_name` VARCHAR(128) NULL COMMENT '水产品学名(拉丁学名)',
    `category` VARCHAR(32) NOT NULL COMMENT '水产大类(FISH/CRUSTACEAN/SHELLFISH/CEPHALOPOD/OTHER)',
    `specification` VARCHAR(128) NOT NULL COMMENT '产品包装与规格说明',
    `source_type` VARCHAR(32) NOT NULL COMMENT '来源类型(DOMESTIC_CAPTURE/DOMESTIC_FARMED/IMPORT)',
    `base_unit_code` VARCHAR(16) NOT NULL DEFAULT 'kg' COMMENT '基准计量单位',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '产品状态(DRAFT/ACTIVE/INACTIVE)',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_code` (`product_code`),
    KEY `idx_product_category` (`category`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='海产品基础主数据表';

-- 3.2 冷链温控基准规则主表
CREATE TABLE IF NOT EXISTS `temperature_rule` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `product_id` BIGINT UNSIGNED NOT NULL COMMENT '适用产品ID',
    `version_no` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '规则版本序号',
    `name` VARCHAR(128) NOT NULL COMMENT '规则方案名称',
    `effective_from` DATETIME(6) NOT NULL COMMENT '规则生效时间',
    `effective_to` DATETIME(6) NULL COMMENT '规则失效时间(空表示长期有效)',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态(DRAFT/ACTIVE/RETIRED)',
    `basis_note` TEXT NULL COMMENT '国标/行标制定依据说明',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    KEY `idx_rule_product` (`product_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='冷链温控基准规则主表';

-- 3.3 冷链温控规则环节明细表
CREATE TABLE IF NOT EXISTS `temperature_rule_stage` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `rule_id` BIGINT UNSIGNED NOT NULL COMMENT '关联温控规则主表ID',
    `stage_code` VARCHAR(32) NOT NULL COMMENT '供应链环节代码(STORAGE/TRANSPORT/PROCESSING/RETAIL)',
    `lower_limit` DECIMAL(6,2) NOT NULL COMMENT '温度下限阈值(摄氏度)',
    `upper_limit` DECIMAL(6,2) NOT NULL COMMENT '温度上限阈值(摄氏度)',
    `unit_code` VARCHAR(16) NOT NULL DEFAULT 'CELSIUS' COMMENT '温标单位',
    `allowed_duration_seconds` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '允许越界缓冲秒数',
    `sequence_no` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '环节展示次序',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    KEY `idx_stage_rule` (`rule_id`, `sequence_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='冷链温控规则环节明细表';

-- =============================================================================
-- 4. 批次与谱系域 (batch)
-- =============================================================================

-- 4.1 追溯批次主表
CREATE TABLE IF NOT EXISTS `batch` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '当前持有企业组织ID',
    `product_id` BIGINT UNSIGNED NOT NULL COMMENT '所属产品ID',
    `batch_no` VARCHAR(64) NOT NULL COMMENT '企业内部业务批次号',
    `batch_type` VARCHAR(24) NOT NULL COMMENT '批次环节类型(SOURCE/PROCESSING/DISTRIBUTION/SALE)',
    `quantity` DECIMAL(18,3) NOT NULL COMMENT '批次当前数量/重量',
    `unit_code` VARCHAR(16) NOT NULL DEFAULT 'kg' COMMENT '计量单位(kg/box/ton)',
    `origin_type` VARCHAR(32) NOT NULL COMMENT '来源类型(OFFSHORE_CATCH/FARM/IMPORT)',
    `origin_text` VARCHAR(255) NOT NULL COMMENT '企业端完整来源产地文本描述',
    `production_date` DATE NULL COMMENT '生产加工日期',
    `capture_date` DATE NULL COMMENT '捕捞出塘日期',
    `freeze_date` DATE NULL COMMENT '速冻完成日期',
    `shelf_life_days` INT UNSIGNED NULL COMMENT '保质期天数',
    `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '批次状态(DRAFT/ACTIVE/FROZEN/RECALLED/CLOSED)',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_batch_org_no` (`org_id`, `batch_no`),
    KEY `idx_batch_product_status` (`product_id`, `status`),
    KEY `idx_batch_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='追溯批次主表';

-- 4.2 批次操作与物料平衡记录表
CREATE TABLE IF NOT EXISTS `batch_operation` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '执行组织ID',
    `operation_no` VARCHAR(64) NOT NULL COMMENT '操作流水单号',
    `operation_type` VARCHAR(32) NOT NULL COMMENT '操作类型(MERGE/SPLIT/PROCESS/REPACK)',
    `occurred_at` DATETIME(6) NOT NULL COMMENT '业务实际发生UTC时间',
    `recorded_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '系统登记UTC时间',
    `status` VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED' COMMENT '状态(DRAFT/SUBMITTED/CORRECTED)',
    `idempotency_key` VARCHAR(64) NOT NULL COMMENT '防重提交幂等键',
    `note` VARCHAR(500) NULL COMMENT '操作备注说明',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_op_org_no` (`org_id`, `operation_no`),
    UNIQUE KEY `uk_op_idempotency` (`idempotency_key`),
    KEY `idx_op_org_time` (`org_id`, `occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批次操作与物料平衡记录表';

-- 4.3 批次操作明细项目表
CREATE TABLE IF NOT EXISTS `batch_operation_item` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `operation_id` BIGINT UNSIGNED NOT NULL COMMENT '关联操作主表ID',
    `batch_id` BIGINT UNSIGNED NULL COMMENT '关联内部批次ID(消耗或产生)',
    `role` VARCHAR(16) NOT NULL COMMENT '在操作中的角色(INPUT/OUTPUT/LOSS/WASTE/SAMPLE)',
    `quantity` DECIMAL(18,3) NOT NULL COMMENT '投入或产出原始数量',
    `unit_code` VARCHAR(16) NOT NULL DEFAULT 'kg' COMMENT '计量单位',
    `normalized_quantity` DECIMAL(18,3) NOT NULL COMMENT '折算为基准单位(kg)的数量',
    `conversion_rule_id` BIGINT UNSIGNED NULL COMMENT '适用的出成率/折算规则ID',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    KEY `idx_item_op` (`operation_id`),
    KEY `idx_item_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批次操作明细项目表';

-- 4.4 批次谱系父子关系图谱边表
CREATE TABLE IF NOT EXISTS `batch_relation` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `operation_id` BIGINT UNSIGNED NOT NULL COMMENT '引起关系的转换操作ID',
    `parent_batch_id` BIGINT UNSIGNED NOT NULL COMMENT '父批次ID(原料批次)',
    `child_batch_id` BIGINT UNSIGNED NOT NULL COMMENT '子批次ID(生成批次)',
    `relation_type` VARCHAR(32) NOT NULL COMMENT '关系类型(TRANSFORM/SPLIT/MERGE)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间(UTC)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_relation` (`operation_id`, `parent_batch_id`, `child_batch_id`),
    KEY `idx_relation_parent` (`parent_batch_id`),
    KEY `idx_relation_child` (`child_batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批次谱系父子关系图谱边表';

-- 4.5 对外公开追溯码绑定表
CREATE TABLE IF NOT EXISTS `public_trace_code` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '关联内部实体批次ID',
    `public_id` VARCHAR(40) NOT NULL COMMENT '消费者公开追溯编码(Base32/不可预测无规律)',
    `token_hash` CHAR(64) NOT NULL COMMENT '校验令牌SHA-256哈希',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态(ACTIVE/DISABLED/RECALLED)',
    `activated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '激活时间',
    `disabled_at` DATETIME(6) NULL COMMENT '停用/废弃时间',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trace_public_id` (`public_id`),
    UNIQUE KEY `uk_trace_token_hash` (`token_hash`),
    KEY `idx_trace_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对外公开追溯码绑定表';

-- =============================================================================
-- 5. 追溯与交接域 (trace)
-- =============================================================================

-- 5.1 供应链关键业务追溯事件表 (追加式不可篡改设计)
CREATE TABLE IF NOT EXISTS `trace_event` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '关联批次ID',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '记录组织ID',
    `site_id` BIGINT UNSIGNED NULL COMMENT '发生场所ID',
    `event_type` VARCHAR(32) NOT NULL COMMENT '事件类型(HARVEST/PROCESS/FREEZE/PACK/SHIP/RECEIVE/INSPECT/RETAIL)',
    `occurred_at` DATETIME(6) NOT NULL COMMENT '业务实际发生UTC时间',
    `recorded_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '系统登记UTC时间',
    `operator_id` BIGINT UNSIGNED NULL COMMENT '具体操作员用户ID',
    `data_source` VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT '来源(MANUAL/IMPORT/SIMULATED/DEVICE)',
    `status` VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED' COMMENT '状态(SUBMITTED/CORRECTED)',
    `corrects_event_id` BIGINT UNSIGNED NULL COMMENT '更正的目标原事件ID(无更正为NULL)',
    `summary` VARCHAR(500) NOT NULL COMMENT '事件核心摘要说明',
    `details_json` JSON NULL COMMENT '结构化业务扩展属性',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '入库时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    KEY `idx_event_batch_time` (`batch_id`, `occurred_at`),
    KEY `idx_event_org_type` (`org_id`, `event_type`, `recorded_at`),
    KEY `idx_event_corrects` (`corrects_event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应链关键业务追溯事件表(追加式)';

-- 5.2 冷链运输任务表
CREATE TABLE IF NOT EXISTS `shipment` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `shipment_no` VARCHAR(64) NOT NULL COMMENT '运输单号',
    `carrier_org_id` BIGINT UNSIGNED NOT NULL COMMENT '承运物流企业ID',
    `vehicle_or_container_no` VARCHAR(64) NOT NULL COMMENT '车牌号或冷藏集装箱编号',
    `origin_site_id` BIGINT UNSIGNED NOT NULL COMMENT '启运场所ID',
    `destination_site_id` BIGINT UNSIGNED NOT NULL COMMENT '目的场所ID',
    `loaded_at` DATETIME(6) NOT NULL COMMENT '装车发运时间',
    `unloaded_at` DATETIME(6) NULL COMMENT '到达卸货时间',
    `required_rule_id` BIGINT UNSIGNED NULL COMMENT '适用的在途温控规则ID',
    `status` VARCHAR(16) NOT NULL DEFAULT 'IN_TRANSIT' COMMENT '状态(PLANNED/IN_TRANSIT/DELIVERED/CANCELLED)',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_shipment_no` (`shipment_no`),
    KEY `idx_shipment_carrier` (`carrier_org_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='冷链运输任务表';

-- 5.3 企业间批次交接凭证表
CREATE TABLE IF NOT EXISTS `transfer` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `transfer_no` VARCHAR(64) NOT NULL COMMENT '交接凭单唯一业务号',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '交接批次ID',
    `shipment_id` BIGINT UNSIGNED NULL COMMENT '关联运输单ID',
    `sender_org_id` BIGINT UNSIGNED NOT NULL COMMENT '发货方企业ID',
    `receiver_org_id` BIGINT UNSIGNED NOT NULL COMMENT '收货方企业ID',
    `quantity` DECIMAL(18,3) NOT NULL COMMENT '发货声明数量',
    `unit_code` VARCHAR(16) NOT NULL DEFAULT 'kg' COMMENT '计量单位',
    `shipped_at` DATETIME(6) NOT NULL COMMENT '发货确认时间',
    `received_at` DATETIME(6) NULL COMMENT '收货验收时间',
    `recorded_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '系统登记时间',
    `received_quantity` DECIMAL(18,3) NULL COMMENT '实收数量(允许磅差差异)',
    `difference_reason` VARCHAR(500) NULL COMMENT '数量差异原因说明',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '交接状态(DRAFT/PENDING/ACCEPTED/REJECTED)',
    `rejection_reason` VARCHAR(500) NULL COMMENT '拒收原因说明',
    `idempotency_key` VARCHAR(64) NOT NULL COMMENT '防重复交接幂等键',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_transfer_no` (`transfer_no`),
    UNIQUE KEY `uk_transfer_idempotency` (`idempotency_key`),
    KEY `idx_transfer_sender` (`sender_org_id`, `status`),
    KEY `idx_transfer_receiver` (`receiver_org_id`, `status`),
    KEY `idx_transfer_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业间批次交接凭证表';

-- =============================================================================
-- 6. 质检、温控与风险域 (quality)
-- =============================================================================

-- 6.1 温控明细时序数据表
CREATE TABLE IF NOT EXISTS `temperature_record` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `batch_id` BIGINT UNSIGNED NULL COMMENT '绑定批次ID(仓储环节常用)',
    `shipment_id` BIGINT UNSIGNED NULL COMMENT '绑定运输单ID(在途环节常用)',
    `stage_code` VARCHAR(32) NOT NULL COMMENT '环节代码(STORAGE/TRANSPORT)',
    `measured_at` DATETIME(6) NOT NULL COMMENT '测温发生时间',
    `recorded_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '数据上传时间',
    `temperature` DECIMAL(6,2) NOT NULL COMMENT '实测温度值(摄氏度)',
    `unit_code` VARCHAR(8) NOT NULL DEFAULT 'CELSIUS' COMMENT '温标单位',
    `data_source` VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT '来源(MANUAL/IMPORT/SIMULATED/DEVICE)',
    `device_no` VARCHAR(64) NULL COMMENT '温度计/探头设备编号',
    `rule_stage_id` BIGINT UNSIGNED NULL COMMENT '判定依据规则环节ID',
    `evaluation` VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '超温判定(NORMAL/HIGH/LOW/MISSING_CONTEXT)',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    KEY `idx_temp_batch` (`batch_id`, `measured_at`),
    KEY `idx_temp_shipment` (`shipment_id`, `measured_at`),
    KEY `idx_temp_eval` (`evaluation`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='温控明细时序数据表';

-- 6.2 批次检验检测摘要报告表
CREATE TABLE IF NOT EXISTS `inspection_report` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '关联批次ID',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '送检组织ID',
    `report_no` VARCHAR(64) NOT NULL COMMENT '质检报告单号',
    `institution_name` VARCHAR(128) NOT NULL COMMENT '检验检测机构名称',
    `inspected_at` DATETIME(6) NOT NULL COMMENT '检验采样完成时间',
    `recorded_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '系统登记时间',
    `items_summary` VARCHAR(500) NOT NULL COMMENT '检测项目摘要(微生物/理化/重金属等)',
    `conclusion` VARCHAR(16) NOT NULL DEFAULT 'PASS' COMMENT '检验结论(PASS/FAIL/PENDING)',
    `institution_verified` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '机构资质是否核验',
    `data_source` VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT '数据来源(MANUAL/IMPORT)',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report_no` (`org_id`, `report_no`),
    KEY `idx_report_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批次检验检测摘要报告表';

-- 6.3 质量与温控异常告警表
CREATE TABLE IF NOT EXISTS `alert` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '归属企业ID',
    `batch_id` BIGINT UNSIGNED NULL COMMENT '涉及批次ID',
    `shipment_id` BIGINT UNSIGNED NULL COMMENT '涉及运输单ID',
    `alert_type` VARCHAR(32) NOT NULL COMMENT '告警类型(TEMP_OVER_UPPER/TEMP_UNDER_LOWER/INSPECT_FAIL/MATERIAL_IMBALANCE)',
    `severity` VARCHAR(16) NOT NULL DEFAULT 'MEDIUM' COMMENT '严重级别(LOW/MEDIUM/HIGH/CRITICAL)',
    `trigger_rule_id` BIGINT UNSIGNED NULL COMMENT '触发规则ID',
    `triggered_at` DATETIME(6) NOT NULL COMMENT '告警触发时间',
    `status` VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '状态(OPEN/ACKNOWLEDGED/PROCESSING/RESOLVED/DISMISSED)',
    `assignee_id` BIGINT UNSIGNED NULL COMMENT '处置责任人ID',
    `resolution` VARCHAR(500) NULL COMMENT '处置说明与结论',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    KEY `idx_alert_org_status` (`org_id`, `status`, `severity`, `created_at`),
    KEY `idx_alert_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='质量与温控异常告警表';

-- 6.4 模拟召回事件主表
CREATE TABLE IF NOT EXISTS `recall` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `recall_no` VARCHAR(64) NOT NULL COMMENT '召回事件业务唯一编号',
    `owner_org_id` BIGINT UNSIGNED NOT NULL COMMENT '发起处置企业组织ID',
    `reason` VARCHAR(500) NOT NULL COMMENT '发起召回原因说明',
    `severity` VARCHAR(16) NOT NULL DEFAULT 'HIGH' COMMENT '召回响应级别(LOW/MEDIUM/HIGH/CRITICAL)',
    `policy_version` VARCHAR(32) NOT NULL DEFAULT 'v1.0' COMMENT '所依据应急预案版本',
    `started_at` DATETIME(6) NOT NULL COMMENT '演练/处置启动时间',
    `closed_at` DATETIME(6) NULL COMMENT '处置关闭完成时间',
    `notification_summary` VARCHAR(1000) NULL COMMENT '下游通知与触达概要',
    `result_summary` VARCHAR(1000) NULL COMMENT '最终处置与评估结论',
    `status` VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS' COMMENT '状态(DRAFT/IN_PROGRESS/CLOSED/CANCELLED)',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_recall_no` (`recall_no`),
    KEY `idx_recall_org_status` (`owner_org_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模拟召回事件主表';

-- 6.5 召回影响批次范围与数量确认表
CREATE TABLE IF NOT EXISTS `recall_batch` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `recall_id` BIGINT UNSIGNED NOT NULL COMMENT '关联召回主表ID',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '被圈定波及批次ID',
    `scope_type` VARCHAR(32) NOT NULL COMMENT '分布环节(SOURCE/INVENTORY/IN_TRANSIT/SOLD)',
    `affected_quantity` DECIMAL(18,3) NOT NULL COMMENT '圈定波及数量',
    `notified_quantity` DECIMAL(18,3) NOT NULL DEFAULT 0.000 COMMENT '已触达通知数量',
    `returned_quantity` DECIMAL(18,3) NOT NULL DEFAULT 0.000 COMMENT '已退运/召回数量',
    `disposed_quantity` DECIMAL(18,3) NOT NULL DEFAULT 0.000 COMMENT '已无害化销毁数量',
    `unit_code` VARCHAR(16) NOT NULL DEFAULT 'kg' COMMENT '计量单位',
    `scope_confirmed_at` DATETIME(6) NOT NULL COMMENT '范围确认时间',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_recall_batch` (`recall_id`, `batch_id`),
    KEY `idx_recall_batch_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='召回影响批次范围与数量确认表';

-- =============================================================================
-- 7. 受控附件域 (attachment)
-- =============================================================================

-- 7.1 受控文件与单据元数据表
CREATE TABLE IF NOT EXISTS `attachment` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `owner_org_id` BIGINT UNSIGNED NOT NULL COMMENT '归属组织ID',
    `object_type` VARCHAR(32) NOT NULL COMMENT '关联业务实体类型(BATCH/INSPECTION/TRANSFER/RECALL)',
    `object_id` BIGINT UNSIGNED NOT NULL COMMENT '关联业务实体ID',
    `original_name` VARCHAR(255) NOT NULL COMMENT '用户上传原始文件名',
    `storage_key` VARCHAR(128) NOT NULL COMMENT '受控存储定位键(相对路径或UUID文件名)',
    `mime_type` VARCHAR(64) NOT NULL COMMENT '文件MIME类型',
    `size_bytes` BIGINT UNSIGNED NOT NULL COMMENT '文件大小(字节数)',
    `sha256` CHAR(64) NOT NULL COMMENT '文件内容SHA-256完整性哈希',
    `access_level` VARCHAR(16) NOT NULL DEFAULT 'RELATED_CHAIN' COMMENT '权限级别(PRIVATE/RELATED_CHAIN/PUBLIC)',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态(ACTIVE/VOID)',
    `void_reason` VARCHAR(255) NULL COMMENT '作废失效原因说明',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    `created_by` BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC更新时间',
    `updated_by` BIGINT UNSIGNED NULL COMMENT '更新人用户ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_attachment_key` (`storage_key`),
    KEY `idx_attachment_object` (`object_type`, `object_id`),
    KEY `idx_attachment_org` (`owner_org_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='受控文件与单据元数据表';

-- =============================================================================
-- 8. 审计日志域 (audit)
-- =============================================================================

-- 8.1 系统追加式操作审计日志表
CREATE TABLE IF NOT EXISTS `audit_log` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `request_id` VARCHAR(64) NOT NULL COMMENT '全链路追踪Request-Id',
    `actor_user_id` BIGINT UNSIGNED NULL COMMENT '操作人用户ID',
    `actor_org_id` BIGINT UNSIGNED NULL COMMENT '操作人所属组织ID',
    `action` VARCHAR(64) NOT NULL COMMENT '业务动作标识(CREATE/UPDATE/DELETE/FREEZE/TRANSFER)',
    `object_type` VARCHAR(64) NOT NULL COMMENT '被操作对象类型',
    `object_id` BIGINT UNSIGNED NULL COMMENT '被操作对象ID',
    `occurred_at` DATETIME(6) NOT NULL COMMENT '操作发生UTC时间',
    `result` VARCHAR(16) NOT NULL COMMENT '操作结果(SUCCESS/DENIED/FAILED)',
    `change_summary_json` JSON NULL COMMENT '结构化变更快照明细',
    `client_ip_hash` CHAR(64) NULL COMMENT '加盐哈希脱敏客户端IP',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '入库记录时间',
    PRIMARY KEY (`id`),
    KEY `idx_audit_org_time` (`actor_org_id`, `occurred_at`),
    KEY `idx_audit_obj_time` (`object_type`, `object_id`, `occurred_at`),
    KEY `idx_audit_request_id` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统追加式操作审计日志表';

SET FOREIGN_KEY_CHECKS = 1;
