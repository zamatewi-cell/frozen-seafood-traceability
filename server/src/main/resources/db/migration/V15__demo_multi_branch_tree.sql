-- =============================================================================
-- Flyway Database Migration: V15__demo_multi_branch_tree.sql
-- Description: 演示「终端下单生成一码、一码聚合多批、各环节多分支」的树状溯源
--   场景:鲜活优选超市(org4)向批发分装(org3)下单 800kg 冷冻扇贝柱,
--   分装用两条独立链的货(链A:9001捕捞→9002加工→9003分装→9004零售;
--   链B:9011捕捞→9012加工→9013分装→9014零售)合成同一订单,
--   交付后生成一个溯源码 TREEMULTIBRANCHDEMOXXABCDE,绑定两个零售批次。
--   消费者扫码看到两个根分支,各自向上溯源到捕捞源头。
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 15.1 链A批次:捕捞→加工→分装→零售(扇贝柱 product_id=4)
INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, capture_date, freeze_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(9001, 1, 4, 'BT-MULTI-SRC-A-20260915', 'SOURCE', 1000.000, 'kg', 'DOMESTIC_CAPTURE', '东海舟山渔场捕捞母港:鲜活扇贝拖网捕捞,冰鲜回港', '2026-09-15', '2026-09-16', 365, 'CLOSED', 'demo-multi-src-a', 2)
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, freeze_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(9002, 2, 4, 'BT-MULTI-PROC-A-20260917', 'PROCESSING', 800.000, 'kg', 'DOMESTIC_CAPTURE', '深海鲜冻加工厂:扇贝柱去壳取肉、单冻、-35℃速冻,金属探测出厂', '2026-09-17', '2026-09-17', 365, 'ACTIVE', 'demo-multi-proc-a', 3)
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, freeze_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(9003, 3, 4, 'BT-MULTI-DIST-A-20260919', 'DISTRIBUTION', 400.000, 'kg', 'DOMESTIC_CAPTURE', '南海冷链分装批发中心:250g/袋单冻分装,二次抽检码垛入库', '2026-09-19', '2026-09-19', 365, 'ACTIVE', 'demo-multi-dist-a', 5)
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(9004, 4, 4, 'BT-MULTI-SALE-A-20260921', 'SALE', 400.000, 'kg', 'DOMESTIC_CAPTURE', '鲜活优选超市冷柜上架:扇贝柱家庭装零售', '2026-09-21', 365, 'FROZEN', 'demo-multi-sale-a', 6)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 15.2 链B批次:另一条独立捕捞→加工→分装→零售
INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, capture_date, freeze_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(9011, 1, 4, 'BT-MULTI-SRC-B-20260914', 'SOURCE', 800.000, 'kg', 'DOMESTIC_CAPTURE', '东海舟山渔场捕捞母港:另一网次扇贝拖网捕捞,冰鲜回港', '2026-09-14', '2026-09-15', 365, 'CLOSED', 'demo-multi-src-b', 2)
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, freeze_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(9012, 2, 4, 'BT-MULTI-PROC-B-20260916', 'PROCESSING', 600.000, 'kg', 'DOMESTIC_CAPTURE', '深海鲜冻加工厂:扇贝柱去壳取肉、单冻、-35℃速冻,金属探测出厂', '2026-09-16', '2026-09-16', 365, 'ACTIVE', 'demo-multi-proc-b', 3)
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, freeze_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(9013, 3, 4, 'BT-MULTI-DIST-B-20260918', 'DISTRIBUTION', 400.000, 'kg', 'DOMESTIC_CAPTURE', '南海冷链分装批发中心:250g/袋单冻分装,二次抽检码垛入库', '2026-09-18', '2026-09-18', 365, 'ACTIVE', 'demo-multi-dist-b', 5)
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO batch (id, org_id, product_id, batch_no, batch_type, quantity, unit_code, origin_type, origin_text, production_date, shelf_life_days, status, creation_idempotency_key, created_by) VALUES
(9014, 4, 4, 'BT-MULTI-SALE-B-20260921', 'SALE', 400.000, 'kg', 'DOMESTIC_CAPTURE', '鲜活优选超市冷柜上架:扇贝柱家庭装零售(批次B)', '2026-09-21', 365, 'FROZEN', 'demo-multi-sale-b', 6)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 15.3 批次操作谱系:每条链3个转换(加工/分装/零售上架)
INSERT INTO batch_operation (id, org_id, operation_no, operation_type, occurred_at, status, idempotency_key, created_by) VALUES
(9101, 2, 'OP-MULTI-PROC-A', 'PROCESS', DATE_SUB(NOW(6), INTERVAL 5 DAY), 'SUBMITTED', 'demo-multi-op-proc-a', 3),
(9102, 3, 'OP-MULTI-DIST-A', 'SPLIT',   DATE_SUB(NOW(6), INTERVAL 3 DAY), 'SUBMITTED', 'demo-multi-op-dist-a', 5),
(9103, 4, 'OP-MULTI-SALE-A', 'SPLIT',   DATE_SUB(NOW(6), INTERVAL 1 DAY), 'SUBMITTED', 'demo-multi-op-sale-a', 6),
(9111, 2, 'OP-MULTI-PROC-B', 'PROCESS', DATE_SUB(NOW(6), INTERVAL 6 DAY), 'SUBMITTED', 'demo-multi-op-proc-b', 3),
(9112, 3, 'OP-MULTI-DIST-B', 'SPLIT',   DATE_SUB(NOW(6), INTERVAL 4 DAY), 'SUBMITTED', 'demo-multi-op-dist-b', 5),
(9113, 4, 'OP-MULTI-SALE-B', 'SPLIT',   DATE_SUB(NOW(6), INTERVAL 1 DAY), 'SUBMITTED', 'demo-multi-op-sale-b', 6)
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO batch_operation_item (id, operation_id, batch_id, role, quantity, unit_code, normalized_quantity, created_by) VALUES
(9201, 9101, 9001, 'INPUT',  1000.000, 'kg', 1000.000, 3),
(9202, 9101, 9002, 'OUTPUT',  800.000, 'kg',  800.000, 3),
(9203, 9102, 9002, 'INPUT',   800.000, 'kg',  800.000, 5),
(9204, 9102, 9003, 'OUTPUT',  400.000, 'kg',  400.000, 5),
(9205, 9103, 9003, 'INPUT',   400.000, 'kg',  400.000, 6),
(9206, 9103, 9004, 'OUTPUT',  400.000, 'kg',  400.000, 6),
(9207, 9111, 9011, 'INPUT',   800.000, 'kg',  800.000, 3),
(9208, 9111, 9012, 'OUTPUT',  600.000, 'kg',  600.000, 3),
(9209, 9112, 9012, 'INPUT',   600.000, 'kg',  600.000, 5),
(9210, 9112, 9013, 'OUTPUT',  400.000, 'kg',  400.000, 5),
(9211, 9113, 9013, 'INPUT',   400.000, 'kg',  400.000, 6),
(9212, 9113, 9014, 'OUTPUT',  400.000, 'kg',  400.000, 6)
ON DUPLICATE KEY UPDATE normalized_quantity = VALUES(normalized_quantity);

-- 15.4 批次谱系边:父子关系(用于溯源树向上递归)
INSERT INTO batch_relation (id, operation_id, parent_batch_id, child_batch_id, relation_type, created_at) VALUES
(9301, 9101, 9001, 9002, 'TRANSFORM', DATE_SUB(NOW(6), INTERVAL 5 DAY)),
(9302, 9102, 9002, 9003, 'SPLIT',     DATE_SUB(NOW(6), INTERVAL 3 DAY)),
(9303, 9103, 9003, 9004, 'SPLIT',     DATE_SUB(NOW(6), INTERVAL 1 DAY)),
(9304, 9111, 9011, 9012, 'TRANSFORM', DATE_SUB(NOW(6), INTERVAL 6 DAY)),
(9305, 9112, 9012, 9013, 'SPLIT',     DATE_SUB(NOW(6), INTERVAL 4 DAY)),
(9306, 9113, 9013, 9014, 'SPLIT',     DATE_SUB(NOW(6), INTERVAL 1 DAY))
ON DUPLICATE KEY UPDATE relation_type = VALUES(relation_type);

-- 15.5 追溯事件:每批2个事件,让树节点时间线有内容
INSERT INTO trace_event (id, batch_id, org_id, site_id, event_type, occurred_at, operator_id, data_source, status, idempotency_key, summary, details_json, created_by) VALUES
(9401, 9001, 1, 1, 'SOURCE',     DATE_SUB(NOW(6), INTERVAL 7 DAY),  2, 'MANUAL', 'SUBMITTED', 'demo-multi-ev-src-a',  '东海舟山渔场拖网捕捞扇贝,冰鲜回港活水舱暂养', JSON_OBJECT('vessel','浙远渔 12089','net','N20260915-03'), 2),
(9402, 9002, 2, 2, 'PROCESS',    DATE_SUB(NOW(6), INTERVAL 5 DAY),  3, 'MANUAL', 'SUBMITTED', 'demo-multi-ev-proc-a', '扇贝柱去壳取肉生产线作业,单冻出成率80%,金属探测通过', JSON_OBJECT('yield','80%','line','L3'), 3),
(9403, 9002, 2, 2, 'FREEZE',     DATE_SUB(NOW(6), INTERVAL 5 DAY),  3, 'DEVICE', 'SUBMITTED', 'demo-multi-ev-frz-a',  '-35℃速冻隧道冻结,中心温度-18.5℃出线', JSON_OBJECT('tunnel','T-02','core_temp','-18.5'), 3),
(9404, 9003, 3, 4, 'PACK',       DATE_SUB(NOW(6), INTERVAL 3 DAY),  5, 'MANUAL', 'SUBMITTED', 'demo-multi-ev-pack-a', '250g/袋单冻分装,贴追溯标签,批次净重核称', NULL, 5),
(9405, 9003, 3, 5, 'TRANSPORT',  DATE_SUB(NOW(6), INTERVAL 2 DAY),  5, 'DEVICE', 'SUBMITTED', 'demo-multi-ev-trn-a',  '冷链车粤A·9X3K2 发运至上海,车厢-18℃恒温', JSON_OBJECT('vehicle','粤A9X3K2','target','-18'), 5),
(9406, 9004, 4, 6, 'ARRIVAL',    DATE_SUB(NOW(6), INTERVAL 1 DAY),  6, 'MANUAL', 'SUBMITTED', 'demo-multi-ev-arr-a',  '超市收货验收,温度-16.5℃,包装完好入库冷柜', NULL, 6),
(9407, 9004, 4, 6, 'SALE',       DATE_SUB(NOW(6), INTERVAL 2 HOUR), 6, 'MANUAL', 'SUBMITTED', 'demo-multi-ev-sale-a', '终端订单交付,批次A扇贝柱上架零售,绑定溯源码', NULL, 6),

(9408, 9011, 1, 1, 'SOURCE',     DATE_SUB(NOW(6), INTERVAL 8 DAY),  2, 'MANUAL', 'SUBMITTED', 'demo-multi-ev-src-b',  '东海舟山渔场另一网次拖网捕捞扇贝,冰鲜回港', JSON_OBJECT('vessel','浙远渔 12090','net','N20260914-07'), 2),
(9409, 9012, 2, 2, 'PROCESS',    DATE_SUB(NOW(6), INTERVAL 6 DAY),  3, 'MANUAL', 'SUBMITTED', 'demo-multi-ev-proc-b', '扇贝柱去壳取肉生产线作业,单冻出成率75%,金属探测通过', JSON_OBJECT('yield','75%','line','L3'), 3),
(9410, 9012, 2, 2, 'FREEZE',     DATE_SUB(NOW(6), INTERVAL 6 DAY),  3, 'DEVICE', 'SUBMITTED', 'demo-multi-ev-frz-b',  '-35℃速冻隧道冻结,中心温度-18.3℃出线', JSON_OBJECT('tunnel','T-02','core_temp','-18.3'), 3),
(9411, 9013, 3, 4, 'PACK',       DATE_SUB(NOW(6), INTERVAL 4 DAY),  5, 'MANUAL', 'SUBMITTED', 'demo-multi-ev-pack-b', '250g/袋单冻分装,贴追溯标签,批次净重核称', NULL, 5),
(9412, 9013, 3, 5, 'TRANSPORT',  DATE_SUB(NOW(6), INTERVAL 3 DAY),  5, 'DEVICE', 'SUBMITTED', 'demo-multi-ev-trn-b',  '冷链车粤A·9X3K2 发运至上海,车厢-18℃恒温', JSON_OBJECT('vehicle','粤A9X3K2','target','-18'), 5),
(9413, 9014, 4, 6, 'ARRIVAL',    DATE_SUB(NOW(6), INTERVAL 1 DAY),  6, 'MANUAL', 'SUBMITTED', 'demo-multi-ev-arr-b',  '超市收货验收,温度-16.7℃,包装完好入库冷柜', NULL, 6),
(9414, 9014, 4, 6, 'SALE',       DATE_SUB(NOW(6), INTERVAL 2 HOUR), 6, 'MANUAL', 'SUBMITTED', 'demo-multi-ev-sale-b', '终端订单交付,批次B扇贝柱上架零售,绑定溯源码', NULL, 6)
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 15.6 终端销售订单:超市(org4)向批发分装(org3)下单 800kg 扇贝柱,交付后生成码
INSERT INTO sales_order (id, order_no, seller_org_id, customer_name, customer_phone, delivery_address, status, trace_code_id, packed_package_no, placed_at, delivered_at, amount_total, currency_code, note, created_by) VALUES
(9001, 'SO-MULTI-TREE-DEMO', 3, '鲜活优选超市(终端)', NULL, '鲜活优选连锁超市上海总店冷柜', 'DELIVERED', 9001, 'TREEMULTIBRANCHDEMOXXABCDE', DATE_SUB(NOW(6), INTERVAL 3 DAY), DATE_SUB(NOW(6), INTERVAL 1 DAY), 32000.00, 'CNY', '终端订单演示:两批扇贝柱合成一单,交付生成溯源码', 6)
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO sales_order_item (id, order_id, product_id, quantity, unit_code, unit_price, row_amount, created_by) VALUES
(9001, 9001, 4, 800.000, 'kg', 40.00, 32000.00, 6)
ON DUPLICATE KEY UPDATE row_amount = VALUES(row_amount);

-- 15.7 公开溯源码:一个码聚合两个零售批次(9004 + 9014)
INSERT INTO public_trace_code (id, batch_id, org_id, public_id, token_hash, status, activated_at, created_by, source_order_id, source_order_type) VALUES
(9001, 9004, 4, 'TREEMULTIBRANCHDEMOXXABCDE', REPLACE(SHA2(CONCAT('TREEMULTIBRANCHDEMOXXABCDE','seed'),256),'-',''), 'ACTIVE', DATE_SUB(NOW(6), INTERVAL 1 DAY), 6, 9001, 'SALES')
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- 15.8 一码多批中间表:绑定两个零售批次,形成两个树根分支
INSERT INTO public_trace_code_batch (trace_code_id, batch_id, batch_org_id, bind_role, bound_by) VALUES
(9001, 9004, 4, 'RETAIL', 6),
(9001, 9014, 4, 'RETAIL', 6)
ON DUPLICATE KEY UPDATE batch_org_id = VALUES(batch_org_id);

SET FOREIGN_KEY_CHECKS = 1;
