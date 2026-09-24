package com.example.traceability.batch;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchRiskSourceType;
import com.example.traceability.batch.mapper.BatchRiskStateMapper;
import com.example.traceability.batch.mapper.BatchRiskTransitionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次风险状态写入收敛（Phase B PB1）：{@code batch.risk_status} 在生产代码中只有一个运行期写入口。
 * <p>
 * 扫描全部生产 SQL 所在位置：{@code src/main/java}（MyBatis 注解 SQL、MyBatis-Plus 条件构造器、实体 setter）
 * 与 {@code src/main/resources} 下的 mapper XML / SQL 文件（若存在）。Flyway 历史迁移 {@code db/migration}
 * 是一次性的结构演进脚本而不是运行期写路径，不在扫描范围内（V8 的历史状态拆分即位于其中）。
 * 任何第二处 {@code UPDATE batch ... SET ... risk_status}、条件构造器写 risk_status、非 NORMAL 的 setRiskStatus、
 * 对风险台账的 UPDATE / DELETE 或越权注入风险 mapper 都会让本测试失败。
 * 通用实体更新无法覆盖 risk_status 的 MySQL 实证见 {@code BatchRiskMysqlIntegrationTest}。
 * </p>
 */
@DisplayName("批次风险状态写入收敛：生产代码只有 BatchRiskStateMapper 一个 risk_status 写入口")
class RiskStatusWriteContainmentTest {

    private static final Path SERVER = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
    private static final Path MAIN_JAVA = SERVER.resolve("src/main/java");
    private static final Path MAIN_RESOURCES = SERVER.resolve("src/main/resources");
    private static final Path MIGRATIONS = MAIN_RESOURCES.resolve("db/migration");

    private static final String STATE_MAPPER_FILE = "BatchRiskStateMapper.java";
    private static final String TRANSITION_MAPPER_FILE = "BatchRiskTransitionMapper.java";
    private static final String RISK_SERVICE_FILE = "BatchRiskService.java";

    /** UPDATE batch 语句的 SET 部分（到 WHERE、语句 / 字符串 / 注解 / XML 结束为止）。 */
    private static final Pattern UPDATE_BATCH_SET = Pattern.compile(
            "(?is)\\bUPDATE\\s+`?batch`?\\s+(.*?)(?:\\bWHERE\\b|\"\"\"|\"\\s*\\)|;|</update>)");
    /** INSERT INTO batch 的列清单：插入只能经实体写入 NORMAL。 */
    private static final Pattern INSERT_BATCH_COLUMNS = Pattern.compile("(?is)\\bINSERT\\s+INTO\\s+`?batch`?\\s*\\(([^)]*)\\)");
    /** MyBatis-Plus 条件构造器写 risk_status。 */
    private static final Pattern WRAPPER_SET = Pattern.compile(
            "\\.set\\s*\\([^;]*?(Batch::getRiskStatus|\"risk_status\"|\"riskStatus\")");
    private static final Pattern WRAPPER_SET_SQL = Pattern.compile("(?i)\\.setSql\\s*\\([^;]*?risk_status");
    /** 实体 setter：生产代码只允许以 NORMAL 插入新批次。 */
    private static final Pattern RISK_SETTER = Pattern.compile("\\.setRiskStatus\\s*\\(([^;]*?)\\)\\s*;");
    private static final String ALLOWED_SETTER_ARGUMENT = "BatchRiskStatus.NORMAL.name()";
    /** 风险台账追加式：任何 UPDATE / DELETE 都不允许。 */
    private static final Pattern LEDGER_MUTATION = Pattern.compile(
            "(?is)\\b(?:UPDATE\\s+`?batch_risk_transition`?|DELETE\\s+FROM\\s+`?batch_risk_transition`?)\\b");
    private static final Pattern RISK_TOKEN = Pattern.compile("(?i)risk_status");
    private static final Pattern STATE_MAPPER_REFERENCE =
            Pattern.compile("\\bcom\\.example\\.traceability\\.batch\\.mapper\\.BatchRiskStateMapper\\b");
    private static final Pattern TRANSITION_MAPPER_REFERENCE =
            Pattern.compile("\\bcom\\.example\\.traceability\\.batch\\.mapper\\.BatchRiskTransitionMapper\\b");

    // =========================================================================
    // 检测器
    // =========================================================================

    /** 返回一段生产源码 / SQL 中所有写 batch.risk_status 的片段（不含实体 setter 规则）。 */
    static List<String> riskStatusSqlWrites(String source) {
        List<String> hits = new ArrayList<>();
        Matcher update = UPDATE_BATCH_SET.matcher(source);
        while (update.find()) {
            if (RISK_TOKEN.matcher(update.group(1)).find()) {
                hits.add(compact(update.group()));
            }
        }
        Matcher insert = INSERT_BATCH_COLUMNS.matcher(source);
        while (insert.find()) {
            if (RISK_TOKEN.matcher(insert.group(1)).find()) {
                hits.add(compact(insert.group()));
            }
        }
        for (Pattern p : List.of(WRAPPER_SET, WRAPPER_SET_SQL, LEDGER_MUTATION)) {
            Matcher m = p.matcher(source);
            while (m.find()) {
                hits.add(compact(m.group()));
            }
        }
        return hits;
    }

    static List<String> forbiddenRiskSetters(String source) {
        List<String> hits = new ArrayList<>();
        Matcher m = RISK_SETTER.matcher(source);
        while (m.find()) {
            if (!ALLOWED_SETTER_ARGUMENT.equals(m.group(1).trim())) {
                hits.add(compact(m.group()));
            }
        }
        return hits;
    }

    private static String compact(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 160 ? one.substring(0, 160) + "…" : one;
    }

    private static Map<Path, String> read(Path root, java.util.function.Predicate<Path> include) throws IOException {
        Map<Path, String> files = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).filter(include).sorted().toList()) {
                files.put(p, Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    // =========================================================================
    // 检测器自检（防止扫描本身失效而空跑通过）
    // =========================================================================

    @Test
    @DisplayName("检测器自检：注解 / 文本块 / 多表 / XML / 条件构造器 / setSql / INSERT 列 / 台账 UPDATE / DELETE 均被识别；WHERE 条件中的 risk_status 不算写入")
    void detectorSelfTest() {
        assertThat(riskStatusSqlWrites("@Update(\"UPDATE batch SET risk_status = 'FROZEN' WHERE id = #{id}\")")).hasSize(1);
        assertThat(riskStatusSqlWrites("@Update(\"\"\"\n  UPDATE batch\n  SET version = version + 1,\n      risk_status = #{s}\n  WHERE id = #{id}\n\"\"\")")).hasSize(1);
        assertThat(riskStatusSqlWrites("@Update(\"UPDATE `batch` b JOIN transfer t ON t.batch_id = b.id SET b.risk_status = 'FROZEN'\")")).hasSize(1);
        assertThat(riskStatusSqlWrites("@Update(\"UPDATE batch SET risk_status = 'NORMAL'\")")).as("no WHERE clause").hasSize(1);
        assertThat(riskStatusSqlWrites("<update id=\"x\">UPDATE batch SET risk_status = #{s} WHERE id = #{id}</update>")).hasSize(1);
        assertThat(riskStatusSqlWrites("new LambdaUpdateWrapper<Batch>().eq(Batch::getId, 1).set(Batch::getRiskStatus, \"FROZEN\");")).hasSize(1);
        assertThat(riskStatusSqlWrites("wrapper.set(true, \"risk_status\", \"FROZEN\");")).hasSize(1);
        assertThat(riskStatusSqlWrites("wrapper.setSql(\"risk_status = 'FROZEN'\");")).hasSize(1);
        assertThat(riskStatusSqlWrites("@Insert(\"INSERT INTO batch (id, risk_status) VALUES (#{id}, 'FROZEN')\")")).hasSize(1);
        assertThat(riskStatusSqlWrites("@Update(\"UPDATE batch_risk_transition SET reason = 'x' WHERE id = 1\")")).hasSize(1);
        assertThat(riskStatusSqlWrites("@Delete(\"DELETE FROM batch_risk_transition WHERE id = 1\")")).hasSize(1);

        assertThat(riskStatusSqlWrites("@Update(\"UPDATE batch SET version = version + 1 WHERE id = #{id} AND risk_status = 'NORMAL'\")")).isEmpty();
        assertThat(riskStatusSqlWrites("@Update(\"UPDATE batch_operation SET status = 'SUBMITTED' WHERE id = #{id}\")")).isEmpty();
        assertThat(riskStatusSqlWrites("@Select(\"SELECT * FROM batch WHERE risk_status = #{riskStatus}\")")).isEmpty();
        assertThat(riskStatusSqlWrites("@Insert(\"INSERT INTO batch_risk_transition (batch_id, to_status) VALUES (1, 'FROZEN')\")")).isEmpty();

        assertThat(forbiddenRiskSetters("batch.setRiskStatus(\"FROZEN\");")).hasSize(1);
        assertThat(forbiddenRiskSetters("batch.setRiskStatus(BatchRiskStatus.FROZEN.name());")).hasSize(1);
        assertThat(forbiddenRiskSetters("batch.setRiskStatus(target);")).hasSize(1);
        assertThat(forbiddenRiskSetters("batch.setRiskStatus(BatchRiskStatus.NORMAL.name());")).isEmpty();
    }

    // =========================================================================
    // 生产代码扫描
    // =========================================================================

    @Test
    @DisplayName("src/main/java：risk_status 的唯一 SQL 写入位于 BatchRiskStateMapper；其余文件无任何 risk_status 写入")
    void javaSourcesHaveExactlyOneRiskStatusWriter() throws IOException {
        Map<Path, String> sources = read(MAIN_JAVA, p -> p.toString().endsWith(".java"));
        assertThat(sources).as("production Java sources scanned").hasSizeGreaterThan(100);

        List<String> violations = new ArrayList<>();
        int allowedWrites = 0;
        for (Map.Entry<Path, String> e : sources.entrySet()) {
            String file = e.getKey().getFileName().toString();
            List<String> writes = riskStatusSqlWrites(e.getValue());
            if (file.equals(STATE_MAPPER_FILE)) {
                allowedWrites += writes.size();
            } else {
                writes.forEach(w -> violations.add(SERVER.relativize(e.getKey()) + ": " + w));
            }
            forbiddenRiskSetters(e.getValue()).forEach(w -> violations.add(SERVER.relativize(e.getKey()) + ": " + w));
        }
        assertThat(violations).as("risk_status writes outside BatchRiskStateMapper").isEmpty();
        assertThat(allowedWrites).as("the single sanctioned risk_status UPDATE in BatchRiskStateMapper").isEqualTo(1);
    }

    @Test
    @DisplayName("src/main/resources：mapper XML / 非迁移 SQL 中不存在 risk_status 写入或风险台账 UPDATE / DELETE")
    void resourceSqlHasNoRiskStatusWriter() throws IOException {
        Map<Path, String> resources = read(MAIN_RESOURCES, p -> {
            String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            return (name.endsWith(".xml") || name.endsWith(".sql")) && !p.startsWith(MIGRATIONS);
        });
        List<String> violations = new ArrayList<>();
        for (Map.Entry<Path, String> e : resources.entrySet()) {
            riskStatusSqlWrites(e.getValue()).forEach(w -> violations.add(SERVER.relativize(e.getKey()) + ": " + w));
        }
        assertThat(violations).as("risk_status writes in mapper XML / resource SQL").isEmpty();
        assertThat(Files.isDirectory(MIGRATIONS)).as("migration directory located (and excluded)").isTrue();
    }

    @Test
    @DisplayName("风险 mapper 只允许 BatchRiskService 注入；两个风险 mapper 都不继承 BaseMapper，台账 mapper 不存在任何修改方法")
    void riskMappersAreConfined() throws IOException {
        Map<Path, String> sources = read(MAIN_JAVA, p -> p.toString().endsWith(".java"));
        List<String> violations = new ArrayList<>();
        Path mapperPackage = MAIN_JAVA.resolve("com/example/traceability/batch/mapper");
        for (Map.Entry<Path, String> e : sources.entrySet()) {
            String file = e.getKey().getFileName().toString();
            String src = e.getValue();
            boolean samePackage = e.getKey().getParent().equals(mapperPackage);
            // 跨包使用必须 import（或写全限定名）；同包使用直接出现类型名。Javadoc 中的文字说明不算引用。
            if (!file.equals(STATE_MAPPER_FILE) && !file.equals(RISK_SERVICE_FILE)
                    && (STATE_MAPPER_REFERENCE.matcher(src).find() || (samePackage && src.contains("BatchRiskStateMapper")))) {
                violations.add(SERVER.relativize(e.getKey()) + " references BatchRiskStateMapper");
            }
            if (!file.equals(TRANSITION_MAPPER_FILE) && !file.equals(RISK_SERVICE_FILE)
                    && (TRANSITION_MAPPER_REFERENCE.matcher(src).find() || (samePackage && src.contains("BatchRiskTransitionMapper")))) {
                violations.add(SERVER.relativize(e.getKey()) + " references BatchRiskTransitionMapper");
            }
        }
        assertThat(STATE_MAPPER_REFERENCE.matcher(sources.get(MAIN_JAVA.resolve(
                "com/example/traceability/batch/application/BatchRiskService.java"))).find())
                .as("reference detector sees BatchRiskService's own import").isTrue();
        assertThat(violations).isEmpty();

        for (Class<?> mapper : List.of(BatchRiskStateMapper.class, BatchRiskTransitionMapper.class)) {
            assertThat(BaseMapper.class.isAssignableFrom(mapper)).as("%s must not inherit generic BaseMapper writes", mapper.getSimpleName()).isFalse();
        }
        assertThat(Arrays.stream(BatchRiskStateMapper.class.getDeclaredMethods()).map(Method::getName))
                .containsExactly("transitionRiskStatus");
        assertThat(Arrays.stream(BatchRiskTransitionMapper.class.getDeclaredMethods()).map(Method::getName))
                .allMatch(n -> n.equals("insert") || n.startsWith("select") || n.startsWith("count"));
    }

    @Test
    @DisplayName("幂等复读每次真正查询数据库：同一事务内的会话级缓存不能让持锁后复读返回预读时的 null")
    void idempotencyReReadBypassesSessionCache() throws NoSuchMethodException {
        org.apache.ibatis.annotations.Options options = BatchRiskTransitionMapper.class
                .getDeclaredMethod("selectByOrgIdAndIdempotencyKey", Long.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Options.class);
        assertThat(options).isNotNull();
        assertThat(options.flushCache()).isEqualTo(org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE);
    }

    @Test
    @DisplayName("Batch.riskStatus 的 MyBatis-Plus 更新策略为 NEVER：通用实体更新永不写入 risk_status")
    void entityUpdateStrategyIsNever() throws NoSuchFieldException {
        TableField tf = Batch.class.getDeclaredField("riskStatus").getAnnotation(TableField.class);
        assertThat(tf.value()).isEqualTo("risk_status");
        assertThat(tf.updateStrategy()).isEqualTo(FieldStrategy.NEVER);
    }

    @Test
    @DisplayName("Java 来源类型与 V12 chk_brt_source_type 严格一致：PB1 只有 MANUAL")
    void sourceTypesAlignedWithV12() throws IOException {
        String v12 = Files.readString(MIGRATIONS.resolve("V12__batch_risk_transition.sql"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("chk_brt_source_type`\\s+CHECK\\s*\\(\\s*`source_type`\\s+IN\\s*\\(([^)]*)\\)").matcher(v12);
        assertThat(m.find()).as("chk_brt_source_type located in V12").isTrue();
        List<String> dbValues = Arrays.stream(m.group(1).split(","))
                .map(s -> s.trim().replace("'", ""))
                .toList();
        List<String> javaValues = Arrays.stream(BatchRiskSourceType.values()).map(Enum::name).toList();
        assertThat(javaValues).containsExactlyElementsOf(dbValues).containsExactly("MANUAL");
    }
}
