package com.example.traceability.batch.dto;

import com.example.traceability.batch.domain.Batch;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 追溯批次响应 DTO。
 * <p>
 * 严格执行白名单投影，绝不暴露 {@code isDeleted}、{@code creationIdempotencyKey} 等内部字段。
 * 业务字段投影采用双编号（{@code traceBatchNo}, {@code externalBatchNo}）与双状态（{@code flowStatus}, {@code riskStatus}）。
 * 所有审计时间统一输出带明确 UTC 偏移量的 ISO 8601 格式时间。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchResponse(
        Long id,
        Long orgId,
        Long productId,
        String traceBatchNo,
        String externalBatchNo,
        String batchType,
        BigDecimal quantity,
        String unitCode,
        String originType,
        String originText,
        LocalDate productionDate,
        LocalDate captureDate,
        LocalDate freezeDate,
        Integer shelfLifeDays,
        String flowStatus,
        String riskStatus,
        Long version,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime createdAt,
        Long createdBy,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime updatedAt,
        Long updatedBy
) {

    public static BatchResponse fromEntity(Batch b) {
        if (b == null) {
            return null;
        }
        return new BatchResponse(
                b.getId(),
                b.getOrgId(),
                b.getProductId(),
                b.getTraceBatchNo(),
                b.getExternalBatchNo(),
                b.getBatchType(),
                b.getQuantity(),
                b.getUnitCode(),
                b.getOriginType(),
                b.getOriginText(),
                b.getProductionDate(),
                b.getCaptureDate(),
                b.getFreezeDate(),
                b.getShelfLifeDays(),
                b.getFlowStatus(),
                b.getRiskStatus(),
                b.getVersion(),
                b.getCreatedAt() != null ? b.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                b.getCreatedBy(),
                b.getUpdatedAt() != null ? b.getUpdatedAt().atOffset(ZoneOffset.UTC) : null,
                b.getUpdatedBy()
        );
    }
}
