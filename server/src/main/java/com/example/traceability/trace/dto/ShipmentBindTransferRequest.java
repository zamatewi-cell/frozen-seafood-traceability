package com.example.traceability.trace.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 发货方把 DRAFT 交接绑定到 PLANNED 运输任务请求。
 *
 * @param transferId              待绑定交接 ID
 * @param expectedTransferVersion 交接期望乐观锁版本号
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
public record ShipmentBindTransferRequest(
        @NotNull(message = "交接 transferId 不能为空")
        Long transferId,

        @NotNull(message = "交接期望乐观锁版本号不能为空")
        @Min(value = 0, message = "交接期望乐观锁版本号不能小于 0")
        Long expectedTransferVersion
) {
}
