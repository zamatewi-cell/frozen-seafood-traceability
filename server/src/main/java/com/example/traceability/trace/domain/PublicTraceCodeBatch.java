package com.example.traceability.trace.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 公开追溯码 - 批次聚合关联实体。
 * <p>
 * 映射 {@code public_trace_code_batch} 中间表，表达「一个公开溯源码聚合多个物理批次」的一对多关系。
 * 终端下单生成的公开码可随时间绑定多个批次，消费者扫码即可查看这些批次聚合的完整履历。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.2.0
 */
@TableName("public_trace_code_batch")
public class PublicTraceCodeBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("trace_code_id")
    private Long traceCodeId;

    @TableField("batch_id")
    private Long batchId;

    @TableField("batch_org_id")
    private Long batchOrgId;

    @TableField("bind_role")
    private String bindRole;

    @TableField("bound_at")
    private LocalDateTime boundAt;

    @TableField("bound_by")
    private Long boundBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTraceCodeId() {
        return traceCodeId;
    }

    public void setTraceCodeId(Long traceCodeId) {
        this.traceCodeId = traceCodeId;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getBatchOrgId() {
        return batchOrgId;
    }

    public void setBatchOrgId(Long batchOrgId) {
        this.batchOrgId = batchOrgId;
    }

    public String getBindRole() {
        return bindRole;
    }

    public void setBindRole(String bindRole) {
        this.bindRole = bindRole;
    }

    public LocalDateTime getBoundAt() {
        return boundAt;
    }

    public void setBoundAt(LocalDateTime boundAt) {
        this.boundAt = boundAt;
    }

    public Long getBoundBy() {
        return boundBy;
    }

    public void setBoundBy(Long boundBy) {
        this.boundBy = boundBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}