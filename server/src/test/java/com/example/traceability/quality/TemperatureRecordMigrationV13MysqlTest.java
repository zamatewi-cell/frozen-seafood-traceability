package com.example.traceability.quality;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V13（Shipment 在途温度记录约束）真实 MySQL 8.4 升级迁移测试（Phase B PB2）。
 * <p>
 * 在隔离临时 schema 中先迁移到 V12，写入组织 / 场所 / 产品、已发布运输温控规则（TRANSPORT 与 STORAGE 两个环节）、
 * 在途与已到达运输任务、交接与批次，再<b>原地</b>升级到 V13，验证：
 * <ol>
 *   <li>升级成功；除 temperature_record、shipment、temperature_rule_stage 外全部表的结构与数据逐表不变；
 *       shipment / temperature_rule_stage 只新增复合外键目标唯一键，数据不变；</li>
 *   <li>temperature_record 只接受 PB2 的 Shipment 在途形状与单点判定快照：判定取值、MISSING_CONTEXT 与 NORMAL / HIGH / LOW 的
 *       快照形状（规则环节、上下限与允许越界时长快照同有同无）及与上下限的一致性、承运组织复合外键、TRANSPORT 规则环节复合外键、
 *       来源、温标、温度范围、时间、幂等唯一键；</li>
 *   <li>同一运输任务同一测量时间允许多条记录（不建立测量时间唯一约束），evaluation 不再有默认值。</li>
 * </ol>
 * 反例：V12 状态下 temperature_record 已有存量行时，V13 在任何 DDL 之前失败、不被记录为成功，且不改动任何表。
 * 不修改 V1–V12。
 * </p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("V13 数据库迁移真实 MySQL 8.4 测试（Shipment 在途温度记录）")
class TemperatureRecordMigrationV13MysqlTest {

    private static final String TEST_PEPPER = "v13-migration-test-pepper-ffffffffffffffff";
    private static final List<String> ALTERED = List.of("temperature_record", "shipment", "temperature_rule_stage");

    @Autowired
    private DataSource dataSource;

    private String rootUser;
    private String rootPassword;
    private String baseUrl;
    private String schema;
    private String url;
    private int keySeq;

    @BeforeEach
    void createSchema() throws SQLException {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        baseUrl = hikari.getJdbcUrl();
        rootUser = System.getenv().getOrDefault("DB_ROOT_USERNAME", "root");
        if (rootUser.isBlank()) {
            rootUser = "root";
        }
        rootPassword = System.getenv().getOrDefault("DB_ROOT_PASSWORD", "");
        schema = "trace_mig_v13_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private String queryString(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, rootUser, rootPassword);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private List<String> businessTables() throws SQLException {
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

    /** 每张表的结构定义与内容校验和（逐表）。 */
    private Map<String, String> fingerprints(List<String> tables, boolean withDdl) throws SQLException {
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

    /**
     * V12 状态下的基础资料与运输事实：
     * <ul>
     *   <li>产品 401 的已发布规则 v1（stage 461 TRANSPORT [-25, -15]，stage 462 STORAGE [-30, -18]）；</li>
     *   <li>552：IN_TRANSIT，承运 720，装载 B901 / B902（同一产品）；553：DELIVERED，承运 720；</li>
     *   <li>承运 721 为另一个承运组织（用于承运组织复合外键反例）。</li>
     * </ul>
     */
    private void seedV12Facts() throws SQLException {
        exec("""
                INSERT INTO organization (id, org_no, name, org_type, status) VALUES
                (720, 'V13_CAR', '承运', 'CARRIER', 'ACTIVE'),
                (721, 'V13_CAR2', '另一承运', 'CARRIER', 'ACTIVE'),
                (730, 'V13_PRC', '加工', 'PROCESSOR', 'ACTIVE'),
                (701, 'V13_RET', '零售', 'RETAILER', 'ACTIVE')
                """);
        exec("""
                INSERT INTO site (id, org_id, site_no, name, site_type, status) VALUES
                (813, 730, 'PRC-COLD', '加工冷库', 'COLD_STORE', 'ACTIVE'),
                (801, 701, 'RET-STORE', '门店', 'STORE', 'ACTIVE')
                """);
        exec("""
                INSERT INTO product (id, product_code, public_name, category, specification, source_type, base_unit_code, status)
                VALUES (401, 'V13_PRD', '冷冻大黄鱼', 'FISH', '500g/条', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE')
                """);
        exec("""
                INSERT INTO temperature_rule (id, product_id, version_no, name, effective_from, effective_to, status)
                VALUES (451, 401, 1, '冷冻大黄鱼运输规则', '2020-01-01 00:00:00', NULL, 'ACTIVE')
                """);
        exec("""
                INSERT INTO temperature_rule_stage (id, rule_id, stage_code, lower_limit, upper_limit, unit_code, allowed_duration_seconds, sequence_no)
                VALUES (461, 451, 'TRANSPORT', -25.00, -15.00, 'CELSIUS', 1800, 1),
                       (462, 451, 'STORAGE', -30.00, -18.00, 'CELSIUS', 0, 2)
                """);
        exec("""
                INSERT INTO batch (id, org_id, creation_org_id, product_id, trace_batch_no, batch_type, quantity, unit_code,
                    origin_type, origin_text, flow_status, risk_status, version)
                VALUES
                (901, 730, 730, 401, 'TB-V13-901', 'PROCESSING', 600.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL', 2),
                (902, 730, 730, 401, 'TB-V13-902', 'PROCESSING', 360.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL', 2)
                """);
        exec("""
                INSERT INTO shipment (id, shipment_no, sender_org_id, receiver_org_id, carrier_org_id, vehicle_or_container_no,
                    origin_site_id, destination_site_id, loaded_at, unloaded_at, status,
                    dispatched_recorded_at, dispatched_by, delivered_recorded_at, delivered_by)
                VALUES
                (552, 'SHP-V13-S1', 730, 701, 720, '浙L·V13S1', 813, 801, '2026-09-21 01:00:00', NULL, 'IN_TRANSIT',
                    '2026-09-21 01:00:00', 1, NULL, NULL),
                (553, 'SHP-V13-S2', 730, 701, 720, '浙L·V13S2', 813, 801, '2026-09-20 01:00:00', '2026-09-20 05:00:00', 'DELIVERED',
                    '2026-09-20 01:00:00', 1, '2026-09-20 05:00:00', 1)
                """);
        exec("""
                INSERT INTO transfer (id, transfer_no, batch_id, shipment_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    status, idempotency_key, submitted_recorded_at, submitted_by)
                VALUES
                (562, 'TRF-V13-T2', 901, 552, 730, 701, 600.000, 'kg', 'PENDING', 'v13-t2-key', '2026-09-21 00:30:00', 2),
                (563, 'TRF-V13-T3', 902, 552, 730, 701, 360.000, 'kg', 'PENDING', 'v13-t3-key', '2026-09-21 00:30:00', 2)
                """);
    }

    /**
     * 插入一条温度记录；stage / lower / upper 为 SQL 片段（可为 NULL）。允许越界时长快照随规则环节推导：
     * 无规则环节时为 NULL，否则为种子环节的 1800 秒（显式取值见 {@link #insertRecordWithDuration}）。
     */
    private void insertRecord(long shipmentId, long orgId, String stageCode, String measuredAt, String temperature,
                              String source, String evaluation, String stageId, String lower, String upper) throws SQLException {
        insertRecordWithKey(shipmentId, orgId, stageCode, measuredAt, temperature, source, evaluation, stageId, lower, upper,
                "k-v13-" + String.format("%012d", ++keySeq));
    }

    private void insertRecordWithKey(long shipmentId, long orgId, String stageCode, String measuredAt, String temperature,
                                     String source, String evaluation, String stageId, String lower, String upper, String key) throws SQLException {
        insertRecordWithDuration(shipmentId, orgId, stageCode, measuredAt, temperature, source, evaluation, stageId, lower, upper,
                "NULL".equals(stageId) ? "NULL" : "1800", key);
    }

    private void insertRecordWithDuration(long shipmentId, long orgId, String stageCode, String measuredAt, String temperature,
                                          String source, String evaluation, String stageId, String lower, String upper,
                                          String allowedDuration, String key) throws SQLException {
        exec("INSERT INTO temperature_record (shipment_id, org_id, actor_user_id, stage_code, measured_at, recorded_at, temperature, "
                + "unit_code, data_source, rule_stage_id, rule_lower_limit, rule_upper_limit, rule_allowed_duration_seconds, evaluation, "
                + "idempotency_key, request_hash) "
                + "VALUES (" + shipmentId + ", " + orgId + ", 3, '" + stageCode + "', '" + measuredAt + "', '2026-09-21 03:00:00.000000', "
                + temperature + ", 'CELSIUS', '" + source + "', " + stageId + ", " + lower + ", " + upper + ", " + allowedDuration + ", '"
                + evaluation + "', '" + key + "', REPEAT('f', 64))");
    }

    private void normal(String measuredAt, String temperature) throws SQLException {
        insertRecord(552, 720, "TRANSPORT", measuredAt, temperature, "MANUAL", "NORMAL", "461", "-25.00", "-15.00");
    }

    @Test
    @DisplayName("原地升级：V12 事实无需清理即升级成功；未改动表逐表不变；temperature_record 只接受 PB2 在途形状与单点判定快照")
    void upgrade_fromV12() throws Exception {
        migrateTo("12");
        seedV12Facts();
        List<String> v12Tables = businessTables();
        List<String> untouched = v12Tables.stream().filter(t -> !ALTERED.contains(t)).toList();
        Map<String, String> before = fingerprints(untouched, true);
        Map<String, String> alteredDataBefore = fingerprints(List.of("shipment", "temperature_rule_stage"), false);

        migrateTo("13");

        // 1. 升级成功；不新增表；未改动表结构与数据逐表不变；shipment / stage 数据不变
        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '13' AND success = 1")).isEqualTo(1);
        assertThat(businessTables()).containsExactlyElementsOf(v12Tables);
        assertThat(fingerprints(untouched, true)).isEqualTo(before);
        assertThat(fingerprints(List.of("shipment", "temperature_rule_stage"), false)).isEqualTo(alteredDataBefore);
        assertThat(queryString("SELECT column_default FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'temperature_record' AND column_name = 'evaluation'")).as("no DEFAULT 'NORMAL'").isNull();
        assertThat(queryLong("SELECT count(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                + "AND table_name = 'temperature_record' AND non_unique = 0 AND column_name = 'measured_at'"))
                .as("measurement time is never part of a unique key").isZero();
        assertThat(queryLong("SELECT count(*) FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE() "
                + "AND table_name = 'temperature_record' AND referenced_table_name IN ('alert', 'batch', 'app_user')"))
                .as("no foreign keys to alert / batch / accounts").isZero();

        // 2. 合法形状：NORMAL / HIGH / LOW 与快照一致（上下限本身属于范围内）；MISSING_CONTEXT 四项快照为空；
        //    同一测量时间允许多条记录；SIMULATED 与设备编号
        normal("2026-09-21 01:00:00.000000", "-25.00");
        normal("2026-09-21 01:30:00.123456", "-15.00");
        normal("2026-09-21 01:30:00.123456", "-18.00");
        insertRecord(552, 720, "TRANSPORT", "2026-09-21 01:45:00", "-12.50", "SIMULATED", "HIGH", "461", "-25.00", "-15.00");
        insertRecord(552, 720, "TRANSPORT", "2026-09-21 01:50:00", "-25.01", "MANUAL", "LOW", "461", "-25.00", "-15.00");
        insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:00:00", "4.00", "MANUAL", "MISSING_CONTEXT", "NULL", "NULL", "NULL");
        exec("UPDATE temperature_record SET device_no = 'PROBE-01' WHERE evaluation = 'HIGH'");
        assertThat(queryLong("SELECT count(*) FROM temperature_record WHERE shipment_id = 552 AND measured_at = '2026-09-21 01:30:00.123456'"))
                .as("same shipment, same measurement time, two records").isEqualTo(2);
        assertThat(queryString("SELECT DATE_FORMAT(MAX(measured_at), '%Y-%m-%d %H:%i:%s.%f') FROM temperature_record"))
                .as("DATETIME(6) keeps microseconds").isEqualTo("2026-09-21 02:00:00.000000");
        assertThat(queryString("SELECT DATE_FORMAT(measured_at, '%f') FROM temperature_record WHERE temperature = -15.00"))
                .isEqualTo("123456");
        // 允许越界时长快照：与规则环节 allowed_duration_seconds 同类型、可空；有规则环节的判定保存快照，MISSING_CONTEXT 为空
        assertThat(queryString("SELECT CONCAT(column_type, ':', is_nullable) FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'temperature_record' AND column_name = 'rule_allowed_duration_seconds'"))
                .isEqualTo(queryString("SELECT CONCAT(column_type, ':YES') FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'temperature_rule_stage' AND column_name = 'allowed_duration_seconds'"))
                .isEqualTo("int unsigned:YES");
        assertThat(queryLong("SELECT count(*) FROM temperature_record WHERE evaluation IN ('NORMAL', 'HIGH', 'LOW') "
                + "AND rule_allowed_duration_seconds = 1800")).isEqualTo(5);
        assertThat(queryLong("SELECT count(*) FROM temperature_record WHERE evaluation = 'MISSING_CONTEXT' "
                + "AND rule_allowed_duration_seconds IS NULL")).isEqualTo(1);
        // 同一幂等键在另一个承运组织中独立（组织内唯一）
        exec("UPDATE shipment SET carrier_org_id = 721 WHERE id = 553");
        insertRecordWithKey(553, 721, "TRANSPORT", "2026-09-20 02:00:00", "-18.00", "MANUAL", "NORMAL", "461", "-25.00", "-15.00",
                "k-v13-000000000001");

        // 3. 判定取值与快照形状 / 一致性
        assertBad("chk_temp_evaluation", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-18.00", "MANUAL", "SUSTAINED", "461", "-25.00", "-15.00"));
        assertBad("chk_temp_evaluation_shape", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-10.00", "MANUAL", "NORMAL", "461", "-25.00", "-15.00"));
        assertBad("chk_temp_evaluation_shape", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-15.00", "MANUAL", "HIGH", "461", "-25.00", "-15.00"));
        assertBad("chk_temp_evaluation_shape", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-25.00", "MANUAL", "LOW", "461", "-25.00", "-15.00"));
        assertBad("chk_temp_evaluation_shape", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-18.00", "MANUAL", "NORMAL", "NULL", "NULL", "NULL"));
        assertBad("chk_temp_evaluation_shape", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-18.00", "MANUAL", "NORMAL", "461", "NULL", "-15.00"));
        assertBad("chk_temp_evaluation_shape", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-18.00", "MANUAL", "MISSING_CONTEXT", "461", "-25.00", "-15.00"));
        assertBad("chk_temp_evaluation_shape", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-18.00", "MANUAL", "MISSING_CONTEXT", "NULL", "-25.00", "NULL"));
        assertBad("chk_temp_evaluation_shape", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-18.00", "MANUAL", "NORMAL", "461", "-15.00", "-25.00"));
        // 允许越界时长快照：NORMAL / HIGH / LOW 必须有，MISSING_CONTEXT 必须没有；不能为负
        for (String evaluation : List.of("NORMAL", "HIGH", "LOW")) {
            String t = evaluation.equals("NORMAL") ? "-18.00" : evaluation.equals("HIGH") ? "-10.00" : "-30.00";
            assertBad("chk_temp_evaluation_shape", () -> insertRecordWithDuration(552, 720, "TRANSPORT", "2026-09-21 02:10:00", t, "MANUAL",
                    evaluation, "461", "-25.00", "-15.00", "NULL", "k-v13-no-duration-" + evaluation));
        }
        assertBad("chk_temp_evaluation_shape", () -> insertRecordWithDuration(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-18.00", "MANUAL",
                "MISSING_CONTEXT", "NULL", "NULL", "NULL", "1800", "k-v13-missing-duration"));
        assertThatThrownBy(() -> insertRecordWithDuration(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-18.00", "MANUAL",
                "NORMAL", "461", "-25.00", "-15.00", "-1", "k-v13-negative-duration"))
                .isInstanceOf(SQLException.class).hasMessageContaining("rule_allowed_duration_seconds");

        // 4. 环节、来源、温标、温度范围、设备编号、时间、软删除与绑定形状
        assertBad("chk_temp_stage", () -> insertRecord(552, 720, "STORAGE", "2026-09-21 02:10:00", "-20.00", "MANUAL", "MISSING_CONTEXT", "NULL", "NULL", "NULL"));
        for (String source : List.of("IMPORT", "DEVICE", "SENSOR")) {
            assertBad("chk_temp_source", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-18.00", source, "MISSING_CONTEXT", "NULL", "NULL", "NULL"));
        }
        assertBad("chk_temp_range", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-80.01", "MANUAL", "MISSING_CONTEXT", "NULL", "NULL", "NULL"));
        assertBad("chk_temp_range", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "60.01", "MANUAL", "MISSING_CONTEXT", "NULL", "NULL", "NULL"));
        assertBad("chk_temp_time", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 03:05:00.000001", "-18.00", "MANUAL", "MISSING_CONTEXT", "NULL", "NULL", "NULL"));
        assertBad("chk_temp_unit", () -> exec("UPDATE temperature_record SET unit_code = 'KELVIN' WHERE evaluation = 'HIGH'"));
        assertBad("chk_temp_device_no", () -> exec("UPDATE temperature_record SET device_no = '   ' WHERE evaluation = 'HIGH'"));
        assertBad("chk_temp_not_deleted", () -> exec("UPDATE temperature_record SET is_deleted = 1 WHERE evaluation = 'HIGH'"));
        assertBad("chk_temp_binding", () -> exec("UPDATE temperature_record SET batch_id = 901 WHERE evaluation = 'HIGH'"));
        assertThatThrownBy(() -> exec("INSERT INTO temperature_record (shipment_id, org_id, actor_user_id, stage_code, measured_at, recorded_at, "
                + "temperature, data_source, idempotency_key, request_hash) VALUES (552, 720, 3, 'TRANSPORT', '2026-09-21 02:10:00', "
                + "'2026-09-21 03:00:00', -18.00, 'MANUAL', 'k-v13-no-evaluation', REPEAT('f', 64))"))
                .as("evaluation has no default").isInstanceOf(SQLException.class).hasMessageContaining("evaluation");

        // 5. 幂等唯一键与外键：承运组织复合外键、TRANSPORT 规则环节复合外键
        assertBad("uk_temp_org_idempotency", () -> insertRecordWithKey(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-18.00", "MANUAL",
                "NORMAL", "461", "-25.00", "-15.00", "k-v13-000000000001"));
        assertBad("fk_temp_shipment_carrier", () -> insertRecord(552, 730, "TRANSPORT", "2026-09-21 02:10:00", "-18.00", "MANUAL", "NORMAL", "461", "-25.00", "-15.00"));
        assertBad("fk_temp_shipment_carrier", () -> insertRecord(552, 721, "TRANSPORT", "2026-09-21 02:10:00", "-18.00", "MANUAL", "NORMAL", "461", "-25.00", "-15.00"));
        assertBad("fk_temp_shipment_carrier", () -> insertRecord(999999, 720, "TRANSPORT", "2026-09-21 02:10:00", "-18.00", "MANUAL", "NORMAL", "461", "-25.00", "-15.00"));
        assertBad("fk_temp_rule_stage", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-20.00", "MANUAL", "NORMAL", "462", "-30.00", "-18.00"));
        assertBad("fk_temp_rule_stage", () -> insertRecord(552, 720, "TRANSPORT", "2026-09-21 02:10:00", "-20.00", "MANUAL", "NORMAL", "999999", "-25.00", "-15.00"));

        // 6. 已有温度记录的运输任务不能被物理删除（先移除交接以隔离 fk_transfer_shipment），其承运组织也不能被改写
        exec("DELETE FROM transfer WHERE shipment_id = 552");
        assertThatThrownBy(() -> exec("DELETE FROM shipment WHERE id = 552")).isInstanceOf(SQLException.class).hasMessageContaining("fk_temp_shipment_carrier");
        assertThatThrownBy(() -> exec("UPDATE shipment SET carrier_org_id = 721 WHERE id = 552")).isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_temp_shipment_carrier");
        assertThat(queryLong("SELECT count(*) FROM temperature_record")).isEqualTo(7);
    }

    private static void assertBad(String constraint, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(SQLException.class).hasMessageContaining(constraint);
    }

    @Test
    @DisplayName("fail-fast：V12 状态下 temperature_record 已有存量行时，V13 在任何 DDL 之前失败、不被记录为成功，且不改动任何表")
    void upgrade_failsFastWhenTemperatureRecordNotEmpty() throws Exception {
        migrateTo("12");
        seedV12Facts();
        exec("INSERT INTO temperature_record (batch_id, shipment_id, stage_code, measured_at, temperature) "
                + "VALUES (NULL, 552, 'TRANSPORT', '2026-09-21 02:00:00', -18.00)");
        List<String> tables = businessTables();
        Map<String, String> before = fingerprints(tables, true);

        assertThatThrownBy(() -> migrateTo("13"))
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("passed");

        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '13' AND success = 1")).isZero();
        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '12' AND success = 1")).isEqualTo(1);
        assertThat(fingerprints(tables, true)).as("no DDL applied before the precondition failed").isEqualTo(before);
        assertThat(queryString("SELECT column_default FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'temperature_record' AND column_name = 'evaluation'")).isEqualTo("NORMAL");
        assertThat(queryLong("SELECT count(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() "
                + "AND constraint_name IN ('uk_shipment_id_carrier', 'uk_stage_id_stage_code')")).isZero();
    }
}
