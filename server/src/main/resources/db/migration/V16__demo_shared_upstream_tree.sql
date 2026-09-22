-- =============================================================================
-- Flyway Database Migration: V16__demo_shared_upstream_tree.sql
-- Description: 演示「共享上游批次」的树状溯源——同一加工批次 c/d 同时作为
--   两个分装批次 a/b 的上游来源,在树中分别出现在 a 和 b 下方。
--
--   树结构(从终端零售向下):
--     零售 R (org4 超市)
--     ├── 分装 a (org3)
--     │   ├── 加工 c (org2) → 捕捞 S1 (org1)
--     │   └── 加工 d (org2) → 捕捞 S2 (org1)
--     └── 分装 b (org3)
--         ├── 加工 c (org2) → 捕捞 S1 (org1)   ← 同一批次重复出现
--         └── 加工 d (org2) → 捕捞 S2 (org1)   ← 同一批次重复出现
--
--   消费者扫码看到 11 个节点(7 个唯一批次,4 个重复),直观展示多分支共享上游。
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 16.1 源头批次:两条捕捞(共享给 a 和 b)
INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, capture_date, freeze_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(9306, 1, 4, 'BT-SHARED-SRC-1-20260913', 'SOURCE', 1200.000, 'kg', 'DOMESTIC_CAPTURE', '东海舟山渔场:浙远渔12089拖网捕捞扇贝,冰鲜回港', '2026-09-13', '2026-09-14', 365, 'CLOSED', 'demo-shared-src1', 2),
(9307, 1, 4, 'BT-SHARED-SRC-2-20260913', 'SOURCE', 800.000, 'kg', 'DOMESTIC_CAPTURE', '东海舟山渔场:浙远渔12090拖网捕捞扇贝,冰鲜回港', '2026-09-13', '2026-09-14', 365, 'CLOSED', 'demo-shared-src2', 2)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 16.2 加工批次 c 和 d(c 用 S1,d 用 S2)
INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, freeze_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(9304, 2, 4, 'BT-SHARED-PROC-C-20260915', 'PROCESSING', 900.000, 'kg', 'DOMESTIC_CAPTURE', '深海鲜冻加工厂L3线:扇贝柱去壳取肉单冻,出成率75%,金属探测通过', '2026-09-15', '2026-09-15', 365, 'ACTIVE', 'demo-shared-proc-c', 3),
(9305, 2, 4, 'BT-SHARED-PROC-D-20260915', 'PROCESSING', 600.000, 'kg', 'DOMESTIC_CAPTURE', '深海鲜冻加工厂L4线:扇贝柱去壳取肉单冻,出成率75%,金属探测通过', '2026-09-15', '2026-09-15', 365, 'ACTIVE', 'demo-shared-proc-d', 3)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 16.3 分装批次 a 和 b(a 用 c+d,b 也用 c+d —— 共享上游)
INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, freeze_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(9302, 3, 4, 'BT-SHARED-DIST-A-20260918', 'DISTRIBUTION', 400.000, 'kg', 'DOMESTIC_CAPTURE', '南海冷链分装:250g/袋单冻分装,贴追溯标签,批次A核称入库', '2026-09-18', '2026-09-18', 365, 'ACTIVE', 'demo-shared-dist-a', 5),
(9303, 3, 4, 'BT-SHARED-DIST-B-20260918', 'DISTRIBUTION', 400.000, 'kg', 'DOMESTIC_CAPTURE', '南海冷链分装:250g/袋单冻分装,贴追溯标签,批次B核称入库', '2026-09-18', '2026-09-18', 365, 'ACTIVE', 'demo-shared-dist-b', 5)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 16.4 零售批次 R(终端超市收货)
INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(9301, 4, 4, 'BT-SHARED-SALE-R-20260921', 'SALE', 800.000, 'kg', 'DOMESTIC_CAPTURE', '鲜活优选超市:扇贝柱家庭装零售,两批分装合成一单交付', '2026-09-21', 365, 'FROZEN', 'demo-shared-sale-r', 6)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 16.5 批次操作记录(6个转换操作)
INSERT INTO batch_operation (id, org_id, operation_no, operation_type, occurred_at, status, idempotency_key, created_by) VALUES
(9401, 2, 'OP-SHARED-PROC-C', 'PROCESS', DATE_SUB(NOW(6), INTERVAL 6 DAY), 'SUBMITTED', 'demo-shared-op-proc-c', 3),
(9402, 2, 'OP-SHARED-PROC-D', 'PROCESS', DATE_SUB(NOW(6), INTERVAL 6 DAY), 'SUBMITTED', 'demo-shared-op-proc-d', 3),
(9403, 3, 'OP-SHARED-DIST-A', 'SPLIT',   DATE_SUB(NOW(6), INTERVAL 3 DAY), 'SUBMITTED', 'demo-shared-op-dist-a', 5),
(9404, 3, 'OP-SHARED-DIST-B', 'SPLIT',   DATE_SUB(NOW(6), INTERVAL 3 DAY), 'SUBMITTED', 'demo-shared-op-dist-b', 5),
(9405, 4, 'OP-SHARED-SALE',   'SPLIT',   DATE_SUB(NOW(6), INTERVAL 1 DAY), 'SUBMITTED', 'demo-shared-op-sale', 6)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 16.6 操作明细(输入/输出批次)
INSERT INTO batch_operation_item (id, operation_id, batch_id, role, quantity, unit_code, normalized_quantity, created_by) VALUES
-- 加工 c: S1 → c
(9501, 9401, 9306, 'INPUT',  1200.000, 'kg', 1200.000, 3),
(9502, 9401, 9304, 'OUTPUT',  900.000, 'kg',  900.000, 3),
-- 加工 d: S2 → d
(9503, 9402, 9307, 'INPUT',   800.000, 'kg',  800.000, 3),
(9504, 9402, 9305, 'OUTPUT',  600.000, 'kg',  600.000, 3),
-- 分装 a: c+d → a
(9505, 9403, 9304, 'INPUT',   300.000, 'kg',  300.000, 5),
(9506, 9403, 9305, 'INPUT',   100.000, 'kg',  100.000, 5),
(9507, 9403, 9302, 'OUTPUT',  400.000, 'kg',  400.000, 5),
-- 分装 b: c+d → b (共享上游!)
(9508, 9404, 9304, 'INPUT',   250.000, 'kg',  250.000, 5),
(9509, 9404, 9305, 'INPUT',   150.000, 'kg',  150.000, 5),
(9510, 9404, 9303, 'OUTPUT',  400.000, 'kg',  400.000, 5),
-- 零售 R: a+b → R
(9511, 9405, 9302, 'INPUT',   400.000, 'kg',  400.000, 6),
(9512, 9405, 9303, 'INPUT',   400.000, 'kg',  400.000, 6),
(9513, 9405, 9301, 'OUTPUT',  800.000, 'kg',  800.000, 6)
ON DUPLICATE KEY UPDATE normalized_quantity = VALUES(normalized_quantity);

-- 16.7 批次谱系边(关键:同一加工批次 c/d 同时是 a 和 b 的上游)
INSERT INTO batch_relation (id, operation_id, parent_batch_id, child_batch_id, relation_type, created_at) VALUES
-- 加工层: S1→c, S2→d
(9601, 9401, 9306, 9304, 'TRANSFORM', DATE_SUB(NOW(6), INTERVAL 6 DAY)),
(9602, 9402, 9307, 9305, 'TRANSFORM', DATE_SUB(NOW(6), INTERVAL 6 DAY)),
-- 分装层: c→a, d→a, c→b, d→b (c和d共享给a和b!)
(9603, 9403, 9304, 9302, 'SPLIT', DATE_SUB(NOW(6), INTERVAL 3 DAY)),
(9604, 9403, 9305, 9302, 'SPLIT', DATE_SUB(NOW(6), INTERVAL 3 DAY)),
(9605, 9404, 9304, 9303, 'SPLIT', DATE_SUB(NOW(6), INTERVAL 3 DAY)),
(9606, 9404, 9305, 9303, 'SPLIT', DATE_SUB(NOW(6), INTERVAL 3 DAY)),
-- 零售层: a→R, b→R
(9607, 9405, 9302, 9301, 'SPLIT', DATE_SUB(NOW(6), INTERVAL 1 DAY)),
(9608, 9405, 9303, 9301, 'SPLIT', DATE_SUB(NOW(6), INTERVAL 1 DAY))
ON DUPLICATE KEY UPDATE relation_type = VALUES(relation_type);

-- 16.8 追溯事件(每批2条,让时间线有内容)
INSERT INTO trace_event (id, batch_id, org_id, site_id, event_type, occurred_at, operator_id, data_source, status, idempotency_key, summary, details_json, created_by) VALUES
-- 捕捞 S1
(9701, 9306, 1, 1, 'SOURCE',    DATE_SUB(NOW(6), INTERVAL 9 DAY),  2, 'MANUAL', 'SUBMITTED', 'demo-shared-ev-s1a', '浙远渔12089东海舟山渔场拖网捕捞扇贝,冰鲜回港', JSON_OBJECT('vessel','浙远渔12089'), 2),
(9702, 9306, 1, 1, 'FREEZE',    DATE_SUB(NOW(6), INTERVAL 8 DAY),  2, 'DEVICE', 'SUBMITTED', 'demo-shared-ev-s1b', '港口-25℃冷库冻结保鲜', NULL, 2),
-- 捕捞 S2
(9703, 9307, 1, 1, 'SOURCE',    DATE_SUB(NOW(6), INTERVAL 9 DAY),  2, 'MANUAL', 'SUBMITTED', 'demo-shared-ev-s2a', '浙远渔12090东海舟山渔场拖网捕捞扇贝,冰鲜回港', JSON_OBJECT('vessel','浙远渔12090'), 2),
(9704, 9307, 1, 1, 'FREEZE',    DATE_SUB(NOW(6), INTERVAL 8 DAY),  2, 'DEVICE', 'SUBMITTED', 'demo-shared-ev-s2b', '港口-25℃冷库冻结保鲜', NULL, 2),
-- 加工 c
(9705, 9304, 2, 2, 'PROCESS',   DATE_SUB(NOW(6), INTERVAL 6 DAY),  3, 'MANUAL', 'SUBMITTED', 'demo-shared-ev-ca', 'L3线扇贝柱去壳取肉单冻,出成率75%,金属探测通过', JSON_OBJECT('line','L3','yield','75%'), 3),
(9706, 9304, 2, 2, 'FREEZE',    DATE_SUB(NOW(6), INTERVAL 6 DAY),  3, 'DEVICE', 'SUBMITTED', 'demo-shared-ev-cb', '-35℃速冻隧道,中心温度-18.5℃', NULL, 3),
-- 加工 d
(9707, 9305, 2, 2, 'PROCESS',   DATE_SUB(NOW(6), INTERVAL 6 DAY),  3, 'MANUAL', 'SUBMITTED', 'demo-shared-ev-da', 'L4线扇贝柱去壳取肉单冻,出成率75%,金属探测通过', JSON_OBJECT('line','L4','yield','75%'), 3),
(9708, 9305, 2, 2, 'FREEZE',    DATE_SUB(NOW(6), INTERVAL 6 DAY),  3, 'DEVICE', 'SUBMITTED', 'demo-shared-ev-db', '-35℃速冻隧道,中心温度-18.3℃', NULL, 3),
-- 分装 a
(9709, 9302, 3, 4, 'PACK',      DATE_SUB(NOW(6), INTERVAL 3 DAY),  5, 'MANUAL', 'SUBMITTED', 'demo-shared-ev-aa', '250g/袋单冻分装,贴追溯标签,批次A核称入库', NULL, 5),
(9710, 9302, 3, 5, 'TRANSPORT', DATE_SUB(NOW(6), INTERVAL 2 DAY),  5, 'DEVICE', 'SUBMITTED', 'demo-shared-ev-ab', '冷链车粤A9X3K2发运上海,车厢-18℃', NULL, 5),
-- 分装 b
(9711, 9303, 3, 4, 'PACK',      DATE_SUB(NOW(6), INTERVAL 3 DAY),  5, 'MANUAL', 'SUBMITTED', 'demo-shared-ev-ba', '250g/袋单冻分装,贴追溯标签,批次B核称入库', NULL, 5),
(9712, 9303, 3, 5, 'TRANSPORT', DATE_SUB(NOW(6), INTERVAL 2 DAY),  5, 'DEVICE', 'SUBMITTED', 'demo-shared-ev-bb', '冷链车粤A9X3K2发运上海,车厢-18℃', NULL, 5),
-- 零售 R
(9713, 9301, 4, 6, 'ARRIVAL',   DATE_SUB(NOW(6), INTERVAL 1 DAY),  6, 'MANUAL', 'SUBMITTED', 'demo-shared-ev-ra', '超市收货验收,温度-16.5℃,包装完好入库冷柜', NULL, 6),
(9714, 9301, 4, 6, 'SALE',      DATE_SUB(NOW(6), INTERVAL 2 HOUR), 6, 'MANUAL', 'SUBMITTED', 'demo-shared-ev-rb', '终端订单交付,两批分装合成一单,绑定溯源码', NULL, 6)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 16.9 终端销售订单(超市向批发分装下单800kg)
INSERT INTO sales_order (id, order_no, seller_org_id, customer_name, customer_phone, delivery_address, status, trace_code_id, packed_package_no, placed_at, delivered_at, amount_total, currency_code, note, created_by) VALUES
(9100, 'SO-SHARED-TREE-DEMO', 3, '鲜活优选超市(共享上游演示)', NULL, '鲜活优选连锁超市上海总店冷柜', 'DELIVERED', 9100, 'MULTITREESHAREDDEMOXXABCDE', DATE_SUB(NOW(6), INTERVAL 3 DAY), DATE_SUB(NOW(6), INTERVAL 1 DAY), 32000.00, 'CNY', '共享上游树演示:两批分装a+b合成一单,各自上游共享加工c+d', 6)
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO sales_order_item (id, order_id, product_id, quantity, unit_code, unit_price, row_amount, created_by) VALUES
(9100, 9100, 4, 800.000, 'kg', 40.00, 32000.00, 6)
ON DUPLICATE KEY UPDATE row_amount = VALUES(row_amount);

-- 16.10 公开溯源码(绑定零售批次R为根)
INSERT INTO public_trace_code (id, batch_id, org_id, public_id, token_hash, status, activated_at, created_by, source_order_id, source_order_type) VALUES
(9100, 9301, 4, 'MULTITREESHAREDDEMOXXABCDE', REPLACE(SHA2(CONCAT('MULTITREESHAREDDEMOXXABCDE','seed'),256),'-',''), 'ACTIVE', DATE_SUB(NOW(6), INTERVAL 1 DAY), 6, 9100, 'SALES')
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO public_trace_code_batch (trace_code_id, batch_id, batch_org_id, bind_role, bound_by) VALUES
(9100, 9301, 4, 'RETAIL', 6)
ON DUPLICATE KEY UPDATE batch_org_id = VALUES(batch_org_id);

SET FOREIGN_KEY_CHECKS = 1;
