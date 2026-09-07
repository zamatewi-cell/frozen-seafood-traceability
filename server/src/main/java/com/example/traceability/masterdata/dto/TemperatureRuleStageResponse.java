package com.example.traceability.masterdata.dto;

import com.example.traceability.masterdata.domain.TemperatureRuleStage;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 温控规则环节明细响应 DTO。
 * <p>
 * 严禁暴露内部逻辑删除字段 {@code isDeleted}。
 * 遵循 API 契约，输出带明确 UTC 偏移的 ISO 8601 时间戳。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TemperatureRuleStageResponse(
        Long id,
        Long ruleId,
        String stageCode,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        String unitCode,
        Integer allowedDurationSeconds,
        Integer sequenceNo,
        Long version,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime createdAt,
        Long createdBy,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime updatedAt,
        Long updatedBy
) {
    public static TemperatureRuleStageResponse fromEntity(TemperatureRuleStage s) {
        if (s == null) {
            return null;
        }
        return new TemperatureRuleStageResponse(
                s.getId(),
                s.getRuleId(),
                s.getStageCode(),
                s.getLowerLimit(),
                s.getUpperLimit(),
                s.getUnitCode(),
                s.getAllowedDurationSeconds(),
                s.getSequenceNo(),
                s.getVersion(),
                s.getCreatedAt() != null ? s.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                s.getCreatedBy(),
                s.getUpdatedAt() != null ? s.getUpdatedAt().atOffset(ZoneOffset.UTC) : null,
                s.getUpdatedBy()
        );
    }
}
