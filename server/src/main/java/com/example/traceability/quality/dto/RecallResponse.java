package com.example.traceability.quality.dto;

import com.example.traceability.quality.domain.Recall;
import com.example.traceability.quality.domain.RecallBatch;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * 模拟召回白名单响应 DTO（企业端；Phase B PB5；独立评审修复）。不暴露幂等键与请求哈希。
 * <p>
 * 发起组织与平台只读角色看到完整影响范围与内部处置总结。其他可见组织只看到案件概要与自己的范围行，看不到内部处置总结与其他组织的事实：
 * 范围批次的<b>当前</b>责任组织（召回通知随批次交接转给它）看到该批次的发起时快照与当前流转 / 风险状态；
 * 发起时的持有方在批次转出后只看到发起时的历史快照，不含新责任组织的任何当前事实。列表不含影响范围。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record RecallResponse(
        Long id,
        String recallNo,
        Long ownerOrgId,
        Long sourceAlertId,
        String reason,
        String status,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime startedAt,
        Long startedBy,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
        OffsetDateTime closedAt,
        Long closedBy,
        String publicDisposition,
        String resultSummary,
        Long version,
        String viewerRelation,
        Summary summary,
        List<ScopeItem> scope
) {

    /**
     * 影响范围汇总（只统计 SEED / DESCENDANT；数量单位 kg）。
     *
     * @param remainingQuantity 库存与在途（剩余）数量合计
     * @param soldQuantity      已终端销售数量合计
     */
    public record Summary(
            int seedCount,
            int descendantCount,
            int ancestorCount,
            int recalledCount,
            int notifiedCount,
            int openTransferCount,
            int publicCodeCount,
            BigDecimal remainingQuantity,
            BigDecimal soldQuantity
    ) {
    }

    /**
     * 影响范围行（发起时快照；当前流转 / 风险状态只对发起组织、平台与该批次的当前责任组织输出）。
     *
     * @param heldByViewer 查看组织当前负责该批次（召回通知需要由它处置）
     */
    public record ScopeItem(
            Long batchId,
            String traceBatchNo,
            String productName,
            String scopeRole,
            Integer depth,
            Long holderOrgId,
            String flowStatus,
            String riskStatusBefore,
            String currentFlowStatus,
            String currentRiskStatus,
            String action,
            Long riskTransitionId,
            BigDecimal declaredQuantity,
            BigDecimal remainingQuantity,
            BigDecimal soldQuantity,
            String unitCode,
            Long openTransferId,
            String openTransferNo,
            String openTransferStatus,
            String shipmentStatus,
            boolean publicCodeActive,
            boolean heldByViewer
    ) {

        static ScopeItem fromEntity(RecallBatch b, boolean full, Long viewerOrgId) {
            boolean held = viewerOrgId != null && Objects.equals(b.getCurrentOrgId(), viewerOrgId);
            boolean current = full || held;
            return new ScopeItem(b.getBatchId(), b.getTraceBatchNo(), b.getProductName(), b.getScopeRole(), b.getDepth(),
                    b.getHolderOrgId(), b.getFlowStatus(), b.getRiskStatusBefore(), current ? b.getCurrentFlowStatus() : null,
                    current ? b.getCurrentRiskStatus() : null, b.getAction(), b.getRiskTransitionId(), b.getDeclaredQuantity(),
                    b.getRemainingQuantity(), b.getSoldQuantity(), b.getUnitCode(), b.getOpenTransferId(), b.getOpenTransferNo(),
                    b.getOpenTransferStatus(), b.getShipmentStatus(), Boolean.TRUE.equals(b.getPublicCodeActive()), held);
        }
    }

    /**
     * 列表项：查看关系取自可见性查询计算的 viewer_relation。
     */
    public static RecallResponse summaryOf(Recall r, boolean owner) {
        return of(r, owner, r.getViewerRelation(), null, null);
    }

    /**
     * 详情。
     *
     * @param full           发起组织或平台只读角色（完整范围、内部处置总结与全部当前事实）
     * @param viewerRelation 查看关系
     * @param viewerOrgId    查看组织（判定哪些范围行由其当前负责）
     * @param rows           已按查看范围过滤的范围行
     */
    public static RecallResponse detailOf(Recall r, boolean full, String viewerRelation, Long viewerOrgId, List<RecallBatch> rows) {
        return of(r, full, viewerRelation, viewerOrgId, rows);
    }

    private static RecallResponse of(Recall r, boolean full, String viewerRelation, Long viewerOrgId, List<RecallBatch> rows) {
        Summary summary = rows == null ? null : summarize(rows);
        List<ScopeItem> scope = rows == null ? null : rows.stream().map(b -> ScopeItem.fromEntity(b, full, viewerOrgId)).toList();
        return new RecallResponse(r.getId(), r.getRecallNo(), r.getOwnerOrgId(), r.getSourceAlertId(), r.getReason(), r.getStatus(),
                utc(r.getStartedAt()), r.getStartedBy(), utc(r.getClosedAt()), r.getClosedBy(), r.getPublicDisposition(),
                full ? r.getResultSummary() : null, r.getVersion(), viewerRelation, summary, scope);
    }

    private static Summary summarize(List<RecallBatch> rows) {
        int seeds = 0;
        int descendants = 0;
        int ancestors = 0;
        int recalled = 0;
        int notified = 0;
        int openTransfers = 0;
        int codes = 0;
        BigDecimal remaining = BigDecimal.ZERO;
        BigDecimal sold = BigDecimal.ZERO;
        for (RecallBatch b : rows) {
            switch (b.getScopeRole()) {
                case "SEED" -> seeds++;
                case "DESCENDANT" -> descendants++;
                default -> ancestors++;
            }
            if ("RECALLED".equals(b.getAction()) || "ALREADY_RECALLED".equals(b.getAction())) {
                recalled++;
            }
            if ("NOTIFY_HOLDER".equals(b.getAction())) {
                notified++;
            }
            if (!"ANCESTOR".equals(b.getScopeRole())) {
                if (b.getOpenTransferId() != null) {
                    openTransfers++;
                }
                if (Boolean.TRUE.equals(b.getPublicCodeActive())) {
                    codes++;
                }
                remaining = remaining.add(b.getRemainingQuantity());
                sold = sold.add(b.getSoldQuantity());
            }
        }
        return new Summary(seeds, descendants, ancestors, recalled, notified, openTransfers, codes, remaining, sold);
    }

    private static OffsetDateTime utc(LocalDateTime t) {
        return t == null ? null : t.atOffset(ZoneOffset.UTC);
    }
}
