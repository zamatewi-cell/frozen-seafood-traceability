package com.example.traceability.quality.dto;

import com.example.traceability.quality.domain.InspectionReport;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 批次检验报告白名单响应 DTO（企业端；不暴露幂等键与请求哈希）。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record InspectionReportResponse(
        Long id,
        Long batchId,
        Long orgId,
        String submitterRole,
        Long transferId,
        Long alertId,
        String reportNo,
        String institutionName,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime inspectedAt,
        String itemsSummary,
        String conclusion,
        String dataSource,
        Long actorUserId,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime recordedAt
) {

    public static InspectionReportResponse fromEntity(InspectionReport r) {
        return new InspectionReportResponse(r.getId(), r.getBatchId(), r.getOrgId(), r.getSubmitterRole(), r.getTransferId(),
                r.getAlertId(), r.getReportNo(), r.getInstitutionName(),
                r.getInspectedAt() == null ? null : r.getInspectedAt().atOffset(ZoneOffset.UTC), r.getItemsSummary(),
                r.getConclusion(), r.getDataSource(), r.getActorUserId(),
                r.getRecordedAt() == null ? null : r.getRecordedAt().atOffset(ZoneOffset.UTC));
    }
}
