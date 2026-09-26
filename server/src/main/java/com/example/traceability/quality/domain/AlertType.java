package com.example.traceability.quality.domain;

/**
 * 告警类型（Phase B PB3 只承载 Shipment 级在途持续超温）。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum AlertType {

    /** 连续高于规则上限达到允许时长。 */
    TEMP_OVER_UPPER,

    /** 连续低于规则下限达到允许时长。 */
    TEMP_UNDER_LOWER;

    public static AlertType of(TemperatureEvaluation direction) {
        return switch (direction) {
            case HIGH -> TEMP_OVER_UPPER;
            case LOW -> TEMP_UNDER_LOWER;
            default -> throw new IllegalArgumentException("只有越界方向可以形成告警: " + direction);
        };
    }
}
