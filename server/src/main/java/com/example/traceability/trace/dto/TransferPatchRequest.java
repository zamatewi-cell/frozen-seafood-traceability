package com.example.traceability.trace.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

/**
 * 修改企业间整批交接草稿请求。
 * <p>
 * 仅支持在 DRAFT 草稿阶段由发送方修改接收组织；强制要求 expectedVersion 乐观锁校验。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TransferPatchRequest(
        @NotNull(message = "接收方企业ID不能为空")
        Long receiverOrgId,

        @NotNull(message = "期望乐观锁版本号不能为空")
        @Min(value = 0, message = "期望乐观锁版本号不能小于 0")
        Long expectedVersion
) {
}
