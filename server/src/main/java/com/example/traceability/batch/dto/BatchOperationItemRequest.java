package com.example.traceability.batch.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 批次操作明细项目请求 DTO。
 * <p>
 * 项目规则：
 * <ul>
 *   <li>{@code role}：INPUT、OUTPUT、LOSS、WASTE、SAMPLE</li>
 *   <li>{@code batchId}：INPUT/OUTPUT 必须提供且为正数；LOSS/WASTE/SAMPLE 严禁提供</li>
 *   <li>{@code quantity}：必须大于 0，最多 3 位小数</li>
 *   <li>{@code unitCode}：Phase 1 严格限定为 {@code kg}</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchOperationItemRequest(
        @NotBlank(message = "项目角色 role 不能为空")
        String role,

        @Positive(message = "关联批次 ID 必须为正整数")
        Long batchId,

        @NotNull(message = "数量 quantity 不能为空")
        @DecimalMin(value = "0.000", inclusive = false, message = "数量必须大于 0")
        @Digits(integer = 15, fraction = 3, message = "数量整数最多 15 位且小数最多 3 位")
        BigDecimal quantity,

        @NotBlank(message = "计量单位 unitCode 不能为空")
        String unitCode
) {
    public BatchOperationItemRequest(String role, Long batchId, BigDecimal quantity) {
        this(role, batchId, quantity, "kg");
    }
}
