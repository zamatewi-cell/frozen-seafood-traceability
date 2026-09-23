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
        BigDecimal remainingQuantity,
        String unitCode,
        String originType,
        String originText,
        LocalDate productionDate,
        LocalDate captureDate,
        LocalDate freezeDate,
        Integer shelfLifeDays,
        String flowStatus,
        String riskStatus,
        Long producedByOperationId,
        Long consumedByOperationId,
        Long firstSaleId,
        Long version,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime createdAt,
        Long createdBy,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime updatedAt,
        Long updatedBy
) {

    /**
     * 不查询操作与销售台账的简化投影：CLOSED 批次剩余量为 0，其余批次剩余量等于声明数量。
     * 仅适用于尚未参与任何已提交批次操作或终端销售的批次（例如刚创建或刚激活的草稿）；
     * 其余场景必须使用 {@link #fromEntity(Batch, BigDecimal)} 传入由已提交记录派生的剩余量。
     */
    public static BatchResponse fromEntity(Batch b) {
        if (b == null) {
            return null;
        }
        BigDecimal remaining = "CLOSED".equals(b.getFlowStatus()) ? BigDecimal.ZERO : b.getQuantity();
        return fromEntity(b, remaining);
    }

    /**
     * 批次白名单投影。
     *
     * @param b                 批次实体
     * @param remainingQuantity 派生剩余量 = 声明数量 - 已提交批次操作 INPUT 消耗量 - 已提交终端销售数量（处置属于 Phase B）
     */
    public static BatchResponse fromEntity(Batch b, BigDecimal remainingQuantity) {
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
                remainingQuantity,
                b.getUnitCode(),
                b.getOriginType(),
                b.getOriginText(),
                b.getProductionDate(),
                b.getCaptureDate(),
                b.getFreezeDate(),
                b.getShelfLifeDays(),
                b.getFlowStatus(),
                b.getRiskStatus(),
                b.getProducedByOperationId(),
                b.getConsumedByOperationId(),
                b.getFirstSaleId(),
                b.getVersion(),
                b.getCreatedAt() != null ? b.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                b.getCreatedBy(),
                b.getUpdatedAt() != null ? b.getUpdatedAt().atOffset(ZoneOffset.UTC) : null,
                b.getUpdatedBy()
        );
    }
}
