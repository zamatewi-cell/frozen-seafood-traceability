package com.example.traceability.quality.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟召回影响范围快照（映射 {@code recall_batch} 表；Phase B PB5）。
 * <p>
 * 召回创建事务内写入：SEED（发起组织持有、本次转为 RECALLED）、DESCENDANT（正向谱系后续批次）、ANCESTOR（反向谱系上游批次，
 * 只用于溯源调查），以及快照时的持有组织、状态、数量、未结束交接与公开追溯码事实。快照列创建后不再改变。
 * {@code traceBatchNo} 起的字段只由查询语句关联当前事实填充，不属于本表列。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class RecallBatch {

    private Long id;
    private Long recallId;
    private Long batchId;
    private String scopeRole;
    private Integer depth;
    private Long holderOrgId;
    private String flowStatus;
    private String riskStatusBefore;
    private String action;
    private Long riskTransitionId;
    private BigDecimal declaredQuantity;
    private BigDecimal remainingQuantity;
    private BigDecimal soldQuantity;
    private String unitCode;
    private Long openTransferId;
    private String openTransferStatus;
    private String shipmentStatus;
    private Boolean publicCodeActive;
    private LocalDateTime createdAt;

    // 查询关联字段（非本表列）
    private String traceBatchNo;
    private String productName;
    private String currentRiskStatus;
    private String currentFlowStatus;
    private String openTransferNo;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRecallId() {
        return recallId;
    }

    public void setRecallId(Long recallId) {
        this.recallId = recallId;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public String getScopeRole() {
        return scopeRole;
    }

    public void setScopeRole(String scopeRole) {
        this.scopeRole = scopeRole;
    }

    public Integer getDepth() {
        return depth;
    }

    public void setDepth(Integer depth) {
        this.depth = depth;
    }

    public Long getHolderOrgId() {
        return holderOrgId;
    }

    public void setHolderOrgId(Long holderOrgId) {
        this.holderOrgId = holderOrgId;
    }

    public String getFlowStatus() {
        return flowStatus;
    }

    public void setFlowStatus(String flowStatus) {
        this.flowStatus = flowStatus;
    }

    public String getRiskStatusBefore() {
        return riskStatusBefore;
    }

    public void setRiskStatusBefore(String riskStatusBefore) {
        this.riskStatusBefore = riskStatusBefore;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Long getRiskTransitionId() {
        return riskTransitionId;
    }

    public void setRiskTransitionId(Long riskTransitionId) {
        this.riskTransitionId = riskTransitionId;
    }

    public BigDecimal getDeclaredQuantity() {
        return declaredQuantity;
    }

    public void setDeclaredQuantity(BigDecimal declaredQuantity) {
        this.declaredQuantity = declaredQuantity;
    }

    public BigDecimal getRemainingQuantity() {
        return remainingQuantity;
    }

    public void setRemainingQuantity(BigDecimal remainingQuantity) {
        this.remainingQuantity = remainingQuantity;
    }

    public BigDecimal getSoldQuantity() {
        return soldQuantity;
    }

    public void setSoldQuantity(BigDecimal soldQuantity) {
        this.soldQuantity = soldQuantity;
    }

    public String getUnitCode() {
        return unitCode;
    }

    public void setUnitCode(String unitCode) {
        this.unitCode = unitCode;
    }

    public Long getOpenTransferId() {
        return openTransferId;
    }

    public void setOpenTransferId(Long openTransferId) {
        this.openTransferId = openTransferId;
    }

    public String getOpenTransferStatus() {
        return openTransferStatus;
    }

    public void setOpenTransferStatus(String openTransferStatus) {
        this.openTransferStatus = openTransferStatus;
    }

    public String getShipmentStatus() {
        return shipmentStatus;
    }

    public void setShipmentStatus(String shipmentStatus) {
        this.shipmentStatus = shipmentStatus;
    }

    public Boolean getPublicCodeActive() {
        return publicCodeActive;
    }

    public void setPublicCodeActive(Boolean publicCodeActive) {
        this.publicCodeActive = publicCodeActive;
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

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getCurrentRiskStatus() {
        return currentRiskStatus;
    }

    public void setCurrentRiskStatus(String currentRiskStatus) {
        this.currentRiskStatus = currentRiskStatus;
    }

    public String getCurrentFlowStatus() {
        return currentFlowStatus;
    }

    public void setCurrentFlowStatus(String currentFlowStatus) {
        this.currentFlowStatus = currentFlowStatus;
    }

    public String getOpenTransferNo() {
        return openTransferNo;
    }

    public void setOpenTransferNo(String openTransferNo) {
        this.openTransferNo = openTransferNo;
    }
}
