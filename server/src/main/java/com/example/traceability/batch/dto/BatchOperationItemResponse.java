package com.example.traceability.batch.dto;

import com.example.traceability.batch.domain.BatchOperationItem;

import java.math.BigDecimal;

/**
 * 批次操作明细项目响应 DTO。
 * <p>
 * 严格白名单投影，不暴露内部删除标记与版本号。
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
        BigDecimal normalizedQuantity
) {

    public static BatchOperationItemResponse fromEntity(BatchOperationItem item) {
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
                item.getNormalizedQuantity()
        );
    }
}
