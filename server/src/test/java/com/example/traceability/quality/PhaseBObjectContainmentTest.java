package com.example.traceability.quality;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.traceability.quality.mapper.AlertActionMapper;
import com.example.traceability.quality.mapper.AlertBatchMapper;
import com.example.traceability.quality.mapper.AlertMapper;
import com.example.traceability.quality.mapper.InspectionReportMapper;
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
 * Phase B 业务对象写入收敛（结构性守卫）：告警、受影响批次快照与处置台账不能经 MyBatis-Plus 通用 {@code BaseMapper}
 * 或任何未声明的 SQL 被改写。
 * <p>
 * 扫描 {@code src/main/java} 与 {@code src/main/resources} 中的非迁移 XML / SQL：
 * <ul>
 *   <li>alert_batch / alert_action / inspection_report（PB4）追加式：任何 UPDATE / DELETE / REPLACE / ON DUPLICATE KEY UPDATE 都不允许，
 *       且 INSERT 只存在于各自的 mapper；</li>
 *   <li>alert 从不删除；其 INSERT 与 UPDATE 只存在于 AlertMapper，UPDATE 只推进处置生命周期列，不改写片段、判定依据、
 *       归属组织或运输任务；</li>
 *   <li>三个 mapper 都不继承 BaseMapper，只声明 insert / select / 生命周期推进方法。</li>
 * </ul>
 * </p>
 */
@DisplayName("Phase B 业务对象写入收敛：告警 / 快照 / 处置台账不经通用 BaseMapper 或未声明 SQL 改写")
class PhaseBObjectContainmentTest {

    private static final Path SERVER = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
    private static final Path MAIN_JAVA = SERVER.resolve("src/main/java");
    private static final Path MAIN_RESOURCES = SERVER.resolve("src/main/resources");
    private static final Path MIGRATIONS = MAIN_RESOURCES.resolve("db/migration");

    /** 追加式表：任何修改都不允许。 */
    private static final List<String> APPEND_ONLY_TABLES = List.of("alert_batch", "alert_action", "inspection_report");
    /** 追加式表与唯一允许 INSERT 的 mapper 文件。 */
    private static final Map<String, String> APPEND_ONLY_OWNERS = Map.of(
            "alert_batch", "AlertBatchMapper.java",
            "alert_action", "AlertActionMapper.java",
            "inspection_report", "InspectionReportMapper.java");
    /** alert 允许由 AlertMapper 推进的生命周期列。 */
    private static final List<String> ALERT_LIFECYCLE_COLUMNS = List.of("status", "acknowledged_at", "acknowledged_by",
            "resolved_at", "resolved_by", "resolution", "version", "updated_at", "updated_by");

    private static Pattern mutation(String table) {
        return Pattern.compile("(?is)\\b(?:UPDATE\\s+`?" + table + "`?\\s|DELETE\\s+FROM\\s+`?" + table + "`?\\b|REPLACE\\s+INTO\\s+`?"
                + table + "`?\\b|INSERT\\s+INTO\\s+`?" + table + "`?[^;]*?ON\\s+DUPLICATE\\s+KEY\\s+UPDATE)");
    }

    private static final Pattern ALERT_UPDATE_SET = Pattern.compile("(?is)\\bUPDATE\\s+`?alert`?\\s+SET\\s+(.*?)\\bWHERE\\b");
    private static final Pattern ALERT_DELETE = Pattern.compile("(?is)\\bDELETE\\s+FROM\\s+`?alert`?\\b");
    private static final Pattern ALERT_INSERT = Pattern.compile("(?is)\\bINSERT\\s+INTO\\s+`?alert`?\\s*\\(");

    private static Map<Path, String> productionSources() throws IOException {
        Map<Path, String> files = new LinkedHashMap<>();
        for (Path root : List.of(MAIN_JAVA, MAIN_RESOURCES)) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    boolean java = name.endsWith(".java");
                    boolean sql = (name.endsWith(".xml") || name.endsWith(".sql")) && !p.startsWith(MIGRATIONS);
                    if (java || sql) {
                        files.put(p, Files.readString(p, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return files;
    }

    @Test
    @DisplayName("检测器自检：追加式表的 UPDATE / DELETE / REPLACE / UPSERT 与 alert 的 DELETE / 非生命周期列 UPDATE 均被识别")
    void detectorSelfTest() {
        assertThat(mutation("alert_batch").matcher("@Update(\"UPDATE alert_batch SET org_id = 1 WHERE id = 1\")").find()).isTrue();
        assertThat(mutation("alert_action").matcher("@Delete(\"DELETE FROM alert_action WHERE id = 1\")").find()).isTrue();
        assertThat(mutation("alert_action").matcher("REPLACE INTO alert_action (id) VALUES (1)").find()).isTrue();
        assertThat(mutation("alert_batch").matcher("INSERT INTO alert_batch (id) VALUES (1) ON DUPLICATE KEY UPDATE org_id = 2").find()).isTrue();
        assertThat(mutation("alert_batch").matcher("INSERT INTO alert_batch (id) VALUES (1)").find()).isFalse();
        assertThat(mutation("alert").matcher("UPDATE alert_batch SET x = 1").find()).as("table name boundary").isFalse();
        assertThat(ALERT_DELETE.matcher("DELETE FROM alert WHERE id = 1").find()).isTrue();
        assertThat(ALERT_DELETE.matcher("DELETE FROM alert_action WHERE id = 1").find()).isFalse();
        Matcher m = ALERT_UPDATE_SET.matcher("UPDATE alert SET rule_upper_limit = 0, status = 'OPEN' WHERE id = 1");
        assertThat(m.find()).isTrue();
        assertThat(nonLifecycleColumns(m.group(1))).containsExactly("rule_upper_limit");
    }

    private static List<String> nonLifecycleColumns(String setClause) {
        List<String> bad = new ArrayList<>();
        Matcher col = Pattern.compile("(?i)`?([a-z_]+)`?\\s*=").matcher(setClause);
        while (col.find()) {
            if (!ALERT_LIFECYCLE_COLUMNS.contains(col.group(1).toLowerCase(java.util.Locale.ROOT))) {
                bad.add(col.group(1));
            }
        }
        return bad;
    }

    @Test
    @DisplayName("生产代码：追加式表无任何修改；alert 从不删除，INSERT / UPDATE 只在 AlertMapper，UPDATE 只推进生命周期列")
    void productionSourcesRespectContainment() throws IOException {
        Map<Path, String> sources = productionSources();
        assertThat(sources).hasSizeGreaterThan(100);
        List<String> violations = new ArrayList<>();
        int alertInserts = 0;
        int alertUpdates = 0;
        for (Map.Entry<Path, String> e : sources.entrySet()) {
            String rel = SERVER.relativize(e.getKey()).toString();
            String src = e.getValue();
            boolean alertMapper = e.getKey().getFileName().toString().equals("AlertMapper.java");
            for (String table : APPEND_ONLY_TABLES) {
                Matcher m = mutation(table).matcher(src);
                while (m.find()) {
                    violations.add(rel + ": " + m.group().replaceAll("\\s+", " "));
                }
            }
            for (Map.Entry<String, String> own : APPEND_ONLY_OWNERS.entrySet()) {
                Matcher insert = Pattern.compile("(?is)\\bINSERT\\s+INTO\\s+`?" + own.getKey() + "`?\\s*\\(").matcher(src);
                while (insert.find()) {
                    if (!e.getKey().getFileName().toString().equals(own.getValue())) {
                        violations.add(rel + ": INSERT INTO " + own.getKey() + " outside " + own.getValue());
                    }
                }
            }
            Matcher del = ALERT_DELETE.matcher(src);
            while (del.find()) {
                violations.add(rel + ": DELETE FROM alert");
            }
            Matcher ins = ALERT_INSERT.matcher(src);
            while (ins.find()) {
                if (alertMapper) {
                    alertInserts++;
                } else {
                    violations.add(rel + ": INSERT INTO alert outside AlertMapper");
                }
            }
            Matcher upd = ALERT_UPDATE_SET.matcher(src);
            while (upd.find()) {
                if (!alertMapper) {
                    violations.add(rel + ": UPDATE alert outside AlertMapper");
                }
                alertUpdates++;
                nonLifecycleColumns(upd.group(1)).forEach(c -> violations.add(rel + ": UPDATE alert SET " + c));
            }
        }
        assertThat(violations).isEmpty();
        assertThat(alertInserts).as("the single sanctioned INSERT INTO alert").isEqualTo(1);
        assertThat(alertUpdates).as("lifecycle updates exist and were inspected").isPositive();
    }

    @Test
    @DisplayName("告警相关 mapper 不继承 BaseMapper，只声明 insert / select / 生命周期推进方法")
    void mappersAreConfined() {
        for (Class<?> mapper : List.of(AlertMapper.class, AlertBatchMapper.class, AlertActionMapper.class, InspectionReportMapper.class)) {
            assertThat(BaseMapper.class.isAssignableFrom(mapper)).as("%s must not inherit generic BaseMapper writes", mapper.getSimpleName())
                    .isFalse();
        }
        assertThat(Arrays.stream(AlertBatchMapper.class.getDeclaredMethods()).map(Method::getName))
                .allMatch(n -> n.equals("insert") || n.startsWith("select") || n.startsWith("count"));
        assertThat(Arrays.stream(AlertActionMapper.class.getDeclaredMethods()).map(Method::getName))
                .allMatch(n -> n.equals("insert") || n.startsWith("select") || n.startsWith("count"));
        assertThat(Arrays.stream(InspectionReportMapper.class.getDeclaredMethods()).map(Method::getName))
                .allMatch(n -> n.equals("insert") || n.startsWith("select") || n.startsWith("count"));
        assertThat(Arrays.stream(AlertMapper.class.getDeclaredMethods()).map(Method::getName))
                .allMatch(n -> n.equals("insert") || n.startsWith("select") || List.of("acknowledge", "resolve").contains(n));
    }
}
