package com.example.traceability.masterdata.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 创建温控基准规则请求 DTO。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TemperatureRuleCreateRequest(

        @NotBlank(message = "规则方案名称不能为空")
        @Size(min = 1, max = 128, message = "规则方案名称长度不能超过 128 个字符")
        String name,

        @NotNull(message = "规则生效起始时间不能为空")
        OffsetDateTime effectiveFrom,

        OffsetDateTime effectiveTo,

        String basisNote,

        @NotNull(message = "温控规则环节明细列表不能为空")
        @NotEmpty(message = "温控规则至少需要定义一个环节明细")
        List<@Valid TemperatureRuleStageCreateRequest> stages
) {
}
