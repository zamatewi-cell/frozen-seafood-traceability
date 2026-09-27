package com.example.traceability.quality.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 告警受影响批次快照（映射 {@code alert_batch} 表；Phase B PB3；统一业务契约 v1.1 §10.1 / §10.2 步骤 3）。
 * <p>
 * 告警创建时在同一事务内经 Shipment → Transfer → Batch 确定，记录快照时批次风险状态与本告警自动冻结产生的风险转换。
 * 快照列创建后不再改变。
 * </p>
 * <p>
 * {@code traceBatchNo} 起的字段只由查询语句关联批次与交接的当前状态填充，不属于本表列。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class AlertBatch {

    private Long id;
    private Long alertId;
    private Long batchId;
    private Long transferId;
    private Long orgId;
    private String riskStatusBefore;
    private Long freezeTransitionId;
    private LocalDateTime createdAt;

    // 查询关联字段（非本表列）：批次与交接的当前事实
    private String traceBatchNo;
    private Long currentOrgId;
    private String currentFlowStatus;
    private String currentRiskStatus;
    private BigDecimal quantity;
    private String unitCode;
    private String transferNo;
    private String transferStatus;

    // 查询关联字段（非本表列）：质量处置进展（PB4）
    /** 本告警对该批次的放行结论（RELEASE_BATCH 动作 ID）；为空表示尚未形成放行结论。 */
    private Long releaseActionId;
    /** 放行结论解除了批次最后一个风险事项时的放行转换；结论形成时仍有其他风险事项则为空。 */
    private Long releaseTransitionId;
    private String latestInspectionConclusion;
    private Integer inspectionCount;

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

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getTransferId() {
        return transferId;
    }

    public void setTransferId(Long transferId) {
        this.transferId = transferId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public String getRiskStatusBefore() {
        return riskStatusBefore;
    }

    public void setRiskStatusBefore(String riskStatusBefore) {
        this.riskStatusBefore = riskStatusBefore;
    }

    public Long getFreezeTransitionId() {
        return freezeTransitionId;
    }

    public void setFreezeTransitionId(Long freezeTransitionId) {
        this.freezeTransitionId = freezeTransitionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getTraceBatchNo() {
        return traceBatchNo;
    }

    public void setTraceBatchNo(String traceBatchNo) {
        this.traceBatchNo = traceBatchNo;
    }

    public Long getCurrentOrgId() {
        return currentOrgId;
    }

    public void setCurrentOrgId(Long currentOrgId) {
        this.currentOrgId = currentOrgId;
    }

    public String getCurrentFlowStatus() {
        return currentFlowStatus;
    }

    public void setCurrentFlowStatus(String currentFlowStatus) {
        this.currentFlowStatus = currentFlowStatus;
    }

    public String getCurrentRiskStatus() {
        return currentRiskStatus;
    }

    public void setCurrentRiskStatus(String currentRiskStatus) {
        this.currentRiskStatus = currentRiskStatus;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getUnitCode() {
        return unitCode;
    }

    public void setUnitCode(String unitCode) {
        this.unitCode = unitCode;
    }

    public String getTransferNo() {
        return transferNo;
    }

    public void setTransferNo(String transferNo) {
        this.transferNo = transferNo;
    }

    public String getTransferStatus() {
        return transferStatus;
    }

    public void setTransferStatus(String transferStatus) {
        this.transferStatus = transferStatus;
    }

    public Long getReleaseActionId() {
        return releaseActionId;
    }

    public void setReleaseActionId(Long releaseActionId) {
        this.releaseActionId = releaseActionId;
    }

    public Long getReleaseTransitionId() {
        return releaseTransitionId;
    }

    public void setReleaseTransitionId(Long releaseTransitionId) {
        this.releaseTransitionId = releaseTransitionId;
    }

    public String getLatestInspectionConclusion() {
        return latestInspectionConclusion;
    }

    public void setLatestInspectionConclusion(String latestInspectionConclusion) {
        this.latestInspectionConclusion = latestInspectionConclusion;
    }

    public Integer getInspectionCount() {
        return inspectionCount;
    }

    public void setInspectionCount(Integer inspectionCount) {
        this.inspectionCount = inspectionCount;
    }
}
