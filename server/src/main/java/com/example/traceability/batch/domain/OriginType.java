package com.example.traceability.batch.domain;

/**
 * 批次水产品来源类型枚举。
 * <p>
 * 规范枚举代码（与 Phase 1 数据字典及 OpenAPI 契约对齐）：
 * <ul>
 *   <li>{@code DOMESTIC_CAPTURE}：国内捕捞</li>
 *   <li>{@code DOMESTIC_FARMED}：国内养殖</li>
 *   <li>{@code IMPORT}：进口海产</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum OriginType {
    DOMESTIC_CAPTURE,
    DOMESTIC_FARMED,
    IMPORT;

    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        for (OriginType type : values()) {
            if (type.name().equalsIgnoreCase(code.trim())) {
                return true;
            }
        }
        return false;
    }

    public static OriginType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (OriginType type : values()) {
            if (type.name().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的来源类型: " + code);
    }
}
