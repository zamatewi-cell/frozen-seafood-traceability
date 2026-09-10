package com.example.traceability.trace.dto;

import com.example.traceability.trace.domain.TraceEvent;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 追溯事件响应 DTO。
 * <p>
 * 严格白名单投影，绝不暴露 {@code isDeleted}、{@code idempotencyKey} 及内部乐观锁审计等非公开持久层字段。
 * 业务发生时间与系统登记时间统一输出毫秒精度的 UTC ISO 8601 格式时间。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TraceEventResponse(
        Long id,
        Long batchId,
        Long orgId,
        Long siteId,
        String eventType,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime occurredAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime recordedAt,
        Long operatorId,
        String dataSource,
        String status,
        String summary,
        Map<String, Object> detailsJson,
        Long correctsEventId,
        String correctionReason
) {

    public static TraceEventResponse fromEntity(TraceEvent e, Map<String, Object> parsedDetails) {
        if (e == null) {
            return null;
        }
        return new TraceEventResponse(
                e.getId(),
                e.getBatchId(),
                e.getOrgId(),
                e.getSiteId(),
                e.getEventType(),
                e.getOccurredAt() != null ? e.getOccurredAt().atOffset(ZoneOffset.UTC) : null,
                e.getRecordedAt() != null ? e.getRecordedAt().atOffset(ZoneOffset.UTC) : null,
                e.getOperatorId(),
                e.getDataSource(),
                e.getStatus(),
                e.getSummary(),
                parsedDetails,
                e.getCorrectsEventId(),
                e.getCorrectionReason()
        );
    }
}
