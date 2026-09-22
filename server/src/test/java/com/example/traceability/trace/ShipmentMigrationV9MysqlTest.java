package com.example.traceability.trace;

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
 * V9 (Shipment 生命周期与 Transfer 绑定) 真实 MySQL 8.4 升级迁移测试。
 * <p>
 * 在隔离临时 schema 中先迁移到 V8、写入历史数据，再升级到 V9，验证：
 * <ol>
 *   <li>无 Shipment 的历史终态交接被归入 Shipment 契约前历史 (is_legacy = 1)，业务字段原样保留；DRAFT 保持新规数据；</li>
 *   <li>存在任何未绑定运输任务的 PENDING 交接（含 legacy）或存量 shipment 行时 V9 fail-fast，不伪造任何业务事实；</li>
 *   <li>V9 之后 shipment 状态 / 生命周期形状 CHECK、transfer 提交后必须绑定 Shipment、同 Shipment 同 Batch 唯一、
 *       同 Shipment 同发送 / 接收方复合外键均由数据库强制；</li>
 *   <li>承运组织与发送 / 接收方是否相同不作为数据库永久约束（Demo MVP Phase A 仅在应用层要求独立 CARRIER 组织）。</li>
 * </ol>
 * </p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("V9 数据库迁移真实 MySQL 8.4 测试（Shipment 生命周期与 Transfer 绑定）")
class ShipmentMigrationV9MysqlTest {

    private static final String TEST_PEPPER = "v9-migration-test-pepper-cccccccccccccccc";

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
        schema = "trace_mig_v9_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private void seedOrganizationsAndSites() throws SQLException {
        exec("""
                INSERT INTO organization (id, org_no, name, org_type, status) VALUES
                (101, 'V9_SRC', '来源', 'SOURCE', 'ACTIVE'),
                (102, 'V9_PRC', '加工', 'PROCESSOR', 'ACTIVE'),
                (103, 'V9_CAR', '承运', 'CARRIER', 'ACTIVE'),
                (104, 'V9_PRC2', '加工二', 'PROCESSOR', 'ACTIVE')
                """);
        exec("""
                INSERT INTO site (id, org_id, site_no, name, site_type) VALUES
                (201, 101, 'S1', '来源码头', 'PORT'),
                (202, 102, 'S2', '加工厂', 'FACTORY'),
                (204, 104, 'S4', '加工二厂', 'FACTORY')
                """);
    }

    @Test
    @DisplayName("升级成功：历史终态交接归入 legacy 且原值保留；V9 后数据库强制 Shipment 生命周期与绑定约束")
    void upgrade_preservesHistoryAndEnforcesNewConstraints() throws Exception {
        migrateTo("8");
        seedOrganizationsAndSites();
        // V7 新规历史：已 ACCEPTED 但从未绑定 Shipment 的交接 + 一张 DRAFT
        exec("""
                INSERT INTO transfer (transfer_no, batch_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    shipped_at, submitted_recorded_at, submitted_by, received_at, decision_recorded_at, decided_by,
                    received_quantity, status, idempotency_key)
                VALUES ('TRF-HIST-ACC', 9001, 101, 102, 1000.000, 'kg',
                    '2026-09-01 10:00:00', '2026-09-01 10:00:01', 1, '2026-09-02 10:00:00', '2026-09-02 10:00:01', 2,
                    1000.000, 'ACCEPTED', 'hist-accepted-key-0001')
                """);
        exec("""
                INSERT INTO transfer (transfer_no, batch_id, sender_org_id, receiver_org_id, quantity, unit_code, status, idempotency_key)
                VALUES ('TRF-HIST-DRAFT', 9002, 101, 102, 500.000, 'kg', 'DRAFT', 'hist-draft-key-000001')
                """);

        migrateTo("9");

        assertThat(queryLong("SELECT is_legacy FROM transfer WHERE transfer_no = 'TRF-HIST-ACC'")).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM transfer WHERE transfer_no = 'TRF-HIST-ACC' AND status = 'ACCEPTED' "
                + "AND shipped_at IS NOT NULL AND received_quantity = 1000.000 AND shipment_id IS NULL")).isEqualTo(1);
        assertThat(queryLong("SELECT is_legacy FROM transfer WHERE transfer_no = 'TRF-HIST-DRAFT'")).isZero();
        assertThat(queryLong("SELECT open_batch_id FROM transfer WHERE transfer_no = 'TRF-HIST-DRAFT'")).isEqualTo(9002);

        // 合法 PLANNED 运输任务：loaded_at 为空
        exec("""
                INSERT INTO shipment (id, shipment_no, sender_org_id, receiver_org_id, carrier_org_id, vehicle_or_container_no,
                    origin_site_id, destination_site_id)
                VALUES (301, 'SHP-V9-1', 101, 102, 103, '浙A-1', 201, 202)
                """);
        assertThat(queryLong("SELECT COUNT(*) FROM shipment WHERE id = 301 AND status = 'PLANNED' AND loaded_at IS NULL")).isEqualTo(1);

        // 状态值域 / 生命周期形状
        assertThatThrownBy(() -> exec("UPDATE shipment SET status = 'LOST' WHERE id = 301"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_shipment_");
        assertThat(queryLong("SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() "
                + "AND constraint_name = 'chk_shipment_status' AND check_clause LIKE '%PLANNED%IN_TRANSIT%DELIVERED%CANCELLED%'")).isEqualTo(1);
        assertThatThrownBy(() -> exec("UPDATE shipment SET loaded_at = NOW(6) WHERE id = 301"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_shipment_lifecycle_shape");
        assertThatThrownBy(() -> exec("UPDATE shipment SET status = 'IN_TRANSIT' WHERE id = 301"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_shipment_lifecycle_shape");
        exec("UPDATE shipment SET status = 'IN_TRANSIT', loaded_at = '2026-09-03 08:00:00', dispatched_recorded_at = NOW(6), dispatched_by = 3 WHERE id = 301");
        assertThatThrownBy(() -> exec("UPDATE shipment SET status = 'DELIVERED', unloaded_at = '2026-09-03 07:00:00', delivered_recorded_at = NOW(6), delivered_by = 3 WHERE id = 301"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_shipment_lifecycle_shape");

        // 发送方与接收方必须不同
        assertThatThrownBy(() -> exec("""
                INSERT INTO shipment (shipment_no, sender_org_id, receiver_org_id, carrier_org_id, vehicle_or_container_no, origin_site_id, destination_site_id)
                VALUES ('SHP-V9-SAME', 101, 101, 103, '浙A-2', 201, 202)
                """)).isInstanceOf(SQLException.class).hasMessageContaining("chk_shipment_parties");

        // 承运组织与发送方相同不是数据库永久约束（Phase A 限制只在应用层执行）
        exec("""
                INSERT INTO shipment (id, shipment_no, sender_org_id, receiver_org_id, carrier_org_id, vehicle_or_container_no, origin_site_id, destination_site_id)
                VALUES (302, 'SHP-V9-SELF', 101, 102, 101, '浙A-3', 201, 202)
                """);

        // 新规 PENDING 交接必须绑定运输任务，且不再写入 shipped_at
        assertThatThrownBy(() -> exec("""
                INSERT INTO transfer (transfer_no, batch_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    submitted_recorded_at, submitted_by, status, idempotency_key)
                VALUES ('TRF-NEW-UNBOUND', 9003, 101, 102, 100.000, 'kg', NOW(6), 1, 'PENDING', 'new-unbound-key-00001')
                """)).isInstanceOf(SQLException.class).hasMessageContaining("chk_transfer_decision_shape");
        exec("""
                INSERT INTO transfer (transfer_no, batch_id, shipment_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    submitted_recorded_at, submitted_by, status, idempotency_key)
                VALUES ('TRF-NEW-BOUND', 9004, 302, 101, 102, 100.000, 'kg', NOW(6), 1, 'PENDING', 'new-bound-key-000001')
                """);

        // 同一运输任务中同一批次只能出现一次（历史 legacy 行同样受约束）
        assertThatThrownBy(() -> exec("""
                INSERT INTO transfer (transfer_no, batch_id, shipment_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    status, idempotency_key, is_legacy)
                VALUES ('TRF-NEW-DUP', 9004, 302, 101, 102, 100.000, 'kg', 'REJECTED', 'new-dup-key-0000001', 1)
                """)).isInstanceOf(SQLException.class).hasMessageContaining("uk_transfer_shipment_batch");

        // 同一运输任务内交接必须与运输任务发送 / 接收方一致（复合外键）
        assertThatThrownBy(() -> exec("""
                INSERT INTO transfer (transfer_no, batch_id, shipment_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    status, idempotency_key)
                VALUES ('TRF-NEW-MIX', 9005, 302, 101, 104, 100.000, 'kg', 'DRAFT', 'new-mix-key-0000001')
                """)).isInstanceOf(SQLException.class).hasMessageContaining("fk_transfer_shipment_parties");

        // 运输任务的组织与场所必须真实存在
        assertThatThrownBy(() -> exec("""
                INSERT INTO shipment (shipment_no, sender_org_id, receiver_org_id, carrier_org_id, vehicle_or_container_no, origin_site_id, destination_site_id)
                VALUES ('SHP-V9-FK', 101, 102, 103, '浙A-4', 201, 999)
                """)).isInstanceOf(SQLException.class).hasMessageContaining("fk_shipment_destination_site");

        assertThat(queryLong("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'shipment_idempotency'")).isEqualTo(1);
    }

    @Test
    @DisplayName("前置条件：存在未绑定运输任务的新规 PENDING 交接时 V9 fail-fast，schema 停留在 V8")
    void precondition_unboundPendingTransfer_failsFast() throws Exception {
        migrateTo("8");
        exec("""
                INSERT INTO transfer (transfer_no, batch_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    shipped_at, submitted_recorded_at, submitted_by, status, idempotency_key)
                VALUES ('TRF-OPEN-PENDING', 9101, 101, 102, 100.000, 'kg', NOW(6), NOW(6), 1, 'PENDING', 'open-pending-key-0001')
                """);

        assertThatThrownBy(() -> migrateTo("9")).isInstanceOf(FlywayException.class);
        assertThat(queryLong("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'shipment' AND column_name = 'sender_org_id'")).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM transfer WHERE transfer_no = 'TRF-OPEN-PENDING' AND status = 'PENDING'")).isEqualTo(1);
    }

    @Test
    @DisplayName("前置条件：V7 之前的 legacy PENDING 交接同样使 V9 fail-fast（否则将永远无法决断并持续阻断批次操作）")
    void precondition_legacyPendingTransfer_failsFast() throws Exception {
        migrateTo("8");
        exec("""
                INSERT INTO transfer (transfer_no, batch_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    shipped_at, status, idempotency_key, is_legacy)
                VALUES ('TRF-LEGACY-PENDING', 9201, 101, 102, 100.000, 'kg', NOW(6), 'PENDING', 'legacy-pending-key-0001', 1)
                """);

        assertThatThrownBy(() -> migrateTo("9")).isInstanceOf(FlywayException.class);
        assertThat(queryLong("SELECT COUNT(*) FROM transfer WHERE transfer_no = 'TRF-LEGACY-PENDING' AND status = 'PENDING' AND is_legacy = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("前置条件：shipment 表存在手工存量行时 V9 fail-fast，不推断缺失的发送 / 接收组织")
    void precondition_existingShipmentRows_failsFast() throws Exception {
        migrateTo("8");
        exec("""
                INSERT INTO shipment (shipment_no, carrier_org_id, vehicle_or_container_no, origin_site_id, destination_site_id, loaded_at)
                VALUES ('SHP-LEGACY-1', 103, '浙A-9', 201, 202, NOW(6))
                """);

        assertThatThrownBy(() -> migrateTo("9")).isInstanceOf(FlywayException.class);
        assertThat(queryLong("SELECT COUNT(*) FROM shipment WHERE shipment_no = 'SHP-LEGACY-1'")).isEqualTo(1);
    }
}
