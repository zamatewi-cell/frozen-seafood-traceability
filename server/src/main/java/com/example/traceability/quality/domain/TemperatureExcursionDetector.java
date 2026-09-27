package com.example.traceability.quality.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 在途持续超温片段判定（纯函数；Phase B PB3；统一业务契约 v1.1 §10.1）。
 * <p>
 * 只使用温度记录登记时持久化的判定依据快照（单点判定、规则环节 ID、上下限与允许越界时长），从不读取可能已被改动的
 * 当前规则环节。输入为同一运输任务的全部记录，按 (measuredAt, id) 升序。
 * </p>
 * <p>
 * 越界片段（episode）：按上述顺序连续、单点判定相同方向（全部 HIGH 或全部 LOW）且判定依据快照完全相同的最长记录序列。
 * 以下任一情况结束当前片段：
 * <ul>
 *   <li>NORMAL 记录（温度回到范围内）；</li>
 *   <li>MISSING_CONTEXT 记录（该时间点没有适用规则，无法证明仍在越界）；</li>
 *   <li>方向改变（HIGH ↔ LOW）：连续的物理温度曲线从高于上限到低于下限必然穿过范围内区间，不能视为同一次连续越界；</li>
 *   <li>判定依据快照改变（规则环节或上下限、允许时长不同）：每个片段只按唯一一份历史规则依据判定。</li>
 * </ul>
 * 持续超温（sustained）：片段内存在一条记录，其测量时间与片段首条记录测量时间之差 ≥ 快照允许越界时长；
 * 允许时长为 0 时首条越界记录即满足（契约 §10.1）。只统计已测得的越界区间，不向后外推：
 * 允许时长大于 0 时单条越界记录永远不构成持续超温。
 * </p>
 * <p>
 * 告警去重：已有告警以其片段首条记录与达到时长的记录标识所覆盖的片段；一个持续超温片段只要包含任一已告警的
 * 标识记录即视为已告警。记录追加式不可修改，因此已告警记录的判定与方向永不改变；后续插入的范围内记录把片段拆开后，
 * 不含任何已告警标识记录的新持续超温片段才是新的越界事件。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public final class TemperatureExcursionDetector {

    private TemperatureExcursionDetector() {
    }

    /** 判定依据快照（上下限统一为两位小数比较，避免标度差异导致误判为不同依据）。 */
    public record Basis(Long ruleStageId, BigDecimal lowerLimit, BigDecimal upperLimit, Integer allowedDurationSeconds) {

        static Basis of(TemperatureRecord r) {
            return new Basis(r.getRuleStageId(), scale(r.getRuleLowerLimit()), scale(r.getRuleUpperLimit()),
                    r.getRuleAllowedDurationSeconds());
        }

        private static BigDecimal scale(BigDecimal v) {
            return v == null ? null : v.setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /**
     * 一个达到持续超温条件的越界片段。
     *
     * @param direction 越界方向（HIGH 或 LOW）
     * @param basis     片段唯一的判定依据快照
     * @param start     片段首条越界记录
     * @param sustained 片段内首条达到允许越界时长的记录
     * @param records   片段全部记录（按 measuredAt、id 升序）
     */
    public record Episode(TemperatureEvaluation direction, Basis basis, TemperatureRecord start, TemperatureRecord sustained,
                          List<TemperatureRecord> records) {

        /** 达到条件时已持续越界的整秒数（向下取整）。 */
        public long durationSeconds() {
            return Duration.between(start.getMeasuredAt(), sustained.getMeasuredAt()).getSeconds();
        }

        boolean containsAny(Set<Long> recordIds) {
            return records.stream().anyMatch(r -> recordIds.contains(r.getId()));
        }
    }

    /**
     * 找出全部达到持续超温条件的越界片段（按片段开始顺序）。
     *
     * @param ordered 同一运输任务的全部温度记录，按 (measuredAt, id) 升序
     */
    public static List<Episode> sustainedEpisodes(List<TemperatureRecord> ordered) {
        Objects.requireNonNull(ordered, "ordered 不能为空");
        List<Episode> result = new ArrayList<>();
        List<TemperatureRecord> run = new ArrayList<>();
        TemperatureEvaluation runDirection = null;
        Basis runBasis = null;
        for (TemperatureRecord r : ordered) {
            TemperatureEvaluation evaluation = TemperatureEvaluation.valueOf(r.getEvaluation());
            boolean outOfRange = evaluation == TemperatureEvaluation.HIGH || evaluation == TemperatureEvaluation.LOW;
            Basis basis = outOfRange ? Basis.of(r) : null;
            boolean continuesRun = outOfRange && evaluation == runDirection && basis.equals(runBasis);
            if (!continuesRun) {
                close(run, runDirection, runBasis, result);
                run = new ArrayList<>();
                runDirection = outOfRange ? evaluation : null;
                runBasis = basis;
            }
            if (outOfRange) {
                run.add(r);
            }
        }
        close(run, runDirection, runBasis, result);
        return result;
    }

    /**
     * 过滤出尚未被任何已有告警覆盖的持续超温片段。
     *
     * @param episodes       {@link #sustainedEpisodes} 的结果
     * @param alertedRecords 已有告警的片段首条记录 ID 与达到时长记录 ID
     */
    public static List<Episode> unalerted(List<Episode> episodes, Collection<Long> alertedRecords) {
        Set<Long> covered = Set.copyOf(alertedRecords);
        return episodes.stream().filter(e -> !e.containsAny(covered)).toList();
    }

    private static void close(List<TemperatureRecord> run, TemperatureEvaluation direction, Basis basis, List<Episode> out) {
        if (run.isEmpty()) {
            return;
        }
        TemperatureRecord start = run.get(0);
        Duration allowed = Duration.ofSeconds(basis.allowedDurationSeconds());
        for (TemperatureRecord candidate : run) {
            if (Duration.between(start.getMeasuredAt(), candidate.getMeasuredAt()).compareTo(allowed) >= 0) {
                out.add(new Episode(direction, basis, start, candidate, List.copyOf(run)));
                return;
            }
        }
    }
}
