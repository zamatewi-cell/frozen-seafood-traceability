package com.example.traceability.trace.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 公开追溯码统一操作幂等记录实体。
 * <p>
 * 映射 public_trace_code_idempotency 表。
 * 针对每一个 (org_id, idempotency_key) 保证唯一性绑定，
 * 结构化记录 action (ACTIVATE/DISABLE)、batch_id、public_trace_code_id 与 request_hash。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@TableName("public_trace_code_idempotency")
public class PublicTraceCodeIdempotency {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("org_id")
    private Long orgId;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("action")
    private String action;

    @TableField("batch_id")
    private Long batchId;

    @TableField("public_trace_code_id")
    private Long publicTraceCodeId;

    @TableField("request_hash")
    private String requestHash;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public PublicTraceCodeIdempotency() {
    }

    public PublicTraceCodeIdempotency(Long orgId, String idempotencyKey, String action, Long batchId, Long publicTraceCodeId, String requestHash, LocalDateTime createdAt) {
        this.orgId = orgId;
        this.idempotencyKey = idempotencyKey;
        this.action = action;
        this.batchId = batchId;
        this.publicTraceCodeId = publicTraceCodeId;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
    }

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

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getPublicTraceCodeId() {
        return publicTraceCodeId;
    }

    public void setPublicTraceCodeId(Long publicTraceCodeId) {
        this.publicTraceCodeId = publicTraceCodeId;
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
