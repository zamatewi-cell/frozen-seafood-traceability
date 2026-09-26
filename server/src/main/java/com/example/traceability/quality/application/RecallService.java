package com.example.traceability.quality.application;

import com.example.traceability.audit.application.AuditApplicationService;
import com.example.traceability.batch.application.BatchQuantityService;
import com.example.traceability.batch.application.BatchRiskService;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchFlowStatus;
import com.example.traceability.batch.domain.BatchLineageEdge;
import com.example.traceability.batch.domain.BatchRiskStatus;
import com.example.traceability.batch.domain.BatchRiskTransition;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.batch.mapper.BatchRelationMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.domain.Alert;
import com.example.traceability.quality.domain.InspectionReport;
import com.example.traceability.quality.domain.Recall;
import com.example.traceability.quality.domain.RecallBatch;
import com.example.traceability.quality.domain.RecallScopeFact;
import com.example.traceability.quality.dto.RecallCloseRequest;
import com.example.traceability.quality.dto.RecallCreateRequest;
import com.example.traceability.quality.dto.RecallResponse;
import com.example.traceability.quality.mapper.AlertBatchMapper;
import com.example.traceability.quality.mapper.AlertMapper;
import com.example.traceability.quality.mapper.InspectionReportMapper;
import com.example.traceability.quality.mapper.RecallBatchMapper;
import com.example.traceability.quality.mapper.RecallMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.traceability.quality.application.QualityWriteGuards.badRequest;
import static com.example.traceability.quality.application.QualityWriteGuards.checkQualityManager;
import static com.example.traceability.quality.application.QualityWriteGuards.isPlatformScope;
import static com.example.traceability.quality.application.QualityWriteGuards.requireAuthenticated;
import static com.example.traceability.quality.application.QualityWriteGuards.requiredText;
import static com.example.traceability.quality.application.QualityWriteGuards.validateIdempotencyKey;

/**
 * 模拟召回应用服务（Phase B PB5；统一业务契约 v1.1 §2.12 / §4.2 / §4.3 / §5 / §13 步骤 8–14 / §14）。
 * <p>
 * 发起：被召回批次当前责任组织的质量管理员选择本组织持有的种子批次（FROZEN，或证据充分的 NORMAL：最新检验报告不合格，
 * 或该批次已被其他召回圈定为正向后续批次），系统在同一事务内：
 * <ul>
 *   <li>反向追溯：沿谱系向上圈定祖先批次（只用于溯源调查，不转换）；</li>
 *   <li>正向圈定：沿已提交批次操作的谱系向下圈定后续批次——本组织持有的同时转为 RECALLED，其他组织持有的通知持有方
 *       （由持有方的质量管理员以本召回为证据发起自己的召回，契约 §14：只有当前责任组织执行 Recall）；</li>
 *   <li>对每个种子 / 后续批次快照剩余（库存与在途）/ 已售数量、未结束交接与运输状态、公开追溯码是否启用；</li>
 *   <li>经风险核心把应召回的批次 NORMAL / FROZEN → RECALLED（ACTIVE 或 CLOSED 均可；已售罄批次保持 CLOSED 同时记录 RECALLED）。</li>
 * </ul>
 * 关闭：发起组织的质量管理员给出受控公开处置结论与内部总结；批次仍保留 RECALLED 历史终态。召回不改变数量、责任组织、流转状态、
 * 交接或公开追溯码，不生成 TraceEvent。本系统的召回是教学演练，不代表真实法定召回。
 * </p>
 * <p>
 * 锁顺序与并发：幂等预读（非锁定）→ 引用来源告警时先锁定告警行（alert → batch，与告警放行 / 处置结论同序）→ 计算范围（种子、
 * 正向后续批次、反向祖先批次）→ 按批次 ID 升序一次锁定全部范围批次：待转换批次（种子 + 本组织持有的后续批次）FOR UPDATE，
 * 只快照的批次（其他组织持有的后续批次与祖先批次）FOR SHARE → 锁后幂等复读 → 重新计算正向范围并与加锁集合比对（并发批次操作 /
 * 交接改变了范围或持有组织时 409 {@code RECALL_SCOPE_CHANGED}，可重试）→ 证据与状态校验 → 插入召回 → 经风险核心转换 → 插入范围快照。
 * 与销售 / 交接接受 / 批次操作 / 告警放行都在批次行锁上串行化；多个批次一律按 ID 升序加锁（与告警自动冻结及其他召回同序）。
 * 加锁阶段之后，召回行、风险转换与范围快照的外键检查只触及本事务已锁定的告警 / 批次或本事务新插入的行，不再等待任何其他锁；
 * 不锁运输任务或交接，范围快照中的未结束交接只是快照引用（V16 刻意不建外键），因此不存在 batch → shipment / transfer / alert
 * 的反向锁边。隔离级别 READ COMMITTED。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class RecallService {

    static final String AUDIT_OBJECT_TYPE = "RECALL";
    static final String AUDIT_ACTION_START = "RECALL_START";
    static final String AUDIT_ACTION_CLOSE = "RECALL_CLOSE";
    static final int MAX_SEEDS = 20;
    static final int REASON_MAX = 500;
    static final int SUMMARY_MAX = 1000;
    static final Set<String> PUBLIC_DISPOSITIONS = Set.of("DESTROYED", "RETURNED");
    private static final DateTimeFormatter RECALL_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 规范化后的发起请求。 */
    record CanonicalStart(List<Long> seedIds, String reason, Long alertId) {
    }

    private final RecallMapper recallMapper;
    private final RecallBatchMapper recallBatchMapper;
    private final AlertMapper alertMapper;
    private final AlertBatchMapper alertBatchMapper;
    private final InspectionReportMapper inspectionReportMapper;
    private final BatchMapper batchMapper;
    private final BatchRelationMapper batchRelationMapper;
    private final BatchQuantityService batchQuantityService;
    private final BatchRiskService batchRiskService;
    private final AuditApplicationService auditService;
    private final ObjectMapper objectMapper;

    public RecallService(
            RecallMapper recallMapper,
            RecallBatchMapper recallBatchMapper,
            AlertMapper alertMapper,
            AlertBatchMapper alertBatchMapper,
            InspectionReportMapper inspectionReportMapper,
            BatchMapper batchMapper,
            BatchRelationMapper batchRelationMapper,
            BatchQuantityService batchQuantityService,
            BatchRiskService batchRiskService,
            AuditApplicationService auditService,
            ObjectMapper objectMapper
    ) {
        this.recallMapper = Objects.requireNonNull(recallMapper, "recallMapper 不能为空");
        this.recallBatchMapper = Objects.requireNonNull(recallBatchMapper, "recallBatchMapper 不能为空");
        this.alertMapper = Objects.requireNonNull(alertMapper, "alertMapper 不能为空");
        this.alertBatchMapper = Objects.requireNonNull(alertBatchMapper, "alertBatchMapper 不能为空");
        this.inspectionReportMapper = Objects.requireNonNull(inspectionReportMapper, "inspectionReportMapper 不能为空");
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper 不能为空");
        this.batchRelationMapper = Objects.requireNonNull(batchRelationMapper, "batchRelationMapper 不能为空");
        this.batchQuantityService = Objects.requireNonNull(batchQuantityService, "batchQuantityService 不能为空");
        this.batchRiskService = Objects.requireNonNull(batchRiskService, "batchRiskService 不能为空");
        this.auditService = Objects.requireNonNull(auditService, "auditService 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    // =========================================================================
    // 发起
    // =========================================================================

    /**
     * 发起模拟召回 (POST /api/v1/recalls)。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RecallResponse start(RecallCreateRequest req, String idempotencyKey, TraceSecurityPrincipal principal) {
        checkQualityManager(principal, "发起模拟召回");
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        CanonicalStart c = canonicalize(req);
        Long orgId = principal.getOrgId();
        String requestHash = QualityWriteGuards.hash("RECALL", "v1",
                c.seedIds().stream().map(String::valueOf).collect(Collectors.joining(",")), c.reason(),
                c.alertId() == null ? null : String.valueOf(c.alertId()));

        Recall existing = recallMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (existing != null) {
            return replayStart(existing, requestHash, principal);
        }
        if (c.alertId() != null) {
            requireAlertScope(alertMapper.selectByIdForUpdate(c.alertId()), c.alertId(), c.seedIds(), orgId);
        }

        // 1. 范围（非锁定预读）：待转换批次（种子 + 本组织持有的正向后续批次）加排他锁；只快照的批次（其他组织持有的后续批次、
        //    反向祖先批次，祖先随种子固定不变）加共享锁。全部按批次 ID 升序一次加锁，之后插入范围快照时的外键检查不再等待批次行锁。
        Map<Long, Integer> descendantsBefore = descendantDepths(c.seedIds());
        Map<Long, Integer> ancestors = ancestorDepths(c.seedIds(), descendantsBefore.keySet());
        Set<Long> toLock = new TreeSet<>(c.seedIds());
        if (!descendantsBefore.isEmpty()) {
            for (Batch b : batchMapper.selectByIdsIgnoreTenant(descendantsBefore.keySet())) {
                if (Objects.equals(b.getOrgId(), orgId)) {
                    toLock.add(b.getId());
                }
            }
        }
        Set<Long> scopeIds = new TreeSet<>(toLock);
        scopeIds.addAll(descendantsBefore.keySet());
        scopeIds.addAll(ancestors.keySet());
        Map<Long, Batch> batchesById = new HashMap<>();
        for (Long id : scopeIds) {
            Batch b = toLock.contains(id) ? batchMapper.selectByIdIgnoreTenantForUpdate(id) : batchMapper.selectByIdIgnoreTenantForShare(id);
            if (b == null && c.seedIds().contains(id)) {
                throw new ResourceNotFoundException("未找到 ID 为 " + id + " 的批次");
            }
            if (b != null) {
                batchesById.put(id, b);
            }
        }

        // 2. 锁后复读：幂等键与正向范围（范围或持有组织在加锁期间变化时要求重试，绝不遗漏未加锁的应召回批次）
        Recall afterLock = recallMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (afterLock != null) {
            return replayStart(afterLock, requestHash, principal);
        }
        Map<Long, Integer> descendants = descendantDepths(c.seedIds());
        Set<Long> expectedLocks = new TreeSet<>(c.seedIds());
        descendants.keySet().stream().map(batchesById::get).filter(Objects::nonNull)
                .filter(b -> Objects.equals(b.getOrgId(), orgId)).forEach(b -> expectedLocks.add(b.getId()));
        if (!descendants.keySet().equals(descendantsBefore.keySet()) || !expectedLocks.equals(toLock)
                || !batchesById.keySet().containsAll(descendants.keySet())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECALL_SCOPE_CHANGED", "召回范围已变化",
                    "召回影响范围在发起期间被并发的批次操作或交接改变，本次发起已回滚，请刷新后重试");
        }

        // 3. 种子校验：本组织持有、已生效、未召回；NORMAL 需要证据
        for (Long seedId : c.seedIds()) {
            Batch seed = batchesById.get(seedId);
            if (!Objects.equals(seed.getOrgId(), orgId)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                        "只有批次当前责任组织的质量管理员可以对批次 " + seed.getTraceBatchNo() + " 发起模拟召回");
            }
            if (!BatchFlowStatus.ACTIVE.name().equals(seed.getFlowStatus()) && !BatchFlowStatus.CLOSED.name().equals(seed.getFlowStatus())) {
                throw invalidState("草稿批次 " + seed.getTraceBatchNo() + " 尚未生效，不能召回");
            }
            if (BatchRiskStatus.RECALLED.name().equals(seed.getRiskStatus())) {
                throw invalidState("批次 " + seed.getTraceBatchNo() + " 已进入模拟召回（RECALLED 为风险终态）");
            }
            if (BatchRiskStatus.NORMAL.name().equals(seed.getRiskStatus()) && !hasEmergencyEvidence(seedId)) {
                throw new BusinessException(HttpStatus.CONFLICT, "RECALL_EVIDENCE_REQUIRED", "紧急召回证据不足",
                        "批次 " + seed.getTraceBatchNo() + " 风险状态正常：需要最新检验报告不合格，或该批次已被上游召回圈定，"
                                + "否则请先风险冻结并调查");
            }
        }

        // 4. 快照事实（转换前）：剩余 / 已售数量、未结束交接与运输状态、公开追溯码（反向祖先只用于溯源调查）
        List<Batch> scopeBatches = new ArrayList<>();
        c.seedIds().forEach(id -> scopeBatches.add(batchesById.get(id)));
        descendants.keySet().stream().sorted().forEach(id -> scopeBatches.add(batchesById.get(id)));
        ancestors.keySet().stream().sorted().map(batchesById::get).filter(Objects::nonNull).forEach(scopeBatches::add);
        Map<Long, BigDecimal> remaining = batchQuantityService.remainingOf(scopeBatches);
        Map<Long, RecallScopeFact> facts = recallBatchMapper.selectScopeFacts(
                        scopeBatches.stream().map(Batch::getId).toList()).stream()
                .collect(Collectors.toMap(RecallScopeFact::getBatchId, Function.identity()));

        // 5. 召回案件 → 风险核心转换 → 范围快照
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        Recall recall = new Recall();
        recall.setRecallNo("RCL-" + nowUtc.format(RECALL_NO_FORMAT) + "-" + (RANDOM.nextInt(900000) + 100000));
        recall.setOwnerOrgId(orgId);
        recall.setSourceAlertId(c.alertId());
        recall.setReason(c.reason());
        recall.setStartedAt(nowUtc);
        recall.setStartedBy(principal.getUserId());
        recall.setIdempotencyKey(cleanKey);
        recall.setRequestHash(requestHash);
        try {
            recallMapper.insert(recall);
        } catch (DuplicateKeyException e) {
            Recall dup = recallMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
            if (dup != null) {
                return replayStart(dup, requestHash, principal);
            }
            throw e;
        } catch (PessimisticLockingFailureException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECALL_CONCURRENT_CONFLICT", "召回并发冲突",
                    "同一幂等键的并发请求发生冲突，本次发起已回滚，请使用相同幂等键重试");
        }

        String transitionReason = "模拟召回 " + recall.getRecallNo() + "：" + c.reason();
        if (transitionReason.length() > REASON_MAX) {
            transitionReason = transitionReason.substring(0, REASON_MAX);
        }
        List<RecallBatch> rows = new ArrayList<>();
        int recalled = 0;
        for (Long seedId : c.seedIds()) {
            Batch seed = batchesById.get(seedId);
            BatchRiskTransition t = batchRiskService.recallForCase(recall.getId(), seedId, principal.getUserId(), orgId,
                    transitionReason, nowUtc);
            rows.add(row(recall, seed, "SEED", 0, "RECALLED", t.getId(), remaining, facts, nowUtc));
            recalled++;
        }
        for (Long id : descendants.keySet().stream().sorted().toList()) {
            Batch b = batchesById.get(id);
            String action;
            Long transitionId = null;
            if (BatchRiskStatus.RECALLED.name().equals(b.getRiskStatus())) {
                action = "ALREADY_RECALLED";
            } else if (!Objects.equals(b.getOrgId(), orgId)) {
                action = "NOTIFY_HOLDER";
            } else if (BatchFlowStatus.DRAFT.name().equals(b.getFlowStatus())) {
                action = "TRACE_ONLY";
            } else {
                transitionId = batchRiskService.recallForCase(recall.getId(), id, principal.getUserId(), orgId,
                        transitionReason, nowUtc).getId();
                action = "RECALLED";
                recalled++;
            }
            rows.add(row(recall, b, "DESCENDANT", descendants.get(id), action, transitionId, remaining, facts, nowUtc));
        }
        for (Long id : ancestors.keySet().stream().sorted().toList()) {
            Batch b = batchesById.get(id);
            if (b != null) {
                rows.add(row(recall, b, "ANCESTOR", -ancestors.get(id), "TRACE_ONLY", null, remaining, facts, nowUtc));
            }
        }
        rows.forEach(recallBatchMapper::insert);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recallNo", recall.getRecallNo());
        summary.put("seedBatchIds", c.seedIds());
        summary.put("recalledBatchCount", recalled);
        summary.put("descendantCount", descendants.size());
        summary.put("ancestorCount", ancestors.size());
        summary.put("sourceAlertId", c.alertId());
        audit(principal, AUDIT_ACTION_START, recall.getId(), nowUtc, summary);
        return detail(recallMapper.selectById(recall.getId()), principal);
    }

    // =========================================================================
    // 关闭
    // =========================================================================

    /**
     * 关闭模拟召回 (POST /api/v1/recalls/{recallId}/close)：IN_PROGRESS → CLOSED，批次仍保留 RECALLED。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RecallResponse close(Long recallId, RecallCloseRequest req, String idempotencyKey, TraceSecurityPrincipal principal) {
        checkQualityManager(principal, "关闭模拟召回");
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        if (req == null) {
            throw badRequest("请求体不能为空");
        }
        if (req.unknownFields() != null && !req.unknownFields().isEmpty()) {
            throw badRequest("关闭请求只接受 publicDisposition、resultSummary，不接受以下字段: " + String.join(", ", req.unknownFields().keySet()));
        }
        String disposition = req.publicDisposition() == null ? "" : req.publicDisposition().trim();
        if (!PUBLIC_DISPOSITIONS.contains(disposition)) {
            throw badRequest("公开处置结论 publicDisposition 只能为 DESTROYED 或 RETURNED");
        }
        String summaryText = requiredText(req.resultSummary(), SUMMARY_MAX, "处置总结 resultSummary");
        Long orgId = principal.getOrgId();
        String requestHash = QualityWriteGuards.hash("RECALL_CLOSE", "v1", String.valueOf(recallId), disposition, summaryText);

        Recall existing = recallMapper.selectByOrgIdAndCloseIdempotencyKey(orgId, cleanKey);
        if (existing != null) {
            return replayClose(existing, recallId, requestHash, principal);
        }
        Recall recall = recallMapper.selectByIdForUpdate(recallId);
        Recall afterLock = recallMapper.selectByOrgIdAndCloseIdempotencyKey(orgId, cleanKey);
        if (afterLock != null) {
            return replayClose(afterLock, recallId, requestHash, principal);
        }
        if (recall == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + recallId + " 的召回");
        }
        if (!Objects.equals(recall.getOwnerOrgId(), orgId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                    "只有召回发起组织的质量管理员可以关闭召回");
        }
        if (!"IN_PROGRESS".equals(recall.getStatus())) {
            throw invalidState("召回已关闭");
        }
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        try {
            if (recallMapper.close(recallId, recall.getVersion(), principal.getUserId(), disposition, summaryText, cleanKey, requestHash,
                    nowUtc) != 1) {
                throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "资源版本冲突", "召回已被并发修改，请刷新后重试");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                    "当前幂等键已被本组织用于关闭其他召回");
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("publicDisposition", disposition);
        audit(principal, AUDIT_ACTION_CLOSE, recallId, nowUtc, summary);
        return detail(recallMapper.selectById(recallId), principal);
    }

    // =========================================================================
    // 查询
    // =========================================================================

    /**
     * 可见召回列表（按主键倒序）：发起组织、范围批次持有组织与平台只读角色。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RecallResponse> listRecalls(TraceSecurityPrincipal principal) {
        requireAuthenticated(principal);
        boolean platform = isPlatformScope(principal);
        return recallMapper.selectVisible(principal.getOrgId(), platform).stream()
                .map(r -> RecallResponse.summaryOf(r, platform || Objects.equals(r.getOwnerOrgId(), principal.getOrgId())))
                .toList();
    }

    /**
     * 召回详情：发起组织与平台只读角色看完整范围；范围批次持有组织只看案件概要与本组织持有的范围行；其他组织 403。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecallResponse getRecall(Long recallId, TraceSecurityPrincipal principal) {
        requireAuthenticated(principal);
        Recall recall = recallMapper.selectById(recallId);
        if (recall == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + recallId + " 的召回");
        }
        return detail(recall, principal);
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    private RecallResponse detail(Recall recall, TraceSecurityPrincipal principal) {
        boolean full = isPlatformScope(principal) || Objects.equals(recall.getOwnerOrgId(), principal.getOrgId());
        List<RecallBatch> rows = recallBatchMapper.selectByRecallId(recall.getId());
        if (!full) {
            rows = rows.stream().filter(r -> Objects.equals(r.getHolderOrgId(), principal.getOrgId())).toList();
            if (rows.isEmpty()) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                        "仅召回发起组织与影响范围批次的持有组织可以查看该召回");
            }
        }
        return RecallResponse.detailOf(recall, full, rows);
    }

    private RecallResponse replayStart(Recall existing, String requestHash, TraceSecurityPrincipal principal) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                    "当前幂等键已被本组织用于不同语义的召回发起请求");
        }
        return detail(existing, principal);
    }

    private RecallResponse replayClose(Recall existing, Long recallId, String requestHash, TraceSecurityPrincipal principal) {
        if (!Objects.equals(existing.getId(), recallId) || !Objects.equals(existing.getCloseRequestHash(), requestHash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                    "当前幂等键已被本组织用于不同语义的召回关闭请求");
        }
        return detail(existing, principal);
    }

    private void requireAlertScope(Alert alert, Long alertId, List<Long> seedIds, Long orgId) {
        if (alert == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + alertId + " 的告警");
        }
        if (!Objects.equals(alert.getOrgId(), orgId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", "只能引用本组织归属的告警");
        }
        for (Long seedId : seedIds) {
            if (alertBatchMapper.countByAlertIdAndBatchId(alertId, seedId) == 0) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ALERT_BATCH_MISMATCH", "告警与批次不匹配",
                        "批次 " + seedId + " 不是来源告警的受影响批次");
            }
        }
    }

    /**
     * NORMAL 批次紧急召回的证据（契约 §4.2：NORMAL → RECALLED 仅用于证据充分的紧急召回）：该批次最新检验报告不合格，
     * 或该批次已被其他召回圈定为正向后续批次。只在持有批次行锁后调用。
     */
    private boolean hasEmergencyEvidence(Long batchId) {
        InspectionReport latest = inspectionReportMapper.selectLatestByBatchId(batchId);
        if (latest != null && "FAIL".equals(latest.getConclusion())) {
            return true;
        }
        return recallBatchMapper.countDescendantScopeByBatchId(batchId) > 0;
    }

    /** 正向影响范围：从种子沿已提交批次操作的谱系向下可达的批次及其最短距离（不含种子）。 */
    private Map<Long, Integer> descendantDepths(List<Long> seedIds) {
        Map<Long, List<Long>> children = new HashMap<>();
        for (BatchLineageEdge e : batchRelationMapper.selectDescendantEdges(seedIds)) {
            if ("SUBMITTED".equals(e.getOperationStatus()) && Integer.valueOf(0).equals(e.getOperationDeleted())) {
                children.computeIfAbsent(e.getParentBatchId(), k -> new ArrayList<>()).add(e.getChildBatchId());
            }
        }
        return bfs(seedIds, children);
    }

    /** 反向追溯：种子的全部祖先及其最短距离（不含种子与正向后续批次）。 */
    private Map<Long, Integer> ancestorDepths(List<Long> seedIds, Set<Long> descendants) {
        Map<Long, List<Long>> parents = new HashMap<>();
        for (Long seedId : seedIds) {
            for (BatchLineageEdge e : batchRelationMapper.selectAncestorEdges(seedId)) {
                parents.computeIfAbsent(e.getChildBatchId(), k -> new ArrayList<>()).add(e.getParentBatchId());
            }
        }
        Map<Long, Integer> result = bfs(seedIds, parents);
        result.keySet().removeAll(descendants);
        return result;
    }

    private static Map<Long, Integer> bfs(List<Long> starts, Map<Long, List<Long>> next) {
        Map<Long, Integer> depth = new TreeMap<>();
        Set<Long> seen = new HashSet<>(starts);
        Deque<Long> queue = new ArrayDeque<>(starts);
        Map<Long, Integer> level = new HashMap<>();
        starts.forEach(s -> level.put(s, 0));
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            for (Long n : next.getOrDefault(current, List.of())) {
                if (seen.add(n)) {
                    int d = level.get(current) + 1;
                    level.put(n, d);
                    depth.put(n, d);
                    queue.add(n);
                }
            }
        }
        return depth;
    }

    private static RecallBatch row(Recall recall, Batch b, String role, int depth, String action, Long transitionId,
                                   Map<Long, BigDecimal> remaining, Map<Long, RecallScopeFact> facts, LocalDateTime nowUtc) {
        RecallScopeFact fact = facts.get(b.getId());
        RecallBatch r = new RecallBatch();
        r.setRecallId(recall.getId());
        r.setBatchId(b.getId());
        r.setScopeRole(role);
        r.setDepth(depth);
        r.setHolderOrgId(b.getOrgId());
        r.setFlowStatus(b.getFlowStatus());
        r.setRiskStatusBefore(b.getRiskStatus());
        r.setAction(action);
        r.setRiskTransitionId(transitionId);
        r.setDeclaredQuantity(b.getQuantity());
        r.setRemainingQuantity(remaining.getOrDefault(b.getId(), BigDecimal.ZERO).max(BigDecimal.ZERO));
        r.setSoldQuantity(fact == null || fact.getSoldQuantity() == null ? BigDecimal.ZERO : fact.getSoldQuantity());
        r.setUnitCode(b.getUnitCode());
        if (fact != null && fact.getOpenTransferId() != null) {
            r.setOpenTransferId(fact.getOpenTransferId());
            r.setOpenTransferStatus(fact.getOpenTransferStatus());
            r.setShipmentStatus(fact.getShipmentStatus());
        }
        r.setPublicCodeActive(fact != null && fact.getPublicCodeActive() != null && fact.getPublicCodeActive() > 0);
        r.setCreatedAt(nowUtc);
        return r;
    }

    static CanonicalStart canonicalize(RecallCreateRequest req) {
        if (req == null) {
            throw badRequest("请求体不能为空");
        }
        if (req.unknownFields() != null && !req.unknownFields().isEmpty()) {
            throw badRequest("发起召回请求只接受 batchIds、reason、alertId，不接受以下字段（由服务端决定或未在契约中声明）: "
                    + String.join(", ", req.unknownFields().keySet()));
        }
        if (req.batchIds() == null || req.batchIds().isEmpty()) {
            throw badRequest("召回批次 batchIds 不能为空");
        }
        if (req.batchIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw badRequest("召回批次 batchIds 必须为正整数");
        }
        List<Long> seeds = req.batchIds().stream().distinct().sorted().toList();
        if (seeds.size() > MAX_SEEDS) {
            throw badRequest("一次最多召回 " + MAX_SEEDS + " 个批次");
        }
        String reason = requiredText(req.reason(), REASON_MAX, "召回原因 reason");
        if (req.alertId() != null && req.alertId() <= 0) {
            throw badRequest("来源告警 alertId 必须为正整数");
        }
        return new CanonicalStart(seeds, reason, req.alertId());
    }

    private void audit(TraceSecurityPrincipal principal, String action, Long recallId, LocalDateTime nowUtc, Map<String, Object> summary) {
        try {
            auditService.recordAudit(principal.getUserId(), principal.getOrgId(), action, AUDIT_OBJECT_TYPE, recallId, nowUtc,
                    "SUCCESS", objectMapper.writeValueAsString(summary));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR", "审计日志序列化失败", e.getMessage());
        }
    }

    private static BusinessException invalidState(String detail) {
        return new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "召回状态不允许", detail);
    }
}
