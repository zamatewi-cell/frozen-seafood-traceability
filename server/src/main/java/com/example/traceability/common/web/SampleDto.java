package com.example.traceability.common.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 示例请求数据传输对象 (Sample DTO)。
 * <p>
 * 用于验证 Jakarta Validation 参数校验在 {@code /api/v1/samples/validate} 接口上的生效机制，
 * 触发时由全局异常处理器统一格式化为 RFC 9457 Problem Details 的 {@code fieldErrors}。
 * </p>
 *
 * @param name     样本名称，必须非空且非全空白字符
 * @param quantity 样本数量，必须非空且必须为正整数
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record SampleDto(
    @NotBlank(message = "样本名称不能为空")
    String name,

    @NotNull(message = "样本数量不能为空")
    @Positive(message = "样本数量必须大于0")
    Integer quantity
) {}
