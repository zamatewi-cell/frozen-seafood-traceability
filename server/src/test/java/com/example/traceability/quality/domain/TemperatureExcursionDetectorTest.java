package com.example.traceability.quality.domain;

import com.example.traceability.quality.domain.TemperatureExcursionDetector.Episode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("在途持续超温片段判定（纯函数，PB3）")
class TemperatureExcursionDetectorTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 25, 1, 0);
    private long seq;

    private TemperatureRecord rec(int minute, String evaluation) {
        return rec(minute, evaluation, 61L, "-25.00", "-15.00", 1800);
    }

    private TemperatureRecord rec(int minute, String evaluation, Long stageId, String lower, String upper, Integer allowed) {
        TemperatureRecord r = new TemperatureRecord();
        r.setId(++seq);
        r.setShipmentId(9L);
        r.setStageCode("TRANSPORT");
        r.setMeasuredAt(T0.plusMinutes(minute));
        r.setEvaluation(evaluation);
        if (!"MISSING_CONTEXT".equals(evaluation)) {
            r.setRuleStageId(stageId);
            r.setRuleLowerLimit(new BigDecimal(lower));
            r.setRuleUpperLimit(new BigDecimal(upper));
            r.setRuleAllowedDurationSeconds(allowed);
        }
        return r;
    }

    private static List<TemperatureRecord> ordered(TemperatureRecord... records) {
        List<TemperatureRecord> list = new ArrayList<>(List.of(records));
        list.sort(Comparator.comparing(TemperatureRecord::getMeasuredAt).thenComparing(TemperatureRecord::getId));
        return list;
    }

    @Test
    @DisplayName("单条越界（允许时长 > 0）永远不构成持续超温；多条但未达到允许时长也不构成")
    void singlePointAndShortRunsAreNotSustained() {
        assertThat(TemperatureExcursionDetector.sustainedEpisodes(ordered(rec(0, "HIGH")))).isEmpty();
        assertThat(TemperatureExcursionDetector.sustainedEpisodes(ordered(rec(0, "HIGH"), rec(10, "HIGH"), rec(29, "HIGH")))).isEmpty();
        assertThat(TemperatureExcursionDetector.sustainedEpisodes(List.of())).isEmpty();
    }

    @Test
    @DisplayName("连续同向越界的已测区间 ≥ 允许时长（恰好等于也算）：片段首条与首条达到时长的记录")
    void sustainedAtExactThreshold() {
        TemperatureRecord a = rec(0, "HIGH");
        TemperatureRecord b = rec(15, "HIGH");
        TemperatureRecord c = rec(30, "HIGH");
        TemperatureRecord d = rec(45, "HIGH");
        List<Episode> episodes = TemperatureExcursionDetector.sustainedEpisodes(ordered(a, b, c, d));
        assertThat(episodes).hasSize(1);
        Episode e = episodes.get(0);
        assertThat(e.direction()).isEqualTo(TemperatureEvaluation.HIGH);
        assertThat(e.start()).isSameAs(a);
        assertThat(e.sustained()).as("first record reaching the allowed duration").isSameAs(c);
        assertThat(e.durationSeconds()).isEqualTo(1800);
        assertThat(e.records()).containsExactly(a, b, c, d);
        assertThat(e.basis().allowedDurationSeconds()).isEqualTo(1800);
    }

    @Test
    @DisplayName("允许时长为 0：首条越界记录即构成持续超温")
    void zeroAllowedDurationSustainsImmediately() {
        TemperatureRecord a = rec(0, "LOW", 62L, "-25.00", "-15.00", 0);
        List<Episode> episodes = TemperatureExcursionDetector.sustainedEpisodes(ordered(a));
        assertThat(episodes).singleElement().satisfies(e -> {
            assertThat(e.direction()).isEqualTo(TemperatureEvaluation.LOW);
            assertThat(e.start()).isSameAs(a);
            assertThat(e.sustained()).isSameAs(a);
            assertThat(e.durationSeconds()).isZero();
        });
    }

    @Test
    @DisplayName("NORMAL、MISSING_CONTEXT、方向改变与判定依据改变都会结束片段")
    void runBreakers() {
        assertThat(TemperatureExcursionDetector.sustainedEpisodes(ordered(rec(0, "HIGH"), rec(20, "NORMAL"), rec(40, "HIGH")))).isEmpty();
        assertThat(TemperatureExcursionDetector.sustainedEpisodes(ordered(rec(0, "HIGH"), rec(20, "MISSING_CONTEXT"), rec(40, "HIGH")))).isEmpty();
        assertThat(TemperatureExcursionDetector.sustainedEpisodes(ordered(rec(0, "HIGH"), rec(40, "LOW")))).isEmpty();
        assertThat(TemperatureExcursionDetector.sustainedEpisodes(ordered(
                rec(0, "HIGH"), rec(40, "HIGH", 63L, "-25.00", "-15.00", 1800)))).as("different rule stage").isEmpty();
        assertThat(TemperatureExcursionDetector.sustainedEpisodes(ordered(
                rec(0, "HIGH"), rec(40, "HIGH", 61L, "-25.00", "-14.00", 1800)))).as("same stage, drifted limits snapshot").isEmpty();
        assertThat(TemperatureExcursionDetector.sustainedEpisodes(ordered(
                rec(0, "HIGH"), rec(40, "HIGH", 61L, "-25.00", "-15.00", 3600)))).as("different allowed duration snapshot").isEmpty();
        // 上下限标度不同但数值相同仍是同一依据
        assertThat(TemperatureExcursionDetector.sustainedEpisodes(ordered(
                rec(0, "HIGH"), rec(40, "HIGH", 61L, "-25.0", "-15", 1800)))).hasSize(1);
    }

    @Test
    @DisplayName("乱序补登：较早测量时间的越界记录后到，同样补全片段；片段按 (measuredAt, id) 排序")
    void outOfOrderCompletion() {
        TemperatureRecord late = rec(40, "HIGH");
        TemperatureRecord early = rec(0, "HIGH");
        List<Episode> episodes = TemperatureExcursionDetector.sustainedEpisodes(ordered(late, early));
        assertThat(episodes).singleElement().satisfies(e -> {
            assertThat(e.start()).isSameAs(early);
            assertThat(e.sustained()).isSameAs(late);
        });
    }

    @Test
    @DisplayName("去重：包含已告警标识记录的片段不再告警；片段被后到的范围内记录拆开后，不含标识记录的新片段才是新事件")
    void unalertedFiltering() {
        TemperatureRecord a = rec(0, "HIGH");
        TemperatureRecord b = rec(20, "HIGH");
        TemperatureRecord c = rec(40, "HIGH");
        TemperatureRecord d = rec(60, "HIGH");
        TemperatureRecord e = rec(80, "HIGH");
        List<Episode> one = TemperatureExcursionDetector.sustainedEpisodes(ordered(a, b, c, d, e));
        assertThat(one).hasSize(1);
        assertThat(one.get(0).sustained()).isSameAs(c);
        List<Long> alerted = List.of(a.getId(), c.getId());
        assertThat(TemperatureExcursionDetector.unalerted(one, alerted)).as("growing episode stays alerted").isEmpty();

        // 后到的 NORMAL 把片段拆成 [a b c] 与 [d e]：后者 20 分钟未达到时长 → 无新告警
        TemperatureRecord normalAt50 = rec(50, "NORMAL");
        List<Episode> split = TemperatureExcursionDetector.sustainedEpisodes(ordered(a, b, c, normalAt50, d, e));
        assertThat(TemperatureExcursionDetector.unalerted(split, alerted)).isEmpty();

        // 继续越界到 90 分钟：[d e f] 达到 30 分钟，是不含已告警记录的新片段
        TemperatureRecord f = rec(90, "HIGH");
        List<Episode> later = TemperatureExcursionDetector.unalerted(
                TemperatureExcursionDetector.sustainedEpisodes(ordered(a, b, c, normalAt50, d, e, f)), alerted);
        assertThat(later).singleElement().satisfies(ep -> {
            assertThat(ep.start()).isSameAs(d);
            assertThat(ep.sustained()).isSameAs(f);
        });
    }

    @Test
    @DisplayName("同一测量时间多条记录按主键稳定排序参与片段划分")
    void sameMeasuredAtOrderedById() {
        TemperatureRecord a = rec(0, "HIGH");
        TemperatureRecord normalSameTime = rec(0, "NORMAL");
        TemperatureRecord c = rec(40, "HIGH");
        // 顺序：a(HIGH, id 小) → normal(同一时间, id 大) → c：NORMAL 结束片段，无持续超温
        assertThat(TemperatureExcursionDetector.sustainedEpisodes(ordered(a, normalSameTime, c))).isEmpty();
    }
}
