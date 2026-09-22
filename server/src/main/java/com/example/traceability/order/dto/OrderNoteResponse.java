package com.example.traceability.order.dto;

import java.time.LocalDateTime;

public record OrderNoteResponse(
        Long id,
        Long orderId,
        String orderType,
        String noteText,
        String statusAt,
        Integer isTerminalVisible,
        Long orgId,
        Long createdBy,
        LocalDateTime createdAt
) {
}
