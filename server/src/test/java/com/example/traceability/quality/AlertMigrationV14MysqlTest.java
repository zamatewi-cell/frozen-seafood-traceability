package com.example.traceability.quality;

import com.example.traceability.common.AbstractFlywayUpgradeMysqlTest;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V14（在途持续超温告警、受影响批次快照、告警处置台账、风险台账 ALERT 来源）真实 MySQL 8.4 升级迁移测试（Phase B PB3）。
 * <p>
 * 在隔离临时 schema 中先迁移到 V13，写入组织 / 场所 / 产品 / 已发布运输规则、在途与已到达运输任务、交接、批次、
 * 在途温度记录与一条 PB1 人工风险转换，再<b>原地</b>升级到 V14，验证：
 * <ol>
 *   <li>升级成功，只新增 alert_batch / alert_action；除 alert、batch_risk_transition、shipment、temperature_record 外
 *       全部表的结构与数据逐表不变；shipment / temperature_record 只新增复合外键目标唯一键，数据不变；
 *       已有风险转换行逐列不变，新列 source_alert_id 为空（不回填）；</li>
 *   <li>alert 只接受 Shipment 级持续超温形状：类型 / 严重度 / 状态 / 环节 / 原因、片段与判定依据一致性、生命周期形状、
 *       归属组织必须是运输任务发货方、片段记录必须属于同一运输任务、同一片段唯一；</li>
 *   <li>风险台账 ALERT 来源只允许系统自动冻结（有来源告警、无操作人、NORMAL → FROZEN），MANUAL 不得携带告警来源；</li>
 *   <li>alert_batch 的冻结转换必须来源于同一告警，快照前 NORMAL 才有冻结转换；alert_action 只有 ACKNOWLEDGE 与组织内幂等。</li>
 * </ol>
 * 反例：V13 状态下 V1 占位表 alert 已有存量行时，V14 在任何 DDL 之前失败、不被记录为成功，且不改动任何表。不修改 V1–V13。
 * </p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("V14 数据库迁移真实 MySQL 8.4 测试（在途持续超温告警）")
class AlertMigrationV14MysqlTest extends AbstractFlywayUpgradeMysqlTest {

    private static final List<String> ALTERED = List.of("alert", "batch_risk_transition", "shipment", "temperature_record");
    private static final String BRT_ROW = "SELECT GROUP_CONCAT(CONCAT_WS('|', id, batch_id, org_id, flow_status, from_status, to_status, "
            + "source_type, actor_user_id, reason, idempotency_key, request_hash, occurred_at, created_at) ORDER BY id) FROM batch_risk_transition";

    @Override
    protected String pepper() {
        return "v14-migration-test-pepper-ffffffffffffffff";
    }

    private void seedV13Facts() throws SQLException {
        exec("""
                INSERT INTO organization (id, org_no, name, org_type, status) VALUES
                (720, 'V14_CAR', '承运', 'CARRIER', 'ACTIVE'),
                (730, 'V14_PRC', '加工', 'PROCESSOR', 'ACTIVE'),
                (701, 'V14_RET', '零售', 'RETAILER', 'ACTIVE')
                """);
        exec("""
                INSERT INTO site (id, org_id, site_no, name, site_type, status) VALUES
                (813, 730, 'PRC-COLD', '加工冷库', 'COLD_STORE', 'ACTIVE'),
                (801, 701, 'RET-STORE', '门店', 'STORE', 'ACTIVE')
                """);
        exec("""
                INSERT INTO product (id, product_code, public_name, category, specification, source_type, base_unit_code, status)
                VALUES (401, 'V14_PRD', '冷冻大黄鱼', 'FISH', '500g/条', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE')
                """);
        exec("""
                INSERT INTO temperature_rule (id, product_id, version_no, name, effective_from, effective_to, status)
                VALUES (451, 401, 1, '冷冻大黄鱼运输规则', '2020-01-01 00:00:00', NULL, 'ACTIVE')
                """);
        exec("""
                INSERT INTO temperature_rule_stage (id, rule_id, stage_code, lower_limit, upper_limit, unit_code, allowed_duration_seconds, sequence_no)
                VALUES (461, 451, 'TRANSPORT', -25.00, -15.00, 'CELSIUS', 1800, 1)
                """);
        exec("""
                INSERT INTO batch (id, org_id, creation_org_id, product_id, trace_batch_no, batch_type, quantity, unit_code,
                    origin_type, origin_text, flow_status, risk_status, version)
                VALUES
                (901, 730, 730, 401, 'TB-V14-901', 'PROCESSING', 600.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL', 2),
                (902, 730, 730, 401, 'TB-V14-902', 'PROCESSING', 360.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'FROZEN', 3)
                """);
        exec("""
                INSERT INTO shipment (id, shipment_no, sender_org_id, receiver_org_id, carrier_org_id, vehicle_or_container_no,
                    origin_site_id, destination_site_id, loaded_at, unloaded_at, status,
                    dispatched_recorded_at, dispatched_by, delivered_recorded_at, delivered_by)
                VALUES
                (552, 'SHP-V14-S1', 730, 701, 720, '浙L·V14S1', 813, 801, '2026-09-21 01:00:00', NULL, 'IN_TRANSIT',
                    '2026-09-21 01:00:00', 1, NULL, NULL),
                (553, 'SHP-V14-S2', 730, 701, 720, '浙L·V14S2', 813, 801, '2026-09-20 01:00:00', '2026-09-20 05:00:00', 'DELIVERED',
                    '2026-09-20 01:00:00', 1, '2026-09-20 05:00:00', 1)
                """);
        exec("""
                INSERT INTO transfer (id, transfer_no, batch_id, shipment_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    status, idempotency_key, submitted_recorded_at, submitted_by)
                VALUES
                (562, 'TRF-V14-T2', 901, 552, 730, 701, 600.000, 'kg', 'PENDING', 'v14-t2-key', '2026-09-21 00:30:00', 2),
                (563, 'TRF-V14-T3', 902, 552, 730, 701, 360.000, 'kg', 'PENDING', 'v14-t3-key', '2026-09-21 00:30:00', 2)
                """);
        exec("""
                INSERT INTO temperature_record (id, shipment_id, org_id, actor_user_id, stage_code, measured_at, recorded_at, temperature,
                    unit_code, data_source, rule_stage_id, rule_lower_limit, rule_upper_limit, rule_allowed_duration_seconds, evaluation,
                    idempotency_key, request_hash)
                VALUES
                (9001, 552, 720, 3, 'TRANSPORT', '2026-09-21 01:10:00', '2026-09-21 03:00:00', -12.00, 'CELSIUS', 'MANUAL',
                    461, -25.00, -15.00, 1800, 'HIGH', 'k-v14-000000000001', REPEAT('a', 64)),
                (9002, 552, 720, 3, 'TRANSPORT', '2026-09-21 01:40:00', '2026-09-21 03:00:00', -11.00, 'CELSIUS', 'MANUAL',
                    461, -25.00, -15.00, 1800, 'HIGH', 'k-v14-000000000002', REPEAT('b', 64)),
                (9003, 553, 720, 3, 'TRANSPORT', '2026-09-20 02:00:00', '2026-09-20 03:00:00', -12.00, 'CELSIUS', 'MANUAL',
                    461, -25.00, -15.00, 1800, 'HIGH', 'k-v14-000000000003', REPEAT('c', 64))
                """);
        exec("""
                INSERT INTO batch_risk_transition (id, batch_id, org_id, flow_status, from_status, to_status, source_type, actor_user_id,
                    reason, idempotency_key, request_hash, occurred_at, created_at)
                VALUES (7001, 902, 730, 'ACTIVE', 'NORMAL', 'FROZEN', 'MANUAL', 11, '来料抽检异常', 'k-v14-manual-000001', REPEAT('d', 64),
                    '2026-09-21 00:50:00', '2026-09-21 00:50:00')
                """);
    }

    private void insertAlert(String alertNo, long orgId, long shipmentId, String type, String status, long start, long sustained,
                             String startedAt, String sustainedAt, int duration, String lower, String upper, int allowed,
                             String extraColumns, String extraValues) throws SQLException {
        exec("INSERT INTO alert (alert_no, org_id, shipment_id, alert_type, severity, status, reason, stage_code, episode_start_record_id, "
                + "sustained_record_id, episode_started_at, sustained_at, duration_seconds, rule_stage_id, rule_lower_limit, "
                + "rule_upper_limit, rule_allowed_duration_seconds, triggered_at" + extraColumns + ") VALUES ('" + alertNo + "', " + orgId + ", "
                + shipmentId + ", '" + type + "', 'HIGH', '" + status + "', '在途持续超温', 'TRANSPORT', " + start + ", " + sustained + ", '"
                + startedAt + "', '" + sustainedAt + "', " + duration + ", 461, " + lower + ", " + upper + ", " + allowed
                + ", '2026-09-21 03:00:00'" + extraValues + ")");
    }

    private void validAlert(String alertNo, long start, long sustained) throws SQLException {
        insertAlert(alertNo, 730, 552, "TEMP_OVER_UPPER", "OPEN", start, sustained, "2026-09-21 01:10:00", "2026-09-21 01:40:00",
                1800, "-25.00", "-15.00", 1800, "", "");
    }

    private void insertTransition(long batchId, String from, String to, String source, String alertId, String actor, String key) throws SQLException {
        exec("INSERT INTO batch_risk_transition (batch_id, org_id, flow_status, from_status, to_status, source_type, source_alert_id, "
                + "actor_user_id, reason, idempotency_key, request_hash, occurred_at) VALUES (" + batchId + ", 730, 'ACTIVE', '" + from
                + "', '" + to + "', '" + source + "', " + alertId + ", " + actor + ", '告警自动冻结', '" + key + "', REPEAT('e', 64), "
                + "'2026-09-21 03:00:00')");
    }

    @Test
    @DisplayName("原地升级：V13 事实无需清理即升级成功；未改动表逐表不变；新表与新约束只接受 PB3 的告警 / 快照 / 处置 / ALERT 来源形状")
    void upgrade_fromV13() throws Exception {
        migrateTo("13");
        seedV13Facts();
        List<String> v13Tables = businessTables();
        List<String> untouched = v13Tables.stream().filter(t -> !ALTERED.contains(t)).toList();
        Map<String, String> before = fingerprints(untouched, true);
        Map<String, String> keyOnlyDataBefore = fingerprints(List.of("shipment", "temperature_record"), false);
        String brtBefore = queryString(BRT_ROW);

        migrateTo("14");

        // 1. 升级成功；只新增两张表；未改动表逐表不变；只加唯一键的表数据不变；风险台账逐列不变且不回填
        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '14' AND success = 1")).isEqualTo(1);
        List<String> expectedTables = new ArrayList<>(v13Tables);
        expectedTables.add("alert_action");
        expectedTables.add("alert_batch");
        assertThat(businessTables()).containsExactlyInAnyOrderElementsOf(expectedTables);
        assertThat(fingerprints(untouched, true)).isEqualTo(before);
        assertThat(fingerprints(List.of("shipment", "temperature_record"), false)).isEqualTo(keyOnlyDataBefore);
        assertThat(queryString(BRT_ROW)).isEqualTo(brtBefore);
        assertThat(queryLong("SELECT count(*) FROM batch_risk_transition WHERE source_alert_id IS NOT NULL")).isZero();
        assertThat(queryLong("SELECT count(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'alert' "
                + "AND column_name IN ('batch_id', 'trigger_rule_id', 'assignee_id', 'is_deleted')")).as("V1 placeholder columns are gone").isZero();

        // 2. 合法告警；类型 / 严重度 / 状态 / 环节 / 原因
        validAlert("ALT-V14-1", 9001, 9002);
        assertBad("chk_alert_type", () -> insertAlert("ALT-X1", 730, 552, "INSPECT_FAIL", "OPEN", 9002, 9002, "2026-09-21 01:40:00",
                "2026-09-21 01:40:00", 0, "-25.00", "-15.00", 0, "", ""));
        // 未知状态同时违反取值与生命周期形状，MySQL 报告其中之一
        assertThatThrownBy(() -> insertAlert("ALT-X2", 730, 552, "TEMP_OVER_UPPER", "DISMISSED", 9002, 9002, "2026-09-21 01:40:00",
                "2026-09-21 01:40:00", 0, "-25.00", "-15.00", 0, "", ""))
                .isInstanceOf(SQLException.class).hasMessageMatching(".*chk_alert_(status|lifecycle_shape).*");
        assertBad("chk_alert_severity", () -> exec("UPDATE alert SET severity = 'URGENT'"));
        assertBad("chk_alert_stage", () -> exec("UPDATE alert SET stage_code = 'STORAGE'"));
        assertBad("chk_alert_reason", () -> exec("UPDATE alert SET reason = '   '"));
        // 片段与判定依据一致性
        assertBad("chk_alert_episode", () -> insertAlert("ALT-X3", 730, 552, "TEMP_OVER_UPPER", "OPEN", 9002, 9001, "2026-09-21 01:40:00",
                "2026-09-21 01:10:00", 0, "-25.00", "-15.00", 0, "", ""));
        assertBad("chk_alert_episode", () -> insertAlert("ALT-X4", 730, 552, "TEMP_OVER_UPPER", "OPEN", 9002, 9002, "2026-09-21 01:40:00",
                "2026-09-21 01:40:00", 60, "-25.00", "-15.00", 1800, "", ""));
        assertBad("chk_alert_episode", () -> insertAlert("ALT-X5", 730, 552, "TEMP_OVER_UPPER", "OPEN", 9002, 9002, "2026-09-21 01:40:00",
                "2026-09-21 01:40:00", 0, "-15.00", "-25.00", 0, "", ""));
        // 生命周期形状
        assertBad("chk_alert_lifecycle_shape", () -> exec("UPDATE alert SET acknowledged_at = '2026-09-21 04:00:00'"));
        assertBad("chk_alert_lifecycle_shape", () -> exec("UPDATE alert SET status = 'ACKNOWLEDGED', acknowledged_at = '2026-09-21 04:00:00'"));
        exec("UPDATE alert SET status = 'ACKNOWLEDGED', acknowledged_at = '2026-09-21 04:00:00', acknowledged_by = 21");
        assertBad("chk_alert_lifecycle_shape", () -> exec("UPDATE alert SET status = 'RESOLVED', resolved_at = '2026-09-21 05:00:00', resolved_by = 21"));
        assertBad("chk_alert_lifecycle_shape", () -> exec("UPDATE alert SET status = 'RESOLVED', resolved_at = '2026-09-21 05:00:00', "
                + "resolved_by = 21, resolution = '  '"));
        exec("UPDATE alert SET status = 'RESOLVED', resolved_at = '2026-09-21 05:00:00', resolved_by = 21, resolution = '复检合格放行'");
        // 归属组织必须是运输任务发货方；片段记录必须属于同一运输任务；同一片段 / 编号唯一
        assertBad("fk_alert_shipment_sender", () -> insertAlert("ALT-X6", 701, 552, "TEMP_OVER_UPPER", "OPEN", 9002, 9002, "2026-09-21 01:40:00",
                "2026-09-21 01:40:00", 0, "-25.00", "-15.00", 0, "", ""));
        assertBad("fk_alert_episode_start", () -> insertAlert("ALT-X7", 730, 552, "TEMP_OVER_UPPER", "OPEN", 9003, 9002, "2026-09-20 02:00:00",
                "2026-09-21 01:40:00", 1800, "-25.00", "-15.00", 1800, "", ""));
        assertBad("fk_alert_sustained_record", () -> insertAlert("ALT-X8", 730, 552, "TEMP_OVER_UPPER", "OPEN", 9002, 9003, "2026-09-21 01:40:00",
                "2026-09-21 01:40:00", 0, "-25.00", "-15.00", 0, "", ""));
        assertBad("uk_alert_shipment_episode", () -> validAlert("ALT-X9", 9001, 9001));
        assertBad("uk_alert_no", () -> validAlert("ALT-V14-1", 9002, 9002));
        validAlert("ALT-V14-2", 9002, 9002);
        long alert1 = queryLong("SELECT id FROM alert WHERE alert_no = 'ALT-V14-1'");
        long alert2 = queryLong("SELECT id FROM alert WHERE alert_no = 'ALT-V14-2'");
        // 运输任务发货方不可改写（复合外键）
        assertBad("fk_alert_shipment_sender", () -> exec("UPDATE shipment SET sender_org_id = 720 WHERE id = 552"));

        // 3. 风险台账 ALERT 来源
        insertTransition(901, "NORMAL", "FROZEN", "ALERT", String.valueOf(alert1), "NULL", "SYS:ALERT:" + alert1 + ":BATCH:901");
        long freezeId = queryLong("SELECT id FROM batch_risk_transition WHERE source_alert_id = " + alert1);
        assertBad("chk_brt_source_shape", () -> insertTransition(901, "NORMAL", "FROZEN", "ALERT", String.valueOf(alert1), "11", "k-v14-a-actor-0001"));
        assertBad("chk_brt_source_shape", () -> insertTransition(901, "NORMAL", "FROZEN", "ALERT", "NULL", "NULL", "k-v14-a-nosrc-0001"));
        assertBad("chk_brt_source_shape", () -> insertTransition(901, "FROZEN", "NORMAL", "ALERT", String.valueOf(alert1), "NULL", "k-v14-a-rel-00001"));
        assertBad("chk_brt_source_shape", () -> insertTransition(901, "NORMAL", "FROZEN", "MANUAL", String.valueOf(alert1), "11", "k-v14-m-src-00001"));
        assertThatThrownBy(() -> insertTransition(901, "NORMAL", "FROZEN", "RECALL", "NULL", "11", "k-v14-recall-0001"))
                .isInstanceOf(SQLException.class).hasMessageMatching(".*chk_brt_source_(type|shape).*");
        assertBad("fk_brt_source_alert", () -> insertTransition(901, "NORMAL", "FROZEN", "ALERT", "999999", "NULL", "k-v14-a-fk-000001"));
        assertBad("chk_brt_transition", () -> insertTransition(901, "FROZEN", "RECALLED", "MANUAL", "NULL", "11", "k-v14-m-rec-00001"));

        // 4. alert_batch：冻结转换必须来源于同一告警；快照前 NORMAL 才有冻结转换
        exec("INSERT INTO alert_batch (alert_id, batch_id, transfer_id, org_id, risk_status_before, freeze_transition_id, created_at) "
                + "VALUES (" + alert1 + ", 901, 562, 730, 'NORMAL', " + freezeId + ", '2026-09-21 03:00:00')");
        exec("INSERT INTO alert_batch (alert_id, batch_id, transfer_id, org_id, risk_status_before, freeze_transition_id, created_at) "
                + "VALUES (" + alert1 + ", 902, 563, 730, 'FROZEN', NULL, '2026-09-21 03:00:00')");
        assertBad("fk_alert_batch_freeze", () -> exec("INSERT INTO alert_batch (alert_id, batch_id, transfer_id, org_id, risk_status_before, "
                + "freeze_transition_id, created_at) VALUES (" + alert2 + ", 901, 562, 730, 'NORMAL', " + freezeId + ", '2026-09-21 03:00:00')"));
        assertBad("chk_alert_batch_freeze_shape", () -> exec("INSERT INTO alert_batch (alert_id, batch_id, transfer_id, org_id, risk_status_before, "
                + "freeze_transition_id, created_at) VALUES (" + alert2 + ", 901, 562, 730, 'NORMAL', NULL, '2026-09-21 03:00:00')"));
        assertBad("chk_alert_batch_freeze_shape", () -> exec("INSERT INTO alert_batch (alert_id, batch_id, transfer_id, org_id, risk_status_before, "
                + "freeze_transition_id, created_at) VALUES (" + alert2 + ", 902, 563, 730, 'FROZEN', 7001, '2026-09-21 03:00:00')"));
        assertBad("chk_alert_batch_risk_before", () -> exec("INSERT INTO alert_batch (alert_id, batch_id, transfer_id, org_id, risk_status_before, "
                + "freeze_transition_id, created_at) VALUES (" + alert2 + ", 902, 563, 730, 'DRAFT', NULL, '2026-09-21 03:00:00')"));
        assertBad("uk_alert_batch", () -> exec("INSERT INTO alert_batch (alert_id, batch_id, transfer_id, org_id, risk_status_before, "
                + "freeze_transition_id, created_at) VALUES (" + alert1 + ", 902, 563, 730, 'FROZEN', NULL, '2026-09-21 03:00:00')"));

        // 5. alert_action：只有 ACKNOWLEDGE；说明非空白；组织内幂等键唯一
        exec("INSERT INTO alert_action (alert_id, org_id, action, actor_user_id, note, idempotency_key, request_hash, occurred_at) "
                + "VALUES (" + alert1 + ", 730, 'ACKNOWLEDGE', 21, NULL, 'k-v14-ack-000001', REPEAT('f', 64), '2026-09-21 04:00:00')");
        assertBad("chk_alert_action_action", () -> exec("INSERT INTO alert_action (alert_id, org_id, action, actor_user_id, note, "
                + "idempotency_key, request_hash, occurred_at) VALUES (" + alert1 + ", 730, 'DISMISS', 21, NULL, 'k-v14-ack-000002', "
                + "REPEAT('f', 64), '2026-09-21 04:00:00')"));
        assertBad("chk_alert_action_note", () -> exec("INSERT INTO alert_action (alert_id, org_id, action, actor_user_id, note, "
                + "idempotency_key, request_hash, occurred_at) VALUES (" + alert1 + ", 730, 'ACKNOWLEDGE', 21, '  ', 'k-v14-ack-000003', "
                + "REPEAT('f', 64), '2026-09-21 04:00:00')"));
        assertBad("uk_alert_action_org_idempotency", () -> exec("INSERT INTO alert_action (alert_id, org_id, action, actor_user_id, note, "
                + "idempotency_key, request_hash, occurred_at) VALUES (" + alert2 + ", 730, 'ACKNOWLEDGE', 21, NULL, 'k-v14-ack-000001', "
                + "REPEAT('f', 64), '2026-09-21 04:00:00')"));

        // 6. 被告警引用的温度记录与运输任务不能被物理删除
        assertThatThrownBy(() -> exec("DELETE FROM temperature_record WHERE id = 9001")).isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_alert_episode_start");
    }

    @Test
    @DisplayName("fail-fast：V13 状态下 V1 占位表 alert 已有存量行时，V14 在任何 DDL 之前失败、不被记录为成功，且不改动任何表")
    void upgrade_failsFastWhenAlertPlaceholderNotEmpty() throws Exception {
        migrateTo("13");
        seedV13Facts();
        exec("INSERT INTO alert (org_id, shipment_id, alert_type, triggered_at) VALUES (730, 552, 'TEMP_OVER_UPPER', '2026-09-21 02:00:00')");
        List<String> tables = businessTables();
        Map<String, String> before = fingerprints(tables, true);

        assertThatThrownBy(() -> migrateTo("14"))
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("passed");

        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '14' AND success = 1")).isZero();
        assertThat(fingerprints(tables, true)).as("no DDL applied before the precondition failed").isEqualTo(before);
        assertThat(queryLong("SELECT count(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() "
                + "AND constraint_name IN ('uk_shipment_id_sender', 'uk_temp_id_shipment')")).isZero();
    }
}
