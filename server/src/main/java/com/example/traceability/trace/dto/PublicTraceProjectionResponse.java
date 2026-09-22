package com.example.traceability.trace.dto;

import java.util.List;

/**
 * 消费者公开追溯信息投影响应 DTO。
 * <p>
 * 严格只暴露白名单安全字段，绝不暴露内部数据库主键 ID、组织/用户/操作人/场所标识、
 * 原始批次号 (raw batch_no)、原始产地 (raw origin_text)、扩展属性 (detailsJson)、
 * 幂等键、哈希指纹 (token_hash)、逻辑删除或审计字段。
 * </p>
 *
 * @param publicTraceId      消费者公开追溯编码 (26 位 Base32)
 * @param product            产品公开属性投影
 * @param batch              首批次脱敏属性投影（兼容旧结构，等价于 segments 中的首段）
 * @param timeline           首批次时间线投影（兼容旧结构）
 * @param segments           一个码聚合的多批次履历段（每组为某一物理批次的脱敏信息+时间线）
 * @param temperatureSummary 温度摘要说明 (当前切片固定为 INSUFFICIENT_DATA 并带诚实说明)
 * @param batchStatus        真实批次流转状态 (ACTIVE/FROZEN/RECALLED/CLOSED)
 * @param recallNotice       模拟召回声明 (仅在 batchStatus=RECALLED 时非空返回系统演练提示，其余为 null)
 * @param queriedAt          查询时间 (带明确 UTC 偏移的 ISO 8601 字符串)
 * @param disclosure         法律与真实性声明
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record PublicTraceProjectionResponse(
        String publicTraceId,
        ProductProjection product,
        BatchProjection batch,
        List<TimelineItem> timeline,
        List<BatchSegment> segments,
        TraceTree tree,
        TemperatureSummaryProjection temperatureSummary,
        String batchStatus,
        String recallNotice,
        String queriedAt,
        String disclosure
) {

    /**
     * 溯源树状结构（一个订单 → 多环节 → 每环节多批次分支）。
     *
     * @param orderId    终端订单ID（内部不暴露，仅调试用，可置 null）
     * @param orderNo   终端订单号（脱敏后）
     * @param nodes      根节点列表（终端环节的各批次分支）
     */
    public record TraceTree(
            String orderNo,
            List<TraceTreeNode> nodes
    ) {
    }

    /**
     * 树节点：某环节用某批次满足订单的一部分。
     *
     * @param stage              环节（SOURCE/PROCESSING/DISTRIBUTION/RETAIL）
     * @param orgName           组织名（脱敏）
     * @param allocatedQuantity 该批次分配数量
     * @param batch              该批次脱敏信息
     * @param timeline           该批次溯源时间线
     * @param children           向上溯源的下一环节节点（多分支）
     */
    public record TraceTreeNode(
            String stage,
            String orgName,
            String allocatedQuantity,
            BatchProjection batch,
            List<TimelineItem> timeline,
            List<TraceTreeNode> children
    ) {
    }

    /**
     * 单个物理批次的公开履历段。
     *
     * @param publicBatchNo  掩码后的公开批次号
     * @param originType     来源类型代码
     * @param maskedOrigin   掩码后的产地文本
     * @param productionDate 生产加工日期 (可为 null)
     * @param timeline       该批次的追溯时间线
     */
    public record BatchSegment(
            String publicBatchNo,
            String originType,
            String maskedOrigin,
            String productionDate,
            List<TimelineItem> timeline
    ) {
    }

    /**
     * 产品公开信息投影。
     *
     * @param name          公开规范品名
     * @param category      海产品类别代码
     * @param specification 规格描述
     */
    public record ProductProjection(
            String name,
            String category,
            String specification
    ) {
    }

    /**
     * 批次公开脱敏信息投影。
     *
     * @param publicBatchNo  掩码后的公开批次号 (绝不暴露原始批次号)
     * @param originType     水产品来源类型代码
     * @param maskedOrigin   掩码后的产地文本描述 (绝不暴露原始产地完整字符串)
     * @param productionDate 生产加工日期 (yyyy-MM-dd，可为 null)
     */
    public record BatchProjection(
            String publicBatchNo,
            String originType,
            String maskedOrigin,
            String productionDate
    ) {
    }

    /**
     * 供应链追溯事件时间线项目。
     *
     * @param event           事件类型与业务标签
     * @param occurredAt      业务实际发生时间 (带明确 UTC 偏移的 ISO 8601 字符串)
     * @param dataSourceLabel 诚实的数据来源标签说明
     * @param summary         事件备注/详情 (如质检环节和清单项数、订单号等)
     */
    public record TimelineItem(
            String event,
            String occurredAt,
            String dataSourceLabel,
            String summary
    ) {
    }

    /**
     * 冷链温控履约摘要。
     *
     * @param result   判定结果 (当前切片恒为 INSUFFICIENT_DATA)
     * @param ruleNote 诚实说明 (说明尚未接入真实温控采集流)
     */
    public record TemperatureSummaryProjection(
            String result,
            String ruleNote
    ) {
    }
}
