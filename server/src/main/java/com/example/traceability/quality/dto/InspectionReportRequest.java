package com.example.traceability.quality.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提交批次检验报告请求（Phase B PB4）。提交组织、提交身份、登记时间由服务端决定；
 * 未在契约中声明的字段（例如机构资质核验结论）被收集到 {@code unknownFields} 并被服务端拒绝。
 *
 * @param reportNo        检验报告编号（组织内唯一）
 * @param institutionName 检验检测机构名称（本系统不核验其资质与真实性）
 * @param inspectedAt     检验完成业务时间
 * @param itemsSummary    检测项目摘要
 * @param conclusion      结论 PASS / FAIL
 * @param dataSource      MANUAL / SIMULATED
 * @param alertId         可选：关联告警（批次必须是该告警的受影响批次）
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record InspectionReportRequest(
        String reportNo,
        String institutionName,
        OffsetDateTime inspectedAt,
        String itemsSummary,
        String conclusion,
        String dataSource,
        Long alertId,

        @JsonAnySetter
        Map<String, Object> unknownFields
) {

    public InspectionReportRequest {
        unknownFields = unknownFields == null ? Map.of() : new LinkedHashMap<>(unknownFields);
    }

    /**
     * 不含未知字段的便捷构造器（服务端内部与测试使用）。
     */
    public InspectionReportRequest(String reportNo, String institutionName, OffsetDateTime inspectedAt, String itemsSummary,
                                   String conclusion, String dataSource, Long alertId) {
        this(reportNo, institutionName, inspectedAt, itemsSummary, conclusion, dataSource, alertId, Map.of());
    }
}
