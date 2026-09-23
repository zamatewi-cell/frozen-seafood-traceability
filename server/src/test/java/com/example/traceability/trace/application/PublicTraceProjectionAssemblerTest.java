package com.example.traceability.trace.application;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchLineageEdge;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.trace.application.PublicTraceProjectionAssembler.Assembly;
import com.example.traceability.trace.application.PublicTraceProjectionAssembler.LineageIntegrityException;
import com.example.traceability.trace.domain.TraceEvent;
import com.example.traceability.trace.dto.PublicTraceProjectionResponse.LineageEdge;
import com.example.traceability.trace.dto.PublicTraceProjectionResponse.LineageNode;
import com.example.traceability.trace.dto.PublicTraceProjectionResponse.TimelineItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 消费者公开投影纯函数组装器单元测试：谱系世代、DAG 去重、兄弟排除、完整性失败关闭、公开事件白名单与稳定排序。
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@DisplayName("公开追溯投影组装器（谱系 / 白名单 / 稳定排序）单元测试")
class PublicTraceProjectionAssemblerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 8, 0);
    private static final long B0 = 101L;
    private static final long B1 = 102L;
    private static final long B2 = 103L;
    private static final long B3 = 104L;

    // ------------------------------------------------------------------ 谱系

    @Test
    @DisplayName("B0 → PROCESS → B1 → SPLIT → B2：3 个节点、世代 0/1/2、角色 ORIGIN/INTERMEDIATE/TARGET、谱系边来自批次操作")
    void chainLineage() {
        Assembly a = assemble(B2, chainEdges(), batches(B0, B1, B2), List.of());

        assertThat(a.lineage().nodes()).extracting(LineageNode::nodeKey).containsExactly("N1", "N2", "N3");
        assertThat(a.lineage().nodes()).extracting(LineageNode::generation).containsExactly(0, 1, 2);
        assertThat(a.lineage().nodes()).extracting(LineageNode::role).containsExactly("ORIGIN", "INTERMEDIATE", "TARGET");
        assertThat(a.lineage().nodes()).extracting(LineageNode::productName).containsExactly("原料鱼", "加工鱼片", "加工鱼片");
        assertThat(a.lineage().edges()).containsExactly(
                new LineageEdge("N1", "N2", "PROCESS", "2026-09-01T09:00:00Z"),
                new LineageEdge("N2", "N3", "SPLIT", "2026-09-01T10:00:00Z"));
    }

    @Test
    @DisplayName("来源批次直接扫码：唯一节点既是目标，角色为 TARGET，无边")
    void singleNodeLineage() {
        Assembly a = assemble(B0, List.of(), batches(B0), List.of());
        assertThat(a.lineage().nodes()).containsExactly(new LineageNode("N1", 0, "TARGET", "原料鱼"));
        assertThat(a.lineage().edges()).isEmpty();
    }

    @Test
    @DisplayName("MERGE 菱形 DAG：共同祖先只出现一次；世代按最长上游路径；节点、边顺序确定")
    void diamondDagDedupAndLongestPath() {
        long x = 1L;
        long y1 = 2L;
        long y2 = 3L;
        long c = 4L;
        long z = 5L;
        // X → Y1、X → Y2（拆分）；Y1 → C（加工）；C、Y2 → Z（合并）：Z 的世代按最长路径 X→Y1→C→Z = 3
        List<BatchLineageEdge> edges = List.of(
                edge(x, y1, "SPLIT", 1), edge(x, y2, "SPLIT", 1),
                edge(y1, c, "PROCESS", 2),
                edge(c, z, "MERGE", 3), edge(y2, z, "MERGE", 3));
        Assembly a = assemble(z, edges, batches(x, y1, y2, c, z), List.of());

        assertThat(a.lineage().nodes()).hasSize(5);
        assertThat(a.lineage().nodes()).extracting(LineageNode::generation).containsExactly(0, 1, 1, 2, 3);
        assertThat(a.lineage().nodes()).extracting(LineageNode::role)
                .containsExactly("ORIGIN", "INTERMEDIATE", "INTERMEDIATE", "INTERMEDIATE", "TARGET");
        assertThat(a.lineage().edges()).extracting(e -> e.fromNodeKey() + ">" + e.toNodeKey())
                .containsExactly("N1>N2", "N1>N3", "N2>N4", "N3>N5", "N4>N5");
    }

    @Test
    @DisplayName("输入边顺序打乱不影响输出（稳定、可重复）")
    void shuffledInputSameOutput() {
        List<BatchLineageEdge> edges = new ArrayList<>(chainEdges());
        List<TraceEvent> events = chainEvents();
        Assembly first = assemble(B2, edges, batches(B0, B1, B2), events);
        Collections.reverse(edges);
        List<TraceEvent> reversed = new ArrayList<>(events);
        Collections.reverse(reversed);
        Assembly second = assemble(B2, edges, batches(B2, B1, B0), reversed);
        assertThat(second).isEqualTo(first);
    }

    // ------------------------------------------------------------------ 谱系完整性：失败关闭

    @Test
    @DisplayName("祖先批次缺失：拒绝（不把 B0 → B1 → B2 静默变成 B1 → B2）")
    void missingAncestorFailsClosed() {
        LineageIntegrityException ex = assertThrows(LineageIntegrityException.class,
                () -> assemble(B2, chainEdges(), batches(B1, B2), List.of()));
        assertThat(ex.getMessage()).contains("缺失");
    }

    @Test
    @DisplayName("祖先批次已逻辑删除：拒绝")
    void deletedAncestorFailsClosed() {
        Map<Long, Batch> batches = batches(B0, B1, B2);
        batches.get(B0).setIsDeleted(1);
        assertThrows(LineageIntegrityException.class, () -> assemble(B2, chainEdges(), batches, List.of()));
    }

    @Test
    @DisplayName("谱系边引用的批次操作缺失 / 未提交 / 已删除 / 无业务时间 / 类型未受控：一律拒绝")
    void unresolvedOperationFailsClosed() {
        List<Consumer<BatchLineageEdge>> corruptions = List.of(
                e -> e.setOperationId(null),
                e -> e.setOperationStatus("DRAFT"),
                e -> e.setOperationStatus("CORRECTED"),
                e -> e.setOperationDeleted(1),
                e -> e.setOperationOccurredAt(null),
                e -> e.setOperationType("PACK"),
                e -> e.setOperationType(null));
        for (Consumer<BatchLineageEdge> corruption : corruptions) {
            List<BatchLineageEdge> edges = chainEdges();
            corruption.accept(edges.get(0));
            assertThrows(LineageIntegrityException.class, () -> assemble(B2, edges, batches(B0, B1, B2), List.of()));
        }
    }

    @Test
    @DisplayName("畸形图结构：自环、重复边、空引用、环 —— 一律拒绝")
    void malformedGraphFailsClosed() {
        assertThrows(LineageIntegrityException.class,
                () -> assemble(B1, List.of(edge(B1, B1, "PROCESS", 1)), batches(B1), List.of()));
        assertThrows(LineageIntegrityException.class,
                () -> assemble(B1, List.of(edge(B0, B1, "PROCESS", 1), edge(B0, B1, "PROCESS", 1)), batches(B0, B1), List.of()));
        assertThrows(LineageIntegrityException.class,
                () -> assemble(B1, List.of(new BatchLineageEdge(null, B1, "TRANSFORM", 9L, "PROCESS", "SUBMITTED", 0, T0)), batches(B1), List.of()));
        // 环：B0 → B1 → B2 → B0（写路径已做环检测，读路径仍失败关闭）
        List<BatchLineageEdge> cycle = List.of(edge(B0, B1, "PROCESS", 1), edge(B1, B2, "SPLIT", 2), edge(B2, B0, "PROCESS", 3));
        LineageIntegrityException ex = assertThrows(LineageIntegrityException.class,
                () -> assemble(B2, cycle, batches(B0, B1, B2), List.of()));
        assertThat(ex.getMessage()).contains("环");
    }

    // ------------------------------------------------------------------ 公开事件白名单

    @Test
    @DisplayName("公开事件白名单与契约 v1.1 §11 事件族完全一致：不含 PURCHASE，且不从 Java 枚举推导")
    void publicEventWhitelistIsExplicit() {
        assertThat(PublicTraceProjectionAssembler.PUBLIC_EVENT_TYPES.keySet()).containsExactlyInAnyOrder(
                "SOURCE", "PROCESS", "FREEZE", "PACK", "WAREHOUSE_IN", "WAREHOUSE_OUT", "TRANSPORT", "ARRIVAL", "SALE");
        assertThat(PublicTraceProjectionAssembler.PUBLIC_EVENT_TYPES).doesNotContainKey("PURCHASE");
        assertThat(PublicTraceProjectionAssembler.PUBLIC_OPERATION_TYPES).containsExactlyInAnyOrder("PROCESS", "SPLIT", "MERGE", "REPACK");
    }

    @Test
    @DisplayName("PURCHASE、未知类型与兄弟 / 无关批次事件不进入公开时间线；未知数据来源使用固定标签，绝不回显原值")
    void nonPublicEventsExcluded() {
        List<TraceEvent> events = List.of(
                event(1, B0, "SOURCE", T0, "MANUAL"),
                event(2, B0, "PURCHASE", T0.plusMinutes(1), "MANUAL"),
                event(3, B1, "RAW_UNKNOWN_TYPE_SENTINEL", T0.plusMinutes(2), "MANUAL"),
                event(4, B3, "SALE", T0.plusMinutes(3), "MANUAL"),          // 兄弟批次 B3 的事件
                event(5, B2, "SALE", T0.plusMinutes(4), "RAW_SOURCE_SENTINEL"));
        Assembly a = assemble(B2, chainEdges(), batches(B0, B1, B2), events);

        assertThat(a.timeline()).extracting(TimelineItem::eventType).containsExactly("SOURCE", "SALE");
        assertThat(a.timeline()).extracting(TimelineItem::nodeKey).containsExactly("N1", "N3");
        assertThat(a.timeline().get(1).dataSourceLabel()).isEqualTo("未标明来源");
        assertThat(a.toString()).doesNotContain("PURCHASE").doesNotContain("RAW_UNKNOWN_TYPE_SENTINEL").doesNotContain("RAW_SOURCE_SENTINEL");
    }

    // ------------------------------------------------------------------ 稳定排序

    @Test
    @DisplayName("相同业务时间：先按谱系世代（祖先在前），再按显式公开事件权重，再按登记时间与事件 ID")
    void equalTimestampOrdering() {
        LocalDateTime same = T0.plusHours(5);
        List<TraceEvent> events = List.of(
                // 目标批次（世代 2）：SALE 与 WAREHOUSE_IN 同时发生，ID 与登记时间故意倒序
                event(50, B2, "SALE", same, "MANUAL"),
                event(40, B2, "WAREHOUSE_IN", same, "MANUAL"),
                // 中间批次（世代 1）
                event(30, B1, "FREEZE", same, "MANUAL"),
                // 最上游批次（世代 0）：两条 ARRIVAL 仅登记时间不同，一条 SOURCE
                eventRecorded(21, B0, "ARRIVAL", same, same.plusSeconds(2)),
                eventRecorded(22, B0, "ARRIVAL", same, same.plusSeconds(1)),
                event(10, B0, "SOURCE", same, "MANUAL"));
        Assembly a = assemble(B2, chainEdges(), batches(B0, B1, B2), events);

        assertThat(a.timeline()).extracting(i -> i.nodeKey() + ":" + i.eventType()).containsExactly(
                "N1:SOURCE", "N1:ARRIVAL", "N1:ARRIVAL", "N2:FREEZE", "N3:WAREHOUSE_IN", "N3:SALE");
        // 同类型同时间：登记时间更早者在前（ID 22 早于 ID 21 登记）
        List<TraceEvent> sorted = new ArrayList<>(events);
        sorted.sort(PublicTraceProjectionAssembler.timelineOrder(Map.of(B0, 0, B1, 1, B2, 2)));
        assertThat(sorted).extracting(TraceEvent::getId).containsExactly(10L, 22L, 21L, 30L, 40L, 50L);
    }

    @Test
    @DisplayName("同一批次、同一时间、九类公开事件：顺序只由显式 PUBLIC_EVENT_ORDER 决定（与枚举声明顺序、ID、输入顺序无关）")
    void publicEventOrderPinned() {
        List<String> expected = List.of("SOURCE", "PROCESS", "FREEZE", "PACK", "WAREHOUSE_IN", "WAREHOUSE_OUT", "TRANSPORT", "ARRIVAL", "SALE");
        List<TraceEvent> events = new ArrayList<>();
        long id = 100;
        for (int i = expected.size() - 1; i >= 0; i--) {
            events.add(event(id++, B0, expected.get(i), T0, "MANUAL"));
        }
        Assembly a = assemble(B0, List.of(), batches(B0), events);
        assertThat(a.timeline()).extracting(TimelineItem::eventType).containsExactlyElementsOf(expected);
        int previous = Integer.MIN_VALUE;
        for (String type : expected) {
            int order = PublicTraceProjectionAssembler.PUBLIC_EVENT_TYPES.get(type).order();
            assertThat(order).isGreaterThan(previous);
            previous = order;
        }
    }

    @Test
    @DisplayName("时间线项目只输出白名单字段：类型、固定标签、UTC 时间、来源标签、节点键")
    void timelineItemShape() {
        Assembly a = assemble(B2, chainEdges(), batches(B0, B1, B2), chainEvents());
        assertThat(a.timeline()).first().isEqualTo(new TimelineItem("SOURCE", "原料采收/出塘", "2026-09-01T08:00:00Z", "企业人工填报", "N1"));
        assertThat(a.timeline()).extracting(TimelineItem::event).doesNotHaveDuplicates().allSatisfy(label -> assertThat(label).isNotBlank());
    }

    // ------------------------------------------------------------------ 模拟召回提示

    @Test
    @DisplayName("模拟召回提示只由批次 riskStatus=RECALLED 决定，CLOSED 与 ACTIVE 措辞不同，均声明教学演练")
    void recallNotice() {
        assertThat(PublicTraceProjectionAssembler.recallNotice("ACTIVE", "NORMAL")).isNull();
        assertThat(PublicTraceProjectionAssembler.recallNotice("CLOSED", "FROZEN")).isNull();
        assertThat(PublicTraceProjectionAssembler.recallNotice("ACTIVE", "RECALLED")).contains("流通环节已暂停").contains("教学演练");
        assertThat(PublicTraceProjectionAssembler.recallNotice("CLOSED", "RECALLED")).contains("已结束正常流转").contains("教学演练")
                .doesNotContain("流通环节已暂停");
    }

    // ------------------------------------------------------------------ fixtures

    private static Assembly assemble(long target, List<BatchLineageEdge> edges, Map<Long, Batch> batches, List<TraceEvent> events) {
        Product raw = new Product();
        raw.setId(1L);
        raw.setPublicName("原料鱼");
        Product fillet = new Product();
        fillet.setId(2L);
        fillet.setPublicName("加工鱼片");
        return PublicTraceProjectionAssembler.assemble(target, edges, batches, Map.of(1L, raw, 2L, fillet), events);
    }

    private static List<BatchLineageEdge> chainEdges() {
        return new ArrayList<>(List.of(edge(B0, B1, "PROCESS", 1), edge(B1, B2, "SPLIT", 2)));
    }

    private static List<TraceEvent> chainEvents() {
        return List.of(
                event(1, B0, "SOURCE", T0, "MANUAL"),
                event(2, B0, "TRANSPORT", T0.plusMinutes(10), "MANUAL"),
                event(3, B0, "ARRIVAL", T0.plusMinutes(20), "MANUAL"),
                event(4, B1, "PROCESS", T0.plusHours(1), "MANUAL"),
                event(5, B2, "WAREHOUSE_IN", T0.plusHours(3), "MANUAL"),
                event(6, B2, "SALE", T0.plusHours(6), "MANUAL"));
    }

    private static BatchLineageEdge edge(long parent, long child, String operationType, int hour) {
        String relation = switch (operationType) {
            case "SPLIT" -> "SPLIT";
            case "MERGE" -> "MERGE";
            default -> "TRANSFORM";
        };
        return new BatchLineageEdge(parent, child, relation, 900L + child, operationType, "SUBMITTED", 0, T0.plusHours(hour));
    }

    private static Map<Long, Batch> batches(long... ids) {
        Map<Long, Batch> map = new HashMap<>();
        for (long id : ids) {
            Batch b = new Batch();
            b.setId(id);
            b.setProductId(id == B0 || id == 1L ? 1L : 2L);
            b.setIsDeleted(0);
            map.put(id, b);
        }
        return map;
    }

    private static TraceEvent event(long id, long batchId, String type, LocalDateTime occurredAt, String dataSource) {
        TraceEvent e = new TraceEvent();
        e.setId(id);
        e.setBatchId(batchId);
        e.setEventType(type);
        e.setOccurredAt(occurredAt);
        e.setRecordedAt(occurredAt);
        e.setDataSource(dataSource);
        return e;
    }

    private static TraceEvent eventRecorded(long id, long batchId, String type, LocalDateTime occurredAt, LocalDateTime recordedAt) {
        TraceEvent e = event(id, batchId, type, occurredAt, "MANUAL");
        e.setRecordedAt(recordedAt);
        return e;
    }
}
