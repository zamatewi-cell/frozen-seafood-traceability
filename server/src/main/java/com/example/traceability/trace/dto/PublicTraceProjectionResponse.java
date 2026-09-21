package com.example.traceability.trace.dto;

import java.util.List;

/**
 * 消费者公开追溯信息投影响应 DTO。
 * <p>
 * 严格只暴露白名单安全字段，绝不暴露内部数据库主键 ID、组织/用户/操作人/场所标识、
 * 创建组织 (creationOrgId)、内部追溯批次号 (traceBatchNo)、原始产地 (raw origin_text)、
 * 扩展属性 (detailsJson)、幂等键、哈希指纹 (token_hash)、逻辑删除或审计字段。
 * </p>
 *
 * @param publicTraceId      消费者公开追溯编码 (26 位 Base32)
 * @param product            产品公开属性投影
 * @param batch              批次脱敏属性投影
 * @param timeline           仅包含 SUBMITTED 生效事件的时间线投影 (排除 CORRECTED 原事件)
 * @param flowStatus        业务流转状态 (DRAFT/ACTIVE/CLOSED)
 * @param riskStatus        风险状态 (NORMAL/FROZEN/RECALLED)
 * @param recallNotice       模拟召回声明 (仅在 riskStatus=RECALLED 时非空返回系统演练提示，其余为 null)
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
        TemperatureSummaryProjection temperatureSummary,
        String flowStatus,
        String riskStatus,
        String recallNotice,
        String queriedAt,
        String disclosure
) {

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
     */
    public record TimelineItem(
            String event,
            String occurredAt,
            String dataSourceLabel
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
