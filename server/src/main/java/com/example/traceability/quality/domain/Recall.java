package com.example.traceability.quality.domain;

import java.time.LocalDateTime;

/**
 * 模拟召回案件（映射 {@code recall} 表；Phase B PB5；统一业务契约 v1.1 §2.12 / §13 步骤 8–14）。
 * <p>
 * 由被召回批次当前责任组织的质量管理员发起的教学演练案件，不表示真实法定召回。生命周期 IN_PROGRESS → CLOSED；关闭时给出受控的
 * 公开处置结论（DESTROYED / RETURNED，消费者页面只显示固定文案）与内部处置总结。批次在关闭后仍保留 RECALLED。召回从不删除。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class Recall {

    private Long id;
    private String recallNo;
    private Long ownerOrgId;
    private Long sourceAlertId;
    private String reason;
    private String status;
    private LocalDateTime startedAt;
    private Long startedBy;
    private LocalDateTime closedAt;
    private Long closedBy;
    private String publicDisposition;
    private String resultSummary;
    private String idempotencyKey;
    private String requestHash;
    private String closeIdempotencyKey;
    private String closeRequestHash;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 查询计算字段（非本表列）：查看组织与召回的关系 OWNER / CURRENT_HOLDER / HISTORICAL_HOLDER / PLATFORM。 */
    private String viewerRelation;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRecallNo() {
        return recallNo;
    }

    public void setRecallNo(String recallNo) {
        this.recallNo = recallNo;
    }

    public Long getOwnerOrgId() {
        return ownerOrgId;
    }

    public void setOwnerOrgId(Long ownerOrgId) {
        this.ownerOrgId = ownerOrgId;
    }

    public Long getSourceAlertId() {
        return sourceAlertId;
    }

    public void setSourceAlertId(Long sourceAlertId) {
        this.sourceAlertId = sourceAlertId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public Long getStartedBy() {
        return startedBy;
    }

    public void setStartedBy(Long startedBy) {
        this.startedBy = startedBy;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public Long getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(Long closedBy) {
        this.closedBy = closedBy;
    }

    public String getPublicDisposition() {
        return publicDisposition;
    }

    public void setPublicDisposition(String publicDisposition) {
        this.publicDisposition = publicDisposition;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public void setResultSummary(String resultSummary) {
        this.resultSummary = resultSummary;
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

    public String getCloseIdempotencyKey() {
        return closeIdempotencyKey;
    }

    public void setCloseIdempotencyKey(String closeIdempotencyKey) {
        this.closeIdempotencyKey = closeIdempotencyKey;
    }

    public String getCloseRequestHash() {
        return closeRequestHash;
    }

    public void setCloseRequestHash(String closeRequestHash) {
        this.closeRequestHash = closeRequestHash;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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

    public String getViewerRelation() {
        return viewerRelation;
    }

    public void setViewerRelation(String viewerRelation) {
        this.viewerRelation = viewerRelation;
    }
}
