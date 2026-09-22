package com.example.traceability.trace.domain;

import java.util.Arrays;

/**
 * 供应链追溯事件类型枚举。
 * <p>
 * 严格遵循 Phase 3 规范契约（Issue #15），仅允许以下 10 种标准关键追溯事件：
 * <ul>
 *   <li>SOURCE: 捕捞出塘/养殖采收/原产源头</li>
 *   <li>PURCHASE: 原料采购/初次收购</li>
 *   <li>PROCESS: 加工分选/去头去鳞/剖杀处理</li>
 *   <li>FREEZE: 急速冷冻/风冷速冻</li>
 *   <li>PACK: 内外包装/装箱称重</li>
 *   <li>WAREHOUSE_IN: 冷库入库</li>
 *   <li>WAREHOUSE_OUT: 冷库出库</li>
 *   <li>TRANSPORT: 冷链干线或支线装车起运</li>
 *   <li>ARRIVAL: 运输到货/到达目的地场所</li>
 *   <li>SALE: 终端零售/批发分销</li>
 * </ul>
 * 严禁使用旧版废弃名称（如 HARVEST, SHIP, RECEIVE, INSPECT, RETAIL）。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum TraceEventType {

    /**
     * 源头产生事件（捕捞、出塘、进口源头）。
     */
    SOURCE,

    /**
     * 采购/收购事件。
     */
    PURCHASE,

    /**
     * 生产加工事件。
     */
    PROCESS,

    /**
     * 急速冷冻事件。
     */
    FREEZE,

    /**
     * 称重包装事件。
     */
    PACK,

    /**
     * 仓储入库事件。
     */
    WAREHOUSE_IN,

    /**
     * 仓储出库事件。
     */
    WAREHOUSE_OUT,

    /**
     * 冷链起运事件。
     */
    TRANSPORT,

    /**
     * 运输到达事件。
     */
    ARRIVAL,

    /**
     * 销售/出货事件。
     */
    SALE,

    /**
     * 质检通过事件（环节清单全部打钩后生成，每次质检一条）。
     */
    QUALITY_CHECK;

    /**
     * 校验字符串编码是否属于合法的追溯事件类型（大小写不敏感）。
     *
     * @param code 待校验事件类型字符串
     * @return 若合法返回 true，否则返回 false
     */
    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String clean = code.trim().toUpperCase();
        return Arrays.stream(values()).anyMatch(e -> e.name().equals(clean));
    }

    /**
     * 根据字符串编码转换为事件类型枚举（大小写不敏感）。
     *
     * @param code 事件类型编码
     * @return 对应的枚举值
     * @throws IllegalArgumentException 若不支持该编码
     */
    public static TraceEventType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("事件类型不能为空");
        }
        return valueOf(code.trim().toUpperCase());
    }
}
