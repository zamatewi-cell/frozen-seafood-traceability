package com.example.traceability.masterdata.dto;

import com.example.traceability.masterdata.domain.TemperatureRule;
import com.example.traceability.masterdata.domain.TemperatureRuleStage;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 温控基准规则方案响应 DTO。
 * <p>
 * 严禁暴露内部逻辑删除字段 {@code isDeleted}。
 * 遵循 API 契约，输出带明确 UTC 偏移的 ISO 8601 时间戳。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TemperatureRuleResponse(
        Long id,
        Long productId,
        Integer versionNo,
        String name,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime effectiveFrom,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime effectiveTo,
        String status,
        String basisNote,
        List<TemperatureRuleStageResponse> stages,
        Long version,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime createdAt,
        Long createdBy,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime updatedAt,
        Long updatedBy
) {
    public static TemperatureRuleResponse of(TemperatureRule r, List<TemperatureRuleStage> stageEntities) {
        if (r == null) {
            return null;
        }
        List<TemperatureRuleStageResponse> stageDtos = stageEntities != null
                ? stageEntities.stream().map(TemperatureRuleStageResponse::fromEntity).toList()
                : List.of();

        return new TemperatureRuleResponse(
                r.getId(),
                r.getProductId(),
                r.getVersionNo(),
                r.getName(),
                r.getEffectiveFrom() != null ? r.getEffectiveFrom().atOffset(ZoneOffset.UTC) : null,
                r.getEffectiveTo() != null ? r.getEffectiveTo().atOffset(ZoneOffset.UTC) : null,
                r.getStatus(),
                r.getBasisNote(),
                stageDtos,
                r.getVersion(),
                r.getCreatedAt() != null ? r.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                r.getCreatedBy(),
                r.getUpdatedAt() != null ? r.getUpdatedAt().atOffset(ZoneOffset.UTC) : null,
                r.getUpdatedBy()
        );
    }
}
