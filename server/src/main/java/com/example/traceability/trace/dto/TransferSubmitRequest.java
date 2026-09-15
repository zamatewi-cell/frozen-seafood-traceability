package com.example.traceability.trace.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

import java.time.OffsetDateTime;

/**
 * 发送方提交企业交接请求。
 * <p>
 * 提交后交接进入 PENDING 状态并形成批次业务排他预留；要求提供实际发货业务时间与 expectedVersion。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TransferSubmitRequest(
        @NotNull(message = "实际发货业务时间不能为空")
        OffsetDateTime shippedAt,

        @NotNull(message = "期望乐观锁版本号不能为空")
        @Min(value = 0, message = "期望乐观锁版本号不能小于 0")
        Long expectedVersion
) {
}
