-- =============================================================================
-- 冷冻海产品溯源系统 - 数据库版本迁移脚本 V20
-- 说明:
--   补充两个QA账号:
--   - capt_qa (org=1 捕捞舰队): 捕捞船长出货质检
--   - whl_qa (org=3 批发分装): 批发分装出货质检
--   均绑定 ROLE_ORG_QA (role_id=3),密码 demo1234
-- =============================================================================

-- 1. 新增两个QA用户(捕捞舰队质管 + 批发分装质管)
INSERT INTO app_user (id, org_id, username, display_name, password_hash, job_type, status, created_at) VALUES
(9, 1, 'capt_qa', '捕捞舰队质管', '{noop}demo1234', 'QUALITY_MANAGER', 'ACTIVE', NOW(6)),
(10, 3, 'whl_qa', '批发分装质管', '{noop}demo1234', 'QUALITY_MANAGER', 'ACTIVE', NOW(6))
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

-- 2. 绑定 ROLE_ORG_QA 角色(role_id=3)
INSERT INTO user_role (user_id, role_id, created_at) VALUES
(9, 3, NOW(6)),
(10, 3, NOW(6))
ON DUPLICATE KEY UPDATE created_at = NOW(6);
