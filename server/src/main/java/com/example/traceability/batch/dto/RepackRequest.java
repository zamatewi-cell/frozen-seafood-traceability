package com.example.traceability.batch.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 分拣分装请求：消耗一个上游批次，产出同种产品的 DISTRIBUTION 批次。
 * 分拣不改产品，仅改变包装/规格，因此产出商品沿用来源批次的产品。
 */
public record RepackRequest(
        @NotNull(message = "来源批次ID不能为空")
        @Positive(message = "来源批次ID必须为正整数")
        Long sourceBatchId,

        @NotNull(message = "消耗数量不能为空")
        @DecimalMin(value = "0.001", inclusive = false, message = "消耗数量必须大于 0")
        BigDecimal consumedQuantity,

        @NotNull(message = "产出数量不能为空")
        @DecimalMin(value = "0.001", inclusive = false, message = "产出数量必须大于 0")
        BigDecimal outputQuantity
) {
}
