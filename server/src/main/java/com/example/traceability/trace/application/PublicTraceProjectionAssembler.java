package com.example.traceability.trace.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchFlowStatus;
import com.example.traceability.batch.domain.BatchLineageEdge;
import com.example.traceability.batch.domain.BatchRiskStatus;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.trace.domain.TraceEvent;
import com.example.traceability.trace.dto.PublicTraceProjectionResponse.LineageEdge;
import com.example.traceability.trace.dto.PublicTraceProjectionResponse.LineageNode;
import com.example.traceability.trace.dto.PublicTraceProjectionResponse.LineageProjection;
import com.example.traceability.trace.dto.PublicTraceProjectionResponse.TimelineItem;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 消费者公开投影的纯函数组装器：谱系校验与世代计算、公开事件白名单与稳定排序。
 * <p>
 * 不访问数据库、不依赖 Spring。输入为服务端已按集合批量读取的祖先谱系边、批次、产品与有效事件；
 * 输出只包含公开白名单字段。任何谱系结构不完整（节点缺失 / 已删除、批次操作无法解析、未知操作类型、
 * 自环、重复边或环）一律抛出 {@link LineageIntegrityException}，绝不返回被截断的“看似完整”的谱系。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
public final class PublicTraceProjectionAssembler {

    /** 节点角色：最上游批次。 */
    public static final String ROLE_ORIGIN = "ORIGIN";
    /** 节点角色：中间批次。 */
    public static final String ROLE_INTERMEDIATE = "INTERMEDIATE";
    /** 节点角色：扫码目标批次。 */
    public static final String ROLE_TARGET = "TARGET";

    /**
     * 消费者公开事件白名单（统一业务契约 v1.1 §11 定义的公开链事件族），每类固定公开标签与公开排序权重。
     * <p>
     * 由公开投影层显式持有：不从 {@code TraceEventType.values()} 或数据库现有取值推导，
     * 调整 Java 枚举声明顺序不会改变公开 API 的排序。PURCHASE 等契约未定义的遗留类型不在白名单内，
     * 未知类型也永远不会以数据库原值回显给匿名消费者。
     * </p>
     */
    static final Map<String, PublicEventType> PUBLIC_EVENT_TYPES = Map.of(
            "SOURCE", new PublicEventType("原料采收/出塘", 10),
            "PROCESS", new PublicEventType("粗加工/精加工", 30),
            "FREEZE", new PublicEventType("速冻冷冻", 40),
            "PACK", new PublicEventType("分装与包装", 50),
            "WAREHOUSE_IN", new PublicEventType("冷库入库", 60),
            "WAREHOUSE_OUT", new PublicEventType("冷库出库", 70),
            "TRANSPORT", new PublicEventType("冷链干线运输", 80),
            "ARRIVAL", new PublicEventType("冷链运输到达", 90),
            "SALE", new PublicEventType("终端零售销售", 100)
    );

    /** 公开谱系边允许的批次操作类型（其他任何取值视为谱系完整性错误）。 */
    static final Set<String> PUBLIC_OPERATION_TYPES = Set.of("PROCESS", "SPLIT", "MERGE", "REPACK");

    private static final String OPERATION_STATUS_SUBMITTED = "SUBMITTED";

    private static final String FALLBACK_PRODUCT_NAME = "冷冻水产品";

    private static final String UNKNOWN_DATA_SOURCE_LABEL = "未标明来源";

    private static final String SIMULATED_RECALL_NOTICE_ACTIVE =
            "此批次海产品已启动系统模拟召回演练，流通环节已暂停，请联系销售商或质量管理部门处理（本提示为系统教学演练模拟信息）。";

    private static final String SIMULATED_RECALL_NOTICE_CLOSED =
            "此批次海产品已结束正常流转（已售罄或处置完毕），并已进入系统模拟召回演练，如持有该批次产品请联系销售商或质量管理部门处理（本提示为系统教学演练模拟信息）。";

    private PublicTraceProjectionAssembler() {
    }

    /**
     * 公开事件类型的固定标签与公开排序权重。
     *
     * @param label 公开标签
     * @param order 公开排序权重（同一业务时间、同一世代内的确定性次序）
     */
    record PublicEventType(String label, int order) {
    }

    /**
     * 谱系结构完整性错误。消息只用于服务端日志，可以包含内部 ID，绝不直接返回给消费者。
     */
    public static final class LineageIntegrityException extends RuntimeException {
        LineageIntegrityException(String message) {
            super(message);
        }
    }

    /**
     * 组装结果。
     *
     * @param lineage  公开谱系
     * @param timeline 公开时间线
     */
    public record Assembly(LineageProjection lineage, List<TimelineItem> timeline) {
    }

    /**
     * 校验祖先谱系边，并返回谱系应包含的全部批次 ID（目标批次 + 每条边引用的父、子批次）。
     *
     * @param targetBatchId 目标批次 ID
     * @param edges         祖先谱系边
     * @return 期望节点集合
     * @throws LineageIntegrityException 边引用的批次操作无法解析、类型未知、自环或重复
     */
    public static Set<Long> expectedNodeIds(Long targetBatchId, List<BatchLineageEdge> edges) {
        Set<Long> nodes = new TreeSet<>();
        nodes.add(Objects.requireNonNull(targetBatchId, "targetBatchId"));
        Set<String> seenPairs = new HashSet<>();
        for (BatchLineageEdge edge : edges) {
            Long parent = edge.getParentBatchId();
            Long child = edge.getChildBatchId();
            if (parent == null || child == null) {
                throw new LineageIntegrityException("谱系边缺少父或子批次引用: parent=" + parent + ", child=" + child);
            }
            if (Objects.equals(parent, child)) {
                throw new LineageIntegrityException("谱系边自环: batch=" + parent);
            }
            if (!seenPairs.add(parent + ">" + child)) {
                throw new LineageIntegrityException("重复谱系边: " + parent + " -> " + child);
            }
            if (edge.getOperationId() == null
                    || !OPERATION_STATUS_SUBMITTED.equals(edge.getOperationStatus())
                    || !Objects.equals(edge.getOperationDeleted(), 0)
                    || edge.getOperationOccurredAt() == null) {
                throw new LineageIntegrityException("谱系边引用的批次操作无法解析为已提交有效操作: "
                        + parent + " -> " + child + ", operationId=" + edge.getOperationId()
                        + ", status=" + edge.getOperationStatus() + ", deleted=" + edge.getOperationDeleted());
            }
            if (edge.getOperationType() == null || !PUBLIC_OPERATION_TYPES.contains(edge.getOperationType())) {
                throw new LineageIntegrityException("谱系边批次操作类型未受控: " + parent + " -> " + child
                        + ", operationType=" + edge.getOperationType());
            }
            nodes.add(parent);
            nodes.add(child);
        }
        return nodes;
    }

    /**
     * 组装公开谱系与时间线。
     *
     * @param targetBatchId 目标批次 ID
     * @param edges         祖先谱系边（须已通过 {@link #expectedNodeIds} 校验）
     * @param batches       已批量读取的有效（未删除）批次，按 ID 索引
     * @param products      已批量读取的产品，按 ID 索引
     * @param events        目标批次与祖先批次的有效事件
     * @return 公开谱系与时间线
     * @throws LineageIntegrityException 节点缺失 / 已删除，或谱系成环
     */
    public static Assembly assemble(
            Long targetBatchId,
            List<BatchLineageEdge> edges,
            Map<Long, Batch> batches,
            Map<Long, Product> products,
            List<TraceEvent> events
    ) {
        Set<Long> expected = expectedNodeIds(targetBatchId, edges);
        Set<Long> missing = new TreeSet<>();
        for (Long id : expected) {
            Batch batch = batches.get(id);
            if (batch == null || Objects.equals(batch.getIsDeleted(), 1)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            throw new LineageIntegrityException("谱系节点缺失或已逻辑删除: target=" + targetBatchId + ", missing=" + missing);
        }

        Map<Long, Integer> generation = computeGenerations(expected, edges);
        Map<Long, Integer> incoming = new HashMap<>();
        for (BatchLineageEdge edge : edges) {
            incoming.merge(edge.getChildBatchId(), 1, Integer::sum);
        }

        List<Long> orderedNodes = new ArrayList<>(expected);
        orderedNodes.sort(Comparator.<Long>comparingInt(generation::get).thenComparing(Comparator.naturalOrder()));
        Map<Long, Integer> index = new HashMap<>();
        List<LineageNode> nodes = new ArrayList<>();
        for (Long id : orderedNodes) {
            index.put(id, index.size());
            String role = Objects.equals(id, targetBatchId) ? ROLE_TARGET
                    : incoming.getOrDefault(id, 0) == 0 ? ROLE_ORIGIN : ROLE_INTERMEDIATE;
            nodes.add(new LineageNode(nodeKey(index.get(id)), generation.get(id), role, productName(batches.get(id), products)));
        }

        List<BatchLineageEdge> orderedEdges = new ArrayList<>(edges);
        orderedEdges.sort(Comparator.<BatchLineageEdge>comparingInt(e -> index.get(e.getChildBatchId()))
                .thenComparingInt(e -> index.get(e.getParentBatchId())));
        List<LineageEdge> publicEdges = orderedEdges.stream()
                .map(e -> new LineageEdge(
                        nodeKey(index.get(e.getParentBatchId())),
                        nodeKey(index.get(e.getChildBatchId())),
                        e.getOperationType(),
                        formatUtc(e.getOperationOccurredAt())))
                .toList();

        List<TraceEvent> publicEvents = new ArrayList<>();
        for (TraceEvent event : events) {
            if (event.getBatchId() != null && index.containsKey(event.getBatchId())
                    && event.getEventType() != null
                    && PUBLIC_EVENT_TYPES.containsKey(event.getEventType())
                    && event.getOccurredAt() != null) {
                publicEvents.add(event);
            }
        }
        publicEvents.sort(timelineOrder(generation));
        List<TimelineItem> timeline = publicEvents.stream()
                .map(e -> new TimelineItem(
                        e.getEventType(),
                        PUBLIC_EVENT_TYPES.get(e.getEventType()).label(),
                        formatUtc(e.getOccurredAt()),
                        dataSourceLabel(e.getDataSource()),
                        nodeKey(index.get(e.getBatchId()))))
                .toList();

        return new Assembly(new LineageProjection(nodes, publicEdges), timeline);
    }

    /**
     * 公开时间线确定性排序：业务发生时间 → 谱系世代（祖先在前）→ 公开事件排序权重 → 登记时间 → 事件 ID。
     * 登记时间与事件 ID 只作为服务端内部稳定次序，不进入公开响应。
     */
    static Comparator<TraceEvent> timelineOrder(Map<Long, Integer> generation) {
        return Comparator.comparing(TraceEvent::getOccurredAt)
                .thenComparingInt(e -> generation.getOrDefault(e.getBatchId(), Integer.MAX_VALUE))
                .thenComparingInt(e -> PUBLIC_EVENT_TYPES.get(e.getEventType()).order())
                .thenComparing(TraceEvent::getRecordedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TraceEvent::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * Kahn 拓扑排序计算世代（最长上游路径）：最上游为 0，子节点为全部父节点世代最大值 + 1。
     * 无法消费全部节点表示存在环，按谱系完整性错误拒绝。
     */
    static Map<Long, Integer> computeGenerations(Set<Long> nodes, List<BatchLineageEdge> edges) {
        Map<Long, List<Long>> children = new HashMap<>();
        Map<Long, Integer> indegree = new HashMap<>();
        for (Long id : nodes) {
            indegree.put(id, 0);
        }
        for (BatchLineageEdge edge : edges) {
            children.computeIfAbsent(edge.getParentBatchId(), k -> new ArrayList<>()).add(edge.getChildBatchId());
            indegree.merge(edge.getChildBatchId(), 1, Integer::sum);
        }
        Map<Long, Integer> generation = new HashMap<>();
        Deque<Long> ready = new ArrayDeque<>();
        for (Long id : nodes) {
            if (indegree.get(id) == 0) {
                generation.put(id, 0);
                ready.add(id);
            }
        }
        int visited = 0;
        while (!ready.isEmpty()) {
            Long current = ready.poll();
            visited++;
            for (Long child : children.getOrDefault(current, List.of())) {
                generation.merge(child, generation.get(current) + 1, Math::max);
                if (indegree.merge(child, -1, Integer::sum) == 0) {
                    ready.add(child);
                }
            }
        }
        if (visited != nodes.size()) {
            throw new LineageIntegrityException("谱系存在环，无法完成拓扑排序: nodes=" + nodes);
        }
        return generation;
    }

    /**
     * 模拟召回提示：只由批次风险状态 RECALLED 决定，与公开追溯码自身状态无关；按流转状态选择措辞。
     *
     * @param flowStatus 批次流转状态
     * @param riskStatus 批次风险状态
     * @return 模拟召回提示；非 RECALLED 时为 null
     */
    public static String recallNotice(String flowStatus, String riskStatus) {
        if (!BatchRiskStatus.RECALLED.name().equals(riskStatus)) {
            return null;
        }
        return BatchFlowStatus.CLOSED.name().equals(flowStatus) ? SIMULATED_RECALL_NOTICE_CLOSED : SIMULATED_RECALL_NOTICE_ACTIVE;
    }

    /**
     * 数据来源的固定公开标签；未知取值使用固定占位标签，绝不回显数据库原值。
     */
    static String dataSourceLabel(String dataSource) {
        if (dataSource == null) {
            return "企业人工填报";
        }
        return switch (dataSource) {
            case "SIMULATED" -> "教学演练与仿真模拟数据（SIMULATED）";
            case "DEVICE" -> "标准预留设备标识（DEVICE，未接入真实硬件）";
            case "MANUAL" -> "企业人工填报";
            case "IMPORT" -> "企业系统导入";
            default -> UNKNOWN_DATA_SOURCE_LABEL;
        };
    }

    static String formatUtc(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String nodeKey(int index) {
        return "N" + (index + 1);
    }

    private static String productName(Batch batch, Map<Long, Product> products) {
        Product product = batch.getProductId() != null ? products.get(batch.getProductId()) : null;
        return product != null && product.getPublicName() != null ? product.getPublicName() : FALLBACK_PRODUCT_NAME;
    }
}
