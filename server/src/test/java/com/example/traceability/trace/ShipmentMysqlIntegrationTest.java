package com.example.traceability.trace;

import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.trace.application.TraceEventApplicationService;
import com.example.traceability.trace.dto.ShipmentCancelRequest;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 冷链运输任务 (Shipment) 真实 MySQL 8.4 端到端集成测试（统一业务契约 v1.1 §7 / §11，Phase A Slice 2）。
 * <p>
 * 覆盖：PLANNED 创建与参与方校验、多交接装载、同批次唯一与同参与方的服务端 + 数据库双重防线、
 * 三方权限隔离、幂等、真实并发（发运 / 装载清单变更 / 到达）、PLANNED → IN_TRANSIT → DELIVERED 形状、
 * 发运后禁止变更装载清单、TRANSPORT / ARRIVAL 按 Batch 精确一条的自动投影与重试不重复。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("冷链运输任务 MySQL 8.4 端到端集成测试")
class ShipmentMysqlIntegrationTest extends AbstractTransferShipmentMysqlIT {

    private Long activeBatch(String tag) throws Exception {
        return createAndSubmitActiveBatch(senderSession, "BAT-SHP-" + tag + "-" + suffix, new BigDecimal("1000.000"));
    }

    @Test
    @DisplayName("完整链：PLANNED 创建 → 绑定 → 提交 → 发运 (TRANSPORT×1) → 到达 (ARRIVAL×1)，责任组织全程不变，事件可追溯到运输任务")
    void fullLifecycle_projectsExactlyOneTransportAndArrivalPerBatch() throws Exception {
        Long batchId = activeBatch("FULL");
        Long transferId = createDraftTransfer(senderSession, batchId, receiverOrg.getId());

        JsonNode created = expect(createShipmentRequest(senderSession, key("idem-shp-full"), carrierOrg.getId(), senderSite.getId(), receiverSite.getId()), 201).get("data");
        Long shipmentId = created.get("id").asLong();
        createdShipmentIds.add(shipmentId);
        assertThat(created.get("status").asString()).isEqualTo("PLANNED");
        assertThat(created.get("senderOrg").get("id").asLong()).isEqualTo(senderOrg.getId());
        assertThat(created.get("receiverOrg").get("id").asLong()).isEqualTo(receiverOrg.getId());
        assertThat(created.get("carrierOrg").get("orgType").asString()).isEqualTo("CARRIER");
        assertThat(created.get("originSite").get("id").asLong()).isEqualTo(senderSite.getId());
        assertThat(created.get("destinationSite").get("id").asLong()).isEqualTo(receiverSite.getId());
        assertThat(created.has("loadedAt")).isFalse();
        assertThat(created.has("isDeleted")).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT loaded_at FROM shipment WHERE id = ?", Object.class, shipmentId)).isNull();

        bind(shipmentId, transferId);
        submit(transferId);
        assertThat(countEvents(batchId, "TRANSPORT")).isZero();

        JsonNode dispatched = dispatch(shipmentId);
        assertThat(dispatched.get("status").asString()).isEqualTo("IN_TRANSIT");
        assertThat(dispatched.has("loadedAt")).isTrue();
        assertThat(dispatched.get("dispatchedBy").asLong()).isEqualTo(carrierUser.getId());
        assertThat(countEvents(batchId, "TRANSPORT")).isEqualTo(1);
        assertThat(countEvents(batchId, "ARRIVAL")).isZero();
        assertThat(batchOrgId(batchId)).isEqualTo(senderOrg.getId());
        assertThat(transferStatus(transferId)).isEqualTo("PENDING");

        Map<String, Object> transport = jdbcTemplate.queryForMap(
                "SELECT e.org_id, e.site_id, e.operator_id, e.occurred_at, s.loaded_at, e.idempotency_key, "
                        + "JSON_UNQUOTE(JSON_EXTRACT(e.details_json, '$.sourceObjectType')) AS sot, "
                        + "CAST(JSON_UNQUOTE(JSON_EXTRACT(e.details_json, '$.sourceObjectId')) AS UNSIGNED) AS soid, "
                        + "CAST(JSON_UNQUOTE(JSON_EXTRACT(e.details_json, '$.carrierOrgId')) AS UNSIGNED) AS carrier, "
                        + "CAST(JSON_UNQUOTE(JSON_EXTRACT(e.details_json, '$.originSiteId')) AS UNSIGNED) AS origin, "
                        + "CAST(JSON_UNQUOTE(JSON_EXTRACT(e.details_json, '$.destinationSiteId')) AS UNSIGNED) AS dest "
                        + "FROM trace_event e JOIN shipment s ON s.id = ? WHERE e.batch_id = ? AND e.event_type = 'TRANSPORT'",
                shipmentId, batchId);
        assertThat(((Number) transport.get("org_id")).longValue()).isEqualTo(senderOrg.getId());
        assertThat(((Number) transport.get("site_id")).longValue()).isEqualTo(senderSite.getId());
        assertThat(((Number) transport.get("operator_id")).longValue()).isEqualTo(carrierUser.getId());
        assertThat(transport.get("occurred_at")).isEqualTo(transport.get("loaded_at"));
        assertThat(transport.get("sot")).isEqualTo("SHIPMENT");
        assertThat(((Number) transport.get("soid")).longValue()).isEqualTo(shipmentId);
        assertThat(((Number) transport.get("carrier")).longValue()).isEqualTo(carrierOrg.getId());
        assertThat(((Number) transport.get("origin")).longValue()).isEqualTo(senderSite.getId());
        assertThat(((Number) transport.get("dest")).longValue()).isEqualTo(receiverSite.getId());
        assertThat(transport.get("idempotency_key")).isEqualTo(TraceEventApplicationService.shipmentTransportEventIdempotencyKey(shipmentId, batchId));

        JsonNode delivered = arrive(shipmentId);
        assertThat(delivered.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(delivered.has("unloadedAt")).isTrue();
        assertThat(countEvents(batchId, "ARRIVAL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ? AND event_type = 'ARRIVAL' AND site_id = ? AND org_id = ?",
                batchId, receiverSite.getId(), senderOrg.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM shipment WHERE id = ? AND unloaded_at >= loaded_at AND delivered_by = ?", shipmentId, carrierUser.getId())).isEqualTo(1);
        // DELIVERED 不自动接受交接，也不改变批次责任组织
        assertThat(transferStatus(transferId)).isEqualTo("PENDING");
        assertThat(batchOrgId(batchId)).isEqualTo(senderOrg.getId());
    }

    @Test
    @DisplayName("一个运输任务装载多个交接：每个 Batch 各一条 TRANSPORT 与 ARRIVAL；装载清单展示两张交接")
    void multipleTransfersInOneShipment() throws Exception {
        Long b1 = activeBatch("M1");
        Long b2 = activeBatch("M2");
        Long t1 = createDraftTransfer(senderSession, b1, receiverOrg.getId());
        Long t2 = createDraftTransfer(senderSession, b2, receiverOrg.getId());
        Long shipmentId = createShipment(senderSession);
        bind(shipmentId, t1);
        JsonNode afterSecond = bind(shipmentId, t2);
        assertThat(afterSecond.get("transfers")).extracting(t -> t.get("transferId").asLong()).containsExactly(t1, t2);

        // 装载清单中仍有 DRAFT 交接时禁止发运
        submit(t1);
        expectProblem(dispatchRequest(carrierSession, shipmentId, shipmentVersion(shipmentId), key("idem-m-d0")), 409, "SHIPMENT_TRANSFER_NOT_PENDING");
        submit(t2);

        dispatch(shipmentId);
        arrive(shipmentId);
        for (Long b : List.of(b1, b2)) {
            assertThat(countEvents(b, "TRANSPORT")).isEqualTo(1);
            assertThat(countEvents(b, "ARRIVAL")).isEqualTo(1);
        }

        TransferAcceptRequest acc = new TransferAcceptRequest(new BigDecimal("1000.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 2L);
        expect(postJson(receiverSession, "/api/v1/transfers/" + t1 + "/accept", key("idem-m-a1"), acc), 200);
        expect(postJson(receiverSession, "/api/v1/transfers/" + t2 + "/accept", key("idem-m-a2"), acc), 200);
        assertThat(batchOrgId(b1)).isEqualTo(receiverOrg.getId());
        assertThat(batchOrgId(b2)).isEqualTo(receiverOrg.getId());
        assertThat(countEvents(b1, "ARRIVAL") + countEvents(b2, "ARRIVAL")).isEqualTo(2);
    }

    @Test
    @DisplayName("创建校验：承运方必须为 CARRIER、启运场所属于发货方、目的场所属于其他非承运组织、承运组织不能作为发货方")
    void createValidation() throws Exception {
        Organization otherProcessor = createOrg("ORG_P2_" + suffix, "其他加工企业-" + suffix, "PROCESSOR");
        Site otherSenderSite = createSite(senderOrg.getId(), "SRC-2-" + suffix, "来源第二码头-" + suffix, "PORT");
        Site carrierHub = createSite(carrierOrg.getId(), "CAR-HUB-" + suffix, "物流中心-" + suffix, "LOGISTICS_HUB");

        expectProblem(createShipmentRequest(senderSession, key("idem-cv-1"), otherProcessor.getId(), senderSite.getId(), receiverSite.getId()), 422, "CARRIER_ORG_INVALID");
        expectProblem(createShipmentRequest(senderSession, key("idem-cv-2"), carrierOrg.getId(), receiverSite.getId(), receiverSite.getId()), 422, "SITE_ORG_MISMATCH");
        expectProblem(createShipmentRequest(senderSession, key("idem-cv-3"), carrierOrg.getId(), senderSite.getId(), otherSenderSite.getId()), 422, "SHIPMENT_SAME_ORGANIZATION");
        expectProblem(createShipmentRequest(senderSession, key("idem-cv-4"), carrierOrg.getId(), senderSite.getId(), carrierHub.getId()), 422, "RECEIVER_ORG_TYPE_NOT_ALLOWED");
        expectProblem(createShipmentRequest(carrierSession, key("idem-cv-5"), carrierOrg.getId(), carrierHub.getId(), receiverSite.getId()), 403, "ROLE_NOT_ALLOWED");
        assertThat(count("SELECT count(*) FROM shipment WHERE sender_org_id IN (?, ?)", senderOrg.getId(), carrierOrg.getId())).isZero();
    }

    @Test
    @DisplayName("同一 Batch 在同一运输任务中只能出现一次：服务端拒绝重复绑定，数据库唯一约束兜底")
    void duplicateBatchInSameShipment_rejectedByServiceAndDatabase() throws Exception {
        Long batchId = activeBatch("DUP");
        Long transferId = createDraftTransfer(senderSession, batchId, receiverOrg.getId());
        Long shipmentId = createShipment(senderSession);
        bind(shipmentId, transferId);
        expectProblem(bindRequest(senderSession, shipmentId, transferId, transferVersion(transferId)), 409, "TRANSFER_BOUND_TO_SHIPMENT");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO transfer (transfer_no, batch_id, shipment_id, sender_org_id, receiver_org_id, quantity, unit_code,
                    status, idempotency_key, version, is_deleted, is_legacy)
                VALUES (?, ?, ?, ?, ?, 1000.000, 'kg', 'DRAFT', ?, 0, 0, 1)
                """, "TRF-DUP-" + suffix, batchId, shipmentId, senderOrg.getId(), receiverOrg.getId(), key("idem-raw-dup")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_transfer_shipment_batch");
    }

    @Test
    @DisplayName("同一运输任务禁止混装不同接收方：服务端 422 SHIPMENT_PARTY_MISMATCH，数据库复合外键兜底")
    void mixedReceiver_rejectedByServiceAndDatabase() throws Exception {
        Organization otherProcessor = createOrg("ORG_P3_" + suffix, "其他加工企业-" + suffix, "PROCESSOR");
        Long batchId = activeBatch("MIX");
        Long otherTransfer = createDraftTransfer(senderSession, batchId, otherProcessor.getId());
        Long shipmentId = createShipment(senderSession);

        expectProblem(bindRequest(senderSession, shipmentId, otherTransfer, 0L), 422, "SHIPMENT_PARTY_MISMATCH");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE transfer SET shipment_id = ? WHERE id = ?", shipmentId, otherTransfer))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_transfer_shipment_parties");
    }

    @Test
    @DisplayName("三方权限隔离：发货方 / 承运方 / 接收方动作分离，无关组织全部 403，不存在 404")
    void permissionMatrix() throws Exception {
        Organization thirdOrg = createOrg("ORG_X_" + suffix, "无关企业-" + suffix, "RETAILER");
        MockHttpSession thirdSession = newSession(thirdOrg, "shp_thd", "OPERATOR", "OWN_ORG");
        Long batchId = activeBatch("PERM");
        Long transferId = createDraftTransfer(senderSession, batchId, receiverOrg.getId());
        Long shipmentId = createShipment(senderSession);

        // 只有发货方能绑定
        for (MockHttpSession s : List.of(carrierSession, receiverSession, thirdSession)) {
            expectProblem(bindRequest(s, shipmentId, transferId, 0L), 403, "ORG_SCOPE_DENIED");
        }
        bind(shipmentId, transferId);
        submit(transferId);

        // 只有指定承运方能发运
        for (MockHttpSession s : List.of(senderSession, receiverSession, thirdSession)) {
            expectProblem(dispatchRequest(s, shipmentId, shipmentVersion(shipmentId), key("idem-p-d")), 403, "ORG_SCOPE_DENIED");
        }
        Organization otherCarrier = createOrg("ORG_C2_" + suffix, "其他承运企业-" + suffix, "CARRIER");
        MockHttpSession otherCarrierSession = newSession(otherCarrier, "car2", "OPERATOR", "OWN_ORG");
        expectProblem(dispatchRequest(otherCarrierSession, shipmentId, shipmentVersion(shipmentId), key("idem-p-d2")), 403, "ORG_SCOPE_DENIED");
        dispatch(shipmentId);

        // 只有指定承运方能确认到达
        for (MockHttpSession s : List.of(senderSession, receiverSession, thirdSession, otherCarrierSession)) {
            expectProblem(arriveRequest(s, shipmentId, shipmentVersion(shipmentId), key("idem-p-a")), 403, "ORG_SCOPE_DENIED");
        }

        // 三方可读，无关组织不可读，不存在返回 404
        for (MockHttpSession s : List.of(senderSession, carrierSession, receiverSession)) {
            expect(getReq(s, "/api/v1/shipments/" + shipmentId), 200);
        }
        expectProblem(getReq(thirdSession, "/api/v1/shipments/" + shipmentId), 403, "ORG_SCOPE_DENIED");
        expect(getReq(thirdSession, "/api/v1/shipments/" + (shipmentId + 999_999)), 404);
        assertThat(expect(getReq(thirdSession, "/api/v1/shipments"), 200).get("data")).isEmpty();
        assertThat(expect(getReq(carrierSession, "/api/v1/shipments?role=CARRIER"), 200).get("data")).hasSize(1);
        assertThat(expect(getReq(receiverSession, "/api/v1/shipments?role=RECEIVER&status=IN_TRANSIT"), 200).get("data")).hasSize(1);
        assertThat(expect(getReq(receiverSession, "/api/v1/shipments?role=SENDER"), 200).get("data")).isEmpty();

        // 承运商不能接受交接，也不会成为批次责任组织
        arrive(shipmentId);
        TransferAcceptRequest acc = new TransferAcceptRequest(new BigDecimal("1000.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, 2L);
        expectProblem(postJson(carrierSession, "/api/v1/transfers/" + transferId + "/accept", key("idem-p-acc"), acc), 403, "ORG_SCOPE_DENIED");
        assertThat(batchOrgId(batchId)).isEqualTo(senderOrg.getId());
    }

    @Test
    @DisplayName("幂等：创建 / 绑定 / 发运 / 到达同 key 重放返回原结果不重复事件；新 key 重试 409 且不产生重复事件")
    void idempotencyAndRetries() throws Exception {
        Long batchId = activeBatch("IDEM");
        Long transferId = createDraftTransfer(senderSession, batchId, receiverOrg.getId());

        String createKey = key("idem-i-create");
        Long shipmentId = expect(createShipmentRequest(senderSession, createKey, carrierOrg.getId(), senderSite.getId(), receiverSite.getId()), 201).get("data").get("id").asLong();
        createdShipmentIds.add(shipmentId);
        assertThat(expect(createShipmentRequest(senderSession, createKey, carrierOrg.getId(), senderSite.getId(), receiverSite.getId()), 201).get("data").get("id").asLong()).isEqualTo(shipmentId);
        expectProblem(createShipmentRequest(senderSession, createKey, carrierOrg.getId(), receiverSite.getId(), senderSite.getId()), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(count("SELECT count(*) FROM shipment WHERE sender_org_id = ?", senderOrg.getId())).isEqualTo(1);

        String bindKey = key("idem-i-bind");
        var bindReq = new com.example.traceability.trace.dto.ShipmentBindTransferRequest(transferId, 0L);
        expect(postJson(senderSession, "/api/v1/shipments/" + shipmentId + "/transfers", bindKey, bindReq), 200);
        expect(postJson(senderSession, "/api/v1/shipments/" + shipmentId + "/transfers", bindKey, bindReq), 200);
        assertThat(transferVersion(transferId)).isEqualTo(1L);
        submit(transferId);

        String dispatchKey = key("idem-i-dispatch");
        long v = shipmentVersion(shipmentId);
        expect(dispatchRequest(carrierSession, shipmentId, v, dispatchKey), 200);
        // 同 key 不同语义（不同装载时间）→ 409 IDEMPOTENCY_CONFLICT；同语义重放见 dispatchReplayWithIdenticalPayload
        var otherPayload = new com.example.traceability.trace.dto.ShipmentDispatchRequest(OffsetDateTime.now(ZoneOffset.UTC).minusHours(3), v);
        expectProblem(postJson(carrierSession, "/api/v1/shipments/" + shipmentId + "/dispatch", dispatchKey, otherPayload), 409, "IDEMPOTENCY_CONFLICT");
        expectProblem(dispatchRequest(carrierSession, shipmentId, shipmentVersion(shipmentId), key("idem-i-dispatch-2")), 409, "INVALID_STATE_TRANSITION");
        assertThat(countEvents(batchId, "TRANSPORT")).isEqualTo(1);

        String arriveKey = key("idem-i-arrive");
        OffsetDateTime unloadedAt = OffsetDateTime.now(ZoneOffset.UTC);
        var arriveReq = new com.example.traceability.trace.dto.ShipmentArriveRequest(unloadedAt, shipmentVersion(shipmentId));
        JsonNode first = expect(postJson(carrierSession, "/api/v1/shipments/" + shipmentId + "/arrive", arriveKey, arriveReq), 200).get("data");
        JsonNode again = expect(postJson(carrierSession, "/api/v1/shipments/" + shipmentId + "/arrive", arriveKey, arriveReq), 200).get("data");
        assertThat(again.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(again.get("version").asLong()).isEqualTo(first.get("version").asLong());
        expectProblem(arriveRequest(carrierSession, shipmentId, shipmentVersion(shipmentId), key("idem-i-arrive-2")), 409, "INVALID_STATE_TRANSITION");
        assertThat(countEvents(batchId, "ARRIVAL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND object_type = 'SHIPMENT' AND action = 'ARRIVE'", carrierOrg.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("同 key 同语义发运重放：返回原结果，TRANSPORT 仍为一条")
    void dispatchReplayWithIdenticalPayload() throws Exception {
        Long batchId = activeBatch("REPLAY");
        Handover h = preparePendingHandover(batchId);
        String dispatchKey = key("idem-replay-d");
        var req = new com.example.traceability.trace.dto.ShipmentDispatchRequest(OffsetDateTime.now(ZoneOffset.UTC), shipmentVersion(h.shipmentId()));
        JsonNode first = expect(postJson(carrierSession, "/api/v1/shipments/" + h.shipmentId() + "/dispatch", dispatchKey, req), 200).get("data");
        JsonNode replay = expect(postJson(carrierSession, "/api/v1/shipments/" + h.shipmentId() + "/dispatch", dispatchKey, req), 200).get("data");
        assertThat(replay.get("version").asLong()).isEqualTo(first.get("version").asLong());
        assertThat(countEvents(batchId, "TRANSPORT")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND object_type = 'SHIPMENT' AND action = 'DISPATCH'", carrierOrg.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("状态机：空装载清单不可发运、未发运不可到达、发运后禁止增删交接、持有旧版本号的发运被检测为冲突")
    void stateMachineAndManifestFreeze() throws Exception {
        Long emptyShipment = createShipment(senderSession);
        expectProblem(dispatchRequest(carrierSession, emptyShipment, shipmentVersion(emptyShipment), key("idem-sm-empty")), 409, "SHIPMENT_EMPTY");
        expectProblem(arriveRequest(carrierSession, emptyShipment, shipmentVersion(emptyShipment), key("idem-sm-arr")), 409, "INVALID_STATE_TRANSITION");

        Long b1 = activeBatch("SM1");
        Long b2 = activeBatch("SM2");
        Long t1 = createDraftTransfer(senderSession, b1, receiverOrg.getId());
        Long t2 = createDraftTransfer(senderSession, b2, receiverOrg.getId());
        Long shipmentId = createShipment(senderSession);
        long versionBeforeBind = shipmentVersion(shipmentId);
        bind(shipmentId, t1);
        submit(t1);
        assertThat(shipmentVersion(shipmentId)).isEqualTo(versionBeforeBind + 1);

        // 承运商持有绑定前的旧版本号：装载清单已变化，发运被乐观锁拒绝
        expectProblem(dispatchRequest(carrierSession, shipmentId, versionBeforeBind, key("idem-sm-stale")), 409, "VERSION_CONFLICT");

        // 解绑后再绑定都会递增版本
        bind(shipmentId, t2);
        long v = shipmentVersion(shipmentId);
        expect(deleteReq(senderSession, "/api/v1/shipments/" + shipmentId + "/transfers/" + t2 + "?expectedTransferVersion=" + transferVersion(t2)), 200);
        assertThat(shipmentVersion(shipmentId)).isEqualTo(v + 1);
        assertThat(jdbcTemplate.queryForObject("SELECT shipment_id FROM transfer WHERE id = ?", Long.class, t2)).isNull();

        // 已提交 (PENDING) 的交接不能被移出装载清单
        expectProblem(deleteReq(senderSession, "/api/v1/shipments/" + shipmentId + "/transfers/" + t1 + "?expectedTransferVersion=" + transferVersion(t1)), 409, "INVALID_STATE_TRANSITION");

        dispatch(shipmentId);
        expectProblem(bindRequest(senderSession, shipmentId, t2, transferVersion(t2)), 409, "SHIPMENT_NOT_PLANNED");
        expectProblem(deleteReq(senderSession, "/api/v1/shipments/" + shipmentId + "/transfers/" + t1 + "?expectedTransferVersion=" + transferVersion(t1)), 409, "SHIPMENT_NOT_PLANNED");
        expectProblem(postJson(senderSession, "/api/v1/shipments/" + shipmentId + "/cancel", key("idem-sm-cxl"),
                new ShipmentCancelRequest("发运后取消", shipmentVersion(shipmentId))), 409, "INVALID_STATE_TRANSITION");
        assertThat(expect(getReq(senderSession, "/api/v1/shipments/" + shipmentId), 200).get("data").get("transfers")).hasSize(1);
        assertThat(countEvents(b2, "TRANSPORT")).isZero();
    }

    @Test
    @DisplayName("删除已绑定 DRAFT 交接：清除绑定、递增运输任务版本，使持有旧版本号的发运被检测为冲突")
    void deleteBoundDraft_bumpsShipmentVersion() throws Exception {
        Long b1 = activeBatch("DEL1");
        Long b2 = activeBatch("DEL2");
        Long t1 = createDraftTransfer(senderSession, b1, receiverOrg.getId());
        Long t2 = createDraftTransfer(senderSession, b2, receiverOrg.getId());
        Long shipmentId = createShipment(senderSession);
        bind(shipmentId, t1);
        bind(shipmentId, t2);
        submit(t1);
        long carrierViewVersion = shipmentVersion(shipmentId);

        expect(deleteReq(senderSession, "/api/v1/transfers/" + t2 + "?expectedVersion=" + transferVersion(t2)), 204);
        assertThat(shipmentVersion(shipmentId)).isEqualTo(carrierViewVersion + 1);
        assertThat(count("SELECT count(*) FROM transfer WHERE id = ? AND is_deleted = 1 AND shipment_id IS NULL", t2)).isEqualTo(1);

        expectProblem(dispatchRequest(carrierSession, shipmentId, carrierViewVersion, key("idem-del-stale")), 409, "VERSION_CONFLICT");
        dispatch(shipmentId);
        assertThat(countEvents(b1, "TRANSPORT")).isEqualTo(1);
        assertThat(countEvents(b2, "TRANSPORT")).isZero();
    }

    @Test
    @DisplayName("真并发：两个承运商会话同时发运，恰好一个成功，TRANSPORT 恰好一条")
    void concurrentDispatch_exactlyOneWins() throws Exception {
        Long batchId = activeBatch("CD");
        Handover h = preparePendingHandover(batchId);
        MockHttpSession c1 = login(carrierUser.getUsername());
        MockHttpSession c2 = login(carrierUser.getUsername());
        long v = shipmentVersion(h.shipmentId());

        int[] st = race(
                () -> perform(dispatchRequest(c1, h.shipmentId(), v, key("idem-cd-1"))),
                () -> perform(dispatchRequest(c2, h.shipmentId(), v, key("idem-cd-2"))));
        assertThat(List.of(st[0], st[1])).containsExactlyInAnyOrder(200, 409);
        assertThat(shipmentStatus(h.shipmentId())).isEqualTo("IN_TRANSIT");
        assertThat(countEvents(batchId, "TRANSPORT")).isEqualTo(1);
    }

    @Test
    @DisplayName("真并发：发运与绑定竞争，结果与装载清单一致（要么绑定失败，要么发运检测到清单变化失败）")
    void concurrentDispatchVersusBind_manifestConsistent() throws Exception {
        Long b1 = activeBatch("CB1");
        Long b2 = activeBatch("CB2");
        Handover h = preparePendingHandover(b1);
        Long t2 = createDraftTransfer(senderSession, b2, receiverOrg.getId());
        long v = shipmentVersion(h.shipmentId());

        int[] st = race(
                () -> perform(dispatchRequest(carrierSession, h.shipmentId(), v, key("idem-cb-d"))),
                () -> perform(bindRequest(senderSession, h.shipmentId(), t2, 0L)));
        assertThat(st[0] == 200 ^ st[1] == 200).as("dispatch=%d bind=%d", st[0], st[1]).isTrue();
        if (st[0] == 200) {
            assertThat(shipmentStatus(h.shipmentId())).isEqualTo("IN_TRANSIT");
            assertThat(jdbcTemplate.queryForObject("SELECT shipment_id FROM transfer WHERE id = ?", Long.class, t2)).isNull();
        } else {
            assertThat(shipmentStatus(h.shipmentId())).isEqualTo("PLANNED");
            assertThat(jdbcTemplate.queryForObject("SELECT shipment_id FROM transfer WHERE id = ?", Long.class, t2)).isEqualTo(h.shipmentId());
        }
        assertThat(countEvents(b1, "TRANSPORT")).isEqualTo(st[0] == 200 ? 1 : 0);
        assertThat(countEvents(b2, "TRANSPORT")).isZero();
    }

    @Test
    @DisplayName("真并发：发运与删除已绑定草稿竞争，删除必须先锁运输任务，结果与装载清单一致")
    void concurrentDispatchVersusDeleteBoundDraft_manifestConsistent() throws Exception {
        Long b1 = activeBatch("CX1");
        Long b2 = activeBatch("CX2");
        Handover h = preparePendingHandover(b1);
        Long t2 = createDraftTransfer(senderSession, b2, receiverOrg.getId());
        bind(h.shipmentId(), t2);
        long v = shipmentVersion(h.shipmentId());
        long t2Version = transferVersion(t2);

        int[] st = race(
                () -> perform(dispatchRequest(carrierSession, h.shipmentId(), v, key("idem-cx-d"))),
                () -> perform(deleteReq(senderSession, "/api/v1/transfers/" + t2 + "?expectedVersion=" + t2Version)));
        // 装载清单含 DRAFT：发运只有在删除先完成且版本未变时才可能成功，但删除会递增版本，因此发运必然失败
        assertThat(st[0]).isEqualTo(409);
        assertThat(st[1]).isEqualTo(204);
        assertThat(shipmentStatus(h.shipmentId())).isEqualTo("PLANNED");
        assertThat(countEvents(b1, "TRANSPORT")).isZero();
        dispatch(h.shipmentId());
        assertThat(countEvents(b1, "TRANSPORT")).isEqualTo(1);
    }

    @Test
    @DisplayName("真并发：两个到达确认竞争，恰好一个成功，ARRIVAL 恰好一条")
    void concurrentArrive_exactlyOneWins() throws Exception {
        Long batchId = activeBatch("CA");
        Handover h = preparePendingHandover(batchId);
        dispatch(h.shipmentId());
        MockHttpSession c1 = login(carrierUser.getUsername());
        MockHttpSession c2 = login(carrierUser.getUsername());
        long v = shipmentVersion(h.shipmentId());

        int[] st = race(
                () -> perform(arriveRequest(c1, h.shipmentId(), v, key("idem-ca-1"))),
                () -> perform(arriveRequest(c2, h.shipmentId(), v, key("idem-ca-2"))));
        assertThat(List.of(st[0], st[1])).containsExactlyInAnyOrder(200, 409);
        assertThat(countEvents(batchId, "ARRIVAL")).isEqualTo(1);
    }

    @Test
    @DisplayName("事务原子性：TRANSPORT 事件身份被预先占用时发运整体回滚（运输任务仍 PLANNED，无幂等与审计记录）")
    void transportEventConflict_rollsBackDispatch() throws Exception {
        Long batchId = activeBatch("RB");
        Handover h = preparePendingHandover(batchId);
        String sysKey = TraceEventApplicationService.shipmentTransportEventIdempotencyKey(h.shipmentId(), batchId);
        jdbcTemplate.update("""
                INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, operator_id, data_source, status,
                    idempotency_key, summary, details_json, version, is_deleted, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, 'TRANSPORT', NOW(6), NOW(6), ?, 'MANUAL', 'SUBMITTED', ?, '冲突预置事件', '{}', 0, 0, NOW(6), ?, NOW(6), ?)
                """, batchId, senderOrg.getId(), senderUser.getId(), sysKey, senderUser.getId(), senderUser.getId());
        long v = shipmentVersion(h.shipmentId());
        String dispatchKey = key("idem-rb-d");

        expectProblem(dispatchRequest(carrierSession, h.shipmentId(), v, dispatchKey), 409, "SHIPMENT_EVENT_CONFLICT");
        assertThat(shipmentStatus(h.shipmentId())).isEqualTo("PLANNED");
        assertThat(shipmentVersion(h.shipmentId())).isEqualTo(v);
        assertThat(count("SELECT count(*) FROM shipment WHERE id = ? AND loaded_at IS NULL", h.shipmentId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM shipment_idempotency WHERE org_id = ? AND idempotency_key = ?", carrierOrg.getId(), dispatchKey)).isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'DISPATCH'", carrierOrg.getId())).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT summary FROM trace_event WHERE batch_id = ? AND event_type = 'TRANSPORT'", String.class, batchId))
                .containsExactly("冲突预置事件");
    }

    @Test
    @DisplayName("人工事件接口不得伪造 TRANSPORT / ARRIVAL（422 EVENT_TYPE_NOT_MANUAL）")
    void manualTransportAndArrivalEvents_rejected() throws Exception {
        Long batchId = activeBatch("MAN");
        for (String type : List.of("TRANSPORT", "ARRIVAL")) {
            expectProblem(postJson(senderSession, "/api/v1/batches/" + batchId + "/events", key("idem-man-" + type.toLowerCase()),
                    Map.of("eventType", type, "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString(),
                            "dataSource", "MANUAL", "summary", "人工伪造")), 422, "EVENT_TYPE_NOT_MANUAL");
        }
        assertThat(countEvents(batchId, "TRANSPORT") + countEvents(batchId, "ARRIVAL")).isZero();
    }

    @Test
    @DisplayName("目录：已认证企业用户可读取启用承运组织列表与接收方启用场所（不含地址）")
    void directoryListsCarriersAndSites() throws Exception {
        JsonNode carriers = expect(getReq(senderSession, "/api/v1/organizations?orgType=CARRIER"), 200).get("data");
        assertThat(carriers).extracting(o -> o.get("id").asLong()).contains(carrierOrg.getId());
        assertThat(carriers).allSatisfy(o -> assertThat(o.get("orgType").asString()).isEqualTo("CARRIER"));
        JsonNode sites = expect(getReq(senderSession, "/api/v1/organizations/" + receiverOrg.getId() + "/sites"), 200).get("data");
        assertThat(sites).extracting(s -> s.get("id").asLong()).containsExactly(receiverSite.getId());
        assertThat(sites.get(0).has("addressText")).isFalse();
        assertThat(expect(getReq(senderSession, "/api/v1/organizations/" + receiverOrg.getId()), 200).get("data").get("name").asString())
                .isEqualTo(receiverOrg.getName());
    }

    // =========================================================================
    // 并发辅助
    // =========================================================================

    @FunctionalInterface
    private interface Call {
        MvcResult run() throws Exception;
    }

    private int[] race(Call first, Call second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<MvcResult> r1 = new AtomicReference<>();
        AtomicReference<MvcResult> r2 = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            executor.submit(() -> runRace(barrier, latch, first, r1, err));
            executor.submit(() -> runRace(barrier, latch, second, r2, err));
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        }
        if (err.get() != null) {
            throw new AssertionError("concurrent request failed", err.get());
        }
        return new int[]{r1.get().getResponse().getStatus(), r2.get().getResponse().getStatus()};
    }

    private static void runRace(CyclicBarrier barrier, CountDownLatch latch, Call call,
                                AtomicReference<MvcResult> out, AtomicReference<Throwable> err) {
        try {
            barrier.await();
            out.set(call.run());
        } catch (Throwable t) {
            err.set(t);
        } finally {
            latch.countDown();
        }
    }
}
