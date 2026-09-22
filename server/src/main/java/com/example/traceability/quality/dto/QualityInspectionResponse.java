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
        Long relatedTransferId,
        Long inspectorId,
        String inspectorName,
        String result,
        String summary,
        String checklistJson,
        LocalDateTime checkedAt,
        LocalDateTime createdAt
) {
}