-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V2
-- 任务编号: GitHub Issue #9
-- 说明: 补充产品主数据与温控规则的 CHECK 双重约束与复合唯一性索引
-- =============================================================================

-- 1. 海产品基础信息表：补充枚举值域与基准单位 CHECK 约束
ALTER TABLE `product`
    ADD CONSTRAINT `chk_product_category` CHECK (`category` IN ('FISH', 'CRUSTACEAN', 'SHELLFISH', 'CEPHALOPOD', 'OTHER')),
    ADD CONSTRAINT `chk_product_source_type` CHECK (`source_type` IN ('DOMESTIC_CAPTURE', 'DOMESTIC_FARMED', 'IMPORT')),
    ADD CONSTRAINT `chk_product_status` CHECK (`status` IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
    ADD CONSTRAINT `chk_product_base_unit_code` CHECK (`base_unit_code` = 'kg');

-- 2. 温控规则主表：同一产品下的版本序号唯一，状态枚举与生效时间区间 CHECK 约束
ALTER TABLE `temperature_rule`
    ADD CONSTRAINT `uk_rule_product_version` UNIQUE (`product_id`, `version_no`),
    ADD CONSTRAINT `chk_rule_status` CHECK (`status` IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    ADD CONSTRAINT `chk_rule_effective_period` CHECK (`effective_to` IS NULL OR `effective_to` > `effective_from`);

-- 3. 温控规则环节明细表：同一规则方案下的环节代码唯一，环节枚举、温标、阈值及数值 CHECK 约束
ALTER TABLE `temperature_rule_stage`
    ADD CONSTRAINT `uk_stage_rule_stage_code` UNIQUE (`rule_id`, `stage_code`),
    ADD CONSTRAINT `chk_stage_code` CHECK (`stage_code` IN ('PROCESSING', 'STORAGE', 'TRANSPORT', 'RETAIL')),
    ADD CONSTRAINT `chk_stage_unit_code` CHECK (`unit_code` = 'CELSIUS'),
    ADD CONSTRAINT `chk_stage_limits` CHECK (`lower_limit` <= `upper_limit`),
    ADD CONSTRAINT `chk_stage_duration` CHECK (`allowed_duration_seconds` >= 0),
    ADD CONSTRAINT `chk_stage_sequence` CHECK (`sequence_no` >= 1);
