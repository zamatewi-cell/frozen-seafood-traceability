package com.example.traceability.batch.domain;

/**
 * 批次谱系父子关系类型枚举。
 * <p>
 * 映射规则：
 * <ul>
 *   <li>{@code MERGE}：由 MERGE 操作生成（多对一或多对多合并）</li>
 *   <li>{@code SPLIT}：由 SPLIT 操作生成（一对多或多对多拆分）</li>
 *   <li>{@code TRANSFORM}：由 PROCESS 或 REPACK 操作生成（加工或重新包装转换）</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum BatchRelationType {
    TRANSFORM,
    SPLIT,
    MERGE;

    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        for (BatchRelationType type : values()) {
            if (type.name().equalsIgnoreCase(code.trim())) {
                return true;
            }
        }
        return false;
    }

    public static BatchRelationType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (BatchRelationType type : values()) {
            if (type.name().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的谱系关系类型: " + code);
    }
}
