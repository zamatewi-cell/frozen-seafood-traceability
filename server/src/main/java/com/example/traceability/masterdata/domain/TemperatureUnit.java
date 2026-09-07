package com.example.traceability.masterdata.domain;

/**
 * 温度单位枚举。
 * <p>
 * 冷冻海产品冷链追溯系统强制统一采用摄氏度（CELSIUS），杜绝单位混淆与浮点歧义。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum TemperatureUnit {

    /**
     * 摄氏度 (Celsius, ℃)
     */
    CELSIUS("CELSIUS", "摄氏度");

    private final String code;
    private final String description;

    TemperatureUnit(String code, String description) {
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
        for (TemperatureUnit u : values()) {
            if (u.name().equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    public static TemperatureUnit fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (TemperatureUnit u : values()) {
            if (u.name().equalsIgnoreCase(code)) {
                return u;
            }
        }
        throw new IllegalArgumentException("不支持的温标单位代码: " + code);
    }
}
