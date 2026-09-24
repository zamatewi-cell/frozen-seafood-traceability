package com.example.traceability.batch.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人工风险冻结 / 解除冻结请求 DTO。
 * <p>
 * 只接受原因 {@code reason}（去除首尾空白后 1..500 个字符，由应用服务校验长度）。
 * 目标状态由接口路径决定，转换时间由服务端生成（UTC），来源类型固定为 MANUAL，组织与操作人取自当前主体；
 * 任何未声明字段（例如 targetStatus、occurredAt、orgId、sourceType）都会收集到 {@link #unknownFields()}，
 * 由应用服务以 400 INVALID_REQUEST 明确拒绝，而不是静默忽略。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchRiskTransitionRequest(
        @NotBlank(message = "原因 reason 不能为空")
        String reason,

        @JsonAnySetter
        Map<String, Object> unknownFields
) {

    public BatchRiskTransitionRequest {
        unknownFields = unknownFields == null ? Map.of() : new LinkedHashMap<>(unknownFields);
    }

    /**
     * 不含未知字段的便捷构造器（服务端内部与测试使用）。
     */
    public BatchRiskTransitionRequest(String reason) {
        this(reason, Map.of());
    }
}
