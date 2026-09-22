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
 * 订单-批次分配实体。
 * <p>
 * 交付时从卖方库存选批次分配给订单。一个订单可分配多批，
 * 每批对应树状图中的一个分支，溯源查询时按分支展开。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
@TableName("order_batch_allocation")
public class OrderBatchAllocation {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private Long orderId;

    @TableField("batch_id")
    private Long batchId;

    @TableField("allocated_quantity")
    private BigDecimal allocatedQuantity;

    @TableField("unit_code")
    private String unitCode;

    @TableField("allocation_order")
    private Integer allocationOrder;

    @TableField("org_id")
    private Long orgId;

    @TableField("allocated_by")
    private Long allocatedBy;

    @TableField("allocated_at")
    private LocalDateTime allocatedAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @Version
    @TableField("version")
    private Long version;

    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public BigDecimal getAllocatedQuantity() {
        return allocatedQuantity;
    }

    public void setAllocatedQuantity(BigDecimal allocatedQuantity) {
        this.allocatedQuantity = allocatedQuantity;
    }

    public String getUnitCode() {
        return unitCode;
    }

    public void setUnitCode(String unitCode) {
        this.unitCode = unitCode;
    }

    public Integer getAllocationOrder() {
        return allocationOrder;
    }

    public void setAllocationOrder(Integer allocationOrder) {
        this.allocationOrder = allocationOrder;
    }

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public Long getAllocatedBy() {
        return allocatedBy;
    }

    public void setAllocatedBy(Long allocatedBy) {
        this.allocatedBy = allocatedBy;
    }

    public LocalDateTime getAllocatedAt() {
        return allocatedAt;
    }

    public void setAllocatedAt(LocalDateTime allocatedAt) {
        this.allocatedAt = allocatedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
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
}
