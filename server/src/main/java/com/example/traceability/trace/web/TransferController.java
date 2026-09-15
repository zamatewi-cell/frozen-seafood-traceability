package com.example.traceability.trace.web;

import com.example.traceability.common.envelope.PageMeta;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.trace.application.TransferApplicationService;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import com.example.traceability.trace.dto.TransferCreateRequest;
import com.example.traceability.trace.dto.TransferPatchRequest;
import com.example.traceability.trace.dto.TransferRejectRequest;
import com.example.traceability.trace.dto.TransferResponse;
import com.example.traceability.trace.dto.TransferSubmitRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 企业间整批交接生命周期控制器。
 * <p>
 * 提供整批交接草稿创建、修改、逻辑删除、提交发货、收货接受与到货拒收接口。
 * 写操作严格校验登录主体、CSRF 防护与 Idempotency-Key 防重幂等键。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Validated
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferApplicationService transferService;

    public TransferController(TransferApplicationService transferService) {
        this.transferService = transferService;
    }

    /**
     * 创建企业间整批交接草稿 (POST /api/v1/transfers)。
     */
    @PostMapping
    public ResponseEntity<SuccessEnvelope<TransferResponse>> createDraft(
            @Valid @RequestBody TransferCreateRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        TransferResponse response = transferService.createDraft(req, idempotencyKey, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 分页查询当前企业相关交接列表 (GET /api/v1/transfers)。
     */
    @GetMapping
    public SuccessEnvelope<List<TransferResponse>> listTransfers(
            @RequestParam(value = "direction", required = false) String direction,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1")
            @Min(value = 1, message = "页码 page 最小值为 1") int page,
            @RequestParam(value = "size", defaultValue = "20")
            @Min(value = 1, message = "分页大小 size 最小值为 1")
            @Max(value = 100, message = "分页大小 size 最大值为 100") int size,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        String normalizedDirection = null;
        if (direction != null && !direction.isBlank()) {
            String dir = direction.trim().toUpperCase();
            if (!java.util.Set.of("SENT", "RECEIVED").contains(dir)) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "参数校验失败",
                        "direction 必须为 SENT 或 RECEIVED"
                );
            }
            normalizedDirection = dir;
        }
        String normalizedStatus = null;
        if (status != null && !status.isBlank()) {
            String st = status.trim().toUpperCase();
            if (!java.util.Set.of("DRAFT", "PENDING", "ACCEPTED", "REJECTED").contains(st)) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_REQUEST",
                        "参数校验失败",
                        "status 必须为 DRAFT、PENDING、ACCEPTED 或 REJECTED"
                );
            }
            normalizedStatus = st;
        }
        List<TransferResponse> list = transferService.listTransfers(normalizedDirection, normalizedStatus, page, size, principal);
        long total = transferService.countTransfers(normalizedDirection, normalizedStatus, principal);
        PageMeta pageMeta = new PageMeta(page, size, total);
        return SuccessEnvelope.ofPage(list, pageMeta);
    }

    /**
     * 查询交接凭证详情 (GET /api/v1/transfers/{transferId})。
     */
    @GetMapping("/{transferId}")
    public SuccessEnvelope<TransferResponse> getTransferDetail(
            @PathVariable Long transferId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        TransferResponse response = transferService.getTransferDetail(transferId, principal);
        return SuccessEnvelope.of(response);
    }

    /**
     * 发送方修改交接草稿 (PATCH /api/v1/transfers/{transferId})。
     */
    @PatchMapping("/{transferId}")
    public SuccessEnvelope<TransferResponse> patchDraft(
            @PathVariable Long transferId,
            @Valid @RequestBody TransferPatchRequest req,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        TransferResponse response = transferService.patchDraft(transferId, req, principal);
        return SuccessEnvelope.of(response);
    }

    /**
     * 发送方逻辑删除交接草稿 (DELETE /api/v1/transfers/{transferId})。
     */
    @DeleteMapping("/{transferId}")
    public ResponseEntity<Void> deleteDraft(
            @PathVariable Long transferId,
            @RequestParam("expectedVersion")
            @Min(value = 0, message = "期望乐观锁版本号不能小于 0") Long expectedVersion,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        transferService.deleteDraft(transferId, expectedVersion, principal);
        return ResponseEntity.noContent().build();
    }

    /**
     * 发送方提交交接 (POST /api/v1/transfers/{transferId}/submit)。
     */
    @PostMapping("/{transferId}/submit")
    public SuccessEnvelope<TransferResponse> submitTransfer(
            @PathVariable Long transferId,
            @Valid @RequestBody TransferSubmitRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        TransferResponse response = transferService.submitTransfer(transferId, req, idempotencyKey, principal);
        return SuccessEnvelope.of(response);
    }

    /**
     * 接收方接受交接并转移持有权 (POST /api/v1/transfers/{transferId}/accept)。
     */
    @PostMapping("/{transferId}/accept")
    public SuccessEnvelope<TransferResponse> acceptTransfer(
            @PathVariable Long transferId,
            @Valid @RequestBody TransferAcceptRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        TransferResponse response = transferService.acceptTransfer(transferId, req, idempotencyKey, principal);
        return SuccessEnvelope.of(response);
    }

    /**
     * 接收方拒收交接 (POST /api/v1/transfers/{transferId}/reject)。
     */
    @PostMapping("/{transferId}/reject")
    public SuccessEnvelope<TransferResponse> rejectTransfer(
            @PathVariable Long transferId,
            @Valid @RequestBody TransferRejectRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        TransferResponse response = transferService.rejectTransfer(transferId, req, idempotencyKey, principal);
        return SuccessEnvelope.of(response);
    }
}
