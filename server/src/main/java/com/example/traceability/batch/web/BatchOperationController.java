package com.example.traceability.batch.web;

import com.example.traceability.batch.application.BatchOperationApplicationService;
import com.example.traceability.batch.dto.BatchOperationCreateRequest;
import com.example.traceability.batch.dto.BatchOperationResponse;
import com.example.traceability.batch.dto.BatchOperationSubmitRequest;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次操作与物料平衡控制器。
 * <p>
 * 提供批次拆分、合并、加工及分装操作草稿创建，以及提交流转与谱系边生成接口。
 * 严格限定仅限企业操作员（OPERATOR）访问，强制校验 CSRF Token 与 Idempotency-Key 请求头。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Validated
@RestController
@RequestMapping("/api/v1/batch-operations")
public class BatchOperationController {

    private final BatchOperationApplicationService operationService;

    public BatchOperationController(BatchOperationApplicationService operationService) {
        this.operationService = operationService;
    }

    /**
     * 创建批次操作草稿。
     *
     * @param idempotencyKey 客户端创建幂等键
     * @param request        批次操作创建参数
     * @param principal      当前认证主体
     * @return 批次操作草稿详情
     */
    @PostMapping
    public ResponseEntity<SuccessEnvelope<BatchOperationResponse>> createOperation(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BatchOperationCreateRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        BatchOperationResponse response = operationService.createDraftOperation(request, idempotencyKey, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 提交批次操作并生成谱系边。
     *
     * @param operationId    批次操作 ID
     * @param idempotencyKey 客户端提交防重幂等键
     * @param request        提交请求参数（必须携带 version）
     * @param principal      当前认证主体
     * @return 提交后的批次操作详情及生成的谱系边
     */
    @PostMapping("/{operationId}/submit")
    public SuccessEnvelope<BatchOperationResponse> submitOperation(
            @PathVariable Long operationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BatchOperationSubmitRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(operationService.submitOperation(operationId, request, idempotencyKey, principal));
    }
}
