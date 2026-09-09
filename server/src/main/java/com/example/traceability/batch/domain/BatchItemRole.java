package com.example.traceability.batch.domain;

/**
 * 批次操作明细项目角色枚举。
 * <p>
 * 项目角色类型：
 * <ul>
 *   <li>{@code INPUT}：投入批次（必须绑定 batchId）</li>
 *   <li>{@code OUTPUT}：产出批次（必须绑定 batchId）</li>
 *   <li>{@code LOSS}：工艺损耗（不得绑定 batchId）</li>
 *   <li>{@code WASTE}：废弃物（不得绑定 batchId）</li>
 *   <li>{@code SAMPLE}：留检样品（不得绑定 batchId）</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum BatchItemRole {
    INPUT,
    OUTPUT,
    LOSS,
    WASTE,
    SAMPLE;

    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        for (BatchItemRole role : values()) {
            if (role.name().equalsIgnoreCase(code.trim())) {
                return true;
            }
        }
        return false;
    }

    public static BatchItemRole fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (BatchItemRole role : values()) {
            if (role.name().equalsIgnoreCase(code.trim())) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知的项目角色: " + code);
    }
}
