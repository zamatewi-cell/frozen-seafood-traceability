package com.example.traceability.trace.domain;

/**
 * 公开追溯码生命周期状态枚举。
 * <p>
 * 区分内部追溯码生命周期状态 (ACTIVE/DISABLED/RECALLED) 与面向消费者的批次状态 (batchStatus)。
 * 一旦置为 DISABLED 即为终态，严禁再激活或轮换。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum PublicTraceCodeStatus {

    /**
     * 正常生效中，消费者可通过公开端点正常查验。
     */
    ACTIVE,

    /**
     * 已停用（终态），对外部消费者查询表现为 404 PUBLIC_TRACE_NOT_FOUND，物理记录保留不可篡改。
     */
    DISABLED,

    /**
     * 召回标记态。
     */
    RECALLED
}
