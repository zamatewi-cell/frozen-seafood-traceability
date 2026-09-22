package com.example.traceability.trace.dto;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.identity.dto.SiteSummaryResponse;
import com.example.traceability.trace.domain.Shipment;
import com.example.traceability.trace.domain.ShipmentStatus;
import com.example.traceability.trace.domain.Transfer;
import com.example.traceability.trace.domain.TransferStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 冷链运输任务响应 DTO（严格白名单投影）。
 * <p>
 * 内嵌三方组织摘要、起止场所摘要与装载清单摘要，屏蔽 isDeleted、幂等键等底层持久化细节。
 * 时间字段统一以 UTC 带时区 ISO 8601 输出。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.3.0
 */
public record ShipmentResponse(
        Long id,
        String shipmentNo,
        ShipmentStatus status,
        PartyRef senderOrg,
        PartyRef receiverOrg,
        PartyRef carrierOrg,
        String vehicleOrContainerNo,
        SiteSummaryResponse originSite,
        SiteSummaryResponse destinationSite,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime loadedAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime unloadedAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime dispatchedRecordedAt,
        Long dispatchedBy,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime deliveredRecordedAt,
        Long deliveredBy,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime cancelledRecordedAt,
        Long cancelledBy,
        String cancelReason,
        List<TransferItem> transfers,
        Long version,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
        OffsetDateTime updatedAt
) {

    /**
     * 参与组织摘要。
     *
     * @param id      组织 ID
     * @param orgNo   组织编号
     * @param name    组织名称
     * @param orgType 组织类型
     */
    public record PartyRef(Long id, String orgNo, String name, String orgType) {

        static PartyRef of(Long id, Map<Long, Organization> orgs) {
            Organization o = orgs.get(id);
            return o == null
                    ? new PartyRef(id, null, null, null)
                    : new PartyRef(o.getId(), o.getOrgNo(), o.getName(), o.getOrgType());
        }
    }

    /**
     * 装载清单交接摘要。
     *
     * @param transferId   交接 ID
     * @param transferNo   交接凭单号
     * @param batchId      批次 ID
     * @param traceBatchNo 追溯批次号
     * @param quantity     交接数量
     * @param unitCode     计量单位
     * @param status       交接状态
     * @param version      交接乐观锁版本号
     */
    public record TransferItem(
            Long transferId,
            String transferNo,
            Long batchId,
            String traceBatchNo,
            BigDecimal quantity,
            String unitCode,
            TransferStatus status,
            Long version
    ) {
    }

    public static ShipmentResponse of(
            Shipment s,
            Map<Long, Organization> orgs,
            Map<Long, Site> sites,
            List<Transfer> transfers,
            Map<Long, Batch> batches
    ) {
        List<TransferItem> items = transfers.stream()
                .map(t -> {
                    Batch b = batches.get(t.getBatchId());
                    return new TransferItem(
                            t.getId(),
                            t.getTransferNo(),
                            t.getBatchId(),
                            b != null ? b.getTraceBatchNo() : null,
                            t.getQuantity(),
                            t.getUnitCode(),
                            t.getStatus(),
                            t.getVersion()
                    );
                })
                .toList();
        return new ShipmentResponse(
                s.getId(),
                s.getShipmentNo(),
                s.getStatus(),
                PartyRef.of(s.getSenderOrgId(), orgs),
                PartyRef.of(s.getReceiverOrgId(), orgs),
                PartyRef.of(s.getCarrierOrgId(), orgs),
                s.getVehicleOrContainerNo(),
                SiteSummaryResponse.fromEntity(sites.get(s.getOriginSiteId())),
                SiteSummaryResponse.fromEntity(sites.get(s.getDestinationSiteId())),
                utc(s.getLoadedAt()),
                utc(s.getUnloadedAt()),
                utc(s.getDispatchedRecordedAt()),
                s.getDispatchedBy(),
                utc(s.getDeliveredRecordedAt()),
                s.getDeliveredBy(),
                utc(s.getCancelledRecordedAt()),
                s.getCancelledBy(),
                s.getCancelReason(),
                items,
                s.getVersion(),
                utc(s.getCreatedAt()),
                utc(s.getUpdatedAt())
        );
    }

    private static OffsetDateTime utc(LocalDateTime t) {
        return t != null ? t.atOffset(ZoneOffset.UTC) : null;
    }
}
