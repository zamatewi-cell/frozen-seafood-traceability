package com.example.traceability.batch.domain;

/**
 * 批次操作状态枚举。
 * <p>
 * 状态机：
 * <ul>
 *   <li>{@code DRAFT}：草稿状态（允许提交）</li>
 *   <li>{@code SUBMITTED}：已提交状态（物料平衡已校验，谱系边已生成，不可变）</li>
 *   <li>{@code CORRECTED}：已更正状态（留痕审计用）</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum BatchOperationStatus {
    DRAFT,
    SUBMITTED,
    CORRECTED;

    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        for (BatchOperationStatus status : values()) {
            if (status.name().equalsIgnoreCase(code.trim())) {
                return true;
            }
        }
        return false;
    }

    public static BatchOperationStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (BatchOperationStatus status : values()) {
            if (status.name().equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的批次操作状态: " + code);
    }
}
