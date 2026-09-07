package com.example.traceability.masterdata.domain;

/**
 * 冷链环节代码枚举。
 * <p>
 * 规则环节支持：PROCESSING（加工速冻环节）、STORAGE（冷库仓储环节）、TRANSPORT（冷链运输环节）、RETAIL（终端零售环节）。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum RuleStageCode {

    /**
     * 加工与速冻环节 (PROCESSING)
     */
    PROCESSING("PROCESSING", "加工速冻环节"),

    /**
     * 仓储冷库环节 (STORAGE)
     */
    STORAGE("STORAGE", "仓储冷库环节"),

    /**
     * 冷链物流运输环节 (TRANSPORT)
     */
    TRANSPORT("TRANSPORT", "冷链运输环节"),

    /**
     * 商超终端零售环节 (RETAIL)
     */
    RETAIL("RETAIL", "商超零售环节");

    private final String code;
    private final String description;

    RuleStageCode(String code, String description) {
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
        for (RuleStageCode sc : values()) {
            if (sc.name().equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    public static RuleStageCode fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (RuleStageCode sc : values()) {
            if (sc.name().equalsIgnoreCase(code)) {
                return sc;
            }
        }
        throw new IllegalArgumentException("未知的冷链环节代码: " + code);
    }
}
