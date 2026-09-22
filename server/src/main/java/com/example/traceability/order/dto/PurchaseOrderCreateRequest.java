package com.example.traceability.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购进货单创建请求。
 */
public record PurchaseOrderCreateRequest(
        @NotNull(message = "供货方 sellerOrgId 不能为空")
        Long sellerOrgId,

        String orderType,

        LocalDateTime expectedDeliveryAt,

        String note,

        String buyerContactName,
        String buyerContactPhone,
        String buyerContactAddress,

        @Valid
        @NotEmpty(message = "采购明细不能为空")
        List<PurchaseItem> items
) {

    public record PurchaseItem(
            @NotNull(message = "商品 productId 不能为空")
            Long productId,

            @NotNull(message = "数量 quantity 不能为空")
            @DecimalMin(value = "0.001", message = "数量必须大于 0")
            BigDecimal quantity,

            String unitCode,

            @NotNull(message = "单价 unitPrice 不能为空")
            @DecimalMin(value = "0", message = "单价不能为负")
            BigDecimal unitPrice
    ) {
    }
}