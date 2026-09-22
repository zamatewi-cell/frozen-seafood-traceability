package com.example.traceability.trace.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 冷链运输任务多动作统一幂等记录实体。
 * <p>
 * 对应数据库表 {@code shipment_idempotency}，在 {@code (org_id, idempotency_key)} 上建立唯一约束。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
@TableName("`shipment_idempotency`")
public class ShipmentIdempotency {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("org_id")
    private Long orgId;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("action")
    private String action;

    @TableField("shipment_id")
    private Long shipmentId;

    @TableField("request_hash")
    private String requestHash;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Long getShipmentId() {
        return shipmentId;
    }

    public void setShipmentId(Long shipmentId) {
        this.shipmentId = shipmentId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
