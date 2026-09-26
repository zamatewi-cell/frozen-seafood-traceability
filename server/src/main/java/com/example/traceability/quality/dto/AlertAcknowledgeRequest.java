package com.example.traceability.quality.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 确认告警请求（Phase B PB3）：只接受可选说明 {@code note}；确认人、时间与状态由服务端决定。
 * 未在契约中声明的字段被收集到 {@code unknownFields} 并被服务端拒绝，而不是静默忽略。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record AlertAcknowledgeRequest(
        String note,

        @JsonAnySetter
        Map<String, Object> unknownFields
) {

    public AlertAcknowledgeRequest {
        unknownFields = unknownFields == null ? Map.of() : new LinkedHashMap<>(unknownFields);
    }

    /**
     * 不含未知字段的便捷构造器（服务端内部与测试使用）。
     */
    public AlertAcknowledgeRequest(String note) {
        this(note, Map.of());
    }
}
