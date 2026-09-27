package com.example.traceability.trace.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 接收方隔离收货请求（Phase B PB4；契约 v1.1 §7.3 / §10.3）。
 * <p>
 * 货物已物理到达：登记实收数量（与交接数量不一致时必须说明差异原因）、隔离场所（接收方本组织启用场所）与隔离原因。
 * 隔离不转移批次责任组织、不改变批次数量或风险状态，也不生成追溯事件。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TransferQuarantineRequest(
        @NotNull(message = "实收数量不能为空")
        @DecimalMin(value = "0.001", message = "实收数量必须大于0")
        @Digits(integer = 15, fraction = 3, message = "实收数量整数最多 15 位且小数最多 3 位")
        BigDecimal receivedQuantity,

        @NotBlank(message = "计量单位不能为空")
        String unitCode,

        @NotNull(message = "到货业务时间不能为空")
        OffsetDateTime occurredAt,

        @Size(max = 500, message = "数量差异原因说明不能超过500字符")
        String differenceReason,

        @NotNull(message = "隔离场所不能为空")
        @Min(value = 1, message = "隔离场所 quarantineSiteId 必须为正整数")
        Long quarantineSiteId,

        @NotBlank(message = "隔离原因不能为空")
        @Size(max = 500, message = "隔离原因不能超过500字符")
        String reason,

        @NotNull(message = "期望乐观锁版本号不能为空")
        @Min(value = 0, message = "期望乐观锁版本号不能小于 0")
        Long expectedVersion
) {
}
