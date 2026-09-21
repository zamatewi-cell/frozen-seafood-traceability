package com.example.traceability.batch.domain;

/**
 * 批次质量与风险状态枚举。
 * <p>
 * 表达水产批次的质量合规与风控处置状态：
 * <ul>
 *   <li>{@code NORMAL}：正常状态（质量合格，允许流转与出入库）</li>
 *   <li>{@code FROZEN}：质量冻结状态（停止发货、流转，待调查）</li>
 *   <li>{@code RECALLED}：召回状态（启动模拟/紧急召回处置）</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum BatchRiskStatus {
    NORMAL,
    FROZEN,
    RECALLED;

    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        for (BatchRiskStatus status : values()) {
            if (status.name().equalsIgnoreCase(code.trim())) {
                return true;
            }
        }
        return false;
    }

    public static BatchRiskStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (BatchRiskStatus status : values()) {
            if (status.name().equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的批次风险状态: " + code);
    }
}
