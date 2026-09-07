package com.example.traceability.masterdata.domain;

/**
 * 水产品来源类型枚举。
 * <p>
 * 遵循数据字典规范：DOMESTIC_CAPTURE（国内捕捞）、DOMESTIC_FARMED（国内养殖）、IMPORT（进口）。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum ProductSourceType {

    /**
     * 国内海洋捕捞
     */
    DOMESTIC_CAPTURE("DOMESTIC_CAPTURE", "国内捕捞"),

    /**
     * 国内海水养殖
     */
    DOMESTIC_FARMED("DOMESTIC_FARMED", "国内养殖"),

    /**
     * 境外进口入关
     */
    IMPORT("IMPORT", "进口");

    private final String code;
    private final String description;

    ProductSourceType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static boolean isValid(String code) {
        if (code == null) {
            return false;
        }
        for (ProductSourceType st : values()) {
            if (st.name().equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    public static ProductSourceType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ProductSourceType st : values()) {
            if (st.name().equalsIgnoreCase(code)) {
                return st;
            }
        }
        throw new IllegalArgumentException("未知的水产品来源类型代码: " + code);
    }
}
