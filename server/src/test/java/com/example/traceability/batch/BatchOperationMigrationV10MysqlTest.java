package com.example.traceability.batch;

import com.example.traceability.common.config.TraceBatchNoHistoryPepperFlywayCallback;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V10（批次操作全量消耗与输出草稿协议）真实 MySQL 8.4 升级迁移测试。
 * <p>
 * 在隔离临时 schema 中先迁移到 V9、写入历史数据，再升级到 V10，验证：
 * <ol>
 *   <li>已提交的历史批次操作、明细与谱系边原样保留；历史批次 produced_by / consumed_by 保持 NULL（不回填、不伪造）；</li>
 *   <li>存在未删除的 DRAFT 批次操作时 V10 fail-fast（旧协议草稿无法在新协议下提交）；</li>
 *   <li>历史数据违反新物理约束（例如 LOSS 引用批次）时迁移自然失败，不替人工修正；</li>
 *   <li>V10 之后数据库强制：OUTPUT 唯一产出、角色与批次引用形状、被操作消耗必须 CLOSED、禁止重复上游边、外键。</li>
 * </ol>
 * </p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("V10 数据库迁移真实 MySQL 8.4 测试（批次操作全量消耗与输出草稿协议）")
class BatchOperationMigrationV10MysqlTest {

    private static final String TEST_PEPPER = "v10-migration-test-pepper-dddddddddddddddd";

    @Autowired
    private DataSource dataSource;

    private String rootUser;
    private String rootPassword;
    private String baseUrl;
    private String schema;
    private String url;

    @BeforeEach
    void createSchema() throws SQLException {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        baseUrl = hikari.getJdbcUrl();
        rootUser = System.getenv().getOrDefault("DB_ROOT_USERNAME", "root");
        if (rootUser.isBlank()) {
            rootUser = "root";
        }
        rootPassword = System.getenv().getOrDefault("DB_ROOT_PASSWORD", "");
        schema = "trace_mig_v10_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        url = baseUrl.replaceFirst("/[a-zA-Z0-9_]+(\\?|$)", "/" + schema + "$1");
        try (Connection c = DriverManager.getConnection(baseUrl, rootUser, rootPassword); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA `" + schema + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    @AfterEach
    void dropSchema() throws SQLException {
        try (Connection c = DriverManager.getConnection(baseUrl, rootUser, rootPassword); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS `" + schema + "`");
        }
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(url, rootUser, rootPassword)
                .locations("classpath:db/migration")
                .callbacks(new TraceBatchNoHistoryPepperFlywayCallback(TEST_PEPPER))
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, rootUser, rootPassword); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, rootUser, rootPassword);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    /**
     * 写入 V9 状态下的历史数据：加工组织、产品、三个批次，以及一张已提交的历史部分消耗 PROCESS（B1 投入 400 / 1000 → B2 390 + LOSS 10）。
     */
    private void seedV9History() throws SQLException {
        exec("INSERT INTO organization (id, org_no, name, org_type, status) VALUES (301, 'V10_PRC', '加工', 'PROCESSOR', 'ACTIVE')");
        exec("""
                INSERT INTO product (id, product_code, public_name, category, specification, source_type, base_unit_code, status)
                VALUES (401, 'V10_PRD', '冷冻大黄鱼', 'FISH', '500g/条', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE')
                """);
        exec("""
                INSERT INTO batch (id, org_id, creation_org_id, product_id, trace_batch_no, batch_type, quantity, unit_code,
                    origin_type, origin_text, flow_status, risk_status)
                VALUES
                (501, 301, 301, 401, 'TB-V10-501', 'SOURCE', 1000.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL'),
                (502, 301, 301, 401, 'TB-V10-502', 'PROCESSING', 390.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL'),
                (503, 301, 301, 401, 'TB-V10-503', 'SOURCE', 50.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL')
                """);
        exec("""
                INSERT INTO batch_operation (id, org_id, operation_no, operation_type, occurred_at, status, idempotency_key, version)
                VALUES (601, 301, 'OP-V10-HIST', 'PROCESS', '2026-09-01 10:00:00', 'SUBMITTED', 'v10-hist-key-0001', 1)
                """);
        exec("""
                INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity) VALUES
                (601, 501, 'INPUT', 400.000, 'kg', 400.000),
                (601, 502, 'OUTPUT', 390.000, 'kg', 390.000),
                (601, NULL, 'LOSS', 10.000, 'kg', 10.000)
                """);
        exec("INSERT INTO batch_relation (operation_id, parent_batch_id, child_batch_id, relation_type) VALUES (601, 501, 502, 'TRANSFORM')");
    }

    @Test
    @DisplayName("升级成功：历史已提交操作、明细与谱系边原样保留，produced_by / consumed_by 不回填；V10 后新物理约束生效")
    void upgrade_preservesHistoryAndEnforcesNewConstraints() throws Exception {
        migrateTo("9");
        seedV9History();

        migrateTo("10");

        assertThat(queryLong("SELECT count(*) FROM batch_operation WHERE id = 601 AND status = 'SUBMITTED'")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM batch_operation_item WHERE operation_id = 601")).isEqualTo(3);
        assertThat(queryLong("SELECT count(*) FROM batch_relation WHERE operation_id = 601")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM batch WHERE produced_by_operation_id IS NOT NULL OR consumed_by_operation_id IS NOT NULL")).isZero();
        assertThat(queryLong("SELECT count(*) FROM batch WHERE id = 501 AND flow_status = 'ACTIVE' AND quantity = 1000.000")).isEqualTo(1);
        // 虚拟生成列只反映未删除的 OUTPUT
        assertThat(queryLong("SELECT output_batch_id FROM batch_operation_item WHERE operation_id = 601 AND role = 'OUTPUT'")).isEqualTo(502);
        assertThat(queryLong("SELECT count(*) FROM batch_operation_item WHERE operation_id = 601 AND role <> 'OUTPUT' AND output_batch_id IS NOT NULL")).isZero();

        exec("""
                INSERT INTO batch_operation (id, org_id, operation_no, operation_type, occurred_at, status, idempotency_key)
                VALUES (602, 301, 'OP-V10-NEW', 'SPLIT', '2026-09-02 10:00:00', 'DRAFT', 'v10-new-key-00001')
                """);
        // 同一批次不能成为两个未删除操作的 OUTPUT
        assertThatThrownBy(() -> exec("INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity) VALUES (602, 502, 'OUTPUT', 390, 'kg', 390)"))
                .isInstanceOf(SQLException.class).hasMessageContaining("uk_item_output_batch");
        // 逻辑删除后释放唯一产出占用
        exec("UPDATE batch_operation_item SET is_deleted = 1 WHERE operation_id = 601 AND role = 'OUTPUT'");
        exec("INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity) VALUES (602, 502, 'OUTPUT', 390, 'kg', 390)");
        // 同一操作内同一批次只出现一次
        assertThatThrownBy(() -> exec("INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity) VALUES (602, 502, 'INPUT', 390, 'kg', 390)"))
                .isInstanceOf(SQLException.class).hasMessageContaining("uk_item_op_batch");
        // 角色与批次引用形状
        assertThatThrownBy(() -> exec("INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity) VALUES (602, 503, 'SAMPLE', 1, 'kg', 1)"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_item_batch_shape");
        assertThatThrownBy(() -> exec("INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity) VALUES (602, NULL, 'INPUT', 1, 'kg', 1)"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_item_batch_shape");
        // 被操作全量消耗的批次必须 CLOSED
        assertThatThrownBy(() -> exec("UPDATE batch SET consumed_by_operation_id = 601 WHERE id = 503"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_batch_consumed_closed");
        exec("UPDATE batch SET flow_status = 'CLOSED', consumed_by_operation_id = 601 WHERE id = 503");
        // 外键
        assertThatThrownBy(() -> exec("UPDATE batch SET produced_by_operation_id = 999999 WHERE id = 502"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_batch_produced_by_operation");
        assertThatThrownBy(() -> exec("INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity) VALUES (602, 999999, 'INPUT', 1, 'kg', 1)"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_item_batch");
        // 禁止重复上游边（即便来自不同操作）
        assertThatThrownBy(() -> exec("INSERT INTO batch_relation (operation_id, parent_batch_id, child_batch_id, relation_type) VALUES (602, 501, 502, 'SPLIT')"))
                .isInstanceOf(SQLException.class).hasMessageContaining("uk_relation_parent_child");
        assertThatThrownBy(() -> exec("INSERT INTO batch_relation (operation_id, parent_batch_id, child_batch_id, relation_type) VALUES (602, 501, 999999, 'SPLIT')"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_relation_child_batch");
    }

    @Test
    @DisplayName("fail-fast：存在未删除的 DRAFT 批次操作（旧协议草稿）时 V10 失败，数据原样保留")
    void upgrade_failsWhenLegacyDraftOperationExists() throws Exception {
        migrateTo("9");
        seedV9History();
        exec("""
                INSERT INTO batch_operation (id, org_id, operation_no, operation_type, occurred_at, status, idempotency_key)
                VALUES (603, 301, 'OP-V10-DRAFT', 'PROCESS', '2026-09-03 10:00:00', 'DRAFT', 'v10-draft-key-0001')
                """);

        assertThatThrownBy(() -> migrateTo("10")).isInstanceOf(FlywayException.class);
        assertThat(queryLong("SELECT count(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'batch' AND column_name = 'produced_by_operation_id'")).isZero();
        assertThat(queryLong("SELECT count(*) FROM batch_operation WHERE id = 603 AND status = 'DRAFT'")).isEqualTo(1);
    }

    @Test
    @DisplayName("已逻辑删除的 DRAFT 批次操作不阻断 V10")
    void upgrade_ignoresDeletedDraftOperation() throws Exception {
        migrateTo("9");
        seedV9History();
        exec("""
                INSERT INTO batch_operation (id, org_id, operation_no, operation_type, occurred_at, status, idempotency_key, is_deleted)
                VALUES (604, 301, 'OP-V10-DEL', 'PROCESS', '2026-09-03 10:00:00', 'DRAFT', 'v10-del-key-00001', 1)
                """);

        migrateTo("10");
        assertThat(queryLong("SELECT count(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'batch' AND column_name = 'consumed_by_operation_id'")).isEqualTo(1);
    }

    @Test
    @DisplayName("历史数据违反新约束（LOSS 引用了批次）时迁移自然失败，不替人工修正")
    void upgrade_failsOnHistoricalShapeViolation() throws Exception {
        migrateTo("9");
        seedV9History();
        exec("INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity) VALUES (601, 503, 'WASTE', 1.000, 'kg', 1.000)");

        assertThatThrownBy(() -> migrateTo("10")).isInstanceOf(FlywayException.class);
        assertThat(queryLong("SELECT count(*) FROM batch_operation_item WHERE operation_id = 601 AND role = 'WASTE' AND batch_id = 503")).isEqualTo(1);
    }
}
