package com.example.traceability.batch.domain;

/**
 * 批次流转状态枚举。
 * <p>
 * 表达水产批次在生命周期中的业务流转阶段：
 * <ul>
 *   <li>{@code DRAFT}：草稿状态（允许编辑、提交）</li>
 *   <li>{@code ACTIVE}：正常生效流通状态（可记追溯事件、交接、拆合加工）</li>
 *   <li>{@code CLOSED}：归档关闭状态（批次结束流转）</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum BatchFlowStatus {
    DRAFT,
    ACTIVE,
    CLOSED;

    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        for (BatchFlowStatus status : values()) {
            if (status.name().equalsIgnoreCase(code.trim())) {
                return true;
            }
        }
        return false;
    }

    public static BatchFlowStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (BatchFlowStatus status : values()) {
            if (status.name().equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的批次流转状态: " + code);
    }
}
