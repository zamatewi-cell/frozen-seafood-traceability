package com.example.traceability.quality.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.application.QualityApplicationService;
import com.example.traceability.quality.dto.ChecklistItemRequest;
import com.example.traceability.quality.dto.ChecklistTemplateResponse;
import com.example.traceability.quality.dto.QualityInspectionCreateRequest;
import com.example.traceability.quality.dto.QualityInspectionResponse;
import com.example.traceability.quality.dto.QualityInspectionResultRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 质检控制器。
 * <p>提供对批次的质检单创建、质检员判定、环节清单打钩与通过、清单模板查询接口。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class QualityInspectionController {

    private final QualityApplicationService qualityService;

    public QualityInspectionController(QualityApplicationService qualityService) {
        this.qualityService = qualityService;
    }

    // ==================== 旧接口(保留兼容) ====================

    @PostMapping("/batches/{batchId}/inspections")
    public ResponseEntity<SuccessEnvelope<QualityInspectionResponse>> createInspection(
            @PathVariable Long batchId,
            @Valid @RequestBody QualityInspectionCreateRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        QualityInspectionResponse resp = qualityService.createInspection(batchId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(resp));
    }

    @GetMapping("/batches/{batchId}/inspections")
    public SuccessEnvelope<List<QualityInspectionResponse>> listInspections(
            @PathVariable Long batchId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(qualityService.listInspections(batchId, principal));
    }

    @PostMapping("/inspections/{inspectionId}/result")
    public SuccessEnvelope<QualityInspectionResponse> submitResult(
            @PathVariable Long inspectionId,
            @Valid @RequestBody QualityInspectionResultRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(qualityService.submitResult(inspectionId, request, principal));
    }

    @GetMapping("/inspections/{inspectionId}")
    public SuccessEnvelope<QualityInspectionResponse> getInspection(
            @PathVariable Long inspectionId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(qualityService.getInspection(inspectionId, principal));
    }

    // ==================== 新:环节质检流程 ====================

    /**
     * 查询某环节的清单模板(供前端渲染打钩)。
     */
    @GetMapping("/quality/checklist-template/{stageCode}")
    public SuccessEnvelope<List<ChecklistTemplateResponse>> getChecklistTemplate(
            @PathVariable String stageCode
    ) {
        return SuccessEnvelope.of(qualityService.getChecklistTemplate(stageCode));
    }

    /**
     * 操作员出货前提交质检审核:为订单分配的每个批次创建待检质检单。
     */
    @PostMapping("/orders/purchase/{orderId}/submit-quality-review")
    public SuccessEnvelope<List<QualityInspectionResponse>> submitQualityReview(
            @PathVariable Long orderId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(qualityService.submitQualityReview(orderId, principal));
    }

    /**
     * 质检员查看自己环节的待审质检单列表。
     */
    @GetMapping("/quality/pending-inspections")
    public SuccessEnvelope<List<QualityInspectionResponse>> listPendingInspections(
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(qualityService.listPendingInspections(principal));
    }

    /**
     * 查询某订单关联的质检单列表(供前端在订单详情里显示质检状态)。
     */
    @GetMapping("/orders/purchase/{orderId}/inspections")
    public SuccessEnvelope<List<QualityInspectionResponse>> listInspectionsByOrder(
            @PathVariable Long orderId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(qualityService.listInspectionsByOrder(orderId, principal));
    }

    /**
     * 质检员逐项打钩:更新 checklist_json 中某项的 passed 状态。
     */
    @PostMapping("/inspections/{inspectionId}/checklist-item")
    public SuccessEnvelope<QualityInspectionResponse> updateChecklistItem(
            @PathVariable Long inspectionId,
            @Valid @RequestBody ChecklistItemRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(qualityService.updateChecklistItem(inspectionId, request, principal));
    }

    /**
     * 质检员提交质检通过:要求清单全部打钩通过,通过后生成溯源事件。
     */
    @PostMapping("/inspections/{inspectionId}/pass")
    public SuccessEnvelope<QualityInspectionResponse> passInspection(
            @PathVariable Long inspectionId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(qualityService.passInspection(inspectionId, principal));
    }
}
