package com.example.traceability.batch.domain;

/**
 * 批次操作业务类型枚举。
 * <p>
 * 支持的操作类型：
 * <ul>
 *   <li>{@code MERGE}：批次合并（至少 2 个 INPUT，至少 1 个 OUTPUT）</li>
 *   <li>{@code SPLIT}：批次拆分（至少 1 个 INPUT，至少 2 个 OUTPUT）</li>
 *   <li>{@code PROCESS}：批次加工（至少 1 个 INPUT，至少 1 个 OUTPUT）</li>
 *   <li>{@code REPACK}：批次分装（至少 1 个 INPUT，至少 1 个 OUTPUT）</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum BatchOperationType {
    MERGE,
    SPLIT,
    PROCESS,
    REPACK;

    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        for (BatchOperationType type : values()) {
            if (type.name().equalsIgnoreCase(code.trim())) {
                return true;
            }
        }
        return false;
    }

    public static BatchOperationType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (BatchOperationType type : values()) {
            if (type.name().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的批次操作类型: " + code);
    }
}
