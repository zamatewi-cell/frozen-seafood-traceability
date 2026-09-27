package com.example.traceability.quality.domain;

import java.math.BigDecimal;

/**
 * 召回范围批次的下游事实（只读查询投影，不对应独立表；Phase B PB5）：已终端销售数量、未结束交接及其运输状态、公开追溯码是否启用。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public class RecallScopeFact {

    private Long batchId;
    private BigDecimal soldQuantity;
    private Long openTransferId;
    private String openTransferStatus;
    private String shipmentStatus;
    private Integer publicCodeActive;

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public BigDecimal getSoldQuantity() {
        return soldQuantity;
    }

    public void setSoldQuantity(BigDecimal soldQuantity) {
        this.soldQuantity = soldQuantity;
    }

    public Long getOpenTransferId() {
        return openTransferId;
    }

    public void setOpenTransferId(Long openTransferId) {
        this.openTransferId = openTransferId;
    }

    public String getOpenTransferStatus() {
        return openTransferStatus;
    }

    public void setOpenTransferStatus(String openTransferStatus) {
        this.openTransferStatus = openTransferStatus;
    }

    public String getShipmentStatus() {
        return shipmentStatus;
    }

    public void setShipmentStatus(String shipmentStatus) {
        this.shipmentStatus = shipmentStatus;
    }

    public Integer getPublicCodeActive() {
        return publicCodeActive;
    }

    public void setPublicCodeActive(Integer publicCodeActive) {
        this.publicCodeActive = publicCodeActive;
    }
}
