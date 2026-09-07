package com.example.traceability.masterdata.domain;

/**
 * 水产大类枚举。
 * <p>
 * 遵循数据库与数据字典规范：FISH/CRUSTACEAN/SHELLFISH/CEPHALOPOD/OTHER。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum ProductCategory {

    /**
     * 海水鱼类 (如三文鱼、大黄鱼、带鱼、金枪鱼等)
     */
    FISH("FISH", "海水鱼类"),

    /**
     * 甲壳类 (如南美白对虾、黑虎虾、帝王蟹、梭子蟹等)
     */
    CRUSTACEAN("CRUSTACEAN", "甲壳虾蟹类"),

    /**
     * 贝类 (如扇贝、生蚝、鲍鱼、贻贝等)
     */
    SHELLFISH("SHELLFISH", "贝类"),

    /**
     * 头足类 (如鱿鱼、章鱼、墨鱼等)
     */
    CEPHALOPOD("CEPHALOPOD", "头足软体类"),

    /**
     * 其它水产品大类
     */
    OTHER("OTHER", "其它水产品");

    private final String code;
    private final String description;

    ProductCategory(String code, String description) {
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
        for (ProductCategory cat : values()) {
            if (cat.name().equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    public static ProductCategory fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ProductCategory cat : values()) {
            if (cat.name().equalsIgnoreCase(code)) {
                return cat;
            }
        }
        throw new IllegalArgumentException("未知的水产大类代码: " + code);
    }
}
