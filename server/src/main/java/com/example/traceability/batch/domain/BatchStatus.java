package com.example.traceability.batch.domain;

/**
 * 批次生命周期状态枚举。
 * <p>
 * 状态机流转：
 * <ul>
 *   <li>{@code DRAFT}：草稿状态（允许编辑、删除、提交）</li>
 *   <li>{@code ACTIVE}：正常生效流通状态（可记追溯事件、交接、拆合加工）</li>
 *   <li>{@code FROZEN}：质量冻结状态（停止发货、流转，待调查）</li>
 *   <li>{@code RECALLED}：召回状态（启动模拟/紧急召回）</li>
 *   <li>{@code CLOSED}：归档关闭状态</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum BatchStatus {
    DRAFT,
    ACTIVE,
    FROZEN,
    RECALLED,
    CLOSED;

    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        for (BatchStatus status : values()) {
            if (status.name().equalsIgnoreCase(code.trim())) {
                return true;
            }
        }
        return false;
    }

    public static BatchStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (BatchStatus status : values()) {
            if (status.name().equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的批次状态: " + code);
    }
}
