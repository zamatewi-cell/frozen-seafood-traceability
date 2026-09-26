package com.example.traceability.quality.dto;

import com.example.traceability.quality.domain.TemperatureRecord;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Shipment 在途温度记录白名单响应 DTO（不暴露幂等键与请求哈希）。
 * <p>
 * {@code measuredAt} / {@code recordedAt} 与数据库 {@code DATETIME(6)} 完全一致（UTC，微秒精度）。
 * {@code evaluation} 为单点判定，不等于持续超温；{@code rule} 为登记时匹配到的规则版本与判定依据快照
 * （上下限与允许越界时长均来自温度记录本身，不读取当前规则环节），MISSING_CONTEXT 时为 null。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record TemperatureRecordResponse(
        Long id,
        Long shipmentId,
        String stageCode,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime measuredAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime recordedAt,
        BigDecimal temperature,
        String unitCode,
        String dataSource,
        String deviceNo,
        String evaluation,
        RuleBasis rule,
        Long orgId,
        Long actorUserId
) {

    /**
     * 判定依据：匹配的规则版本（来源追溯）与登记时的上下限、允许越界时长快照。
     *
     * @param ruleId                 规则 ID
     * @param name                   规则名称
     * @param versionNo              规则版本序号
     * @param ruleStageId            规则环节 ID（TRANSPORT）
     * @param lowerLimit             下限快照（摄氏度）
     * @param upperLimit             上限快照（摄氏度）
     * @param allowedDurationSeconds 允许越界时长快照（秒，登记时固定；PB2 不做持续超温判定）
     */
    public record RuleBasis(
            Long ruleId,
            String name,
            Integer versionNo,
            Long ruleStageId,
            BigDecimal lowerLimit,
            BigDecimal upperLimit,
            Integer allowedDurationSeconds
    ) {
    }

    public static TemperatureRecordResponse fromEntity(TemperatureRecord r) {
        RuleBasis rule = r.getRuleStageId() == null ? null : new RuleBasis(
                r.getRuleId(),
                r.getRuleName(),
                r.getRuleVersionNo(),
                r.getRuleStageId(),
                r.getRuleLowerLimit(),
                r.getRuleUpperLimit(),
                r.getRuleAllowedDurationSeconds()
        );
        return new TemperatureRecordResponse(
                r.getId(),
                r.getShipmentId(),
                r.getStageCode(),
                r.getMeasuredAt() != null ? r.getMeasuredAt().atOffset(ZoneOffset.UTC) : null,
                r.getRecordedAt() != null ? r.getRecordedAt().atOffset(ZoneOffset.UTC) : null,
                r.getTemperature(),
                r.getUnitCode(),
                r.getDataSource(),
                r.getDeviceNo(),
                r.getEvaluation(),
                rule,
                r.getOrgId(),
                r.getActorUserId()
        );
    }
}
