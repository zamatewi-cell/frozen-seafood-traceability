package com.example.traceability.trace.domain;

/**
 * 消费者端数据脱敏与掩码工具类。
 * <p>
 * 严格按照安全白名单要求，对外部业务批次号与产地文本执行确定性掩码遮蔽，
 * 绝不暴露内部追溯批次号 {@code traceBatchNo}，亦杜绝完整 {@code origin_text} 在消费者公开投影中泄露。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public final class TraceDataMasker {

    private TraceDataMasker() {
    }

    /**
     * 外部业务批次号掩码遮盖（保留首尾部分字符，中间以 **** 混淆；若外部批次号为空或空白则返回 ****，绝不回退暴露内部 traceBatchNo）。
     *
     * @param raw 外部业务批次号 (externalBatchNo)
     * @return 掩码后的公开批次号
     */
    public static String maskBatchNo(String raw) {
        if (raw == null || raw.isBlank()) {
            return "****";
        }
        String s = raw.trim();
        if (s.length() <= 4) {
            return "****";
        }
        if (s.length() <= 8) {
            return s.charAt(0) + "****" + s.charAt(s.length() - 1);
        }
        return s.substring(0, 3) + "****" + s.substring(s.length() - 3);
    }

    /**
     * 产地与作业海域文本掩码遮盖（保留首尾区域特征，中间以脱敏星号混淆）。
     *
     * @param raw 原始来源产地描述文本
     * @return 掩码后的公开来源文本
     */
    public static String maskOriginText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "***";
        }
        String s = raw.trim();
        if (s.length() <= 2) {
            return "**";
        }
        if (s.length() <= 6) {
            return s.charAt(0) + "***" + s.charAt(s.length() - 1);
        }
        return s.substring(0, 2) + "****" + s.substring(s.length() - 2);
    }
}
