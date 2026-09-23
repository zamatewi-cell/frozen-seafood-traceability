package com.example.traceability.trace;

import com.example.traceability.identity.domain.Site;
import com.example.traceability.trace.dto.CorrectTraceEventRequest;
import com.example.traceability.trace.dto.CreateTraceEventRequest;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自有冷库出入库（WAREHOUSE_IN / WAREHOUSE_OUT）真实 MySQL 8.4 端到端集成测试（统一业务契约 v1.1 §8 / §11，Phase A Slice 4）。
 * <p>
 * 覆盖：当前责任组织使用本组织启用冷库记录出入库、批次行（责任组织 / 数量 / 双状态 / 版本）不变、
 * 不产生批次 / 谱系 / 交接 / 运输副作用、跨组织与非冷库场所拒绝、CLOSED 后禁止新建仓储流转但允许审计更正、
 * 幂等重放与并发、与 Transfer ACCEPT 的批次行锁串行化。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("自有冷库出入库 MySQL 8.4 端到端集成测试")
class WarehouseEventMysqlIntegrationTest extends AbstractTransferShipmentMysqlIT {

    private Site processorColdStore;
    private Site processorColdStoreB;
    private Site sourceColdStore;

    @BeforeEach
    void setUpColdStores() {
        processorColdStore = createSite(receiverOrg.getId(), "PRC-COLD-" + suffix, "加工企业自有冷库-" + suffix, "COLD_STORE");
        processorColdStoreB = createSite(receiverOrg.getId(), "PRC-COLD2-" + suffix, "加工企业二号冷库-" + suffix, "COLD_STORE");
        sourceColdStore = createSite(senderOrg.getId(), "SRC-COLD-" + suffix, "来源企业冷库-" + suffix, "COLD_STORE");
    }

    private static CreateTraceEventRequest warehouse(String type, Long siteId) {
        return new CreateTraceEventRequest(type, OffsetDateTime.now(ZoneOffset.UTC), siteId, "MANUAL",
                "WAREHOUSE_IN".equals(type) ? "冷库入库" : "冷库出库", null);
    }

    private MockHttpServletRequestBuilder recordEvent(MockHttpSession session, Long batchId, String idemKey, Object body) throws Exception {
        return postJson(session, "/api/v1/batches/" + batchId + "/events", idemKey, body);
    }

    private Map<String, Object> batchRow(Long batchId) {
        return jdbcTemplate.queryForMap(
                "SELECT org_id, quantity, flow_status, risk_status, version, product_id, trace_batch_no FROM batch WHERE id = ?", batchId);
    }

    /** 本用例组织范围内的批次 / 谱系 / 交接 / 运输数量快照。 */
    private List<Integer> sideEffectCounts(Long batchId) {
        return List.of(
                count("SELECT count(*) FROM batch WHERE creation_org_id IN (?, ?) OR org_id IN (?, ?)",
                        senderOrg.getId(), receiverOrg.getId(), senderOrg.getId(), receiverOrg.getId()),
                count("SELECT count(*) FROM batch_relation WHERE parent_batch_id = ? OR child_batch_id = ?", batchId, batchId),
                count("SELECT count(*) FROM transfer WHERE batch_id = ?", batchId),
                count("SELECT count(*) FROM shipment WHERE sender_org_id IN (?, ?)", senderOrg.getId(), receiverOrg.getId()));
    }

    private int warehouseEvents(Long batchId) {
        return count("SELECT count(*) FROM trace_event WHERE batch_id = ? AND event_type IN ('WAREHOUSE_IN', 'WAREHOUSE_OUT')", batchId);
    }

    @Test
    @DisplayName("加工企业 IN → OUT → 另一冷库 OUT（不强制配对 / 顺序）：批次行完全不变，无批次 / 谱系 / 交接 / 运输副作用")
    void inAndOut_ownColdStore_batchUnchanged() throws Exception {
        Long batchId = processorHeldBatch("BAT-WH-FULL-" + suffix, new BigDecimal("600.000"));
        Map<String, Object> before = batchRow(batchId);
        List<Integer> sideEffectsBefore = sideEffectCounts(batchId);

        JsonNode in = expect(recordEvent(receiverSession, batchId, key("idem-wh-in"), warehouse("WAREHOUSE_IN", processorColdStore.getId())), 201).get("data");
        assertThat(in.get("eventType").asString()).isEqualTo("WAREHOUSE_IN");
        assertThat(in.get("siteId").asLong()).isEqualTo(processorColdStore.getId());
        assertThat(in.get("orgId").asLong()).isEqualTo(receiverOrg.getId());
        expect(recordEvent(receiverSession, batchId, key("idem-wh-out"), warehouse("WAREHOUSE_OUT", processorColdStore.getId())), 201);
        expect(recordEvent(receiverSession, batchId, key("idem-wh-out2"), warehouse("WAREHOUSE_OUT", processorColdStoreB.getId())), 201);

        assertThat(batchRow(batchId)).isEqualTo(before);
        assertThat(sideEffectCounts(batchId)).isEqualTo(sideEffectsBefore);
        assertThat(countEvents(batchId, "WAREHOUSE_IN")).isEqualTo(1);
        assertThat(countEvents(batchId, "WAREHOUSE_OUT")).isEqualTo(2);
        assertThat(count("""
                SELECT count(*) FROM trace_event e JOIN site s ON s.id = e.site_id
                 WHERE e.batch_id = ? AND e.event_type IN ('WAREHOUSE_IN', 'WAREHOUSE_OUT')
                   AND e.org_id = ? AND s.org_id = ? AND s.site_type = 'COLD_STORE' AND s.status = 'ACTIVE'
                   AND e.data_source = 'MANUAL' AND e.status = 'SUBMITTED' AND e.details_json IS NULL
                """, batchId, receiverOrg.getId(), receiverOrg.getId())).isEqualTo(3);

        JsonNode timeline = expect(getReq(receiverSession, "/api/v1/batches/" + batchId + "/events"), 200).get("data");
        assertThat(timeline).extracting(e -> e.get("eventType").asString())
                .containsExactly("SOURCE", "TRANSPORT", "ARRIVAL", "WAREHOUSE_IN", "WAREHOUSE_OUT", "WAREHOUSE_OUT");
    }

    @Test
    @DisplayName("越权与伪造拒绝：他组织冷库 403、本组织加工厂 422、缺场所 400、非 MANUAL 400、details 400、人工 SALE 422；无任何写入")
    void forgedWarehouseFacts_rejectedWithoutWrites() throws Exception {
        Long batchId = processorHeldBatch("BAT-WH-NEG-" + suffix, new BigDecimal("360.000"));
        Map<String, Object> before = batchRow(batchId);
        int eventsBefore = count("SELECT count(*) FROM trace_event WHERE batch_id = ?", batchId);
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);

        expectProblem(recordEvent(receiverSession, batchId, key("idem-wh-x1"), warehouse("WAREHOUSE_IN", sourceColdStore.getId())), 403, "ORG_SCOPE_DENIED");
        expectProblem(recordEvent(receiverSession, batchId, key("idem-wh-x2"), warehouse("WAREHOUSE_IN", receiverSite.getId())), 422, "WAREHOUSE_SITE_TYPE_INVALID");
        expectProblem(recordEvent(receiverSession, batchId, key("idem-wh-x3"), warehouse("WAREHOUSE_OUT", null)), 400, "INVALID_REQUEST");
        expectProblem(recordEvent(receiverSession, batchId, key("idem-wh-x4"),
                new CreateTraceEventRequest("WAREHOUSE_IN", at, processorColdStore.getId(), "DEVICE", "设备入库", null)), 400, "INVALID_REQUEST");
        expectProblem(recordEvent(receiverSession, batchId, key("idem-wh-x5"),
                new CreateTraceEventRequest("WAREHOUSE_IN", at, processorColdStore.getId(), "MANUAL", "部分入库", Map.of("quantity", 100))), 400, "INVALID_REQUEST");
        expectProblem(recordEvent(receiverSession, batchId, key("idem-wh-x6"),
                new CreateTraceEventRequest("SALE", at, null, "MANUAL", "伪造销售", null)), 422, "EVENT_TYPE_NOT_MANUAL");
        // 原来源企业（历史参与组织）即使使用自己的冷库也不能再记录
        expectProblem(recordEvent(senderSession, batchId, key("idem-wh-x7"), warehouse("WAREHOUSE_IN", sourceColdStore.getId())), 403, "ORG_SCOPE_DENIED");

        assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ?", batchId)).isEqualTo(eventsBefore);
        assertThat(batchRow(batchId)).isEqualTo(before);
    }

    @Test
    @DisplayName("CLOSED 后禁止新建仓储流转；既有仓储事件仍可审计更正（IN ↔ OUT），但不能把非仓储事件更正为仓储事件")
    void closedBatch_blocksNewMovementButAllowsAuditCorrection() throws Exception {
        Long batchId = processorHeldBatch("BAT-WH-CLS-" + suffix, new BigDecimal("600.000"));
        Long inId = expect(recordEvent(receiverSession, batchId, key("idem-wh-cin"), warehouse("WAREHOUSE_IN", processorColdStore.getId())), 201)
                .get("data").get("id").asLong();
        Long freezeId = expect(recordEvent(receiverSession, batchId, key("idem-wh-frz"),
                new CreateTraceEventRequest("FREEZE", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), null, "MANUAL", "速冻完成", null)), 201)
                .get("data").get("id").asLong();

        // ACTIVE 批次仓储更正允许
        JsonNode activeCorrection = expect(postJson(receiverSession, "/api/v1/batches/" + batchId + "/events/" + inId + "/corrections", key("idem-wh-cor1"),
                new CorrectTraceEventRequest("WAREHOUSE_IN", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2), processorColdStoreB.getId(),
                        "MANUAL", "冷库入库（更正场所）", null, "场所录入错误")), 201).get("data");
        Long correctedInId = activeCorrection.get("id").asLong();

        createAndSubmitOperation("PROCESS", List.of(opInput(batchId, "600.000"), opOutput("600.000")));
        assertThat(batchRow(batchId).get("flow_status")).isEqualTo("CLOSED");
        Map<String, Object> closed = batchRow(batchId);

        expectProblem(recordEvent(receiverSession, batchId, key("idem-wh-cout"), warehouse("WAREHOUSE_OUT", processorColdStore.getId())), 422, "BATCH_FLOW_BLOCKED");
        expectProblem(recordEvent(receiverSession, batchId, key("idem-wh-cin2"), warehouse("WAREHOUSE_IN", processorColdStore.getId())), 422, "BATCH_FLOW_BLOCKED");

        // CLOSED 批次：仓储事件审计更正允许（IN → OUT 方向更正），原版本变为 CORRECTED
        JsonNode closedCorrection = expect(postJson(receiverSession, "/api/v1/batches/" + batchId + "/events/" + correctedInId + "/corrections", key("idem-wh-cor2"),
                new CorrectTraceEventRequest("WAREHOUSE_OUT", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2), processorColdStore.getId(),
                        "MANUAL", "冷库出库（更正方向）", null, "方向录入错误")), 201).get("data");
        assertThat(closedCorrection.get("correctsEventId").asLong()).isEqualTo(correctedInId);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM trace_event WHERE id = ?", String.class, correctedInId)).isEqualTo("CORRECTED");

        // CLOSED 批次：更正也不能绕过冷库规则或把 FREEZE 伪造为仓储流转
        expectProblem(postJson(receiverSession, "/api/v1/batches/" + batchId + "/events/" + closedCorrection.get("id").asLong() + "/corrections", key("idem-wh-cor3"),
                new CorrectTraceEventRequest("WAREHOUSE_OUT", OffsetDateTime.now(ZoneOffset.UTC), receiverSite.getId(), "MANUAL", "改到加工厂", null, "原因")),
                422, "WAREHOUSE_SITE_TYPE_INVALID");
        expectProblem(postJson(receiverSession, "/api/v1/batches/" + batchId + "/events/" + freezeId + "/corrections", key("idem-wh-cor4"),
                new CorrectTraceEventRequest("WAREHOUSE_IN", OffsetDateTime.now(ZoneOffset.UTC), processorColdStore.getId(), "MANUAL", "伪造入库", null, "原因")),
                422, "WAREHOUSE_EVENT_TYPE_CHANGE_FORBIDDEN");

        assertThat(batchRow(batchId)).isEqualTo(closed);
        assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ? AND event_type IN ('WAREHOUSE_IN', 'WAREHOUSE_OUT') AND status = 'SUBMITTED'", batchId)).isEqualTo(1);
    }

    @Test
    @DisplayName("幂等：同键同载荷重放同一事件（含并发），同键不同载荷 409；数据库只落一行")
    void idempotentReplay_andConcurrentSameKey_singleRow() throws Exception {
        Long batchId = processorHeldBatch("BAT-WH-IDEM-" + suffix, new BigDecimal("600.000"));
        CreateTraceEventRequest body = warehouse("WAREHOUSE_IN", processorColdStore.getId());
        String idemKey = key("idem-wh-rep");

        Long first = expect(recordEvent(receiverSession, batchId, idemKey, body), 201).get("data").get("id").asLong();
        Long replay = expect(recordEvent(receiverSession, batchId, idemKey, body), 201).get("data").get("id").asLong();
        assertThat(replay).isEqualTo(first);
        expectProblem(recordEvent(receiverSession, batchId, idemKey, warehouse("WAREHOUSE_OUT", processorColdStore.getId())), 409, "IDEMPOTENCY_CONFLICT");

        String raceKey = key("idem-wh-race");
        CreateTraceEventRequest raceBody = warehouse("WAREHOUSE_OUT", processorColdStore.getId());
        List<MvcResult> results = race(
                () -> perform(recordEvent(receiverSession, batchId, raceKey, raceBody)),
                () -> perform(recordEvent(receiverSession, batchId, raceKey, raceBody)));
        assertThat(results).extracting(r -> r.getResponse().getStatus()).containsOnly(201);
        assertThat(count("SELECT count(*) FROM trace_event WHERE org_id = ? AND idempotency_key = ?", receiverOrg.getId(), raceKey)).isEqualTo(1);
        assertThat(warehouseEvents(batchId)).isEqualTo(2);
    }

    @Test
    @DisplayName("仓储入库与 Transfer ACCEPT 并发：批次行锁串行化，入库只能由当时的责任组织成功记录")
    void warehouseIn_racingAccept_isSerializedByBatchLock() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-WH-RACE-" + suffix, new BigDecimal("1000.000"));
        Handover h = prepareDeliveredHandover(batchId);
        TransferAcceptRequest accept = new TransferAcceptRequest(new BigDecimal("1000.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, transferVersion(h.transferId()));

        List<MvcResult> results = race(
                () -> perform(recordEvent(senderSession, batchId, key("idem-wh-rin"), warehouse("WAREHOUSE_IN", sourceColdStore.getId()))),
                () -> perform(postJson(receiverSession, "/api/v1/transfers/" + h.transferId() + "/accept", key("idem-wh-racc"), accept)));
        int inStatus = results.get(0).getResponse().getStatus();
        assertThat(results.get(1).getResponse().getStatus()).isEqualTo(200);
        assertThat(inStatus).isIn(201, 403);
        assertThat(batchOrgId(batchId)).isEqualTo(receiverOrg.getId());
        if (inStatus == 201) {
            assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ? AND event_type = 'WAREHOUSE_IN' AND org_id = ? AND site_id = ?",
                    batchId, senderOrg.getId(), sourceColdStore.getId())).isEqualTo(1);
        } else {
            assertThat(warehouseEvents(batchId)).isZero();
        }
    }

    private List<MvcResult> race(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> f1 = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return first.call();
            });
            Future<MvcResult> f2 = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return second.call();
            });
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        }
    }
}
