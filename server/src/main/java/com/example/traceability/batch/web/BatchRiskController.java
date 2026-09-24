package com.example.traceability.batch.web;

import com.example.traceability.batch.application.BatchRiskService;
import com.example.traceability.batch.dto.BatchRiskTransitionRequest;
import com.example.traceability.batch.dto.BatchRiskTransitionResponse;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 批次风险状态控制器（Phase B PB1：人工风险冻结 / 解除冻结）。
 * <p>
 * 写接口仅限批次当前责任组织的质量管理员（QUALITY_MANAGER），强制校验 CSRF Token 与 Idempotency-Key 请求头；
 * 查询接口开放给当前责任组织（完整历史）、平台只读角色（完整历史）与历史参与组织（仅本组织登记的转换）。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Validated
@RestController
@RequestMapping("/api/v1/batches/{batchId}")
public class BatchRiskController {

    private final BatchRiskService batchRiskService;

    public BatchRiskController(BatchRiskService batchRiskService) {
        this.batchRiskService = batchRiskService;
    }

    /**
     * 风险冻结 NORMAL → FROZEN（同一幂等键同一语义重放原转换，返回 201）。
     */
    @PostMapping("/risk/freeze")
    public ResponseEntity<SuccessEnvelope<BatchRiskTransitionResponse>> freeze(
            @PathVariable Long batchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BatchRiskTransitionRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        BatchRiskTransitionResponse response = batchRiskService.freeze(batchId, request, idempotencyKey, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 解除冻结 FROZEN → NORMAL（同一幂等键同一语义重放原转换，返回 201）。
     */
    @PostMapping("/risk/release")
    public ResponseEntity<SuccessEnvelope<BatchRiskTransitionResponse>> release(
            @PathVariable Long batchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BatchRiskTransitionRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        BatchRiskTransitionResponse response = batchRiskService.release(batchId, request, idempotencyKey, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 查询批次风险状态转换历史（按登记顺序）。
     */
    @GetMapping("/risk-transitions")
    public SuccessEnvelope<List<BatchRiskTransitionResponse>> listTransitions(
            @PathVariable Long batchId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(batchRiskService.listTransitions(batchId, principal));
    }
}
