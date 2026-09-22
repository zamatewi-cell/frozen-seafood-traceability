package com.example.traceability.quality.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.domain.QualityInspection;
import com.example.traceability.quality.domain.QualityInspectionResult;
import com.example.traceability.quality.domain.QualityInspectionType;
import com.example.traceability.quality.dto.QualityInspectionCreateRequest;
import com.example.traceability.quality.dto.QualityInspectionResponse;
import com.example.traceability.quality.dto.QualityInspectionResultRequest;
import com.example.traceability.quality.mapper.QualityInspectionMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 质检应用服务。
 * <p>
 * 提供对指定批次的质检单创建与质检员（质量管理员）判定能力。
 * 业务规则：
 * 1. 仅质量管理员（ROLE_ORG_QA / QUALITY_MANAGER）可创建与判定；
 * 2. 质检仅针对当前企业组织名下的批次（组织隔离）；
 * 3. 质检单创建后为 INSPECTING（待检），仅允许判定为 PASS（合格）或 FAIL（不合格），已判定不可更改；
 * 4. 生产结果 FAIL 时不允许进入后续发货（收货侧到货验收 FAIL 则拒绝收货）。
 * </p>
 */
@Service
public class QualityApplicationService {

    private static final String QUALITY_ROLE_QA = "ROLE_ORG_QA";
    private static final String QUALITY_ROLE_MANAGER = "QUALITY_MANAGER";
    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final QualityInspectionMapper inspectionMapper;
    private final BatchMapper batchMapper;

    public QualityApplicationService(QualityInspectionMapper inspectionMapper, BatchMapper batchMapper) {
        this.inspectionMapper = Objects.requireNonNull(inspectionMapper, "inspectionMapper 不能为空");
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper 不能为空");
    }

    @Transactional(rollbackFor = Exception.class)
    public QualityInspectionResponse createInspection(
            Long batchId,
            QualityInspectionCreateRequest request,
            TraceSecurityPrincipal principal
    ) {
        checkQualityRole(principal);
        if (request == null || !QualityInspectionType.isValid(request.inspectionType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_QC_TYPE",
                    "非法质检类型", "质检类型必须为 SOURCE_RECEIVE/OUTGOING/ARRIVAL/SPOT");
        }
        Batch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND", "批次不存在", "被检批次不存在");
        }
        if (!Objects.equals(batch.getOrgId(), principal.getOrgId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED",
                    "组织数据访问越权", "只能对本企业组织名下的批次进行质检");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        QualityInspection insp = new QualityInspection();
        insp.setInspectionNo(nextNo());
        insp.setBatchId(batchId);
        insp.setOrgId(principal.getOrgId());
        insp.setInspectionType(request.inspectionType());
        insp.setRelatedTransferId(request.relatedTransferId());
        insp.setResult(QualityInspectionResult.INSPECTING);
        insp.setSummary(request.summary());
        insp.setChecklistJson(request.checklistJson());
        insp.setCreatedBy(principal.getUserId());
        insp.setCreatedAt(now);
        insp.setUpdatedAt(now);
        inspectionMapper.insert(insp);
        return toResponse(insp);
    }

    @Transactional(rollbackFor = Exception.class)
    public QualityInspectionResponse submitResult(
            Long inspectionId,
            QualityInspectionResultRequest request,
            TraceSecurityPrincipal principal
    ) {
        checkQualityRole(principal);
        if (request == null || !QualityInspectionResult.isValid(request.result())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_QC_RESULT",
                    "非法质检判定", "判定结果必须为 PASS 或 FAIL");
        }
        QualityInspection insp = requireOrgScopeInspection(inspectionId, principal.getOrgId());
        if (!QualityInspectionResult.INSPECTING.equals(insp.getResult())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "QC_ALREADY_DECIDED",
                    "质检单已判定", "质检单已提交结论，不可重复判定");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        insp.setResult(request.result());
        insp.setInspectorId(principal.getUserId());
        insp.setInspectorName(principal.getDisplayName());
        insp.setSummary(request.summary() == null ? insp.getSummary() : request.summary());
        insp.setCheckedAt(now);
        insp.setUpdatedBy(principal.getUserId());
        inspectionMapper.updateById(insp);
        return toResponse(insp);
    }

    public List<QualityInspectionResponse> listInspections(Long batchId, TraceSecurityPrincipal principal) {
        return inspectionMapper.selectList(new LambdaQueryWrapper<QualityInspection>()
                        .eq(QualityInspection::getBatchId, batchId)
                        .eq(QualityInspection::getOrgId, principal.getOrgId())
                        .orderByDesc(QualityInspection::getCreatedAt))
                .stream().map(this::toResponse).toList();
    }

    public QualityInspectionResponse getInspection(Long inspectionId, TraceSecurityPrincipal principal) {
        return toResponse(requireOrgScopeInspection(inspectionId, principal.getOrgId()));
    }

    /**
     * 供外部（交接收货校验）查询某批次、某组织下是否已有 PASS 的指定质检类型记录。
     */
    public boolean hasPassedInspection(Long batchId, Long orgId, String inspectionType) {
        long cnt = inspectionMapper.selectCount(new LambdaQueryWrapper<QualityInspection>()
                .eq(QualityInspection::getBatchId, batchId)
                .eq(QualityInspection::getOrgId, orgId)
                .eq(QualityInspection::getInspectionType, inspectionType)
                .eq(QualityInspection::getResult, QualityInspectionResult.PASS));
        return cnt > 0;
    }

    private QualityInspection requireOrgScopeInspection(Long inspectionId, Long orgId) {
        QualityInspection insp = inspectionMapper.selectById(inspectionId);
        if (insp == null || !Objects.equals(insp.getOrgId(), orgId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "QC_NOT_FOUND",
                    "质检单不存在", "质检单不存在或不属于当前企业组织");
        }
        return insp;
    }

    private void checkQualityRole(TraceSecurityPrincipal principal) {
        boolean ok = principal != null && principal.getRoles() != null
                && (principal.getRoles().contains(QUALITY_ROLE_QA)
                || principal.getRoles().contains(QUALITY_ROLE_MANAGER));
        if (!ok) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                    "无权访问", "质检操作仅限质量管理员执行");
        }
    }

    private String nextNo() {
        return "QC" + NO_FORMAT.format(LocalDateTime.now(ZoneOffset.UTC))
                + Integer.toHexString(RANDOM.nextInt(0xFFFF)).toUpperCase();
    }

    private QualityInspectionResponse toResponse(QualityInspection i) {
        return new QualityInspectionResponse(
                i.getId(), i.getInspectionNo(), i.getBatchId(), i.getOrgId(),
                i.getInspectionType(), i.getRelatedTransferId(),
                i.getInspectorId(), i.getInspectorName(), i.getResult(),
                i.getSummary(), i.getChecklistJson(), i.getCheckedAt(), i.getCreatedAt());
    }
}