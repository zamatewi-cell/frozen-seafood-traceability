-- =============================================================================
-- Flyway Database Migration: V9__demo_auth_and_orders.sql
-- Description: 演示种子数据（多角色账号 + 商品 + B2B采购单 + B2C销售单）
-- 说明:
--   1. 密码使用 DelegatingPasswordEncoder 的 {noop} 前缀，演示登录密码统一为 demo1234
--   2. 显式指定 ID 保证引用关系确定；仅用于本地演示，不写入生产环境
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 9.1 组织（供应链多角色）
INSERT INTO organization (id, org_no, name, org_type, status, created_at) VALUES
(1, 'ORG-SOURCE-001', '蓝海远洋捕捞舰队', 'SOURCE', 'ACTIVE', NOW(6)),
(2, 'ORG-PRO-001', '深海鲜冻加工厂', 'PROCESSOR', 'ACTIVE', NOW(6)),
(3, 'ORG-DIST-001', '南海冷链分装批发中心', 'DISTRIBUTOR', 'ACTIVE', NOW(6)),
(4, 'ORG-RETAIL-001', '鲜活优选连锁超市', 'RETAILER', 'ACTIVE', NOW(6)),
(5, 'ORG-RETAIL-002', '亿家生鲜线上商家', 'RETAILER', 'ACTIVE', NOW(6)),
(6, 'ORG-CARRIER-001', '极速冷链物流', 'CARRIER', 'ACTIVE', NOW(6))
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 9.2 场所
INSERT INTO site (id, org_id, site_no, name, site_type, address_text, timezone, status, created_at) VALUES
(1, 1, 'ST001', '东海捕捞母港', 'PORT', '浙江舟山捕捞母港', 'Asia/Shanghai', 'ACTIVE', NOW(6)),
(2, 2, 'ST001', '一号速冻车间', 'FACTORY', '山东青岛保税加工区1号', 'Asia/Shanghai', 'ACTIVE', NOW(6)),
(3, 2, 'ST002', '冷库A区', 'COLD_STORE', '山东青岛保税加工区冷库A', 'Asia/Shanghai', 'ACTIVE', NOW(6)),
(4, 3, 'ST001', '南海分装作业中心', 'FACTORY', '广东广州黄埔分装中心', 'Asia/Shanghai', 'ACTIVE', NOW(6)),
(5, 3, 'ST002', '中转冷库', 'COLD_STORE', '广东广州中转冷库2', 'Asia/Shanghai', 'ACTIVE', NOW(6)),
(6, 4, 'ST001', '鲜活优选总店', 'STORE', '上海市静安区南京西路88号', 'Asia/Shanghai', 'ACTIVE', NOW(6)),
(7, 5, 'ST001', '亿家生鲜-华南仓', 'LOGISTICS_HUB', '广东省深圳市南山区', 'Asia/Shanghai', 'ACTIVE', NOW(6)),
(8, 6, 'ST001', '极速冷链华南车队', 'LOGISTICS_HUB', '广东省广州市白云区冷链车队', 'Asia/Shanghai', 'ACTIVE', NOW(6))
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 9.3 角色
INSERT INTO role (id, role_code, name, scope_type, status, created_at) VALUES
(1, 'ROLE_SYS_ADMIN', '系统管理员', 'ALL', 'ACTIVE', NOW(6)),
(2, 'ROLE_ORG_OPERATOR', '企业操作员', 'ORG_ONLY', 'ACTIVE', NOW(6)),
(3, 'ROLE_ORG_QA', '企业质量管理员', 'ORG_ONLY', 'ACTIVE', NOW(6)),
(4, 'ROLE_AUDIT_VIEWER', '审计查看者', 'ORG_ONLY', 'ACTIVE', NOW(6)),
(5, 'ROLE_CONSUMER', '消费者', 'PUBLIC', 'ACTIVE', NOW(6))
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 9.4 用户（演示登录密码统一为 demo1234，用 {noop} 前缀便于种子脚本直插）
INSERT INTO app_user (id, org_id, username, display_name, password_hash, job_type, status, created_at) VALUES
(1, 1, 'sys_admin', '系统管理员', '{noop}demo1234', 'ADMIN', 'ACTIVE', NOW(6)),
(2, 1, 'capt_ship', '捕捞船长', '{noop}demo1234', 'SOURCE_OPERATOR', 'ACTIVE', NOW(6)),
(3, 2, 'plant_op', '加工厂操作员', '{noop}demo1234', 'PROCESS_OPERATOR', 'ACTIVE', NOW(6)),
(4, 2, 'plant_qa', '加工厂质管', '{noop}demo1234', 'QUALITY_MANAGER', 'ACTIVE', NOW(6)),
(5, 3, 'whl_op', '批发开单员', '{noop}demo1234', 'DIST_OPERATOR', 'ACTIVE', NOW(6)),
(6, 4, 'market_op', '超市采购员', '{noop}demo1234', 'RETAIL_PURCHASE', 'ACTIVE', NOW(6)),
(7, 5, 'shop_op', '电商商家运营', '{noop}demo1234', 'RETAIL_SALES', 'ACTIVE', NOW(6)),
(8, 4, 'market_qa', '超市质管', '{noop}demo1234', 'QUALITY_MANAGER', 'ACTIVE', NOW(6))
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

-- 9.5 用户-角色
INSERT INTO user_role (user_id, role_id, created_at) VALUES
(1, 1, NOW(6)),
(2, 2, NOW(6)),
(3, 2, NOW(6)),
(4, 3, NOW(6)),
(5, 2, NOW(6)),
(6, 2, NOW(6)),
(7, 2, NOW(6)),
(8, 3, NOW(6))
ON DUPLICATE KEY UPDATE created_at = NOW(6);

-- 9.6 商品主数据
INSERT INTO product (id, product_code, public_name, scientific_name, category, specification, source_type, base_unit_code, status, created_at) VALUES
(1, 'P-FISH-0001', '东海带鱼', 'Trichiurus lepturus', 'FISH', '5kg/箱 冷冻', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE', NOW(6)),
(2, 'P-CRUST-0001', '南美白对虾', 'Litopenaeus vannamei', 'CRUSTACEAN', '2kg/盒 去壳', 'DOMESTIC_FARMED', 'kg', 'ACTIVE', NOW(6)),
(3, 'P-FISH-0002', '挪威冰鲜三文鱼', 'Salmo salar', 'FISH', '2.5kg/条 冷链冰鲜', 'IMPORT', 'kg', 'ACTIVE', NOW(6)),
(4, 'P-SHELL-0001', '冷冻扇贝柱', 'Patinopecten yessoensis', 'SHELLFISH', '500g/袋 单冻', 'DOMESTIC_FARMED', 'kg', 'ACTIVE', NOW(6))
ON DUPLICATE KEY UPDATE public_name = VALUES(public_name);

-- 9.7 B2B 采购进货单：覆盖 加工厂进原料 / 超市进货 / 商家买成品
-- PO-A：加工厂向捕捞舰队进原料（对应“屠宰场进货原料”场景）
INSERT INTO purchase_order (id, order_no, buyer_org_id, seller_org_id, order_type, status, ordered_at, expected_delivery_at, amount_total, currency_code, note, created_at, created_by) VALUES
(1, 'PO202609010001', 2, 1, 'RAW_MATERIAL', 'RECEIVED', DATE_SUB(NOW(6), INTERVAL 2 DAY), DATE_SUB(NOW(6), INTERVAL 1 DAY), 14000.00, 'CNY', '加工厂订购东海带鱼原料一批', DATE_SUB(NOW(6), INTERVAL 2 DAY), 3);
INSERT INTO purchase_order_item (order_id, product_id, quantity, unit_code, unit_price, row_amount, created_at, created_by) VALUES
(1, 1, 500.000, 'kg', 28.00, 14000.00, NOW(6), 3);

-- PO-B：超市向批发分装中心进货（超市进货场景）
INSERT INTO purchase_order (id, order_no, buyer_org_id, seller_org_id, order_type, status, ordered_at, expected_delivery_at, amount_total, currency_code, note, created_at, created_by) VALUES
(2, 'PO202609020001', 4, 3, 'FINISHED_GOODS', 'SHIPPED', DATE_SUB(NOW(6), INTERVAL 1 DAY), DATE_SUB(NOW(6), INTERVAL 7 HOUR), 25000.00, 'CNY', '超市向批发中心订购对虾与扇贝', DATE_SUB(NOW(6), INTERVAL 1 DAY), 6);
INSERT INTO purchase_order_item (order_id, product_id, quantity, unit_code, unit_price, row_amount, created_at, created_by) VALUES
(2, 2, 300.000, 'kg', 60.00, 18000.00, NOW(6), 6),
(2, 4, 200.000, 'kg', 35.00, 7000.00, NOW(6), 6);

-- PO-C：线上商家向加工厂购买成品（商家买成品场景）
INSERT INTO purchase_order (id, order_no, buyer_org_id, seller_org_id, order_type, status, ordered_at, expected_delivery_at, amount_total, currency_code, note, created_at, created_by) VALUES
(3, 'PO202609030001', 5, 2, 'FINISHED_GOODS', 'CONFIRMED', DATE_SUB(NOW(6), INTERVAL 8 HOUR), DATE_SUB(NOW(6), INTERVAL 6 HOUR), 9500.00, 'CNY', '电商商家向加工厂采购冰鲜三文鱼', DATE_SUB(NOW(6), INTERVAL 8 HOUR), 7);
INSERT INTO purchase_order_item (order_id, product_id, quantity, unit_code, unit_price, row_amount, created_at, created_by) VALUES
(3, 3, 100.000, 'kg', 95.00, 9500.00, NOW(6), 7);

-- 9.8 B2C 销售/客户订单
-- SO-A：亿家生鲜(商家)卖给个人客户，已送达并绑定溯源码包装号
INSERT INTO sales_order (id, order_no, seller_org_id, customer_name, customer_phone, delivery_address, status, trace_code_id, packed_package_no, placed_at, delivered_at, amount_total, currency_code, note, created_at, created_by) VALUES
(1, 'SO202609010002', 5, '王女士', '138****1234', '广东省深圳市南山区海月路 1 号', 'DELIVERED', 1001, 'PKG-EF20260901-A', DATE_SUB(NOW(6), INTERVAL 5 HOUR), DATE_SUB(NOW(6), INTERVAL 3 HOUR), 440.00, 'CNY', '南美白对虾 5kg 家庭装', DATE_SUB(NOW(6), INTERVAL 5 HOUR), 7);
INSERT INTO sales_order_item (order_id, product_id, quantity, unit_code, unit_price, row_amount, created_at, created_by) VALUES
(1, 2, 5.000, 'kg', 88.00, 440.00, NOW(6), 7);

-- SO-B：超市卖给个人客户，运输中
INSERT INTO sales_order (id, order_no, seller_org_id, customer_name, customer_phone, delivery_address, status, trace_code_id, packed_package_no, placed_at, delivered_at, amount_total, currency_code, note, created_at, created_by) VALUES
(2, 'SO202609030003', 4, '李先生', '139****5678', '上海市静安区南京西路 88 号', 'SHIPPED', 1002, 'PKG-EF20260903-B', DATE_SUB(NOW(6), INTERVAL 4 HOUR), NULL, 1580.00, 'CNY', '冰鲜三文鱼 10kg 宴请装', DATE_SUB(NOW(6), INTERVAL 4 HOUR), 6);
INSERT INTO sales_order_item (order_id, product_id, quantity, unit_code, unit_price, row_amount, created_at, created_by) VALUES
(2, 3, 10.000, 'kg', 158.00, 1580.00, NOW(6), 6);

SET FOREIGN_KEY_CHECKS = 1;