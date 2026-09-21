package com.example.traceability.batch.web;

import com.example.traceability.batch.application.BatchApplicationService;
import com.example.traceability.batch.dto.BatchCreateRequest;
import com.example.traceability.batch.dto.BatchPatchRequest;
import com.example.traceability.batch.dto.BatchQueryCriteria;
import com.example.traceability.batch.dto.BatchResponse;
import com.example.traceability.batch.dto.BatchSubmitRequest;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 追溯批次生命周期控制器。
 * <p>
 * 提供批次分页查询（支持双编号与双状态过滤）、详情查看、草稿创建、草稿增量更新以及草稿提交激活等接口。
 * 读操作需已认证用户，写操作严格限定为 OPERATOR 角色并校验 CSRF。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Validated
@RestController
@RequestMapping("/api/v1/batches")
public class BatchController {

    private final BatchApplicationService batchService;

    public BatchController(BatchApplicationService batchService) {
        this.batchService = batchService;
    }

    /**
     * 分页查询批次列表。
     *
     * @param traceBatchNo    服务端追溯批次号（可选）
     * @param externalBatchNo 外部业务批次号（可选）
     * @param flowStatus      批次流转状态代码（可选）
     * @param riskStatus      批次风险状态代码（可选）
     * @param page            页码（从 1 开始，默认 1）
     * @param size            分页大小（默认 20，范围 1~100）
     * @param principal       当前认证主体
     * @return 批次列表分页封套
     */
    @GetMapping
    public SuccessEnvelope<List<BatchResponse>> listBatches(
            @RequestParam(name = "traceBatchNo", required = false) String traceBatchNo,
            @RequestParam(name = "externalBatchNo", required = false) String externalBatchNo,
            @RequestParam(name = "flowStatus", required = false) String flowStatus,
            @RequestParam(name = "riskStatus", required = false) String riskStatus,
            @RequestParam(name = "page", defaultValue = "1") @Min(value = 1, message = "页码 page 最小值为 1") int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(value = 1, message = "分页大小 size 最小值为 1") @Max(value = 100, message = "分页大小 size 最大值为 100") int size,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        BatchQueryCriteria criteria = new BatchQueryCriteria(traceBatchNo, externalBatchNo, flowStatus, riskStatus, page, size);
        return batchService.listBatches(criteria, principal);
    }

    /**
     * 查询指定 ID 的批次详情。
     *
     * @param batchId   批次内部主键 ID
     * @param principal 当前认证主体
     * @return 批次详情封套
     */
    @GetMapping("/{batchId}")
    public SuccessEnvelope<BatchResponse> getBatch(
            @PathVariable("batchId") Long batchId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(batchService.getBatchById(batchId, principal));
    }

    /**
     * 创建批次草稿（仅限企业操作员 OPERATOR 角色）。
     *
     * @param idempotencyKey 客户端幂等键
     * @param request        批次创建参数
     * @param principal      当前认证主体
     * @return 创建后的批次详情
     */
    @PostMapping
    public ResponseEntity<SuccessEnvelope<BatchResponse>> createBatch(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BatchCreateRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        BatchResponse response = batchService.createDraftBatch(request, idempotencyKey, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 增量更新批次草稿（仅限企业操作员 OPERATOR 角色）。
     *
     * @param batchId   批次内部主键 ID
     * @param request   增量更新参数（必须携带 version）
     * @param principal 当前认证主体
     * @return 更新后的批次详情
     */
    @PatchMapping("/{batchId}")
    public SuccessEnvelope<BatchResponse> patchBatch(
            @PathVariable("batchId") Long batchId,
            @Valid @RequestBody BatchPatchRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(batchService.patchDraftBatch(batchId, request, principal));
    }

    /**
     * 提交激活批次草稿（仅限企业操作员 OPERATOR 角色）。
     *
     * @param batchId   批次内部主键 ID
     * @param request   提交请求参数（必须携带 version）
     * @param principal 当前认证主体
     * @return 提交激活后的批次详情
     */
    @PostMapping("/{batchId}/submit")
    public SuccessEnvelope<BatchResponse> submitBatch(
            @PathVariable("batchId") Long batchId,
            @Valid @RequestBody BatchSubmitRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(batchService.submitDraftBatch(batchId, request, principal));
    }
}
