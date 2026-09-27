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
 * V16（模拟召回、影响范围快照、RECALL 来源与 RECALLED 终态）真实 MySQL 8.4 升级迁移测试（Phase B PB5）。
 * <p>
 * 在隔离临时 schema 中先迁移到 V15，写入组织 / 产品、冻结与已关闭批次、人工风险转换，再<b>原地</b>升级到 V16，验证：
 * <ol>
 *   <li>升级成功；除 recall、recall_batch、batch_risk_transition 外全部表逐表不变；既有风险转换逐列不变（不回填）；</li>
 *   <li>recall：IN_PROGRESS / CLOSED 生命周期形状（关闭必须有受控公开处置结论与内部总结）、原因非空、编号与组织内幂等键唯一；</li>
 *   <li>batch_risk_transition：只有 RECALL 来源（有操作人、有召回来源、无告警来源）可以转为 RECALLED，RECALLED 不再转出；</li>
 *   <li>recall_batch：范围角色与动作 / 距离一致、RECALLED 行必须引用来源于同一召回的转换、数量非负且不超过声明数量、
 *       未结束交接形状、同一召回同一批次唯一。</li>
 * </ol>
 * 反例：V15 状态下 V1 占位表 recall 已有存量行时，V16 在任何 DDL 之前失败、不被记录为成功，且不改动任何表。不修改 V1–V15。
 * </p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("V16 数据库迁移真实 MySQL 8.4 测试（模拟召回）")
class RecallMigrationV16MysqlTest extends AbstractFlywayUpgradeMysqlTest {

    private static final List<String> ALTERED = List.of("recall", "recall_batch", "batch_risk_transition");
    private static final String BRT_ROWS = "SELECT GROUP_CONCAT(CONCAT_WS('|', id, batch_id, org_id, flow_status, from_status, to_status, "
            + "source_type, actor_user_id, idempotency_key) ORDER BY id) FROM batch_risk_transition";

    @Override
    protected String pepper() {
        return "v16-migration-test-pepper-ffffffffffffffff";
    }

    private void seedV15Facts() throws SQLException {
        exec("""
                INSERT INTO organization (id, org_no, name, org_type, status) VALUES
                (730, 'V16_PRC', '加工', 'PROCESSOR', 'ACTIVE'),
                (701, 'V16_RET', '零售', 'RETAILER', 'ACTIVE')
                """);
        exec("""
                INSERT INTO product (id, product_code, public_name, category, specification, source_type, base_unit_code, status)
                VALUES (401, 'V16_PRD', '冷冻大黄鱼', 'FISH', '500g/条', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE')
                """);
        exec("""
                INSERT INTO batch (id, org_id, creation_org_id, product_id, trace_batch_no, batch_type, quantity, unit_code,
                    origin_type, origin_text, flow_status, risk_status, version)
                VALUES
                (901, 730, 730, 401, 'TB-V16-901', 'PROCESSING', 600.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'FROZEN', 3),
                (902, 701, 730, 401, 'TB-V16-902', 'PROCESSING', 360.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'CLOSED', 'NORMAL', 5),
                (903, 730, 730, 401, 'TB-V16-903', 'PROCESSING', 100.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL', 1)
                """);
        exec("""
                INSERT INTO batch_risk_transition (id, batch_id, org_id, flow_status, from_status, to_status, source_type, actor_user_id,
                    reason, idempotency_key, request_hash, occurred_at)
                VALUES (7001, 901, 730, 'ACTIVE', 'NORMAL', 'FROZEN', 'MANUAL', 21, '抽检异常', 'k-v16-manual-000001', REPEAT('a', 64),
                    '2026-09-21 01:00:00')
                """);
    }

    private void recall(long id, String no, String key) throws SQLException {
        exec("INSERT INTO recall (id, recall_no, owner_org_id, reason, status, started_at, started_by, idempotency_key, request_hash) "
                + "VALUES (" + id + ", '" + no + "', 730, '检验不合格', 'IN_PROGRESS', '2026-09-22 00:00:00', 21, '" + key + "', REPEAT('b', 64))");
    }

    private void transition(long batchId, String from, String to, String source, String recallId, String alertId, String actor, String key)
            throws SQLException {
        exec("INSERT INTO batch_risk_transition (batch_id, org_id, flow_status, from_status, to_status, source_type, source_alert_id, "
                + "source_recall_id, actor_user_id, reason, idempotency_key, request_hash, occurred_at) VALUES (" + batchId + ", 730, 'ACTIVE', '"
                + from + "', '" + to + "', '" + source + "', " + alertId + ", " + recallId + ", " + actor + ", '模拟召回', '" + key
                + "', REPEAT('c', 64), '2026-09-22 00:00:00')");
    }

    private void scope(long recallId, long batchId, String role, int depth, String riskBefore, String action, String transitionId,
                       String remaining, String transferId, String transferStatus) throws SQLException {
        exec("INSERT INTO recall_batch (recall_id, batch_id, scope_role, depth, holder_org_id, flow_status, risk_status_before, action, "
                + "risk_transition_id, declared_quantity, remaining_quantity, sold_quantity, unit_code, open_transfer_id, open_transfer_status, "
                + "shipment_status, public_code_active, created_at) VALUES (" + recallId + ", " + batchId + ", '" + role + "', " + depth
                + ", 730, 'ACTIVE', '" + riskBefore + "', '" + action + "', " + transitionId + ", 600.000, " + remaining + ", 0.000, 'kg', "
                + transferId + ", " + transferStatus + ", NULL, 0, '2026-09-22 00:00:00')");
    }

    @Test
    @DisplayName("原地升级：V15 事实无需清理即升级成功；未改动表逐表不变；新形状只接受 PB5 的召回 / RECALLED / 影响范围事实")
    void upgrade_fromV15() throws Exception {
        migrateTo("15");
        seedV15Facts();
        List<String> tables = businessTables();
        List<String> untouched = tables.stream().filter(t -> !ALTERED.contains(t)).toList();
        Map<String, String> before = fingerprints(untouched, true);
        String brtBefore = queryString(BRT_ROWS);

        migrateTo("16");

        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '16' AND success = 1")).isEqualTo(1);
        assertThat(businessTables()).containsExactlyElementsOf(tables);
        assertThat(fingerprints(untouched, true)).isEqualTo(before);
        assertThat(queryString(BRT_ROWS)).isEqualTo(brtBefore);
        assertThat(queryLong("SELECT count(*) FROM batch_risk_transition WHERE source_recall_id IS NOT NULL")).isZero();
        assertThat(queryLong("SELECT count(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'recall' "
                + "AND column_name IN ('severity', 'policy_version', 'notification_summary', 'is_deleted')")).as("V1 placeholder columns are gone")
                .isZero();

        // 1. recall 生命周期
        recall(5001, "RCL-V16-1", "k-v16-rc-000001");
        recall(5002, "RCL-V16-2", "k-v16-rc-000002");
        assertBad("uk_recall_no", () -> recall(5003, "RCL-V16-1", "k-v16-rc-000003"));
        assertBad("uk_recall_org_idempotency", () -> recall(5004, "RCL-V16-4", "k-v16-rc-000001"));
        assertBad("chk_recall_reason", () -> exec("UPDATE recall SET reason = '  ' WHERE id = 5001"));
        assertBad("chk_recall_lifecycle_shape", () -> exec("UPDATE recall SET status = 'CLOSED', closed_at = '2026-09-23 00:00:00', "
                + "closed_by = 21 WHERE id = 5001"));
        assertBad("chk_recall_lifecycle_shape", () -> exec("UPDATE recall SET status = 'CLOSED', closed_at = '2026-09-23 00:00:00', closed_by = 21, "
                + "public_disposition = 'BURNED', result_summary = '处置', close_idempotency_key = 'k-v16-close-0001', close_request_hash = REPEAT('d', 64) "
                + "WHERE id = 5001"));
        assertBad("chk_recall_lifecycle_shape", () -> exec("UPDATE recall SET public_disposition = 'DESTROYED' WHERE id = 5001"));
        assertThatThrownBy(() -> exec("UPDATE recall SET status = 'CANCELLED' WHERE id = 5001")).isInstanceOf(SQLException.class)
                .hasMessageMatching(".*chk_recall_(status|lifecycle_shape).*");

        // 2. RECALLED 只能来自 RECALL 来源
        transition(901, "FROZEN", "RECALLED", "RECALL", "5001", "NULL", "21", "SYS:RECALL:5001:BATCH:901");
        long recall901 = queryLong("SELECT id FROM batch_risk_transition WHERE source_recall_id = 5001 AND batch_id = 901");
        transition(902, "NORMAL", "RECALLED", "RECALL", "5002", "NULL", "22", "SYS:RECALL:5002:BATCH:902");
        assertBad("chk_brt_source_shape", () -> transition(903, "NORMAL", "RECALLED", "RECALL", "5001", "NULL", "NULL", "k-v16-t-00000001"));
        assertBad("chk_brt_source_shape", () -> transition(903, "NORMAL", "RECALLED", "MANUAL", "NULL", "NULL", "21", "k-v16-t-00000002"));
        assertBad("chk_brt_source_shape", () -> transition(903, "NORMAL", "FROZEN", "RECALL", "5001", "NULL", "21", "k-v16-t-00000003"));
        assertBad("chk_brt_source_shape", () -> transition(903, "NORMAL", "FROZEN", "MANUAL", "5001", "NULL", "21", "k-v16-t-00000004"));
        assertBad("chk_brt_transition", () -> transition(903, "RECALLED", "NORMAL", "MANUAL", "NULL", "NULL", "21", "k-v16-t-00000005"));
        assertBad("fk_brt_source_recall", () -> transition(903, "NORMAL", "RECALLED", "RECALL", "999999", "NULL", "21", "k-v16-t-00000006"));

        // 3. recall_batch 形状
        scope(5001, 901, "SEED", 0, "FROZEN", "RECALLED", String.valueOf(recall901), "600.000", "NULL", "NULL");
        scope(5001, 903, "ANCESTOR", -1, "NORMAL", "TRACE_ONLY", "NULL", "100.000", "NULL", "NULL");
        assertBad("fk_recall_batch_transition", () -> scope(5002, 901, "SEED", 0, "FROZEN", "RECALLED", String.valueOf(recall901), "600.000",
                "NULL", "NULL"));
        assertBad("chk_recall_batch_role_action", () -> scope(5002, 903, "SEED", 0, "NORMAL", "NOTIFY_HOLDER", "NULL", "100.000", "NULL", "NULL"));
        assertBad("chk_recall_batch_role_action", () -> scope(5002, 903, "DESCENDANT", 0, "NORMAL", "NOTIFY_HOLDER", "NULL", "100.000", "NULL", "NULL"));
        assertBad("chk_recall_batch_role_action", () -> scope(5002, 903, "ANCESTOR", -1, "NORMAL", "NOTIFY_HOLDER", "NULL", "100.000", "NULL", "NULL"));
        assertBad("chk_recall_batch_transition_shape", () -> scope(5002, 903, "DESCENDANT", 1, "NORMAL", "RECALLED", "NULL", "100.000", "NULL", "NULL"));
        assertBad("chk_recall_batch_transition_shape", () -> scope(5002, 903, "DESCENDANT", 1, "NORMAL", "ALREADY_RECALLED", "NULL", "100.000",
                "NULL", "NULL"));
        assertBad("chk_recall_batch_quantities", () -> scope(5002, 903, "DESCENDANT", 1, "NORMAL", "NOTIFY_HOLDER", "NULL", "700.000", "NULL", "NULL"));
        assertBad("chk_recall_batch_transfer_shape", () -> scope(5002, 903, "DESCENDANT", 1, "NORMAL", "NOTIFY_HOLDER", "NULL", "100.000", "NULL",
                "'ACCEPTED'"));
        assertBad("uk_recall_batch", () -> scope(5001, 901, "DESCENDANT", 1, "RECALLED", "ALREADY_RECALLED", "NULL", "600.000", "NULL", "NULL"));

        // 4. 关闭形状（受控公开处置结论 + 内部总结 + 关闭幂等键）
        exec("UPDATE recall SET status = 'CLOSED', closed_at = '2026-09-23 00:00:00', closed_by = 21, public_disposition = 'DESTROYED', "
                + "result_summary = '已按演练流程销毁', close_idempotency_key = 'k-v16-close-0001', close_request_hash = REPEAT('d', 64) WHERE id = 5001");
        assertThat(queryString("SELECT status FROM recall WHERE id = 5001")).isEqualTo("CLOSED");
        assertBad("chk_recall_lifecycle_shape", () -> exec("UPDATE recall SET closed_at = '2026-09-21 00:00:00' WHERE id = 5001"));
    }

    @Test
    @DisplayName("fail-fast：V15 状态下 V1 占位表 recall 已有存量行时，V16 在任何 DDL 之前失败、不被记录为成功，且不改动任何表")
    void upgrade_failsFastWhenRecallPlaceholderNotEmpty() throws Exception {
        migrateTo("15");
        seedV15Facts();
        exec("INSERT INTO recall (recall_no, owner_org_id, reason, started_at) VALUES ('LEGACY-RC', 730, '历史', '2026-09-21 00:00:00')");
        List<String> tables = businessTables();
        Map<String, String> before = fingerprints(tables, true);

        assertThatThrownBy(() -> migrateTo("16")).isInstanceOf(FlywayException.class).hasStackTraceContaining("passed");

        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '16' AND success = 1")).isZero();
        assertThat(fingerprints(tables, true)).as("no DDL applied before the precondition failed").isEqualTo(before);
    }
}
