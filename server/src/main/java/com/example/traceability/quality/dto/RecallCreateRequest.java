package com.example.traceability.quality.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发起模拟召回请求（Phase B PB5）：召回种子批次（发起组织当前持有）、原因与可选来源告警。
 * 召回编号、影响范围、状态与时间全部由服务端决定；未在契约中声明的字段被收集到 {@code unknownFields} 并被服务端拒绝。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record RecallCreateRequest(
        List<Long> batchIds,
        String reason,
        Long alertId,

        @JsonAnySetter
        Map<String, Object> unknownFields
) {

    public RecallCreateRequest {
        unknownFields = unknownFields == null ? Map.of() : new LinkedHashMap<>(unknownFields);
    }

    /**
     * 不含未知字段的便捷构造器（服务端内部与测试使用）。
     */
    public RecallCreateRequest(List<Long> batchIds, String reason, Long alertId) {
        this(batchIds, reason, alertId, Map.of());
    }
}
