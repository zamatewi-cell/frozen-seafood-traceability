package com.example.traceability.quality.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 关闭模拟召回请求（Phase B PB5）：受控的公开处置结论（DESTROYED / RETURNED，消费者页面只显示对应的固定文案）与内部处置总结。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record RecallCloseRequest(
        String publicDisposition,
        String resultSummary,

        @JsonAnySetter
        Map<String, Object> unknownFields
) {

    public RecallCloseRequest {
        unknownFields = unknownFields == null ? Map.of() : new LinkedHashMap<>(unknownFields);
    }

    /**
     * 不含未知字段的便捷构造器（服务端内部与测试使用）。
     */
    public RecallCloseRequest(String publicDisposition, String resultSummary) {
        this(publicDisposition, resultSummary, Map.of());
    }
}
