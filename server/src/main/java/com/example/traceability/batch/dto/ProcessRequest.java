package com.example.traceability.batch.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 加工厂加工请求:消耗原料批次,产出成品批次。
 */
public record ProcessRequest(
        @NotNull(message = "原料批次ID不能为空")
        @Positive(message = "原料批次ID必须为正整数")
        Long sourceBatchId,

        @NotNull(message = "消耗数量不能为空")
        @DecimalMin(value = "0.001", inclusive = false, message = "消耗数量必须大于 0")
        BigDecimal consumedQuantity,

        @NotNull(message = "产出商品ID不能为空")
        @Positive(message = "产出商品ID必须为正整数")
        Long outputProductId,

        @NotNull(message = "产出数量不能为空")
        @DecimalMin(value = "0.001", inclusive = false, message = "产出数量必须大于 0")
        BigDecimal outputQuantity
) {
}
