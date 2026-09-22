package com.example.traceability.quality.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 提交质检审核请求（操作员出货前发起）。
 * <p>操作员点"提交质检审核"后，系统根据出货方组织类型自动确定环节，
 * 创建一条待检质检单，关联到订单和分配的批次。</p>
 */
public record SubmitQualityReviewRequest(
        @NotEmpty(message = "分配批次不能为空")
        @Valid
        List<ChecklistItemRequest> checklist
) {
}
