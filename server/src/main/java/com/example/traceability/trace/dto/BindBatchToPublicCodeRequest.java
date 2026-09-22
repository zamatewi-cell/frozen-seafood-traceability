package com.example.traceability.trace.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 将物理批次绑定到公开追溯码的请求。
 */
public record BindBatchToPublicCodeRequest(
        @NotBlank(message = "publicId 不能为空")
        String publicId,

        @jakarta.validation.constraints.NotNull(message = "batchId 不能为空")
        Long batchId,

        String bindRole
) {
}