package com.example.traceability.common.envelope;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 统一响应元数据对象 (Response Metadata)。
 * <p>
 * 包含全链路唯一请求追踪 ID、带时区偏移的 ISO 8601 时间戳及可选的分页元数据。
 * 当非分页请求时，{@code page} 为 {@code null}，将通过 Jackson 的 Non-Null 机制自动忽略。
 * </p>
 *
 * @param requestId 请求追踪 ID（与响应头 X-Request-Id 一致）
 * @param timestamp 响应生成时间（带时区偏移的 ISO 8601 字符串）
 * @param page      分页元数据（仅分页请求时存在，非分页时为 null）
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseMeta(
    String requestId,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
    OffsetDateTime timestamp,
    PageMeta page
) {

    /**
     * MDC 中存储链路追踪 ID 的固定键名。
     */
    public static final String MDC_REQUEST_ID_KEY = "requestId";

    /**
     * 便捷构造器：构造不含分页元数据的通用元数据对象。
     *
     * @param requestId 请求追踪 ID
     * @param timestamp 带时区的时间戳
     */
    public ResponseMeta(String requestId, OffsetDateTime timestamp) {
        this(requestId, timestamp, null);
    }

    /**
     * 静态工厂方法：自动从当前线程 MDC 提取 requestId 创建当前时刻元数据。若 MDC 不存在则自动生成 UUID。
     *
     * @return 响应元数据对象
     */
    public static ResponseMeta now() {
        return now(currentRequestId());
    }

    /**
     * 静态工厂方法：使用指定的 requestId 创建当前时刻元数据。
     *
     * @param requestId 显式指定的请求追踪 ID
     * @return 响应元数据对象
     */
    public static ResponseMeta now(String requestId) {
        String effectiveId = (requestId != null && !requestId.isBlank()) ? requestId : currentRequestId();
        return new ResponseMeta(effectiveId, OffsetDateTime.now());
    }

    /**
     * 静态工厂方法：自动从 MDC 提取 requestId 创建包含分页元数据的元数据对象。
     *
     * @param page 分页元数据
     * @return 响应元数据对象
     */
    public static ResponseMeta ofPage(PageMeta page) {
        return ofPage(page, currentRequestId());
    }

    /**
     * 静态工厂方法：使用指定的 requestId 创建包含分页元数据的元数据对象。
     *
     * @param page      分页元数据
     * @param requestId 显式指定的请求追踪 ID
     * @return 响应元数据对象
     */
    public static ResponseMeta ofPage(PageMeta page, String requestId) {
        String effectiveId = (requestId != null && !requestId.isBlank()) ? requestId : currentRequestId();
        return new ResponseMeta(effectiveId, OffsetDateTime.now(), page);
    }

    /**
     * 提取当前链路追踪 ID。优先从 SLF4J MDC 中获取，若缺失则退化生成随机 UUID。
     *
     * @return 请求追踪 ID
     */
    private static String currentRequestId() {
        String reqId = MDC.get(MDC_REQUEST_ID_KEY);
        if (reqId != null && !reqId.isBlank()) {
            return reqId;
        }
        return UUID.randomUUID().toString();
    }
}
