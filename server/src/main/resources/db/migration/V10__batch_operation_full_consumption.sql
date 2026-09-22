-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V10
-- 任务编号: Phase A Slice 3 (PROCESS / SPLIT)
-- 业务基线: docs/BUSINESS_CONTRACT_V1.1.md §4 / §5 / §6 / §11，docs/DEMO_MVP_ROADMAP.md §5.3
-- 说明:
--   1. 前置条件 (fail-fast，不伪造任何业务数据)：
--      不存在未删除的 DRAFT 批次操作 —— 旧协议草稿引用客户端预先激活的输出批次，
--      在"输出批次由服务端生成并随操作提交原子激活"的新协议下永远无法提交，
--      把它们改造成新协议需要凭空捏造输出草稿批次，迁移不替人工做此决定。已 SUBMITTED 的历史操作原样保留。
--   2. batch 表：
--      - produced_by_operation_id：产出该批次的批次操作 (服务端生成的输出批次)；
--      - consumed_by_operation_id：全量消耗该批次的已提交批次操作；
--      - 外键指向 batch_operation；CHECK：被操作全量消耗的批次必须为 CLOSED；
--      - 不回填：历史批次保持 NULL，表示"非新协议产出 / 未被新协议全量消耗"。
--        产出批次的 batch_type 不做数据库约束：PROCESS 产出为 PROCESSING，SPLIT 产出继承输入批次类型。
--   3. batch_operation_item 表：
--      - 角色与批次引用形状 CHECK：INPUT/OUTPUT 必须引用批次，LOSS/WASTE/SAMPLE 严禁引用批次；
--      - output_batch_id 虚拟生成列 + 唯一索引：一个批次至多作为一个未删除操作的 OUTPUT；
--      - UNIQUE (operation_id, batch_id)：同一操作内同一批次只出现一次；
--      - 外键：operation_id → batch_operation，batch_id → batch。
--   4. batch_relation 表：外键与 UNIQUE (parent_batch_id, child_batch_id) (禁止重复上游边)。
--   5. 本迁移不引入 Sale、仓储、温度、告警、召回或 QUARANTINED 相关结构；chk_op_type / chk_op_status 保持不变。
-- =============================================================================

-- 0. 前置条件校验：条件不满足时向 NOT NULL 列写入 NULL，迁移立即失败。
CREATE TEMPORARY TABLE `tmp_v10_precondition` (
    `check_name` VARCHAR(64) NOT NULL COMMENT '前置条件名称',
    `passed` CHAR(1) NOT NULL COMMENT '通过标记'
);
INSERT INTO `tmp_v10_precondition` (`check_name`, `passed`)
VALUES ('no_legacy_draft_batch_operation',
        IF((SELECT COUNT(*) FROM `batch_operation` WHERE `status` = 'DRAFT' AND `is_deleted` = 0) = 0, 'Y', NULL));
DROP TEMPORARY TABLE `tmp_v10_precondition`;

-- 1. batch：产出 / 全量消耗操作引用
ALTER TABLE `batch`
    ADD COLUMN `produced_by_operation_id` BIGINT UNSIGNED NULL COMMENT '产出该批次的批次操作ID(服务端生成的输出批次)' AFTER `risk_status`,
    ADD COLUMN `consumed_by_operation_id` BIGINT UNSIGNED NULL COMMENT '全量消耗该批次的已提交批次操作ID' AFTER `produced_by_operation_id`;

ALTER TABLE `batch`
    ADD CONSTRAINT `fk_batch_produced_by_operation` FOREIGN KEY (`produced_by_operation_id`) REFERENCES `batch_operation` (`id`),
    ADD CONSTRAINT `fk_batch_consumed_by_operation` FOREIGN KEY (`consumed_by_operation_id`) REFERENCES `batch_operation` (`id`),
    ADD CONSTRAINT `chk_batch_consumed_closed` CHECK (`consumed_by_operation_id` IS NULL OR `flow_status` = 'CLOSED');

-- 2. batch_operation_item：角色形状、唯一产出、同操作不重复与外键
ALTER TABLE `batch_operation_item`
    ADD COLUMN `output_batch_id` BIGINT UNSIGNED GENERATED ALWAYS AS (
        CASE WHEN `role` = 'OUTPUT' AND `is_deleted` = 0 THEN `batch_id` ELSE NULL END
    ) VIRTUAL COMMENT '未删除 OUTPUT 项目的批次ID(唯一产出约束专用)' AFTER `batch_id`;

ALTER TABLE `batch_operation_item`
    ADD CONSTRAINT `chk_item_batch_shape` CHECK (
        (`role` IN ('INPUT', 'OUTPUT') AND `batch_id` IS NOT NULL)
        OR (`role` IN ('LOSS', 'WASTE', 'SAMPLE') AND `batch_id` IS NULL)
    ),
    ADD CONSTRAINT `uk_item_output_batch` UNIQUE (`output_batch_id`),
    ADD CONSTRAINT `uk_item_op_batch` UNIQUE (`operation_id`, `batch_id`),
    ADD CONSTRAINT `fk_item_operation` FOREIGN KEY (`operation_id`) REFERENCES `batch_operation` (`id`),
    ADD CONSTRAINT `fk_item_batch` FOREIGN KEY (`batch_id`) REFERENCES `batch` (`id`);

-- 3. batch_relation：外键与禁止重复上游边
ALTER TABLE `batch_relation`
    ADD CONSTRAINT `uk_relation_parent_child` UNIQUE (`parent_batch_id`, `child_batch_id`),
    ADD CONSTRAINT `fk_relation_operation` FOREIGN KEY (`operation_id`) REFERENCES `batch_operation` (`id`),
    ADD CONSTRAINT `fk_relation_parent_batch` FOREIGN KEY (`parent_batch_id`) REFERENCES `batch` (`id`),
    ADD CONSTRAINT `fk_relation_child_batch` FOREIGN KEY (`child_batch_id`) REFERENCES `batch` (`id`);
