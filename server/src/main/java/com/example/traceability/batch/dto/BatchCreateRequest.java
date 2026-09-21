package com.example.traceability.batch.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 批次草稿创建请求 DTO。
 * <p>
 * 绝不声明 traceBatchNo（由服务端在创建时安全生成全局唯一追溯批号），
 * 仅接受企业可选的外部业务批次号 externalBatchNo。
 * 包含必填的产品、类型、数量、单位、来源类型及来源说明，以及可选的生产/捕捞/速冻日期和保质期天数。
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

        @NotBlank(message = "批次类型不能为空")
        String batchType,

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
        Integer shelfLifeDays
) {
}
