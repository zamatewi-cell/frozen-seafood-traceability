package com.example.traceability.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购进货单响应对象。
 */
public record PurchaseOrderResponse(
        Long id,
        String orderNo,
        Long buyerOrgId,
        Long sellerOrgId,
        String orderType,
        String status,
        LocalDateTime orderedAt,
        LocalDateTime expectedDeliveryAt,
        String note,
        BigDecimal amountTotal,
        String currencyCode,
        List<PurchaseItem> items
) {

    public record PurchaseItem(
            Long id,
            Long productId,
            String productName,
            BigDecimal quantity,
            String unitCode,
            BigDecimal unitPrice,
            BigDecimal rowAmount
    ) {
    }
}