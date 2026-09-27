package com.example.traceability.quality.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.application.InspectionReportService;
import com.example.traceability.quality.dto.InspectionReportRequest;
import com.example.traceability.quality.dto.InspectionReportResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 批次检验报告接口（Phase B PB4）：提交与查询。报告追加式，不提供修改或删除接口。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/batches/{batchId}/inspection-reports")
public class InspectionReportController {

    private final InspectionReportService reportService;

    public InspectionReportController(InspectionReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 提交检验报告（同一幂等键同一语义重放原报告，返回 201）。
     */
    @PostMapping
    public ResponseEntity<SuccessEnvelope<InspectionReportResponse>> submit(
            @PathVariable Long batchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody InspectionReportRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        InspectionReportResponse response = reportService.submit(batchId, request, idempotencyKey, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 查询批次检验报告（按登记顺序）。
     */
    @GetMapping
    public SuccessEnvelope<List<InspectionReportResponse>> list(
            @PathVariable Long batchId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(reportService.listReports(batchId, principal));
    }
}
