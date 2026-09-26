package com.example.traceability.quality.domain;

/**
 * 告警处置状态：OPEN（系统创建，待确认）→ ACKNOWLEDGED（当前责任组织质量管理员确认异常并承担处置）→ RESOLVED（处置结论）。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum AlertStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED
}
