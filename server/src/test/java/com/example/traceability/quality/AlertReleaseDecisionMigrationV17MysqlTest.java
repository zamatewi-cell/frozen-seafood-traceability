package com.example.traceability.quality;

import com.example.traceability.common.AbstractFlywayUpgradeMysqlTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V17（逐告警放行结论：RELEASE_BATCH 的放行转换改为可空）真实 MySQL 8.4 升级迁移测试（Phase B 独立评审修复）。
 * <p>
 * 在隔离临时 schema 中先迁移到 V16，写入同一运输任务两个越界片段形成的两个告警（同一批次同时处于两个告警中）、
 * 自动冻结与依据检验结论的放行转换、检验报告与处置动作，再<b>原地</b>升级到 V17，验证：
 * <ol>
 *   <li>升级成功；除 alert_action 外全部表逐表不变（含 DDL）；alert_action 的既有行逐列不变（只放宽约束，不回填）；</li>
 *   <li>RELEASE_BATCH 可以不引用放行转换（其他风险事项仍未解除时只记录本告警的放行结论），但仍必须有批次与依据检验报告；</li>
 *   <li>ACKNOWLEDGE / RESOLVE 形状不变；放行转换非空时仍必须来源于同一告警；同一告警同一批次仍只能有一个放行结论。</li>
 * </ol>
 * 不修改 V1–V16。
 * </p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("V17 数据库迁移真实 MySQL 8.4 测试（逐告警放行结论）")
class AlertReleaseDecisionMigrationV17MysqlTest extends AbstractFlywayUpgradeMysqlTest {

    private static final String ACTION_ROWS = "SELECT GROUP_CONCAT(CONCAT_WS('|', id, alert_id, org_id, action, batch_id, risk_transition_id, "
            + "inspection_report_id, released_batch_id, actor_user_id, idempotency_key, request_hash, occurred_at) ORDER BY id) FROM alert_action";

    @Override
    protected String pepper() {
        return "v17-migration-test-pepper-ffffffffffffffff";
    }

    private void seedV16Facts() throws SQLException {
        exec("""
                INSERT INTO organization (id, org_no, name, org_type, status) VALUES
                (720, 'V17_CAR', '承运', 'CARRIER', 'ACTIVE'),
                (730, 'V17_PRC', '加工', 'PROCESSOR', 'ACTIVE'),
                (701, 'V17_RET', '零售', 'RETAILER', 'ACTIVE')
                """);
        exec("""
                INSERT INTO site (id, org_id, site_no, name, site_type, status) VALUES
                (813, 730, 'PRC-COLD', '加工冷库', 'COLD_STORE', 'ACTIVE'),
                (801, 701, 'RET-STORE', '门店', 'STORE', 'ACTIVE')
                """);
        exec("""
                INSERT INTO product (id, product_code, public_name, category, specification, source_type, base_unit_code, status)
                VALUES (401, 'V17_PRD', '冷冻大黄鱼', 'FISH', '500g/条', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE')
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
                (901, 730, 730, 401, 'TB-V17-901', 'PROCESSING', 600.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'FROZEN', 3),
                (902, 730, 730, 401, 'TB-V17-902', 'PROCESSING', 360.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL', 5)
                """);
        exec("""
                INSERT INTO shipment (id, shipment_no, sender_org_id, receiver_org_id, carrier_org_id, vehicle_or_container_no,
                    origin_site_id, destination_site_id, loaded_at, unloaded_at, status,
                    dispatched_recorded_at, dispatched_by, delivered_recorded_at, delivered_by)
                VALUES (552, 'SHP-V17-S1', 730, 701, 720, '浙L·V17S1', 813, 801, '2026-09-21 01:00:00', '2026-09-21 05:00:00', 'DELIVERED',
                    '2026-09-21 01:00:00', 1, '2026-09-21 05:00:00', 1)
                """);
        exec("""
                INSERT INTO transfer (id, transfer_no, batch_id, shipment_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    status, idempotency_key, submitted_recorded_at, submitted_by)
                VALUES
                (562, 'TRF-V17-T1', 901, 552, 730, 701, 600.000, 'kg', 'PENDING', 'v17-t1-key', '2026-09-21 00:30:00', 2),
                (563, 'TRF-V17-T2', 902, 552, 730, 701, 360.000, 'kg', 'PENDING', 'v17-t2-key', '2026-09-21 00:30:00', 2)
                """);
        // 越界片段 1（9001）→ 回到范围内（9002）→ 越界片段 2（9003）
        exec("""
                INSERT INTO temperature_record (id, shipment_id, org_id, actor_user_id, stage_code, measured_at, recorded_at, temperature,
                    unit_code, data_source, rule_stage_id, rule_lower_limit, rule_upper_limit, rule_allowed_duration_seconds, evaluation,
                    idempotency_key, request_hash)
                VALUES (9001, 552, 720, 3, 'TRANSPORT', '2026-09-21 02:00:00', '2026-09-21 02:00:00', -10.00, 'CELSIUS', 'MANUAL',
                    461, -25.00, -15.00, 0, 'HIGH', 'k-v17-000000000001', REPEAT('a', 64)),
                       (9002, 552, 720, 3, 'TRANSPORT', '2026-09-21 02:30:00', '2026-09-21 02:30:00', -18.00, 'CELSIUS', 'MANUAL',
                    461, -25.00, -15.00, 0, 'NORMAL', 'k-v17-000000000002', REPEAT('a', 64)),
                       (9003, 552, 720, 3, 'TRANSPORT', '2026-09-21 03:00:00', '2026-09-21 03:00:00', -9.00, 'CELSIUS', 'MANUAL',
                    461, -25.00, -15.00, 0, 'HIGH', 'k-v17-000000000003', REPEAT('a', 64))
                """);
        exec("""
                INSERT INTO alert (id, alert_no, org_id, shipment_id, alert_type, severity, status, reason, stage_code, episode_start_record_id,
                    sustained_record_id, episode_started_at, sustained_at, duration_seconds, rule_stage_id, rule_lower_limit, rule_upper_limit,
                    rule_allowed_duration_seconds, triggered_at, acknowledged_at, acknowledged_by)
                VALUES
                (3001, 'ALT-V17-1', 730, 552, 'TEMP_OVER_UPPER', 'HIGH', 'ACKNOWLEDGED', '在途持续超温', 'TRANSPORT', 9001, 9001,
                    '2026-09-21 02:00:00', '2026-09-21 02:00:00', 0, 461, -25.00, -15.00, 0, '2026-09-21 02:00:01', '2026-09-21 04:00:00', 21),
                (3002, 'ALT-V17-2', 730, 552, 'TEMP_OVER_UPPER', 'HIGH', 'ACKNOWLEDGED', '在途持续超温', 'TRANSPORT', 9003, 9003,
                    '2026-09-21 03:00:00', '2026-09-21 03:00:00', 0, 461, -25.00, -15.00, 0, '2026-09-21 03:00:01', '2026-09-21 04:00:00', 21)
                """);
        exec("""
                INSERT INTO batch_risk_transition (id, batch_id, org_id, flow_status, from_status, to_status, source_type, source_alert_id,
                    actor_user_id, reason, idempotency_key, request_hash, occurred_at)
                VALUES
                (7101, 901, 730, 'ACTIVE', 'NORMAL', 'FROZEN', 'ALERT', 3001, NULL, '告警自动冻结', 'SYS:ALERT:3001:BATCH:901', REPEAT('b', 64), '2026-09-21 02:00:01'),
                (7102, 902, 730, 'ACTIVE', 'NORMAL', 'FROZEN', 'ALERT', 3001, NULL, '告警自动冻结', 'SYS:ALERT:3001:BATCH:902', REPEAT('c', 64), '2026-09-21 02:00:01'),
                (7103, 902, 730, 'ACTIVE', 'FROZEN', 'NORMAL', 'ALERT', 3001, 21, '依据检验结论放行', 'SYS:ALERT-RELEASE:3001:BATCH:902', REPEAT('d', 64),
                    '2026-09-21 06:00:00')
                """);
        exec("""
                INSERT INTO alert_batch (alert_id, batch_id, transfer_id, org_id, risk_status_before, freeze_transition_id, created_at)
                VALUES (3001, 901, 562, 730, 'NORMAL', 7101, '2026-09-21 02:00:01'),
                       (3001, 902, 563, 730, 'NORMAL', 7102, '2026-09-21 02:00:01'),
                       (3002, 901, 562, 730, 'FROZEN', NULL, '2026-09-21 03:00:01'),
                       (3002, 902, 563, 730, 'FROZEN', NULL, '2026-09-21 03:00:01')
                """);
        exec("""
                INSERT INTO inspection_report (id, batch_id, org_id, submitter_role, transfer_id, alert_id, report_no, institution_name, inspected_at,
                    items_summary, conclusion, data_source, actor_user_id, idempotency_key, request_hash, recorded_at)
                VALUES
                (6001, 902, 730, 'CURRENT_ORG', NULL, 3001, 'V17-R-902-A1', '演示检测中心', '2026-09-21 05:30:00', '感官与微生物', 'PASS', 'MANUAL', 21,
                    'k-v17-rpt-0000001', REPEAT('e', 64), '2026-09-21 05:40:00'),
                (6002, 901, 730, 'CURRENT_ORG', NULL, 3001, 'V17-R-901-A1', '演示检测中心', '2026-09-21 05:30:00', '感官与微生物', 'PASS', 'MANUAL', 21,
                    'k-v17-rpt-0000002', REPEAT('e', 64), '2026-09-21 05:41:00'),
                (6003, 901, 730, 'CURRENT_ORG', NULL, 3002, 'V17-R-901-A2', '演示检测中心', '2026-09-21 05:30:00', '感官与微生物', 'PASS', 'MANUAL', 21,
                    'k-v17-rpt-0000003', REPEAT('e', 64), '2026-09-21 05:42:00')
                """);
        // V16 既有形状：确认 ×2，以及引用放行转换的 RELEASE_BATCH（902 依据 A1 放行）
        exec("""
                INSERT INTO alert_action (id, alert_id, org_id, action, batch_id, risk_transition_id, inspection_report_id, actor_user_id, note,
                    idempotency_key, request_hash, occurred_at)
                VALUES
                (8001, 3001, 730, 'ACKNOWLEDGE', NULL, NULL, NULL, 21, NULL, 'k-v17-ack-00000001', REPEAT('f', 64), '2026-09-21 04:00:00'),
                (8002, 3002, 730, 'ACKNOWLEDGE', NULL, NULL, NULL, 21, NULL, 'k-v17-ack-00000002', REPEAT('f', 64), '2026-09-21 04:00:00'),
                (8003, 3001, 730, 'RELEASE_BATCH', 902, 7103, 6001, 21, NULL, 'k-v17-rel-00000001', REPEAT('f', 64), '2026-09-21 06:00:00')
                """);
    }

    private void action(long alertId, String action, String batchId, String transitionId, String reportId, String key) throws SQLException {
        exec("INSERT INTO alert_action (alert_id, org_id, action, batch_id, risk_transition_id, inspection_report_id, actor_user_id, note, "
                + "idempotency_key, request_hash, occurred_at) VALUES (" + alertId + ", 730, '" + action + "', " + batchId + ", " + transitionId
                + ", " + reportId + ", 21, NULL, '" + key + "', REPEAT('9', 64), '2026-09-21 07:00:00')");
    }

    @Test
    @DisplayName("原地升级：V16 事实无需清理即升级成功；只放宽 alert_action 形状，其余表与既有动作行逐列不变")
    void upgrade_fromV16() throws Exception {
        migrateTo("16");
        seedV16Facts();
        List<String> tables = businessTables();
        List<String> untouched = tables.stream().filter(t -> !t.equals("alert_action")).toList();
        Map<String, String> before = fingerprints(untouched, true);
        String actionsBefore = queryString(ACTION_ROWS);
        assertBad("chk_alert_action_shape", () -> action(3001, "RELEASE_BATCH", "901", "NULL", "6002", "k-v17-pre-0000001"));

        migrateTo("17");

        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '17' AND success = 1")).isEqualTo(1);
        assertThat(businessTables()).containsExactlyElementsOf(tables);
        assertThat(fingerprints(untouched, true)).as("V17 only relaxes alert_action").isEqualTo(before);
        assertThat(queryString(ACTION_ROWS)).as("existing actions are not backfilled or rewritten").isEqualTo(actionsBefore);

        // 1. 其他风险事项仍未解除：只记录本告警的放行结论（无转换），仍须有批次与依据检验报告
        action(3001, "RELEASE_BATCH", "901", "NULL", "6002", "k-v17-new-0000001");
        assertThat(queryLong("SELECT count(*) FROM alert_action WHERE alert_id = 3001 AND batch_id = 901 AND action = 'RELEASE_BATCH' "
                + "AND risk_transition_id IS NULL AND released_batch_id = 901")).isEqualTo(1);
        assertBad("chk_alert_action_shape", () -> action(3002, "RELEASE_BATCH", "901", "NULL", "NULL", "k-v17-new-0000002"));
        assertBad("chk_alert_action_shape", () -> action(3002, "RELEASE_BATCH", "NULL", "NULL", "6003", "k-v17-new-0000003"));

        // 2. ACKNOWLEDGE / RESOLVE 形状不变
        assertBad("chk_alert_action_shape", () -> action(3002, "RESOLVE", "901", "NULL", "NULL", "k-v17-new-0000004"));
        assertBad("chk_alert_action_shape", () -> action(3002, "ACKNOWLEDGE", "NULL", "NULL", "6003", "k-v17-new-0000005"));

        // 3. 放行转换非空时仍必须来源于同一告警；依据报告必须属于同一批次；同一告警同一批次仍只能有一个放行结论
        assertBad("fk_alert_action_transition", () -> action(3002, "RELEASE_BATCH", "902", "7103", "6001", "k-v17-new-0000006"));
        assertBad("fk_alert_action_report", () -> action(3002, "RELEASE_BATCH", "902", "NULL", "6003", "k-v17-new-0000007"));
        assertBad("uk_alert_action_release", () -> action(3001, "RELEASE_BATCH", "901", "NULL", "6002", "k-v17-new-0000008"));

        // 4. 另一告警对同一批次的放行结论可以引用来源于该告警的放行转换（最后一个风险事项解除时的转换）
        exec("""
                INSERT INTO batch_risk_transition (id, batch_id, org_id, flow_status, from_status, to_status, source_type, source_alert_id,
                    actor_user_id, reason, idempotency_key, request_hash, occurred_at)
                VALUES (7104, 901, 730, 'ACTIVE', 'FROZEN', 'NORMAL', 'ALERT', 3002, 21, '依据检验结论放行', 'SYS:ALERT-RELEASE:3002:BATCH:901',
                    REPEAT('d', 64), '2026-09-21 08:00:00')
                """);
        action(3002, "RELEASE_BATCH", "901", "7104", "6003", "k-v17-new-0000009");
        assertThat(queryLong("SELECT count(*) FROM alert_action WHERE action = 'RELEASE_BATCH' AND batch_id = 901")).isEqualTo(2);
    }
}
