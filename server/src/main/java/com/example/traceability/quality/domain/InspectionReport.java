package com.example.traceability.quality.domain;

import java.time.LocalDateTime;

/**
 * 批次检验报告（映射 {@code inspection_report} 表，追加式；Phase B PB4；统一业务契约 v1.1 §2.10 / §10.3）。
 * <p>
 * 针对 Batch 的结构化检验报告：报告编号、检验机构名称（本系统不核验其资质与真实性）、检验时间、检测项目摘要与结论 PASS / FAIL。
 * 由批次当前责任组织（CURRENT_ORG）或该批次隔离交接的接收方（QUARANTINE_RECEIVER）的质量管理员提交，可关联受影响批次所属告警。
 * 报告只是证据：不自动冻结、不自动放行、不自动召回。登记后不可修改或删除。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class InspectionReport {

    private Long id;
    private Long batchId;
    private Long orgId;
    private String submitterRole;
    private Long transferId;
    private Long alertId;
    private String reportNo;
    private String institutionName;
    private LocalDateTime inspectedAt;
    private String itemsSummary;
    private String conclusion;
    private String dataSource;
    private Long actorUserId;
    private String idempotencyKey;
    private String requestHash;
    private LocalDateTime recordedAt;

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

    public String getSubmitterRole() {
        return submitterRole;
    }

    public void setSubmitterRole(String submitterRole) {
        this.submitterRole = submitterRole;
    }

    public Long getTransferId() {
        return transferId;
    }

    public void setTransferId(Long transferId) {
        this.transferId = transferId;
    }

    public Long getAlertId() {
        return alertId;
    }

    public void setAlertId(Long alertId) {
        this.alertId = alertId;
    }

    public String getReportNo() {
        return reportNo;
    }

    public void setReportNo(String reportNo) {
        this.reportNo = reportNo;
    }

    public String getInstitutionName() {
        return institutionName;
    }

    public void setInstitutionName(String institutionName) {
        this.institutionName = institutionName;
    }

    public LocalDateTime getInspectedAt() {
        return inspectedAt;
    }

    public void setInspectedAt(LocalDateTime inspectedAt) {
        this.inspectedAt = inspectedAt;
    }

    public String getItemsSummary() {
        return itemsSummary;
    }

    public void setItemsSummary(String itemsSummary) {
        this.itemsSummary = itemsSummary;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(Long actorUserId) {
        this.actorUserId = actorUserId;
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

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(LocalDateTime recordedAt) {
        this.recordedAt = recordedAt;
    }
}
