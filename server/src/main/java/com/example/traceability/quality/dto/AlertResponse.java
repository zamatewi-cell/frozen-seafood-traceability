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
import java.util.Map;

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
     * @param autoFrozen                 本告警是否自动冻结了该批次（快照时为 NORMAL）
     * @param released                   本告警是否已依据检验结论对该批次形成放行结论（PB4）
     * @param releaseTransitionId        放行结论解除了批次最后一个风险事项时的放行转换；形成结论时仍有其他风险事项则不输出
     * @param latestInspectionConclusion 关联本告警的最新检验结论（PASS / FAIL），没有报告时不输出
     * @param pendingHolds               批次仍处于 FROZEN 时，除本告警外仍未解除的风险事项（只对告警归属组织与平台输出）
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
            String unitCode,
            boolean released,
            Long releaseTransitionId,
            String latestInspectionConclusion,
            int inspectionCount,
            List<PendingHold> pendingHolds
    ) {

        static AffectedBatch fromEntity(AlertBatch b, List<PendingHold> pendingHolds) {
            return new AffectedBatch(b.getBatchId(), b.getTraceBatchNo(), b.getTransferId(), b.getTransferNo(), b.getTransferStatus(),
                    b.getRiskStatusBefore(), b.getFreezeTransitionId() != null, b.getFreezeTransitionId(), b.getCurrentOrgId(),
                    b.getCurrentFlowStatus(), b.getCurrentRiskStatus(), b.getQuantity(), b.getUnitCode(),
                    b.getReleaseActionId() != null, b.getReleaseTransitionId(), b.getLatestInspectionConclusion(),
                    b.getInspectionCount() == null ? 0 : b.getInspectionCount(), pendingHolds);
        }
    }

    /**
     * 仍使批次保持 FROZEN 的其他风险事项。
     *
     * @param type    ALERT（其他未处置告警尚未对该批次形成放行结论）或 MANUAL_FREEZE（人工风险冻结尚未人工解除）
     * @param alertId type = ALERT 时的告警
     * @param alertNo type = ALERT 时的告警编号
     */
    public record PendingHold(String type, Long alertId, String alertNo) {
    }

    /**
     * 处置动作历史。
     */
    public record Action(
            Long id,
            String action,
            Long orgId,
            Long batchId,
            Long riskTransitionId,
            Long inspectionReportId,
            Long actorUserId,
            String note,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
            OffsetDateTime occurredAt
    ) {

        static Action fromEntity(AlertAction a) {
            return new Action(a.getId(), a.getAction(), a.getOrgId(), a.getBatchId(), a.getRiskTransitionId(), a.getInspectionReportId(),
                    a.getActorUserId(), a.getNote(), utc(a.getOccurredAt()));
        }
    }

    public static AlertResponse summary(Alert a) {
        return of(a, null, null);
    }

    /**
     * @param pendingHolds 按批次 ID 给出的其他未解除风险事项；不含某批次（或整个参数为 null）时该批次不输出 pendingHolds
     */
    public static AlertResponse detail(Alert a, List<AlertBatch> batches, List<AlertAction> actions, Map<Long, List<PendingHold>> pendingHolds) {
        return of(a, batches.stream().map(b -> AffectedBatch.fromEntity(b, pendingHolds == null ? null : pendingHolds.get(b.getBatchId()))).toList(),
                actions.stream().map(Action::fromEntity).toList());
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
