package com.example.traceability.order.domain;

/**
 * 订单状态常量。
 * <p>采购进货单（B2B）与销售订单（B2C）分别使用各自的状态机。</p>
 */
public final class OrderStatus {

    private OrderStatus() {
    }

    // 采购进货单状态
    public static final String PURCHASE_SUBMITTED = "SUBMITTED";
    public static final String PURCHASE_CONFIRMED = "CONFIRMED";
    public static final String PURCHASE_PROCESSING = "PROCESSING";
    public static final String PURCHASE_SHIPPED = "SHIPPED";
    public static final String PURCHASE_RECEIVED = "RECEIVED";
    public static final String PURCHASE_CANCELLED = "CANCELLED";

    // 销售订单状态
    public static final String SALES_PLACED = "PLACED";
    public static final String SALES_CONFIRMED = "CONFIRMED";
    public static final String SALES_PROCESSING = "PROCESSING";
    public static final String SALES_SHIPPED = "SHIPPED";
    public static final String SALES_DELIVERED = "DELIVERED";
    public static final String SALES_CANCELLED = "CANCELLED";
}