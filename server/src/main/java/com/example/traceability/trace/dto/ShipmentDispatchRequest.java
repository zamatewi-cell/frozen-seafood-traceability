package com.example.traceability.trace.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * 承运商确认装载发运请求 (PLANNED → IN_TRANSIT)。
 *
 * @param loadedAt        装载发运业务时间
 * @param expectedVersion 运输任务期望乐观锁版本号（装载清单变化会递增版本）
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
public record ShipmentDispatchRequest(
        @NotNull(message = "装载发运时间 loadedAt 不能为空")
        OffsetDateTime loadedAt,

        @NotNull(message = "期望乐观锁版本号不能为空")
        @Min(value = 0, message = "期望乐观锁版本号不能小于 0")
        Long expectedVersion
) {
}
