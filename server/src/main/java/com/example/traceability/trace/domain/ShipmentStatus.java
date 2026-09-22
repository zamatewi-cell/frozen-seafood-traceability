package com.example.traceability.trace.domain;

/**
 * 冷链运输任务生命周期状态枚举。
 * <p>
 * 状态机：PLANNED → IN_TRANSIT → DELIVERED；PLANNED → CANCELLED (统一业务契约 v1.1 §7.3)。
 * Shipment 状态变化不自动修改 Transfer 状态；DELIVERED 不代表接收方已经验收。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
public enum ShipmentStatus {
    /**
     * 已计划：发货方可绑定/解绑 DRAFT Transfer 并提交 Transfer。
     */
    PLANNED,

    /**
     * 运输中：承运商已确认装载发运，装载清单冻结；为每个 Batch 自动生成一条 TRANSPORT。
     */
    IN_TRANSIT,

    /**
     * 已到达：承运商已确认物理到达；为每个 Batch 自动生成一条 ARRIVAL，Transfer 仍待接收方决定。
     */
    DELIVERED,

    /**
     * 已取消：仅允许从 PLANNED 取消，且所有绑定 Transfer 仍为 DRAFT。
     */
    CANCELLED
}
