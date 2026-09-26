package com.example.traceability.quality.dto;

import com.example.traceability.quality.domain.Alert;
import com.example.traceability.quality.domain.AlertAction;
import com.example.traceability.quality.domain.AlertBatch;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 告警白名单响应 DTO（企业端；Phase B PB3）。
 * <p>
 * 列表只含告警本身；详情另含受影响批次快照（关联批次与交接当前事实）与处置动作历史。不暴露幂等键与请求哈希。
 * 判定依据全部来自告警创建时复制的快照列；规则名称与版本只用于来源展示。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record AlertResponse(
        Long id,
        String alertNo,
        String alertType,
        String severity,
        String status,
        String reason,
        Long orgId,
        Long shipmentId,
        String shipmentNo,
        Long receiverOrgId,
        Long carrierOrgId,
        String stageCode,
        Long episodeStartRecordId,
        Long sustainedRecordId,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime episodeStartedAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime sustainedAt,
        Integer durationSeconds,
        TemperatureRecordResponse.RuleBasis rule,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime triggeredAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime acknowledgedAt,
        Long acknowledgedBy,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime resolvedAt,
        Long resolvedBy,
        String resolution,
        Long version,
        List<AffectedBatch> batches,
        List<Action> actions
) {

    /**
     * 受影响批次快照与其当前事实。
     *
     * @param autoFrozen 本告警是否自动冻结了该批次（快照时为 NORMAL）
     */
    public record AffectedBatch(
            Long batchId,
            String traceBatchNo,
            Long transferId,
            String transferNo,
            String transferStatus,
            String riskStatusBefore,
            boolean autoFrozen,
            Long freezeTransitionId,
            Long currentOrgId,
            String currentFlowStatus,
            String currentRiskStatus,
            BigDecimal quantity,
            String unitCode
    ) {

        static AffectedBatch fromEntity(AlertBatch b) {
            return new AffectedBatch(b.getBatchId(), b.getTraceBatchNo(), b.getTransferId(), b.getTransferNo(), b.getTransferStatus(),
                    b.getRiskStatusBefore(), b.getFreezeTransitionId() != null, b.getFreezeTransitionId(), b.getCurrentOrgId(),
                    b.getCurrentFlowStatus(), b.getCurrentRiskStatus(), b.getQuantity(), b.getUnitCode());
        }
    }

    /**
     * 处置动作历史。
     */
    public record Action(
            Long id,
            String action,
            Long orgId,
            Long actorUserId,
            String note,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
            OffsetDateTime occurredAt
    ) {

        static Action fromEntity(AlertAction a) {
            return new Action(a.getId(), a.getAction(), a.getOrgId(), a.getActorUserId(), a.getNote(), utc(a.getOccurredAt()));
        }
    }

    public static AlertResponse summary(Alert a) {
        return of(a, null, null);
    }

    public static AlertResponse detail(Alert a, List<AlertBatch> batches, List<AlertAction> actions) {
        return of(a, batches.stream().map(AffectedBatch::fromEntity).toList(), actions.stream().map(Action::fromEntity).toList());
    }

    private static AlertResponse of(Alert a, List<AffectedBatch> batches, List<Action> actions) {
        TemperatureRecordResponse.RuleBasis rule = new TemperatureRecordResponse.RuleBasis(a.getRuleId(), a.getRuleName(),
                a.getRuleVersionNo(), a.getRuleStageId(), a.getRuleLowerLimit(), a.getRuleUpperLimit(), a.getRuleAllowedDurationSeconds());
        return new AlertResponse(a.getId(), a.getAlertNo(), a.getAlertType(), a.getSeverity(), a.getStatus(), a.getReason(),
                a.getOrgId(), a.getShipmentId(), a.getShipmentNo(), a.getReceiverOrgId(), a.getCarrierOrgId(), a.getStageCode(),
                a.getEpisodeStartRecordId(), a.getSustainedRecordId(), utc(a.getEpisodeStartedAt()), utc(a.getSustainedAt()),
                a.getDurationSeconds(), rule, utc(a.getTriggeredAt()), utc(a.getAcknowledgedAt()), a.getAcknowledgedBy(),
                utc(a.getResolvedAt()), a.getResolvedBy(), a.getResolution(), a.getVersion(), batches, actions);
    }

    private static OffsetDateTime utc(LocalDateTime t) {
        return t == null ? null : t.atOffset(ZoneOffset.UTC);
    }
}
