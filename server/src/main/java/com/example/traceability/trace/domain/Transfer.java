package com.example.traceability.trace.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 企业间批次交接凭证实体。
 * <p>
 * 对应数据库表 {@code transfer}，记录整批海产品在发送组织与接收组织之间的交接流转全生命周期。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@TableName("`transfer`")
public class Transfer {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("transfer_no")
    private String transferNo;

    @TableField("batch_id")
    private Long batchId;

    @TableField("open_batch_id")
    private Long openBatchId;

    @TableField("shipment_id")
    private Long shipmentId;

    @TableField("sender_org_id")
    private Long senderOrgId;

    @TableField("receiver_org_id")
    private Long receiverOrgId;

    @TableField("quantity")
    private BigDecimal quantity;

    @TableField("unit_code")
    private String unitCode;

    @TableField("shipped_at")
    private LocalDateTime shippedAt;

    @TableField("submitted_recorded_at")
    private LocalDateTime submittedRecordedAt;

    @TableField("submitted_by")
    private Long submittedBy;

    @TableField("received_at")
    private LocalDateTime receivedAt;

    @TableField("decision_recorded_at")
    private LocalDateTime decisionRecordedAt;

    @TableField("decided_by")
    private Long decidedBy;

    @TableField("received_quantity")
    private BigDecimal receivedQuantity;

    @TableField("difference_reason")
    private String differenceReason;

    @TableField("status")
    private TransferStatus status;

    @TableField("rejection_reason")
    private String rejectionReason;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @Version
    @TableField("version")
    private Long version;

    @TableField("is_deleted")
    private Integer isDeleted;

    /**
     * 历史存量数据标记 (1: V7迁移前历史旧数据, 0: V7及之后新规数据，仅供数据库约束识别，不暴露给外部DTO)。
     */
    @TableField("is_legacy")
    private Integer isLegacy = 0;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("created_by")
    private Long createdBy;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("updated_by")
    private Long updatedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTransferNo() {
        return transferNo;
    }

    public void setTransferNo(String transferNo) {
        this.transferNo = transferNo;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getOpenBatchId() {
        return openBatchId;
    }

    public void setOpenBatchId(Long openBatchId) {
        this.openBatchId = openBatchId;
    }

    public Long getShipmentId() {
        return shipmentId;
    }

    public void setShipmentId(Long shipmentId) {
        this.shipmentId = shipmentId;
    }

    public Long getSenderOrgId() {
        return senderOrgId;
    }

    public void setSenderOrgId(Long senderOrgId) {
        this.senderOrgId = senderOrgId;
    }

    public Long getReceiverOrgId() {
        return receiverOrgId;
    }

    public void setReceiverOrgId(Long receiverOrgId) {
        this.receiverOrgId = receiverOrgId;
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

    public LocalDateTime getShippedAt() {
        return shippedAt;
    }

    public void setShippedAt(LocalDateTime shippedAt) {
        this.shippedAt = shippedAt;
    }

    public LocalDateTime getSubmittedRecordedAt() {
        return submittedRecordedAt;
    }

    public void setSubmittedRecordedAt(LocalDateTime submittedRecordedAt) {
        this.submittedRecordedAt = submittedRecordedAt;
    }

    public Long getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(Long submittedBy) {
        this.submittedBy = submittedBy;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public LocalDateTime getDecisionRecordedAt() {
        return decisionRecordedAt;
    }

    public void setDecisionRecordedAt(LocalDateTime decisionRecordedAt) {
        this.decisionRecordedAt = decisionRecordedAt;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(Long decidedBy) {
        this.decidedBy = decidedBy;
    }

    public BigDecimal getReceivedQuantity() {
        return receivedQuantity;
    }

    public void setReceivedQuantity(BigDecimal receivedQuantity) {
        this.receivedQuantity = receivedQuantity;
    }

    public String getDifferenceReason() {
        return differenceReason;
    }

    public void setDifferenceReason(String differenceReason) {
        this.differenceReason = differenceReason;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public void setStatus(TransferStatus status) {
        this.status = status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Integer getIsLegacy() {
        return isLegacy;
    }

    public void setIsLegacy(Integer isLegacy) {
        this.isLegacy = isLegacy;
    }
}
