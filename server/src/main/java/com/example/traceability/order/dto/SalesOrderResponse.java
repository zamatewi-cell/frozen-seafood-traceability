package com.example.traceability.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售/客户订单响应对象。
 */
public record SalesOrderResponse(
        Long id,
        String orderNo,
        Long sellerOrgId,
        Long buyerOrgId,
        String customerName,
        String customerPhone,
        String deliveryAddress,
        String status,
        Long traceCodeId,
        String packedPackageNo,
        LocalDateTime placedAt,
        LocalDateTime deliveredAt,
        String note,
        String cancelRequestRole,
        String cancelRequestReason,
        String cancelRequestStatus,
        BigDecimal amountTotal,
        String currencyCode,
        List<SalesItem> items
) {

    public record SalesItem(
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