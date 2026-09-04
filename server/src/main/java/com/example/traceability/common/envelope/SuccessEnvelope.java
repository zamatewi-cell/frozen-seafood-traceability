package com.example.traceability.common.envelope;

/**
 * 统一成功响应包装器 (SuccessEnvelope)。
 * <p>
 * 严格遵循 OpenAPI 3.1 契约规范，顶层必须且仅包含 {@code data} 载荷与 {@code meta} 元数据两部分，
 * 严禁使用旧式的 {@code { code: 200, message: "success" }} 格式。
 * </p>
 *
 * @param <T>  业务数据载荷类型
 * @param data 业务数据载荷（单实体对象、列表或操作结果）
 * @param meta 响应元数据（包含 requestId、带时区的时间戳、分页信息等）
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record SuccessEnvelope<T>(
    T data,
    ResponseMeta meta
) {

    /**
     * 构建包含指定数据载荷的成功响应包装器，自动读取链路追踪 ID 与当前时间。
     *
     * @param data 业务数据载荷
     * @param <T>  载荷类型
     * @return 成功响应包装器
     */
    public static <T> SuccessEnvelope<T> of(T data) {
        return new SuccessEnvelope<>(data, ResponseMeta.now());
    }

    /**
     * 构建包含指定数据载荷与显式请求 ID 的成功响应包装器。
     *
     * @param data      业务数据载荷
     * @param requestId 请求追踪 ID
     * @param <T>       载荷类型
     * @return 成功响应包装器
     */
    public static <T> SuccessEnvelope<T> of(T data, String requestId) {
        return new SuccessEnvelope<>(data, ResponseMeta.now(requestId));
    }

    /**
     * 构建包含分页元数据的成功响应包装器，自动读取链路追踪 ID。
     *
     * @param data     数据载荷（通常为分页列表）
     * @param pageMeta 分页元数据
     * @param <T>      载荷类型
     * @return 成功响应包装器
     */
    public static <T> SuccessEnvelope<T> ofPage(T data, PageMeta pageMeta) {
        return new SuccessEnvelope<>(data, ResponseMeta.ofPage(pageMeta));
    }

    /**
     * 构建包含分页元数据与显式请求 ID 的成功响应包装器。
     *
     * @param data      数据载荷（通常为分页列表）
     * @param pageMeta  分页元数据
     * @param requestId 请求追踪 ID
     * @param <T>       载荷类型
     * @return 成功响应包装器
     */
    public static <T> SuccessEnvelope<T> ofPage(T data, PageMeta pageMeta, String requestId) {
        return new SuccessEnvelope<>(data, ResponseMeta.ofPage(pageMeta, requestId));
    }
}
