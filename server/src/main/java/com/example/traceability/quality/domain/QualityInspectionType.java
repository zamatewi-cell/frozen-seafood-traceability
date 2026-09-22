package com.example.traceability.quality.domain;

/**
 * 质检类型。
 * <p>SOURCE_RECEIVE 原料入库验收 / OUTGOING 出厂质检 / ARRIVAL 到货验收 / SPOT 日常抽检。</p>
 */
public final class QualityInspectionType {

    public static final String SOURCE_RECEIVE = "SOURCE_RECEIVE";
    public static final String OUTGOING = "OUTGOING";
    public static final String ARRIVAL = "ARRIVAL";
    public static final String SPOT = "SPOT";

    private QualityInspectionType() {
    }

    public static boolean isValid(String type) {
        return SOURCE_RECEIVE.equals(type) || OUTGOING.equals(type)
                || ARRIVAL.equals(type) || SPOT.equals(type);
    }
}