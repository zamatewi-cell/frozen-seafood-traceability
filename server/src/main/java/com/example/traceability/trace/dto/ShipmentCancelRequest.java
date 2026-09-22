package com.example.traceability.trace.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 发货方取消运输任务请求 (PLANNED → CANCELLED)。
 *
 * @param reason          取消原因
 * @param expectedVersion 运输任务期望乐观锁版本号
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
public record ShipmentCancelRequest(
        @NotBlank(message = "取消原因 reason 不能为空")
        @Size(max = 500, message = "取消原因长度不得超过 500 个字符")
        String reason,

        @NotNull(message = "期望乐观锁版本号不能为空")
        @Min(value = 0, message = "期望乐观锁版本号不能小于 0")
        Long expectedVersion
) {
}
