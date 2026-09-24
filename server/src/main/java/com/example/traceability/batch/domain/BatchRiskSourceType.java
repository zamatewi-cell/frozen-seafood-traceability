package com.example.traceability.batch.domain;

/**
 * 批次风险状态转换来源类型（与 V12 {@code chk_brt_source_type} 严格一致）。
 * <p>
 * PB1 只有 {@code MANUAL}：当前责任组织质量管理员（QUALITY_MANAGER）人工冻结 / 解除冻结，必须记录操作人。
 * 后续来源必须与数据库约束同时扩展，且各自使用类型化来源外键，而不是多态引用：
 * <ul>
 *   <li>PB3：{@code ALERT}（系统路径，Alert 作为结构化来源，新增 {@code source_alert_id} 外键）；</li>
 *   <li>PB5：{@code RECALL}（新增 {@code source_recall_id} 外键）。</li>
 * </ul>
 * 在对应迁移落地之前不得提前加入可执行枚举值。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum BatchRiskSourceType {
    MANUAL
}
