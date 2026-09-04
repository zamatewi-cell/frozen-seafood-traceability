package com.example.traceability.common.exception;

/**
 * 字段级参数校验失败条目 (Field Error Item)。
 * <p>
 * 遵循 RFC 9457 扩展规范与 OpenAPI 3.1 错误契约，输出在 {@link Problem#getFieldErrors()} 中。
 * </p>
 *
 * @param field   校验失败的属性路径或字段名称（如 "batchNo"、"quantity"）
 * @param code    触发校验失败的约束规则代码（如 "NotBlank"、"Positive"）
 * @param message 面向调用方或前端提示的友好错误消息
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record FieldErrorItem(
    String field,
    String code,
    String message
) {}
