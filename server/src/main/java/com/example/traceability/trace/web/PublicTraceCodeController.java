package com.example.traceability.trace.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.trace.application.PublicTraceApplicationService;
import com.example.traceability.trace.dto.PublicTraceCodeResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次对外公开追溯码企业端管理控制器。
 * <p>
 * 提供批次公开追溯码的激活与终态停用操作。
 * 强制要求 Session 登录态、CSRF 防护、Idempotency-Key 幂等头以及当前组织 OPERATOR 操作员角色。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Validated
@RestController
@RequestMapping("/api/v1/batches/{batchId}/public-trace-code")
public class PublicTraceCodeController {

    private final PublicTraceApplicationService publicTraceService;

    public PublicTraceCodeController(PublicTraceApplicationService publicTraceService) {
        this.publicTraceService = publicTraceService;
    }

    /**
     * 激活指定批次的对外公开追溯码（仅限本组织 OPERATOR 角色）。
     *
     * @param batchId        批次内部主键 ID
     * @param idempotencyKey 客户端幂等键 (16..128 字符)
     * @param principal      当前认证主体
     * @return 激活的公开追溯码详情封套
     */
    @PostMapping("/activate")
    public SuccessEnvelope<PublicTraceCodeResponse> activatePublicTraceCode(
            @PathVariable Long batchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        PublicTraceCodeResponse response = publicTraceService.activatePublicTraceCode(batchId, idempotencyKey, principal);
        return SuccessEnvelope.of(response);
    }

    /**
     * 停用指定批次的对外公开追溯码（仅限本组织 OPERATOR 角色）。
     *
     * @param batchId        批次内部主键 ID
     * @param idempotencyKey 客户端幂等键 (16..128 字符)
     * @param principal      当前认证主体
     * @return 停用后的公开追溯码详情封套
     */
    @PostMapping("/disable")
    public SuccessEnvelope<PublicTraceCodeResponse> disablePublicTraceCode(
            @PathVariable Long batchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        PublicTraceCodeResponse response = publicTraceService.disablePublicTraceCode(batchId, idempotencyKey, principal);
        return SuccessEnvelope.of(response);
    }
}
