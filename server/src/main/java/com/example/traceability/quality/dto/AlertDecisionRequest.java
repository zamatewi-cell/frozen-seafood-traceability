package com.example.traceability.quality.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警质量处置请求（Phase B PB4）：依据检验结论放行受影响批次时的可选说明 {@code note}，或形成处置结论时必填的
 * {@code resolution}。决定人、时间、依据报告与状态全部由服务端决定；未在契约中声明的字段被收集到 {@code unknownFields}
 * 并被服务端拒绝。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record AlertDecisionRequest(
        String note,
        String resolution,

        @JsonAnySetter
        Map<String, Object> unknownFields
) {

    public AlertDecisionRequest {
        unknownFields = unknownFields == null ? Map.of() : new LinkedHashMap<>(unknownFields);
    }

    /** 放行说明（服务端内部与测试使用）。 */
    public static AlertDecisionRequest release(String note) {
        return new AlertDecisionRequest(note, null, Map.of());
    }

    /** 处置结论（服务端内部与测试使用）。 */
    public static AlertDecisionRequest resolve(String resolution) {
        return new AlertDecisionRequest(null, resolution, Map.of());
    }
}
