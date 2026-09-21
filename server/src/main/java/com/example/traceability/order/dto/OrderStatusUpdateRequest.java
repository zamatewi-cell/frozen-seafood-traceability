package com.example.traceability.order.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 订单状态流转请求。
 */
public record OrderStatusUpdateRequest(
        @NotBlank(message = "目标状态 status 不能为空")
        String status
) {
}