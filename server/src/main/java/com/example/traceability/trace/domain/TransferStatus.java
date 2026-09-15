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
     * 待处理状态 (发货已锁定，待接收方接受或拒收)。
     */
    PENDING,

    /**
     * 已接受终态 (已转移持有权，已写入 ARRIVAL 追溯事件，只读)。
     */
    ACCEPTED,

    /**
     * 已拒收终态 (未转移持有权，已登记拒收原因，只读)。
     */
    REJECTED
}
