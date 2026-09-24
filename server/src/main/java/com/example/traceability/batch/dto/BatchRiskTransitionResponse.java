package com.example.traceability.batch.dto;

import com.example.traceability.batch.domain.BatchRiskTransition;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 批次风险状态转换白名单响应 DTO（不暴露幂等键、请求哈希与登记时间）。
 * <p>
 * {@code occurredAt} 与数据库 {@code batch_risk_transition.occurred_at} 完全一致（UTC，微秒精度）。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchRiskTransitionResponse(
        Long id,
        Long batchId,
        Long orgId,
        String flowStatus,
        String fromStatus,
        String toStatus,
        String sourceType,
        String reason,
        Long actorUserId,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime occurredAt
) {

    public static BatchRiskTransitionResponse fromEntity(BatchRiskTransition t) {
        return new BatchRiskTransitionResponse(
                t.getId(),
                t.getBatchId(),
                t.getOrgId(),
                t.getFlowStatus(),
                t.getFromStatus(),
                t.getToStatus(),
                t.getSourceType(),
                t.getReason(),
                t.getActorUserId(),
                t.getOccurredAt() != null ? t.getOccurredAt().atOffset(ZoneOffset.UTC) : null
        );
    }
}
