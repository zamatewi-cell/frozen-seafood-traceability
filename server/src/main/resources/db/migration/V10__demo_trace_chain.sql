-- =============================================================================
-- Flyway Database Migration: V10__demo_trace_chain.sql
-- Description: 演示模拟批次溯源链数据（批次/追溯码/事件/温度/质检/运输/交接/告警）
-- 与 V9 销售订单对齐：SO-A(对虾, trace_code 1001)、SO-B(三文鱼, trace_code 1002)
-- 供本地演示消费者扫码查询完整溯源履历
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =============================================================================
-- 链路一：南美白对虾（P-CRUST-0001）
-- SOURCE(捕捞) -> PROCESSING(加工) -> DISTRIBUTION(分装批发) -> SALE(零售售出)
-- =============================================================================

-- 10.1 对虾批次：捕捞源头批次 org1
INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, capture_date, freeze_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(1001, 1, 2, 'BT-SOURCE-20260901-SH01', 'SOURCE', 5000.000, 'kg', 'DOMESTIC_FARMED', '东海舟山海域捕捞母港：南美白对虾鲜活捕捞，单车96小时冷链回港', '2026-09-01', '2026-09-02', 365, 'CLOSED', 'demo-chain-1-source', 2)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 10.2 对虾批次：加工厂加工净分装 org2
INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, freeze_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(1002, 2, 2, 'BT-PROC-20260903-PR01', 'PROCESSING', 3900.000, 'kg', 'DOMESTIC_FARMED', '深海鲜冻加工厂：去壳、单冻、-35℃速冻，金属探测出厂检验', '2026-09-03', '2026-09-03', 365, 'ACTIVE', 'demo-chain-1-proc', 3)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 10.3 对虾批次：批发分装中心 org3
INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, freeze_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(1003, 3, 2, 'BT-DIST-20260905-DR01', 'DISTRIBUTION', 1500.000, 'kg', 'DOMESTIC_FARMED', '南海冷链分装批发中心：500g/袋单冻分装，二次抽检，码垛入库', '2026-09-05', '2026-09-05', 365, 'ACTIVE', 'demo-chain-1-dist', 5)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 10.4 对虾批次：零售售卖批次 org4(超市)
INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(1004, 4, 2, 'BT-SALE-20260906-RT01', 'SALE', 300.000, 'kg', 'DOMESTIC_FARMED', '鲜活优选超市冷柜销售：对虾家庭装上架', '2026-09-05', 365, 'FROZEN', 'demo-chain-1-sale', 6)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 10.5 对虾公开追溯码 (trace_code_id=1001，对虾 500g 家庭装)
INSERT INTO public_trace_code (id, batch_id, org_id, public_id, token_hash, status, activated_at, created_by) VALUES
(1001, 1004, 4, 'VEEQFIXQFYHIQKMIQB2SMECW2R', REPLACE(SHA2(CONCAT('VEEQFIXQFYHIQKMIQB2SMECW2R','seed'),256),'-',''), 'ACTIVE', DATE_SUB(NOW(6), INTERVAL 5 HOUR), 6)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 10.6 对虾批次谱系：捕获->加工->分装->售卖
INSERT INTO batch_operation (id, org_id, operation_no, operation_type, occurred_at, status, idempotency_key, created_by) VALUES
(1001, 2, 'OP-20260903-PR01', 'PROCESS', DATE_SUB(NOW(6), INTERVAL 15 DAY), 'SUBMITTED', 'demo-chain-1-op-proc', 3),
(1002, 3, 'OP-20260905-DR01', 'SPLIT', DATE_SUB(NOW(6), INTERVAL 13 DAY), 'SUBMITTED', 'demo-chain-1-op-dist', 5),
(1003, 4, 'OP-20260906-RT01', 'SPLIT', DATE_SUB(NOW(6), INTERVAL 12 DAY), 'SUBMITTED', 'demo-chain-1-op-sale', 6)
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO batch_operation_item (id, operation_id, batch_id, role, quantity, unit_code, normalized_quantity, created_by) VALUES
(1001, 1001, 1001, 'INPUT', 5000.000, 'kg', 5000.000, 3),
(1002, 1001, 1002, 'OUTPUT', 3900.000, 'kg', 3900.000, 3),
(1003, 1001, NULL, 'WASTE', 1100.000, 'kg', 1100.000, 3),
(1004, 1002, 1002, 'INPUT', 3900.000, 'kg', 3900.000, 5),
(1005, 1002, 1003, 'OUTPUT', 1500.000, 'kg', 1500.000, 5),
(1006, 1003, 1003, 'INPUT', 1500.000, 'kg', 1500.000, 6),
(1007, 1003, 1004, 'OUTPUT', 300.000, 'kg', 300.000, 6)
ON DUPLICATE KEY UPDATE normalized_quantity = VALUES(normalized_quantity);

INSERT INTO batch_relation (id, operation_id, parent_batch_id, child_batch_id, relation_type) VALUES
(1001, 1001, 1001, 1002, 'TRANSFORM'),
(1002, 1002, 1002, 1003, 'SPLIT'),
(1003, 1003, 1003, 1004, 'SPLIT')
ON DUPLICATE KEY UPDATE relation_type = VALUES(relation_type);

-- 10.7 对虾企业间交接：捕捞舰队->加工厂 等
INSERT INTO transfer (id, transfer_no, batch_id, shipment_id, sender_org_id, receiver_org_id, quantity, unit_code, shipped_at, recorded_at, received_at, received_quantity, status, idempotency_key, is_legacy, created_by) VALUES
(1001, 'TRANS-20260902-SH-PR', 1001, NULL, 1, 2, 5000.000, 'kg', DATE_SUB(NOW(6), INTERVAL 16 DAY), DATE_SUB(NOW(6), INTERVAL 16 DAY), DATE_SUB(NOW(6), INTERVAL 15 DAY), 5000.000, 'ACCEPTED', 'demo-chain-1-tf-src', 1, 2),
(1002, 'TRANS-20260904-PR-DIST', 1002, NULL, 2, 3, 3900.000, 'kg', DATE_SUB(NOW(6), INTERVAL 14 DAY), DATE_SUB(NOW(6), INTERVAL 14 DAY), DATE_SUB(NOW(6), INTERVAL 13 DAY), 3900.000, 'ACCEPTED', 'demo-chain-1-tf-pr', 1, 3),
(1003, 'TRANS-20260905-DIST-RT', 1003, NULL, 3, 4, 1500.000, 'kg', DATE_SUB(NOW(6), INTERVAL 12 DAY), DATE_SUB(NOW(6), INTERVAL 12 DAY), DATE_SUB(NOW(6), INTERVAL 12 DAY), 1500.000, 'ACCEPTED', 'demo-chain-1-tf-dist', 1, 5)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 10.8 对虾追溯事件链（追加式）
INSERT INTO trace_event (id, batch_id, org_id, site_id, event_type, occurred_at, operator_id, data_source, status, idempotency_key, summary, details_json, created_by) VALUES
(1001, 1001, 1, 1, 'SOURCE', DATE_SUB(NOW(6), INTERVAL 16 DAY), 2, 'MANUAL', 'SUBMITTED', 'demo-chain-1-ev-src', '舟山海域鲜活捕捞，捕捞网次C20260901-17，回港活水舱暂养', JSON_OBJECT('harvest_batch','C20260901-17','vessel','浙远渔 12088','captain','capt_ship'), 2),
(1002, 1002, 2, 2, 'PURCHASE', DATE_SUB(NOW(6), INTERVAL 15 DAY), 3, 'MANUAL', 'SUBMITTED', 'demo-chain-1-ev-pur', '加工厂向蓝海舰队采购原料5吨，抽检合格入库', NULL, 3),
(1003, 1002, 2, 2, 'PROCESS', DATE_SUB(NOW(6), INTERVAL 15 DAY), 3, 'MANUAL', 'SUBMITTED', 'demo-chain-1-ev-proc', '去壳单冻生产线作业，出成率78%，金属探测逐个通过', JSON_OBJECT('yield','78%','line','L2','batch','BT-PROC-20260903-PR01'), 3),
(1004, 1002, 2, 2, 'FREEZE', DATE_SUB(NOW(6), INTERVAL 15 DAY), 3, 'DEVICE', 'SUBMITTED', 'demo-chain-1-ev-freeze', '-35℃速冻隧道冻结，中心温度达-18℃出线', JSON_OBJECT('tunnel','T-01','core_temp','-18.4'), 3),
(1005, 1003, 3, 4, 'PACK', DATE_SUB(NOW(6), INTERVAL 13 DAY), 5, 'MANUAL', 'SUBMITTED', 'demo-chain-1-ev-pack', '500g/袋单冻分装，贴追溯标签，批次净重核称', NULL, 5),
(1006, 1003, 3, 5, 'WAREHOUSE_IN', DATE_SUB(NOW(6), INTERVAL 13 DAY), 5, 'MANUAL', 'SUBMITTED', 'demo-chain-1-ev-whin', '入中转冷库-22℃码垛，先进先出库位F3-22', JSON_OBJECT('storage','-22','slot','F3-22'), 5),
(1007, 1003, 3, 5, 'TRANSPORT', DATE_SUB(NOW(6), INTERVAL 11 DAY), 5, 'DEVICE', 'SUBMITTED', 'demo-chain-1-ev-trans', '冷链车粤A·6L8K9 发运至上海，车厢-18℃恒温', JSON_OBJECT('vehicle','粤A6L8K9','target','-18'), 5),
(1008, 1003, 3, 5, 'WAREHOUSE_OUT', DATE_SUB(NOW(6), INTERVAL 11 DAY), 5, 'MANUAL', 'SUBMITTED', 'demo-chain-1-ev-whout', '中转冷库出库，批次重量复核一致', NULL, 5),
(1009, 1004, 4, 6, 'ARRIVAL', DATE_SUB(NOW(6), INTERVAL 10 DAY), 6, 'MANUAL', 'SUBMITTED', 'demo-chain-1-ev-arr', '超市收货验收，温度-16.8℃，外观包装完好', NULL, 6),
(1010, 1004, 4, 6, 'SALE', DATE_SUB(NOW(6), INTERVAL 5 HOUR), 6, 'MANUAL', 'SUBMITTED', 'demo-chain-1-ev-sale', '王女士购买南美白对虾5kg家庭装，生成追溯码绑定', JSON_OBJECT('package','PKG-EF20260901-A','customer','王女士'), 6)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 10.9 对虾质检报告与在途温度
INSERT INTO inspection_report (id, batch_id, org_id, report_no, institution_name, inspected_at, items_summary, conclusion, institution_verified, data_source, created_by) VALUES
(1001, 1002, 2, 'QC-20260903-SH01', '青岛海检检测技术中心', DATE_SUB(NOW(6), INTERVAL 15 DAY), '挥发性盐基氮、沙门氏菌、金黄色葡萄球菌、重金属(镉/铅)四项抽检', 'PASS', TRUE, 'IMPORT', 4)
ON DUPLICATE KEY UPDATE conclusion = VALUES(conclusion);

INSERT INTO temperature_record (id, batch_id, shipment_id, stage_code, measured_at, temperature, data_source, device_no, evaluation, created_by) VALUES
(1001, 1002, NULL, 'STORAGE', DATE_SUB(NOW(6), INTERVAL 14 DAY), -18.20, 'DEVICE', 'PR-TH-001', 'NORMAL', 3),
(1002, 1003, NULL, 'STORAGE', DATE_SUB(NOW(6), INTERVAL 13 DAY), -22.10, 'DEVICE', 'DS-TH-102', 'NORMAL', 5),
(1003, 1003, NULL, 'TRANSPORT', DATE_SUB(NOW(6), INTERVAL 11 DAY), -18.90, 'DEVICE', 'TR-TH-902', 'NORMAL', 5),
(1004, 1003, NULL, 'TRANSPORT', DATE_SUB(NOW(6), INTERVAL 11 DAY), -12.50, 'DEVICE', 'TR-TH-902', 'HIGH', 5)
ON DUPLICATE KEY UPDATE evaluation = VALUES(evaluation);

-- 10.10 对虾在途超温告警（演示温控异常识别）
INSERT INTO alert (id, org_id, batch_id, alert_type, severity, triggered_at, status, resolution, created_by) VALUES
(1001, 3, 1003, 'TEMP_OVER_UPPER', 'HIGH', DATE_SUB(NOW(6), INTERVAL 11 DAY), 'RESOLVED', '粤A6L8K9 途中短暂超温至-12.5℃，司机开箱查验后空调复位，芯温仍低于-15℃，判定不影响品质', 5)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- =============================================================================
-- 链路二：挪威冰鲜三文鱼（P-FISH-0002）
-- IMPORT(进口) -> PROCESSING(加工) -> SALE(超市/电商售卖)
-- =============================================================================

-- 10.11 三文鱼批次：进口源头批次 org1（以蓝海贸易作为进口商示范）
INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, capture_date, freeze_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(2001, 1, 3, 'BT-IMP-20260908-NO01', 'SOURCE', 2500.000, 'kg', 'IMPORT', '挪威卑尔根渔港：大西洋鲑冰鲜出口，欧盟卫生证书附件，空运浦东--冰鲜链', '2026-09-08', NULL, 21, 'ACTIVE', 'demo-chain-2-imp', 2)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 10.12 三文鱼批次：电商加工分切 org2
INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(2002, 2, 3, 'BT-PROC-20260909-NO02', 'PROCESSING', 2200.000, 'kg', 'IMPORT', '电商分切中心：去皮去骨、真空贴体分切，沥水称重入盒', '2026-09-09', 21, 'ACTIVE', 'demo-chain-2-proc', 3)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 10.13 三文鱼批次: 电商/商家销售批次 org5
INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(2003, 5, 3, 'BT-SALE-20260909-NO03', 'SALE', 600.000, 'kg', 'IMPORT', '亿家生鲜华南仓：三文鱼冰鲜供应链远途订单分拣发货', '2026-09-09', 21, 'FROZEN', 'demo-chain-2-sale', 7)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 10.14 三文鱼公开追溯码 (trace_code_id=1002)
INSERT INTO public_trace_code (id, batch_id, org_id, public_id, token_hash, status, activated_at, created_by) VALUES
(1002, 2003, 5, 'M3VBLVB623O5OD4MZK3AXSFPMA', REPLACE(SHA2(CONCAT('M3VBLVB623O5OD4MZK3AXSFPMA','seed'),256),'-',''), 'ACTIVE', DATE_SUB(NOW(6), INTERVAL 4 HOUR), 7)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 10.15 三文鱼批次谱系：进口->加工->售卖
INSERT INTO batch_operation (id, org_id, operation_no, operation_type, occurred_at, status, idempotency_key, created_by) VALUES
(2001, 2, 'OP-20260909-NO02', 'PROCESS', DATE_SUB(NOW(6), INTERVAL 9 DAY), 'SUBMITTED', 'demo-chain-2-op-proc', 3),
(2002, 5, 'OP-20260909-NO03', 'SPLIT', DATE_SUB(NOW(6), INTERVAL 8 DAY), 'SUBMITTED', 'demo-chain-2-op-sale', 7)
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO batch_operation_item (id, operation_id, batch_id, role, quantity, unit_code, normalized_quantity, created_by) VALUES
(2001, 2001, 2001, 'INPUT', 2500.000, 'kg', 2500.000, 3),
(2002, 2001, 2002, 'OUTPUT', 2200.000, 'kg', 2200.000, 3),
(2003, 2002, 2002, 'INPUT', 2200.000, 'kg', 2200.000, 7),
(2004, 2002, 2003, 'OUTPUT', 600.000, 'kg', 600.000, 7)
ON DUPLICATE KEY UPDATE normalized_quantity = VALUES(normalized_quantity);

INSERT INTO batch_relation (id, operation_id, parent_batch_id, child_batch_id, relation_type) VALUES
(2001, 2001, 2001, 2002, 'TRANSFORM'),
(2002, 2002, 2002, 2003, 'SPLIT')
ON DUPLICATE KEY UPDATE relation_type = VALUES(relation_type);

-- 10.16 三文鱼企业间交接
INSERT INTO transfer (id, transfer_no, batch_id, shipment_id, sender_org_id, receiver_org_id, quantity, unit_code, shipped_at, recorded_at, received_at, received_quantity, status, idempotency_key, is_legacy, created_by) VALUES
(2001, 'TRANS-20260908-NO-SH', 2001, NULL, 1, 2, 2500.000, 'kg', DATE_SUB(NOW(6), INTERVAL 9 DAY), DATE_SUB(NOW(6), INTERVAL 9 DAY), DATE_SUB(NOW(6), INTERVAL 9 DAY), 2500.000, 'ACCEPTED', 'demo-chain-2-tf-imp', 1, 2),
(2002, 'TRANS-20260909-PR-SH', 2002, NULL, 2, 5, 2200.000, 'kg', DATE_SUB(NOW(6), INTERVAL 8 DAY), DATE_SUB(NOW(6), INTERVAL 8 DAY), DATE_SUB(NOW(6), INTERVAL 8 DAY), 2200.000, 'ACCEPTED', 'demo-chain-2-tf-proc', 1, 3)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 10.17 三文鱼追溯事件链
INSERT INTO trace_event (id, batch_id, org_id, site_id, event_type, occurred_at, operator_id, data_source, status, idempotency_key, summary, details_json, created_by) VALUES
(2001, 2001, 1, 1, 'SOURCE', DATE_SUB(NOW(6), INTERVAL 9 DAY), 2, 'IMPORT', 'SUBMITTED', 'demo-chain-2-ev-src', '挪威卑尔根渔港出口，空运浦东机场冰鲜链清关，卫生证附件NO-SE-20260908', JSON_OBJECT('origin','Norway Bergen','airway','CA1024','cert','NO-SE-20260908'), 2),
(2002, 2002, 2, 2, 'PROCESS', DATE_SUB(NOW(6), INTERVAL 8 DAY), 3, 'MANUAL', 'SUBMITTED', 'demo-chain-2-ev-proc', '真空贴体分切，中心温度-1.2℃，出成率88%', JSON_OBJECT('yield','88%','core_temp','-1.2'), 3),
(2003, 2002, 2, 3, 'WAREHOUSE_IN', DATE_SUB(NOW(6), INTERVAL 8 DAY), 3, 'DEVICE', 'SUBMITTED', 'demo-chain-2-ev-whin', '入0℃冰鲜库，潮汐式翻箱保鲜', NULL, 3),
(2004, 2003, 5, 7, 'ARRIVAL', DATE_SUB(NOW(6), INTERVAL 6 DAY), 7, 'MANUAL', 'SUBMITTED', 'demo-chain-2-ev-arr', '华南仓收货，芯温-0.8℃，冰鲜链完整', NULL, 7),
(2005, 2003, 5, 7, 'SALE', DATE_SUB(NOW(6), INTERVAL 4 HOUR), 7, 'MANUAL', 'SUBMITTED', 'demo-chain-2-ev-sale', '李先生购买冰鲜三文鱼10kg宴请装，生成追溯码绑定', JSON_OBJECT('package','PKG-EF20260903-B','customer','李先生'), 7)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 10.18 三文鱼质检报告
INSERT INTO inspection_report (id, batch_id, org_id, report_no, institution_name, inspected_at, items_summary, conclusion, institution_verified, data_source, created_by) VALUES
(2001, 2002, 2, 'QC-20260909-NO01', '青岛海检检测技术中心', DATE_SUB(NOW(6), INTERVAL 8 DAY), '微生物、过氧化值、组胺(沙门氏菌)与重金属检测', 'PASS', TRUE, 'IMPORT', 4)
ON DUPLICATE KEY UPDATE conclusion = VALUES(conclusion);

-- 10.19 用一次性事务来保持外键完整
SET FOREIGN_KEY_CHECKS = 1;