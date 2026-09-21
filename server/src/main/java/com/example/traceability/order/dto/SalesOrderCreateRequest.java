package com.example.traceability.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 销售/客户订单创建请求（当前登录组织为销售方）。
 */
public record SalesOrderCreateRequest(
        @NotBlank(message = "客户称谓 customerName 不能为空")
        String customerName,

        String customerPhone,

        @NotBlank(message = "收货地址 deliveryAddress 不能为空")
        String deliveryAddress,

        String note,

        @Valid
        @NotEmpty(message = "商品明细不能为空")
        List<SalesItem> items
) {

    public record SalesItem(
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