package com.example.traceability.masterdata.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 创建温控规则环节明细请求 DTO。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TemperatureRuleStageCreateRequest(

        @NotBlank(message = "供应链环节代码不能为空")
        @Size(max = 32, message = "环节代码长度不能超过 32 个字符")
        String stageCode,

        @NotNull(message = "温度下限阈值不能为空")
        @Digits(integer = 4, fraction = 2, message = "温度下限阈值整数位最多4位，小数位最多2位")
        BigDecimal lowerLimit,

        @NotNull(message = "温度上限阈值不能为空")
        @Digits(integer = 4, fraction = 2, message = "温度上限阈值整数位最多4位，小数位最多2位")
        BigDecimal upperLimit,

        @NotBlank(message = "温标单位不能为空")
        @Size(max = 16, message = "温标单位长度不能超过 16 个字符")
        String unitCode,

        @Min(value = 0, message = "允许越界缓冲秒数必须大于或等于0")
        Integer allowedDurationSeconds,

        @Min(value = 1, message = "环节展示次序必须大于或等于1")
        Integer sequenceNo
) {
    public TemperatureRuleStageCreateRequest {
        if (allowedDurationSeconds == null) {
            allowedDurationSeconds = 0;
        }
        if (sequenceNo == null) {
            sequenceNo = 1;
        }
    }
}
