package com.example.traceability.quality.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Shipment 级在途持续超温告警实体（映射 {@code alert} 表；Phase B PB3；统一业务契约 v1.1 §2.11 / §10.2）。
 * <p>
 * 告警是需要质量人员处理的异常工作项：由系统在温度登记事务内，按温度记录持久化的判定依据快照判定持续超温后创建，
 * 归属运输任务发货方（受影响批次当时的责任组织）。片段与判定依据字段创建后不再改变；只有处置生命周期字段
 * （确认、处置结论）随质量管理员的动作推进。告警从不删除。
 * </p>
 * <p>
 * {@code shipmentNo}、{@code receiverOrgId}、{@code carrierOrgId} 与规则来源展示字段只由查询语句关联填充，不属于本表列。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class Alert {

    private Long id;
    private String alertNo;
    private Long orgId;
    private Long shipmentId;
    private String alertType;
    private String severity;
    private String status;
    private String reason;
    private String stageCode;
    private Long episodeStartRecordId;
    private Long sustainedRecordId;
    private LocalDateTime episodeStartedAt;
    private LocalDateTime sustainedAt;
    private Integer durationSeconds;
    private Long ruleStageId;
    private BigDecimal ruleLowerLimit;
    private BigDecimal ruleUpperLimit;
    private Integer ruleAllowedDurationSeconds;
    private LocalDateTime triggeredAt;
    private LocalDateTime acknowledgedAt;
    private Long acknowledgedBy;
    private LocalDateTime resolvedAt;
    private Long resolvedBy;
    private String resolution;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 查询关联字段（非本表列）
    private String shipmentNo;
    private Long receiverOrgId;
    private Long carrierOrgId;
    private Long ruleId;
    private String ruleName;
    private Integer ruleVersionNo;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAlertNo() {
        return alertNo;
    }

    public void setAlertNo(String alertNo) {
        this.alertNo = alertNo;
    }

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public Long getShipmentId() {
        return shipmentId;
    }

    public void setShipmentId(Long shipmentId) {
        this.shipmentId = shipmentId;
    }

    public String getAlertType() {
        return alertType;
    }

    public void setAlertType(String alertType) {
        this.alertType = alertType;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStageCode() {
        return stageCode;
    }

    public void setStageCode(String stageCode) {
        this.stageCode = stageCode;
    }

    public Long getEpisodeStartRecordId() {
        return episodeStartRecordId;
    }

    public void setEpisodeStartRecordId(Long episodeStartRecordId) {
        this.episodeStartRecordId = episodeStartRecordId;
    }

    public Long getSustainedRecordId() {
        return sustainedRecordId;
    }

    public void setSustainedRecordId(Long sustainedRecordId) {
        this.sustainedRecordId = sustainedRecordId;
    }

    public LocalDateTime getEpisodeStartedAt() {
        return episodeStartedAt;
    }

    public void setEpisodeStartedAt(LocalDateTime episodeStartedAt) {
        this.episodeStartedAt = episodeStartedAt;
    }

    public LocalDateTime getSustainedAt() {
        return sustainedAt;
    }

    public void setSustainedAt(LocalDateTime sustainedAt) {
        this.sustainedAt = sustainedAt;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Long getRuleStageId() {
        return ruleStageId;
    }

    public void setRuleStageId(Long ruleStageId) {
        this.ruleStageId = ruleStageId;
    }

    public BigDecimal getRuleLowerLimit() {
        return ruleLowerLimit;
    }

    public void setRuleLowerLimit(BigDecimal ruleLowerLimit) {
        this.ruleLowerLimit = ruleLowerLimit;
    }

    public BigDecimal getRuleUpperLimit() {
        return ruleUpperLimit;
    }

    public void setRuleUpperLimit(BigDecimal ruleUpperLimit) {
        this.ruleUpperLimit = ruleUpperLimit;
    }

    public Integer getRuleAllowedDurationSeconds() {
        return ruleAllowedDurationSeconds;
    }

    public void setRuleAllowedDurationSeconds(Integer ruleAllowedDurationSeconds) {
        this.ruleAllowedDurationSeconds = ruleAllowedDurationSeconds;
    }

    public LocalDateTime getTriggeredAt() {
        return triggeredAt;
    }

    public void setTriggeredAt(LocalDateTime triggeredAt) {
        this.triggeredAt = triggeredAt;
    }

    public LocalDateTime getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(LocalDateTime acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public Long getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public void setAcknowledgedBy(Long acknowledgedBy) {
        this.acknowledgedBy = acknowledgedBy;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(LocalDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Long getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(Long resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getShipmentNo() {
        return shipmentNo;
    }

    public void setShipmentNo(String shipmentNo) {
        this.shipmentNo = shipmentNo;
    }

    public Long getReceiverOrgId() {
        return receiverOrgId;
    }

    public void setReceiverOrgId(Long receiverOrgId) {
        this.receiverOrgId = receiverOrgId;
    }

    public Long getCarrierOrgId() {
        return carrierOrgId;
    }

    public void setCarrierOrgId(Long carrierOrgId) {
        this.carrierOrgId = carrierOrgId;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public Integer getRuleVersionNo() {
        return ruleVersionNo;
    }

    public void setRuleVersionNo(Integer ruleVersionNo) {
        this.ruleVersionNo = ruleVersionNo;
    }
}
