package com.example.traceability.batch.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 批次草稿增量更新请求 DTO。
 * <p>
 * 仅允许修改草稿状态批次的可变字段（数量、产地描述、生产/捕捞/速冻日期和保质期天数）。
 * 必须携带乐观锁版本号 {@code version} 进行条件更新。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchPatchRequest(
        @NotNull(message = "乐观锁版本号 version 不能为空")
        @Min(value = 0, message = "乐观锁版本号 version 必须非负")
        Long version,

        @DecimalMin(value = "0.000", inclusive = false, message = "批次数量必须大于 0")
        @Digits(integer = 15, fraction = 3, message = "批次数量整数最多 15 位且小数最多 3 位")
        BigDecimal quantity,

        @Size(max = 255, message = "产地来源描述长度不能超过 255")
        String originText,

        LocalDate productionDate,

        LocalDate captureDate,

        LocalDate freezeDate,

        @Min(value = 1, message = "保质期天数必须大于 0")
        Integer shelfLifeDays
) {
}
