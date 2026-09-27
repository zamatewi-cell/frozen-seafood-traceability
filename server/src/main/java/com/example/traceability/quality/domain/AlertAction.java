package com.example.traceability.quality.domain;

import java.time.LocalDateTime;

/**
 * 告警处置动作（映射 {@code alert_action} 表，追加式；Phase B PB3）。
 * <p>
 * 每一次人工处置动作一行，同时承担组织内幂等记录：UNIQUE (org_id, idempotency_key) + 规范化请求语义哈希。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class AlertAction {

    private Long id;
    private Long alertId;
    private Long orgId;
    private String action;
    /** RELEASE_BATCH（PB4）：放行批次、放行风险转换与依据检验报告。 */
    private Long batchId;
    private Long riskTransitionId;
    private Long inspectionReportId;
    private Long actorUserId;
    private String note;
    private String idempotencyKey;
    private String requestHash;
    private LocalDateTime occurredAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAlertId() {
        return alertId;
    }

    public void setAlertId(Long alertId) {
        this.alertId = alertId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(Long actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getRiskTransitionId() {
        return riskTransitionId;
    }

    public void setRiskTransitionId(Long riskTransitionId) {
        this.riskTransitionId = riskTransitionId;
    }

    public Long getInspectionReportId() {
        return inspectionReportId;
    }

    public void setInspectionReportId(Long inspectionReportId) {
        this.inspectionReportId = inspectionReportId;
    }
}
