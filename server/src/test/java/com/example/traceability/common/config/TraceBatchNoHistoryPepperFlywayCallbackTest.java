package com.example.traceability.common.config;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TraceBatchNoHistoryPepperFlywayCallback} 单元测试。
 * <p>
 * 覆盖三条安全契约：
 * <ol>
 *   <li><b>版本闸门</b>：回调只对即将执行的 V8 版本化迁移生效；V1~V7、V9、repeatable、
 *       版本缺失、非 {@code BEFORE_EACH_MIGRATE} 事件一律不触发，因此不会把一次性的历史回填密钥
 *       变成整个迁移体系的长期强依赖。{@code handle} 独立做同一判定，不依赖 {@code supports} 的过滤。</li>
 *   <li><b>密钥强度</b>：null、空串、纯空白、已公开的示例占位值、UTF-8 编码后不足 32 字节的值全部拒绝；
 *       长度按 UTF-8 字节而非 Java char 计量；异常信息只含配置要求与变量名，绝不含密钥的任何编码形式。</li>
 *   <li><b>密钥不入 SQL 文本</b>：注入语句是固定模板，pepper 只经 {@code PreparedStatement#setString}
 *       绑定参数传入；绝不使用 {@code Statement} 拼接明文 / 十六进制 / Base64 形式的密钥。</li>
 * </ol>
 * 断言以 mock 行为验证为主（真正执行了什么 SQL、参数怎么传），不做脆弱的源码字符串断言。
 * </p>
 */
class TraceBatchNoHistoryPepperFlywayCallbackTest {

    /** 测试专用高熵 pepper（36 个 ASCII 字符 = 36 字节 >= 32），仅存在于测试进程内存。 */
    private static final String STRONG_PEPPER = "Zq7vK2pR9tL4mX8nB6cW1yH3jD5sF0gA2eU4";

    /** 恰好 32 字节的边界值：必须被接受。 */
    private static final String BOUNDARY_32_BYTE_PEPPER = "aZ9kQ2wE5rT8yU1iO4pS7dF0gH3jK6lX";

    /** 31 字节：低于门槛，必须被拒绝。 */
    private static final String BOUNDARY_31_BYTE_PEPPER = "aZ9kQ2wE5rT8yU1iO4pS7dF0gH3jK6l";

    /**
     * 11 个 CJK 字符：char 数只有 11（远低于 32），但 UTF-8 编码后是 33 字节。
     * 该用例是"长度必须按 UTF-8 字节而非 char 计量"的判别性证据：按 char 计量会被误拒。
     */
    private static final String MULTIBYTE_33_BYTE_PEPPER = "随机高熵密钥值甲乙丙丁";

    /** 构造带迁移版本信息的回调上下文。{@code version} 为 null 表示 repeatable（无版本号）迁移。 */
    private static Context contextForVersion(String version, Connection connection) {
        MigrationInfo migrationInfo = mock(MigrationInfo.class);
        when(migrationInfo.getVersion())
                .thenReturn(version == null ? null : MigrationVersion.fromVersion(version));
        Context context = mock(Context.class);
        when(context.getMigrationInfo()).thenReturn(migrationInfo);
        if (connection != null) {
            when(context.getConnection()).thenReturn(connection);
        }
        return context;
    }

    private static Context contextForVersion(String version) {
        return contextForVersion(version, null);
    }

    private static TraceBatchNoHistoryPepperFlywayCallback callbackWith(String pepper) {
        return new TraceBatchNoHistoryPepperFlywayCallback(pepper);
    }

    // ---------------------------------------------------------------------
    // 契约一：版本闸门
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("契约一：回调只对即将执行的 V8 版本化迁移生效")
    class VersionGateTests {

        private final TraceBatchNoHistoryPepperFlywayCallback callback = callbackWith(STRONG_PEPPER);

        @Test
        @DisplayName("BEFORE_EACH_MIGRATE + 版本 8：支持")
        void supportsBeforeEachMigrateOfVersion8() {
            assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, contextForVersion("8"))).isTrue();
        }

        @ParameterizedTest(name = "版本 {0} 不得触发 pepper 依赖")
        @ValueSource(strings = {"1", "2", "3", "4", "5", "6", "7", "9", "10", "8.1", "80", "0.8"})
        @DisplayName("V1~V7 与未来的 V9/V10 等版本：不支持")
        void doesNotSupportOtherVersions(String version) {
            assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, contextForVersion(version))).isFalse();
        }

        @Test
        @DisplayName("repeatable（无版本号）迁移：不支持")
        void doesNotSupportRepeatableMigration() {
            assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, contextForVersion(null))).isFalse();
        }

        @Test
        @DisplayName("migrationInfo 为 null：不支持，绝不误执行注入")
        void doesNotSupportWhenMigrationInfoIsNull() {
            Context context = mock(Context.class);
            when(context.getMigrationInfo()).thenReturn(null);
            assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, context)).isFalse();
        }

        @Test
        @DisplayName("版本为 Flyway 预定义哨兵 (EMPTY/LATEST/CURRENT/NEXT)：不支持")
        void doesNotSupportPredefinedSentinelVersions() {
            for (MigrationVersion sentinel : new MigrationVersion[]{
                    MigrationVersion.EMPTY, MigrationVersion.LATEST,
                    MigrationVersion.CURRENT, MigrationVersion.NEXT}) {
                MigrationInfo migrationInfo = mock(MigrationInfo.class);
                when(migrationInfo.getVersion()).thenReturn(sentinel);
                Context context = mock(Context.class);
                when(context.getMigrationInfo()).thenReturn(migrationInfo);

                assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, context))
                        .as("预定义版本哨兵 %s 不得被当成 V8", sentinel)
                        .isFalse();
            }
        }

        @ParameterizedTest(name = "事件 {0} 不得触发 pepper 注入")
        @EnumSource(value = Event.class, mode = EnumSource.Mode.EXCLUDE, names = {"BEFORE_EACH_MIGRATE"})
        @DisplayName("其余所有 Flyway 事件：不支持（即使上下文版本正好是 8）")
        void doesNotSupportOtherEvents(Event event) {
            assertThat(callback.supports(event, contextForVersion("8"))).isFalse();
        }

        @Test
        @DisplayName("handle 独立做防御性版本复判：非 V8 时不校验 pepper、不触碰连接")
        void handleDefensivelyRechecksVersionEvenWithoutPepper() {
            TraceBatchNoHistoryPepperFlywayCallback noPepperCallback = callbackWith(null);
            Connection connection = mock(Connection.class);

            for (String version : new String[]{"7", "9", null}) {
                Context context = contextForVersion(version, connection);
                assertThatCode(() -> noPepperCallback.handle(Event.BEFORE_EACH_MIGRATE, context))
                        .as("版本 %s 不得要求 pepper", String.valueOf(version))
                        .doesNotThrowAnyException();
            }

            verifyNoInteractions(connection);
        }

        @Test
        @DisplayName("handle 遇到非 BEFORE_EACH_MIGRATE 事件时直接无操作返回")
        void handleIgnoresOtherEvents() {
            TraceBatchNoHistoryPepperFlywayCallback noPepperCallback = callbackWith(null);
            Connection connection = mock(Connection.class);
            Context context = contextForVersion("8", connection);

            assertThatCode(() -> noPepperCallback.handle(Event.AFTER_EACH_MIGRATE, context))
                    .doesNotThrowAnyException();

            verifyNoInteractions(connection);
        }
    }

    // ---------------------------------------------------------------------
    // 契约二：密钥强度与占位值校验
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("契约二：pepper 强度与公开占位值校验")
    class PepperStrengthTests {

        private FlywayException handleV8Expecting(String pepper) {
            Connection connection = mock(Connection.class);
            Context context = contextForVersion("8", connection);
            try {
                callbackWith(pepper).handle(Event.BEFORE_EACH_MIGRATE, context);
            } catch (FlywayException e) {
                verifyNoInteractions(connection);
                return e;
            }
            throw new AssertionError("pepper 不合格时必须抛出 FlywayException");
        }

        @Test
        @DisplayName("pepper 为 null：拒绝执行 V8")
        void rejectsNullPepper() {
            assertThat(handleV8Expecting(null)).hasMessageContaining(
                    TraceBatchNoHistoryPepperFlywayCallback.PEPPER_ENV_VARIABLE);
        }

        @ParameterizedTest(name = "空白值 [{0}] 必须被拒绝")
        @ValueSource(strings = {"", " ", "   ", "\t", "\n", " \t\r\n "})
        @DisplayName("pepper 为空串或纯空白：拒绝执行 V8")
        void rejectsBlankPepper(String blank) {
            assertThat(handleV8Expecting(blank)).hasMessageContaining(
                    TraceBatchNoHistoryPepperFlywayCallback.PEPPER_ENV_VARIABLE);
        }

        @Test
        @DisplayName("pepper 仍为已公开的示例占位值：拒绝执行 V8（占位值不是密钥）")
        void rejectsKnownPlaceholderPepper() {
            String placeholder = TraceBatchNoHistoryPepperFlywayCallback.PLACEHOLDER_PEPPER;
            // 占位值本身长度已超过 32 字节，只能靠显式拉黑拦住，证明不是被长度规则顺手挡下的
            assertThat(placeholder.getBytes(StandardCharsets.UTF_8).length)
                    .isGreaterThanOrEqualTo(TraceBatchNoHistoryPepperFlywayCallback.MIN_PEPPER_BYTE_LENGTH);

            assertThat(handleV8Expecting(placeholder)).hasMessageContaining(
                    TraceBatchNoHistoryPepperFlywayCallback.PEPPER_ENV_VARIABLE);
            // 前后空白包裹的占位值同样必须被识别
            assertThat(handleV8Expecting("  " + placeholder + "  ")).hasMessageContaining(
                    TraceBatchNoHistoryPepperFlywayCallback.PEPPER_ENV_VARIABLE);
        }

        @Test
        @DisplayName("pepper UTF-8 编码后不足 32 字节：拒绝执行 V8")
        void rejectsPepperShorterThan32Bytes() {
            assertThat(BOUNDARY_31_BYTE_PEPPER.getBytes(StandardCharsets.UTF_8)).hasSize(31);
            assertThat(handleV8Expecting(BOUNDARY_31_BYTE_PEPPER)).hasMessageContaining(
                    TraceBatchNoHistoryPepperFlywayCallback.PEPPER_ENV_VARIABLE);
        }

        @Test
        @DisplayName("恰好 32 字节的高熵值：允许执行 V8（边界包含）")
        void acceptsPepperOfExactly32Bytes() throws SQLException {
            assertThat(BOUNDARY_32_BYTE_PEPPER.getBytes(StandardCharsets.UTF_8)).hasSize(32);
            assertThat(injectedPepperFor(BOUNDARY_32_BYTE_PEPPER)).isEqualTo(BOUNDARY_32_BYTE_PEPPER);
        }

        @Test
        @DisplayName("长度按 UTF-8 字节而非 char 计量：11 个 CJK 字符 (33 字节) 必须被接受")
        void countsLengthInUtf8BytesNotJavaChars() throws SQLException {
            assertThat(MULTIBYTE_33_BYTE_PEPPER.length())
                    .as("char 数必须明显低于 32，否则本用例无法区分两种计量口径")
                    .isLessThan(TraceBatchNoHistoryPepperFlywayCallback.MIN_PEPPER_BYTE_LENGTH);
            assertThat(MULTIBYTE_33_BYTE_PEPPER.getBytes(StandardCharsets.UTF_8)).hasSize(33);

            assertThat(injectedPepperFor(MULTIBYTE_33_BYTE_PEPPER)).isEqualTo(MULTIBYTE_33_BYTE_PEPPER);
        }

        @Test
        @DisplayName("异常信息只含配置要求与变量名，绝不含 pepper 的明文 / 十六进制 / Base64 形式")
        void exceptionMessageNeverLeaksPepperInAnyEncoding() {
            String almostStrongPepper = "leaked-secret-candidate-value-x";
            assertThat(almostStrongPepper.getBytes(StandardCharsets.UTF_8).length).isLessThan(32);

            FlywayException exception = handleV8Expecting(almostStrongPepper);
            assertNoPepperLeak(exception.getMessage(), almostStrongPepper);

            FlywayException placeholderException =
                    handleV8Expecting(TraceBatchNoHistoryPepperFlywayCallback.PLACEHOLDER_PEPPER);
            // 占位值是公开值，可以被复述，但同样不应被回显，保持统一口径
            assertThat(placeholderException.getMessage())
                    .doesNotContain(TraceBatchNoHistoryPepperFlywayCallback.PLACEHOLDER_PEPPER);

            // 异常信息必须给出可操作的配置指引
            assertThat(exception.getMessage())
                    .contains(TraceBatchNoHistoryPepperFlywayCallback.PEPPER_ENV_VARIABLE)
                    .contains(TraceBatchNoHistoryPepperFlywayCallback.PEPPER_PROPERTY_NAME)
                    .contains(String.valueOf(TraceBatchNoHistoryPepperFlywayCallback.MIN_PEPPER_BYTE_LENGTH));
        }
    }

    // ---------------------------------------------------------------------
    // 契约三：固定 SQL + 绑定参数，密钥绝不进入 SQL 文本
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("契约三：固定参数化 SQL，pepper 只经绑定参数传入")
    class ParameterBindingTests {

        @Test
        @DisplayName("V8 迁移前使用固定 PreparedStatement 模板注入会话变量")
        void usesFixedPreparedStatementTemplate() throws SQLException {
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            callbackWith(STRONG_PEPPER).handle(Event.BEFORE_EACH_MIGRATE, contextForVersion("8", connection));

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(connection).prepareStatement(sqlCaptor.capture());

            String executedSql = sqlCaptor.getValue();
            assertThat(executedSql)
                    .as("SQL 必须是回调声明的固定模板，逐字相等")
                    .isEqualTo(TraceBatchNoHistoryPepperFlywayCallback.SET_PEPPER_SESSION_VARIABLE_SQL)
                    .isEqualTo("SET @" + TraceBatchNoHistoryPepperFlywayCallback.SESSION_VARIABLE_NAME + " = ?");

            verify(statement).setString(1, STRONG_PEPPER);
            verify(statement).execute();
            verify(statement).close();
        }

        @Test
        @DisplayName("pepper 只经 setString 绑定传入，SQL 文本不含明文 / 十六进制 / Base64 形式")
        void pepperIsPassedOnlyThroughParameterBinding() throws SQLException {
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            callbackWith(STRONG_PEPPER).handle(Event.BEFORE_EACH_MIGRATE, contextForVersion("8", connection));

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(connection).prepareStatement(sqlCaptor.capture());
            assertNoPepperLeak(sqlCaptor.getValue(), STRONG_PEPPER);

            // 绑定参数拿到的必须是逐字节未经修改的原始 pepper：任何规范化都会破坏历史摘要可复现性
            ArgumentCaptor<String> boundValueCaptor = ArgumentCaptor.forClass(String.class);
            verify(statement).setString(anyInt(), boundValueCaptor.capture());
            assertThat(boundValueCaptor.getValue()).isEqualTo(STRONG_PEPPER);
        }

        @Test
        @DisplayName("绝不使用 Statement 拼接执行：不得调用 createStatement 或 execute(String)")
        void neverUsesStatementStringConcatenation() throws SQLException {
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);

            callbackWith(STRONG_PEPPER).handle(Event.BEFORE_EACH_MIGRATE, contextForVersion("8", connection));

            verify(connection, never()).createStatement();
            verify(statement, never()).execute(anyString());
            verify(statement, never()).executeUpdate(anyString());
            verify(statement, never()).addBatch(anyString());
        }

        @Test
        @DisplayName("注入失败时异常只回显 SQLState / errorCode，不回显 pepper 与 SQL 内容")
        void sqlExceptionNeverEchoesPepperOrSql() throws SQLException {
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.execute()).thenThrow(
                    new SQLException("原始驱动信息可能含有语句内容 " + STRONG_PEPPER, "42000", 1064));

            Context context = contextForVersion("8", connection);
            TraceBatchNoHistoryPepperFlywayCallback callback = callbackWith(STRONG_PEPPER);

            assertThatThrownBy(() -> callback.handle(Event.BEFORE_EACH_MIGRATE, context))
                    .isInstanceOf(FlywayException.class)
                    .satisfies(thrown -> {
                        assertNoPepperLeak(thrown.getMessage(), STRONG_PEPPER);
                        assertThat(thrown.getMessage()).contains("42000").contains("1064");
                        // 不得挂载会在日志中递归输出原始驱动信息的 cause
                        assertThat(thrown.getCause()).isNull();
                    });
        }
    }

    /** 断言一段文本不含 pepper 的任何常见可逆编码形式。 */
    private static void assertNoPepperLeak(String text, String pepper) {
        byte[] raw = pepper.getBytes(StandardCharsets.UTF_8);
        assertThat(text)
                .as("不得泄露 pepper 明文")
                .doesNotContain(pepper)
                .as("不得泄露 pepper 的十六进制形式")
                .doesNotContain(HexFormat.of().formatHex(raw))
                .doesNotContain(HexFormat.of().withUpperCase().formatHex(raw))
                .as("不得泄露 pepper 的 Base64 形式")
                .doesNotContain(Base64.getEncoder().encodeToString(raw))
                .doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
    }

    /** 以合格 pepper 执行一次 V8 注入，返回实际绑定到 PreparedStatement 的参数值。 */
    private static String injectedPepperFor(String pepper) throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        callbackWith(pepper).handle(Event.BEFORE_EACH_MIGRATE, contextForVersion("8", connection));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(statement).setString(anyInt(), captor.capture());
        return captor.getValue();
    }
}
