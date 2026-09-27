package com.example.traceability.trace.domain;

/**
 * 企业间整批交接生命周期状态枚举。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum TransferStatus {
    /**
     * 草稿状态 (仅发送方可见可修改可逻辑删除可提交)。
     */
    DRAFT,

    /**
     * 待处理状态 (已绑定 PLANNED Shipment 并由发送方提交，待 Shipment 到达后由接收方接受或拒收)。
     */
    PENDING,

    /**
     * 隔离收货状态 (Phase B PB4；契约 v1.1 §7.3 / §10.3)：货物已物理到达并登记实收数量与隔离场所，批次当前责任组织仍为发送方；
     * 接收方不得加工、销售或再次交接，只能依据质量结论 ACCEPT（批次风险恢复正常后）或 REJECT。仍属未结束交接。
     */
    QUARANTINED,

    /**
     * 已接受终态 (前提为关联 Shipment 已 DELIVERED；已转移当前责任组织，不生成 ARRIVAL 追溯事件，只读)。
     */
    ACCEPTED,

    /**
     * 已拒收终态 (未转移持有权，已登记拒收原因，只读)。
     */
    REJECTED
}
