package com.example.traceability.quality.application;

import com.example.traceability.audit.application.AuditApplicationService;
import com.example.traceability.batch.application.BatchRiskService;
import com.example.traceability.batch.domain.BatchRiskStatus;
import com.example.traceability.batch.domain.PendingAlertHold;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.domain.Alert;
import com.example.traceability.quality.domain.AlertAction;
import com.example.traceability.quality.domain.AlertBatch;
import com.example.traceability.quality.domain.AlertStatus;
import com.example.traceability.quality.domain.InspectionReport;
import com.example.traceability.quality.dto.AlertAcknowledgeRequest;
import com.example.traceability.quality.dto.AlertDecisionRequest;
import com.example.traceability.quality.dto.AlertResponse;
import com.example.traceability.quality.mapper.AlertActionMapper;
import com.example.traceability.quality.mapper.AlertBatchMapper;
import com.example.traceability.quality.mapper.AlertMapper;
import com.example.traceability.quality.mapper.InspectionReportMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static com.example.traceability.quality.application.QualityWriteGuards.badRequest;
import static com.example.traceability.quality.application.QualityWriteGuards.checkQualityManager;
import static com.example.traceability.quality.application.QualityWriteGuards.isPlatformScope;
import static com.example.traceability.quality.application.QualityWriteGuards.optionalText;
import static com.example.traceability.quality.application.QualityWriteGuards.requireAuthenticated;
import static com.example.traceability.quality.application.QualityWriteGuards.requiredText;
import static com.example.traceability.quality.application.QualityWriteGuards.validateIdempotencyKey;

/**
 * 告警查询与人工处置应用服务（Phase B PB3 / PB4；统一业务契约 v1.1 §2.11 / §10.2 步骤 7 / §10.3 / §13 步骤 7–11 / §14）。
 * <p>
 * 查看：告警归属组织（运输任务发货方，任意角色）、运输任务接收方与承运方（任意角色，只读）与平台只读角色。
 * 处置（只有告警归属组织的质量管理员，平台 / 系统管理员不可代办）：
 * <ul>
 *   <li>确认异常 OPEN → ACKNOWLEDGED（确认人即处置负责人）；</li>
 *   <li>依据检验结论对受影响批次形成放行结论（PB4；独立评审修复）：告警已确认、批次仍冻结、关联本告警的<b>最新</b>检验报告为 PASS，
 *       且该批次最新的检验报告（不论关联哪个告警或未关联告警）不是不合格；同一告警对同一批次只形成一次放行结论。
 *       风险事项属于批次：只有解除了批次最后一个风险事项（其他未处置告警、尚未人工解除的人工风险冻结）的结论才经风险核心
 *       FROZEN → NORMAL（来源 ALERT、有操作人），否则只记录本告警的结论、批次保持 FROZEN；恢复正常后接收方才能接受隔离中的交接；</li>
 *   <li>形成处置结论 ACKNOWLEDGED → RESOLVED（PB4）：每个受影响批次都已由本告警形成放行结论，或已进入召回（RECALLED）。</li>
 * </ul>
 * 告警处置不改变交接、运输任务或批次数量，不生成 TraceEvent；检验报告不会自动放行。
 * </p>
 * <p>
 * 处置动作的锁顺序：动作幂等预读（非锁定）→ 告警行锁（FOR UPDATE，串行化同一告警的全部处置）→ 锁后幂等复读 →
 * （放行）批次行锁（风险核心，锁后读取最新检验结论）→ 追加动作行 → 条件更新告警。任何路径都不存在 batch → alert 的反向锁边。
 * 隔离级别 READ COMMITTED：持锁后的复读必须看到等待期间已提交的同键动作与检验报告。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class AlertApplicationService {

    static final String AUDIT_ACTION_ACKNOWLEDGE = "ALERT_ACKNOWLEDGE";
    static final String AUDIT_ACTION_RELEASE_BATCH = "ALERT_RELEASE_BATCH";
    static final String AUDIT_ACTION_RESOLVE = "ALERT_RESOLVE";
    static final String AUDIT_OBJECT_TYPE = "ALERT";
    static final String ACTION_ACKNOWLEDGE = "ACKNOWLEDGE";
    static final String ACTION_RELEASE_BATCH = "RELEASE_BATCH";
    static final String ACTION_RESOLVE = "RESOLVE";
    static final int NOTE_MAX_LENGTH = 500;

    private final AlertMapper alertMapper;
    private final AlertBatchMapper alertBatchMapper;
    private final AlertActionMapper alertActionMapper;
    private final InspectionReportMapper inspectionReportMapper;
    private final BatchRiskService batchRiskService;
    private final AuditApplicationService auditService;
    private final ObjectMapper objectMapper;

    public AlertApplicationService(
            AlertMapper alertMapper,
            AlertBatchMapper alertBatchMapper,
            AlertActionMapper alertActionMapper,
            InspectionReportMapper inspectionReportMapper,
            BatchRiskService batchRiskService,
            AuditApplicationService auditService,
            ObjectMapper objectMapper
    ) {
        this.alertMapper = Objects.requireNonNull(alertMapper, "alertMapper 不能为空");
        this.alertBatchMapper = Objects.requireNonNull(alertBatchMapper, "alertBatchMapper 不能为空");
        this.alertActionMapper = Objects.requireNonNull(alertActionMapper, "alertActionMapper 不能为空");
        this.inspectionReportMapper = Objects.requireNonNull(inspectionReportMapper, "inspectionReportMapper 不能为空");
        this.batchRiskService = Objects.requireNonNull(batchRiskService, "batchRiskService 不能为空");
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
     * 告警详情：受影响批次快照（含批次与交接当前事实、处置进展）与处置动作历史。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AlertResponse getAlert(Long alertId, TraceSecurityPrincipal principal) {
        requireAuthenticated(principal);
        Alert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + alertId + " 的告警");
        }
        requireVisible(alert, principal);
        return detail(alert, principal);
    }

    /**
     * 确认告警：OPEN → ACKNOWLEDGED（告警归属组织的质量管理员）。
     * <p>
     * 校验顺序：认证 / 平台代办 / QUALITY_MANAGER → 幂等键 → 说明 → 幂等预读 → 告警行锁 → 锁后幂等复读 → 告警存在 →
     * 归属组织 → 状态 → 追加动作 → 条件更新 → 审计。幂等重放先于一切可变状态校验。
     * </p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AlertResponse acknowledge(Long alertId, AlertAcknowledgeRequest req, String idempotencyKey,
                                     TraceSecurityPrincipal principal) {
        checkQualityManager(principal, "确认告警");
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        if (req == null) {
            throw badRequest("请求体不能为空");
        }
        rejectUnknown(req.unknownFields(), "确认告警请求只接受 note");
        String note = optionalText(req.note(), NOTE_MAX_LENGTH, "说明 note");
        String requestHash = hash(ACTION_ACKNOWLEDGE, alertId, null, note);

        Locked locked = lockAlert(alertId, principal, cleanKey, requestHash, "确认告警");
        if (locked.replay() != null) {
            return locked.replay();
        }
        Alert alert = locked.alert();
        if (!AlertStatus.OPEN.name().equals(alert.getStatus())) {
            throw invalidState("只有待确认（OPEN）的告警可以确认，当前状态为: " + alert.getStatus());
        }

        // 先追加动作行（此前本事务未修改任何数据，同键冲突时可安全重放或拒绝），再条件推进告警状态
        LocalDateTime nowUtc = now();
        AlertAction action = newAction(alert, ACTION_ACKNOWLEDGE, principal, note, cleanKey, requestHash, nowUtc);
        AlertResponse replay = insertAction(action, requestHash, principal);
        if (replay != null) {
            return replay;
        }
        if (alertMapper.acknowledge(alert.getId(), alert.getVersion(), principal.getUserId(), nowUtc) != 1) {
            throw versionConflict();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("alertActionId", action.getId());
        summary.put("fromStatus", AlertStatus.OPEN.name());
        summary.put("toStatus", AlertStatus.ACKNOWLEDGED.name());
        audit(principal, AUDIT_ACTION_ACKNOWLEDGE, alert.getId(), nowUtc, summary);
        return detail(alertMapper.selectById(alert.getId()), principal);
    }

    /**
     * 依据检验结论对受影响批次形成放行结论（PB4；独立评审修复）：告警 ACKNOWLEDGED、批次属于本告警且本告警尚未对其形成放行结论、
     * 批次当前责任组织为本组织且仍冻结、关联本告警的最新检验报告为 PASS，且该批次最新的检验报告不是不合格。
     * 追加 RELEASE_BATCH 动作；只有解除了批次最后一个风险事项时才经风险核心 FROZEN → NORMAL（来源 ALERT、操作人为本质量管理员）
     * 并在动作上引用该转换，否则批次保持 FROZEN、动作不引用转换。交接保持原状态：接收方随后依据批次恢复正常的事实接受隔离中的交接。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AlertResponse releaseBatch(Long alertId, Long batchId, AlertDecisionRequest req, String idempotencyKey,
                                      TraceSecurityPrincipal principal) {
        checkQualityManager(principal, "依据检验结论放行");
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        if (req == null) {
            throw badRequest("请求体不能为空");
        }
        rejectUnknown(req.unknownFields(), "放行请求只接受 note");
        if (req.resolution() != null) {
            throw badRequest("放行请求不接受 resolution（处置结论请使用处置接口）");
        }
        String note = optionalText(req.note(), NOTE_MAX_LENGTH, "说明 note");
        String requestHash = hash(ACTION_RELEASE_BATCH, alertId, batchId, note);

        Locked locked = lockAlert(alertId, principal, cleanKey, requestHash, "放行批次");
        if (locked.replay() != null) {
            return locked.replay();
        }
        Alert alert = locked.alert();
        if (AlertStatus.OPEN.name().equals(alert.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ALERT_NOT_ACKNOWLEDGED", "告警尚未确认",
                    "请先确认异常，再依据检验结论处置受影响批次");
        }
        if (!AlertStatus.ACKNOWLEDGED.name().equals(alert.getStatus())) {
            throw invalidState("告警已形成处置结论，不能再放行批次");
        }
        if (alertBatchMapper.countByAlertIdAndBatchId(alertId, batchId) == 0) {
            throw new ResourceNotFoundException("批次 " + batchId + " 不是该告警的受影响批次");
        }
        if (alertActionMapper.countReleaseByAlertIdAndBatchId(alertId, batchId) > 0) {
            throw invalidState("该告警已对此批次形成放行结论");
        }

        LocalDateTime nowUtc = now();
        AtomicReference<InspectionReport> basis = new AtomicReference<>();
        String reason = note != null ? note : "依据告警 " + alert.getAlertNo() + " 关联的检验结论放行";
        BatchRiskService.AlertReleaseOutcome outcome = batchRiskService.releaseForAlert(alertId, batchId, principal.getUserId(),
                principal.getOrgId(), reason, batch -> {
                    // 批次行锁之后读取最新结论：检验报告提交同样先锁批次行，结论与放行不会交错
                    InspectionReport latest = inspectionReportMapper.selectLatestByAlertIdAndBatchId(alertId, batchId);
                    if (latest == null) {
                        throw new BusinessException(HttpStatus.CONFLICT, "INSPECTION_REQUIRED", "缺少检验结论",
                                "放行前必须有关联本告警的该批次检验报告");
                    }
                    if (!"PASS".equals(latest.getConclusion())) {
                        throw new BusinessException(HttpStatus.CONFLICT, "INSPECTION_NOT_PASSED", "检验结论不合格",
                                "关联本告警的该批次最新检验报告（" + latest.getReportNo() + "）结论为不合格，不能放行");
                    }
                    // 该批次最新的检验证据（不论关联哪个告警或未关联告警）为不合格时，不能只凭本告警较早的合格结论放行
                    InspectionReport newest = inspectionReportMapper.selectLatestByBatchId(batchId);
                    if (newest != null && !"PASS".equals(newest.getConclusion())) {
                        throw new BusinessException(HttpStatus.CONFLICT, "INSPECTION_NOT_PASSED", "检验结论不合格",
                                "该批次最新检验报告（" + newest.getReportNo() + "）结论为不合格，不能依据较早的合格结论放行");
                    }
                    if (!BatchRiskStatus.FROZEN.name().equals(batch.getRiskStatus())) {
                        throw invalidState("批次当前不处于风险冻结状态 (riskStatus=" + batch.getRiskStatus() + ")，无需放行");
                    }
                    basis.set(latest);
                }, nowUtc);

        Long transitionId = outcome.transition() == null ? null : outcome.transition().getId();
        AlertAction action = newAction(alert, ACTION_RELEASE_BATCH, principal, note, cleanKey, requestHash, nowUtc);
        action.setBatchId(batchId);
        action.setRiskTransitionId(transitionId);
        action.setInspectionReportId(basis.get().getId());
        try {
            alertActionMapper.insert(action);
        } catch (DuplicateKeyException e) {
            // 本事务可能已写入放行转换，不能重放：同键请求已被告警行锁串行化，这里只可能是另一告警上的同键请求
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                    "当前幂等键已被本组织用于其他告警处置请求，本次放行已回滚");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("alertActionId", action.getId());
        summary.put("batchId", batchId);
        summary.put("riskTransitionId", transitionId);
        summary.put("inspectionReportId", basis.get().getId());
        summary.put("batchRiskStatus", transitionId == null ? outcome.batch().getRiskStatus() : outcome.transition().getToStatus());
        summary.put("remainingAlertIds", outcome.remainingAlertHolds().stream().map(PendingAlertHold::getAlertId).toList());
        summary.put("manualFreezeHold", outcome.manualFreezeHold());
        audit(principal, AUDIT_ACTION_RELEASE_BATCH, alert.getId(), nowUtc, summary);
        return detail(alertMapper.selectById(alert.getId()), principal);
    }

    /**
     * 形成处置结论（PB4）：ACKNOWLEDGED → RESOLVED；每个受影响批次都必须已由本告警形成放行结论（不论当时是否恢复正常），
     * 或已进入模拟召回（RECALLED）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AlertResponse resolve(Long alertId, AlertDecisionRequest req, String idempotencyKey, TraceSecurityPrincipal principal) {
        checkQualityManager(principal, "形成处置结论");
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        if (req == null) {
            throw badRequest("请求体不能为空");
        }
        rejectUnknown(req.unknownFields(), "处置结论请求只接受 resolution");
        if (req.note() != null) {
            throw badRequest("处置结论请求不接受 note，请填写 resolution");
        }
        String resolution = requiredText(req.resolution(), NOTE_MAX_LENGTH, "处置结论 resolution");
        String requestHash = hash(ACTION_RESOLVE, alertId, null, resolution);

        Locked locked = lockAlert(alertId, principal, cleanKey, requestHash, "形成处置结论");
        if (locked.replay() != null) {
            return locked.replay();
        }
        Alert alert = locked.alert();
        if (!AlertStatus.ACKNOWLEDGED.name().equals(alert.getStatus())) {
            throw invalidState("只有处置中（ACKNOWLEDGED）的告警可以形成处置结论，当前状态为: " + alert.getStatus());
        }
        List<AlertBatch> batches = alertBatchMapper.selectByAlertId(alertId);
        long pending = batches.stream()
                .filter(b -> b.getReleaseActionId() == null && !BatchRiskStatus.RECALLED.name().equals(b.getCurrentRiskStatus()))
                .count();
        if (pending > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "ALERT_BATCHES_PENDING", "仍有批次未处置",
                    "仍有 " + pending + " 个受影响批次尚未由本告警依据检验结论形成放行结论或进入召回，不能形成处置结论");
        }

        LocalDateTime nowUtc = now();
        AlertAction action = newAction(alert, ACTION_RESOLVE, principal, null, cleanKey, requestHash, nowUtc);
        AlertResponse replay = insertAction(action, requestHash, principal);
        if (replay != null) {
            return replay;
        }
        if (alertMapper.resolve(alert.getId(), alert.getVersion(), principal.getUserId(), resolution, nowUtc) != 1) {
            throw versionConflict();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("alertActionId", action.getId());
        summary.put("fromStatus", AlertStatus.ACKNOWLEDGED.name());
        summary.put("toStatus", AlertStatus.RESOLVED.name());
        summary.put("releasedBatchCount", batches.stream().filter(b -> b.getReleaseActionId() != null).count());
        summary.put("recalledBatchCount", batches.stream().filter(b -> BatchRiskStatus.RECALLED.name().equals(b.getCurrentRiskStatus())).count());
        audit(principal, AUDIT_ACTION_RESOLVE, alert.getId(), nowUtc, summary);
        return detail(alertMapper.selectById(alert.getId()), principal);
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    /** 告警行锁结果：{@code replay} 非空表示命中同组织同键同语义的已提交动作，直接返回。 */
    private record Locked(Alert alert, AlertResponse replay) {
    }

    /**
     * 幂等预读 → 告警行锁 → 锁后幂等复读 → 告警存在 → 归属组织。
     */
    private Locked lockAlert(Long alertId, TraceSecurityPrincipal principal, String cleanKey, String requestHash, String label) {
        Long orgId = principal.getOrgId();
        AlertAction existing = alertActionMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (existing != null) {
            return new Locked(null, replayOrConflict(existing, requestHash, principal));
        }
        Alert alert = alertMapper.selectByIdForUpdate(alertId);
        AlertAction afterLock = alertActionMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (afterLock != null) {
            return new Locked(null, replayOrConflict(afterLock, requestHash, principal));
        }
        if (alert == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + alertId + " 的告警");
        }
        if (!Objects.equals(alert.getOrgId(), orgId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                    "只有告警归属组织（受影响批次的当前责任组织）的质量管理员可以" + label);
        }
        return new Locked(alert, null);
    }

    /**
     * 告警详情。仍处于 FROZEN 的受影响批次附带除本告警外仍未解除的风险事项，只对该批次的当前责任组织与平台只读角色输出
     * （其他可见方——例如接收方、承运方，或批次已转出后的原归属组织——看不到当前责任组织的风险事项）。
     */
    private AlertResponse detail(Alert alert, TraceSecurityPrincipal viewer) {
        List<AlertBatch> batches = alertBatchMapper.selectByAlertId(alert.getId());
        Map<Long, List<AlertResponse.PendingHold>> holds = new LinkedHashMap<>();
        boolean platform = isPlatformScope(viewer);
        for (AlertBatch b : batches) {
            if (!BatchRiskStatus.FROZEN.name().equals(b.getCurrentRiskStatus())
                    || !(platform || Objects.equals(b.getCurrentOrgId(), viewer.getOrgId()))) {
                continue;
            }
            BatchRiskService.RiskHolds open = batchRiskService.openHolds(b.getBatchId(), b.getCurrentRiskStatus());
            List<AlertResponse.PendingHold> others = new ArrayList<>();
            open.alertHolds().stream().filter(h -> !Objects.equals(h.getAlertId(), alert.getId()))
                    .forEach(h -> others.add(new AlertResponse.PendingHold("ALERT", h.getAlertId(), h.getAlertNo())));
            if (open.manualFreezeHold()) {
                others.add(new AlertResponse.PendingHold("MANUAL_FREEZE", null, null));
            }
            holds.put(b.getBatchId(), others);
        }
        return AlertResponse.detail(alert, batches, alertActionMapper.selectByAlertId(alert.getId()), holds);
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
    private AlertResponse insertAction(AlertAction action, String requestHash, TraceSecurityPrincipal principal) {
        try {
            alertActionMapper.insert(action);
            return null;
        } catch (DuplicateKeyException e) {
            AlertAction dup = alertActionMapper.selectByOrgIdAndIdempotencyKeyForUpdate(action.getOrgId(), action.getIdempotencyKey());
            if (dup != null) {
                return replayOrConflict(dup, requestHash, principal);
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "ALERT_CONCURRENT_CONFLICT", "告警并发冲突",
                    "同一幂等键的并发请求发生冲突，本次告警处置已回滚，请使用相同幂等键重试");
        }
    }

    private AlertResponse replayOrConflict(AlertAction existing, String requestHash, TraceSecurityPrincipal principal) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                    "当前幂等键已被本组织用于不同语义的告警处置请求");
        }
        return detail(alertMapper.selectById(existing.getAlertId()), principal);
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

    private static void rejectUnknown(Map<String, Object> unknownFields, String accepted) {
        if (unknownFields != null && !unknownFields.isEmpty()) {
            throw badRequest(accepted + "，不接受以下字段（由服务端决定或未在契约中声明）: " + String.join(", ", unknownFields.keySet()));
        }
    }

    /**
     * 规范化请求语义哈希：动作、告警、批次（放行）与去除首尾空白后的说明 / 结论（空说明与缺省等价）。
     */
    static String hash(String action, Long alertId, Long batchId, String text) {
        return QualityWriteGuards.hash("ALERT_ACTION", "v1", action, String.valueOf(alertId),
                batchId == null ? null : String.valueOf(batchId), text);
    }

    private void audit(TraceSecurityPrincipal principal, String action, Long alertId, LocalDateTime nowUtc, Map<String, Object> summary) {
        try {
            auditService.recordAudit(principal.getUserId(), principal.getOrgId(), action, AUDIT_OBJECT_TYPE, alertId, nowUtc,
                    "SUCCESS", objectMapper.writeValueAsString(summary));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR", "审计日志序列化失败", e.getMessage());
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static BusinessException invalidState(String detail) {
        return new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "告警状态不允许", detail);
    }

    private static BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "资源版本冲突", "告警已被并发修改，请刷新后重试");
    }
}
