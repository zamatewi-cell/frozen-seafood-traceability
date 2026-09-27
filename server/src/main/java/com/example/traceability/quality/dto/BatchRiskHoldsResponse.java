package com.example.traceability.quality.dto;

import com.example.traceability.batch.application.BatchRiskService;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.quality.domain.RecallNotice;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 批次当前未解除的风险事项与收到的上游召回通知（企业端只读；Phase B 独立评审修复）。
 * <p>
 * 风险事项与召回通知属于批次：只对批次的当前责任组织与平台只读角色输出。不暴露幂等键、原因、操作人或其他组织的事实。
 * </p>
 *
 * @param batchId          批次
 * @param riskStatus       批次当前风险状态
 * @param alertHolds       尚未对该批次形成放行结论的未处置告警（批次 RECALLED 时为空）
 * @param manualFreezeHold 当前冻结期由人工风险冻结开启且尚未人工解除
 * @param recallNotices    其他组织召回影响范围中对该批次的通知（NOTIFY_HOLDER），按召回 ID 升序
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record BatchRiskHoldsResponse(
        Long batchId,
        String riskStatus,
        List<AlertHold> alertHolds,
        boolean manualFreezeHold,
        List<Notice> recallNotices
) {

    /**
     * 未解除的告警风险事项。
     */
    public record AlertHold(Long alertId, String alertNo, String alertStatus) {
    }

    /**
     * 上游召回通知。
     *
     * @param notifiedAt 召回发起时间（通知时间）
     * @param depth      批次在该召回正向范围中的谱系距离
     */
    public record Notice(
            Long recallId,
            String recallNo,
            String recallStatus,
            Long ownerOrgId,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", timezone = "UTC")
            OffsetDateTime notifiedAt,
            Integer depth
    ) {
    }

    public static BatchRiskHoldsResponse of(Batch batch, BatchRiskService.RiskHolds holds, List<RecallNotice> notices) {
        return new BatchRiskHoldsResponse(batch.getId(), batch.getRiskStatus(),
                holds.alertHolds().stream().map(h -> new AlertHold(h.getAlertId(), h.getAlertNo(), h.getAlertStatus())).toList(),
                holds.manualFreezeHold(),
                notices.stream().map(n -> new Notice(n.getRecallId(), n.getRecallNo(), n.getRecallStatus(), n.getOwnerOrgId(),
                        utc(n.getStartedAt()), n.getDepth())).toList());
    }

    private static OffsetDateTime utc(LocalDateTime t) {
        return t == null ? null : t.atOffset(ZoneOffset.UTC);
    }
}
