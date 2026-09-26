package com.example.traceability.quality.domain;

import java.time.LocalDateTime;

/**
 * 批次收到的一条上游召回通知（只读查询投影，不对应独立表；Phase B 独立评审修复）。
 * <p>
 * 来自其他召回影响范围中该批次的 NOTIFY_HOLDER 行：通知属于批次，由批次的当前责任组织查看并决定风险冻结或以此为证据发起本组织召回。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class RecallNotice {

    private Long recallId;

    private String recallNo;

    private String recallStatus;

    private Long ownerOrgId;

    private LocalDateTime startedAt;

    private Integer depth;

    public Long getRecallId() {
        return recallId;
    }

    public void setRecallId(Long recallId) {
        this.recallId = recallId;
    }

    public String getRecallNo() {
        return recallNo;
    }

    public void setRecallNo(String recallNo) {
        this.recallNo = recallNo;
    }

    public String getRecallStatus() {
        return recallStatus;
    }

    public void setRecallStatus(String recallStatus) {
        this.recallStatus = recallStatus;
    }

    public Long getOwnerOrgId() {
        return ownerOrgId;
    }

    public void setOwnerOrgId(Long ownerOrgId) {
        this.ownerOrgId = ownerOrgId;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public Integer getDepth() {
        return depth;
    }

    public void setDepth(Integer depth) {
        this.depth = depth;
    }
}
