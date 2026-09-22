package com.example.traceability.batch.dto;

import com.example.traceability.batch.domain.BatchOperation;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 批次操作响应 DTO。
 * <p>
 * 严格白名单投影，绝不泄露 {@code isDeleted}、{@code idempotencyKey} 及 {@code submissionIdempotencyKey} 等内部安全敏感字段。
 * 所有审计与业务时间统一输出带明确 UTC 偏移量的 ISO 8601 格式时间。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchOperationResponse(
        Long id,
        Long orgId,
        String operationNo,
        String operationType,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime occurredAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime recordedAt,
        String status,
        String note,
        Boolean balanced,
        BigDecimal inputTotal,
        BigDecimal outputTotal,
        BigDecimal lossTotal,
        BigDecimal wasteTotal,
        BigDecimal sampleTotal,
        Long version,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime createdAt,
        Long createdBy,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime updatedAt,
        Long updatedBy,
        List<BatchOperationItemResponse> items,
        List<BatchRelationResponse> relations
) {

    public static BatchOperationResponse fromEntity(
            BatchOperation op,
            List<BatchOperationItemResponse> items,
            List<BatchRelationResponse> relations,
            Boolean balanced
    ) {
        return fromEntity(op, items, relations, balanced, null);
    }

    /**
     * 批次操作白名单投影。
     *
     * @param totals 各角色数量合计（按 INPUT、OUTPUT、LOSS、WASTE、SAMPLE 顺序；为 null 时合计字段输出 null）
     */
    public static BatchOperationResponse fromEntity(
            BatchOperation op,
            List<BatchOperationItemResponse> items,
            List<BatchRelationResponse> relations,
            Boolean balanced,
            BigDecimal[] totals
    ) {
        if (op == null) {
            return null;
        }
        return new BatchOperationResponse(
                op.getId(),
                op.getOrgId(),
                op.getOperationNo(),
                op.getOperationType(),
                op.getOccurredAt() != null ? op.getOccurredAt().atOffset(ZoneOffset.UTC) : null,
                op.getRecordedAt() != null ? op.getRecordedAt().atOffset(ZoneOffset.UTC) : null,
                op.getStatus(),
                op.getNote(),
                balanced,
                totals != null ? totals[0] : null,
                totals != null ? totals[1] : null,
                totals != null ? totals[2] : null,
                totals != null ? totals[3] : null,
                totals != null ? totals[4] : null,
                op.getVersion(),
                op.getCreatedAt() != null ? op.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                op.getCreatedBy(),
                op.getUpdatedAt() != null ? op.getUpdatedAt().atOffset(ZoneOffset.UTC) : null,
                op.getUpdatedBy(),
                items != null ? items : List.of(),
                relations != null ? relations : List.of()
        );
    }
}
