package com.example.traceability.trace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 追溯事件更正请求 DTO。
 * <p>
 * 更正操作必须提供非空更正原因说明 correctionReason。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record CorrectTraceEventRequest(
        @NotBlank(message = "事件类型 eventType 不能为空")
        String eventType,

        @NotNull(message = "业务发生时间 occurredAt 不能为空")
        OffsetDateTime occurredAt,

        Long siteId,

        @NotBlank(message = "数据来源 dataSource 不能为空")
        String dataSource,

        @NotBlank(message = "事件摘要 summary 不能为空")
        @Size(max = 500, message = "事件摘要 summary 长度不得超过 500 个字符")
        String summary,

        Map<String, Object> detailsJson,

        @NotBlank(message = "更正原因 correctionReason 不能为空")
        @Size(max = 500, message = "更正原因 correctionReason 长度不得超过 500 个字符")
        String correctionReason
) {
}
