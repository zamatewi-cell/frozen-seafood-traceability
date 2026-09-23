package com.example.traceability.sale.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.sale.application.SaleApplicationService;
import com.example.traceability.sale.dto.SaleCreateRequest;
import com.example.traceability.sale.dto.SaleResponse;
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
 * 终端销售控制器。
 * <p>
 * 提交接口仅限批次当前责任 RETAILER 的企业操作员（OPERATOR），强制校验 CSRF Token 与 Idempotency-Key 请求头；
 * 查询接口开放给批次当前责任组织与平台只读角色。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@Validated
@RestController
@RequestMapping("/api/v1/batches/{batchId}/sales")
public class SaleController {

    private final SaleApplicationService saleService;

    public SaleController(SaleApplicationService saleService) {
        this.saleService = saleService;
    }

    /**
     * 提交终端销售（同一幂等键同一语义重放原销售，返回 201）。
     */
    @PostMapping
    public ResponseEntity<SuccessEnvelope<SaleResponse>> createSale(
            @PathVariable Long batchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SaleCreateRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        SaleResponse response = saleService.createSale(batchId, request, idempotencyKey, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessEnvelope.of(response));
    }

    /**
     * 查询批次终端销售记录（按业务发生时间、ID 升序）。
     */
    @GetMapping
    public SuccessEnvelope<List<SaleResponse>> listSales(
            @PathVariable Long batchId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal
    ) {
        return SuccessEnvelope.of(saleService.listSales(batchId, principal));
    }
}
