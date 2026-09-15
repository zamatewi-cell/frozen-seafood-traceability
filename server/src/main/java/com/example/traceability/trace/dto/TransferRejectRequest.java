package com.example.traceability.trace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;

import java.time.OffsetDateTime;

/**
 * 接收方拒收企业交接请求。
 * <p>
 * 接收方拒绝货物交接，终结交接流程；不转移批次持有权，不写入追溯事件，但必须如实记录拒收原因与决定时间。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TransferRejectRequest(
        @NotBlank(message = "拒收原因说明不能为空")
        @Size(max = 500, message = "拒收原因说明不能超过500字符")
        String reason,

        @NotNull(message = "拒收业务发生时间不能为空")
        OffsetDateTime occurredAt,

        @NotNull(message = "期望乐观锁版本号不能为空")
        @Min(value = 0, message = "期望乐观锁版本号不能小于 0")
        Long expectedVersion
) {
}
