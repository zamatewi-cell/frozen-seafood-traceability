package com.example.traceability.trace.dto;

import java.util.List;

/**
 * 消费者公开追溯信息投影响应 DTO。
 * <p>
 * 严格只暴露白名单安全字段，绝不暴露内部数据库主键 ID、组织/用户/操作人/场所标识、
 * 创建组织 (creationOrgId)、内部追溯批次号 (traceBatchNo)、原始产地 (raw origin_text)、
 * 扩展属性 (detailsJson)、事件摘要 (summary)、幂等键、哈希指纹 (token_hash)、逻辑删除或审计字段。
 * 谱系节点只使用响应内局部键 {@code nodeKey}（N1、N2 …）关联，不是内部批次 ID。
 * </p>
 *
 * @param publicTraceId      消费者公开追溯编码 (26 位 Base32)
 * @param product            目标批次产品公开属性投影
 * @param batch              目标批次脱敏属性投影
 * @param lineage            目标批次及其允许公开的祖先谱系（只含祖先，不含兄弟或下游批次）
 * @param timeline           目标批次与祖先批次的有效公开事件时间线 (排除 CORRECTED 原事件与非公开事件类型)
 * @param temperatureSummary 冷链温控摘要（当前恒为 INSUFFICIENT_DATA）
 * @param flowStatus         目标批次业务流转状态 (DRAFT/ACTIVE/CLOSED)
 * @param riskStatus         目标批次风险状态 (NORMAL/FROZEN/RECALLED)
 * @param recallNotice       模拟召回声明 (仅在 riskStatus=RECALLED 时非空返回系统演练提示，其余为 null)
 * @param queriedAt          查询时间 (带明确 UTC 偏移的 ISO 8601 字符串)
 * @param disclosure         真实性与教学演练声明
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public record PublicTraceProjectionResponse(
        String publicTraceId,
        ProductProjection product,
        BatchProjection batch,
        LineageProjection lineage,
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
     * 公开谱系：节点按世代升序排列，边按子节点、父节点顺序排列。
     *
     * @param nodes 谱系节点（目标批次恰好一个 TARGET 节点）
     * @param edges 谱系边（由已提交批次操作与 BatchRelation 支撑，不是追溯事件）
     */
    public record LineageProjection(
            List<LineageNode> nodes,
            List<LineageEdge> edges
    ) {
    }

    /**
     * 公开谱系节点。
     *
     * @param nodeKey     响应内局部键 (N1、N2 …)，按世代与服务端稳定次序分配，不是内部批次 ID
     * @param generation  世代（最上游为 0，按最长上游路径计算）
     * @param role        ORIGIN（最上游批次）/ INTERMEDIATE（中间批次）/ TARGET（扫码批次）
     * @param productName 该批次产品的消费者公开名称
     */
    public record LineageNode(
            String nodeKey,
            int generation,
            String role,
            String productName
    ) {
    }

    /**
     * 公开谱系边：一次已提交的物料转换（加工 / 拆分 / 合并 / 分装）。
     *
     * @param fromNodeKey   上游节点键
     * @param toNodeKey     下游节点键
     * @param operationType 批次操作类型 (PROCESS/SPLIT/MERGE/REPACK)
     * @param occurredAt    批次操作业务发生时间 (带明确 UTC 偏移的 ISO 8601 字符串)
     */
    public record LineageEdge(
            String fromNodeKey,
            String toNodeKey,
            String operationType,
            String occurredAt
    ) {
    }

    /**
     * 供应链追溯事件时间线项目。
     *
     * @param eventType       公开白名单内的事件类型代码
     * @param event           事件受控业务标签
     * @param occurredAt      业务实际发生时间 (带明确 UTC 偏移的 ISO 8601 字符串)
     * @param dataSourceLabel 诚实的数据来源标签说明
     * @param nodeKey         事件所属谱系节点键
     */
    public record TimelineItem(
            String eventType,
            String event,
            String occurredAt,
            String dataSourceLabel,
            String nodeKey
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
