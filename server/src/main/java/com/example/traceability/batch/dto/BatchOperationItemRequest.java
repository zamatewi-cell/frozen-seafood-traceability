package com.example.traceability.batch.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 批次操作明细项目请求 DTO。
 * <p>
 * 项目规则（Phase A Slice 3）：
 * <ul>
 *   <li>{@code role}：INPUT、OUTPUT、LOSS、WASTE、SAMPLE</li>
 *   <li>{@code batchId}：仅 INPUT 必须提供；OUTPUT 批次由服务端生成，严禁提供；LOSS/WASTE/SAMPLE 严禁提供</li>
 *   <li>{@code quantity}：必须大于 0，最多 3 位小数；INPUT 必须等于输入批次当前全部剩余量</li>
 *   <li>{@code unitCode}：严格限定为 {@code kg}</li>
 *   <li>{@code productId} / {@code externalBatchNo} / {@code shelfLifeDays}：仅 OUTPUT 可选提供，
 *       分别表示产出批次的产品（PROCESS 默认沿用输入产品；SPLIT 只能沿用输入产品）、企业外部批号与保质期天数</li>
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
        String unitCode,

        @Positive(message = "产出产品 ID 必须为正整数")
        Long productId,

        @Size(max = 64, message = "产出批次外部批号最多 64 个字符")
        String externalBatchNo,

        @Positive(message = "保质期天数必须大于 0")
        Integer shelfLifeDays
) {
    public BatchOperationItemRequest(String role, Long batchId, BigDecimal quantity) {
        this(role, batchId, quantity, "kg", null, null, null);
    }

    public BatchOperationItemRequest(String role, Long batchId, BigDecimal quantity, String unitCode) {
        this(role, batchId, quantity, unitCode, null, null, null);
    }
}
