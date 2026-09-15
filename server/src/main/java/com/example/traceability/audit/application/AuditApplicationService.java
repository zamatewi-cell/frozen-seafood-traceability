package com.example.traceability.audit.application;

import com.example.traceability.audit.domain.AuditLog;
import com.example.traceability.audit.mapper.AuditLogMapper;
import com.example.traceability.common.filter.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 结构化业务审计日志应用服务。
 * <p>
 * 负责记录全系统写操作（CREATE/UPDATE/DELETE/SUBMIT/ACCEPT/REJECT）的追加式审计轨迹，
 * 严格包含调用人、执行企业、操作对象、发生时间、全链路追踪 RequestId 及差异快照摘要。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class AuditApplicationService {

    private final AuditLogMapper auditLogMapper;

    public AuditApplicationService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 记录关键业务操作审计日志。
     *
     * @param actorUserId       操作人用户 ID
     * @param actorOrgId        操作人所属组织 ID
     * @param action            业务动作标识 (CREATE/UPDATE/DELETE/SUBMIT/ACCEPT/REJECT)
     * @param objectType        被操作对象类型 (如 "TRANSFER")
     * @param objectId          被操作对象 ID
     * @param occurredAt        操作发生时间 (UTC)
     * @param result            操作结果 (SUCCESS/DENIED/FAILED)
     * @param changeSummaryJson 差异变更结构化快照 JSON (白名单摘要，杜绝敏感载荷泄露)
     */
    public void recordAudit(
            Long actorUserId,
            Long actorOrgId,
            String action,
            String objectType,
            Long objectId,
            LocalDateTime occurredAt,
            String result,
            String changeSummaryJson
    ) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        AuditLog auditLog = new AuditLog();
        auditLog.setRequestId(requestId);
        auditLog.setActorUserId(actorUserId);
        auditLog.setActorOrgId(actorOrgId);
        auditLog.setAction(action);
        auditLog.setObjectType(objectType);
        auditLog.setObjectId(objectId);
        auditLog.setOccurredAt(occurredAt != null ? occurredAt : LocalDateTime.now(ZoneOffset.UTC));
        auditLog.setResult(result != null ? result : "SUCCESS");
        auditLog.setChangeSummaryJson(changeSummaryJson);
        auditLog.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));

        auditLogMapper.insert(auditLog);
    }
}
