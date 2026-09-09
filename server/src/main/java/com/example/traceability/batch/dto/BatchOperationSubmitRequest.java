package com.example.traceability.batch.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 批次操作提交请求 DTO。
 * <p>
 * 携带客户端期望的批次操作当前乐观锁版本号 {@code version}，防止并发冲突。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchOperationSubmitRequest(
        @NotNull(message = "乐观锁版本号 version 不能为空")
        @Min(value = 0, message = "乐观锁版本号 version 必须大于等于 0")
        Long version
) {
}
