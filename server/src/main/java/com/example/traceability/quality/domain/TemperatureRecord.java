package com.example.traceability.quality.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Shipment 在途温度记录实体（映射 {@code temperature_record} 表，追加式不可修改；Phase B PB2）。
 * <p>
 * 一条记录是某一时间点对 Shipment 运输环境的一次温度测量（契约 v1.1 §2.9 / §10.1），绑定运输任务而不是分别复制到
 * 各个批次（§13 步骤 1）。{@code orgId} 为登记时的承运组织，{@code evaluation} 为登记时按当时适用规则得出的单点判定，
 * 连同规则环节 ID、上下限与允许越界时长快照一起固定，之后不追溯改写（ADR-006）；历史判定依据只来自这些快照列，
 * 从不重新读取可能已被改动的规则环节。
 * </p>
 * <p>
 * {@code ruleId}、{@code ruleName}、{@code ruleVersionNo} 只由查询语句关联规则表填充（来源追溯展示），
 * 不属于本表列，插入时从不写入。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class TemperatureRecord {

    private Long id;
    private Long shipmentId;
    private Long orgId;
    private Long actorUserId;
    private String stageCode;
    private LocalDateTime measuredAt;
    private LocalDateTime recordedAt;
    private BigDecimal temperature;
    private String unitCode;
    private String dataSource;
    private String deviceNo;
    private Long ruleStageId;
    private BigDecimal ruleLowerLimit;
    private BigDecimal ruleUpperLimit;
    private Integer ruleAllowedDurationSeconds;
    private String evaluation;
    private String idempotencyKey;
    private String requestHash;

    // 以下为查询关联字段（非本表列）
    private Long ruleId;
    private String ruleName;
    private Integer ruleVersionNo;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getShipmentId() {
        return shipmentId;
    }

    public void setShipmentId(Long shipmentId) {
        this.shipmentId = shipmentId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(Long actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getStageCode() {
        return stageCode;
    }

    public void setStageCode(String stageCode) {
        this.stageCode = stageCode;
    }

    public LocalDateTime getMeasuredAt() {
        return measuredAt;
    }

    public void setMeasuredAt(LocalDateTime measuredAt) {
        this.measuredAt = measuredAt;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(LocalDateTime recordedAt) {
        this.recordedAt = recordedAt;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public void setTemperature(BigDecimal temperature) {
        this.temperature = temperature;
    }

    public String getUnitCode() {
        return unitCode;
    }

    public void setUnitCode(String unitCode) {
        this.unitCode = unitCode;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public String getDeviceNo() {
        return deviceNo;
    }

    public void setDeviceNo(String deviceNo) {
        this.deviceNo = deviceNo;
    }

    public Long getRuleStageId() {
        return ruleStageId;
    }

    public void setRuleStageId(Long ruleStageId) {
        this.ruleStageId = ruleStageId;
    }

    public BigDecimal getRuleLowerLimit() {
        return ruleLowerLimit;
    }

    public void setRuleLowerLimit(BigDecimal ruleLowerLimit) {
        this.ruleLowerLimit = ruleLowerLimit;
    }

    public BigDecimal getRuleUpperLimit() {
        return ruleUpperLimit;
    }

    public void setRuleUpperLimit(BigDecimal ruleUpperLimit) {
        this.ruleUpperLimit = ruleUpperLimit;
    }

    public String getEvaluation() {
        return evaluation;
    }

    public void setEvaluation(String evaluation) {
        this.evaluation = evaluation;
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

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public Integer getRuleVersionNo() {
        return ruleVersionNo;
    }

    public void setRuleVersionNo(Integer ruleVersionNo) {
        this.ruleVersionNo = ruleVersionNo;
    }

    public Integer getRuleAllowedDurationSeconds() {
        return ruleAllowedDurationSeconds;
    }

    public void setRuleAllowedDurationSeconds(Integer ruleAllowedDurationSeconds) {
        this.ruleAllowedDurationSeconds = ruleAllowedDurationSeconds;
    }
}
