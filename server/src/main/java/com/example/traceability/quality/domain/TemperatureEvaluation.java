package com.example.traceability.quality.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 单条温度测量的单点判定结果（契约 v1.1 §2.9 / §10.1 / §15.9）。
 * <p>
 * 只说明<b>这一次</b>测量是否落在测量时适用的运输温控规则范围内；单点越界不等于持续超温，
 * 不产生 Alert，也不改变任何批次风险状态。上下限本身属于范围内。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum TemperatureEvaluation {

    /** 单点在规则范围内（lower ≤ t ≤ upper）。 */
    NORMAL,

    /** 单点高于规则上限（t &gt; upper）。 */
    HIGH,

    /** 单点低于规则下限（t &lt; lower）。 */
    LOW,

    /** 测量时没有唯一适用的运输温控规则环节，未判定。 */
    MISSING_CONTEXT;

    /**
     * 按匹配规则环节的上下限快照判定单点结果。
     *
     * @param temperature 测量温度（摄氏度）
     * @param lowerLimit  规则下限
     * @param upperLimit  规则上限
     * @return NORMAL / HIGH / LOW（从不返回 MISSING_CONTEXT）
     */
    public static TemperatureEvaluation classify(BigDecimal temperature, BigDecimal lowerLimit, BigDecimal upperLimit) {
        Objects.requireNonNull(temperature, "temperature 不能为空");
        Objects.requireNonNull(lowerLimit, "lowerLimit 不能为空");
        Objects.requireNonNull(upperLimit, "upperLimit 不能为空");
        if (lowerLimit.compareTo(upperLimit) > 0) {
            throw new IllegalArgumentException("规则下限不得高于上限: " + lowerLimit + " > " + upperLimit);
        }
        if (temperature.compareTo(upperLimit) > 0) {
            return HIGH;
        }
        if (temperature.compareTo(lowerLimit) < 0) {
            return LOW;
        }
        return NORMAL;
    }
}
