package com.example.traceability.trace.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 发送方提交企业交接请求。
 * <p>
 * 提交前交接必须已绑定 PLANNED 运输任务；提交后交接进入 PENDING 并形成批次业务排他预留。
 * 物理发运时间由承运商确认装载时写入 shipment.loaded_at，Transfer 提交不再承载发运时间。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TransferSubmitRequest(
        @NotNull(message = "期望乐观锁版本号不能为空")
        @Min(value = 0, message = "期望乐观锁版本号不能小于 0")
        Long expectedVersion
) {
}
