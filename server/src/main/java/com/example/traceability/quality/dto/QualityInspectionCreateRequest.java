package com.example.traceability.quality.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建质检单请求。
 */
public record QualityInspectionCreateRequest(
        @NotBlank(message = "质检类型不能为空")
        String inspectionType,

        Long relatedTransferId,

        @Size(max = 500, message = "质检说明最长500字符")
        String summary,

        String checklistJson
) {
}