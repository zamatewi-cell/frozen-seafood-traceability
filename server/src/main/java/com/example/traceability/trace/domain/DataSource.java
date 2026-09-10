package com.example.traceability.trace.domain;

import java.util.Arrays;

/**
 * 追溯数据来源枚举。
 * <p>
 * 遵循系统契约（Issue #15）：
 * <ul>
 *   <li>MANUAL: 手工登记补录</li>
 *   <li>IMPORT: 外部文件/数据导入</li>
 *   <li>SIMULATED: 教学与仿真环境模拟数据，必须显式保留并在消费者界面明确提示</li>
 *   <li>DEVICE: 申报来源为设备采集，仅用于表达数据源分类，不得在系统或文档中宣传为真实设备已接入</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public enum DataSource {

    /**
     * 手工录入。
     */
    MANUAL,

    /**
     * 批量导入。
     */
    IMPORT,

    /**
     * 教学与演示仿真数据。
     */
    SIMULATED,

    /**
     * 设备声明数据（非真实物联网硬件接入证明）。
     */
    DEVICE;

    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String clean = code.trim().toUpperCase();
        return Arrays.stream(values()).anyMatch(e -> e.name().equals(clean));
    }

    public static DataSource fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("数据来源不能为空");
        }
        return valueOf(code.trim().toUpperCase());
    }
}
