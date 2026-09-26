package com.example.traceability.quality;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.quality.application.ShipmentTemperatureService;
import com.example.traceability.quality.domain.TemperatureEvaluation;
import com.example.traceability.quality.mapper.TemperatureRecordMapper;
import com.example.traceability.trace.domain.DataSource;
import com.example.traceability.trace.mapper.ShipmentMapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shipment 在途温度记录追加式与 PB2 边界收敛（Phase B PB2）。
 * <p>
 * 扫描全部生产 SQL 所在位置（{@code src/main/java} 与 {@code src/main/resources} 中的非迁移 XML / SQL）：
 * 任何对 {@code temperature_record} 的 UPDATE / DELETE、条件构造器写入或越权注入温度记录 mapper 都会让本测试失败。
 * 同时固定 PB2 的范围：温度服务不调用风险核心、不写批次风险状态、不生成 TraceEvent、不引用 Alert；
 * Java 侧开放的数据来源与判定取值与 V13 的 CHECK 约束严格一致；幂等复读与到达的最新测量时间读取都真正访问数据库，
 * 且唯一的锁定读只锁温度记录行，不锁规则表。
 * </p>
 */
@DisplayName("在途温度记录追加式与 PB2 边界：无 UPDATE / DELETE、mapper 收敛、不触达风险 / 事件 / 告警")
class TemperatureRecordAppendOnlyContainmentTest {

    private static final Path SERVER = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
    private static final Path MAIN_JAVA = SERVER.resolve("src/main/java");
    private static final Path MAIN_RESOURCES = SERVER.resolve("src/main/resources");
    private static final Path MIGRATIONS = MAIN_RESOURCES.resolve("db/migration");

    private static final String RECORD_MAPPER_FILE = "TemperatureRecordMapper.java";
    private static final String TEMPERATURE_SERVICE_FILE = "ShipmentTemperatureService.java";

    private static final Pattern RECORD_MUTATION = Pattern.compile(
            "(?is)\\b(?:UPDATE\\s+`?temperature_record`?|DELETE\\s+FROM\\s+`?temperature_record`?|"
                    + "INSERT\\s+INTO\\s+`?temperature_record`?[^;]*?ON\\s+DUPLICATE\\s+KEY\\s+UPDATE|REPLACE\\s+INTO\\s+`?temperature_record`?)\\b");
    private static final Pattern RECORD_INSERT = Pattern.compile("(?is)\\bINSERT\\s+INTO\\s+`?temperature_record`?\\b");
    private static final Pattern RECORD_MAPPER_REFERENCE =
            Pattern.compile("\\bcom\\.example\\.traceability\\.quality\\.mapper\\.TemperatureRecordMapper\\b");

    static List<String> mutations(String source) {
        List<String> hits = new ArrayList<>();
        Matcher m = RECORD_MUTATION.matcher(source);
        while (m.find()) {
            hits.add(m.group().replaceAll("\\s+", " "));
        }
        return hits;
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

    @Test
    @DisplayName("检测器自检：UPDATE / DELETE / ON DUPLICATE KEY UPDATE / REPLACE 均被识别；普通 INSERT 与 SELECT 不算修改")
    void detectorSelfTest() {
        assertThat(mutations("@Update(\"UPDATE temperature_record SET evaluation = 'NORMAL' WHERE id = 1\")")).hasSize(1);
        assertThat(mutations("@Update(\"\"\"\n UPDATE `temperature_record`\n SET temperature = 1\n\"\"\")")).hasSize(1);
        assertThat(mutations("@Delete(\"DELETE FROM temperature_record WHERE id = 1\")")).hasSize(1);
        assertThat(mutations("INSERT INTO temperature_record (id) VALUES (1) ON DUPLICATE KEY UPDATE evaluation = 'NORMAL'")).hasSize(1);
        assertThat(mutations("REPLACE INTO temperature_record (id) VALUES (1)")).hasSize(1);
        assertThat(mutations("@Insert(\"INSERT INTO temperature_record (id) VALUES (1)\")")).isEmpty();
        assertThat(mutations("@Select(\"SELECT * FROM temperature_record WHERE shipment_id = 1\")")).isEmpty();
    }

    @Test
    @DisplayName("生产 Java 与资源 SQL 中不存在任何温度记录修改；唯一的 INSERT 位于 TemperatureRecordMapper")
    void productionSourcesNeverMutateRecords() throws IOException {
        Map<Path, String> sources = read(MAIN_JAVA, p -> p.toString().endsWith(".java"));
        sources.putAll(read(MAIN_RESOURCES, p -> {
            String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            return (name.endsWith(".xml") || name.endsWith(".sql")) && !p.startsWith(MIGRATIONS);
        }));
        assertThat(sources).hasSizeGreaterThan(100);
        List<String> violations = new ArrayList<>();
        int inserts = 0;
        for (Map.Entry<Path, String> e : sources.entrySet()) {
            mutations(e.getValue()).forEach(m -> violations.add(SERVER.relativize(e.getKey()) + ": " + m));
            Matcher insert = RECORD_INSERT.matcher(e.getValue());
            while (insert.find()) {
                if (e.getKey().getFileName().toString().equals(RECORD_MAPPER_FILE)) {
                    inserts++;
                } else {
                    violations.add(SERVER.relativize(e.getKey()) + ": INSERT INTO temperature_record outside TemperatureRecordMapper");
                }
            }
        }
        assertThat(violations).isEmpty();
        assertThat(inserts).as("the single sanctioned INSERT").isEqualTo(1);
    }

    @Test
    @DisplayName("温度记录 mapper 不继承 BaseMapper、只有 insert / select 方法，且只允许 ShipmentTemperatureService 注入")
    void recordMapperIsConfined() throws IOException {
        assertThat(BaseMapper.class.isAssignableFrom(TemperatureRecordMapper.class)).isFalse();
        assertThat(Arrays.stream(TemperatureRecordMapper.class.getDeclaredMethods()).map(Method::getName))
                .allMatch(n -> n.equals("insert") || n.startsWith("select"));

        Map<Path, String> sources = read(MAIN_JAVA, p -> p.toString().endsWith(".java"));
        List<String> violations = new ArrayList<>();
        for (Map.Entry<Path, String> e : sources.entrySet()) {
            String file = e.getKey().getFileName().toString();
            if (!file.equals(RECORD_MAPPER_FILE) && !file.equals(TEMPERATURE_SERVICE_FILE)
                    && RECORD_MAPPER_REFERENCE.matcher(e.getValue()).find()) {
                violations.add(SERVER.relativize(e.getKey()) + " references TemperatureRecordMapper");
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("PB2 边界：温度服务不注入风险核心 / 批次写 mapper / 追溯事件 / 告警，不含 risk_status 或 FOR UPDATE 批次读取")
    void temperatureServiceStaysWithinPb2() throws IOException {
        // 只检查代码：Javadoc / 注释中说明“不做什么”的文字不算引用
        String src = Files.readString(MAIN_JAVA.resolve(
                "com/example/traceability/quality/application/ShipmentTemperatureService.java"), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "");
        for (String forbidden : List.of("BatchRiskService", "BatchRiskStateMapper", "BatchRiskTransitionMapper",
                "TraceEventApplicationService", "TraceEventMapper", "Alert", "risk_status", "setRiskStatus",
                "selectByIdForUpdate(transfer", "selectByIdIgnoreTenantForUpdate", "Quarantine", "Recall", "InspectionReport")) {
            assertThat(src).as("ShipmentTemperatureService must not reference %s", forbidden).doesNotContain(forbidden);
        }
        Set<String> fieldTypes = Set.copyOf(Arrays.stream(ShipmentTemperatureService.class.getDeclaredFields())
                .map(Field::getType).map(Class::getSimpleName).toList());
        assertThat(fieldTypes).doesNotContain("BatchRiskService", "TraceEventApplicationService");
    }

    @Test
    @DisplayName("Java 开放的数据来源与判定取值与 V13 CHECK 严格一致；V13 不建立测量时间唯一约束")
    void javaValuesAlignedWithV13() throws IOException {
        String v13 = Files.readString(MIGRATIONS.resolve("V13__shipment_temperature_record.sql"), StandardCharsets.UTF_8);
        assertThat(inList(v13, "chk_temp_source", "data_source"))
                .containsExactlyInAnyOrder(DataSource.MANUAL.name(), DataSource.SIMULATED.name());
        assertThat(inList(v13, "chk_temp_evaluation", "evaluation"))
                .containsExactlyElementsOf(Arrays.stream(TemperatureEvaluation.values()).map(Enum::name).toList());
        assertThat(v13).doesNotContainPattern("(?i)UNIQUE\\s*\\([^)]*measured_at");
    }

    private static List<String> inList(String sql, String constraint, String column) {
        Matcher m = Pattern.compile(constraint + "`\\s+CHECK\\s*\\(\\s*`" + column + "`\\s+IN\\s*\\(([^)]*)\\)").matcher(sql);
        assertThat(m.find()).as("%s located in V13", constraint).isTrue();
        return Arrays.stream(m.group(1).split(",")).map(s -> s.trim().replace("'", "")).toList();
    }

    @Test
    @DisplayName("历史判定依据只读温度记录快照列：上下限与允许越界时长来自 tr.*，从不读取当前规则环节的 lower / upper / allowed_duration")
    void responsesReadOnlySnapshotBasis() {
        String select = TemperatureRecordMapper.SELECT_WITH_RULE;
        assertThat(select).contains("tr.rule_lower_limit", "tr.rule_upper_limit", "tr.rule_allowed_duration_seconds");
        assertThat(select).doesNotContain("s.lower_limit", "s.upper_limit", "s.allowed_duration_seconds");
        assertThat(Arrays.stream(TemperatureRecordMapper.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("select"))
                .map(m -> String.join(" ", m.getAnnotation(Select.class).value())))
                .allSatisfy(sql -> assertThat(sql).startsWith(select));
    }

    @Test
    @DisplayName("幂等复读与到达的最新测量时间读取都清空会话缓存；锁定读只锁温度记录行（FOR UPDATE OF tr / FOR SHARE）")
    void lockingReadsAreScopedAndUncached() throws NoSuchMethodException {
        Method reRead = TemperatureRecordMapper.class.getDeclaredMethod("selectByOrgIdAndIdempotencyKey", Long.class, String.class);
        assertThat(reRead.getAnnotation(Options.class).flushCache()).isEqualTo(Options.FlushCachePolicy.TRUE);

        Method forUpdate = TemperatureRecordMapper.class.getDeclaredMethod("selectByOrgIdAndIdempotencyKeyForUpdate", Long.class, String.class);
        String forUpdateSql = String.join(" ", forUpdate.getAnnotation(Select.class).value());
        assertThat(forUpdateSql).contains("FOR UPDATE OF tr").doesNotContain("FOR SHARE");

        Method latest = ShipmentMapper.class.getDeclaredMethod("selectLatestTemperatureMeasuredAtForShare", Long.class);
        String latestSql = String.join(" ", latest.getAnnotation(Select.class).value());
        assertThat(latestSql).contains("FROM temperature_record").contains("ORDER BY measured_at DESC, id DESC").endsWith("FOR SHARE");
        assertThat(latest.getAnnotation(Options.class).flushCache()).isEqualTo(Options.FlushCachePolicy.TRUE);
    }
}
