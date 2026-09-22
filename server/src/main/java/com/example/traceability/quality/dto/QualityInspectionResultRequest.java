package com.example.traceability.quality.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 质检判定请求（提交 PASS / FAIL 结论）。
 */
public record QualityInspectionResultRequest(
        @NotBlank(message = "判定结果不能为空")
        String result,

        @Size(max = 500, message = "质检结论最长500字符")
        String summary
) {
}