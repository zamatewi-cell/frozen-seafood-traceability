package com.example.traceability.batch.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 来源批次草稿创建请求 DTO。
 * <p>
 * 企业端公开的批次创建接口只用于建立来源批次（SOURCE）：批次类型、traceBatchNo、当前责任组织、创建组织、
 * 流转状态与风险状态全部由服务端决定，本 DTO 不声明这些字段。
 * 任何未在契约中声明的属性（例如客户端夹带的 batchType、traceBatchNo、orgId、flowStatus）
 * 都会被收集到 {@link #unknownFields()}，并由应用服务以 400 INVALID_REQUEST 明确拒绝，而不是静默忽略。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchCreateRequest(
        @Size(max = 64, message = "外部业务批次号长度不能超过 64")
        String externalBatchNo,

        @NotNull(message = "产品ID不能为空")
        @Positive(message = "产品ID必须为正整数")
        Long productId,

        @NotNull(message = "批次数量不能为空")
        @DecimalMin(value = "0.000", inclusive = false, message = "批次数量必须大于 0")
        @Digits(integer = 15, fraction = 3, message = "批次数量整数最多 15 位且小数最多 3 位")
        BigDecimal quantity,

        @NotBlank(message = "计量单位不能为空")
        String unitCode,

        @NotBlank(message = "来源类型不能为空")
        String originType,

        @NotBlank(message = "产地来源描述不能为空")
        @Size(max = 255, message = "产地来源描述长度不能超过 255")
        String originText,

        LocalDate productionDate,

        LocalDate captureDate,

        LocalDate freezeDate,

        @Min(value = 1, message = "保质期天数必须大于 0")
        Integer shelfLifeDays,

        @JsonAnySetter
        Map<String, Object> unknownFields
) {

    public BatchCreateRequest {
        unknownFields = unknownFields == null ? Map.of() : new LinkedHashMap<>(unknownFields);
    }

    /**
     * 不含未知字段的便捷构造器（服务端内部与测试使用）。
     */
    public BatchCreateRequest(
            String externalBatchNo,
            Long productId,
            BigDecimal quantity,
            String unitCode,
            String originType,
            String originText,
            LocalDate productionDate,
            LocalDate captureDate,
            LocalDate freezeDate,
            Integer shelfLifeDays
    ) {
        this(externalBatchNo, productId, quantity, unitCode, originType, originText,
                productionDate, captureDate, freezeDate, shelfLifeDays, Map.of());
    }
}
