package com.example.traceability.quality.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.application.BatchRiskHoldService;
import com.example.traceability.quality.dto.BatchRiskHoldsResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次风险事项查询接口（Phase B 独立评审修复）：批次当前未解除的告警风险事项、人工风险冻结与上游召回通知。只读。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/batches/{batchId}/risk-holds")
public class BatchRiskHoldController {

    private final BatchRiskHoldService holdService;

    public BatchRiskHoldController(BatchRiskHoldService holdService) {
        this.holdService = holdService;
    }

    /**
     * 批次当前未解除的风险事项与上游召回通知（当前责任组织与平台只读角色）。
     */
    @GetMapping
    public SuccessEnvelope<BatchRiskHoldsResponse> get(
            @PathVariable Long batchId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(holdService.getRiskHolds(batchId, principal));
    }
}
