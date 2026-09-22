package com.example.traceability.quality.dto;

/**
 * 质检清单模板项响应。
 */
public record ChecklistTemplateResponse(
        Long id,
        String stageCode,
        String orgType,
        String itemName,
        String itemDesc,
        Integer sortOrder,
        Integer isRequired
) {
}
