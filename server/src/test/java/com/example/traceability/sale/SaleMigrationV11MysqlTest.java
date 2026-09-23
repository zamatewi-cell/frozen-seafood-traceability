package com.example.traceability.sale;

import com.example.traceability.common.config.TraceBatchNoHistoryPepperFlywayCallback;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
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
 * V11（终端 Sale 台账与首次销售标记）真实 MySQL 8.4 升级迁移测试。
 * <p>
 * 在隔离临时 schema 中先迁移到 V10，写入一条完整的 Phase A 历史链（组织 / 场所目录、来源批次、PROCESS / SPLIT 操作与谱系、
 * 已到达运输任务与已接受交接、legacy 交接、追溯事件与公开追溯码），再<b>原地</b>升级到 V11，验证：
 * <ol>
 *   <li>升级无需任何数据清理即成功，历史行数与关键字段原样保留，first_sale_id 全部为 NULL（不回填）；</li>
 *   <li>{@code site} 新增 {@code UNIQUE (id, org_id)} 在已有场所上成功建立；</li>
 *   <li>sale 台账物理约束：正数量、kg、SUBMITTED、组织内幂等键唯一、场所必须属于销售组织；</li>
 *   <li>batch.first_sale_id 三列复合外键：同批次同组织的首笔 Sale 成功，跨批次 / 跨组织被拒绝，后续销售保持原标记；</li>
 *   <li>已开始销售的批次不能再被操作全量消耗、不能是 DRAFT、责任组织不能再变更；未销售批次的交接责任转移不受影响。</li>
 * </ol>
 * 不修改 V1–V10。
 * </p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("V11 数据库迁移真实 MySQL 8.4 测试（终端 Sale 台账与首次销售标记）")
class SaleMigrationV11MysqlTest {

    private static final String TEST_PEPPER = "v11-migration-test-pepper-eeeeeeeeeeeeeeee";

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
        schema = "trace_mig_v11_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    /**
     * V10 状态下的完整 Phase A 历史链（全部满足 V1–V10 的物理约束）：
     * <ul>
     *   <li>组织：来源 710、承运 720、加工 730、零售 A 701、零售 B 702；场所：来源码头、加工厂、加工冷库、零售门店 A、零售门店 B、零售 A 停用门店；</li>
     *   <li>B0(950) 1000kg 来源批次 → T0 经 S0 交给加工企业 → PROCESS OP601 → B1(951) 960kg → SPLIT OP602 → B2(901) 600kg / B3(902) 360kg；</li>
     *   <li>B2 / B3 经同一运输任务 S1 由加工企业交给零售 A 并已 ACCEPTED；零售 B 的 903 来自 legacy 交接；904 为 CLOSED 历史批次；</li>
     *   <li>追溯事件（SOURCE / PROCESS / TRANSPORT / ARRIVAL）与 B2 的公开追溯码。</li>
     * </ul>
     */
    private void seedV10History() throws SQLException {
        exec("""
                INSERT INTO organization (id, org_no, name, org_type, status) VALUES
                (710, 'V11_SRC', '来源', 'SOURCE', 'ACTIVE'),
                (720, 'V11_CAR', '承运', 'CARRIER', 'ACTIVE'),
                (730, 'V11_PRC', '加工', 'PROCESSOR', 'ACTIVE'),
                (701, 'V11_RET_A', '零售A', 'RETAILER', 'ACTIVE'),
                (702, 'V11_RET_B', '零售B', 'RETAILER', 'ACTIVE')
                """);
        exec("""
                INSERT INTO site (id, org_id, site_no, name, site_type, status) VALUES
                (811, 710, 'SRC-PORT', '来源码头', 'PORT', 'ACTIVE'),
                (812, 730, 'PRC-FAC', '加工厂', 'FACTORY', 'ACTIVE'),
                (813, 730, 'PRC-COLD', '加工冷库', 'COLD_STORE', 'ACTIVE'),
                (801, 701, 'A-STORE', '门店A', 'STORE', 'ACTIVE'),
                (802, 702, 'B-STORE', '门店B', 'STORE', 'ACTIVE'),
                (803, 701, 'A-OLD', '停用门店A', 'STORE', 'INACTIVE')
                """);
        exec("""
                INSERT INTO product (id, product_code, public_name, category, specification, source_type, base_unit_code, status)
                VALUES (401, 'V11_PRD', '冷冻大黄鱼', 'FISH', '500g/条', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE')
                """);
        exec("""
                INSERT INTO batch (id, org_id, creation_org_id, product_id, trace_batch_no, batch_type, quantity, unit_code,
                    origin_type, origin_text, flow_status, risk_status, version)
                VALUES
                (950, 730, 710, 401, 'TB-V11-950', 'SOURCE', 1000.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'CLOSED', 'NORMAL', 3),
                (951, 730, 730, 401, 'TB-V11-951', 'PROCESSING', 960.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'CLOSED', 'NORMAL', 2),
                (901, 701, 730, 401, 'TB-V11-901', 'PROCESSING', 600.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL', 2),
                (902, 701, 730, 401, 'TB-V11-902', 'PROCESSING', 360.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL', 2),
                (903, 702, 710, 401, 'TB-V11-903', 'SOURCE', 100.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'ACTIVE', 'NORMAL', 1),
                (904, 701, 701, 401, 'TB-V11-904', 'SOURCE', 50.000, 'kg', 'DOMESTIC_CAPTURE', '舟山渔场', 'CLOSED', 'NORMAL', 1)
                """);
        exec("""
                INSERT INTO batch_operation (id, org_id, operation_no, operation_type, occurred_at, status, idempotency_key, submission_idempotency_key, version)
                VALUES
                (601, 730, 'OP-V11-PROC', 'PROCESS', '2026-09-20 02:00:00', 'SUBMITTED', 'v11-op-601-create', 'v11-op-601-submit', 1),
                (602, 730, 'OP-V11-SPLIT', 'SPLIT', '2026-09-20 03:00:00', 'SUBMITTED', 'v11-op-602-create', 'v11-op-602-submit', 1)
                """);
        exec("""
                INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity) VALUES
                (601, 950, 'INPUT', 1000.000, 'kg', 1000.000),
                (601, 951, 'OUTPUT', 960.000, 'kg', 960.000),
                (601, NULL, 'LOSS', 30.000, 'kg', 30.000),
                (601, NULL, 'SAMPLE', 10.000, 'kg', 10.000),
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
                (551, 'SHP-V11-S0', 710, 730, 720, '浙L·V11S0', 811, 812, '2026-09-19 01:00:00', '2026-09-19 06:00:00', 'DELIVERED',
                    '2026-09-19 01:00:00', 1, '2026-09-19 06:00:00', 1),
                (552, 'SHP-V11-S1', 730, 701, 720, '浙L·V11S1', 813, 801, '2026-09-21 01:00:00', '2026-09-21 05:00:00', 'DELIVERED',
                    '2026-09-21 01:00:00', 1, '2026-09-21 05:00:00', 1)
                """);
        exec("""
                INSERT INTO transfer (id, transfer_no, batch_id, shipment_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    status, idempotency_key, submitted_recorded_at, submitted_by, received_at, decision_recorded_at, decided_by, received_quantity)
                VALUES
                (561, 'TRF-V11-T0', 950, 551, 710, 730, 1000.000, 'kg', 'ACCEPTED', 'v11-t0-key', '2026-09-19 00:30:00', 1, '2026-09-19 07:00:00', '2026-09-19 07:00:00', 2, 1000.000),
                (562, 'TRF-V11-T2', 901, 552, 730, 701, 600.000, 'kg', 'ACCEPTED', 'v11-t2-key', '2026-09-21 00:30:00', 2, '2026-09-21 06:00:00', '2026-09-21 06:00:00', 3, 600.000),
                (563, 'TRF-V11-T3', 902, 552, 730, 701, 360.000, 'kg', 'ACCEPTED', 'v11-t3-key', '2026-09-21 00:30:00', 2, '2026-09-21 06:00:00', '2026-09-21 06:00:00', 3, 360.000)
                """);
        exec("""
                INSERT INTO transfer (id, transfer_no, batch_id, sender_org_id, receiver_org_id, quantity, unit_code, shipped_at, received_at,
                    received_quantity, status, idempotency_key, is_legacy)
                VALUES (564, 'TRF-V11-LEGACY', 903, 710, 702, 100.000, 'kg', '2026-09-01 00:00:00', '2026-09-02 00:00:00', 100.000, 'ACCEPTED', 'v11-legacy-key', 1)
                """);
        exec("""
                INSERT INTO trace_event (batch_id, org_id, site_id, event_type, occurred_at, summary, idempotency_key, details_json) VALUES
                (950, 710, NULL, 'SOURCE', '2026-09-18 00:00:00', '来源批次激活', 'SYS:SOURCE:BATCH:950', JSON_OBJECT('sourceObjectType', 'BATCH', 'sourceObjectId', 950)),
                (951, 730, NULL, 'PROCESS', '2026-09-20 02:00:00', '加工产出 960 kg', 'SYS:PROCESS:OPERATION:601:BATCH:951', JSON_OBJECT('sourceObjectType', 'BATCH_OPERATION', 'sourceObjectId', 601)),
                (901, 730, 813, 'TRANSPORT', '2026-09-21 01:00:00', '冷链运输发运', 'SYS:TRANSPORT:SHIPMENT:552:BATCH:901', JSON_OBJECT('sourceObjectType', 'SHIPMENT', 'sourceObjectId', 552)),
                (901, 730, 801, 'ARRIVAL', '2026-09-21 05:00:00', '冷链运输到达', 'SYS:ARRIVAL:SHIPMENT:552:BATCH:901', JSON_OBJECT('sourceObjectType', 'SHIPMENT', 'sourceObjectId', 552)),
                (901, 730, 813, 'WAREHOUSE_IN', '2026-09-20 04:00:00', '冷库入库', 'v11-warehouse-in-0001', NULL)
                """);
        exec("""
                INSERT INTO public_trace_code (batch_id, org_id, public_id, token_hash, status)
                VALUES (901, 701, 'ABCDEFGHIJKLMNOPQRSTUVWX23', REPEAT('b', 64), 'ACTIVE')
                """);
    }

    private void insertSale(long id, long orgId, long batchId, long siteId, String qty, String key) throws SQLException {
        exec("INSERT INTO sale (id, org_id, batch_id, site_id, quantity, unit_code, occurred_at, status, idempotency_key, request_hash, created_by) VALUES ("
                + id + ", " + orgId + ", " + batchId + ", " + siteId + ", " + qty + ", 'kg', '2026-09-23 01:30:15.123456', 'SUBMITTED', '"
                + key + "', REPEAT('a', 64), 1)");
    }

    @Test
    @DisplayName("原地升级：完整 V10 历史链无需清理即升级成功并原样保留；V11 台账与首次销售标记约束在历史数据上全部生效")
    void upgrade_fromRealisticV10History() throws Exception {
        migrateTo("10");
        seedV10History();
        String batchesBefore = queryString("SELECT GROUP_CONCAT(CONCAT_WS(':', id, org_id, flow_status, risk_status, quantity, "
                + "IFNULL(produced_by_operation_id, '-'), IFNULL(consumed_by_operation_id, '-'), version) ORDER BY id) FROM batch");

        migrateTo("11");

        // 1. 升级成功、历史原样保留、不回填
        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '11' AND success = 1")).isEqualTo(1);
        assertThat(queryString("SELECT GROUP_CONCAT(CONCAT_WS(':', id, org_id, flow_status, risk_status, quantity, "
                + "IFNULL(produced_by_operation_id, '-'), IFNULL(consumed_by_operation_id, '-'), version) ORDER BY id) FROM batch"))
                .isEqualTo(batchesBefore);
        assertThat(queryLong("SELECT count(*) FROM batch WHERE first_sale_id IS NOT NULL")).isZero();
        assertThat(queryLong("SELECT count(*) FROM organization")).isEqualTo(5);
        assertThat(queryLong("SELECT count(*) FROM site")).isEqualTo(6);
        assertThat(queryLong("SELECT count(*) FROM batch_operation WHERE status = 'SUBMITTED'")).isEqualTo(2);
        assertThat(queryLong("SELECT count(*) FROM batch_operation_item")).isEqualTo(7);
        assertThat(queryLong("SELECT count(*) FROM batch_relation")).isEqualTo(3);
        assertThat(queryLong("SELECT count(*) FROM shipment WHERE status = 'DELIVERED'")).isEqualTo(2);
        assertThat(queryLong("SELECT count(*) FROM transfer WHERE status = 'ACCEPTED'")).isEqualTo(4);
        assertThat(queryLong("SELECT count(*) FROM transfer WHERE is_legacy = 1")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM trace_event")).isEqualTo(5);
        assertThat(queryLong("SELECT count(*) FROM public_trace_code")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM sale")).isZero();
        // site (id, org_id) 唯一键在已有场所上建立成功
        assertThat(queryLong("SELECT count(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() "
                + "AND table_name = 'site' AND constraint_name = 'uk_site_id_org' AND constraint_type = 'UNIQUE'")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'batch' AND column_name = 'first_sale_id' AND is_nullable = 'YES'")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'sale' AND column_name = 'occurred_at' AND datetime_precision = 6")).isEqualTo(1);
        // 历史批次在 first_sale_id 为 NULL 时仍可正常更新（例如既有乐观锁版本递增）
        exec("UPDATE batch SET version = version + 1 WHERE id = 904");

        // 2. sale 台账字段约束
        assertThatThrownBy(() -> insertSale(1, 701, 901, 801, "0", "k-zero-000000000001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_sale_quantity");
        assertThatThrownBy(() -> exec("INSERT INTO sale (org_id, batch_id, site_id, quantity, unit_code, occurred_at, idempotency_key, request_hash, created_by) VALUES (701, 901, 801, 1, 'box', NOW(6), 'k-unit-000000000001', REPEAT('a', 64), 1)"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_sale_unit_code");
        assertThatThrownBy(() -> exec("INSERT INTO sale (org_id, batch_id, site_id, quantity, occurred_at, status, idempotency_key, request_hash, created_by) VALUES (701, 901, 801, 1, NOW(6), 'VOID', 'k-void-000000000001', REPEAT('a', 64), 1)"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_sale_status");
        // 场所必须属于销售组织（复合外键）
        assertThatThrownBy(() -> insertSale(2, 701, 901, 802, "1", "k-site-000000000001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_sale_site_org");
        assertThatThrownBy(() -> insertSale(3, 701, 999999, 801, "1", "k-batch-00000000001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_sale_batch");

        // 3. 规范首次销售：已接受交接的 B2(901) 由零售 A 在门店 A 首售 200，回写 first_sale_id 成功
        insertSale(1001, 701, 901, 801, "200", "k-a1-00000000000001");
        exec("UPDATE batch SET first_sale_id = COALESCE(first_sale_id, 1001), version = version + 1 WHERE id = 901");
        assertThat(queryLong("SELECT first_sale_id FROM batch WHERE id = 901")).isEqualTo(1001);
        // 组织内幂等键唯一
        assertThatThrownBy(() -> insertSale(1002, 701, 901, 801, "1", "k-a1-00000000000001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("uk_sale_org_idempotency");
        // 后续销售保持原始 first_sale_id（与应用层 COALESCE 回写一致），售罄关闭
        insertSale(1003, 701, 901, 801, "400", "k-a2-00000000000001");
        exec("UPDATE batch SET first_sale_id = COALESCE(first_sale_id, 1003), flow_status = 'CLOSED', version = version + 1 WHERE id = 901");
        assertThat(queryLong("SELECT first_sale_id FROM batch WHERE id = 901")).isEqualTo(1001);
        assertThat(queryString("SELECT flow_status FROM batch WHERE id = 901")).isEqualTo("CLOSED");

        // 4. 跨批次：B3(902) 不能指向 B2 的 Sale
        assertThatThrownBy(() -> exec("UPDATE batch SET first_sale_id = 1001 WHERE id = 902"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_batch_first_sale");
        // 跨组织：零售 B 对其 903 的 Sale 不能被零售 A 的 B3 引用
        insertSale(1004, 702, 903, 802, "10", "k-c1-00000000000001");
        assertThatThrownBy(() -> exec("UPDATE batch SET first_sale_id = 1004 WHERE id = 902"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_batch_first_sale");
        // 同批次但销售行记录为另一组织：B3 仍不能指向它
        insertSale(1005, 702, 902, 802, "10", "k-b-foreign-0000001");
        assertThatThrownBy(() -> exec("UPDATE batch SET first_sale_id = 1005 WHERE id = 902"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_batch_first_sale");
        assertThatThrownBy(() -> exec("UPDATE batch SET first_sale_id = 999999 WHERE id = 902"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_batch_first_sale");
        assertThat(queryLong("SELECT count(*) FROM batch WHERE id = 902 AND first_sale_id IS NULL")).isEqualTo(1);

        // 5. 已被操作全量消耗的历史批次 B1(951) 不能再获得首次销售标记
        insertSale(1006, 730, 951, 812, "1", "k-b1-00000000000001");
        assertThatThrownBy(() -> exec("UPDATE batch SET first_sale_id = 1006 WHERE id = 951"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_batch_sale_consumption_exclusive");

        // 6. 零售 B 的 903 正常首售后：责任组织不能再变更、不能回到 DRAFT、不能再被操作全量消耗
        exec("UPDATE batch SET first_sale_id = 1004 WHERE id = 903");
        assertThatThrownBy(() -> exec("UPDATE batch SET org_id = 701 WHERE id = 903"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_batch_first_sale");
        assertThatThrownBy(() -> exec("UPDATE batch SET flow_status = 'DRAFT' WHERE id = 903"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_batch_sale_not_draft");
        assertThatThrownBy(() -> exec("UPDATE batch SET flow_status = 'CLOSED', consumed_by_operation_id = 602 WHERE id = 903"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_batch_sale_consumption_exclusive");

        // 7. 未开始销售的批次，既有交接接受时的责任组织转移不受 V11 影响（first_sale_id 为 NULL 时复合外键不参与校验）
        exec("UPDATE batch SET org_id = 702, version = version + 1 WHERE id = 902");
        assertThat(queryLong("SELECT org_id FROM batch WHERE id = 902")).isEqualTo(702);
    }
}
