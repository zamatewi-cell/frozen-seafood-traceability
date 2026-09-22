package com.example.traceability.order.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购进货单主表实体（B2B）。
 */
@TableName("purchase_order")
public class PurchaseOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_no")
    private String orderNo;

    @TableField("buyer_org_id")
    private Long buyerOrgId;

    @TableField("seller_org_id")
    private Long sellerOrgId;

    @TableField("order_type")
    private String orderType;

    @TableField("status")
    private String status;

    @TableField("handling_status")
    private String handlingStatus;

    @TableField("ordered_at")
    private LocalDateTime orderedAt;

    @TableField("expected_delivery_at")
    private LocalDateTime expectedDeliveryAt;

    @TableField("receipt_batch_id")
    private Long receiptBatchId;

    @TableField("trace_code_id")
    private Long traceCodeId;

    @TableField("public_trace_id")
    private String publicTraceId;

    @TableField("cancel_request_role")
    private String cancelRequestRole;

    @TableField("cancel_request_reason")
    private String cancelRequestReason;

    @TableField("cancel_request_status")
    private String cancelRequestStatus;

    @TableField("cancel_request_at")
    private LocalDateTime cancelRequestAt;

    @TableField("amount_total")
    private BigDecimal amountTotal;

    @TableField("currency_code")
    private String currencyCode;

    @TableField("note")
    private String note;

    @TableField("buyer_contact_name")
    private String buyerContactName;

    @TableField("buyer_contact_phone")
    private String buyerContactPhone;

    @TableField("buyer_contact_address")
    private String buyerContactAddress;

    @TableField("approved_by")
    private Long approvedBy;

    @TableField("approved_at")
    private LocalDateTime approvedAt;

    @TableField("reject_reason")
    private String rejectReason;

    @Version
    @TableField("version")
    private Long version;

    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;

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

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getBuyerOrgId() {
        return buyerOrgId;
    }

    public void setBuyerOrgId(Long buyerOrgId) {
        this.buyerOrgId = buyerOrgId;
    }

    public Long getSellerOrgId() {
        return sellerOrgId;
    }

    public void setSellerOrgId(Long sellerOrgId) {
        this.sellerOrgId = sellerOrgId;
    }

    public String getOrderType() {
        return orderType;
    }

    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getHandlingStatus() {
        return handlingStatus;
    }

    public void setHandlingStatus(String handlingStatus) {
        this.handlingStatus = handlingStatus;
    }

    public LocalDateTime getOrderedAt() {
        return orderedAt;
    }

    public void setOrderedAt(LocalDateTime orderedAt) {
        this.orderedAt = orderedAt;
    }

    public LocalDateTime getExpectedDeliveryAt() {
        return expectedDeliveryAt;
    }

    public void setExpectedDeliveryAt(LocalDateTime expectedDeliveryAt) {
        this.expectedDeliveryAt = expectedDeliveryAt;
    }

    public Long getReceiptBatchId() {
        return receiptBatchId;
    }

    public void setReceiptBatchId(Long receiptBatchId) {
        this.receiptBatchId = receiptBatchId;
    }

    public Long getTraceCodeId() {
        return traceCodeId;
    }

    public void setTraceCodeId(Long traceCodeId) {
        this.traceCodeId = traceCodeId;
    }

    public String getPublicTraceId() {
        return publicTraceId;
    }

    public void setPublicTraceId(String publicTraceId) {
        this.publicTraceId = publicTraceId;
    }

    public String getCancelRequestRole() {
        return cancelRequestRole;
    }

    public void setCancelRequestRole(String cancelRequestRole) {
        this.cancelRequestRole = cancelRequestRole;
    }

    public String getCancelRequestReason() {
        return cancelRequestReason;
    }

    public void setCancelRequestReason(String cancelRequestReason) {
        this.cancelRequestReason = cancelRequestReason;
    }

    public String getCancelRequestStatus() {
        return cancelRequestStatus;
    }

    public void setCancelRequestStatus(String cancelRequestStatus) {
        this.cancelRequestStatus = cancelRequestStatus;
    }

    public LocalDateTime getCancelRequestAt() {
        return cancelRequestAt;
    }

    public void setCancelRequestAt(LocalDateTime cancelRequestAt) {
        this.cancelRequestAt = cancelRequestAt;
    }

    public BigDecimal getAmountTotal() {
        return amountTotal;
    }

    public void setAmountTotal(BigDecimal amountTotal) {
        this.amountTotal = amountTotal;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getBuyerContactName() {
        return buyerContactName;
    }

    public void setBuyerContactName(String buyerContactName) {
        this.buyerContactName = buyerContactName;
    }

    public String getBuyerContactPhone() {
        return buyerContactPhone;
    }

    public void setBuyerContactPhone(String buyerContactPhone) {
        this.buyerContactPhone = buyerContactPhone;
    }

    public String getBuyerContactAddress() {
        return buyerContactAddress;
    }

    public void setBuyerContactAddress(String buyerContactAddress) {
        this.buyerContactAddress = buyerContactAddress;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(Long approvedBy) {
        this.approvedBy = approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
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
}