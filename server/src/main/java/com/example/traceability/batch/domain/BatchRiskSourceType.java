package com.example.traceability.batch.domain;

/**
 * 批次风险状态转换来源类型（与最新迁移中的 {@code chk_brt_source_type} 严格一致）。
 * <ul>
 *   <li>{@code MANUAL}（PB1，V12）：当前责任组织质量管理员（QUALITY_MANAGER）人工冻结 / 解除冻结，必须记录操作人；</li>
 *   <li>{@code ALERT}（PB3 / PB4，V14 / V15）：在途持续超温告警创建时系统自动冻结受影响批次（无操作人），以及告警归属组织的
 *       质量管理员依据检验结论放行（有操作人）；类型化来源外键 {@code source_alert_id}；</li>
 *   <li>{@code RECALL}（PB5，V16）：模拟召回把批次转为风险终态 RECALLED（NORMAL / FROZEN → RECALLED，必须有操作人），
 *       类型化来源外键 {@code source_recall_id}；只有该来源可以写入 RECALLED。</li>
 * </ul>
 * 新来源必须与数据库约束同时扩展，且各自使用类型化来源外键，而不是多态引用。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum BatchRiskSourceType {
    MANUAL,
    ALERT,
    RECALL
}
