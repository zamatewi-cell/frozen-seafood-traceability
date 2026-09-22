package com.example.traceability.trace.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

/**
 * 冷链运输任务实体。
 * <p>
 * 对应数据库表 {@code shipment}，表达一次物理冷链运输：承运组织、车辆或容器、起止场所、装载发运与到达。
 * Shipment 不转移 Batch 当前责任组织 (统一业务契约 v1.1 §2.8 / §3.3)；
 * 一个 Shipment 可承载多个 Transfer，同一 Shipment 内的 Transfer 具有相同发送方与接收方。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
@TableName("`shipment`")
public class Shipment {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("shipment_no")
    private String shipmentNo;

    @TableField("sender_org_id")
    private Long senderOrgId;

    @TableField("receiver_org_id")
    private Long receiverOrgId;

    @TableField("carrier_org_id")
    private Long carrierOrgId;

    @TableField("vehicle_or_container_no")
    private String vehicleOrContainerNo;

    @TableField("origin_site_id")
    private Long originSiteId;

    @TableField("destination_site_id")
    private Long destinationSiteId;

    @TableField("loaded_at")
    private LocalDateTime loadedAt;

    @TableField("unloaded_at")
    private LocalDateTime unloadedAt;

    @TableField("dispatched_recorded_at")
    private LocalDateTime dispatchedRecordedAt;

    @TableField("dispatched_by")
    private Long dispatchedBy;

    @TableField("delivered_recorded_at")
    private LocalDateTime deliveredRecordedAt;

    @TableField("delivered_by")
    private Long deliveredBy;

    @TableField("cancelled_recorded_at")
    private LocalDateTime cancelledRecordedAt;

    @TableField("cancelled_by")
    private Long cancelledBy;

    @TableField("cancel_reason")
    private String cancelReason;

    @TableField("status")
    private ShipmentStatus status;

    @Version
    @TableField("version")
    private Long version;

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

    public String getShipmentNo() {
        return shipmentNo;
    }

    public void setShipmentNo(String shipmentNo) {
        this.shipmentNo = shipmentNo;
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

    public Long getCarrierOrgId() {
        return carrierOrgId;
    }

    public void setCarrierOrgId(Long carrierOrgId) {
        this.carrierOrgId = carrierOrgId;
    }

    public String getVehicleOrContainerNo() {
        return vehicleOrContainerNo;
    }

    public void setVehicleOrContainerNo(String vehicleOrContainerNo) {
        this.vehicleOrContainerNo = vehicleOrContainerNo;
    }

    public Long getOriginSiteId() {
        return originSiteId;
    }

    public void setOriginSiteId(Long originSiteId) {
        this.originSiteId = originSiteId;
    }

    public Long getDestinationSiteId() {
        return destinationSiteId;
    }

    public void setDestinationSiteId(Long destinationSiteId) {
        this.destinationSiteId = destinationSiteId;
    }

    public LocalDateTime getLoadedAt() {
        return loadedAt;
    }

    public void setLoadedAt(LocalDateTime loadedAt) {
        this.loadedAt = loadedAt;
    }

    public LocalDateTime getUnloadedAt() {
        return unloadedAt;
    }

    public void setUnloadedAt(LocalDateTime unloadedAt) {
        this.unloadedAt = unloadedAt;
    }

    public LocalDateTime getDispatchedRecordedAt() {
        return dispatchedRecordedAt;
    }

    public void setDispatchedRecordedAt(LocalDateTime dispatchedRecordedAt) {
        this.dispatchedRecordedAt = dispatchedRecordedAt;
    }

    public Long getDispatchedBy() {
        return dispatchedBy;
    }

    public void setDispatchedBy(Long dispatchedBy) {
        this.dispatchedBy = dispatchedBy;
    }

    public LocalDateTime getDeliveredRecordedAt() {
        return deliveredRecordedAt;
    }

    public void setDeliveredRecordedAt(LocalDateTime deliveredRecordedAt) {
        this.deliveredRecordedAt = deliveredRecordedAt;
    }

    public Long getDeliveredBy() {
        return deliveredBy;
    }

    public void setDeliveredBy(Long deliveredBy) {
        this.deliveredBy = deliveredBy;
    }

    public LocalDateTime getCancelledRecordedAt() {
        return cancelledRecordedAt;
    }

    public void setCancelledRecordedAt(LocalDateTime cancelledRecordedAt) {
        this.cancelledRecordedAt = cancelledRecordedAt;
    }

    public Long getCancelledBy() {
        return cancelledBy;
    }

    public void setCancelledBy(Long cancelledBy) {
        this.cancelledBy = cancelledBy;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public void setStatus(ShipmentStatus status) {
        this.status = status;
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
