package com.example.traceability.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

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
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "UTC")
        OffsetDateTime createdAt,
        Integer traceEventCount
) {
}
