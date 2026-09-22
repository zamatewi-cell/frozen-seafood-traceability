package com.example.traceability.trace.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.trace.application.PublicTraceApplicationService;
import com.example.traceability.trace.dto.BindBatchToPublicCodeRequest;
import com.example.traceability.trace.dto.PublicTraceCodeResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开追溯码企业端聚合管理控制器。
 * <p>
 * 提供「一码聚合多批」能力：把物理批次绑定到某个公开溯源码，使消费者扫码可看到
 * 该码聚合的多个批次完整履历。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.2.0
 */
@Validated
@RestController
@RequestMapping("/api/v1/public-trace-codes")
public class PublicTraceCodeAdminController {

    private final PublicTraceApplicationService publicTraceService;

    public PublicTraceCodeAdminController(PublicTraceApplicationService publicTraceService) {
        this.publicTraceService = publicTraceService;
    }

    /**
     * 将物理批次绑定到某个公开追溯码（一码可聚合多批，幂等）。
     */
    @PostMapping("/bind-batch")
    public SuccessEnvelope<PublicTraceCodeResponse> bindBatch(
            @Valid @RequestBody BindBatchToPublicCodeRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        PublicTraceCodeResponse response = publicTraceService.bindBatchToPublicCode(
                request.publicId(), request.batchId(), request.bindRole(), principal);
        return SuccessEnvelope.of(response);
    }
}