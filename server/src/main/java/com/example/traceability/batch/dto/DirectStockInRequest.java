package com.example.traceability.batch.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 捕捞船长直接入库请求(自捕自产,无需上游订单)。
 */
public record DirectStockInRequest(
        @NotNull(message = "商品ID不能为空")
        @Positive(message = "商品ID必须为正整数")
        Long productId,

        @NotNull(message = "入库数量不能为空")
        @DecimalMin(value = "0.001", inclusive = false, message = "入库数量必须大于 0")
        BigDecimal quantity,

        String batchNo,
        String originText,
        LocalDate captureDate
) {
}
