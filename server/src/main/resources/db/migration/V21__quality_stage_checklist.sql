-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V21
-- 说明:
--   1. 质检单表加 inspection_stage(环节) + related_order_id(关联订单) + checklist_passed_at
--   2. 新建质检清单模板表 quality_checklist_template（按环节配置清单项）
--   3. 写入三个环节的种子清单数据:
--      CAPTURE_OUT  捕捞出货质检 (org=1 capt_qa)
--      PROCESS_OUT  加工出货质检 (org=2 plant_qa)
--      DIST_OUT     批发分装出货质检 (org=3 whl_qa)
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 21.1 质检单表加字段
ALTER TABLE `quality_inspection`
    ADD COLUMN `inspection_stage` VARCHAR(24) NULL COMMENT '质检环节(CAPTURE_OUT/PROCESS_OUT/DIST_OUT)' AFTER `inspection_type`,
    ADD COLUMN `related_order_id` BIGINT UNSIGNED NULL COMMENT '关联订单ID(提交质检审核时绑定)' AFTER `inspection_stage`,
    ADD COLUMN `checklist_passed_at` DATETIME(6) NULL COMMENT '清单全部通过时间(UTC)' AFTER `checklist_json`;

-- 给 inspection_stage 加索引方便按环节过滤
ALTER TABLE `quality_inspection`
    ADD KEY `idx_qc_stage` (`org_id`, `inspection_stage`, `result`);

-- 21.2 质检清单模板表
CREATE TABLE IF NOT EXISTS `quality_checklist_template` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `stage_code` VARCHAR(24) NOT NULL COMMENT '环节编码(CAPTURE_OUT/PROCESS_OUT/DIST_OUT)',
    `org_type` VARCHAR(24) NOT NULL COMMENT '组织类型(SOURCE/PROCESSOR/DISTRIBUTOR)',
    `item_name` VARCHAR(100) NOT NULL COMMENT '清单项名称',
    `item_desc` VARCHAR(500) NULL COMMENT '清单项说明',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序序号',
    `is_required` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否必检(0:否,1:是)',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态(ACTIVE/INACTIVE)',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_qct_stage` (`stage_code`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='质检清单模板';

-- 21.3 种子数据:三个环节的清单项

-- 环节1: 捕捞出货质检 (capt_qa, org=1 捕捞舰队)
INSERT INTO quality_checklist_template (stage_code, org_type, item_name, item_desc, sort_order, is_required, status) VALUES
('CAPTURE_OUT', 'SOURCE', '渔获新鲜度', '检查渔获色泽、气味、眼球澄清度，确认新鲜', 1, 1, 'ACTIVE'),
('CAPTURE_OUT', 'SOURCE', '冷链温度', '船载冷库温度记录 -18℃以下', 2, 1, 'ACTIVE'),
('CAPTURE_OUT', 'SOURCE', '捕捞海域合规', '确认捕捞海域在合规作业范围内', 3, 1, 'ACTIVE'),
('CAPTURE_OUT', 'SOURCE', '渔获重量核对', '核对实际重量与申报量一致', 4, 1, 'ACTIVE'),
('CAPTURE_OUT', 'SOURCE', '包装完整性', '检查包装无破损、无二次拆封痕迹', 5, 1, 'ACTIVE');

-- 环节2: 加工出货质检 (plant_qa, org=2 加工厂)
INSERT INTO quality_checklist_template (stage_code, org_type, item_name, item_desc, sort_order, is_required, status) VALUES
('PROCESS_OUT', 'PROCESSOR', '速冻中心温度', '速冻车间中心温度达 -35℃以下', 1, 1, 'ACTIVE'),
('PROCESS_OUT', 'PROCESSOR', '成品包装密封', '真空包装无漏气、封口完整', 2, 1, 'ACTIVE'),
('PROCESS_OUT', 'PROCESSOR', '金属异物检测', '过金属检测仪，无报警记录', 3, 1, 'ACTIVE'),
('PROCESS_OUT', 'PROCESSOR', '微生物抽检', '菌落总数、大肠菌群检测合格', 4, 1, 'ACTIVE'),
('PROCESS_OUT', 'PROCESSOR', '标签信息完整', '生产日期、保质期、批号、产地齐全', 5, 1, 'ACTIVE'),
('PROCESS_OUT', 'PROCESSOR', '冷链仓储温度', '成品冷库 -18℃以下持续记录', 6, 1, 'ACTIVE');

-- 环节3: 批发分装出货质检 (whl_qa, org=3 批发分装)
INSERT INTO quality_checklist_template (stage_code, org_type, item_name, item_desc, sort_order, is_required, status) VALUES
('DIST_OUT', 'DISTRIBUTOR', '分装重量准确', '每袋实际重量与标注重量误差在±2%以内', 1, 1, 'ACTIVE'),
('DIST_OUT', 'DISTRIBUTOR', '标签完整性', '溯源码、批号、生产日期、保质期标签齐全', 2, 1, 'ACTIVE'),
('DIST_OUT', 'DISTRIBUTOR', '冷链中转温度', '中转冷库 -18℃以下，装车前温度达标', 3, 1, 'ACTIVE'),
('DIST_OUT', 'DISTRIBUTOR', '外包装完好', '纸箱无破损、无受潮、无挤压变形', 4, 1, 'ACTIVE'),
('DIST_OUT', 'DISTRIBUTOR', '分装批次溯源绑定', '确认分装批次已绑定上游溯源码', 5, 1, 'ACTIVE');

SET FOREIGN_KEY_CHECKS = 1;
