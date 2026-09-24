package com.example.traceability.batch.application;

import com.example.traceability.audit.application.AuditApplicationService;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchFlowStatus;
import com.example.traceability.batch.domain.BatchRiskSourceType;
import com.example.traceability.batch.domain.BatchRiskStatus;
import com.example.traceability.batch.domain.BatchRiskTransition;
import com.example.traceability.batch.dto.BatchRiskTransitionRequest;
import com.example.traceability.batch.dto.BatchRiskTransitionResponse;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.batch.mapper.BatchRiskStateMapper;
import com.example.traceability.batch.mapper.BatchRiskTransitionMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 批次风险状态核心应用服务（Phase B PB1；统一业务契约 v1.1 §4.2 / §4.3 / §5.9 / §14）。
 * <p>
 * 运行期 {@code batch.risk_status} 的<b>唯一</b>写入者。PB1 只提供人工转换：当前责任组织的质量管理员
 * （QUALITY_MANAGER）风险冻结 NORMAL → FROZEN、解除冻结 FROZEN → NORMAL，ACTIVE 与 CLOSED 批次均可，
 * DRAFT 不参与，RECALLED 为终态。每次转换在同一事务内：锁定批次 → 追加一行 {@code batch_risk_transition}
 * → 条件更新批次风险状态（版本 +1）→ 写 RISK_FREEZE / RISK_RELEASE 审计。
 * 转换不改变数量、责任组织、流转状态与公开追溯码，不生成 TraceEvent（尤其不是表示速冻工序的 FREEZE 事件），
 * 也不向祖先、后代或同源批次传播。冻结期间的业务阻断全部复用 Phase A 既有守卫（交接 / 批次操作 / 销售 /
 * 仓储 / 首次激活公开码），本服务不重复实现。未来 Alert（PB3，系统路径）与 Recall（PB5）必须调用本服务的核心，
 * 不得另建写入口。
 * </p>
 * <p>
 * 锁顺序与死锁安全：
 * <ul>
 *   <li>业务锁只有一行 batch（FOR UPDATE）；从不锁 shipment / transfer / batch_operation，也不锁第二个批次；</li>
 *   <li>风险台账 / 幂等唯一索引上的锁（插入时的唯一性检查可能等待其他事务未提交的同键行）只在取得批次行锁<b>之后</b>获取；</li>
 *   <li>任何生产路径都不存在"先持有风险台账 / 幂等索引锁、再请求批次行锁"的顺序；</li>
 *   <li>PB1 不存在 batch → shipment / transfer / batch_operation / 其他 batch 的加锁路径。</li>
 * </ul>
 * 因此 PB1 不引入任何反向业务锁边，与既有 shipment → transfer → batch、batch_operation → batch（升序）、
 * batch → trace_event、batch → public_trace_code 顺序不成环。未来多批次的系统 / 召回路径必须先按批次 ID 升序
 * 取得全部批次行锁，再插入风险转换行；已持有 shipment → transfer → batches 的调用方可直接调用风险核心而不改变该顺序。
 * 唯一的残余等待环是 InnoDB 自身的同键插入模式：三个事务插入同一 (org_id, idempotency_key) 且先插入者回滚时，
 * 两个等待者可能在该唯一索引上互为死锁，数据库回滚其中一个的整个事务；本服务把它映射为可重试的 409，而不是 500。
 * </p>
 * <p>
 * 隔离级别 READ COMMITTED：持锁后的幂等复读必须看到等待批次行锁期间已提交的同键转换。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class BatchRiskService {

    static final String AUDIT_ACTION_FREEZE = "RISK_FREEZE";
    static final String AUDIT_ACTION_RELEASE = "RISK_RELEASE";
    static final String AUDIT_OBJECT_TYPE = "BATCH";
    static final int REASON_MAX_LENGTH = 500;
    /** 服务端生成的系统幂等键前缀（例如 PB3 的 {@code SYS:ALERT:{alertId}:BATCH:{batchId}}），人工请求不得使用。 */
    static final String RESERVED_SYSTEM_KEY_PREFIX = "SYS:";

    private static final String ROLE_QUALITY_MANAGER = "QUALITY_MANAGER";
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");

    /**
     * 人工风险转换动作：目标状态由接口路径决定。
     */
    enum ManualAction {
        FREEZE(BatchRiskStatus.FROZEN, BatchRiskStatus.NORMAL, AUDIT_ACTION_FREEZE, "风险冻结"),
        RELEASE(BatchRiskStatus.NORMAL, BatchRiskStatus.FROZEN, AUDIT_ACTION_RELEASE, "解除冻结");

        final BatchRiskStatus target;
        final BatchRiskStatus expectedFrom;
        final String auditAction;
        final String label;

        ManualAction(BatchRiskStatus target, BatchRiskStatus expectedFrom, String auditAction, String label) {
            this.target = target;
            this.expectedFrom = expectedFrom;
            this.auditAction = auditAction;
            this.label = label;
        }
    }

    /** 核心转换结果：{@code replayed} 为 true 表示命中并发同键的已提交转换，本次未写入。 */
    private record Applied(BatchRiskTransition transition, boolean replayed) {
    }

    private final BatchMapper batchMapper;
    private final BatchRiskStateMapper riskStateMapper;
    private final BatchRiskTransitionMapper transitionMapper;
    private final AuditApplicationService auditService;
    private final ObjectMapper objectMapper;

    public BatchRiskService(
            BatchMapper batchMapper,
            BatchRiskStateMapper riskStateMapper,
            BatchRiskTransitionMapper transitionMapper,
            AuditApplicationService auditService,
            ObjectMapper objectMapper
    ) {
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper 不能为空");
        this.riskStateMapper = Objects.requireNonNull(riskStateMapper, "riskStateMapper 不能为空");
        this.transitionMapper = Objects.requireNonNull(transitionMapper, "transitionMapper 不能为空");
        this.auditService = Objects.requireNonNull(auditService, "auditService 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 人工风险冻结：NORMAL → FROZEN（ACTIVE / CLOSED 批次）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchRiskTransitionResponse freeze(Long batchId, BatchRiskTransitionRequest req, String idempotencyKey,
                                              TraceSecurityPrincipal principal) {
        return manualTransition(ManualAction.FREEZE, batchId, req, idempotencyKey, principal);
    }

    /**
     * 人工解除冻结：FROZEN → NORMAL（ACTIVE / CLOSED 批次）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchRiskTransitionResponse release(Long batchId, BatchRiskTransitionRequest req, String idempotencyKey,
                                               TraceSecurityPrincipal principal) {
        return manualTransition(ManualAction.RELEASE, batchId, req, idempotencyKey, principal);
    }

    /**
     * 查询批次风险转换历史（沿用追溯事件 / 批次操作的历史参与判定方式，契约 §14）：
     * <ul>
     *   <li>批次当前责任组织（任意角色）与平台只读角色：完整历史；</li>
     *   <li>历史参与组织（本组织曾作为当时的责任组织登记过该批次的风险转换）：仅本组织登记的转换，
     *       看不到之后其他组织的转换、原因与操作人；</li>
     *   <li>其他组织：403 ORG_SCOPE_DENIED；匿名：401。</li>
     * </ul>
     * 只读：历史参与组织不因此获得任何批次写权限。
     */
    // 授权所依据的可变责任组织事实与构造响应的全部数据必须来自同一 InnoDB 一致性快照：只读 REPEATABLE READ 事务
    // 让首个一致性读建立读视图，后续非锁定读复用它，看不到交接接受后新责任组织才提交的行（不加任何锁）
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<BatchRiskTransitionResponse> listTransitions(Long batchId, TraceSecurityPrincipal principal) {
        requireAuthenticated(principal);
        Batch batch = batchMapper.selectByIdIgnoreTenant(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }
        List<BatchRiskTransition> rows;
        if (isPlatformScope(principal) || Objects.equals(batch.getOrgId(), principal.getOrgId())) {
            rows = transitionMapper.selectByBatchId(batchId);
        } else if (transitionMapper.countByBatchIdAndOrgId(batchId, principal.getOrgId()) > 0) {
            rows = transitionMapper.selectByBatchIdAndOrgId(batchId, principal.getOrgId());
        } else {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                    "无权查看其他组织批次的风险状态转换记录");
        }
        return rows.stream().map(BatchRiskTransitionResponse::fromEntity).toList();
    }

    // =========================================================================
    // 人工转换流程
    // =========================================================================

    /**
     * 校验顺序：认证 / 平台代办 / QUALITY_MANAGER → 幂等键 → 原因 → 幂等预读 → 批次行锁 → 锁后幂等复读 →
     * 批次存在 → 当前责任组织 → 风险核心（状态校验 → 台账 → 批次条件更新）→ 审计。
     * <p>
     * 幂等重放先于一切可变状态与责任组织校验：同组织同键同语义的重试（包括状态已被之后的转换改变、
     * 或批次已交接给其他组织之后）返回本组织原转换记录，不产生新写入。
     * </p>
     */
    private BatchRiskTransitionResponse manualTransition(ManualAction action, Long batchId, BatchRiskTransitionRequest req,
                                                         String idempotencyKey, TraceSecurityPrincipal principal) {
        checkWriteAccess(principal);
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        String reason = validateReason(req);
        Long orgId = principal.getOrgId();
        String requestHash = computeRequestHash(action, batchId, reason);

        // 1. 幂等预读（不加锁）
        BatchRiskTransition existing = transitionMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (existing != null) {
            return replayOrConflict(existing, requestHash);
        }

        // 2. 锁定批次行（唯一业务锁与串行化点），锁后复读幂等键（同键并发的后到者在此识别先到者结果）
        Batch batch = batchMapper.selectByIdIgnoreTenantForUpdate(batchId);
        BatchRiskTransition afterLock = transitionMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (afterLock != null) {
            return replayOrConflict(afterLock, requestHash);
        }
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }

        // 3. 当前责任组织（历史参与组织、其他组织一律拒绝）
        if (!Objects.equals(batch.getOrgId(), orgId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                    "只有批次当前责任组织的质量管理员可以" + action.label);
        }

        // 4. 风险核心：状态校验 → 追加台账 → 批次条件更新
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        Applied applied = applyTransition(batch, action, BatchRiskSourceType.MANUAL, principal.getUserId(),
                reason, cleanKey, requestHash, nowUtc);
        if (applied.replayed()) {
            return BatchRiskTransitionResponse.fromEntity(applied.transition());
        }

        // 5. 审计（与转换同一事务）
        BatchRiskTransition t = applied.transition();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("transitionId", t.getId());
        summary.put("fromStatus", t.getFromStatus());
        summary.put("toStatus", t.getToStatus());
        summary.put("flowStatus", t.getFlowStatus());
        summary.put("sourceType", t.getSourceType());
        auditService.recordAudit(principal.getUserId(), orgId, action.auditAction, AUDIT_OBJECT_TYPE, batch.getId(),
                nowUtc, "SUCCESS", serializeSummary(summary));

        return BatchRiskTransitionResponse.fromEntity(t);
    }

    /**
     * 风险状态转换核心（唯一写入口）。
     * <p>
     * 前提：调用方已在 READ COMMITTED 事务内持有该批次行锁（FOR UPDATE），并已完成权限、责任组织与幂等复读。
     * 本方法不再获取任何业务锁；插入台账行时只会在幂等唯一索引上等待（锁在批次行锁之后获取）。
     * 状态矩阵：ACTIVE / CLOSED 上 NORMAL ⇄ FROZEN；DRAFT 不参与；RECALLED 为终态；方向不符 409。
     * </p>
     */
    private Applied applyTransition(Batch locked, ManualAction action, BatchRiskSourceType sourceType, Long actorUserId,
                                    String reason, String idempotencyKey, String requestHash, LocalDateTime nowUtc) {
        String flowStatus = locked.getFlowStatus();
        String fromStatus = locked.getRiskStatus();
        if (!BatchFlowStatus.ACTIVE.name().equals(flowStatus) && !BatchFlowStatus.CLOSED.name().equals(flowStatus)) {
            throw invalidTransition("草稿批次尚未生效，不存在风险状态转换 (flowStatus=" + flowStatus + ")");
        }
        if (BatchRiskStatus.RECALLED.name().equals(fromStatus)) {
            throw invalidTransition("批次已进入模拟召回（RECALLED 为风险终态），不能" + action.label);
        }
        if (!action.expectedFrom.name().equals(fromStatus)) {
            throw invalidTransition(action == ManualAction.FREEZE
                    ? "批次已处于风险冻结状态 (riskStatus=" + fromStatus + ")"
                    : "批次当前未处于风险冻结状态，无需解除 (riskStatus=" + fromStatus + ")");
        }

        BatchRiskTransition t = new BatchRiskTransition();
        t.setBatchId(locked.getId());
        t.setOrgId(locked.getOrgId());
        t.setFlowStatus(flowStatus);
        t.setFromStatus(fromStatus);
        t.setToStatus(action.target.name());
        t.setSourceType(sourceType.name());
        t.setActorUserId(actorUserId);
        t.setReason(reason);
        t.setIdempotencyKey(idempotencyKey);
        t.setRequestHash(requestHash);
        t.setOccurredAt(nowUtc);
        t.setCreatedAt(nowUtc);
        try {
            transitionMapper.insert(t);
        } catch (DuplicateKeyException e) {
            // 同组织同键被另一批次上的并发请求抢先提交（同一批次的同键请求已被批次行锁串行化并在锁后复读识别）
            BatchRiskTransition dup = transitionMapper.selectByOrgIdAndIdempotencyKeyForUpdate(locked.getOrgId(), idempotencyKey);
            if (dup != null) {
                replayOrConflict(dup, requestHash);
                return new Applied(dup, true);
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            // InnoDB 三方同键插入且先插入者回滚时，两个等待者在唯一索引上互相等待，其中一方被选为死锁牺牲者，
            // 其整个事务（含批次行锁）已被数据库回滚：映射为可重试冲突而不是 500；使用同一幂等键重试即得到确定结果。
            throw new BusinessException(HttpStatus.CONFLICT, "BATCH_CONCURRENT_CONFLICT", "批次并发冲突",
                    "同一幂等键的并发请求发生冲突，本次" + action.label + "已回滚，请使用相同幂等键重试");
        }

        int updated = riskStateMapper.transitionRiskStatus(locked.getId(), locked.getOrgId(), fromStatus,
                action.target.name(), flowStatus, nowUtc, actorUserId);
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "BATCH_CONCURRENT_CONFLICT", "批次并发冲突",
                    "批次 " + locked.getTraceBatchNo() + " 状态已被并发修改，本次" + action.label + "已回滚");
        }
        return new Applied(t, false);
    }

    // =========================================================================
    // 校验与辅助
    // =========================================================================

    private BatchRiskTransitionResponse replayOrConflict(BatchRiskTransition existing, String requestHash) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                    "当前幂等键已被本组织用于不同语义的风险状态转换请求");
        }
        return BatchRiskTransitionResponse.fromEntity(existing);
    }

    /**
     * 写权限：未认证 401；平台 / 系统管理员不可代办 403 ADMIN_RESTRICTED；必须是 QUALITY_MANAGER（403 ACCESS_DENIED）。
     * 当前责任组织在批次行锁下另行校验（403 ORG_SCOPE_DENIED）。
     */
    private void checkWriteAccess(TraceSecurityPrincipal principal) {
        requireAuthenticated(principal);
        if (principal.getRoles().contains("SYSTEM_ADMIN") || isPlatformScope(principal)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_RESTRICTED", "管理员权限受限",
                    "平台管理角色不可代办企业风险冻结或解除冻结");
        }
        if (!principal.getRoles().contains(ROLE_QUALITY_MANAGER)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足",
                    "风险冻结与解除冻结仅限批次当前责任组织的质量管理员（QUALITY_MANAGER）执行");
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

    private static String validateReason(BatchRiskTransitionRequest req) {
        if (req == null) {
            throw badRequest("请求体不能为空");
        }
        if (req.unknownFields() != null && !req.unknownFields().isEmpty()) {
            throw badRequest("风险状态转换请求只接受 reason，不接受以下字段（由服务端决定或未在契约中声明）: "
                    + String.join(", ", req.unknownFields().keySet()));
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw badRequest("原因 reason 不能为空");
        }
        if (reason.length() > REASON_MAX_LENGTH) {
            throw badRequest("原因 reason 不能超过 " + REASON_MAX_LENGTH + " 个字符");
        }
        return reason;
    }

    /**
     * 规范化请求语义哈希：动作、批次与去除首尾空白后的原因；冻结与解除共用同一幂等键空间。
     */
    static String computeRequestHash(ManualAction action, Long batchId, String reason) {
        String canonical = String.join("\u001F", "BATCH_RISK_TRANSITION", "v1", action.name(), String.valueOf(batchId), reason);
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

    private static BusinessException invalidTransition(String detail) {
        return new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "风险状态转换不允许", detail);
    }

    private static BusinessException badRequest(String detail) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "参数校验失败", detail);
    }
}
