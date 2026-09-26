package com.example.traceability.quality.application;

import com.example.traceability.audit.application.AuditApplicationService;
import com.example.traceability.batch.application.BatchRiskService;
import com.example.traceability.batch.application.BatchRiskService.AlertFreezeOutcome;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.quality.domain.Alert;
import com.example.traceability.quality.domain.AlertBatch;
import com.example.traceability.quality.domain.AlertStatus;
import com.example.traceability.quality.domain.AlertType;
import com.example.traceability.quality.domain.TemperatureEvaluation;
import com.example.traceability.quality.domain.TemperatureExcursionDetector;
import com.example.traceability.quality.domain.TemperatureExcursionDetector.Episode;
import com.example.traceability.quality.domain.TemperatureRecord;
import com.example.traceability.quality.mapper.AlertBatchMapper;
import com.example.traceability.quality.mapper.AlertMapper;
import com.example.traceability.trace.domain.Shipment;
import com.example.traceability.trace.domain.Transfer;
import com.example.traceability.trace.mapper.TransferMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 在途持续超温告警系统路径（Phase B PB3；统一业务契约 v1.1 §10.1 / §10.2 步骤 2–4 / §13 步骤 2–4）。
 * <p>
 * 由温度登记在同一 READ COMMITTED 事务内、持有运输任务行锁并插入新记录之后调用：按全部记录的持久化判定依据快照
 * 找出尚未告警的持续超温片段，为每个片段创建一条 Shipment 级告警（OPEN、HIGH），经 Shipment → Transfer → Batch
 * 快照受影响批次，并通过风险核心 {@link BatchRiskService#freezeForAlert} 自动冻结其中 NORMAL 的批次。
 * 批次流转状态、数量、责任组织与公开追溯码不变，不生成 TraceEvent；运输任务与交接不被修改。
 * </p>
 * <p>
 * 锁顺序：shipment（调用方已持有 FOR UPDATE）→ temperature_record（调用方已插入）→ alert（插入）→
 * batch（风险核心按 ID 升序 FOR UPDATE）→ batch_risk_transition / alert_batch（插入）。交接只做普通读：
 * IN_TRANSIT 后装载清单冻结，交接的接收决定要求 DELIVERED 且同样先锁运输任务行，因此持锁期间交接不会变化。
 * 同一运输任务的温度登记、到达与告警创建全部由运输任务行锁串行化：并发登记同时补全同一片段只会产生一条告警。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class TemperatureExcursionAlertService {

    static final String AUDIT_ACTION = "ALERT_TRIGGER";
    static final String AUDIT_OBJECT_TYPE = "ALERT";
    static final String SEVERITY = "HIGH";
    private static final DateTimeFormatter ALERT_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AlertMapper alertMapper;
    private final AlertBatchMapper alertBatchMapper;
    private final TransferMapper transferMapper;
    private final BatchRiskService batchRiskService;
    private final AuditApplicationService auditService;
    private final ObjectMapper objectMapper;

    public TemperatureExcursionAlertService(
            AlertMapper alertMapper,
            AlertBatchMapper alertBatchMapper,
            TransferMapper transferMapper,
            BatchRiskService batchRiskService,
            AuditApplicationService auditService,
            ObjectMapper objectMapper
    ) {
        this.alertMapper = Objects.requireNonNull(alertMapper, "alertMapper 不能为空");
        this.alertBatchMapper = Objects.requireNonNull(alertBatchMapper, "alertBatchMapper 不能为空");
        this.transferMapper = Objects.requireNonNull(transferMapper, "transferMapper 不能为空");
        this.batchRiskService = Objects.requireNonNull(batchRiskService, "batchRiskService 不能为空");
        this.auditService = Objects.requireNonNull(auditService, "auditService 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 追加温度记录后判定并创建持续超温告警。
     *
     * @param lockedShipment 调用方已持有行锁（FOR UPDATE）的 IN_TRANSIT 运输任务
     * @param ordered        该运输任务全部温度记录（含刚插入的记录），按 (measuredAt, id) 升序
     * @param nowUtc         本次登记的服务端时间（告警创建时间）
     * @return 本次新建的告警（通常为空或一条）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Alert> raiseSustainedExcursionAlerts(Shipment lockedShipment, List<TemperatureRecord> ordered, LocalDateTime nowUtc) {
        List<Episode> sustained = TemperatureExcursionDetector.sustainedEpisodes(ordered);
        if (sustained.isEmpty()) {
            return List.of();
        }
        List<Long> alerted = new ArrayList<>();
        for (Alert existing : alertMapper.selectEpisodeKeysByShipmentId(lockedShipment.getId())) {
            alerted.add(existing.getEpisodeStartRecordId());
            alerted.add(existing.getSustainedRecordId());
        }
        List<Episode> fresh = TemperatureExcursionDetector.unalerted(sustained, alerted);
        if (fresh.isEmpty()) {
            return List.of();
        }

        // 受影响批次：Shipment → Transfer → Batch（在途装载清单已冻结）
        List<Transfer> manifest = transferMapper.selectByShipmentId(lockedShipment.getId());
        Map<Long, Transfer> transferByBatch = manifest.stream()
                .collect(Collectors.toMap(Transfer::getBatchId, Function.identity(), (a, b) -> {
                    throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "ALERT_SCOPE_INTEGRITY", "告警影响范围数据不一致",
                            "同一批次在运输任务中出现多次，已拒绝本次温度登记");
                }, LinkedHashMap::new));

        List<Alert> created = new ArrayList<>(fresh.size());
        for (Episode episode : fresh) {
            Alert alert = newAlert(lockedShipment, episode, nowUtc);
            alertMapper.insert(alert);

            List<AlertFreezeOutcome> outcomes = batchRiskService.freezeForAlert(alert.getId(), alert.getAlertNo(),
                    alert.getOrgId(), transferByBatch.keySet(), nowUtc);
            int frozen = 0;
            for (AlertFreezeOutcome outcome : outcomes) {
                AlertBatch row = new AlertBatch();
                row.setAlertId(alert.getId());
                row.setBatchId(outcome.batch().getId());
                row.setTransferId(transferByBatch.get(outcome.batch().getId()).getId());
                row.setOrgId(outcome.batch().getOrgId());
                row.setRiskStatusBefore(outcome.riskStatusBefore());
                row.setFreezeTransitionId(outcome.transition() == null ? null : outcome.transition().getId());
                row.setCreatedAt(nowUtc);
                alertBatchMapper.insert(row);
                if (outcome.transition() != null) {
                    frozen++;
                }
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("alertNo", alert.getAlertNo());
            summary.put("shipmentId", lockedShipment.getId());
            summary.put("alertType", alert.getAlertType());
            summary.put("episodeStartRecordId", alert.getEpisodeStartRecordId());
            summary.put("sustainedRecordId", alert.getSustainedRecordId());
            summary.put("durationSeconds", alert.getDurationSeconds());
            summary.put("ruleStageId", alert.getRuleStageId());
            summary.put("affectedBatchCount", outcomes.size());
            summary.put("autoFrozenBatchCount", frozen);
            auditService.recordAudit(null, alert.getOrgId(), AUDIT_ACTION, AUDIT_OBJECT_TYPE, alert.getId(), nowUtc,
                    "SUCCESS", serializeSummary(summary));
            created.add(alert);
        }
        return created;
    }

    private static Alert newAlert(Shipment shipment, Episode episode, LocalDateTime nowUtc) {
        Alert alert = new Alert();
        alert.setAlertNo("ALT-" + nowUtc.format(ALERT_NO_FORMAT) + "-" + (RANDOM.nextInt(900000) + 100000));
        alert.setOrgId(shipment.getSenderOrgId());
        alert.setShipmentId(shipment.getId());
        alert.setAlertType(AlertType.of(episode.direction()).name());
        alert.setSeverity(SEVERITY);
        alert.setStatus(AlertStatus.OPEN.name());
        alert.setStageCode(episode.start().getStageCode());
        alert.setEpisodeStartRecordId(episode.start().getId());
        alert.setSustainedRecordId(episode.sustained().getId());
        alert.setEpisodeStartedAt(episode.start().getMeasuredAt());
        alert.setSustainedAt(episode.sustained().getMeasuredAt());
        alert.setDurationSeconds(Math.toIntExact(episode.durationSeconds()));
        alert.setRuleStageId(episode.basis().ruleStageId());
        alert.setRuleLowerLimit(episode.basis().lowerLimit());
        alert.setRuleUpperLimit(episode.basis().upperLimit());
        alert.setRuleAllowedDurationSeconds(episode.basis().allowedDurationSeconds());
        alert.setTriggeredAt(nowUtc);
        alert.setReason(reason(shipment, episode));
        return alert;
    }

    /** 服务端生成的告警原因：方向、判定依据上 / 下限、已持续时长与规则允许时长（不含任何单点即持续超温的表述）。 */
    static String reason(Shipment shipment, Episode episode) {
        boolean high = episode.direction() == TemperatureEvaluation.HIGH;
        String limit = (high ? episode.basis().upperLimit() : episode.basis().lowerLimit()).toPlainString();
        int allowed = episode.basis().allowedDurationSeconds();
        String condition = allowed == 0
                ? "（判定依据允许连续越界时长为 0 秒，越界即构成持续超温）"
                : "，已持续 " + episode.durationSeconds() + " 秒，达到判定依据允许的连续越界时长 " + allowed + " 秒";
        return "运输任务 " + shipment.getShipmentNo() + " 在途温度连续" + (high ? "高于上限 " : "低于下限 ") + limit + " ℃"
                + condition + "，形成持续超温告警";
    }

    private String serializeSummary(Map<String, Object> summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR", "审计日志序列化失败", e.getMessage());
        }
    }
}
