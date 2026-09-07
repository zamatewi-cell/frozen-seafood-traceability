package com.example.traceability.masterdata.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("温控规则半开区间重叠判定领域算法测试")
class TemperatureRuleIntervalValidatorTest {

    @Test
    @DisplayName("严格相邻区间 [A, B) 与 [B, C) 互不重叠，允许平滑接续")
    void adjacentIntervalsShouldNotOverlap() {
        LocalDateTime t1 = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 6, 1, 0, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 12, 1, 0, 0);

        // [t1, t2) 与 [t2, t3)
        boolean overlap1 = TemperatureRuleIntervalValidator.isOverlapping(t1, t2, t2, t3);
        assertThat(overlap1).isFalse();

        // 反向顺序对称性校验：[t2, t3) 与 [t1, t2)
        boolean overlap2 = TemperatureRuleIntervalValidator.isOverlapping(t2, t3, t1, t2);
        assertThat(overlap2).isFalse();
    }

    @Test
    @DisplayName("交错重叠区间应判定为重叠冲突")
    void intersectingIntervalsShouldOverlap() {
        LocalDateTime t1 = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 7, 1, 0, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 6, 1, 0, 0);
        LocalDateTime t4 = LocalDateTime.of(2026, 12, 1, 0, 0);

        // [2026-01-01, 2026-07-01) 与 [2026-06-01, 2026-12-01)
        boolean overlap = TemperatureRuleIntervalValidator.isOverlapping(t1, t2, t3, t4);
        assertThat(overlap).isTrue();
    }

    @Test
    @DisplayName("包含关系区间应判定为重叠冲突")
    void containedIntervalsShouldOverlap() {
        LocalDateTime outerFrom = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime outerTo = LocalDateTime.of(2026, 12, 31, 23, 59);
        LocalDateTime innerFrom = LocalDateTime.of(2026, 3, 1, 0, 0);
        LocalDateTime innerTo = LocalDateTime.of(2026, 6, 1, 0, 0);

        boolean overlap = TemperatureRuleIntervalValidator.isOverlapping(outerFrom, outerTo, innerFrom, innerTo);
        assertThat(overlap).isTrue();
    }

    @Test
    @DisplayName("长期有效正无穷区间 [from, null) 的重叠判定")
    void openEndedIntervalShouldOverlapCorrectly() {
        LocalDateTime t1 = LocalDateTime.of(2026, 6, 1, 0, 0);
        // 规则A: 2026-06-01 至正无穷 [t1, null)

        // 规则B: [2026-01-01, 2026-06-01) 严格早于且相邻，不应重叠
        LocalDateTime bFrom = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime bTo = LocalDateTime.of(2026, 6, 1, 0, 0);
        assertThat(TemperatureRuleIntervalValidator.isOverlapping(t1, null, bFrom, bTo)).isFalse();

        // 规则C: [2026-01-01, 2026-06-02) 晚于 t1，应判定重叠
        LocalDateTime cTo = LocalDateTime.of(2026, 6, 2, 0, 0);
        assertThat(TemperatureRuleIntervalValidator.isOverlapping(t1, null, bFrom, cTo)).isTrue();

        // 规则D: [2026-08-01, 2026-10-01) 处于正无穷区间内部，应判定重叠
        LocalDateTime dFrom = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime dTo = LocalDateTime.of(2026, 10, 1, 0, 0);
        assertThat(TemperatureRuleIntervalValidator.isOverlapping(t1, null, dFrom, dTo)).isTrue();

        // 规则E: 两个皆为正无穷 [2026-06-01, null) 与 [2026-08-01, null) 必然重叠
        assertThat(TemperatureRuleIntervalValidator.isOverlapping(t1, null, dFrom, null)).isTrue();
    }

    @Test
    @DisplayName("起始时间 effectiveFrom 为 null 时必须抛出异常")
    void nullEffectiveFromShouldThrowException() {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertThatThrownBy(() -> TemperatureRuleIntervalValidator.isOverlapping(null, t, t, t))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemperatureRuleIntervalValidator.isOverlapping(t, t, null, t))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
