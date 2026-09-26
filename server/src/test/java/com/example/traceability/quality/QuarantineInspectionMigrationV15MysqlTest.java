package com.example.traceability.quality;

import com.example.traceability.common.AbstractFlywayUpgradeMysqlTest;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V15（QUARANTINED 隔离收货、检验报告、告警放行与处置结论）真实 MySQL 8.4 升级迁移测试（Phase B PB4）。
 * <p>
 * 在隔离临时 schema 中先迁移到 V14，写入运输任务 / 交接（PENDING、ACCEPTED、REJECTED）、冻结批次、持续超温告警及其受影响批次快照、
 * 系统冻结转换与确认动作，再<b>原地</b>升级到 V15，验证：
 * <ol>
 *   <li>升级成功；除 transfer、inspection_report、alert_action、batch_risk_transition 外全部表逐表不变；
 *       既有交接、处置动作与风险转换逐列不变（不回填）；</li>
 *   <li>transfer：QUARANTINED 形状（实收、差异原因、隔离场所 / 原因 / 登记人全有，尚无决定）、隔离场所必须属于接收方、
 *       隔离中仍是未结束交接（同批次不能再建交接）、经隔离后 ACCEPTED / REJECTED 保留隔离事实；</li>
 *   <li>inspection_report：提交身份与隔离交接复合外键、关联告警必须包含该批次、结论只有 PASS / FAIL、来源、组织内报告编号唯一、检验时间；</li>
 *   <li>alert_action：RELEASE_BATCH 必须引用来源于同一告警的放行转换与该批次的报告，同一批次最多放行一次；
 *       batch_risk_transition：ALERT 放行 FROZEN → NORMAL 必须有操作人。</li>
 * </ol>
 * 反例：V14 状态下 V1 占位表 inspection_report 已有存量行时，V15 在任何 DDL 之前失败、不被记录为成功，且不改动任何表。不修改 V1–V14。
 * </p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("V15 数据库迁移真实 MySQL 8.4 测试（隔离收货、检验报告与告警放行）")
class QuarantineInspectionMigrationV15MysqlTest extends AbstractFlywayUpgradeMysqlTest {

    private static final List<String> ALTERED = List.of("transfer", "inspection_report", "alert_action", "batch_risk_transition");
    private static final String TRANSFER_ROWS = "SELECT GROUP_CONCAT(CONCAT_WS('|', id, transfer_no, batch_id, open_batch_id, shipment_id, sender_org_id, "
            + "receiver_org_id, quantity, status, received_at, decision_recorded_at, decided_by, received_quantity, difference_reason, "
            + "rejection_reason, version, is_legacy) ORDER BY id) FROM transfer";
    private static final String ACTION_ROWS = "SELECT GROUP_CONCAT(CONCAT_WS('|', id, alert_id, org_id, action, actor_user_id, note, "
            + "idempotency_key, request_hash, occurred_at) ORDER BY id) FROM alert_action";
    private static final String BRT_ROWS = "SELECT GROUP_CONCAT(CONCAT_WS('|', id, batch_id, org_id, from_status, to_status, source_type, "
            + "source_alert_id, actor_user_id, idempotency_key) ORDER BY id) FROM batch_risk_transition";

    @Override
    protected String pepper() {
        return "v15-migration-test-pepper-ffffffffffffffff";
    }

    private void seedV14Facts() throws SQLException {
        exec("""
                INSERT INTO organization (id, org_no, name, org_type, status) VALUES
                (720, 'V15_CAR', '承运', 'CARRIER', 'ACTIVE'),
                (730, 'V15_PRC', '加工', 'PROCESSOR', 'ACTIVE'),
                (701, 'V15_RET', '零售', 'RETAILER', 'ACTIVE'),
                (702, 'V15_OTH', '其他', 'RETAILER', 'ACTIVE')
                """);
        exec("""
                INSERT INTO site (id, org_id, site_no, name, site_type, status) VALUES
                (813, 730, 'PRC-COLD', '加工冷库', 'COLD_STORE', 'ACTIVE'),
                (801, 701, 'RET-STORE', '门店', 'STORE', 'ACTIVE'),
                (802, 701, 'RET-QUAR', '隔离冷库', 'COLD_STORE', 'ACTIVE'),
                (803, 702, 'OTH-STORE', '他组织门店', 'STORE', 'ACTIVE')
                """);
        exec("""
                INSERT INTO product (id, product_code, public_name, category, specification, source_type, base_unit_code, status)
                VALUES (401, 'V15_PRD', '冷冻大黄鱼', 'FISH', '500g/条', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE')
                """);
        exec("""
                INSERT INTO temperature_rule (id, product_id, version_no, name, effective_from, effective_to, status)
                VALUES (451, 401, 1, '冷冻大黄鱼运输规则', '2020-01-01 00:00:00', NULL, 'ACTIVE')
                """);
        exec("""
                INSERT INTO temperature_rule_stage (id, rule_id, stage_code, lower_limit, upper_limit, unit_code, allowed_duration_seconds, sequence_no)
                VALUES (461, 451, 'TRANSPORT', -25.00, -15.00, 'CELSIUS', 0, 1)
                """);
        exec("""
                INSERT INTO batch (id, org_id, creation_org_id, product_id, trace_batch_no, batch_type, quantity, unit_code,
                    origin_type, origin_text, flow_status, risk_status, version)
                VALUES
                (901, 730, 730, 401, 'TB-V15-901', 'PROCESSING', 600.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'FROZEN', 3),
                (902, 730, 730, 401, 'TB-V15-902', 'PROCESSING', 360.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'FROZEN', 3),
                (903, 701, 730, 401, 'TB-V15-903', 'PROCESSING', 100.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL', 3),
                (904, 730, 730, 401, 'TB-V15-904', 'PROCESSING', 100.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL', 3)
                """);
        exec("""
                INSERT INTO shipment (id, shipment_no, sender_org_id, receiver_org_id, carrier_org_id, vehicle_or_container_no,
                    origin_site_id, destination_site_id, loaded_at, unloaded_at, status,
                    dispatched_recorded_at, dispatched_by, delivered_recorded_at, delivered_by)
                VALUES
                (552, 'SHP-V15-S1', 730, 701, 720, '浙L·V15S1', 813, 801, '2026-09-21 01:00:00', '2026-09-21 05:00:00', 'DELIVERED',
                    '2026-09-21 01:00:00', 1, '2026-09-21 05:00:00', 1),
                (553, 'SHP-V15-S2', 730, 701, 720, '浙L·V15S2', 813, 801, '2026-09-20 01:00:00', '2026-09-20 05:00:00', 'DELIVERED',
                    '2026-09-20 01:00:00', 1, '2026-09-20 05:00:00', 1)
                """);
        exec("""
                INSERT INTO transfer (id, transfer_no, batch_id, shipment_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    status, idempotency_key, submitted_recorded_at, submitted_by, received_at, decision_recorded_at, decided_by,
                    received_quantity, rejection_reason)
                VALUES
                (562, 'TRF-V15-T2', 901, 552, 730, 701, 600.000, 'kg', 'PENDING', 'v15-t2-key', '2026-09-21 00:30:00', 2, NULL, NULL, NULL, NULL, NULL),
                (563, 'TRF-V15-T3', 902, 552, 730, 701, 360.000, 'kg', 'PENDING', 'v15-t3-key', '2026-09-21 00:30:00', 2, NULL, NULL, NULL, NULL, NULL),
                (564, 'TRF-V15-T4', 903, 553, 730, 701, 100.000, 'kg', 'ACCEPTED', 'v15-t4-key', '2026-09-20 00:30:00', 2,
                    '2026-09-20 06:00:00', '2026-09-20 06:00:00', 3, 100.000, NULL),
                (565, 'TRF-V15-T5', 904, 553, 730, 701, 100.000, 'kg', 'REJECTED', 'v15-t5-key', '2026-09-20 00:30:00', 2,
                    '2026-09-20 06:00:00', '2026-09-20 06:00:00', 3, NULL, '拒收')
                """);
        exec("""
                INSERT INTO temperature_record (id, shipment_id, org_id, actor_user_id, stage_code, measured_at, recorded_at, temperature,
                    unit_code, data_source, rule_stage_id, rule_lower_limit, rule_upper_limit, rule_allowed_duration_seconds, evaluation,
                    idempotency_key, request_hash)
                VALUES (9001, 552, 720, 3, 'TRANSPORT', '2026-09-21 02:00:00', '2026-09-21 02:00:00', -10.00, 'CELSIUS', 'MANUAL',
                    461, -25.00, -15.00, 0, 'HIGH', 'k-v15-000000000001', REPEAT('a', 64)),
                       (9002, 552, 720, 3, 'TRANSPORT', '2026-09-21 03:00:00', '2026-09-21 03:00:00', -9.00, 'CELSIUS', 'MANUAL',
                    461, -25.00, -15.00, 0, 'HIGH', 'k-v15-000000000002', REPEAT('a', 64))
                """);
        exec("""
                INSERT INTO alert (id, alert_no, org_id, shipment_id, alert_type, severity, status, reason, stage_code, episode_start_record_id,
                    sustained_record_id, episode_started_at, sustained_at, duration_seconds, rule_stage_id, rule_lower_limit, rule_upper_limit,
                    rule_allowed_duration_seconds, triggered_at, acknowledged_at, acknowledged_by)
                VALUES (3001, 'ALT-V15-1', 730, 552, 'TEMP_OVER_UPPER', 'HIGH', 'ACKNOWLEDGED', '在途持续超温', 'TRANSPORT', 9001, 9001,
                    '2026-09-21 02:00:00', '2026-09-21 02:00:00', 0, 461, -25.00, -15.00, 0, '2026-09-21 02:00:01', '2026-09-21 03:00:00', 21)
                """);
        exec("""
                INSERT INTO batch_risk_transition (id, batch_id, org_id, flow_status, from_status, to_status, source_type, source_alert_id,
                    actor_user_id, reason, idempotency_key, request_hash, occurred_at)
                VALUES
                (7101, 901, 730, 'ACTIVE', 'NORMAL', 'FROZEN', 'ALERT', 3001, NULL, '告警自动冻结', 'SYS:ALERT:3001:BATCH:901', REPEAT('b', 64), '2026-09-21 02:00:01'),
                (7102, 902, 730, 'ACTIVE', 'NORMAL', 'FROZEN', 'ALERT', 3001, NULL, '告警自动冻结', 'SYS:ALERT:3001:BATCH:902', REPEAT('c', 64), '2026-09-21 02:00:01')
                """);
        exec("""
                INSERT INTO alert_batch (alert_id, batch_id, transfer_id, org_id, risk_status_before, freeze_transition_id, created_at)
                VALUES (3001, 901, 562, 730, 'NORMAL', 7101, '2026-09-21 02:00:01'),
                       (3001, 902, 563, 730, 'NORMAL', 7102, '2026-09-21 02:00:01')
                """);
        exec("""
                INSERT INTO alert_action (id, alert_id, org_id, action, actor_user_id, note, idempotency_key, request_hash, occurred_at)
                VALUES (8001, 3001, 730, 'ACKNOWLEDGE', 21, NULL, 'k-v15-ack-00000001', REPEAT('d', 64), '2026-09-21 03:00:00')
                """);
    }

    private void quarantine(long transferId, String siteId, String reason) throws SQLException {
        exec("UPDATE transfer SET status = 'QUARANTINED', received_at = '2026-09-21 05:10:00', received_quantity = quantity, "
                + "quarantine_site_id = " + siteId + ", quarantine_reason = " + reason + ", quarantined_recorded_at = '2026-09-21 05:10:00', "
                + "quarantined_by = 31 WHERE id = " + transferId);
    }

    private void insertReport(long batchId, long orgId, String role, String transferId, String alertId, String reportNo,
                              String conclusion, String source, String inspectedAt, String key) throws SQLException {
        exec("INSERT INTO inspection_report (batch_id, org_id, submitter_role, transfer_id, alert_id, report_no, institution_name, inspected_at, "
                + "items_summary, conclusion, data_source, actor_user_id, idempotency_key, request_hash, recorded_at) VALUES ("
                + batchId + ", " + orgId + ", '" + role + "', " + transferId + ", " + alertId + ", '" + reportNo + "', '演示检测中心', '"
                + inspectedAt + "', '感官与微生物', '" + conclusion + "', '" + source + "', 21, '" + key + "', REPEAT('e', 64), '2026-09-21 06:00:00')");
    }

    private void report(long batchId, long orgId, String role, String transferId, String alertId, String reportNo, String key) throws SQLException {
        insertReport(batchId, orgId, role, transferId, alertId, reportNo, "PASS", "MANUAL", "2026-09-21 05:30:00", key);
    }

    private void action(String action, String batchId, String transitionId, String reportId, String key) throws SQLException {
        exec("INSERT INTO alert_action (alert_id, org_id, action, batch_id, risk_transition_id, inspection_report_id, actor_user_id, note, "
                + "idempotency_key, request_hash, occurred_at) VALUES (3001, 730, '" + action + "', " + batchId + ", " + transitionId + ", "
                + reportId + ", 21, NULL, '" + key + "', REPEAT('f', 64), '2026-09-21 07:00:00')");
    }

    @Test
    @DisplayName("原地升级：V14 事实无需清理即升级成功；未改动表逐表不变；新形状只接受 PB4 的隔离 / 检验 / 放行事实")
    void upgrade_fromV14() throws Exception {
        migrateTo("14");
        seedV14Facts();
        List<String> tables = businessTables();
        List<String> untouched = tables.stream().filter(t -> !ALTERED.contains(t)).toList();
        Map<String, String> before = fingerprints(untouched, true);
        String transfersBefore = queryString(TRANSFER_ROWS);
        String actionsBefore = queryString(ACTION_ROWS);
        String brtBefore = queryString(BRT_ROWS);

        migrateTo("15");

        // 1. 升级成功、不新增表、未改动表逐表不变、既有行逐列不变
        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '15' AND success = 1")).isEqualTo(1);
        assertThat(businessTables()).containsExactlyElementsOf(tables);
        assertThat(fingerprints(untouched, true)).isEqualTo(before);
        assertThat(queryString(TRANSFER_ROWS)).isEqualTo(transfersBefore);
        assertThat(queryString(ACTION_ROWS)).isEqualTo(actionsBefore);
        assertThat(queryString(BRT_ROWS)).isEqualTo(brtBefore);
        assertThat(queryLong("SELECT count(*) FROM transfer WHERE quarantine_site_id IS NOT NULL OR quarantine_reason IS NOT NULL")).isZero();
        assertThat(queryLong("SELECT count(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'inspection_report' AND column_name = 'institution_verified'")).as("no authenticity claim column").isZero();

        // 2. transfer：状态、QUARANTINED 形状、隔离场所归属、隔离期间仍是未结束交接
        assertThatThrownBy(() -> exec("UPDATE transfer SET status = 'HOLD' WHERE id = 562")).isInstanceOf(SQLException.class)
                .hasMessageMatching(".*chk_transfer_(status|decision_shape).*");
        assertBad("chk_transfer_decision_shape", () -> quarantine(562, "NULL", "'隔离'"));
        assertBad("chk_transfer_decision_shape", () -> quarantine(562, "802", "'   '"));
        assertBad("fk_transfer_quarantine_site", () -> quarantine(562, "803", "'隔离'"));
        assertBad("fk_transfer_quarantine_site", () -> quarantine(562, "813", "'隔离'"));
        quarantine(562, "802", "'到货随附告警，隔离待检'");
        assertThat(queryLong("SELECT open_batch_id FROM transfer WHERE id = 562")).isEqualTo(901);
        assertBad("uk_transfer_open_batch", () -> exec("INSERT INTO transfer (transfer_no, batch_id, sender_org_id, receiver_org_id, quantity, "
                + "unit_code, status, idempotency_key) VALUES ('TRF-V15-DUP', 901, 730, 702, 600.000, 'kg', 'DRAFT', 'v15-dup-key')"));
        assertBad("chk_transfer_decision_shape", () -> exec("UPDATE transfer SET decided_by = 3 WHERE id = 562"));
        // 经隔离后决定：保留全部隔离事实；拒收保留实收数量
        assertBad("chk_transfer_decision_shape", () -> exec("UPDATE transfer SET status = 'ACCEPTED', decision_recorded_at = '2026-09-22 00:00:00', "
                + "decided_by = 3, quarantine_reason = NULL WHERE id = 562"));
        exec("UPDATE transfer SET status = 'REJECTED', decision_recorded_at = '2026-09-22 00:00:00', decided_by = 3, rejection_reason = '不合格' "
                + "WHERE id = 562");
        assertThat(queryLong("SELECT count(*) FROM transfer WHERE id = 562 AND open_batch_id IS NULL AND received_quantity = 600")).isEqualTo(1);
        quarantine(563, "802", "'到货随附告警，隔离待检'");
        exec("UPDATE transfer SET status = 'ACCEPTED', decision_recorded_at = '2026-09-22 00:00:00', decided_by = 3 WHERE id = 563");
        // 直接拒收（未隔离）不得带实收数量；直接接受不得带部分隔离事实
        assertBad("chk_transfer_decision_shape", () -> exec("UPDATE transfer SET received_quantity = 100 WHERE id = 565"));
        assertBad("chk_transfer_decision_shape", () -> exec("UPDATE transfer SET quarantine_reason = '补记' WHERE id = 564"));

        // 3. inspection_report
        report(901, 730, "CURRENT_ORG", "NULL", "3001", "SND-001", "k-v15-rpt-000001");
        report(901, 701, "QUARANTINE_RECEIVER", "562", "3001", "RCV-001", "k-v15-rpt-000002");
        long passReport = queryLong("SELECT id FROM inspection_report WHERE report_no = 'SND-001'");
        assertBad("fk_inspection_quarantine_transfer", () -> report(901, 702, "QUARANTINE_RECEIVER", "562", "NULL", "OTH-001", "k-v15-rpt-000003"));
        assertBad("fk_inspection_quarantine_transfer", () -> report(902, 701, "QUARANTINE_RECEIVER", "562", "NULL", "RCV-002", "k-v15-rpt-000004"));
        assertBad("chk_inspection_submitter_role", () -> report(901, 730, "CURRENT_ORG", "562", "NULL", "SND-002", "k-v15-rpt-000005"));
        assertBad("chk_inspection_submitter_role", () -> report(901, 701, "QUARANTINE_RECEIVER", "NULL", "NULL", "RCV-003", "k-v15-rpt-000006"));
        assertBad("chk_inspection_submitter_role", () -> report(901, 730, "AUTHORITY", "NULL", "NULL", "SND-003", "k-v15-rpt-000007"));
        assertBad("fk_inspection_alert_batch", () -> report(903, 701, "CURRENT_ORG", "NULL", "3001", "RCV-004", "k-v15-rpt-000008"));
        assertBad("chk_inspection_conclusion", () -> insertReport(901, 730, "CURRENT_ORG", "NULL", "NULL", "SND-004", "PENDING", "MANUAL",
                "2026-09-21 05:30:00", "k-v15-rpt-000009"));
        assertBad("chk_inspection_source", () -> insertReport(901, 730, "CURRENT_ORG", "NULL", "NULL", "SND-005", "PASS", "IMPORT",
                "2026-09-21 05:30:00", "k-v15-rpt-000010"));
        assertBad("chk_inspection_time", () -> insertReport(901, 730, "CURRENT_ORG", "NULL", "NULL", "SND-006", "PASS", "MANUAL",
                "2026-09-21 06:05:01", "k-v15-rpt-000011"));
        assertBad("uk_inspection_org_report_no", () -> report(902, 730, "CURRENT_ORG", "NULL", "3001", "SND-001", "k-v15-rpt-000012"));
        assertBad("uk_inspection_org_idempotency", () -> report(902, 730, "CURRENT_ORG", "NULL", "3001", "SND-007", "k-v15-rpt-000001"));

        // 4. 放行转换与放行动作
        assertBad("chk_brt_source_shape", () -> exec("INSERT INTO batch_risk_transition (batch_id, org_id, flow_status, from_status, to_status, "
                + "source_type, source_alert_id, actor_user_id, reason, idempotency_key, request_hash, occurred_at) VALUES (901, 730, 'ACTIVE', "
                + "'FROZEN', 'NORMAL', 'ALERT', 3001, NULL, '放行', 'SYS:ALERT-RELEASE:3001:BATCH:901', REPEAT('g', 64), '2026-09-21 07:00:00')"));
        exec("INSERT INTO batch_risk_transition (id, batch_id, org_id, flow_status, from_status, to_status, source_type, source_alert_id, "
                + "actor_user_id, reason, idempotency_key, request_hash, occurred_at) VALUES (7103, 901, 730, 'ACTIVE', 'FROZEN', 'NORMAL', "
                + "'ALERT', 3001, 21, '依据检验结论放行', 'SYS:ALERT-RELEASE:3001:BATCH:901', REPEAT('g', 64), '2026-09-21 07:00:00')");
        assertBad("chk_alert_action_shape", () -> action("RELEASE_BATCH", "901", "7103", "NULL", "k-v15-act-000001"));
        assertBad("chk_alert_action_shape", () -> action("ACKNOWLEDGE", "901", "NULL", "NULL", "k-v15-act-000002"));
        assertBad("fk_alert_action_report", () -> action("RELEASE_BATCH", "902", "7102", String.valueOf(passReport), "k-v15-act-000003"));
        action("RELEASE_BATCH", "901", "7103", String.valueOf(passReport), "k-v15-act-000004");
        assertBad("uk_alert_action_release", () -> action("RELEASE_BATCH", "901", "7101", String.valueOf(passReport), "k-v15-act-000005"));
        assertThatThrownBy(() -> action("DISMISS", "NULL", "NULL", "NULL", "k-v15-act-000006")).isInstanceOf(SQLException.class)
                .hasMessageMatching(".*chk_alert_action_(action|shape).*");
        action("RESOLVE", "NULL", "NULL", "NULL", "k-v15-act-000007");
        // 放行转换必须来源于同一告警：另建告警后引用其他告警的转换失败
        exec("INSERT INTO alert (id, alert_no, org_id, shipment_id, alert_type, severity, status, reason, stage_code, episode_start_record_id, "
                + "sustained_record_id, episode_started_at, sustained_at, duration_seconds, rule_stage_id, rule_lower_limit, rule_upper_limit, "
                + "rule_allowed_duration_seconds, triggered_at) VALUES (3002, 'ALT-V15-2', 730, 552, 'TEMP_OVER_UPPER', 'HIGH', 'OPEN', '另一片段', "
                + "'TRANSPORT', 9002, 9002, '2026-09-21 03:00:00', '2026-09-21 03:00:00', 0, 461, -25.00, -15.00, 0, '2026-09-21 08:00:00')");
        assertThatThrownBy(() -> exec("INSERT INTO alert_action (alert_id, org_id, action, batch_id, risk_transition_id, inspection_report_id, "
                + "actor_user_id, idempotency_key, request_hash, occurred_at) VALUES (3002, 730, 'RELEASE_BATCH', 901, 7103, " + passReport
                + ", 21, 'k-v15-act-000008', REPEAT('f', 64), '2026-09-21 09:00:00')")).isInstanceOf(SQLException.class)
                .hasMessageMatching(".*(fk_alert_action_transition|fk_alert_action_batch).*");
    }

    @Test
    @DisplayName("fail-fast：V14 状态下 V1 占位表 inspection_report 已有存量行时，V15 在任何 DDL 之前失败、不被记录为成功，且不改动任何表")
    void upgrade_failsFastWhenInspectionPlaceholderNotEmpty() throws Exception {
        migrateTo("14");
        seedV14Facts();
        exec("INSERT INTO inspection_report (batch_id, org_id, report_no, institution_name, inspected_at, items_summary) "
                + "VALUES (901, 730, 'LEGACY-1', '机构', '2026-09-21 05:00:00', '摘要')");
        List<String> tables = businessTables();
        Map<String, String> before = fingerprints(tables, true);

        assertThatThrownBy(() -> migrateTo("15")).isInstanceOf(FlywayException.class).hasStackTraceContaining("passed");

        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '15' AND success = 1")).isZero();
        assertThat(fingerprints(tables, true)).as("no DDL applied before the precondition failed").isEqualTo(before);
    }
}
