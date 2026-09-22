package com.example.traceability.quality.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 清单项打钩请求。
 * <p>质检员逐项确认，passed=true 表示该项已检查通过。</p>
 */
public record ChecklistItemRequest(
        @NotBlank(message = "清单项名称不能为空")
        String itemName,

        @NotNull(message = "passed 不能为空")
        Boolean passed,

        String remark
) {
}
