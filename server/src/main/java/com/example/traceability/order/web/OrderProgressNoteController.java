package com.example.traceability.order.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.order.application.OrderProgressNoteService;
import com.example.traceability.order.dto.OrderNoteCreateRequest;
import com.example.traceability.order.dto.OrderNoteResponse;
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
 * 订单进度备注控制器。
 */
@RestController
@RequestMapping("/api/v1/orders/{orderType}/{orderId}/notes")
public class OrderProgressNoteController {

    private final OrderProgressNoteService noteService;

    public OrderProgressNoteController(OrderProgressNoteService noteService) {
        this.noteService = noteService;
    }

    @PostMapping
    public ResponseEntity<SuccessEnvelope<OrderNoteResponse>> addNote(
            @PathVariable String orderType,
            @PathVariable Long orderId,
            @Valid @RequestBody OrderNoteCreateRequest request,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessEnvelope.of(noteService.addNote(orderType, orderId, request, principal)));
    }

    @GetMapping
    public SuccessEnvelope<List<OrderNoteResponse>> listNotes(
            @PathVariable String orderType,
            @PathVariable Long orderId,
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(noteService.listNotes(orderType, orderId, principal));
    }
}
