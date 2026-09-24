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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V12（批次风险状态转换台账）真实 MySQL 8.4 升级迁移测试（Phase B PB1）。
 * <p>
 * 在隔离临时 schema 中先迁移到 V11，写入一条完整的 Phase A 历史链（组织 / 场所、来源批次、PROCESS / SPLIT 操作与谱系、
 * 已到达运输任务与已接受交接、追溯事件、公开追溯码、终端销售与首次销售标记、各类幂等记录与审计），以及 V8 历史拆分遗留的
 * ACTIVE+FROZEN 与 CLOSED+RECALLED 批次，再<b>原地</b>升级到 V12，验证：
 * <ol>
 *   <li>升级无需任何数据清理即成功；全部 V1–V11 表的表结构（SHOW CREATE TABLE）与内容校验和（CHECKSUM TABLE）逐表不变——
 *       V12 不修改 batch，也不回填（遗留 FROZEN / RECALLED 批次没有台账行）；</li>
 *   <li>新台账只接受 PB1 的人工转换形状：NORMAL ⇄ FROZEN、流转快照 ACTIVE / CLOSED、来源仅 MANUAL 且必须有操作人、
 *       原因去空白后非空、组织内幂等键唯一、批次与组织外键；</li>
 *   <li>台账只引用 batch(id)，因此存在台账行的批次仍可按既有交接规则变更责任组织。</li>
 * </ol>
 * 反例：V11 之后若已存在一张形状错误的同名表，V12（不带 IF NOT EXISTS）立即失败、不被记录为成功，也不改动那张表。
 * 不修改 V1–V11。
 * </p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("V12 数据库迁移真实 MySQL 8.4 测试（批次风险状态转换台账）")
class BatchRiskMigrationV12MysqlTest {

    private static final String TEST_PEPPER = "v12-migration-test-pepper-ffffffffffffffff";

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
        schema = "trace_mig_v12_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    /** 每张表的结构定义与内容校验和（逐表），用于证明升级前后 V1–V11 的表结构与数据完全不变。 */
    private Map<String, String> fingerprints(List<String> tables) throws SQLException {
        Map<String, String> result = new TreeMap<>();
        try (Connection c = DriverManager.getConnection(url, rootUser, rootPassword); Statement s = c.createStatement()) {
            for (String t : tables) {
                String ddl;
                try (ResultSet rs = s.executeQuery("SHOW CREATE TABLE `" + t + "`")) {
                    rs.next();
                    ddl = rs.getString(2);
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
     * V11 状态下的完整 Phase A 历史链（全部满足 V1–V11 的物理约束）与 V8 历史拆分遗留的风险状态批次：
     * <ul>
     *   <li>B0(950) → PROCESS → B1(951) → SPLIT → B2(901) / B3(902)，B2 / B3 经 S1 交给零售 A 并已 ACCEPTED；</li>
     *   <li>B2 已售罄 CLOSED（两笔 Sale + first_sale_id），B3 部分销售 ACTIVE；</li>
     *   <li>905：V8 由旧 FROZEN 拆分得到的 ACTIVE+FROZEN；906：CLOSED+RECALLED（均无任何风险台账）；</li>
     *   <li>追溯事件、公开追溯码与幂等记录、交接 / 运输幂等记录与审计日志。</li>
     * </ul>
     */
    private void seedV11History() throws SQLException {
        exec("""
                INSERT INTO organization (id, org_no, name, org_type, status) VALUES
                (710, 'V12_SRC', '来源', 'SOURCE', 'ACTIVE'),
                (720, 'V12_CAR', '承运', 'CARRIER', 'ACTIVE'),
                (730, 'V12_PRC', '加工', 'PROCESSOR', 'ACTIVE'),
                (701, 'V12_RET', '零售', 'RETAILER', 'ACTIVE')
                """);
        exec("""
                INSERT INTO site (id, org_id, site_no, name, site_type, status) VALUES
                (811, 710, 'SRC-PORT', '来源码头', 'PORT', 'ACTIVE'),
                (812, 730, 'PRC-FAC', '加工厂', 'FACTORY', 'ACTIVE'),
                (813, 730, 'PRC-COLD', '加工冷库', 'COLD_STORE', 'ACTIVE'),
                (801, 701, 'RET-STORE', '门店', 'STORE', 'ACTIVE')
                """);
        exec("""
                INSERT INTO product (id, product_code, public_name, category, specification, source_type, base_unit_code, status)
                VALUES (401, 'V12_PRD', '冷冻大黄鱼', 'FISH', '500g/条', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE')
                """);
        exec("""
                INSERT INTO batch (id, org_id, creation_org_id, product_id, trace_batch_no, batch_type, quantity, unit_code,
                    origin_type, origin_text, flow_status, risk_status, version)
                VALUES
                (950, 730, 710, 401, 'TB-V12-950', 'SOURCE', 1000.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'CLOSED', 'NORMAL', 3),
                (951, 730, 730, 401, 'TB-V12-951', 'PROCESSING', 960.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'CLOSED', 'NORMAL', 2),
                (901, 701, 730, 401, 'TB-V12-901', 'PROCESSING', 600.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL', 2),
                (902, 701, 730, 401, 'TB-V12-902', 'PROCESSING', 360.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL', 2),
                (905, 710, 710, 401, 'TB-V12-905', 'SOURCE', 80.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'FROZEN', 1),
                (906, 710, 710, 401, 'TB-V12-906', 'SOURCE', 40.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'CLOSED', 'RECALLED', 1)
                """);
        exec("""
                INSERT INTO batch_operation (id, org_id, operation_no, operation_type, occurred_at, status, idempotency_key, submission_idempotency_key, version)
                VALUES
                (601, 730, 'OP-V12-PROC', 'PROCESS', '2026-09-20 02:00:00', 'SUBMITTED', 'v12-op-601-create', 'v12-op-601-submit', 1),
                (602, 730, 'OP-V12-SPLIT', 'SPLIT', '2026-09-20 03:00:00', 'SUBMITTED', 'v12-op-602-create', 'v12-op-602-submit', 1)
                """);
        exec("""
                INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity) VALUES
                (601, 950, 'INPUT', 1000.000, 'kg', 1000.000),
                (601, 951, 'OUTPUT', 960.000, 'kg', 960.000),
                (601, NULL, 'LOSS', 40.000, 'kg', 40.000),
                (602, 951, 'INPUT', 960.000, 'kg', 960.000),
                (602, 901, 'OUTPUT', 600.000, 'kg', 600.000),
                (602, 902, 'OUTPUT', 360.000, 'kg', 360.000)
                """);
        exec("""
                INSERT INTO batch_relation (operation_id, parent_batch_id, child_batch_id, relation_type) VALUES
                (601, 950, 951, 'TRANSFORM'), (602, 951, 901, 'SPLIT'), (602, 951, 902, 'SPLIT')
                """);
        exec("UPDATE batch SET consumed_by_operation_id = 601 WHERE id = 950");
        exec("UPDATE batch SET produced_by_operation_id = 601, consumed_by_operation_id = 602 WHERE id = 951");
        exec("UPDATE batch SET produced_by_operation_id = 602 WHERE id IN (901, 902)");
        exec("""
                INSERT INTO shipment (id, shipment_no, sender_org_id, receiver_org_id, carrier_org_id, vehicle_or_container_no,
                    origin_site_id, destination_site_id, loaded_at, unloaded_at, status,
                    dispatched_recorded_at, dispatched_by, delivered_recorded_at, delivered_by)
                VALUES
                (551, 'SHP-V12-S0', 710, 730, 720, '浙L·V12S0', 811, 812, '2026-09-19 01:00:00', '2026-09-19 06:00:00', 'DELIVERED',
                    '2026-09-19 01:00:00', 1, '2026-09-19 06:00:00', 1),
                (552, 'SHP-V12-S1', 730, 701, 720, '浙L·V12S1', 813, 801, '2026-09-21 01:00:00', '2026-09-21 05:00:00', 'DELIVERED',
                    '2026-09-21 01:00:00', 1, '2026-09-21 05:00:00', 1)
                """);
        exec("""
                INSERT INTO transfer (id, transfer_no, batch_id, shipment_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    status, idempotency_key, submitted_recorded_at, submitted_by, received_at, decision_recorded_at, decided_by, received_quantity)
                VALUES
                (561, 'TRF-V12-T0', 950, 551, 710, 730, 1000.000, 'kg', 'ACCEPTED', 'v12-t0-key', '2026-09-19 00:30:00', 1, '2026-09-19 07:00:00', '2026-09-19 07:00:00', 2, 1000.000),
                (562, 'TRF-V12-T2', 901, 552, 730, 701, 600.000, 'kg', 'ACCEPTED', 'v12-t2-key', '2026-09-21 00:30:00', 2, '2026-09-21 06:00:00', '2026-09-21 06:00:00', 3, 600.000),
                (563, 'TRF-V12-T3', 902, 552, 730, 701, 360.000, 'kg', 'ACCEPTED', 'v12-t3-key', '2026-09-21 00:30:00', 2, '2026-09-21 06:00:00', '2026-09-21 06:00:00', 3, 360.000)
                """);
        exec("""
                INSERT INTO trace_event (batch_id, org_id, site_id, event_type, occurred_at, summary, idempotency_key, details_json) VALUES
                (950, 710, NULL, 'SOURCE', '2026-09-18 00:00:00', '来源批次激活', 'SYS:SOURCE:BATCH:950', JSON_OBJECT('sourceObjectType', 'BATCH', 'sourceObjectId', 950)),
                (951, 730, NULL, 'PROCESS', '2026-09-20 02:00:00', '加工产出 960 kg', 'SYS:PROCESS:OPERATION:601:BATCH:951', JSON_OBJECT('sourceObjectType', 'BATCH_OPERATION', 'sourceObjectId', 601)),
                (951, 730, 813, 'FREEZE', '2026-09-20 02:30:00', '速冻', 'v12-freeze-process-0001', NULL),
                (901, 730, 813, 'TRANSPORT', '2026-09-21 01:00:00', '冷链运输发运', 'SYS:TRANSPORT:SHIPMENT:552:BATCH:901', JSON_OBJECT('sourceObjectType', 'SHIPMENT', 'sourceObjectId', 552)),
                (901, 730, 801, 'ARRIVAL', '2026-09-21 05:00:00', '冷链运输到达', 'SYS:ARRIVAL:SHIPMENT:552:BATCH:901', JSON_OBJECT('sourceObjectType', 'SHIPMENT', 'sourceObjectId', 552))
                """);
        exec("""
                INSERT INTO public_trace_code (id, batch_id, org_id, public_id, token_hash, status)
                VALUES (71, 901, 701, 'ABCDEFGHIJKLMNOPQRSTUVWX23', REPEAT('b', 64), 'ACTIVE')
                """);
        exec("""
                INSERT INTO public_trace_code_idempotency (org_id, idempotency_key, action, batch_id, public_trace_code_id, request_hash)
                VALUES (701, 'v12-ptc-activate-0001', 'ACTIVATE', 901, 71, REPEAT('c', 64))
                """);
        exec("""
                INSERT INTO sale (id, org_id, batch_id, site_id, quantity, unit_code, occurred_at, status, idempotency_key, request_hash, created_by) VALUES
                (1001, 701, 901, 801, 200.000, 'kg', '2026-09-22 01:00:00.123456', 'SUBMITTED', 'v12-sale-0000000001', REPEAT('a', 64), 3),
                (1002, 701, 901, 801, 400.000, 'kg', '2026-09-22 02:00:00.123456', 'SUBMITTED', 'v12-sale-0000000002', REPEAT('a', 64), 3),
                (1003, 701, 902, 801, 60.000, 'kg', '2026-09-22 03:00:00.123456', 'SUBMITTED', 'v12-sale-0000000003', REPEAT('a', 64), 3)
                """);
        exec("UPDATE batch SET first_sale_id = 1001, flow_status = 'CLOSED', version = version + 2 WHERE id = 901");
        exec("UPDATE batch SET first_sale_id = 1003, version = version + 1 WHERE id = 902");
        exec("""
                INSERT INTO transfer_idempotency (org_id, idempotency_key, action, transfer_id, request_hash)
                VALUES (730, 'v12-trf-create-0001', 'CREATE', 562, REPEAT('d', 64)), (701, 'v12-trf-accept-0001', 'ACCEPT', 562, REPEAT('d', 64))
                """);
        exec("""
                INSERT INTO shipment_idempotency (org_id, idempotency_key, action, shipment_id, request_hash)
                VALUES (730, 'v12-shp-create-0001', 'CREATE', 552, REPEAT('e', 64)), (720, 'v12-shp-arrive-0001', 'ARRIVE', 552, REPEAT('e', 64))
                """);
        exec("""
                INSERT INTO audit_log (request_id, actor_user_id, actor_org_id, action, object_type, object_id, occurred_at, result, change_summary_json)
                VALUES ('req-v12-1', 3, 701, 'ACCEPT', 'TRANSFER', 562, '2026-09-21 06:00:00', 'SUCCESS', JSON_OBJECT('receivedQuantity', '600'))
                """);
    }

    private void insertTransition(long batchId, long orgId, String flow, String from, String to, String source, String actor,
                                  String reason, String key) throws SQLException {
        exec("INSERT INTO batch_risk_transition (batch_id, org_id, flow_status, from_status, to_status, source_type, actor_user_id, "
                + "reason, idempotency_key, request_hash, occurred_at) VALUES (" + batchId + ", " + orgId + ", '" + flow + "', '"
                + from + "', '" + to + "', '" + source + "', " + actor + ", '" + reason + "', '" + key + "', REPEAT('f', 64), UTC_TIMESTAMP(6))");
    }

    @Test
    @DisplayName("原地升级：完整 V11 历史链无需清理即升级成功；全部 V1–V11 表结构与数据逐表不变、不回填；V12 台账约束全部生效")
    void upgrade_fromRealisticV11History() throws Exception {
        migrateTo("11");
        seedV11History();
        List<String> v11Tables = businessTables();
        assertThat(v11Tables).doesNotContain("batch_risk_transition").contains("batch", "sale", "transfer", "audit_log");
        Map<String, String> before = fingerprints(v11Tables);
        String batchesBefore = queryString("SELECT GROUP_CONCAT(CONCAT_WS(':', id, org_id, flow_status, risk_status, quantity, "
                + "IFNULL(first_sale_id, '-'), IFNULL(consumed_by_operation_id, '-'), version) ORDER BY id) FROM batch");

        migrateTo("12");

        // 1. 升级成功，只新增一张表，V1–V11 表结构与数据逐表不变，不回填
        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '12' AND success = 1")).isEqualTo(1);
        List<String> v12Tables = businessTables();
        assertThat(v12Tables).containsExactlyInAnyOrderElementsOf(concat(v11Tables, "batch_risk_transition"));
        assertThat(fingerprints(v11Tables)).isEqualTo(before);
        assertThat(queryString("SELECT GROUP_CONCAT(CONCAT_WS(':', id, org_id, flow_status, risk_status, quantity, "
                + "IFNULL(first_sale_id, '-'), IFNULL(consumed_by_operation_id, '-'), version) ORDER BY id) FROM batch"))
                .isEqualTo(batchesBefore);
        assertThat(queryLong("SELECT count(*) FROM batch_risk_transition")).as("no backfill").isZero();
        assertThat(queryString("SELECT risk_status FROM batch WHERE id = 905")).isEqualTo("FROZEN");
        assertThat(queryString("SELECT CONCAT(flow_status, '/', risk_status) FROM batch WHERE id = 906")).isEqualTo("CLOSED/RECALLED");
        assertThat(queryLong("SELECT count(*) FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE() "
                + "AND table_name = 'batch_risk_transition'")).as("only fk_brt_batch and fk_brt_org").isEqualTo(2);
        assertThat(queryLong("SELECT count(*) FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE() "
                + "AND table_name = 'batch_risk_transition' AND referenced_table_name IN ('alert', 'recall', 'recall_batch', 'app_user')"))
                .as("no foreign keys to placeholder alert / recall or to accounts").isZero();
        assertThat(queryLong("SELECT count(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'batch_risk_transition' AND column_name LIKE 'source%'"))
                .as("no polymorphic source_ref_id in V12, only source_type").isEqualTo(1);

        // 2. 合法的 PB1 人工转换形状
        insertTransition(901, 701, "CLOSED", "NORMAL", "FROZEN", "MANUAL", "3", "售罄后历史风险调查", "k-v12-000000000001");
        insertTransition(901, 701, "CLOSED", "FROZEN", "NORMAL", "MANUAL", "3", "调查结束", "k-v12-000000000002");
        insertTransition(902, 701, "ACTIVE", "NORMAL", "FROZEN", "MANUAL", "3", "门店抽检", "k-v12-000000000003");
        // 同一幂等键在另一个组织中独立
        insertTransition(950, 730, "CLOSED", "NORMAL", "FROZEN", "MANUAL", "2", "加工企业历史调查", "k-v12-000000000001");
        assertThat(queryLong("SELECT count(*) FROM batch_risk_transition")).isEqualTo(4);

        // 3. 转换对、流转快照、来源类型与形状、原因、幂等与外键约束
        for (String[] bad : List.of(
                new String[]{"NORMAL", "RECALLED"}, new String[]{"FROZEN", "RECALLED"}, new String[]{"RECALLED", "NORMAL"},
                new String[]{"NORMAL", "NORMAL"}, new String[]{"FROZEN", "FROZEN"})) {
            assertThatThrownBy(() -> insertTransition(902, 701, "ACTIVE", bad[0], bad[1], "MANUAL", "3", "非法转换", "k-v12-bad-" + bad[0] + bad[1]))
                    .isInstanceOf(SQLException.class).hasMessageContaining("chk_brt_transition");
        }
        assertThatThrownBy(() -> insertTransition(902, 701, "DRAFT", "NORMAL", "FROZEN", "MANUAL", "3", "草稿", "k-v12-draft-00001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_brt_flow_status");
        for (String source : List.of("ALERT", "RECALL", "SYSTEM")) {
            assertThatThrownBy(() -> insertTransition(902, 701, "ACTIVE", "NORMAL", "FROZEN", source, "NULL", "未来来源", "k-v12-src-" + source))
                    .isInstanceOf(SQLException.class).hasMessageContaining("chk_brt_source_type");
        }
        assertThatThrownBy(() -> insertTransition(902, 701, "ACTIVE", "NORMAL", "FROZEN", "MANUAL", "NULL", "无操作人", "k-v12-actor-00001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_brt_source_shape");
        assertThatThrownBy(() -> insertTransition(902, 701, "ACTIVE", "NORMAL", "FROZEN", "MANUAL", "3", "   ", "k-v12-reason-0001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_brt_reason");
        assertThatThrownBy(() -> insertTransition(902, 701, "ACTIVE", "NORMAL", "FROZEN", "MANUAL", "3", "重复键", "k-v12-000000000003"))
                .isInstanceOf(SQLException.class).hasMessageContaining("uk_brt_org_idempotency");
        assertThatThrownBy(() -> insertTransition(999999, 701, "ACTIVE", "NORMAL", "FROZEN", "MANUAL", "3", "不存在的批次", "k-v12-fk-batch-01"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_brt_batch");
        assertThatThrownBy(() -> insertTransition(902, 999999, "ACTIVE", "NORMAL", "FROZEN", "MANUAL", "3", "不存在的组织", "k-v12-fk-org-0001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_brt_org");

        // 4. 存在台账行的批次仍可按既有规则变更责任组织（台账只引用 batch.id），但不能被物理删除
        exec("UPDATE batch SET org_id = 710, version = version + 1 WHERE id = 905");
        insertTransition(905, 710, "ACTIVE", "FROZEN", "NORMAL", "MANUAL", "1", "解除遗留冻结", "k-v12-legacy-0001");
        exec("UPDATE batch SET risk_status = 'NORMAL', version = version + 1 WHERE id = 905");
        exec("UPDATE batch SET org_id = 730, version = version + 1 WHERE id = 905");
        assertThat(queryLong("SELECT org_id FROM batch WHERE id = 905")).isEqualTo(730);
        assertThatThrownBy(() -> exec("DELETE FROM batch WHERE id = 905"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_brt_batch");
    }

    @Test
    @DisplayName("fail-fast：V11 之后已存在一张形状错误 / 不完整的 batch_risk_transition 时，V12 失败且不会被记录为成功，也不改动那张表")
    void upgrade_failsFastOnPreExistingStaleTable() throws Exception {
        migrateTo("11");
        seedV11History();
        // 形状错误且不完整：缺少台账列、约束与外键
        exec("CREATE TABLE `batch_risk_transition` (`id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, `batch_id` BIGINT UNSIGNED NULL, "
                + "`note` VARCHAR(20) NULL, PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");
        exec("INSERT INTO `batch_risk_transition` (`batch_id`, `note`) VALUES (901, 'stale')");
        String staleColumns = queryString("SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'batch_risk_transition'");
        String batchesBefore = queryString("SELECT GROUP_CONCAT(CONCAT_WS(':', id, org_id, flow_status, risk_status, version) ORDER BY id) FROM batch");

        assertThatThrownBy(() -> migrateTo("12"))
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("batch_risk_transition")
                .hasStackTraceContaining("already exists");

        // V12 未被记录为成功应用；V1–V11 仍是唯一成功的迁移
        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '12' AND success = 1")).isZero();
        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '11' AND success = 1")).isEqualTo(1);
        // 失败的 V12 没有静默接受或改造这张表，也没有改动任何既有数据
        assertThat(queryString("SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'batch_risk_transition'")).isEqualTo(staleColumns).isEqualTo("id,batch_id,note");
        assertThat(queryLong("SELECT count(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() "
                + "AND table_name = 'batch_risk_transition' AND constraint_name LIKE 'chk_brt_%'")).isZero();
        assertThat(queryLong("SELECT count(*) FROM batch_risk_transition")).isEqualTo(1);
        assertThat(queryString("SELECT GROUP_CONCAT(CONCAT_WS(':', id, org_id, flow_status, risk_status, version) ORDER BY id) FROM batch"))
                .isEqualTo(batchesBefore);
    }

    private static List<String> concat(List<String> base, String extra) {
        List<String> all = new ArrayList<>(base);
        all.add(extra);
        return all;
    }
}
