package com.example.traceability.common;

import com.example.traceability.common.config.TraceBatchNoHistoryPepperFlywayCallback;
import com.zaxxer.hikari.HikariDataSource;
import org.assertj.core.api.ThrowableAssert;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase B 起的原地升级迁移测试共享夹具（真实 MySQL 8.4）：每个测试用例在隔离临时 schema 中迁移到指定版本、写入历史事实、
 * 再原地升级，比较逐表结构与内容校验和。管理连接使用 DB_ROOT_USERNAME / DB_ROOT_PASSWORD（与既有迁移测试一致）。
 */
public abstract class AbstractFlywayUpgradeMysqlTest {

    @Autowired
    private DataSource dataSource;

    private String rootUser;
    private String rootPassword;
    private String baseUrl;
    private String url;
    protected String schema;

    /** 迁移测试专用的历史批次号 pepper。 */
    protected abstract String pepper();

    @BeforeEach
    void createUpgradeSchema() throws SQLException {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        baseUrl = hikari.getJdbcUrl();
        rootUser = System.getenv().getOrDefault("DB_ROOT_USERNAME", "root");
        if (rootUser.isBlank()) {
            rootUser = "root";
        }
        rootPassword = System.getenv().getOrDefault("DB_ROOT_PASSWORD", "");
        schema = "trace_mig_pb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        url = baseUrl.replaceFirst("/[a-zA-Z0-9_]+(\\?|$)", "/" + schema + "$1");
        try (Connection c = DriverManager.getConnection(baseUrl, rootUser, rootPassword); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA `" + schema + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    @AfterEach
    void dropUpgradeSchema() throws SQLException {
        try (Connection c = DriverManager.getConnection(baseUrl, rootUser, rootPassword); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS `" + schema + "`");
        }
    }

    protected void migrateTo(String version) {
        Flyway.configure()
                .dataSource(url, rootUser, rootPassword)
                .locations("classpath:db/migration")
                .callbacks(new TraceBatchNoHistoryPepperFlywayCallback(pepper()))
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    protected void exec(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, rootUser, rootPassword); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    protected long queryLong(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, rootUser, rootPassword);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    protected String queryString(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, rootUser, rootPassword);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    protected List<String> businessTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, rootUser, rootPassword);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() "
                     + "AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history' ORDER BY table_name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    /** 每张表的结构定义（可选）、内容校验和与行数（逐表）。 */
    protected Map<String, String> fingerprints(List<String> tables, boolean withDdl) throws SQLException {
        Map<String, String> result = new TreeMap<>();
        try (Connection c = DriverManager.getConnection(url, rootUser, rootPassword); Statement s = c.createStatement()) {
            for (String t : tables) {
                String ddl = "";
                if (withDdl) {
                    try (ResultSet rs = s.executeQuery("SHOW CREATE TABLE `" + t + "`")) {
                        rs.next();
                        ddl = rs.getString(2);
                    }
                }
                String checksum;
                try (ResultSet rs = s.executeQuery("CHECKSUM TABLE `" + t + "` EXTENDED")) {
                    rs.next();
                    checksum = rs.getString(2);
                }
                long rows;
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM `" + t + "`")) {
                    rs.next();
                    rows = rs.getLong(1);
                }
                result.put(t, rows + "|" + checksum + "|" + ddl);
            }
        }
        return result;
    }

    protected static void assertBad(String constraint, ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(SQLException.class).hasMessageContaining(constraint);
    }
}
