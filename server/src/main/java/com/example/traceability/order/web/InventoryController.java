package com.example.traceability.order.web;

import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import com.example.traceability.order.application.InventoryApplicationService;
import com.example.traceability.order.dto.InventoryBatchResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 库存查询控制器。
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryApplicationService inventoryService;

    public InventoryController(InventoryApplicationService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/my-batches")
    public SuccessEnvelope<List<InventoryBatchResponse>> listMyBatches(
            @AuthenticationPrincipal TraceSecurityPrincipal principal) {
        return SuccessEnvelope.of(inventoryService.listMyInventory(principal));
    }
}
