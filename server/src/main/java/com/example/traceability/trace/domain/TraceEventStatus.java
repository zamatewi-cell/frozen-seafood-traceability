package com.example.traceability.trace.domain;

import java.util.Arrays;

/**
 * 追溯事件状态枚举。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum TraceEventStatus {

    /**
     * 已提交有效版本（当前生效版本）。
     */
    SUBMITTED,

    /**
     * 已更正历史版本（已被后续更正版本修正，仍保留只读追溯历史）。
     */
    CORRECTED;

    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String clean = code.trim().toUpperCase();
        return Arrays.stream(values()).anyMatch(e -> e.name().equals(clean));
    }

    public static TraceEventStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("事件状态不能为空");
        }
        return valueOf(code.trim().toUpperCase());
    }
}
