package com.example.traceability.quality.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.application.QualityApplicationService;
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
 * <p>提供对批次的质检单创建、质检员判定、列表与详情查询接口。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class QualityInspectionController {

    private final QualityApplicationService qualityService;

    public QualityInspectionController(QualityApplicationService qualityService) {
        this.qualityService = qualityService;
    }

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
}