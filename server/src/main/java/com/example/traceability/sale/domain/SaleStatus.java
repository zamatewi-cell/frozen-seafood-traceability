package com.example.traceability.sale.domain;

/**
 * 终端销售状态。
 * <p>
 * 统一业务契约 v1.1 未定义销售撤销，Sale 台账追加式不可修改，Phase A 仅有 {@code SUBMITTED}。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum SaleStatus {
    SUBMITTED
}
