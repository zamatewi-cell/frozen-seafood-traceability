package com.example.traceability.audit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 追加式操作审计日志实体。
 * <p>
 * 对应数据库表 {@code audit_log}，不可篡改地记录核心业务变更的主体、组织、动作、对象、时间与摘要。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@TableName("`audit_log`")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("request_id")
    private String requestId;

    @TableField("actor_user_id")
    private Long actorUserId;

    @TableField("actor_org_id")
    private Long actorOrgId;

    @TableField("action")
    private String action;

    @TableField("object_type")
    private String objectType;

    @TableField("object_id")
    private Long objectId;

    @TableField("occurred_at")
    private LocalDateTime occurredAt;

    @TableField("result")
    private String result;

    @TableField("change_summary_json")
    private String changeSummaryJson;

    @TableField("client_ip_hash")
    private String clientIpHash;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(Long actorUserId) {
        this.actorUserId = actorUserId;
    }

    public Long getActorOrgId() {
        return actorOrgId;
    }

    public void setActorOrgId(Long actorOrgId) {
        this.actorOrgId = actorOrgId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public Long getObjectId() {
        return objectId;
    }

    public void setObjectId(Long objectId) {
        this.objectId = objectId;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getChangeSummaryJson() {
        return changeSummaryJson;
    }

    public void setChangeSummaryJson(String changeSummaryJson) {
        this.changeSummaryJson = changeSummaryJson;
    }

    public String getClientIpHash() {
        return clientIpHash;
    }

    public void setClientIpHash(String clientIpHash) {
        this.clientIpHash = clientIpHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
