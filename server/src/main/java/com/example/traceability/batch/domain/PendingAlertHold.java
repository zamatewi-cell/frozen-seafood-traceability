package com.example.traceability.batch.domain;

/**
 * 批次上的一个未解除告警风险事项（只读查询投影，不对应独立表；Phase B 独立评审修复）。
 * <p>
 * 定义与人工解除冻结的守卫一致：批次属于该告警的受影响批次快照、告警尚未形成处置结论（非 RESOLVED），
 * 且该告警尚未对该批次记录放行结论（RELEASE_BATCH）。批次进入 RECALLED 后全部告警风险事项视为已解除。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class PendingAlertHold {

    private Long alertId;

    private String alertNo;

    private String alertStatus;

    private Long shipmentId;

    public PendingAlertHold() {
    }

    public PendingAlertHold(Long alertId, String alertNo, String alertStatus, Long shipmentId) {
        this.alertId = alertId;
        this.alertNo = alertNo;
        this.alertStatus = alertStatus;
        this.shipmentId = shipmentId;
    }

    public Long getAlertId() {
        return alertId;
    }

    public void setAlertId(Long alertId) {
        this.alertId = alertId;
    }

    public String getAlertNo() {
        return alertNo;
    }

    public void setAlertNo(String alertNo) {
        this.alertNo = alertNo;
    }

    public String getAlertStatus() {
        return alertStatus;
    }

    public void setAlertStatus(String alertStatus) {
        this.alertStatus = alertStatus;
    }

    public Long getShipmentId() {
        return shipmentId;
    }

    public void setShipmentId(Long shipmentId) {
        this.shipmentId = shipmentId;
    }
}
