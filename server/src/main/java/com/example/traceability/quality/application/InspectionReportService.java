package com.example.traceability.quality.application;

import com.example.traceability.audit.application.AuditApplicationService;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchFlowStatus;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.domain.Alert;
import com.example.traceability.quality.domain.AlertStatus;
import com.example.traceability.quality.domain.InspectionReport;
import com.example.traceability.quality.dto.InspectionReportRequest;
import com.example.traceability.quality.dto.InspectionReportResponse;
import com.example.traceability.quality.mapper.AlertBatchMapper;
import com.example.traceability.quality.mapper.AlertMapper;
import com.example.traceability.quality.mapper.InspectionReportMapper;
import com.example.traceability.trace.domain.Transfer;
import com.example.traceability.trace.mapper.TransferMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.example.traceability.quality.application.QualityWriteGuards.badRequest;
import static com.example.traceability.quality.application.QualityWriteGuards.checkQualityManager;
import static com.example.traceability.quality.application.QualityWriteGuards.isPlatformScope;
import static com.example.traceability.quality.application.QualityWriteGuards.requireAuthenticated;
import static com.example.traceability.quality.application.QualityWriteGuards.requiredText;
import static com.example.traceability.quality.application.QualityWriteGuards.validateIdempotencyKey;

/**
 * 批次检验报告应用服务（Phase B PB4；统一业务契约 v1.1 §2.10 / §10.2 步骤 7 / §10.3 / §13 步骤 7–11 / §14）。
 * <p>
 * 检验报告是结构化证据，不自动冻结、不自动放行、不自动召回，也不核验外部机构真实性。提交人必须是质量管理员，其组织为：
 * <ul>
 *   <li>批次当前责任组织（CURRENT_ORG）——负责调查与处置；或</li>
 *   <li>该批次隔离中（QUARANTINED）交接的接收方（QUARANTINE_RECEIVER）——隔离期间只有证据提交权限（§10.3 / §14）。</li>
 * </ul>
 * 可关联告警（批次必须是该告警的受影响批次，告警未处置完毕）；关联告警的最新报告是告警放行决定的依据。
 * </p>
 * <p>
 * 锁顺序：报告幂等预读（非锁定）→ 批次行锁（FOR UPDATE，与告警放行决定串行化：放行在批次行锁下读取最新报告）→
 * 锁后复读 → 插入。不修改批次、交接或告警，不生成 TraceEvent。隔离级别 READ COMMITTED。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Service
public class InspectionReportService {

    static final String AUDIT_ACTION = "INSPECTION_REPORT_SUBMIT";
    static final String AUDIT_OBJECT_TYPE = "BATCH";
    static final String ROLE_CURRENT_ORG = "CURRENT_ORG";
    static final String ROLE_QUARANTINE_RECEIVER = "QUARANTINE_RECEIVER";
    static final Set<String> CONCLUSIONS = Set.of("PASS", "FAIL");
    static final Set<String> SOURCES = Set.of("MANUAL", "SIMULATED");
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final DateTimeFormatter CANONICAL_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");

    /** 规范化后的报告内容：只在这里规范化一次，哈希、校验与持久化使用同一个值。 */
    record Canonical(String reportNo, String institutionName, LocalDateTime inspectedAtUtc, String itemsSummary,
                     String conclusion, String dataSource, Long alertId) {
    }

    private final InspectionReportMapper reportMapper;
    private final BatchMapper batchMapper;
    private final TransferMapper transferMapper;
    private final AlertMapper alertMapper;
    private final AlertBatchMapper alertBatchMapper;
    private final AuditApplicationService auditService;
    private final ObjectMapper objectMapper;

    public InspectionReportService(
            InspectionReportMapper reportMapper,
            BatchMapper batchMapper,
            TransferMapper transferMapper,
            AlertMapper alertMapper,
            AlertBatchMapper alertBatchMapper,
            AuditApplicationService auditService,
            ObjectMapper objectMapper
    ) {
        this.reportMapper = Objects.requireNonNull(reportMapper, "reportMapper 不能为空");
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper 不能为空");
        this.transferMapper = Objects.requireNonNull(transferMapper, "transferMapper 不能为空");
        this.alertMapper = Objects.requireNonNull(alertMapper, "alertMapper 不能为空");
        this.alertBatchMapper = Objects.requireNonNull(alertBatchMapper, "alertBatchMapper 不能为空");
        this.auditService = Objects.requireNonNull(auditService, "auditService 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 提交检验报告 (POST /api/v1/batches/{batchId}/inspection-reports)。
     * <p>
     * 校验顺序：认证 / 平台代办 / QUALITY_MANAGER → 幂等键 → 请求规范化 → 幂等预读 → 批次行锁 → 锁后幂等复读 → 批次存在 →
     * 提交身份（当前责任组织或隔离接收方）→ 批次已生效 → 关联告警 → 检验时间 → 插入 → 审计。
     * </p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InspectionReportResponse submit(Long batchId, InspectionReportRequest req, String idempotencyKey,
                                           TraceSecurityPrincipal principal) {
        checkQualityManager(principal, "提交检验报告");
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        Canonical c = canonicalize(req);
        Long orgId = principal.getOrgId();
        String requestHash = QualityWriteGuards.hash("INSPECTION_REPORT", "v1", String.valueOf(batchId), c.reportNo(),
                c.institutionName(), c.inspectedAtUtc().format(CANONICAL_TIME), c.itemsSummary(), c.conclusion(), c.dataSource(),
                c.alertId() == null ? null : String.valueOf(c.alertId()));

        InspectionReport existing = reportMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (existing != null) {
            return replayOrConflict(existing, requestHash);
        }

        Batch batch = batchMapper.selectByIdIgnoreTenantForUpdate(batchId);
        InspectionReport afterLock = reportMapper.selectByOrgIdAndIdempotencyKey(orgId, cleanKey);
        if (afterLock != null) {
            return replayOrConflict(afterLock, requestHash);
        }
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }

        // 提交身份：批次当前责任组织，或该批次隔离中交接的接收方（隔离期间只有证据提交权限）
        String submitterRole;
        Long transferId = null;
        if (Objects.equals(batch.getOrgId(), orgId)) {
            submitterRole = ROLE_CURRENT_ORG;
        } else {
            Transfer quarantined = transferMapper.selectQuarantinedByBatchIdAndReceiver(batchId, orgId);
            if (quarantined == null) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                        "只有批次当前责任组织或该批次隔离收货的接收方可以提交检验报告");
            }
            submitterRole = ROLE_QUARANTINE_RECEIVER;
            transferId = quarantined.getId();
        }
        if (BatchFlowStatus.DRAFT.name().equals(batch.getFlowStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "批次尚未生效", "草稿批次不能登记检验报告");
        }
        if (c.alertId() != null) {
            Alert alert = alertMapper.selectById(c.alertId());
            if (alert == null || alertBatchMapper.countByAlertIdAndBatchId(c.alertId(), batchId) == 0) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ALERT_BATCH_MISMATCH", "告警与批次不匹配",
                        "关联告警不存在，或该批次不是该告警的受影响批次");
            }
            if (AlertStatus.RESOLVED.name().equals(alert.getStatus())) {
                throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "告警已处置",
                        "告警已形成处置结论，不能再关联新的检验报告");
            }
        }

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (c.inspectedAtUtc().isAfter(nowUtc.plus(MAX_FUTURE_SKEW))) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BUSINESS_TIME", "业务时间不合法",
                    "检验时间 inspectedAt 不能晚于当前时间");
        }

        InspectionReport report = new InspectionReport();
        report.setBatchId(batchId);
        report.setOrgId(orgId);
        report.setSubmitterRole(submitterRole);
        report.setTransferId(transferId);
        report.setAlertId(c.alertId());
        report.setReportNo(c.reportNo());
        report.setInstitutionName(c.institutionName());
        report.setInspectedAt(c.inspectedAtUtc());
        report.setItemsSummary(c.itemsSummary());
        report.setConclusion(c.conclusion());
        report.setDataSource(c.dataSource());
        report.setActorUserId(principal.getUserId());
        report.setIdempotencyKey(cleanKey);
        report.setRequestHash(requestHash);
        report.setRecordedAt(nowUtc);
        try {
            reportMapper.insert(report);
        } catch (DuplicateKeyException e) {
            InspectionReport dup = reportMapper.selectByOrgIdAndIdempotencyKeyForUpdate(orgId, cleanKey);
            if (dup != null) {
                return replayOrConflict(dup, requestHash);
            }
            throw new BusinessException(HttpStatus.CONFLICT, "INSPECTION_REPORT_NO_DUPLICATE", "报告编号重复",
                    "本组织已登记过编号为 " + c.reportNo() + " 的检验报告");
        } catch (PessimisticLockingFailureException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "INSPECTION_REPORT_CONCURRENT_CONFLICT", "检验报告并发冲突",
                    "同一幂等键的并发请求发生冲突，本次提交已回滚，请使用相同幂等键重试");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("inspectionReportId", report.getId());
        summary.put("submitterRole", submitterRole);
        summary.put("conclusion", c.conclusion());
        summary.put("alertId", c.alertId());
        summary.put("transferId", transferId);
        auditService.recordAudit(principal.getUserId(), orgId, AUDIT_ACTION, AUDIT_OBJECT_TYPE, batchId, nowUtc, "SUCCESS",
                serializeSummary(summary));
        return InspectionReportResponse.fromEntity(report);
    }

    /**
     * 查询批次检验报告：批次当前责任组织与平台只读角色看全部；提交过报告的组织与隔离接收方只看本组织提交的报告；
     * 其他组织 403。只读 REPEATABLE READ 快照（授权依据的责任组织与报告数据来自同一快照）。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<InspectionReportResponse> listReports(Long batchId, TraceSecurityPrincipal principal) {
        requireAuthenticated(principal);
        Batch batch = batchMapper.selectByIdIgnoreTenant(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("未找到 ID 为 " + batchId + " 的批次");
        }
        Long orgId = principal.getOrgId();
        List<InspectionReport> rows;
        if (isPlatformScope(principal) || Objects.equals(batch.getOrgId(), orgId)) {
            rows = reportMapper.selectByBatchId(batchId);
        } else if (reportMapper.countByBatchIdAndOrgId(batchId, orgId) > 0
                || transferMapper.selectQuarantinedByBatchIdAndReceiver(batchId, orgId) != null) {
            rows = reportMapper.selectByBatchIdAndOrgId(batchId, orgId);
        } else {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权",
                    "无权查看其他组织批次的检验报告");
        }
        return rows.stream().map(InspectionReportResponse::fromEntity).toList();
    }

    // =========================================================================
    // 校验与辅助
    // =========================================================================

    static Canonical canonicalize(InspectionReportRequest req) {
        if (req == null) {
            throw badRequest("请求体不能为空");
        }
        if (req.unknownFields() != null && !req.unknownFields().isEmpty()) {
            throw badRequest("检验报告请求只接受 reportNo、institutionName、inspectedAt、itemsSummary、conclusion、dataSource、alertId，"
                    + "不接受以下字段（由服务端决定或未在契约中声明）: " + String.join(", ", req.unknownFields().keySet()));
        }
        String reportNo = requiredText(req.reportNo(), 64, "报告编号 reportNo");
        String institution = requiredText(req.institutionName(), 128, "检验机构 institutionName");
        String items = requiredText(req.itemsSummary(), 500, "检测项目摘要 itemsSummary");
        OffsetDateTime inspectedAt = req.inspectedAt();
        if (inspectedAt == null) {
            throw badRequest("检验时间 inspectedAt 不能为空");
        }
        String conclusion = req.conclusion() == null ? "" : req.conclusion().trim();
        if (!CONCLUSIONS.contains(conclusion)) {
            throw badRequest("检验结论 conclusion 只能为 PASS 或 FAIL");
        }
        String source = req.dataSource() == null ? "" : req.dataSource().trim();
        if (!SOURCES.contains(source)) {
            throw badRequest("数据来源 dataSource 只能为 MANUAL 或 SIMULATED");
        }
        if (req.alertId() != null && req.alertId() <= 0) {
            throw badRequest("关联告警 alertId 必须为正整数");
        }
        LocalDateTime inspectedAtUtc = inspectedAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.MICROS);
        return new Canonical(reportNo, institution, inspectedAtUtc, items, conclusion, source, req.alertId());
    }

    private static InspectionReportResponse replayOrConflict(InspectionReport existing, String requestHash) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等提交冲突",
                    "当前幂等键已被本组织用于不同语义的检验报告提交");
        }
        return InspectionReportResponse.fromEntity(existing);
    }

    private String serializeSummary(Map<String, Object> summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR", "审计日志序列化失败", e.getMessage());
        }
    }
}
