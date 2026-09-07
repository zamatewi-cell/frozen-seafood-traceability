package com.example.traceability.masterdata.domain;

/**
 * 产品状态枚举。
 * <p>
 * 至少包含 DRAFT（草稿）、ACTIVE（启用生效）、INACTIVE（停用）。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum ProductStatus {

    /**
     * 草稿状态：新创建的产品主数据，尚未正式投入业务流转。
     */
    DRAFT("DRAFT", "草稿"),

    /**
     * 正常启用状态：可在批次建档与规则关联中使用。
     */
    ACTIVE("ACTIVE", "启用"),

    /**
     * 已停用状态：禁止在其下发布温控规则，也不得在后续业务中被引用建批。
     */
    INACTIVE("INACTIVE", "停用");

    private final String code;
    private final String description;

    ProductStatus(String code, String description) {
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
        for (ProductStatus status : values()) {
            if (status.name().equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    public static ProductStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ProductStatus status : values()) {
            if (status.name().equalsIgnoreCase(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的产品状态代码: " + code);
    }
}
