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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 批次操作与物料平衡控制器。
 * <p>
 * 提供加工 (PROCESS) / 拆分 (SPLIT) 操作草稿创建、提交、详情、按批次查询与草稿删除接口。
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

    /**
     * 查询批次操作详情（操作所属组织可读，含批次已转出后的历史只读；平台只读角色可读）。
     *
     * @param operationId 批次操作 ID
     * @param principal   当前认证主体
     * @return 批次操作详情
     */
    @GetMapping("/{operationId}")
    public SuccessEnvelope<BatchOperationResponse> getOperation(
            @PathVariable Long operationId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(operationService.getOperation(operationId, principal));
    }

    /**
     * 按批次查询引用该批次的批次操作（企业用户仅返回本组织创建的操作）。
     *
     * @param batchId   批次 ID（必填）
     * @param page      页码（从 1 开始）
     * @param size      每页记录数（1..100）
     * @param principal 当前认证主体
     * @return 分页批次操作列表
     */
    @GetMapping
    public SuccessEnvelope<List<BatchOperationResponse>> listOperations(
            @RequestParam Long batchId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return operationService.listOperationsByBatch(batchId, page, size, principal);
    }

    /**
     * 删除批次操作草稿（同时逻辑删除其服务端生成的 OUTPUT 草稿批次）。
     *
     * @param operationId     批次操作 ID
     * @param expectedVersion 期望版本号
     * @param principal       当前认证主体
     * @return 204 No Content
     */
    @DeleteMapping("/{operationId}")
    public ResponseEntity<Void> deleteOperation(
            @PathVariable Long operationId,
            @RequestParam Long expectedVersion,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        operationService.deleteDraftOperation(operationId, expectedVersion, principal);
        return ResponseEntity.noContent().build();
    }
}
