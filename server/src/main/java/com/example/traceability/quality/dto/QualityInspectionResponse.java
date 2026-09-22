package com.example.traceability.quality.dto;

import java.time.LocalDateTime;

/**
 * 质检单响应对象。
 */
public record QualityInspectionResponse(
        Long id,
        String inspectionNo,
        Long batchId,
        Long orgId,
        String inspectionType,
        String inspectionStage,
        Long relatedOrderId,
        Long relatedTransferId,
        Long inspectorId,
        String inspectorName,
        String result,
        String summary,
        String checklistJson,
        LocalDateTime checklistPassedAt,
        LocalDateTime checkedAt,
        LocalDateTime createdAt
) {
}
