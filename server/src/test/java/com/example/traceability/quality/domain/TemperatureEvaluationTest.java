package com.example.traceability.quality.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("单点判定：上下限本身属于范围内，越界 0.01 即为 HIGH / LOW")
class TemperatureEvaluationTest {

    private static final BigDecimal LOWER = new BigDecimal("-25.00");
    private static final BigDecimal UPPER = new BigDecimal("-15.00");

    private static TemperatureEvaluation classify(String t) {
        return TemperatureEvaluation.classify(new BigDecimal(t), LOWER, UPPER);
    }

    @Test
    @DisplayName("边界：等于下限 / 上限为 NORMAL；上限 + 0.01 为 HIGH；下限 - 0.01 为 LOW")
    void boundaries() {
        assertThat(classify("-25.00")).isEqualTo(TemperatureEvaluation.NORMAL);
        assertThat(classify("-15.00")).isEqualTo(TemperatureEvaluation.NORMAL);
        assertThat(classify("-15")).as("scale does not matter").isEqualTo(TemperatureEvaluation.NORMAL);
        assertThat(classify("-18.50")).isEqualTo(TemperatureEvaluation.NORMAL);
        assertThat(classify("-14.99")).isEqualTo(TemperatureEvaluation.HIGH);
        assertThat(classify("-25.01")).isEqualTo(TemperatureEvaluation.LOW);
        assertThat(classify("4.00")).isEqualTo(TemperatureEvaluation.HIGH);
    }

    @Test
    @DisplayName("零宽规则（上下限相等）：只有恰好等于该值为 NORMAL")
    void zeroWidthBand() {
        BigDecimal v = new BigDecimal("-18.00");
        assertThat(TemperatureEvaluation.classify(new BigDecimal("-18.00"), v, v)).isEqualTo(TemperatureEvaluation.NORMAL);
        assertThat(TemperatureEvaluation.classify(new BigDecimal("-17.99"), v, v)).isEqualTo(TemperatureEvaluation.HIGH);
        assertThat(TemperatureEvaluation.classify(new BigDecimal("-18.01"), v, v)).isEqualTo(TemperatureEvaluation.LOW);
    }

    @Test
    @DisplayName("判定从不返回 MISSING_CONTEXT；上下限颠倒或缺失时拒绝判定")
    void neverMissingContextAndRejectsInvalidBand() {
        for (String t : new String[]{"-80.00", "-25.00", "-20.00", "-15.00", "60.00"}) {
            assertThat(classify(t)).isNotEqualTo(TemperatureEvaluation.MISSING_CONTEXT);
        }
        assertThatThrownBy(() -> TemperatureEvaluation.classify(new BigDecimal("-18"), UPPER, LOWER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemperatureEvaluation.classify(new BigDecimal("-18"), null, UPPER))
                .isInstanceOf(NullPointerException.class);
    }
}
