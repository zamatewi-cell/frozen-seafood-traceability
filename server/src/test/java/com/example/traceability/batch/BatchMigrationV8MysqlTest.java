package com.example.traceability.batch;

import com.example.traceability.common.config.TraceBatchNoHistoryPepperFlywayCallback;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 0 Task 1A & 1C: V8 数据库迁移与历史兼容性测试套件 (TDD)。
 * <p>
 * 契约规范：
 * 1. batch 新增 trace_batch_no、external_batch_no、flow_status、risk_status；
 *    external_batch_no 回填旧 batch_no，可空且可重复；trace_batch_no NOT NULL UNIQUE。
 * 2. 旧状态映射：
 *    DRAFT->DRAFT/NORMAL；ACTIVE->ACTIVE/NORMAL；CLOSED->CLOSED/NORMAL；
 *    FROZEN->ACTIVE/FROZEN；RECALLED->ACTIVE/RECALLED。
 *    旧 RECALLED 只证明存在召回风险，不能证明数量已耗尽或流转生命周期已关闭，
 *    因此按 DEMO_MVP_ROADMAP.md §4.1 映射为 ACTIVE/RECALLED，由 riskStatus 阻断正常业务写入。
 * 3. 历史 trace_batch_no 使用 SHA2(secretPepper + domainSeparator + historicalBatchId, 256) 的不可逆确定性摘要；
 *    格式 TB-H- + 64 位小写十六进制散列。不得拼入明文 id，不得使用 org_id 或 batch_no，
 *    且 pepper 绝不硬编码于 Java / SQL / Git 受控文件；由 Flyway 回调注入 MySQL 会话变量。
 *    相同 pepper + 相同历史 id 在不同干净库中结果完全一致；不同 pepper 结果必须不同。
 * 4. 删除旧 uk_batch_org_no、旧 chk_batch_status，再删除旧 batch_no/status 列。
 *    增加 flow/risk 各自枚举检查和组合检查，明确拒绝 DRAFT+FROZEN 与 DRAFT+RECALLED。
 * 5. PublicTraceCode 的 public_id/token_hash/batch_id/org_id 字段保持稳定，不改动、不漂移；
 *    插入时符合 V6 规范（org_id NOT NULL，public_id 长度合规）。
 * 6. V1-V7 文件 checksum/内容绝不改变；V8 的 Flyway checksum 不随 pepper 变化。
 * 7. 必须同时验证空库 V1->V8 和预先 migrate 到 V7 后插入五种状态历史数据再 migrate 到 V8。
 * 8. 新增 creation_org_id（创建组织，不可变，仅服务创建幂等域与审计），回填为迁移时的 org_id；
 *    删除旧 uk_batch_org_idempotency，改建 UNIQUE(creation_org_id, creation_idempotency_key)。
 * </p>
 */
public class BatchMigrationV8MysqlTest {

    public static final String DOMAIN_SEPARATION_PREFIX = "domain:seafood:trace:batch:v8:history:";

    /** V8 引用的 MySQL 会话变量名（真实 pepper 由 Flyway 回调在同一连接上注入）。 */
    public static final String PEPPER_SESSION_VARIABLE = "trace_batch_no_history_pepper";

    /** 注入 pepper 的环境变量名（真实值只存在于本地 .env / CI secret，绝不进入 Git）。 */
    public static final String PEPPER_ENV_VARIABLE = "TRACE_BATCH_NO_HISTORY_PEPPER";

    /**
     * 测试专用 pepper（仅存在于测试进程内存，不是生产密钥，也绝不写入迁移历史或日志）。
     * <p>
     * 长度均已满足回调要求的"UTF-8 编码后不少于 32 字节"。
     * </p>
     */
    public static final String TEST_PEPPER_A = "unit-test-pepper-aaaaaaaaaaaaaaaaaaaa";
    public static final String TEST_PEPPER_B = "unit-test-pepper-bbbbbbbbbbbbbbbbbbbb";

    /** 不合格的 pepper：null、空串、纯空白、已公开的示例占位值、UTF-8 不足 32 字节的弱值。 */
    public static final String[] UNACCEPTABLE_PEPPERS = {
            null,
            "",
            "   ",
            TraceBatchNoHistoryPepperFlywayCallback.PLACEHOLDER_PEPPER,
            "too-short-pepper-only-31-bytes!"
    };

    /**
     * 计算历史批次期望生成的确定性 trace_batch_no。
     * <p>
     * 与 V8 SQL 中 {@code SHA2(CONCAT(@trace_batch_no_history_pepper, '<domain>', CAST(id AS CHAR)), 256)} 完全一致：
     * 摘要输入为 secretPepper + domainSeparator + historicalBatchId。
     * </p>
     */
    public static String computeHistoricalTraceBatchNo(String pepper, long id) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            String payload = pepper + DOMAIN_SEPARATION_PREFIX + id;
            byte[] digest = sha256.digest(payload.getBytes(StandardCharsets.UTF_8));
            return "TB-H-" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("计算 SHA-256 摘要失败", e);
        }
    }

    /**
     * 历史（已被废弃）的公开固定前缀枚举算法：SHA-256(domainSeparator + id)。
     * <p>
     * 仅用于回归断言"旧的可离线枚举算法不得再产出当前结果"。
     * </p>
     */
    public static String computeLegacyEnumerableTraceBatchNo(long id) {
        return computeHistoricalTraceBatchNo("", id);
    }

    /**
     * 第一部分：离线可运行的静态契约校验与算法确定性测试（不依赖外部运行中的数据库）。
     */
    @Nested
    @DisplayName("V8 迁移脚本静态契约与确定性哈希算法测试")
    class StaticContractAndAlgorithmTests {

        private static final Path MIGRATION_DIR = Paths.get("src/main/resources/db/migration");

        @Test
        @DisplayName("契约6：V1~V7迁移脚本Checksum绝不改变")
        void testV1ToV7ChecksumsRemainUnchanged() throws Exception {
            Map<String, String> expectedHashes = Map.of(
                    "V1__init_schema.sql", "E639F923BB6ADF4EAD58AEB1C647B6575F4696C7E6DD77122F08D314AEE97C0C",
                    "V2__product_rule_constraints.sql", "00A87F65719809D4D6362E8C6B20AB5186C29FFED5FE1EB13986D49D1CA3A9DC",
                    "V3__batch_constraints.sql", "484E05EFBA71232D0367838D894F3E22C1C2C2F3D44D1E8EC09F2FCDE5207417",
                    "V4__batch_operation_constraints.sql", "B24B5A549A2FF45CC17412ACDCD07A6ECA01342C91037AC64232114BFBF11B59",
                    "V5__trace_event_constraints.sql", "E7EA6AA4B77741F4087D1CE4AF64FD73DCEF5163877974E652B4047B41B971DF",
                    "V6__public_trace_code_constraints.sql", "062B1EDCF44E5C89FFA3A0363B8340170CBD01F9BAB056C9DB551387803D7B42",
                    "V7__transfer_constraints.sql", "A87C3026E2BF7D1C00AE131BD1171E5E5D19C6C32A6594DD76C13671D83C94C8"
            );

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> entry : expectedHashes.entrySet()) {
                Path filePath = MIGRATION_DIR.resolve(entry.getKey());
                assertThat(Files.exists(filePath))
                        .as("Migration file %s must exist", entry.getKey())
                        .isTrue();

                byte[] fileBytes = Files.readAllBytes(filePath);
                byte[] hash = md.digest(fileBytes);
                String actualHex = HexFormat.of().formatHex(hash).toUpperCase();
                assertThat(actualHex)
                        .as("Checksum of %s must remain strictly unchanged", entry.getKey())
                        .isEqualTo(entry.getValue());
            }
        }

        @Test
        @DisplayName("契约1,3,4,5：V8迁移脚本存在且严格符合SQL契约规范")
        void testV8MigrationScriptContentAndContract() throws Exception {
            Path v8File = MIGRATION_DIR.resolve("V8__batch_dual_status_and_identifiers.sql");
            assertThat(Files.exists(v8File))
                    .as("V8 migration script V8__batch_dual_status_and_identifiers.sql must exist")
                    .isTrue();

            String sql = Files.readString(v8File, StandardCharsets.UTF_8);

            // 1. 新增字段检查
            assertThat(sql).contains("trace_batch_no");
            assertThat(sql).contains("external_batch_no");
            assertThat(sql).contains("flow_status");
            assertThat(sql).contains("risk_status");

            // 2. 状态映射检查
            assertThat(sql).contains("DRAFT");
            assertThat(sql).contains("ACTIVE");
            assertThat(sql).contains("CLOSED");
            assertThat(sql).contains("NORMAL");
            assertThat(sql).contains("FROZEN");
            assertThat(sql).contains("RECALLED");

            // 2.1 旧 RECALLED 必须映射为 ACTIVE（不再映射为 CLOSED）
            String flowCaseBlock = sql.substring(sql.indexOf("`flow_status` = CASE"), sql.indexOf("`risk_status` = CASE"));
            assertThat(flowCaseBlock)
                    .as("历史 RECALLED 只证明存在召回风险，不能证明流转已关闭，必须映射为 ACTIVE")
                    .contains("WHEN 'RECALLED' THEN 'ACTIVE'")
                    .doesNotContain("WHEN 'RECALLED' THEN 'CLOSED'");

            // 3. 历史 trace_batch_no 确定性摘要生成检查 (不得拼入明文 id，不得使用 org_id 或 batch_no)
            assertThat(sql).contains("TB-H-");
            assertThat(sql).contains("SHA2");
            assertThat(sql).contains(DOMAIN_SEPARATION_PREFIX);
            assertThat(sql).doesNotContain("CONCAT('TB-H-', id)");
            assertThat(sql).doesNotContain("CONCAT('TB-H-', `id`)");

            // 3.1 摘要必须由会话变量注入的 pepper 参与，且 pepper 绝不硬编码在版本化 SQL 中
            assertThat(sql)
                    .as("V8 只能引用固定的会话变量名，真实 pepper 由 Flyway 回调在同一连接上注入")
                    .contains("@" + PEPPER_SESSION_VARIABLE);
            assertThat(sql).doesNotContain(TEST_PEPPER_A);
            assertThat(sql).doesNotContain(TEST_PEPPER_B);

            // 严格检查生成 trace_batch_no 的表达式右侧逻辑 (不得引用 org_id 或 batch_no 列，且必须含 pepper 会话变量)
            String traceBatchNoUpdateLine = sql.lines()
                    .filter(line -> line.contains("`trace_batch_no` ="))
                    .findFirst()
                    .orElseThrow();
            String expression = traceBatchNoUpdateLine.substring(traceBatchNoUpdateLine.indexOf('=') + 1);
            assertThat(expression).contains("@" + PEPPER_SESSION_VARIABLE);

            // 剥离会话变量名后再校验列引用：变量名本身含有 batch_no 子串，不能按裸子串判定
            String expressionWithoutPepperVariable = expression.replace("@" + PEPPER_SESSION_VARIABLE, "@PEPPER");
            assertThat(expressionWithoutPepperVariable)
                    .as("身份摘要绝不得掺入 org_id 或企业输入的 batch_no/external_batch_no")
                    .doesNotContain("org_id")
                    .doesNotContain("batch_no");

            // 4. 旧约束与旧列删除检查
            assertThat(sql).contains("uk_batch_org_no");
            assertThat(sql).contains("chk_batch_status");
            assertThat(sql).contains("DROP COLUMN `batch_no`");
            assertThat(sql).contains("DROP COLUMN `status`");

            // 5. 新约束与新索引检查
            assertThat(sql).contains("chk_batch_flow_status");
            assertThat(sql).contains("chk_batch_risk_status");
            assertThat(sql).contains("chk_batch_status_combination");
            assertThat(sql).contains("uk_batch_trace_batch_no");

            // 5.1 创建组织与创建幂等域拆分：新增 creation_org_id，替换旧的 uk_batch_org_idempotency
            assertThat(sql).contains("creation_org_id");
            assertThat(sql).contains("DROP INDEX `uk_batch_org_idempotency`");
            assertThat(sql).contains("uk_batch_creation_org_idempotency");

            // 6. PublicTraceCode 绝不修改
            assertThat(sql.toUpperCase()).doesNotContain("PUBLIC_TRACE_CODE");
        }

        @Test
        @DisplayName("契约3：历史 trace_batch_no 摘要算法稳定确定、不含明文 id 且随 pepper 变化")
        void testDeterministicHashAlgorithmIntegrity() {
            long testId = 123456789L;
            String computed = computeHistoricalTraceBatchNo(TEST_PEPPER_A, testId);

            assertThat(computed).startsWith("TB-H-");
            assertThat(computed).hasSize(5 + 64); // 'TB-H-' + 64 hex chars
            assertThat(computed).doesNotContain(String.valueOf(testId));
            assertThat(computed).doesNotContain(TEST_PEPPER_A);

            // 相同 pepper + 相同 ID 稳定可复现
            assertThat(computed).isEqualTo(computeHistoricalTraceBatchNo(TEST_PEPPER_A, testId));

            // 不同 ID 结果不同
            assertThat(computed).isNotEqualTo(computeHistoricalTraceBatchNo(TEST_PEPPER_A, 123456790L));

            // 不同 pepper 对相同历史 ID 必须产出不同结果
            assertThat(computed)
                    .as("不同 pepper 必须使同一历史 ID 产出不同的 trace_batch_no")
                    .isNotEqualTo(computeHistoricalTraceBatchNo(TEST_PEPPER_B, testId));

            // 旧的公开固定前缀枚举算法不得再直接产出当前结果
            assertThat(computed)
                    .as("废弃的 SHA-256(domain + id) 公开枚举算法不得再产出当前结果")
                    .isNotEqualTo(computeLegacyEnumerableTraceBatchNo(testId));

            // 验证特定已知 ID 的计算稳定性
            String id1001Trace = computeHistoricalTraceBatchNo(TEST_PEPPER_A, 1001L);
            assertThat(id1001Trace).startsWith("TB-H-").hasSize(69);
            assertThat(id1001Trace).isEqualTo(computeHistoricalTraceBatchNo(TEST_PEPPER_A, 1001L));
        }

        @Test
        @DisplayName("契约3：pepper 绝不硬编码在任何 Git 受控的 Java / SQL / 配置文件中")
        void testPepperIsNeverHardcodedInTrackedSources() throws Exception {
            // application.yml 只允许引用环境变量占位符，且不得提供公开默认值回退
            Path applicationYml = Paths.get("src/main/resources/application.yml");
            String yml = Files.readString(applicationYml, StandardCharsets.UTF_8);
            assertThat(yml).contains(PEPPER_ENV_VARIABLE);
            assertThat(yml).doesNotContain(TEST_PEPPER_A);
            assertThat(yml).doesNotContain(TEST_PEPPER_B);

            // V8 versioned migration 只引用会话变量，不含任何字面量 pepper
            String v8 = Files.readString(MIGRATION_DIR.resolve("V8__batch_dual_status_and_identifiers.sql"), StandardCharsets.UTF_8);
            assertThat(v8).contains("@" + PEPPER_SESSION_VARIABLE);
            assertThat(v8).doesNotContain(PEPPER_ENV_VARIABLE + "=");
        }

        @Test
        @DisplayName("契约3：.env.example 只声明变量名不提供任何可用值，复制即用也拿不到有效 pepper")
        void testEnvExampleShipsAnEmptyPepperValue() throws Exception {
            // 测试工作目录是 server/，.env.example 位于仓库根目录
            Path envExample = Paths.get("..", ".env.example");
            assertThat(Files.exists(envExample))
                    .as(".env.example must exist at repository root")
                    .isTrue();

            String env = Files.readString(envExample, StandardCharsets.UTF_8);
            String assignment = env.lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith(PEPPER_ENV_VARIABLE + "="))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            ".env.example must declare " + PEPPER_ENV_VARIABLE));

            assertThat(assignment)
                    .as("示例文件必须只保留变量名，值留空；任何随文件公开的值都等同于无密钥")
                    .isEqualTo(PEPPER_ENV_VARIABLE + "=");
            assertThat(env)
                    .as("已公开的占位值绝不能再作为可用值出现在示例文件中")
                    .doesNotContain(TraceBatchNoHistoryPepperFlywayCallback.PLACEHOLDER_PEPPER);
            assertThat(env).doesNotContain(TEST_PEPPER_A);
            assertThat(env).doesNotContain(TEST_PEPPER_B);
        }
    }

    /**
     * 第二部分：基于真实 MySQL 8.4 的集成迁移测试。
     * 当配置了 MYSQL_IT_ENABLED=true 且数据库可用时执行。
     */
    @Nested
    @SpringBootTest
    @EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
    @DisplayName("基于真实 MySQL 8.4 数据库的 V8 升级迁移与约束验证")
    class RealMysqlMigrationTests {

        @Autowired
        private DataSource dataSource;

        /**
         * 构造带 pepper 注入回调的 Flyway 实例。
         * <p>
         * 回调在 BEFORE_EACH_MIGRATE 于 Flyway 执行迁移的同一条连接上设置会话变量，
         * V8 只引用该变量名，因此 V8 文件内容（进而其 Flyway checksum）与 pepper 完全无关。
         * </p>
         */
        private Flyway flywayTo(String url, String user, String password, String version, String pepper) {
            return Flyway.configure()
                    .dataSource(url, user, password)
                    .locations("classpath:db/migration")
                    .callbacks(new TraceBatchNoHistoryPepperFlywayCallback(pepper))
                    .target(MigrationVersion.fromVersion(version))
                    .load();
        }

        /** 读取指定版本迁移在 flyway_schema_history 中登记的 checksum。 */
        private Integer readMigrationChecksum(String url, String user, String password, String version) throws SQLException {
            try (Connection conn = DriverManager.getConnection(url, user, password);
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT checksum FROM flyway_schema_history WHERE version = ? AND success = 1")) {
                ps.setString(1, version);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("迁移版本 %s 必须已成功登记", version).isTrue();
                    return (Integer) rs.getObject("checksum");
                }
            }
        }

        @Test
        @DisplayName("契约1~7：V1->V7存量五种状态数据在临时库平滑迁移到V8并验证全量契约与精确期望比对")
        void testV7ToV8MigrationWithHistoricalFiveStatuses() throws Exception {
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            HikariDataSource hikari = (HikariDataSource) dataSource;
            String originalJdbcUrl = hikari.getJdbcUrl();

            String rootUsername = getRootUsername();
            String rootPassword = getRootPassword();

            String tempSchema = "trace_mig_test_v8_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String tempJdbcUrl = originalJdbcUrl.replaceFirst("/[a-zA-Z0-9_]+(\\?|$)", "/" + tempSchema + "$1");

            try (Connection rootConn = DriverManager.getConnection(originalJdbcUrl, rootUsername, rootPassword);
                 Statement rootStmt = rootConn.createStatement()) {

                rootStmt.execute("CREATE SCHEMA `" + tempSchema + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");

                try {
                    // 1. 运行 Flyway V1~V7 迁移
                    Flyway flywayV1toV7 = flywayTo(tempJdbcUrl, rootUsername, rootPassword, "7", TEST_PEPPER_A);
                    flywayV1toV7.migrate();

                    // 2. 插入五种状态的历史合法批次数据以及同编号重复数据
                    try (Connection tempConn = DriverManager.getConnection(tempJdbcUrl, rootUsername, rootPassword);
                         Statement tempStmt = tempConn.createStatement()) {

                        // 准备外键依赖：组织与产品
                        tempStmt.execute("""
                                INSERT INTO `organization` (`id`, `org_no`, `name`, `org_type`, `status`)
                                VALUES (101, 'ORG-SRC-001', '捕捞企业A', 'SOURCE', 'ACTIVE'),
                                       (102, 'ORG-PROC-001', '加工企业B', 'PROCESSOR', 'ACTIVE')
                                """);
                        tempStmt.execute("""
                                INSERT INTO `product` (`id`, `product_code`, `public_name`, `category`, `specification`, `source_type`)
                                VALUES (201, 'PROD-001', '冷冻白虾', 'CRUSTACEAN', '500g/盒', 'DOMESTIC_CAPTURE')
                                """);

                        // 插入五种状态的批次
                        tempStmt.execute("""
                                INSERT INTO `batch` (`id`, `org_id`, `product_id`, `batch_no`, `batch_type`, `quantity`, `unit_code`, `origin_type`, `origin_text`, `status`)
                                VALUES
                                (1001, 101, 201, 'BN-HIST-DRAFT', 'SOURCE', 100.000, 'kg', 'DOMESTIC_CAPTURE', '东海1区', 'DRAFT'),
                                (1002, 101, 201, 'BN-HIST-ACTIVE', 'SOURCE', 200.000, 'kg', 'DOMESTIC_CAPTURE', '东海2区', 'ACTIVE'),
                                (1003, 101, 201, 'BN-HIST-CLOSED', 'SOURCE', 300.000, 'kg', 'DOMESTIC_CAPTURE', '东海3区', 'CLOSED'),
                                (1004, 101, 201, 'BN-HIST-FROZEN', 'SOURCE', 400.000, 'kg', 'DOMESTIC_CAPTURE', '东海4区', 'FROZEN'),
                                (1005, 101, 201, 'BN-HIST-RECALLED', 'SOURCE', 500.000, 'kg', 'DOMESTIC_CAPTURE', '东海5区', 'RECALLED'),
                                (1006, 102, 201, 'BN-HIST-ACTIVE', 'PROCESSING', 150.000, 'kg', 'DOMESTIC_CAPTURE', '东海2区', 'ACTIVE')
                                """);

                        // 为 1002 插入一条 public_trace_code，必须严格满足 V6 约束 (org_id NOT NULL, public_id 长度 <= 40, status='ACTIVE', disabled_at=NULL)
                        tempStmt.execute("""
                                INSERT INTO `public_trace_code` (`id`, `org_id`, `batch_id`, `public_id`, `token_hash`, `status`, `disabled_at`)
                                VALUES (301, 101, 1002, 'PUB-TEST-UUID-001', 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789', 'ACTIVE', NULL)
                                """);
                    }

                    // 3. 执行 Flyway 升级到 V8
                    Flyway flywayV8 = flywayTo(tempJdbcUrl, rootUsername, rootPassword, "8", TEST_PEPPER_A);
                    int migrated = flywayV8.migrate().migrationsExecuted;
                    assertThat(migrated).isGreaterThanOrEqualTo(1);

                    // 4. 验证历史数据映射与字段保持
                    try (Connection tempConn = DriverManager.getConnection(tempJdbcUrl, rootUsername, rootPassword);
                         Statement tempStmt = tempConn.createStatement()) {

                        // 4.1 验证五种历史状态的映射
                        assertBatchStatusAndIdentifiers(tempConn, 1001L, "BN-HIST-DRAFT", "DRAFT", "NORMAL");
                        assertBatchStatusAndIdentifiers(tempConn, 1002L, "BN-HIST-ACTIVE", "ACTIVE", "NORMAL");
                        assertBatchStatusAndIdentifiers(tempConn, 1003L, "BN-HIST-CLOSED", "CLOSED", "NORMAL");
                        assertBatchStatusAndIdentifiers(tempConn, 1004L, "BN-HIST-FROZEN", "ACTIVE", "FROZEN");
                        assertBatchStatusAndIdentifiers(tempConn, 1005L, "BN-HIST-RECALLED", "ACTIVE", "RECALLED");
                        assertBatchStatusAndIdentifiers(tempConn, 1006L, "BN-HIST-ACTIVE", "ACTIVE", "NORMAL");

                        // 4.2 验证数据库产生的历史 trace_batch_no 与测试端固定 domain+SHA256 的期望值精确比较 (isEqualTo)
                        List<Long> historicalIds = List.of(1001L, 1002L, 1003L, 1004L, 1005L, 1006L);
                        for (Long id : historicalIds) {
                            try (PreparedStatement ps = tempConn.prepareStatement("SELECT trace_batch_no FROM batch WHERE id = ?")) {
                                ps.setLong(1, id);
                                try (ResultSet rs = ps.executeQuery()) {
                                    assertThat(rs.next()).as("Batch with id %d must exist", id).isTrue();
                                    String actualTraceBatchNo = rs.getString("trace_batch_no");
                                    String expectedTraceBatchNo = computeHistoricalTraceBatchNo(TEST_PEPPER_A, id);

                                    // 精确 expected 比较
                                    assertThat(actualTraceBatchNo)
                                            .as("Historical trace_batch_no for id %d must exactly match SHA256 deterministic hash", id)
                                            .isEqualTo(expectedTraceBatchNo);

                                    // 格式与不含明文检查
                                    assertThat(actualTraceBatchNo).startsWith("TB-H-").hasSize(5 + 64);
                                    assertThat(actualTraceBatchNo).doesNotContain(String.valueOf(id));
                                }
                            }
                        }

                        // 4.3 验证旧字段 batch_no, status 已经从 batch 表中被物理删除
                        DatabaseMetaData metaData = tempConn.getMetaData();
                        try (ResultSet columns = metaData.getColumns(tempSchema, null, "batch", "batch_no")) {
                            assertThat(columns.next()).as("Column batch_no must be dropped").isFalse();
                        }
                        try (ResultSet columns = metaData.getColumns(tempSchema, null, "batch", "status")) {
                            assertThat(columns.next()).as("Column status must be dropped").isFalse();
                        }

                        // 4.4 验证 external_batch_no 允许在同一组织内重复插入
                        tempStmt.execute("""
                                INSERT INTO `batch` (`id`, `org_id`, `creation_org_id`, `product_id`, `trace_batch_no`, `external_batch_no`, `batch_type`, `quantity`, `unit_code`, `origin_type`, `origin_text`, `flow_status`, `risk_status`)
                                VALUES (1007, 101, 101, 201, 'TB-NEW-001', 'BN-HIST-DRAFT', 'SOURCE', 50.000, 'kg', 'DOMESTIC_CAPTURE', '东海1区', 'DRAFT', 'NORMAL')
                                """);

                        // 4.5 验证 trace_batch_no 重复时被 UNIQUE 约束拒绝
                        assertThatThrownBy(() -> tempStmt.execute("""
                                INSERT INTO `batch` (`id`, `org_id`, `creation_org_id`, `product_id`, `trace_batch_no`, `external_batch_no`, `batch_type`, `quantity`, `unit_code`, `origin_type`, `origin_text`, `flow_status`, `risk_status`)
                                VALUES (1008, 101, 101, 201, 'TB-NEW-001', 'BN-HIST-DIFF', 'SOURCE', 50.000, 'kg', 'DOMESTIC_CAPTURE', '东海1区', 'DRAFT', 'NORMAL')
                                """))
                                .isInstanceOf(SQLException.class);

                        // 4.6 验证非法组合 DRAFT + FROZEN 被 CHECK 约束拒绝
                        assertThatThrownBy(() -> tempStmt.execute("""
                                INSERT INTO `batch` (`id`, `org_id`, `creation_org_id`, `product_id`, `trace_batch_no`, `external_batch_no`, `batch_type`, `quantity`, `unit_code`, `origin_type`, `origin_text`, `flow_status`, `risk_status`)
                                VALUES (1009, 101, 101, 201, 'TB-NEW-002', 'BN-INVALID-1', 'SOURCE', 50.000, 'kg', 'DOMESTIC_CAPTURE', '东海1区', 'DRAFT', 'FROZEN')
                                """))
                                .isInstanceOf(SQLException.class);

                        // 4.7 验证非法组合 DRAFT + RECALLED 被 CHECK 约束拒绝
                        assertThatThrownBy(() -> tempStmt.execute("""
                                INSERT INTO `batch` (`id`, `org_id`, `creation_org_id`, `product_id`, `trace_batch_no`, `external_batch_no`, `batch_type`, `quantity`, `unit_code`, `origin_type`, `origin_text`, `flow_status`, `risk_status`)
                                VALUES (1010, 101, 101, 201, 'TB-NEW-003', 'BN-INVALID-2', 'SOURCE', 50.000, 'kg', 'DOMESTIC_CAPTURE', '东海1区', 'DRAFT', 'RECALLED')
                                """))
                                .isInstanceOf(SQLException.class);

                        // 4.8 验证 public_trace_code 的 public_id/token_hash/batch_id 及 org_id 原样保留未漂移
                        try (ResultSet rs = tempStmt.executeQuery("SELECT * FROM `public_trace_code` WHERE `id` = 301")) {
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getString("public_id")).isEqualTo("PUB-TEST-UUID-001");
                            assertThat(rs.getString("public_id").length()).isLessThanOrEqualTo(40);
                            assertThat(rs.getString("token_hash")).isEqualTo("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
                            assertThat(rs.getLong("batch_id")).isEqualTo(1002L);
                            assertThat(rs.getLong("org_id")).isEqualTo(101L);
                            assertThat(rs.getString("status")).isEqualTo("ACTIVE");
                            assertThat(rs.getTimestamp("disabled_at")).isNull();
                        }

                        // 4.9 creation_org_id 回填为迁移时刻的 org_id，且已成为 NOT NULL
                        assertCreationOrgId(tempConn, 1001L, 101L);
                        assertCreationOrgId(tempConn, 1005L, 101L);
                        assertCreationOrgId(tempConn, 1006L, 102L);
                        try (ResultSet rs = metaData.getColumns(tempSchema, null, "batch", "creation_org_id")) {
                            assertThat(rs.next()).as("Column creation_org_id must exist").isTrue();
                            assertThat(rs.getString("IS_NULLABLE")).isEqualTo("NO");
                        }

                        // 4.10 旧的 uk_batch_org_idempotency 必须消失，新的创建幂等域唯一键必须存在
                        assertThat(readIndexNames(tempConn, tempSchema))
                                .as("以可转移的 org_id 为作用域的旧创建幂等唯一键必须被移除")
                                .doesNotContain("uk_batch_org_idempotency")
                                .contains("uk_batch_creation_org_idempotency");

                        // 4.11 创建幂等域生效：同一 creation_org_id 下重复 creation_idempotency_key 被拒绝
                        tempStmt.execute("""
                                INSERT INTO `batch` (`id`, `org_id`, `creation_org_id`, `product_id`, `trace_batch_no`, `external_batch_no`, `batch_type`, `quantity`, `unit_code`, `origin_type`, `origin_text`, `flow_status`, `risk_status`, `creation_idempotency_key`)
                                VALUES (1011, 101, 101, 201, 'TB-IDEM-001', 'BN-IDEM', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '东海1区', 'DRAFT', 'NORMAL', 'idem-key-scope-0001')
                                """);
                        assertThatThrownBy(() -> tempStmt.execute("""
                                INSERT INTO `batch` (`id`, `org_id`, `creation_org_id`, `product_id`, `trace_batch_no`, `external_batch_no`, `batch_type`, `quantity`, `unit_code`, `origin_type`, `origin_text`, `flow_status`, `risk_status`, `creation_idempotency_key`)
                                VALUES (1012, 101, 101, 201, 'TB-IDEM-002', 'BN-IDEM', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '东海1区', 'DRAFT', 'NORMAL', 'idem-key-scope-0001')
                                """))
                                .isInstanceOf(SQLException.class);

                        // 4.12 不同 creation_org_id 可持有相同 creation_idempotency_key：
                        //      这正是"接收方已有同名幂等键也不会阻断 Transfer ACCEPT"的物理基础
                        tempStmt.execute("""
                                INSERT INTO `batch` (`id`, `org_id`, `creation_org_id`, `product_id`, `trace_batch_no`, `external_batch_no`, `batch_type`, `quantity`, `unit_code`, `origin_type`, `origin_text`, `flow_status`, `risk_status`, `creation_idempotency_key`)
                                VALUES (1013, 102, 102, 201, 'TB-IDEM-003', 'BN-IDEM', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '东海1区', 'DRAFT', 'NORMAL', 'idem-key-scope-0001')
                                """);

                        // 4.13 交接只改 org_id，绝不改 creation_org_id
                        tempStmt.execute("UPDATE `batch` SET `org_id` = 102 WHERE `id` = 1011");
                        assertCreationOrgId(tempConn, 1011L, 101L);
                        try (PreparedStatement ps = tempConn.prepareStatement("SELECT org_id FROM batch WHERE id = 1011")) {
                            try (ResultSet rs = ps.executeQuery()) {
                                assertThat(rs.next()).isTrue();
                                assertThat(rs.getLong("org_id")).isEqualTo(102L);
                            }
                        }

                        // 4.14 creation_idempotency_key 为 NULL 时仍可多行并存（保持 V3 的 MySQL NULL 语义）
                        tempStmt.execute("""
                                INSERT INTO `batch` (`id`, `org_id`, `creation_org_id`, `product_id`, `trace_batch_no`, `external_batch_no`, `batch_type`, `quantity`, `unit_code`, `origin_type`, `origin_text`, `flow_status`, `risk_status`, `creation_idempotency_key`)
                                VALUES (1014, 101, 101, 201, 'TB-IDEM-NULL-1', 'BN-IDEM', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '东海1区', 'DRAFT', 'NORMAL', NULL),
                                       (1015, 101, 101, 201, 'TB-IDEM-NULL-2', 'BN-IDEM', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '东海1区', 'DRAFT', 'NORMAL', NULL)
                                """);

                        // 4.15 creation_org_id 外键生效：不存在的组织必须被拒绝
                        assertThatThrownBy(() -> tempStmt.execute("""
                                INSERT INTO `batch` (`id`, `org_id`, `creation_org_id`, `product_id`, `trace_batch_no`, `external_batch_no`, `batch_type`, `quantity`, `unit_code`, `origin_type`, `origin_text`, `flow_status`, `risk_status`)
                                VALUES (1016, 101, 999999, 201, 'TB-FK-BAD', 'BN-FK', 'SOURCE', 10.000, 'kg', 'DOMESTIC_CAPTURE', '东海1区', 'DRAFT', 'NORMAL')
                                """))
                                .isInstanceOf(SQLException.class);
                    }
                } finally {
                    rootStmt.execute("DROP SCHEMA IF EXISTS `" + tempSchema + "`");
                }
            }
        }

        @Test
        @DisplayName("契约3扩展：两个隔离 Schema 迁移同一历史 ID 验证生成结果绝对稳定一致")
        void testHistoricalTraceBatchNoStabilityAcrossTwoSchemas() throws Exception {
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            HikariDataSource hikari = (HikariDataSource) dataSource;
            String originalJdbcUrl = hikari.getJdbcUrl();

            String rootUsername = getRootUsername();
            String rootPassword = getRootPassword();

            String schemaA = "trace_mig_stab_a_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String schemaB = "trace_mig_stab_b_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String urlA = originalJdbcUrl.replaceFirst("/[a-zA-Z0-9_]+(\\?|$)", "/" + schemaA + "$1");
            String urlB = originalJdbcUrl.replaceFirst("/[a-zA-Z0-9_]+(\\?|$)", "/" + schemaB + "$1");

            try (Connection rootConn = DriverManager.getConnection(originalJdbcUrl, rootUsername, rootPassword);
                 Statement rootStmt = rootConn.createStatement()) {

                rootStmt.execute("CREATE SCHEMA `" + schemaA + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                rootStmt.execute("CREATE SCHEMA `" + schemaB + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");

                try {
                    // 分别对两个 Schema 运行 V1~V7 迁移，插入相同的历史数据，再升级到 V8
                    for (String url : List.of(urlA, urlB)) {
                        Flyway flywayV7 = flywayTo(url, rootUsername, rootPassword, "7", TEST_PEPPER_A);
                        flywayV7.migrate();

                        try (Connection conn = DriverManager.getConnection(url, rootUsername, rootPassword);
                             Statement stmt = conn.createStatement()) {
                            stmt.execute("INSERT INTO `organization` (`id`, `org_no`, `name`, `org_type`, `status`) VALUES (88, 'ORG-88', '稳定测试企业', 'SOURCE', 'ACTIVE')");
                            stmt.execute("INSERT INTO `product` (`id`, `product_code`, `public_name`, `category`, `specification`, `source_type`) VALUES (888, 'PROD-888', '稳定测试鱼', 'FISH', '1kg', 'DOMESTIC_CAPTURE')");
                            stmt.execute("INSERT INTO `batch` (`id`, `org_id`, `product_id`, `batch_no`, `batch_type`, `quantity`, `unit_code`, `origin_type`, `origin_text`, `status`) VALUES (5555, 88, 888, 'BN-STABLE-5555', 'SOURCE', 50.000, 'kg', 'DOMESTIC_CAPTURE', '海域', 'ACTIVE')");
                        }

                        Flyway flywayV8 = flywayTo(url, rootUsername, rootPassword, "8", TEST_PEPPER_A);
                        flywayV8.migrate();
                    }

                    // 查询两个 Schema 中历史 ID 5555 的 trace_batch_no
                    String traceA;
                    try (Connection connA = DriverManager.getConnection(urlA, rootUsername, rootPassword);
                         Statement stmtA = connA.createStatement();
                         ResultSet rsA = stmtA.executeQuery("SELECT trace_batch_no FROM batch WHERE id = 5555")) {
                        assertThat(rsA.next()).isTrue();
                        traceA = rsA.getString("trace_batch_no");
                    }

                    String traceB;
                    try (Connection connB = DriverManager.getConnection(urlB, rootUsername, rootPassword);
                         Statement stmtB = connB.createStatement();
                         ResultSet rsB = stmtB.executeQuery("SELECT trace_batch_no FROM batch WHERE id = 5555")) {
                        assertThat(rsB.next()).isTrue();
                        traceB = rsB.getString("trace_batch_no");
                    }

                    String expectedTrace = computeHistoricalTraceBatchNo(TEST_PEPPER_A, 5555L);
                    assertThat(traceA)
                            .as("Trace batch no in schema A must match expected deterministic hash")
                            .isEqualTo(expectedTrace);
                    assertThat(traceB)
                            .as("Trace batch no in schema B must match expected deterministic hash")
                            .isEqualTo(expectedTrace);
                    assertThat(traceA)
                            .as("Trace batch no must be identical across isolated schemas for the same historical id")
                            .isEqualTo(traceB);

                    // 结果既不含明文 id，也不等于已废弃的公开前缀枚举算法结果
                    assertThat(traceA).doesNotContain("5555");
                    assertThat(traceA).isNotEqualTo(computeLegacyEnumerableTraceBatchNo(5555L));

                } finally {
                    rootStmt.execute("DROP SCHEMA IF EXISTS `" + schemaA + "`");
                    rootStmt.execute("DROP SCHEMA IF EXISTS `" + schemaB + "`");
                }
            }
        }

        @Test
        @DisplayName("契约3+6：不同 pepper 对同一历史 ID 产出不同 trace_batch_no，但 V8 的 Flyway checksum 完全不变")
        void testDifferentPepperChangesDigestButNeverV8Checksum() throws Exception {
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            HikariDataSource hikari = (HikariDataSource) dataSource;
            String originalJdbcUrl = hikari.getJdbcUrl();

            String rootUsername = getRootUsername();
            String rootPassword = getRootPassword();

            String schemaA = "trace_mig_pep_a_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String schemaB = "trace_mig_pep_b_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String urlA = originalJdbcUrl.replaceFirst("/[a-zA-Z0-9_]+(\\?|$)", "/" + schemaA + "$1");
            String urlB = originalJdbcUrl.replaceFirst("/[a-zA-Z0-9_]+(\\?|$)", "/" + schemaB + "$1");

            try (Connection rootConn = DriverManager.getConnection(originalJdbcUrl, rootUsername, rootPassword);
                 Statement rootStmt = rootConn.createStatement()) {

                rootStmt.execute("CREATE SCHEMA `" + schemaA + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                rootStmt.execute("CREATE SCHEMA `" + schemaB + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");

                try {
                    // 两个隔离 schema 使用完全相同的历史数据，但注入不同的 pepper
                    List<String> urls = List.of(urlA, urlB);
                    List<String> peppers = List.of(TEST_PEPPER_A, TEST_PEPPER_B);
                    for (int i = 0; i < urls.size(); i++) {
                        String url = urls.get(i);
                        String pepper = peppers.get(i);

                        flywayTo(url, rootUsername, rootPassword, "7", pepper).migrate();

                        try (Connection conn = DriverManager.getConnection(url, rootUsername, rootPassword);
                             Statement stmt = conn.createStatement()) {
                            stmt.execute("INSERT INTO `organization` (`id`, `org_no`, `name`, `org_type`, `status`) VALUES (77, 'ORG-77', 'pepper 测试企业', 'SOURCE', 'ACTIVE')");
                            stmt.execute("INSERT INTO `product` (`id`, `product_code`, `public_name`, `category`, `specification`, `source_type`) VALUES (777, 'PROD-777', 'pepper 测试鱼', 'FISH', '1kg', 'DOMESTIC_CAPTURE')");
                            stmt.execute("INSERT INTO `batch` (`id`, `org_id`, `product_id`, `batch_no`, `batch_type`, `quantity`, `unit_code`, `origin_type`, `origin_text`, `status`) VALUES (6666, 77, 777, 'BN-PEPPER-6666', 'SOURCE', 50.000, 'kg', 'DOMESTIC_CAPTURE', '海域', 'ACTIVE')");
                        }

                        flywayTo(url, rootUsername, rootPassword, "8", pepper).migrate();
                    }

                    String traceA = readTraceBatchNo(urlA, rootUsername, rootPassword, 6666L);
                    String traceB = readTraceBatchNo(urlB, rootUsername, rootPassword, 6666L);

                    // 不同 pepper => 同一历史 ID 产出不同摘要，且各自与测试端算法精确一致
                    assertThat(traceA).isEqualTo(computeHistoricalTraceBatchNo(TEST_PEPPER_A, 6666L));
                    assertThat(traceB).isEqualTo(computeHistoricalTraceBatchNo(TEST_PEPPER_B, 6666L));
                    assertThat(traceA)
                            .as("不同 pepper 必须使同一历史 ID 产出不同的 trace_batch_no")
                            .isNotEqualTo(traceB);

                    // 关键：V8 的 Flyway checksum 必须与 pepper 完全无关
                    Integer checksumA = readMigrationChecksum(urlA, rootUsername, rootPassword, "8");
                    Integer checksumB = readMigrationChecksum(urlB, rootUsername, rootPassword, "8");
                    assertThat(checksumA)
                            .as("V8 versioned migration 只引用会话变量名，其 checksum 绝不能随 pepper 漂移")
                            .isEqualTo(checksumB);

                    // V1~V7 的 checksum 同样必须两库一致（证明未因本次改造被触碰）
                    for (String version : List.of("1", "2", "3", "4", "5", "6", "7")) {
                        assertThat(readMigrationChecksum(urlA, rootUsername, rootPassword, version))
                                .as("V%s checksum must be identical across environments", version)
                                .isEqualTo(readMigrationChecksum(urlB, rootUsername, rootPassword, version));
                    }

                    // pepper 绝不出现在迁移历史中
                    try (Connection conn = DriverManager.getConnection(urlA, rootUsername, rootPassword);
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT GROUP_CONCAT(CONCAT_WS('|', version, description, script, installed_by) SEPARATOR '##') AS history FROM flyway_schema_history")) {
                        assertThat(rs.next()).isTrue();
                        String history = rs.getString("history");
                        assertThat(history).doesNotContain(TEST_PEPPER_A);
                        assertThat(history).doesNotContain(TEST_PEPPER_B);
                    }

                } finally {
                    rootStmt.execute("DROP SCHEMA IF EXISTS `" + schemaA + "`");
                    rootStmt.execute("DROP SCHEMA IF EXISTS `" + schemaB + "`");
                }
            }
        }

        @Test
        @DisplayName("契约3：pepper 缺失/空白/仍为公开占位值/强度不足时 V8 必须快速失败，绝不回退到公开默认算法")
        void testMigrationFailsFastWhenPepperMissingOrBlank() throws Exception {
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            HikariDataSource hikari = (HikariDataSource) dataSource;
            String originalJdbcUrl = hikari.getJdbcUrl();

            String rootUsername = getRootUsername();
            String rootPassword = getRootPassword();

            for (String unacceptablePepper : UNACCEPTABLE_PEPPERS) {
                String tempSchema = "trace_mig_nopep_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                String tempJdbcUrl = originalJdbcUrl.replaceFirst("/[a-zA-Z0-9_]+(\\?|$)", "/" + tempSchema + "$1");

                try (Connection rootConn = DriverManager.getConnection(originalJdbcUrl, rootUsername, rootPassword);
                     Statement rootStmt = rootConn.createStatement()) {

                    rootStmt.execute("CREATE SCHEMA `" + tempSchema + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                    try {
                        assertThatThrownBy(() -> flywayTo(tempJdbcUrl, rootUsername, rootPassword, "8", unacceptablePepper).migrate())
                                .as("不合格 pepper(%s)执行 V8 时迁移必须失败", describePepper(unacceptablePepper))
                                .isInstanceOf(FlywayException.class)
                                .hasMessageContaining(PEPPER_ENV_VARIABLE);

                        assertNoSuccessfulV8History(tempJdbcUrl, rootUsername, rootPassword);
                    } finally {
                        rootStmt.execute("DROP SCHEMA IF EXISTS `" + tempSchema + "`");
                    }
                }
            }
        }

        @Test
        @DisplayName("契约3：V1->V7 不依赖 pepper，未配置密钥时也能完整迁移到 V7")
        void testV1ToV7MigratesWithoutAnyPepper() throws Exception {
            withTemporarySchema("trace_mig_v7nopep_", (tempSchema, tempJdbcUrl, rootUsername, rootPassword) -> {
                // 完全不提供 pepper，V1~V7 必须照常成功：一次性的历史回填密钥不得成为全体迁移的强依赖
                int executed = flywayTo(tempJdbcUrl, rootUsername, rootPassword, "7", null)
                        .migrate().migrationsExecuted;
                assertThat(executed)
                        .as("V1~V7 共 7 个版本化迁移必须在无 pepper 的情况下全部成功")
                        .isEqualTo(7);

                try (Connection conn = DriverManager.getConnection(tempJdbcUrl, rootUsername, rootPassword);
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT COUNT(*) AS c FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("c")).isEqualTo(7);
                }

                // V7 结构仍是旧模型：batch_no / status 存在，V8 新列尚未出现
                assertBatchColumnPresence(tempJdbcUrl, rootUsername, rootPassword, tempSchema,
                        List.of("batch_no", "status"), List.of("trace_batch_no", "external_batch_no",
                                "flow_status", "risk_status", "creation_org_id"));
            });
        }

        @Test
        @DisplayName("契约3：已有 V7 存量数据的库在缺少合格 pepper 时执行 V8 必须失败且不留任何残留")
        void testV8OnPopulatedV7DatabaseFailsWithoutPepperAndLeavesNoResidue() throws Exception {
            for (String unacceptablePepper : UNACCEPTABLE_PEPPERS) {
                withTemporarySchema("trace_mig_v7res_", (tempSchema, tempJdbcUrl, rootUsername, rootPassword) -> {
                    flywayTo(tempJdbcUrl, rootUsername, rootPassword, "7", null).migrate();
                    seedHistoricalV7Data(tempJdbcUrl, rootUsername, rootPassword);

                    assertThatThrownBy(() -> flywayTo(tempJdbcUrl, rootUsername, rootPassword, "8", unacceptablePepper).migrate())
                            .as("存量库在 pepper(%s) 不合格时执行 V8 必须失败", describePepper(unacceptablePepper))
                            .isInstanceOf(FlywayException.class)
                            .hasMessageContaining(PEPPER_ENV_VARIABLE);

                    // 1. 不得留下成功的 V8 迁移记录
                    assertNoSuccessfulV8History(tempJdbcUrl, rootUsername, rootPassword);

                    // 2. 不得留下任何半成品新结构：V8 新列一个都不应存在，旧列必须原样保留
                    assertBatchColumnPresence(tempJdbcUrl, rootUsername, rootPassword, tempSchema,
                            List.of("batch_no", "status"), List.of("trace_batch_no", "external_batch_no",
                                    "flow_status", "risk_status", "creation_org_id"));

                    // 3. V8 的前置临时表不得泄漏到 schema 中
                    assertNoLeakedTable(tempJdbcUrl, rootUsername, rootPassword, "tmp_v8_pepper_precondition");

                    // 4. 存量业务数据必须逐字未被触碰
                    try (Connection conn = DriverManager.getConnection(tempJdbcUrl, rootUsername, rootPassword);
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(
                                 "SELECT `id`, `batch_no`, `status` FROM `batch` ORDER BY `id`")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getLong("id")).isEqualTo(1001L);
                        assertThat(rs.getString("batch_no")).isEqualTo("BN-HIST-DRAFT");
                        assertThat(rs.getString("status")).isEqualTo("DRAFT");
                    }

                    // 5. 失败的 V8 尝试绝不能把密钥（或其任何编码形式）写进迁移历史
                    assertFlywayHistoryNeverLeaksPepper(tempJdbcUrl, rootUsername, rootPassword, unacceptablePepper);
                });
            }
        }

        @Test
        @DisplayName("契约3：已完成 V8 的库在移除 pepper 后仍可正常 validate 与空跑 migrate")
        void testCompletedV8DatabaseValidatesAndNoOpMigratesWithoutPepper() throws Exception {
            withTemporarySchema("trace_mig_donev8_", (tempSchema, tempJdbcUrl, rootUsername, rootPassword) -> {
                // 先用合格 pepper 完成 V1->V7->V8，并带上真实历史数据
                flywayTo(tempJdbcUrl, rootUsername, rootPassword, "7", null).migrate();
                seedHistoricalV7Data(tempJdbcUrl, rootUsername, rootPassword);
                flywayTo(tempJdbcUrl, rootUsername, rootPassword, "8", TEST_PEPPER_A).migrate();

                String traceBatchNoBefore = readTraceBatchNo(tempJdbcUrl, rootUsername, rootPassword, 1001L);
                assertThat(traceBatchNoBefore).isEqualTo(computeHistoricalTraceBatchNo(TEST_PEPPER_A, 1001L));

                // 随后彻底移除 pepper：V8 已完成、无待执行迁移，回调不会被触发
                for (String removedPepper : new String[]{null, ""}) {
                    Flyway withoutPepper = flywayTo(tempJdbcUrl, rootUsername, rootPassword, "8", removedPepper);

                    assertThatCode(withoutPepper::validate)
                            .as("已完成 V8 的库在无 pepper 时 validate 必须通过")
                            .doesNotThrowAnyException();

                    assertThat(withoutPepper.migrate().migrationsExecuted)
                            .as("无待执行迁移时 migrate 必须是零执行的空跑，不得要求 pepper")
                            .isZero();
                }

                // 空跑不得改写既有历史摘要
                assertThat(readTraceBatchNo(tempJdbcUrl, rootUsername, rootPassword, 1001L))
                        .isEqualTo(traceBatchNoBefore);
            });
        }

        /** 读取指定历史批次的 trace_batch_no。 */
        private String readTraceBatchNo(String url, String user, String password, long id) throws SQLException {
            try (Connection conn = DriverManager.getConnection(url, user, password);
                 PreparedStatement ps = conn.prepareStatement("SELECT trace_batch_no FROM batch WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("Batch with id %d must exist", id).isTrue();
                    return rs.getString("trace_batch_no");
                }
            }
        }

        @Test
        @DisplayName("契约7：全新空库直接执行 V1->V8 成功且表结构具备双状态双编号新约束")
        void testEmptyDatabaseDirectV1ToV8Migration() throws Exception {
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            HikariDataSource hikari = (HikariDataSource) dataSource;
            String originalJdbcUrl = hikari.getJdbcUrl();

            String rootUsername = getRootUsername();
            String rootPassword = getRootPassword();

            String tempSchema = "trace_mig_empty_v8_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String tempJdbcUrl = originalJdbcUrl.replaceFirst("/[a-zA-Z0-9_]+(\\?|$)", "/" + tempSchema + "$1");

            try (Connection rootConn = DriverManager.getConnection(originalJdbcUrl, rootUsername, rootPassword);
                 Statement rootStmt = rootConn.createStatement()) {

                rootStmt.execute("CREATE SCHEMA `" + tempSchema + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");

                try {
                    // 空库直接执行到 V8
                    Flyway flyway = flywayTo(tempJdbcUrl, rootUsername, rootPassword, "8", TEST_PEPPER_A);
                    int executed = flyway.migrate().migrationsExecuted;
                    assertThat(executed).isGreaterThanOrEqualTo(8);

                    try (Connection tempConn = DriverManager.getConnection(tempJdbcUrl, rootUsername, rootPassword)) {
                        DatabaseMetaData metaData = tempConn.getMetaData();

                        // 验证新列存在
                        try (ResultSet rs = metaData.getColumns(tempSchema, null, "batch", "trace_batch_no")) {
                            assertThat(rs.next()).isTrue();
                        }
                        try (ResultSet rs = metaData.getColumns(tempSchema, null, "batch", "external_batch_no")) {
                            assertThat(rs.next()).isTrue();
                        }
                        try (ResultSet rs = metaData.getColumns(tempSchema, null, "batch", "flow_status")) {
                            assertThat(rs.next()).isTrue();
                        }
                        try (ResultSet rs = metaData.getColumns(tempSchema, null, "batch", "risk_status")) {
                            assertThat(rs.next()).isTrue();
                        }

                        // 验证旧列不存在
                        try (ResultSet rs = metaData.getColumns(tempSchema, null, "batch", "batch_no")) {
                            assertThat(rs.next()).isFalse();
                        }
                        try (ResultSet rs = metaData.getColumns(tempSchema, null, "batch", "status")) {
                            assertThat(rs.next()).isFalse();
                        }
                    }
                } finally {
                    rootStmt.execute("DROP SCHEMA IF EXISTS `" + tempSchema + "`");
                }
            }
        }

        /** 断言指定批次的创建组织。creation_org_id 创建后不可变，交接绝不得改写。 */
        private void assertCreationOrgId(Connection conn, long id, long expectedCreationOrgId) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("SELECT creation_org_id FROM batch WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("Batch with id %d must exist", id).isTrue();
                    assertThat(rs.getLong("creation_org_id"))
                            .as("creation_org_id of batch %d", id)
                            .isEqualTo(expectedCreationOrgId);
                }
            }
        }

        /** 读取 batch 表上现存的全部索引名。 */
        private List<String> readIndexNames(Connection conn, String schema) throws SQLException {
            List<String> names = new java.util.ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getIndexInfo(schema, null, "batch", false, false)) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
            return names;
        }

        private void assertBatchStatusAndIdentifiers(Connection conn, long id, String expectedExternalBatchNo,
                                                     String expectedFlowStatus, String expectedRiskStatus) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT external_batch_no, flow_status, risk_status FROM batch WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("Batch with id %d must exist", id).isTrue();
                    assertThat(rs.getString("external_batch_no")).isEqualTo(expectedExternalBatchNo);
                    assertThat(rs.getString("flow_status")).isEqualTo(expectedFlowStatus);
                    assertThat(rs.getString("risk_status")).isEqualTo(expectedRiskStatus);
                }
            }
        }

        // -----------------------------------------------------------------
        // 临时 Schema 与断言辅助
        // -----------------------------------------------------------------

        /** 在一次性临时 schema 中执行测试体，无论成败都在 finally 中彻底删除，杜绝 trace_mig_% 残留。 */
        @FunctionalInterface
        private interface TemporarySchemaScenario {
            void run(String schema, String jdbcUrl, String rootUsername, String rootPassword) throws Exception;
        }

        private void withTemporarySchema(String prefix, TemporarySchemaScenario scenario) throws Exception {
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            String originalJdbcUrl = ((HikariDataSource) dataSource).getJdbcUrl();
            String rootUsername = getRootUsername();
            String rootPassword = getRootPassword();

            String tempSchema = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String tempJdbcUrl = originalJdbcUrl.replaceFirst("/[a-zA-Z0-9_]+(\\?|$)", "/" + tempSchema + "$1");

            try (Connection rootConn = DriverManager.getConnection(originalJdbcUrl, rootUsername, rootPassword);
                 Statement rootStmt = rootConn.createStatement()) {
                rootStmt.execute("CREATE SCHEMA `" + tempSchema + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                try {
                    scenario.run(tempSchema, tempJdbcUrl, rootUsername, rootPassword);
                } finally {
                    rootStmt.execute("DROP SCHEMA IF EXISTS `" + tempSchema + "`");
                }
            }
        }

        /** 只描述 pepper 的不合格类别，绝不在测试输出中回显候选值本身。 */
        private String describePepper(String pepper) {
            if (pepper == null) {
                return "null";
            }
            if (pepper.isEmpty()) {
                return "empty";
            }
            if (pepper.isBlank()) {
                return "blank";
            }
            if (TraceBatchNoHistoryPepperFlywayCallback.PLACEHOLDER_PEPPER.equalsIgnoreCase(pepper.strip())) {
                return "public-placeholder";
            }
            return "too-short-" + pepper.getBytes(StandardCharsets.UTF_8).length + "-bytes";
        }

        /** 写入 V7 结构下的历史存量数据（含全部五种旧状态）。 */
        private void seedHistoricalV7Data(String url, String user, String password) throws SQLException {
            try (Connection conn = DriverManager.getConnection(url, user, password);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        INSERT INTO `organization` (`id`, `org_no`, `name`, `org_type`, `status`)
                        VALUES (101, 'ORG-SRC-001', '捕捞企业A', 'SOURCE', 'ACTIVE'),
                               (102, 'ORG-PROC-001', '加工企业B', 'PROCESSOR', 'ACTIVE')
                        """);
                stmt.execute("""
                        INSERT INTO `product` (`id`, `product_code`, `public_name`, `category`, `specification`, `source_type`)
                        VALUES (201, 'PROD-001', '冷冻白虾', 'CRUSTACEAN', '500g/盒', 'DOMESTIC_CAPTURE')
                        """);
                stmt.execute("""
                        INSERT INTO `batch` (`id`, `org_id`, `product_id`, `batch_no`, `batch_type`, `quantity`, `unit_code`, `origin_type`, `origin_text`, `status`)
                        VALUES
                        (1001, 101, 201, 'BN-HIST-DRAFT', 'SOURCE', 100.000, 'kg', 'DOMESTIC_CAPTURE', '东海1区', 'DRAFT'),
                        (1002, 101, 201, 'BN-HIST-ACTIVE', 'SOURCE', 200.000, 'kg', 'DOMESTIC_CAPTURE', '东海2区', 'ACTIVE'),
                        (1003, 101, 201, 'BN-HIST-CLOSED', 'SOURCE', 300.000, 'kg', 'DOMESTIC_CAPTURE', '东海3区', 'CLOSED'),
                        (1004, 101, 201, 'BN-HIST-FROZEN', 'SOURCE', 400.000, 'kg', 'DOMESTIC_CAPTURE', '东海4区', 'FROZEN'),
                        (1005, 101, 201, 'BN-HIST-RECALLED', 'SOURCE', 500.000, 'kg', 'DOMESTIC_CAPTURE', '东海5区', 'RECALLED')
                        """);
            }
        }

        /** 断言库中不存在任何成功的 V8 迁移记录（flyway_schema_history 表可能尚未创建）。 */
        private void assertNoSuccessfulV8History(String url, String user, String password) throws SQLException {
            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                if (!tableExists(conn, "flyway_schema_history")) {
                    return;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) AS c FROM flyway_schema_history WHERE version = '8' AND success = 1");
                     ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("c"))
                            .as("pepper 不合格时绝不能留下成功的 V8 迁移记录")
                            .isZero();
                }
            }
        }

        /** 断言 batch 表上存在 expectedPresent 中的全部列，且不存在 expectedAbsent 中的任何列。 */
        private void assertBatchColumnPresence(String url, String user, String password, String schema,
                                               List<String> expectedPresent, List<String> expectedAbsent)
                throws SQLException {
            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                DatabaseMetaData metaData = conn.getMetaData();
                for (String column : expectedPresent) {
                    try (ResultSet rs = metaData.getColumns(schema, null, "batch", column)) {
                        assertThat(rs.next()).as("batch.%s 必须存在", column).isTrue();
                    }
                }
                for (String column : expectedAbsent) {
                    try (ResultSet rs = metaData.getColumns(schema, null, "batch", column)) {
                        assertThat(rs.next()).as("batch.%s 不得残留", column).isFalse();
                    }
                }
            }
        }

        /** 断言指定表未泄漏到 schema 中（V8 的前置校验临时表必须随会话结束消失）。 */
        private void assertNoLeakedTable(String url, String user, String password, String tableName)
                throws SQLException {
            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                assertThat(tableExists(conn, tableName))
                        .as("临时表 %s 不得泄漏为持久表", tableName)
                        .isFalse();
            }
        }

        private boolean tableExists(Connection conn, String tableName) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) AS c FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = ?")) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return rs.getInt("c") > 0;
                }
            }
        }

        /** 断言 flyway_schema_history 全文不含 pepper 的明文 / 十六进制 / Base64 形式。 */
        private void assertFlywayHistoryNeverLeaksPepper(String url, String user, String password, String pepper)
                throws SQLException {
            if (pepper == null || pepper.isBlank()) {
                return;
            }
            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                if (!tableExists(conn, "flyway_schema_history")) {
                    return;
                }
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT IFNULL(GROUP_CONCAT(CONCAT_WS('|', version, description, script, installed_by) "
                                     + "SEPARATOR '##'), '') AS history FROM flyway_schema_history")) {
                    assertThat(rs.next()).isTrue();
                    String history = rs.getString("history");
                    byte[] raw = pepper.getBytes(StandardCharsets.UTF_8);
                    assertThat(history)
                            .doesNotContain(pepper)
                            .doesNotContain(HexFormat.of().formatHex(raw))
                            .doesNotContain(HexFormat.of().withUpperCase().formatHex(raw))
                            .doesNotContain(Base64.getEncoder().encodeToString(raw));
                }
            }
        }

        private String getRootUsername() {
            String rootUsername = System.getenv("DB_ROOT_USERNAME");
            if (rootUsername == null || rootUsername.isBlank()) {
                rootUsername = System.getProperty("db.root.username", "root");
            }
            return (rootUsername == null || rootUsername.isBlank()) ? "root" : rootUsername;
        }

        private String getRootPassword() {
            String rootPassword = System.getenv("DB_ROOT_PASSWORD");
            if (rootPassword == null) {
                rootPassword = System.getProperty("db.root.password", "");
            }
            return rootPassword;
        }
    }
}
