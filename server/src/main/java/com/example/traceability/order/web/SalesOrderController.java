package com.example.traceability.order.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.order.application.OrderApplicationService;
import com.example.traceability.order.dto.OrderStatusUpdateRequest;
import com.example.traceability.order.dto.SalesOrderCreateRequest;
import com.example.traceability.order.dto.SalesOrderResponse;
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
 * 销售/客户订单控制器（B2C）。
 */
@RestController
@RequestMapping("/api/v1/orders/sales")
public class SalesOrderController {

    private final OrderApplicationService orderService;

    public SalesOrderController(OrderApplicationService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<SuccessEnvelope<SalesOrderResponse>> create(
            @Valid @RequestBody SalesOrderCreateRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessEnvelope.of(orderService.createSalesOrder(request, principal)));
    }

    @GetMapping
    public SuccessEnvelope<List<SalesOrderResponse>> list(
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.listSalesOrders(principal));
    }

    @GetMapping("/{orderId}")
    public SuccessEnvelope<SalesOrderResponse> get(
            @PathVariable Long orderId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.getSalesOrder(orderId, principal));
    }

    @PostMapping("/{orderId}/status")
    public SuccessEnvelope<SalesOrderResponse> updateStatus(
            @PathVariable Long orderId,
            @Valid @RequestBody OrderStatusUpdateRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(orderService.updateSalesStatus(orderId, request, principal));
    }
}