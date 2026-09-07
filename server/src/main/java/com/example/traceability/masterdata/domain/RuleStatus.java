package com.example.traceability.masterdata.domain;

/**
 * 温控基准规则状态枚举。
 * <p>
 * 状态机流转：DRAFT（草稿） -> ACTIVE（已发布生效） -> RETIRED（已废弃/历史版本）。
 * 一旦发布生效，规则即不可修改且物理保留，作为不可篡改的温控审计基准。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum RuleStatus {

    /**
     * 草稿状态：新建规则方案，尚未发布，阶段阈值可调整。
     */
    DRAFT("DRAFT", "草稿"),

    /**
     * 已发布生效：受保护只读状态，作为各业务环节温度合规判定的当前基准。
     */
    ACTIVE("ACTIVE", "生效"),

    /**
     * 已废弃/历史版本：新版本规则发布后或主动退役的历史归档规则。
     */
    RETIRED("RETIRED", "已废弃");

    private final String code;
    private final String description;

    RuleStatus(String code, String description) {
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
        for (RuleStatus rs : values()) {
            if (rs.name().equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    public static RuleStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (RuleStatus rs : values()) {
            if (rs.name().equalsIgnoreCase(code)) {
                return rs;
            }
        }
        throw new IllegalArgumentException("未知的规则状态代码: " + code);
    }
}
