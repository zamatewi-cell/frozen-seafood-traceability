package com.example.traceability.quality.domain;

/**
 * 质检环节常量。
 * <p>三个出货环节各自有专属质检员和清单:
 * <ul>
 *   <li>CAPTURE_OUT: 捕捞出货质检 (capt_qa, org=1 捕捞舰队)</li>
 *   <li>PROCESS_OUT: 加工出货质检 (plant_qa, org=2 加工厂)</li>
 *   <li>DIST_OUT: 批发分装出货质检 (whl_qa, org=3 批发分装)</li>
 * </ul>
 * </p>
 */
public final class QualityInspectionStage {

    public static final String CAPTURE_OUT = "CAPTURE_OUT";
    public static final String PROCESS_OUT = "PROCESS_OUT";
    public static final String DIST_OUT = "DIST_OUT";

    private QualityInspectionStage() {
    }

    public static boolean isValid(String stage) {
        return CAPTURE_OUT.equals(stage) || PROCESS_OUT.equals(stage) || DIST_OUT.equals(stage);
    }
}
