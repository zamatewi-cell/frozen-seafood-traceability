package com.example.traceability.quality.domain;

/**
 * 质检判定结果。
 * <p>INSPECTING 待检 / PASS 合格 / FAIL 不合格（据此决定是否放行、收货或召回）。</p>
 */
public final class QualityInspectionResult {

    public static final String INSPECTING = "INSPECTING";
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";

    private QualityInspectionResult() {
    }

    public static boolean isValid(String result) {
        return PASS.equals(result) || FAIL.equals(result);
    }
}