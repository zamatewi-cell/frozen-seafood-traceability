package com.example.traceability.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record BatchAllocationRequest(
        @NotNull Long batchId,
        @NotNull @Positive BigDecimal allocatedQuantity
) {
}
