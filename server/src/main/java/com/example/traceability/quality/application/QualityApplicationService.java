package com.example.traceability.quality.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.order.domain.OrderBatchAllocation;
import com.example.traceability.order.mapper.OrderBatchAllocationMapper;
import com.example.traceability.quality.domain.QualityChecklistTemplate;
import com.example.traceability.quality.domain.QualityInspection;
import com.example.traceability.quality.domain.QualityInspectionResult;
import com.example.traceability.quality.domain.QualityInspectionStage;
import com.example.traceability.quality.domain.QualityInspectionType;
import com.example.traceability.quality.dto.ChecklistTemplateResponse;
import com.example.traceability.quality.dto.QualityInspectionCreateRequest;
import com.example.traceability.quality.dto.QualityInspectionResponse;
import com.example.traceability.quality.dto.QualityInspectionResultRequest;
import com.example.traceability.quality.mapper.QualityChecklistTemplateMapper;
import com.example.traceability.quality.mapper.QualityInspectionMapper;
import com.example.traceability.trace.application.TraceEventApplicationService;
import com.example.traceability.trace.domain.TraceEventType;
import com.example.traceability.trace.dto.CreateTraceEventRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 质检应用服务。
 * <p>
 * 提供对指定批次/订单的质检单创建、质检员判定、环节清单打钩与通过能力。
 * 业务规则:
 * 1. 出货前操作员提交质检审核 -> 创建待检质检单(带环节stage),质检员可见;
 * 2. 质检员逐项打钩确认清单,全部通过后可提交质检通过;
 * 3. 质检通过后每个清单项生成一条溯源事件(TraceEvent),加到批次谱系中;
 * 4. 仅质检通过(PIPE_PASSED)的订单批次才允许出货。
 * </p>
 */
@Service
public class QualityApplicationService {

    private static final String QUALITY_ROLE_QA = "ROLE_ORG_QA";
    private static final String QUALITY_ROLE_MANAGER = "QUALITY_MANAGER";
    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final QualityInspectionMapper inspectionMapper;
    private final QualityChecklistTemplateMapper checklistTemplateMapper;
    private final BatchMapper batchMapper;
    private final OrderBatchAllocationMapper allocationMapper;
    private final TraceEventApplicationService traceEventService;
    private final ObjectMapper objectMapper;

    public QualityApplicationService(
            QualityInspectionMapper inspectionMapper,
            QualityChecklistTemplateMapper checklistTemplateMapper,
            BatchMapper batchMapper,
            OrderBatchAllocationMapper allocationMapper,
            TraceEventApplicationService traceEventService,
            ObjectMapper objectMapper
    ) {
        this.inspectionMapper = Objects.requireNonNull(inspectionMapper, "inspectionMapper 不能为空");
        this.checklistTemplateMapper = Objects.requireNonNull(checklistTemplateMapper, "checklistTemplateMapper 不能为空");
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper 不能为空");
        this.allocationMapper = Objects.requireNonNull(allocationMapper, "allocationMapper 不能为空");
        this.traceEventService = Objects.requireNonNull(traceEventService, "traceEventService 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    // ==================== 旧接口(保留兼容) ====================

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

    // ==================== 新:环节质检流程 ====================

    /**
     * 查询某环节的清单模板(供前端渲染打钩)。
     */
    public List<ChecklistTemplateResponse> getChecklistTemplate(String stageCode) {
        if (!QualityInspectionStage.isValid(stageCode)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_QC_STAGE",
                    "非法质检环节", "环节必须为 CAPTURE_OUT/PROCESS_OUT/DIST_OUT");
        }
        return checklistTemplateMapper.selectList(new LambdaQueryWrapper<QualityChecklistTemplate>()
                        .eq(QualityChecklistTemplate::getStageCode, stageCode)
                        .eq(QualityChecklistTemplate::getStatus, "ACTIVE")
                        .orderByAsc(QualityChecklistTemplate::getSortOrder))
                .stream().map(t -> new ChecklistTemplateResponse(
                        t.getId(), t.getStageCode(), t.getOrgType(),
                        t.getItemName(), t.getItemDesc(), t.getSortOrder(), t.getIsRequired()))
                .toList();
    }

    /**
     * 操作员出货前提交质检审核:根据出货方组织类型确定环节,
     * 为订单的每个分配批次创建待检质检单。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<QualityInspectionResponse> submitQualityReview(
            Long orderId,
            TraceSecurityPrincipal principal
    ) {
        requireOperator(principal);
        // 查订单分配的批次
        List<OrderBatchAllocation> allocs = allocationMapper.selectByOrderId(orderId);
        if (allocs == null || allocs.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "NO_ALLOCATION",
                    "未分配批次", "请先分配批次后再提交质检审核");
        }
        // 根据当前组织类型确定环节
        String stage = resolveStageByOrgType(principal.getOrgType());
        // 检查是否已有待检/通过的质检单(避免重复提交)
        for (OrderBatchAllocation alloc : allocs) {
            long existing = inspectionMapper.selectCount(new LambdaQueryWrapper<QualityInspection>()
                    .eq(QualityInspection::getBatchId, alloc.getBatchId())
                    .eq(QualityInspection::getOrgId, principal.getOrgId())
                    .eq(QualityInspection::getInspectionStage, stage)
                    .in(QualityInspection::getResult, QualityInspectionResult.INSPECTING, QualityInspectionResult.PASS));
            if (existing > 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "QC_ALREADY_SUBMITTED",
                        "已提交质检", "批次 " + alloc.getBatchId() + " 已有待检或已通过的质检单");
            }
        }
        // 获取该环节的清单模板
        List<QualityChecklistTemplate> templates = checklistTemplateMapper.selectList(
                new LambdaQueryWrapper<QualityChecklistTemplate>()
                        .eq(QualityChecklistTemplate::getStageCode, stage)
                        .eq(QualityChecklistTemplate::getStatus, "ACTIVE")
                        .orderByAsc(QualityChecklistTemplate::getSortOrder));
        if (templates.isEmpty()) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "NO_CHECKLIST_TEMPLATE",
                    "清单模板缺失", "环节 " + stage + " 没有配置清单模板");
        }
        // 初始化 checklist_json: 每个清单项 passed=false
        String checklistJson = initChecklistJson(templates);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<QualityInspectionResponse> result = new ArrayList<>();
        for (OrderBatchAllocation alloc : allocs) {
            QualityInspection insp = new QualityInspection();
            insp.setInspectionNo(nextNo());
            insp.setBatchId(alloc.getBatchId());
            insp.setOrgId(principal.getOrgId());
            insp.setInspectionType(QualityInspectionType.OUTGOING);
            insp.setInspectionStage(stage);
            insp.setRelatedOrderId(orderId);
            insp.setResult(QualityInspectionResult.INSPECTING);
            insp.setChecklistJson(checklistJson);
            insp.setSummary("出货质检审核-环节:" + stage);
            insp.setCreatedBy(principal.getUserId());
            insp.setCreatedAt(now);
            insp.setUpdatedAt(now);
            inspectionMapper.insert(insp);
            result.add(toResponse(insp));
        }
        return result;
    }

    /**
     * 质检员查看自己环节的待审质检单列表。
     */
    public List<QualityInspectionResponse> listPendingInspections(TraceSecurityPrincipal principal) {
        checkQualityRole(principal);
        return inspectionMapper.selectList(new LambdaQueryWrapper<QualityInspection>()
                        .eq(QualityInspection::getOrgId, principal.getOrgId())
                        .eq(QualityInspection::getResult, QualityInspectionResult.INSPECTING)
                        .isNotNull(QualityInspection::getInspectionStage)
                        .orderByDesc(QualityInspection::getCreatedAt))
                .stream().map(this::toResponse).toList();
    }

    /**
     * 质检员逐项打钩:更新 checklist_json 中某项的 passed 状态。
     * 全部 passed 后自动记录 checklist_passed_at。
     */
    @Transactional(rollbackFor = Exception.class)
    public QualityInspectionResponse updateChecklistItem(
            Long inspectionId,
            com.example.traceability.quality.dto.ChecklistItemRequest request,
            TraceSecurityPrincipal principal
    ) {
        checkQualityRole(principal);
        QualityInspection insp = requireOrgScopeInspection(inspectionId, principal.getOrgId());
        if (!QualityInspectionResult.INSPECTING.equals(insp.getResult())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "QC_ALREADY_DECIDED",
                    "质检单已判定", "已判定的质检单不可再打钩");
        }
        if (insp.getChecklistJson() == null || insp.getChecklistJson().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NO_CHECKLIST",
                    "清单为空", "该质检单没有清单数据");
        }
        try {
            List<Map<String, Object>> items = objectMapper.readValue(
                    insp.getChecklistJson(), new TypeReference<List<Map<String, Object>>>() {});
            boolean found = false;
            for (Map<String, Object> item : items) {
                if (request.itemName().equals(item.get("itemName"))) {
                    item.put("passed", request.passed());
                    if (request.remark() != null) {
                        item.put("remark", request.remark());
                    }
                    item.put("checkedBy", principal.getDisplayName());
                    item.put("checkedAt", LocalDateTime.now(ZoneOffset.UTC).toString());
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "CHECKLIST_ITEM_NOT_FOUND",
                        "清单项不存在", "未找到清单项: " + request.itemName());
            }
            // 检查是否全部通过
            boolean allPassed = items.stream()
                    .map(i -> Boolean.TRUE.equals(i.get("passed")))
                    .reduce(true, Boolean::logicalAnd);
            String newJson = objectMapper.writeValueAsString(items);
            insp.setChecklistJson(newJson);
            insp.setUpdatedBy(principal.getUserId());
            insp.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            if (allPassed && insp.getChecklistPassedAt() == null) {
                insp.setChecklistPassedAt(LocalDateTime.now(ZoneOffset.UTC));
            }
            inspectionMapper.updateById(insp);
            return toResponse(insp);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "CHECKLIST_UPDATE_FAILED",
                    "清单更新失败", e.getMessage());
        }
    }

    /**
     * 质检员提交质检通过:要求清单全部打钩通过。
     * 通过后为每个清单项生成一条溯源事件(TraceEvent)。
     */
    @Transactional(rollbackFor = Exception.class)
    public QualityInspectionResponse passInspection(
            Long inspectionId,
            TraceSecurityPrincipal principal
    ) {
        checkQualityRole(principal);
        QualityInspection insp = requireOrgScopeInspection(inspectionId, principal.getOrgId());
        if (!QualityInspectionResult.INSPECTING.equals(insp.getResult())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "QC_ALREADY_DECIDED",
                    "质检单已判定", "质检单已提交结论，不可重复判定");
        }
        if (insp.getChecklistJson() == null || insp.getChecklistJson().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NO_CHECKLIST",
                    "清单为空", "请先完成清单检查");
        }
        try {
            List<Map<String, Object>> items = objectMapper.readValue(
                    insp.getChecklistJson(), new TypeReference<List<Map<String, Object>>>() {});
            boolean allPassed = items.stream()
                    .map(i -> Boolean.TRUE.equals(i.get("passed")))
                    .reduce(true, Boolean::logicalAnd);
            if (!allPassed) {
                long unchecked = items.stream()
                        .filter(i -> !Boolean.TRUE.equals(i.get("passed"))).count();
                throw new BusinessException(HttpStatus.BAD_REQUEST, "CHECKLIST_INCOMPLETE",
                        "清单未全部通过", "还有 " + unchecked + " 项未通过，无法提交质检通过");
            }
            // 标记质检通过
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            insp.setResult(QualityInspectionResult.PASS);
            insp.setInspectorId(principal.getUserId());
            insp.setInspectorName(principal.getDisplayName());
            insp.setCheckedAt(now);
            if (insp.getChecklistPassedAt() == null) {
                insp.setChecklistPassedAt(now);
            }
            insp.setUpdatedBy(principal.getUserId());
            insp.setUpdatedAt(now);
            inspectionMapper.updateById(insp);

            // 为每个清单项生成溯源事件
            generateTraceEventsForChecklist(insp, items, principal);

            return toResponse(insp);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "QC_PASS_FAILED",
                    "质检通过失败", e.getMessage());
        }
    }

    /**
     * 为质检单的每个清单项生成溯源事件。
     * 使用 PACK 事件类型，detailsJson 记录环节/清单项/通过时间。
     */
    private void generateTraceEventsForChecklist(
            QualityInspection insp,
            List<Map<String, Object>> items,
            TraceSecurityPrincipal principal
    ) {
        for (Map<String, Object> item : items) {
            String itemName = (String) item.get("itemName");
            String checkedBy = (String) item.getOrDefault("checkedBy", principal.getDisplayName());
            LocalDateTime checkedAtUtc = item.get("checkedAt") != null
                    ? LocalDateTime.parse(item.get("checkedAt").toString())
                    .atOffset(ZoneOffset.UTC).toLocalDateTime()
                    : LocalDateTime.now(ZoneOffset.UTC);
            Map<String, Object> details = new HashMap<>();
            details.put("stage", insp.getInspectionStage());
            details.put("checklistItem", itemName);
            details.put("checkedBy", checkedBy);
            details.put("checkedAt", checkedAtUtc.toString());
            details.put("inspectionNo", insp.getInspectionNo());
            details.put("orderId", insp.getRelatedOrderId());
            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    TraceEventType.PACK.name(),
                    checkedAtUtc.atOffset(ZoneOffset.UTC),
                    null,
                    "MANUAL",
                    "质检通过: " + itemName + " (环节:" + insp.getInspectionStage() + ")",
                    details
            );
            String idempotencyKey = "quality-checklist-" + insp.getId() + "-" + Math.abs(itemName.hashCode());
            traceEventService.createEventForQuality(insp.getBatchId(), req, idempotencyKey, principal);
        }
    }

    /**
     * 根据组织类型确定质检环节。
     */
    private String resolveStageByOrgType(String orgType) {
        if (orgType == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NO_ORG_TYPE",
                    "组织类型为空", "无法确定质检环节");
        }
        return switch (orgType) {
            case "SOURCE" -> QualityInspectionStage.CAPTURE_OUT;
            case "PROCESSOR" -> QualityInspectionStage.PROCESS_OUT;
            case "DISTRIBUTOR" -> QualityInspectionStage.DIST_OUT;
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_ORG_TYPE",
                    "不支持的组织类型", "组织类型 " + orgType + " 没有对应的出货质检环节");
        };
    }

    /**
     * 查询某订单关联的质检单列表(供前端在订单详情里显示质检状态)。
     * 按当前操作者组织过滤:卖方看到自己环节的质检单,买方看到卖方环节的质检单。
     */
    public List<QualityInspectionResponse> listInspectionsByOrder(
            Long orderId, TraceSecurityPrincipal principal) {
        if (principal == null || principal.getOrgId() == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                    "无权访问", "请先登录");
        }
        return inspectionMapper.selectList(new LambdaQueryWrapper<QualityInspection>()
                        .eq(QualityInspection::getRelatedOrderId, orderId)
                        .eq(QualityInspection::getIsDeleted, 0)
                        .orderByDesc(QualityInspection::getCreatedAt))
                .stream().map(this::toResponse).toList();
    }

    /**
     * 判断某订单的质检是否已全部通过(供前端按钮显示用)。
     */
    public boolean isOrderQualityPassed(Long orderId, Long orgId, String orgType) {
        String stage = resolveStageByOrgType(orgType);
        List<OrderBatchAllocation> allocs = allocationMapper.selectByOrderId(orderId);
        if (allocs == null || allocs.isEmpty()) {
            return false;
        }
        for (OrderBatchAllocation alloc : allocs) {
            long cnt = inspectionMapper.selectCount(new LambdaQueryWrapper<QualityInspection>()
                    .eq(QualityInspection::getBatchId, alloc.getBatchId())
                    .eq(QualityInspection::getOrgId, orgId)
                    .eq(QualityInspection::getInspectionStage, stage)
                    .eq(QualityInspection::getResult, QualityInspectionResult.PASS));
            if (cnt == 0) {
                return false;
            }
        }
        return true;
    }

    // ==================== 私有辅助 ====================

    private String initChecklistJson(List<QualityChecklistTemplate> templates) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (QualityChecklistTemplate t : templates) {
            Map<String, Object> item = new HashMap<>();
            item.put("itemName", t.getItemName());
            item.put("itemDesc", t.getItemDesc());
            item.put("passed", false);
            item.put("checkedBy", null);
            item.put("checkedAt", null);
            item.put("remark", null);
            items.add(item);
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "CHECKLIST_INIT_FAILED",
                    "清单初始化失败", e.getMessage());
        }
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

    private void requireOperator(TraceSecurityPrincipal principal) {
        if (principal == null || principal.getRoles() == null
                || !principal.getRoles().contains("OPERATOR")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED",
                    "角色权限不足", "仅企业操作员(OPERATOR)可提交质检审核");
        }
    }

    private String nextNo() {
        return "QC" + NO_FORMAT.format(LocalDateTime.now(ZoneOffset.UTC))
                + Integer.toHexString(RANDOM.nextInt(0xFFFF)).toUpperCase();
    }

    private QualityInspectionResponse toResponse(QualityInspection i) {
        return new QualityInspectionResponse(
                i.getId(), i.getInspectionNo(), i.getBatchId(), i.getOrgId(),
                i.getInspectionType(), i.getInspectionStage(), i.getRelatedOrderId(),
                i.getRelatedTransferId(),
                i.getInspectorId(), i.getInspectorName(), i.getResult(),
                i.getSummary(), i.getChecklistJson(), i.getChecklistPassedAt(),
                i.getCheckedAt(), i.getCreatedAt());
    }
}
