package com.example.traceability.trace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 追溯事件普通创建请求 DTO。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record CreateTraceEventRequest(
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

        Map<String, Object> detailsJson
) {
}
