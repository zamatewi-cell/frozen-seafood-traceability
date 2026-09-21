package com.example.traceability.common.config;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 历史 trace_batch_no 回填 pepper 注入回调（仅服务于 V8 这一次性历史回填迁移）。
 * <p>
 * V8 历史批次的 {@code trace_batch_no} 采用
 * {@code SHA2(secretPepper || domainSeparator || historicalBatchId, 256)} 的不可逆确定性摘要回填。
 * 若直接把 pepper 字面量写入版本化迁移 SQL，会导致同一个 V8 在不同环境产生不同的 Flyway checksum，
 * 进而破坏迁移历史一致性；因此本回调在 <b>即将执行 V8 之前</b>、<b>在 Flyway 用于执行迁移的同一条 JDBC 连接上</b>
 * 设置 MySQL 会话变量 {@code @trace_batch_no_history_pepper}，V8 SQL 只引用这个固定的变量名。
 * </p>
 * <p>
 * 由此保证：
 * <ul>
 *   <li>V8 文件内容与 pepper 无关，其 Flyway checksum 在任何环境恒定；</li>
 *   <li>pepper 不写入 Git、不写入版本化迁移 SQL、不写入 {@code flyway_schema_history}，
 *       其取值只来自运行期环境变量 / Spring 属性，并只下发到当前数据库会话；</li>
 *   <li>本应用自身的日志与异常信息不会主动回显 pepper 及其任何编码形式；</li>
 *   <li>pepper 缺失、空白、仍为公开占位值或强度不足时立即失败，绝不回退到任何公开默认值。</li>
 * </ul>
 * </p>
 * <p>
 * <b>版本闸门</b>：本回调只对"即将执行的版本化迁移恰好是 V8"这一种情形生效。
 * V1~V7、未来的 V9/V10、以及任何 repeatable（无版本号）迁移都不会触发 pepper 校验与注入，
 * 因此一次性的历史回填密钥不会变成整个迁移体系的长期强依赖。
 * {@link #supports(Event, Context)} 与 {@link #handle(Event, Context)} 各自独立做同一判定，
 * 即便调用方绕过 {@code supports} 直接调用 {@code handle}，也不会对非 V8 迁移执行注入。
 * </p>
 * <p>
 * <b>密钥不入 SQL 文本</b>：注入语句是编译期常量 {@link #SET_PEPPER_SESSION_VARIABLE_SQL}，
 * pepper 仅通过 {@link PreparedStatement#setString(int, String)} 以绑定参数传入，
 * 应用代码不会以明文、十六进制、Base64 或任何其他可逆编码把 pepper 拼接进 SQL 字符串。
 * SQL 异常也只回显 SQLState / errorCode，不回显 pepper 与语句内容。
 * </p>
 * <p>
 * <b>责任边界（不要把上述保证误读为"任何日志都不可能记录 pepper"）</b>：
 * 上面几条只覆盖<em>本应用进程内</em>可控的范围。绑定参数在到达数据库后是否被落盘留痕，
 * 取决于部署侧配置而非应用代码——MySQL 服务端 general log、审计插件日志、慢查询日志，
 * 以及中间的连接池 / 代理 / JDBC 驱动跟踪日志，都可能记录语句及其绑定参数。
 * 因此生产环境必须由部署安全配置来保证：执行 V8 期间不开启会记录绑定参数的服务端日志与代理日志；
 * 若因排障必须临时开启，相关日志须限制访问并尽快清理，不得长期留存含敏感绑定参数的记录。
 * </p>
 * <p>
 * 会话变量的生存期是连接级：Flyway 对一次 migrate 的全部迁移复用同一条连接，
 * 且 MySQL 用户自定义变量不随事务提交或回滚而清除，因此在 V8 的 {@code BEFORE_EACH_MIGRATE} 设置后，
 * 该变量在随后执行的 V8 语句中始终可见。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Component
public class TraceBatchNoHistoryPepperFlywayCallback implements Callback {

    /** V8 迁移脚本引用的 MySQL 会话变量名。 */
    public static final String SESSION_VARIABLE_NAME = "trace_batch_no_history_pepper";

    /** 注入 pepper 的环境变量名（仅用于错误提示，不含任何密钥内容）。 */
    public static final String PEPPER_ENV_VARIABLE = "TRACE_BATCH_NO_HISTORY_PEPPER";

    /** 注入 pepper 的 Spring 属性名（仅用于错误提示，不含任何密钥内容）。 */
    public static final String PEPPER_PROPERTY_NAME = "trace.batch-no.history-pepper";

    /** 唯一需要注入 pepper 的版本化迁移：V8 历史回填。 */
    public static final MigrationVersion TARGET_MIGRATION_VERSION = MigrationVersion.fromVersion("8");

    /** pepper 的最小强度要求，按 UTF-8 编码后的字节数计（不是 Java char 数）。 */
    public static final int MIN_PEPPER_BYTE_LENGTH = 32;

    /**
     * {@code .env.example} 曾经携带的公开占位值。
     * <p>
     * 该字面量已公开于仓库历史，任何人都可据此离线枚举历史主键，必须与"未配置"同等对待并拒绝。
     * 这不是密钥，而是必须被拉黑的公开值。
     * </p>
     */
    public static final String PLACEHOLDER_PEPPER = "change-me-generate-a-high-entropy-random-secret";

    /**
     * 注入会话变量的固定 SQL 模板（编译期常量）。
     * <p>
     * 模板本身不含、也永远不会含任何形式的密钥；pepper 只经绑定参数传入。
     * </p>
     */
    public static final String SET_PEPPER_SESSION_VARIABLE_SQL = "SET @" + SESSION_VARIABLE_NAME + " = ?";

    private final String pepper;

    public TraceBatchNoHistoryPepperFlywayCallback(
            @Value("${" + PEPPER_PROPERTY_NAME + ":}") String pepper
    ) {
        this.pepper = pepper;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE && isTargetV8Migration(context);
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        // 防御性复判：不依赖 supports 的过滤结果，非 V8 一律不注入、不校验 pepper
        if (event != Event.BEFORE_EACH_MIGRATE || !isTargetV8Migration(context)) {
            return;
        }

        String validatedPepper = requireStrongPepper(pepper);

        // 必须复用 Flyway 执行迁移的同一条连接，否则会话变量对 V8 不可见
        try (PreparedStatement statement =
                     context.getConnection().prepareStatement(SET_PEPPER_SESSION_VARIABLE_SQL)) {
            // pepper 只以绑定参数传入，SQL 文本始终是上面的固定模板
            statement.setString(1, validatedPepper);
            statement.execute();
        } catch (SQLException e) {
            // 只暴露 SQLState / errorCode，绝不回显 pepper、其任何编码形式或原始 SQL
            throw new FlywayException(
                    "注入历史追溯批次号回填密钥会话变量失败 (SQLState=" + e.getSQLState()
                            + ", errorCode=" + e.getErrorCode() + ")"
            );
        }
    }

    @Override
    public String getCallbackName() {
        return "TraceBatchNoHistoryPepper";
    }

    /**
     * 判定当前回调上下文对应的迁移是否正是 V8 历史回填迁移。
     * <p>
     * context、migrationInfo、version 任一为空，或版本是 Flyway 预定义哨兵
     * ({@code EMPTY/LATEST/CURRENT/NEXT})，或是 repeatable（无版本号）迁移时，一律判定为"不是 V8"，
     * 从而绝不会在缺少版本信息的情况下误执行 pepper 注入。
     * </p>
     */
    static boolean isTargetV8Migration(Context context) {
        if (context == null) {
            return false;
        }
        MigrationInfo migrationInfo = context.getMigrationInfo();
        if (migrationInfo == null) {
            return false;
        }
        MigrationVersion version = migrationInfo.getVersion();
        if (version == null || version.isPredefined()) {
            return false;
        }
        return TARGET_MIGRATION_VERSION.equals(version);
    }

    /**
     * 校验 pepper 强度，返回可用于注入的原始值。
     * <p>
     * 依次拒绝：null、空串、纯空白、已公开的占位值、UTF-8 编码后不足
     * {@value #MIN_PEPPER_BYTE_LENGTH} 字节的弱值。
     * 长度按 UTF-8 字节数而非 Java char 数计量，避免多字节字符导致的强度误判。
     * 校验通过后返回<b>未经修剪的原始值</b>：摘要输入必须与配置值逐字节一致，任何规范化都会破坏历史结果的可复现性。
     * </p>
     * <p>
     * 所有异常信息只描述配置要求与变量名，绝不包含 pepper 本身、其长度以外的任何特征或其编码形式。
     * </p>
     */
    static String requireStrongPepper(String rawPepper) {
        if (rawPepper == null || rawPepper.isBlank()) {
            throw new FlywayException(configurationRequirementMessage("未配置或为空白"));
        }
        if (PLACEHOLDER_PEPPER.equalsIgnoreCase(rawPepper.strip())) {
            throw new FlywayException(configurationRequirementMessage("仍为公开示例占位值"));
        }
        if (rawPepper.getBytes(StandardCharsets.UTF_8).length < MIN_PEPPER_BYTE_LENGTH) {
            throw new FlywayException(configurationRequirementMessage(
                    "强度不足，UTF-8 编码后不足 " + MIN_PEPPER_BYTE_LENGTH + " 字节"));
        }
        return rawPepper;
    }

    /** 构造只含配置要求与变量名、绝不含密钥内容的异常信息。 */
    private static String configurationRequirementMessage(String reason) {
        return "历史追溯批次号回填密钥" + reason + "：请通过环境变量 " + PEPPER_ENV_VARIABLE
                + "（或 Spring 属性 " + PEPPER_PROPERTY_NAME + "）配置一个 UTF-8 编码后不少于 "
                + MIN_PEPPER_BYTE_LENGTH + " 字节的高熵随机值，"
                + "不得使用示例占位值，系统禁止回退到任何公开默认值。生成示例：openssl rand -base64 48";
    }
}
