package com.example.traceability.order.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.order.application.OrderBatchAllocationService;
import com.example.traceability.order.dto.BatchAllocationRequest;
import com.example.traceability.order.dto.BatchAllocationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单-批次分配控制器。
 */
@RestController
@RequestMapping("/api/v1/purchase-orders/{orderId}/allocations")
public class OrderBatchAllocationController {

    private final OrderBatchAllocationService allocService;

    public OrderBatchAllocationController(OrderBatchAllocationService allocService) {
        this.allocService = allocService;
    }

    @PostMapping
    public ResponseEntity<SuccessEnvelope<BatchAllocationResponse>> allocate(
            @PathVariable Long orderId,
            @Valid @RequestBody BatchAllocationRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessEnvelope.of(allocService.allocate(orderId, request, principal)));
    }

    @GetMapping
    public SuccessEnvelope<List<BatchAllocationResponse>> list(
            @PathVariable Long orderId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(allocService.listAllocations(orderId, principal));
    }

    @DeleteMapping("/{allocId}")
    public SuccessEnvelope<Void> remove(
            @PathVariable Long orderId,
            @PathVariable Long allocId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        allocService.removeAllocation(orderId, allocId, principal);
        return SuccessEnvelope.of(null);
    }
}
