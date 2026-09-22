package com.example.traceability.batch.dto;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchOperationItem;

import java.math.BigDecimal;

/**
 * 批次操作明细项目响应 DTO。
 * <p>
 * 严格白名单投影，不暴露内部删除标记与版本号。INPUT / OUTPUT 项目补充关联批次的追溯批次号、产品、批次类型与流转状态，
 * 便于前端展示；LOSS / WASTE / SAMPLE 项目这些字段为 null。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchOperationItemResponse(
        Long id,
        Long operationId,
        Long batchId,
        String role,
        BigDecimal quantity,
        String unitCode,
        BigDecimal normalizedQuantity,
        String traceBatchNo,
        String externalBatchNo,
        Long productId,
        String batchType,
        String batchFlowStatus,
        String batchRiskStatus
) {

    public static BatchOperationItemResponse fromEntity(BatchOperationItem item) {
        return fromEntity(item, null);
    }

    public static BatchOperationItemResponse fromEntity(BatchOperationItem item, Batch batch) {
        if (item == null) {
            return null;
        }
        return new BatchOperationItemResponse(
                item.getId(),
                item.getOperationId(),
                item.getBatchId(),
                item.getRole(),
                item.getQuantity(),
                item.getUnitCode(),
                item.getNormalizedQuantity(),
                batch != null ? batch.getTraceBatchNo() : null,
                batch != null ? batch.getExternalBatchNo() : null,
                batch != null ? batch.getProductId() : null,
                batch != null ? batch.getBatchType() : null,
                batch != null ? batch.getFlowStatus() : null,
                batch != null ? batch.getRiskStatus() : null
        );
    }
}
