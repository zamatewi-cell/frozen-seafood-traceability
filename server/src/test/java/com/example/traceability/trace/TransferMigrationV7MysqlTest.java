package com.example.traceability.trace;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 8.4 升级迁移测试 (Issue #21 历史数据兼容性验证)。
 * <p>
 * 验证目标：
 * 1. 在完全隔离的临时 schema 中，先执行 V1~V6 迁移；
 * 2. 写入同一批次的两条合法旧 PENDING 交接，覆盖 V1-V6 尚无未结束交接唯一约束的历史形态；
 * 3. 执行 V7 迁移脚本，断言迁移成功；
 * 4. 断言历史数据的业务字段（单号、数量、单位、发货时间、状态等）原样保留且未篡改，未伪造操作人，仅被标记内部 is_legacy=1；
 * 5. 验证历史行不参与新唯一索引，同时新行严格受未结束交接唯一约束与生命周期 CHECK 保护；
 * 6. 测试结束后严格执行 DROP SCHEMA 物理删除临时 schema，绝不污染或影响主库 seafood_trace。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("V7 数据库迁移真实 MySQL 8.4 历史兼容性测试")
class TransferMigrationV7MysqlTest {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("V7迁移测试：含合法旧V1~V6交接行的隔离临时schema升级成功，保留历史原值且不伪造审计人")
    void testV7Migration_withLegacyPendingTransfers_migratesSuccessfullyAndPreservesHistory() throws Exception {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;

        String originalJdbcUrl = hikari.getJdbcUrl();

        // 获取管理员凭据以独立建库，避免给业务账号全局 CREATE/DROP 权限；不输出密码日志
        String rootUsername = System.getenv("DB_ROOT_USERNAME");
        if (rootUsername == null || rootUsername.isBlank()) {
            rootUsername = System.getProperty("db.root.username", "root");
        }
        if (rootUsername == null || rootUsername.isBlank()) {
            rootUsername = "root";
        }

        String rootPassword = System.getenv("DB_ROOT_PASSWORD");
        if (rootPassword == null) {
            rootPassword = System.getProperty("db.root.password", "");
        }

        // 1. 生成唯一临时 schema 名称 (例如 trace_mig_test_a1b2c3d4)
        String tempSchema = "trace_mig_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // 构造指向临时 schema 的 JDBC URL
        String tempJdbcUrl = originalJdbcUrl.replaceFirst("/[a-zA-Z0-9_]+(\\?|$)", "/" + tempSchema + "$1");

        try (Connection rootConn = DriverManager.getConnection(originalJdbcUrl, rootUsername, rootPassword);
             Statement rootStmt = rootConn.createStatement()) {

            // 2. 创建隔离临时 schema
            rootStmt.execute("CREATE SCHEMA `" + tempSchema + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");

            try {
                // 3. 运行 Flyway V1~V6 迁移 (使用管理员连接隔离执行)
                Flyway flywayV1toV6 = Flyway.configure()
                        .dataSource(tempJdbcUrl, rootUsername, rootPassword)
                        .locations("classpath:db/migration")
                        .target(MigrationVersion.fromVersion("6"))
                        .load();
                flywayV1toV6.migrate();

                // 4. V1~V6 尚无未结束交接唯一约束，插入同批次的两条历史合法 PENDING 记录
                try (Connection tempConn = DriverManager.getConnection(tempJdbcUrl, rootUsername, rootPassword);
                     Statement tempStmt = tempConn.createStatement()) {
                    tempStmt.execute("""
                            INSERT INTO `transfer` (
                                `transfer_no`, `batch_id`, `sender_org_id`, `receiver_org_id`,
                                `quantity`, `unit_code`, `shipped_at`, `status`, `idempotency_key`,
                                `version`, `is_deleted`
                            ) VALUES
                            (
                                'TRF-LEGACY-001', 99901, 101, 102,
                                500.500, 'kg', '2026-09-01 10:00:00.000000', 'PENDING', 'legacy-key-64-bit-sample-1',
                                0, 0
                            ),
                            (
                                'TRF-LEGACY-002', 99901, 101, 102,
                                500.500, 'kg', '2026-09-02 10:00:00.000000', 'PENDING', 'legacy-key-64-bit-sample-2',
                                0, 0
                            )
                            """);
                }

                // 5. 运行 Flyway 升级到 V7
                Flyway flywayV7 = Flyway.configure()
                        .dataSource(tempJdbcUrl, rootUsername, rootPassword)
                        .locations("classpath:db/migration")
                        .target(MigrationVersion.fromVersion("7"))
                        .load();
                int migrated = flywayV7.migrate().migrationsExecuted;
                assertThat(migrated).isGreaterThanOrEqualTo(1);

                // 6. 验证两条同批次历史记录均原样保留，且不参与 V7 后新数据唯一索引
                try (Connection tempConn = DriverManager.getConnection(tempJdbcUrl, rootUsername, rootPassword);
                     PreparedStatement queryStmt = tempConn.prepareStatement(
                             "SELECT * FROM `transfer` WHERE `batch_id` = ? ORDER BY `transfer_no`")) {
                    queryStmt.setLong(1, 99901L);
                    try (ResultSet rs = queryStmt.executeQuery()) {
                        for (int sequence = 1; sequence <= 2; sequence++) {
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getString("transfer_no")).isEqualTo("TRF-LEGACY-00" + sequence);
                            assertThat(rs.getLong("batch_id")).isEqualTo(99901L);
                            assertThat(rs.getLong("sender_org_id")).isEqualTo(101L);
                            assertThat(rs.getLong("receiver_org_id")).isEqualTo(102L);
                            assertThat(rs.getBigDecimal("quantity")).isEqualByComparingTo(new BigDecimal("500.500"));
                            assertThat(rs.getString("unit_code")).isEqualTo("kg");
                            assertThat(rs.getString("status")).isEqualTo("PENDING");
                            assertThat(rs.getString("idempotency_key")).isEqualTo("legacy-key-64-bit-sample-" + sequence);
                            assertThat(rs.getTimestamp("shipped_at")).isNotNull();
                            assertThat(rs.getObject("submitted_recorded_at")).isNull();
                            assertThat(rs.getObject("submitted_by")).isNull();
                            assertThat(rs.getObject("decision_recorded_at")).isNull();
                            assertThat(rs.getObject("decided_by")).isNull();
                            assertThat(rs.getInt("is_legacy")).isEqualTo(1);
                            assertThat(rs.getObject("open_batch_id")).isNull();
                        }
                        assertThat(rs.next()).isFalse();
                    }

                    // 7. 验证 V7 后新行严格遵循未结束交接唯一约束与生命周期 shape CHECK
                    try (Statement stmt = tempConn.createStatement()) {
                        // 7.1 新插入符合 DRAFT 规范的数据 (is_legacy 默认 0)，成功
                        stmt.execute("""
                                INSERT INTO `transfer` (
                                    `transfer_no`, `batch_id`, `sender_org_id`, `receiver_org_id`,
                                    `quantity`, `unit_code`, `status`, `idempotency_key`
                                ) VALUES (
                                    'TRF-NEW-DRAFT-001', 99902, 101, 102,
                                    100.000, 'kg', 'DRAFT', 'new-draft-key-001'
                                )
                                """);

                        // 7.2 同一批次的第 2 条新 DRAFT 必须被物理唯一约束拒绝
                        assertThatThrownBy(() -> stmt.execute("""
                                INSERT INTO `transfer` (
                                    `transfer_no`, `batch_id`, `sender_org_id`, `receiver_org_id`,
                                    `quantity`, `unit_code`, `status`, `idempotency_key`
                                ) VALUES (
                                    'TRF-NEW-DRAFT-002', 99902, 101, 102,
                                    100.000, 'kg', 'DRAFT', 'new-draft-key-002'
                                )
                                """))
                                .isInstanceOf(SQLException.class)
                                .hasMessageContaining("uk_transfer_open_batch");

                        // 7.3 新插入违反 PENDING 规范的数据，预期触发 CHECK 失败
                        assertThatThrownBy(() -> stmt.execute("""
                                INSERT INTO `transfer` (
                                    `transfer_no`, `batch_id`, `sender_org_id`, `receiver_org_id`,
                                    `quantity`, `unit_code`, `shipped_at`, `status`, `idempotency_key`
                                ) VALUES (
                                    'TRF-NEW-INVALID-002', 99903, 101, 102,
                                    100.000, 'kg', NOW(6), 'PENDING', 'new-invalid-key-002'
                                )
                                """))
                                .isInstanceOf(SQLException.class)
                                .hasMessageContaining("chk_transfer_decision_shape");
                    }
                }
            } finally {
                // 8. 测试结束物理删除隔离临时 schema，保证主库绝对不受任何影响
                rootStmt.execute("DROP SCHEMA IF EXISTS `" + tempSchema + "`");
            }
        }
    }
}
