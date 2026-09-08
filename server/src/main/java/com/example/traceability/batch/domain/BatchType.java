package com.example.traceability.batch.domain;

/**
 * 批次环节类型枚举。
 * <p>
 * 定义水产批次的生产流通环节：
 * <ul>
 *   <li>{@code SOURCE}：初级原料/来源捕捞/出塘批次</li>
 *   <li>{@code PROCESSING}：加工速冻成品批次</li>
 *   <li>{@code DISTRIBUTION}：仓储物流/分销批次</li>
 *   <li>{@code SALE}：终端销售/零售批次</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum BatchType {
    SOURCE,
    PROCESSING,
    DISTRIBUTION,
    SALE;

    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        for (BatchType type : values()) {
            if (type.name().equalsIgnoreCase(code.trim())) {
                return true;
            }
        }
        return false;
    }

    public static BatchType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (BatchType type : values()) {
            if (type.name().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的批次类型: " + code);
    }
}
