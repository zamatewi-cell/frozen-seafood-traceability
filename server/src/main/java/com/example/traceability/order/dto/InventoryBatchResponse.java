package com.example.traceability.order.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InventoryBatchResponse(
        Long batchId,
        String batchNo,
        Long productId,
        String productName,
        String batchType,
        BigDecimal totalQuantity,
        BigDecimal allocatedQuantity,
        BigDecimal availableQuantity,
        String unitCode,
        String status,
        String originType,
        String originText,
        LocalDate productionDate,
        Integer traceEventCount
) {
}
