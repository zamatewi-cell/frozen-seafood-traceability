package com.example.traceability.batch.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 批次谱系父子关系图谱边实体。
 * <p>
 * 映射数据库 {@code batch_relation} 表，由批次操作提交时自动根据 INPUT × OUTPUT 笛卡尔积生成。
 * 表达父批次到子批次的有向溯源拓扑关系。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@TableName("batch_relation")
public class BatchRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("operation_id")
    private Long operationId;

    @TableField("parent_batch_id")
    private Long parentBatchId;

    @TableField("child_batch_id")
    private Long childBatchId;

    @TableField("relation_type")
    private String relationType;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOperationId() {
        return operationId;
    }

    public void setOperationId(Long operationId) {
        this.operationId = operationId;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
