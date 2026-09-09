package com.example.traceability.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 批次操作草稿创建请求 DTO。
 * <p>
 * 强制包含操作类型、业务实际发生时间、操作备注（可选）及至少 2 个操作明细项目。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchOperationCreateRequest(
        @NotBlank(message = "操作类型 operationType 不能为空")
        String operationType,

        @NotNull(message = "业务发生时间 occurredAt 不能为空")
        OffsetDateTime occurredAt,

        @Size(max = 500, message = "操作备注 note 最多 500 个字符")
        String note,

        @NotNull(message = "操作明细 items 不能为空")
        @Size(min = 2, message = "操作明细 items 至少包含 2 个项目")
        List<@Valid BatchOperationItemRequest> items
) {
}
