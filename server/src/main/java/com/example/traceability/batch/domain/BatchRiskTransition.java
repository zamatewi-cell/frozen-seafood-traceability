package com.example.traceability.batch.domain;

import java.time.LocalDateTime;

/**
 * 批次风险状态转换台账实体（映射 {@code batch_risk_transition} 表，追加式不可修改）。
 * <p>
 * 每一次运行期 {@code batch.risk_status} 变更恰好对应一行，与批次条件更新、审计日志在同一事务内提交。
 * {@code orgId} 为转换时批次当前责任组织，{@code flowStatus} 为转换时流转状态快照（转换不改变流转状态）。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class BatchRiskTransition {

    private Long id;
    private Long batchId;
    private Long orgId;
    private String flowStatus;
    private String fromStatus;
    private String toStatus;
    private String sourceType;
    private Long actorUserId;
    private String reason;
    private String idempotencyKey;
    private String requestHash;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public String getFlowStatus() {
        return flowStatus;
    }

    public void setFlowStatus(String flowStatus) {
        this.flowStatus = flowStatus;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(Long actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
