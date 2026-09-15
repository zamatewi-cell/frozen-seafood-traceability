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
 * 接收方接受企业交接请求。
 * <p>
 * 接收方确认收货并转移批次持有权；若实收数量与发货数量快照不同，强制要求填写 differenceReason。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TransferAcceptRequest(
        @NotNull(message = "实收数量不能为空")
        @DecimalMin(value = "0.001", message = "实收数量必须大于0")
        @Digits(integer = 15, fraction = 3, message = "实收数量整数最多 15 位且小数最多 3 位")
        BigDecimal receivedQuantity,

        @NotBlank(message = "计量单位不能为空")
        String unitCode,

        @NotNull(message = "收货验收业务时间不能为空")
        OffsetDateTime occurredAt,

        @Size(max = 500, message = "数量差异原因说明不能超过500字符")
        String differenceReason,

        @NotNull(message = "期望乐观锁版本号不能为空")
        @Min(value = 0, message = "期望乐观锁版本号不能小于 0")
        Long expectedVersion
) {
}
