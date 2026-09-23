package com.example.traceability.sale.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 终端销售提交请求 DTO。
 * <p>
 * 只接受销售门店、销售数量与业务发生时间；计量单位取自批次，销售组织取自当前主体，剩余量由服务端派生。
 * 任何未声明字段（例如 remainingQuantity、orgId、unitCode、customer 等）都会收集到 {@link #unknownFields()}，
 * 由应用服务以 400 INVALID_REQUEST 明确拒绝，而不是静默忽略。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record SaleCreateRequest(
        @NotNull(message = "销售门店 siteId 不能为空")
        @Positive(message = "销售门店 siteId 必须为正整数")
        Long siteId,

        @NotNull(message = "销售数量 quantity 不能为空")
        @DecimalMin(value = "0.000", inclusive = false, message = "销售数量必须大于 0")
        @Digits(integer = 15, fraction = 3, message = "销售数量整数最多 15 位且小数最多 3 位")
        BigDecimal quantity,

        @NotNull(message = "销售发生时间 occurredAt 不能为空")
        OffsetDateTime occurredAt,

        @JsonAnySetter
        Map<String, Object> unknownFields
) {

    public SaleCreateRequest {
        unknownFields = unknownFields == null ? Map.of() : new LinkedHashMap<>(unknownFields);
    }

    /**
     * 不含未知字段的便捷构造器（服务端内部与测试使用）。
     */
    public SaleCreateRequest(Long siteId, BigDecimal quantity, OffsetDateTime occurredAt) {
        this(siteId, quantity, occurredAt, Map.of());
    }
}
