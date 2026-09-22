package com.example.traceability.trace.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * 承运商确认物理到达请求 (IN_TRANSIT → DELIVERED)。
 *
 * @param unloadedAt      到达卸货业务时间
 * @param expectedVersion 运输任务期望乐观锁版本号
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
public record ShipmentArriveRequest(
        @NotNull(message = "到达时间 unloadedAt 不能为空")
        OffsetDateTime unloadedAt,

        @NotNull(message = "期望乐观锁版本号不能为空")
        @Min(value = 0, message = "期望乐观锁版本号不能小于 0")
        Long expectedVersion
) {
}
