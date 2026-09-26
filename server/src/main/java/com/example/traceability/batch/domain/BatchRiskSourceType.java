package com.example.traceability.batch.domain;

/**
 * 批次风险状态转换来源类型（与最新迁移中的 {@code chk_brt_source_type} 严格一致）。
 * <ul>
 *   <li>{@code MANUAL}（PB1，V12）：当前责任组织质量管理员（QUALITY_MANAGER）人工冻结 / 解除冻结，必须记录操作人；</li>
 *   <li>{@code ALERT}（PB3，V14）：在途持续超温告警创建时系统自动冻结受影响批次，类型化来源外键 {@code source_alert_id}，
 *       无操作人。</li>
 * </ul>
 * 后续来源（PB5：{@code RECALL}，{@code source_recall_id}）必须与数据库约束同时扩展，且各自使用类型化来源外键，
 * 而不是多态引用；在对应迁移落地之前不得提前加入可执行枚举值。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum BatchRiskSourceType {
    MANUAL,
    ALERT
}
