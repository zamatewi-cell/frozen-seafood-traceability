package com.example.traceability.batch.domain;

import java.time.LocalDateTime;

/**
 * 反向溯源查询得到的一条祖先谱系边（只读查询投影，不对应独立表）。
 * <p>
 * 由 {@code batch_relation} 左连接产生该边的 {@code batch_operation} 得到。批次操作字段可能为 null
 * （操作行缺失），调用方必须校验操作已提交、未删除且类型受控，否则按谱系完整性错误拒绝，不得静默丢弃该边。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class BatchLineageEdge {

    private Long parentBatchId;

    private Long childBatchId;

    private String relationType;

    private Long operationId;

    private String operationType;

    private String operationStatus;

    private Integer operationDeleted;

    private LocalDateTime operationOccurredAt;

    public BatchLineageEdge() {
    }

    public BatchLineageEdge(
            Long parentBatchId,
            Long childBatchId,
            String relationType,
            Long operationId,
            String operationType,
            String operationStatus,
            Integer operationDeleted,
            LocalDateTime operationOccurredAt
    ) {
        this.parentBatchId = parentBatchId;
        this.childBatchId = childBatchId;
        this.relationType = relationType;
        this.operationId = operationId;
        this.operationType = operationType;
        this.operationStatus = operationStatus;
        this.operationDeleted = operationDeleted;
        this.operationOccurredAt = operationOccurredAt;
    }

    public Long getParentBatchId() {
        return parentBatchId;
    }

    public void setParentBatchId(Long parentBatchId) {
        this.parentBatchId = parentBatchId;
    }

    public Long getChildBatchId() {
        return childBatchId;
    }

    public void setChildBatchId(Long childBatchId) {
        this.childBatchId = childBatchId;
    }

    public String getRelationType() {
        return relationType;
    }

    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }

    public Long getOperationId() {
        return operationId;
    }

    public void setOperationId(Long operationId) {
        this.operationId = operationId;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getOperationStatus() {
        return operationStatus;
    }

    public void setOperationStatus(String operationStatus) {
        this.operationStatus = operationStatus;
    }

    public Integer getOperationDeleted() {
        return operationDeleted;
    }

    public void setOperationDeleted(Integer operationDeleted) {
        this.operationDeleted = operationDeleted;
    }

    public LocalDateTime getOperationOccurredAt() {
        return operationOccurredAt;
    }

    public void setOperationOccurredAt(LocalDateTime operationOccurredAt) {
        this.operationOccurredAt = operationOccurredAt;
    }
}
