-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V11
-- 说明:
--   批次(Batch)/批次操作(BatchOperation)/交接(Transfer)/追溯事件(TraceEvent)/
--   公开追溯码 等企业写操作在服务端校验 principal.getRoles().contains("OPERATOR")，
--   即要求用户持有 role_code 恰为 'OPERATOR' 的角色。
--   V9 演示账号仅绑定 ROLE_ORG_OPERATOR/ROLE_ORG_QA，缺 OPERATOR 角色，
--   导致以上写接口全部 403。本脚本新增 OPERATOR 角色并绑定到各业务岗位演示账号，
--   以便完整跑通"原料->加工->打包->运输交接->零售->消费者"业务链路。
--   注意：role 表 id=4 已由 V9 固定为 ROLE_AUDIT_VIEWER，此处不可复用，故采用 id=6。
-- =============================================================================

-- 1. 修复 V9 预留的 ROLE_AUDIT_VIEWER 名称（避免被旧的临时脚本改成"企业操作员"）
UPDATE `role` SET `name` = '审计查看者' WHERE `id` = 4;

-- 2. 撤除任何误绑定到 ROLE_AUDIT_VIEWER 的演示业务岗位账号
DELETE FROM `user_role` WHERE `role_id` = 4 AND `user_id` IN (2, 3, 5, 6, 7);

-- 3. 新增企业操作员角色（role_code 必须为 OPERATOR，业务写接口按此校验）
INSERT INTO `role` (id, role_code, name, scope_type, status, created_at)
VALUES (6, 'OPERATOR', '企业操作员', 'ORG_ONLY', 'ACTIVE', NOW(6))
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 4. 为各业务岗位演示账号绑定 OPERATOR 角色
--    capt_ship(2) 捕捞船长 / plant_op(3) 加工厂操作员 / whl_op(5) 批发开单员
--    market_op(6) 超市采购员 / shop_op(7) 电商商家运营
INSERT INTO `user_role` (user_id, role_id, created_at) VALUES
(2, 6, NOW(6)),
(3, 6, NOW(6)),
(5, 6, NOW(6)),
(6, 6, NOW(6)),
(7, 6, NOW(6))
ON DUPLICATE KEY UPDATE created_at = NOW(6);