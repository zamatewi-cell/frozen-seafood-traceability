package com.example.traceability.masterdata.domain;

import java.time.LocalDateTime;

/**
 * 温控规则时间区间重叠判定领域校验器。
 * <p>
 * 规则生效时间遵循左闭右开半开区间 {@code [effectiveFrom, effectiveTo)} 语义。
 * 当 {@code effectiveTo} 为 {@code null} 时，表示长期有效直至正无穷大 {@code [effectiveFrom, +∞)}。
 * 相邻区间（例如前一规则结束时间与后一规则起始时间完全相同）允许平滑过渡，不判定为冲突重叠。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public final class TemperatureRuleIntervalValidator {

    private TemperatureRuleIntervalValidator() {
    }

    /**
     * 判断两个半开区间 {@code [from1, to1)} 与 {@code [from2, to2)} 是否重叠。
     *
     * @param from1 区间1起始时间（非空）
     * @param to1   区间1结束时间（可空，空表示正无穷大）
     * @param from2 区间2起始时间（非空）
     * @param to2   区间2结束时间（可空，空表示正无穷大）
     * @return {@code true} 表示存在重叠冲突；{@code false} 表示互不重叠或严格相邻
     */
    public static boolean isOverlapping(
            LocalDateTime from1, LocalDateTime to1,
            LocalDateTime from2, LocalDateTime to2
    ) {
        if (from1 == null || from2 == null) {
            throw new IllegalArgumentException("区间的起始时间 effectiveFrom 不能为 null");
        }

        // 判断条件1: from1 < to2 (若 to2 为 null，则视作正无穷，恒满足)
        boolean from1BeforeTo2 = (to2 == null) || from1.isBefore(to2);

        // 判断条件2: from2 < to1 (若 to1 为 null，则视作正无穷，恒满足)
        boolean from2BeforeTo1 = (to1 == null) || from2.isBefore(to1);

        // 两半开区间交集非空的充要条件是两个不等式同时成立
        return from1BeforeTo2 && from2BeforeTo1;
    }
}
