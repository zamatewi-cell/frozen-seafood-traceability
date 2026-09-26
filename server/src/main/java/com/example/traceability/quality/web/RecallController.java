package com.example.traceability.quality.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.quality.application.RecallService;
import com.example.traceability.quality.dto.RecallCloseRequest;
import com.example.traceability.quality.dto.RecallCreateRequest;
import com.example.traceability.quality.dto.RecallResponse;
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
 * 模拟召回接口（Phase B PB5）：发起、关闭与查询。召回是教学演练，不代表真实法定召回；不提供修改或删除接口。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/recalls")
public class RecallController {

    private final RecallService recallService;

    public RecallController(RecallService recallService) {
        this.recallService = recallService;
    }

    /**
     * 发起模拟召回（同一幂等键同一语义重放，返回 201）。
     */
    @PostMapping
    public ResponseEntity<SuccessEnvelope<RecallResponse>> start(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody RecallCreateRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        RecallResponse response = recallService.start(request, idempotencyKey, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 可见召回列表（按主键倒序）。
     */
    @GetMapping
    public SuccessEnvelope<List<RecallResponse>> list(@AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(recallService.listRecalls(principal));
    }

    /**
     * 召回详情（影响范围按查看组织过滤）。
     */
    @GetMapping("/{recallId}")
    public SuccessEnvelope<RecallResponse> get(
            @PathVariable Long recallId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(recallService.getRecall(recallId, principal));
    }

    /**
     * 关闭模拟召回（IN_PROGRESS → CLOSED；批次保留 RECALLED）。
     */
    @PostMapping("/{recallId}/close")
    public SuccessEnvelope<RecallResponse> close(
            @PathVariable Long recallId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody RecallCloseRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(recallService.close(recallId, request, idempotencyKey, principal));
    }
}
