package com.example.traceability.order.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.order.application.OrderApplicationService;
import com.example.traceability.order.dto.OrderDecisionRequest;
import com.example.traceability.order.dto.OrderStatusUpdateRequest;
import com.example.traceability.order.dto.PurchaseOrderCreateRequest;
import com.example.traceability.order.dto.PurchaseOrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 采购进货单控制器（B2B）。
 */
@RestController
@RequestMapping("/api/v1/orders/purchase")
public class PurchaseOrderController {

    private final OrderApplicationService orderService;

    public PurchaseOrderController(OrderApplicationService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<SuccessEnvelope<PurchaseOrderResponse>> create(
            @Valid @RequestBody PurchaseOrderCreateRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessEnvelope.of(orderService.createPurchaseOrder(request, principal)));
    }

    @GetMapping
    public SuccessEnvelope<List<PurchaseOrderResponse>> list(
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.listPurchaseOrders(principal));
    }

    @GetMapping("/{orderId}")
    public SuccessEnvelope<PurchaseOrderResponse> get(
            @PathVariable Long orderId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.getPurchaseOrder(orderId, principal));
    }

    @PostMapping("/{orderId}/status")
    public SuccessEnvelope<PurchaseOrderResponse> updateStatus(
            @PathVariable Long orderId,
            @Valid @RequestBody OrderStatusUpdateRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.updatePurchaseStatus(orderId, request, principal));
    }

    @PostMapping("/{orderId}/approve")
    public SuccessEnvelope<PurchaseOrderResponse> approve(
            @PathVariable Long orderId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.approvePurchaseOrder(orderId, principal));
    }

    @PostMapping("/{orderId}/reject")
    public SuccessEnvelope<PurchaseOrderResponse> reject(
            @PathVariable Long orderId,
            @Valid @RequestBody OrderDecisionRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.rejectPurchaseOrder(orderId, request, principal));
    }

    @PostMapping("/{orderId}/schedule")
    public SuccessEnvelope<PurchaseOrderResponse> schedule(
            @PathVariable Long orderId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.schedulePurchaseOrder(orderId, principal));
    }

    @PostMapping("/{orderId}/complete-delivery")
    public SuccessEnvelope<PurchaseOrderResponse> completeDelivery(
            @PathVariable Long orderId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.completePurchaseOrderDelivery(orderId, principal));
    }

    @PostMapping("/{orderId}/receive")
    public SuccessEnvelope<PurchaseOrderResponse> receive(
            @PathVariable Long orderId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.receivePurchaseOrder(orderId, principal));
    }

    @PostMapping("/{orderId}/cancel-request")
    public SuccessEnvelope<PurchaseOrderResponse> cancelRequest(
            @PathVariable Long orderId,
            @RequestBody CancelReasonBody body,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.requestCancelOrder(orderId, body.reason(), principal));
    }

    @PostMapping("/{orderId}/cancel-approve")
    public SuccessEnvelope<PurchaseOrderResponse> cancelApprove(
            @PathVariable Long orderId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.approveCancelRequest(orderId, principal));
    }

    @PostMapping("/{orderId}/cancel-reject")
    public SuccessEnvelope<PurchaseOrderResponse> cancelReject(
            @PathVariable Long orderId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.rejectCancelRequest(orderId, principal));
    }

    @PostMapping("/{orderId}/cancel-withdraw")
    public SuccessEnvelope<PurchaseOrderResponse> cancelWithdraw(
            @PathVariable Long orderId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.withdrawCancelRequest(orderId, principal));
    }

    public record CancelReasonBody(String reason) {
    }
}