package com.example.traceability.order.dto;

/**
 * 采购单审批/排产决策请求。
 * <p>仅供货方操作员可调用；驳回时必须携带原因。</p>
 */
public record OrderDecisionRequest(
        String reason
) {
}