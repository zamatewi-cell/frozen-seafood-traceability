package com.example.traceability.batch.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 追溯批次主表实体。
 * <p>
 * 映射数据库 {@code batch} 表，表达冷冻海鲜追溯体系中的最小实物管理与流转单元。
 * 业务字段采用双编号（{@code traceBatchNo} 服务端全局唯一追溯批号，{@code externalBatchNo} 企业可选业务批号）
 * 与双状态（{@code flowStatus} 流转状态，{@code riskStatus} 质量风险状态）正交模型。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@TableName("batch")
public class Batch {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 当前责任持有组织。Transfer ACCEPTED 时转移给接收方。 */
    @TableField("org_id")
    private Long orgId;

    /**
     * 创建该批次的组织。
     * <p>
     * 创建后不可变，仅用于创建幂等键作用域与审计，<b>不</b>代表当前责任组织，
     * 也不对客户端开放写入，不进入 BatchResponse 与消费者公开投影。
     * </p>
     */
    @TableField("creation_org_id")
    private Long creationOrgId;

    @TableField("product_id")
    private Long productId;

    @TableField("trace_batch_no")
    private String traceBatchNo;

    @TableField("external_batch_no")
    private String externalBatchNo;

    @TableField("batch_type")
    private String batchType;

    @TableField("quantity")
    private BigDecimal quantity;

    @TableField("unit_code")
    private String unitCode;

    @TableField("origin_type")
    private String originType;

    @TableField("origin_text")
    private String originText;

    @TableField("production_date")
    private LocalDate productionDate;

    @TableField("capture_date")
    private LocalDate captureDate;

    @TableField("freeze_date")
    private LocalDate freezeDate;

    @TableField("shelf_life_days")
    private Integer shelfLifeDays;

    @TableField("flow_status")
    private String flowStatus;

    /**
     * 风险状态：插入时写入 NORMAL；此后只能经 {@code BatchRiskService} → {@code BatchRiskStateMapper} 在同一事务内
     * 连同风险转换台账一起变更。更新策略 NEVER：通用实体更新（updateById / update(entity, wrapper)）永不写入该列，
     * 过期实体不能覆盖冻结状态。
     */
    @TableField(value = "risk_status", updateStrategy = FieldStrategy.NEVER)
    private String riskStatus;

    /** 产出该批次的批次操作 ID；仅服务端生成的操作输出批次非空（Slice 3 起）。 */
    @TableField("produced_by_operation_id")
    private Long producedByOperationId;

    /** 全量消耗该批次的已提交批次操作 ID；非空时批次必为 CLOSED（Slice 3 起）。 */
    @TableField("consumed_by_operation_id")
    private Long consumedByOperationId;

    /** 第一次有效终端销售 ID（写一次，Slice 5 起）；非空后该批次永久禁止交接与批次操作。 */
    @TableField("first_sale_id")
    private Long firstSaleId;

    @TableField("creation_idempotency_key")
    private String creationIdempotencyKey;

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

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public Long getCreationOrgId() {
        return creationOrgId;
    }

    public void setCreationOrgId(Long creationOrgId) {
        this.creationOrgId = creationOrgId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getTraceBatchNo() {
        return traceBatchNo;
    }

    public void setTraceBatchNo(String traceBatchNo) {
        this.traceBatchNo = traceBatchNo;
    }

    public String getExternalBatchNo() {
        return externalBatchNo;
    }

    public void setExternalBatchNo(String externalBatchNo) {
        this.externalBatchNo = externalBatchNo;
    }

    public String getBatchType() {
        return batchType;
    }

    public void setBatchType(String batchType) {
        this.batchType = batchType;
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

    public String getOriginType() {
        return originType;
    }

    public void setOriginType(String originType) {
        this.originType = originType;
    }

    public String getOriginText() {
        return originText;
    }

    public void setOriginText(String originText) {
        this.originText = originText;
    }

    public LocalDate getProductionDate() {
        return productionDate;
    }

    public void setProductionDate(LocalDate productionDate) {
        this.productionDate = productionDate;
    }

    public LocalDate getCaptureDate() {
        return captureDate;
    }

    public void setCaptureDate(LocalDate captureDate) {
        this.captureDate = captureDate;
    }

    public LocalDate getFreezeDate() {
        return freezeDate;
    }

    public void setFreezeDate(LocalDate freezeDate) {
        this.freezeDate = freezeDate;
    }

    public Integer getShelfLifeDays() {
        return shelfLifeDays;
    }

    public void setShelfLifeDays(Integer shelfLifeDays) {
        this.shelfLifeDays = shelfLifeDays;
    }

    public String getFlowStatus() {
        return flowStatus;
    }

    public void setFlowStatus(String flowStatus) {
        this.flowStatus = flowStatus;
    }

    public String getRiskStatus() {
        return riskStatus;
    }

    public void setRiskStatus(String riskStatus) {
        this.riskStatus = riskStatus;
    }

    public String getCreationIdempotencyKey() {
        return creationIdempotencyKey;
    }

    public void setCreationIdempotencyKey(String creationIdempotencyKey) {
        this.creationIdempotencyKey = creationIdempotencyKey;
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

    public Long getProducedByOperationId() {
        return producedByOperationId;
    }

    public void setProducedByOperationId(Long producedByOperationId) {
        this.producedByOperationId = producedByOperationId;
    }

    public Long getConsumedByOperationId() {
        return consumedByOperationId;
    }

    public void setConsumedByOperationId(Long consumedByOperationId) {
        this.consumedByOperationId = consumedByOperationId;
    }

    public Long getFirstSaleId() {
        return firstSaleId;
    }

    public void setFirstSaleId(Long firstSaleId) {
        this.firstSaleId = firstSaleId;
    }
}
