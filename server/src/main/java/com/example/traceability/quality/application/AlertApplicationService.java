package com.example.traceability.quality.application;

import com.example.traceability.audit.application.AuditApplicationService;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.domain.Alert;
import com.example.traceability.quality.domain.AlertAction;
import com.example.traceability.quality.domain.AlertStatus;
import com.example.traceability.quality.dto.AlertAcknowledgeRequest;
import com.example.traceability.quality.dto.AlertResponse;
import com.example.traceability.quality.mapper.AlertActionMapper;
import com.example.traceability.quality.mapper.AlertBatchMapper;
import com.example.traceability.quality.mapper.AlertMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 告警查询与人工处置应用服务（Phase B PB3；统一业务契约 v1.1 §2.11 / §10.2 步骤 7 / §10.3 / §13 步骤 7 / §14）。
 * <p>
 * 查看：告警归属组织（运输任务发货方，任意角色）、运输任务接收方与承运方（任意角色，只读）与平台只读角色。
 * 确认：只有告警归属组织的质量管理员（QUALITY_MANAGER）可以确认异常（OPEN → ACKNOWLEDGED），确认人即处置负责人；
 * 平台 / 系统管理员不可代办。确认不改变任何批次、交接或运输任务状态，不生成 TraceEvent。
 * </p>
 * <p>
 * 处置动作的锁顺序：动作幂等预读（非锁定）→ 告警行锁（FOR UPDATE，串行化同一告警的全部处置）→ 锁后幂等复读 →
 * 追加动作行（幂等唯一索引上的锁只在告警行锁之后获取）→ 条件更新告警。隔离级别 READ COMMITTED：持锁后的复读必须
 * 看到等待告警行锁期间已提交的同键动作。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class AlertApplicationService {

    static final String AUDIT_ACTION_ACKNOWLEDGE = "ALERT_ACKNOWLEDGE";
    static final String AUDIT_OBJECT_TYPE = "ALERT";
    static final String ACTION_ACKNOWLEDGE = "ACKNOWLEDGE";
    static final int NOTE_MAX_LENGTH = 500;
    static final String RESERVED_SYSTEM_KEY_PREFIX = "SYS:";

    private static final String ROLE_QUALITY_MANAGER = "QUALITY_MANAGER";
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");

    private final AlertMapper alertMapper;
    private final AlertBatchMapper alertBatchMapper;
    private final AlertActionMapper alertActionMapper;
    private final AuditApplicationService auditService;
    private final ObjectMapper objectMapper;

    public AlertApplicationService(
            AlertMapper alertMapper,
            AlertBatchMapper alertBatchMapper,
            AlertActionMapper alertActionMapper,
            AuditApplicationService auditService,
            ObjectMapper objectMapper
    ) {
        this.alertMapper = Objects.requireNonNull(alertMapper, "alertMapper 不能为空");
        this.alertBatchMapper = Objects.requireNonNull(alertBatchMapper, "alertBatchMapper 不能为空");
        this.alertActionMapper = Objects.requireNonNull(alertActionMapper, "alertActionMapper 不能为空");
        this.auditService = Objects.requireNonNull(auditService, "auditService 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 可见告警列表（按主键倒序），可按状态与运输任务过滤。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<AlertResponse> listAlerts(String status, Long shipmentId, TraceSecurityPrincipal principal) {
        requireAuthenticated(principal);
        String cleanStatus = null;
        if (status != null && !status.isBlank()) {
            String candidate = status.trim().toUpperCase(Locale.ROOT);
            if (EnumSet.allOf(AlertStatus.class).stream().noneMatch(s -> s.name().equals(candidate))) {
                throw badRequest("status 只能为 OPEN、ACKNOWLEDGED 或 RESOLVED");
            }
            cleanStatus = candidate;
        }
        return alertMapper.selectVisible(principal.getOrgId(), isPlatformScope(principal), cleanStatus, shipmentId).stream()
                .map(AlertResponse::summary)
                .toList();
    }

    /**
     * 告警详情：受影响批次快照（含批次与交接当前事实）与处置动作历史。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AlertResponse getAlert(Long alertId, TraceSecurityPrincipal principal) {
        requireAuthenticated(principal);
        Alert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + alertId + " 的告警");
        }
        requireVisible(alert, principal);
        return detail(alert);
    }

    /**
     * 确认告警：OPEN → ACKNOWLEDGED（告警归属组织的质量管理员）。
     * <p>
     * 校验顺序：认证 / 平台代办 / QUALITY_MANAGER → 幂等键 → 说明 → 幂等预读 → 告警行锁 → 锁后幂等复读 → 告警存在 →
     * 归属组织 → 状态 → 条件更新 → 追加动作 → 审计。幂等重放先于一切可变状态校验。
     * </p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AlertResponse acknowledge(Long alertId, AlertAcknowledgeRequest req, String idempotencyKey,
                                     TraceSecurityPrincipal principal) {
        checkQualityManager(principal, "确认告警");
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        String note = validateNote(req);
        Long orgId = principal.getOrgId();
        String requestHash = computeRequestHash(ACTION_ACKNOWLEDGE, alertId, note);

        AlertAction existing = alertActionMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (existing != null) {
            return replayOrConflict(existing, requestHash);
        }

        Alert alert = alertMapper.selectByIdForUpdate(alertId);
        AlertAction afterLock = alertActionMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (afterLock != null) {
            return replayOrConflict(afterLock, requestHash);
        }
        if (alert == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + alertId + " 的告警");
        }
        requireOwnerOrg(alert, orgId, "确认告警");
        if (!AlertStatus.OPEN.name().equals(alert.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "告警状态不允许",
                    "只有待确认（OPEN）的告警可以确认，当前状态为: " + alert.getStatus());
        }

        // 先追加动作行（此前本事务未修改任何数据，同键冲突时可安全重放或拒绝），再条件推进告警状态
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        AlertAction action = newAction(alert, ACTION_ACKNOWLEDGE, principal, note, cleanKey, requestHash, nowUtc);
        AlertResponse replay = insertAction(action, requestHash);
        if (replay != null) {
            return replay;
        }
        if (alertMapper.acknowledge(alert.getId(), alert.getVersion(), principal.getUserId(), nowUtc) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "资源版本冲突", "告警已被并发修改，请刷新后重试");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("alertActionId", action.getId());
        summary.put("fromStatus", AlertStatus.OPEN.name());
        summary.put("toStatus", AlertStatus.ACKNOWLEDGED.name());
        auditService.recordAudit(principal.getUserId(), orgId, AUDIT_ACTION_ACKNOWLEDGE, AUDIT_OBJECT_TYPE, alert.getId(),
                nowUtc, "SUCCESS", serializeSummary(summary));
        return detail(alertMapper.selectById(alert.getId()));
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    private AlertResponse detail(Alert alert) {
        return AlertResponse.detail(alert, alertBatchMapper.selectByAlertId(alert.getId()),
                alertActionMapper.selectByAlertId(alert.getId()));
    }

    private static AlertAction newAction(Alert alert, String action, TraceSecurityPrincipal principal, String note,
                                         String key, String requestHash, LocalDateTime nowUtc) {
        AlertAction a = new AlertAction();
        a.setAlertId(alert.getId());
        a.setOrgId(principal.getOrgId());
        a.setAction(action);
        a.setActorUserId(principal.getUserId());
        a.setNote(note);
        a.setIdempotencyKey(key);
        a.setRequestHash(requestHash);
        a.setOccurredAt(nowUtc);
        return a;
    }

    /**
     * 追加动作行（本事务此前未修改任何数据）。同组织同键被另一告警上的并发请求抢先提交时返回其重放结果
     * （请求哈希含告警 ID，实际总是冲突）；同一告警的同键请求已被告警行锁串行化并在锁后复读识别。
     *
     * @return null 表示已插入；非 null 为同键已提交动作的重放结果，调用方直接返回
     */
    private AlertResponse insertAction(AlertAction action, String requestHash) {
        try {
            alertActionMapper.insert(action);
            return null;
        } catch (DuplicateKeyException e) {
            AlertAction dup = alertActionMapper.selectByOrgIdAndIdempotencyKeyForUpdate(action.getOrgId(), action.getIdempotencyKey());
            if (dup != null) {
                return replayOrConflict(dup, requestHash);
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "ALERT_CONCURRENT_CONFLICT", "告警并发冲突",
                    "同一幂等键的并发请求发生冲突，本次告警处置已回滚，请使用相同幂等键重试");
        }
    }

    private AlertResponse replayOrConflict(AlertAction existing, String requestHash) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                    "当前幂等键已被本组织用于不同语义的告警处置请求");
        }
        return detail(alertMapper.selectById(existing.getAlertId()));
    }

    private static void requireVisible(Alert alert, TraceSecurityPrincipal principal) {
        Long orgId = principal.getOrgId();
        if (isPlatformScope(principal) || Objects.equals(alert.getOrgId(), orgId)
                || Objects.equals(alert.getReceiverOrgId(), orgId) || Objects.equals(alert.getCarrierOrgId(), orgId)) {
            return;
        }
        throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                "仅告警归属组织与运输任务的接收方、承运方可以查看该告警");
    }

    private static void requireOwnerOrg(Alert alert, Long orgId, String label) {
        if (!Objects.equals(alert.getOrgId(), orgId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                    "只有告警归属组织（受影响批次的当前责任组织）的质量管理员可以" + label);
        }
    }

    /**
     * 写权限：未认证 401；平台 / 系统管理员不可代办 403 ADMIN_RESTRICTED；必须是 QUALITY_MANAGER（403 ACCESS_DENIED）。
     */
    private static void checkQualityManager(TraceSecurityPrincipal principal, String label) {
        requireAuthenticated(principal);
        if (principal.getRoles().contains("SYSTEM_ADMIN") || isPlatformScope(principal)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_RESTRICTED", "管理员权限受限",
                    "平台管理角色不可代办企业告警处置");
        }
        if (!principal.getRoles().contains(ROLE_QUALITY_MANAGER)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足",
                    label + "仅限告警归属组织的质量管理员（QUALITY_MANAGER）执行");
        }
    }

    private static void requireAuthenticated(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未认证", "请先登录");
        }
    }

    private static String validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey.trim()).matches()) {
            throw badRequest("Idempotency-Key 请求头必填，长度 16 到 128 个字符，只能包含字母、数字与 . _ : -");
        }
        String clean = idempotencyKey.trim();
        if (clean.toUpperCase(Locale.ROOT).startsWith(RESERVED_SYSTEM_KEY_PREFIX)) {
            throw badRequest("Idempotency-Key 不能以保留前缀 SYS: 开头（系统生成键专用）");
        }
        return clean;
    }

    private static String validateNote(AlertAcknowledgeRequest req) {
        if (req == null) {
            throw badRequest("请求体不能为空");
        }
        if (req.unknownFields() != null && !req.unknownFields().isEmpty()) {
            throw badRequest("确认告警请求只接受 note，不接受以下字段（由服务端决定或未在契约中声明）: "
                    + String.join(", ", req.unknownFields().keySet()));
        }
        return cleanNote(req.note(), "说明 note");
    }

    static String cleanNote(String raw, String label) {
        String note = raw == null ? null : raw.trim();
        if (note != null && note.isEmpty()) {
            return null;
        }
        if (note != null && note.length() > NOTE_MAX_LENGTH) {
            throw badRequest(label + " 不能超过 " + NOTE_MAX_LENGTH + " 个字符");
        }
        return note;
    }

    /**
     * 规范化请求语义哈希：动作、告警与去除首尾空白后的说明（空说明与缺省等价）。
     */
    static String computeRequestHash(String action, Long alertId, String note) {
        String canonical = String.join("\u001F", "ALERT_ACTION", "v1", action, String.valueOf(alertId), note == null ? "" : note);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", e);
        }
    }

    private String serializeSummary(Map<String, Object> summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR", "审计日志序列化失败", e.getMessage());
        }
    }

    private static boolean isPlatformScope(TraceSecurityPrincipal principal) {
        return principal != null && principal.getScopes() != null && principal.getScopes().contains("PLATFORM");
    }

    private static BusinessException badRequest(String detail) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", detail);
    }
}
