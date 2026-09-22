package com.example.traceability.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BatchAllocationResponse(
        Long id,
        Long orderId,
        Long batchId,
        String batchNo,
        String productName,
        BigDecimal allocatedQuantity,
        String unitCode,
        Integer allocationOrder,
        Long orgId,
        Long allocatedBy,
        LocalDateTime allocatedAt
) {
}
