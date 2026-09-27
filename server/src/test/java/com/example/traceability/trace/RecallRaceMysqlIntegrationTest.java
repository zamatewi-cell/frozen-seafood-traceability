package com.example.traceability.trace;

import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.quality.dto.RecallCloseRequest;
import com.example.traceability.quality.dto.RecallCreateRequest;
import com.example.traceability.quality.mapper.AlertActionMapper;
import com.example.traceability.quality.mapper.RecallBatchMapper;
import com.example.traceability.quality.mapper.RecallMapper;
import com.example.traceability.sale.mapper.SaleMapper;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import com.example.traceability.trace.dto.TransferCreateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B PB5 模拟召回的确定性竞态（真实 MySQL 8.4，全部经真实 API）。
 * <p>
 * 召回按批次 ID 升序锁定全部待转换批次，与销售、批次操作、告警放行以及另一次召回都在批次行锁上串行化：
 * <ul>
 *   <li>召回先提交：之后的销售 / 批次操作 / 告警放行 / 重复召回被拒绝（RECALLED 终态），数量不变；引用告警的召回先锁告警再锁批次
 *       （alert → batch，与告警放行同序），停在插入召回行之前也不会与放行死锁；</li>
 *   <li>销售先提交：召回快照记录已售数量；批次操作先提交（产生新的后续批次）：召回检测到范围变化 409 并可重试；
 *       告警放行先提交（批次恢复正常且无不合格证据）：召回 409 证据不足；</li>
 *   <li>交接接受先提交（责任组织转移）：原组织召回 403，新责任组织可凭同一证据召回；召回先提交：接受被阻断、交接保持 PENDING 可拒收；</li>
 *   <li>不同组织对同一谱系的并发召回：范围批次一律按 ID 升序加锁（待转换排他、只快照共享），不会因范围快照的外键检查互相等待；</li>
 *   <li>并发关闭同一召回在召回行锁上串行化，只生效一次。</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AbstractPhaseBRaceMysqlIT.GateConfiguration.class)
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("模拟召回确定性竞态 MySQL 8.4 集成测试（PB5）")
class RecallRaceMysqlIntegrationTest extends AbstractPhaseBRaceMysqlIT {

    private MockHttpServletRequestBuilder recallReq(MockHttpSession session, Long batchId, String idemKey) throws Exception {
        return recallReq(session, batchId, null, idemKey);
    }

    private MockHttpServletRequestBuilder recallReq(MockHttpSession session, Long batchId, Long alertId, String idemKey) throws Exception {
        return postJson(session, "/api/v1/recalls", idemKey, new RecallCreateRequest(List.of(batchId), "检验不合格，模拟召回", alertId));
    }

    /** 零售企业持有、剩余 300 kg、最新检验不合格（证据充分）的 NORMAL 批次。 */
    private Long retailerBatchWithFailEvidence(String tag) throws Exception {
        useRetailerReceiver();
        Long batchId = receiverHeldBatch("EXT-RR-" + tag + "-" + suffix, new BigDecimal("300"));
        inspect(receiverQm(), batchId, "RT-FAIL-" + tag, "FAIL", null);
        return batchId;
    }

    @Test
    @DisplayName("召回先持有批次行锁、销售等待：召回提交后销售被阻断（RECALLED），已售数量为 0")
    void recallFirst_thenSaleBlocked() throws Exception {
        Long batchId = retailerBatchWithFailEvidence("RS");

        AtomicReference<Running> sale = new AtomicReference<>();
        MvcResult recalled = gated(RecallBatchMapper.class, "insert",
                () -> perform(recallReq(receiverQm(), batchId, key("idem-rc"))),
                () -> {
                    sale.set(background("pb5-sale-B", () -> perform(saleRequest(receiverSession, batchId, receiverSite.getId(), "100", key("idem-sale")))));
                    awaitRowLockWait(sale.get().thread, "batch");
                });
        MvcResult sold = sale.get().join();

        assertThat(status(recalled)).isEqualTo(201);
        assertThat(status(sold)).isIn(409, 422);
        assertThat(code(sold)).isEqualTo("BATCH_FLOW_BLOCKED");
        assertThat(riskStatus(batchId)).isEqualTo("RECALLED");
        assertThat(count("SELECT count(*) FROM sale WHERE batch_id = ?", batchId)).isZero();
    }

    @Test
    @DisplayName("销售先持有批次行锁、召回等待：销售提交后召回快照记录已售 100 kg、剩余 200 kg，批次转为 RECALLED")
    void saleFirst_thenRecallSnapshotsSoldQuantity() throws Exception {
        Long batchId = retailerBatchWithFailEvidence("SR");

        AtomicReference<Running> recall = new AtomicReference<>();
        MvcResult sold = gated(SaleMapper.class, "insert",
                () -> perform(saleRequest(receiverSession, batchId, receiverSite.getId(), "100", key("idem-sale"))),
                () -> {
                    recall.set(background("pb5-recall-B", () -> perform(recallReq(receiverQm(), batchId, key("idem-rc")))));
                    awaitRowLockWait(recall.get().thread, "batch");
                });
        MvcResult recalled = recall.get().join();

        assertThat(status(sold)).isEqualTo(201);
        assertThat(status(recalled)).isEqualTo(201);
        JsonNode seed = data(recalled).get("scope").get(0);
        assertThat(seed.get("soldQuantity").decimalValue()).isEqualByComparingTo("100");
        assertThat(seed.get("remainingQuantity").decimalValue()).isEqualByComparingTo("200");
        assertThat(riskStatus(batchId)).isEqualTo("RECALLED");
    }

    @Test
    @DisplayName("两次召回并发召回同一批次：先提交者生效，后到者 409 INVALID_STATE_TRANSITION，只有一条 RECALLED 转换")
    void concurrentRecalls_onlyOneTransition() throws Exception {
        Long batchId = retailerBatchWithFailEvidence("RR");

        AtomicReference<Running> second = new AtomicReference<>();
        MvcResult first = gated(RecallBatchMapper.class, "insert",
                () -> perform(recallReq(receiverQm(), batchId, key("idem-rc-a"))),
                () -> {
                    second.set(background("pb5-recall-B", () -> perform(recallReq(receiverQm(), batchId, key("idem-rc-b")))));
                    awaitRowLockWait(second.get().thread, "batch");
                });
        MvcResult rejected = second.get().join();

        assertThat(status(first)).isEqualTo(201);
        assertThat(status(rejected)).isEqualTo(409);
        assertThat(code(rejected)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(count("SELECT count(*) FROM batch_risk_transition WHERE batch_id = ? AND to_status = 'RECALLED'", batchId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM recall WHERE owner_org_id = ?", receiverOrg.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("批次操作先提交并产生新的后续批次、召回等待：召回检测到正向范围变化 409 RECALL_SCOPE_CHANGED；重试后新后续批次同时被召回")
    void operationFirst_thenRecallScopeChangedAndRetry() throws Exception {
        Long seed = processorHeldBatch("EXT-RO-" + suffix, new BigDecimal("500"));
        MockHttpSession processorQm = newSession(receiverOrg, "prc_qm", QM_ROLE, "OWN_ORG");
        inspect(processorQm, seed, "PRC-FAIL", "FAIL", null);
        JsonNode draft = createOperation("PROCESS", List.of(opInput(seed, "500"), opOutput("480"), opOther("LOSS", "20")));

        AtomicReference<Running> recall = new AtomicReference<>();
        MvcResult submitted = gated(BatchMapper.class, "closeConsumedInput",
                () -> perform(submitOperationRequest(receiverSession, draft.get("id").asLong(), draft.get("version").asLong(), key("idem-op-s"))),
                () -> {
                    recall.set(background("pb5-recall-B", () -> perform(recallReq(processorQm, seed, key("idem-rc-a")))));
                    awaitRowLockWait(recall.get().thread, "batch");
                });
        MvcResult changed = recall.get().join();

        assertThat(status(submitted)).isEqualTo(200);
        assertThat(status(changed)).isEqualTo(409);
        assertThat(code(changed)).isEqualTo("RECALL_SCOPE_CHANGED");
        assertThat(riskStatus(seed)).isEqualTo("NORMAL");
        assertThat(count("SELECT count(*) FROM recall WHERE owner_org_id = ?", receiverOrg.getId())).isZero();

        JsonNode retried = expect(recallReq(processorQm, seed, key("idem-rc-b")), 201).get("data");
        Long output = draft.get("items").findValues("batchId").stream().map(JsonNode::asLong).filter(id -> !id.equals(seed)).findFirst().orElseThrow();
        assertThat(retried.get("scope")).anySatisfy(row -> {
            assertThat(row.get("batchId").asLong()).isEqualTo(output);
            assertThat(row.get("scopeRole").asString()).isEqualTo("DESCENDANT");
            assertThat(row.get("action").asString()).isEqualTo("RECALLED");
        });
        assertThat(riskStatus(seed)).isEqualTo("RECALLED");
        assertThat(riskStatus(output)).isEqualTo("RECALLED");
    }

    @Test
    @DisplayName("召回先持有批次行锁、批次操作提交等待：召回提交后批次操作被阻断，输入批次保持 RECALLED 且未被消耗")
    void recallFirst_thenOperationBlocked() throws Exception {
        Long seed = processorHeldBatch("EXT-RP-" + suffix, new BigDecimal("500"));
        MockHttpSession processorQm = newSession(receiverOrg, "prc_qm", QM_ROLE, "OWN_ORG");
        inspect(processorQm, seed, "PRC-FAIL", "FAIL", null);
        JsonNode draft = createOperation("PROCESS", List.of(opInput(seed, "500"), opOutput("480"), opOther("LOSS", "20")));

        AtomicReference<Running> submit = new AtomicReference<>();
        MvcResult recalled = gated(RecallBatchMapper.class, "insert",
                () -> perform(recallReq(processorQm, seed, key("idem-rc"))),
                () -> {
                    submit.set(background("pb5-op-B", () -> perform(submitOperationRequest(receiverSession, draft.get("id").asLong(),
                            draft.get("version").asLong(), key("idem-op-s")))));
                    awaitRowLockWait(submit.get().thread, "batch");
                });
        MvcResult blocked = submit.get().join();

        assertThat(status(recalled)).isEqualTo(201);
        assertThat(status(blocked)).isIn(409, 422);
        assertThat(riskStatus(seed)).isEqualTo("RECALLED");
        assertThat(batchRow(seed).get("flow_status")).isEqualTo("ACTIVE");
        assertThat(batchRow(seed).get("consumed_by_operation_id")).isNull();
    }

    @Test
    @DisplayName("告警放行先持有告警与批次行锁、引用该告警的召回在告警行锁上等待：放行提交后批次正常且无不合格证据，召回 409 RECALL_EVIDENCE_REQUIRED")
    void releaseFirst_thenRecallNeedsEvidence() throws Exception {
        Manifest m = deliveredAlertManifest("RC-REL", "500");
        Long alertId = onlyAlertId(m.shipmentId());
        acknowledge(alertId);
        inspect(senderQm(), m.batch(0), "SND-PASS", "PASS", alertId);

        AtomicReference<Running> recall = new AtomicReference<>();
        MvcResult released = gated(AlertActionMapper.class, "insert",
                () -> perform(releaseReq(senderQm(), alertId, m.batch(0), null, key("idem-rel"))),
                () -> {
                    recall.set(background("pb5-recall-B", () -> perform(recallReq(senderQm(), m.batch(0), alertId, key("idem-rc")))));
                    awaitRowLockWait(recall.get().thread, "alert");
                });
        MvcResult rejected = recall.get().join();

        assertThat(status(released)).isEqualTo(200);
        assertThat(status(rejected)).isEqualTo(409);
        assertThat(code(rejected)).isEqualTo("RECALL_EVIDENCE_REQUIRED");
        assertThat(riskStatus(m.batch(0))).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("引用告警的召回先持有告警与批次行锁（停在插入召回行之前）、告警放行在告警行锁上等待：无死锁，召回提交后放行 409（批次已 RECALLED）")
    void recallFirst_thenReleaseRejected() throws Exception {
        Manifest m = deliveredAlertManifest("RC-RCL", "500");
        Long alertId = onlyAlertId(m.shipmentId());
        acknowledge(alertId);
        inspect(senderQm(), m.batch(0), "SND-PASS", "PASS", alertId);

        AtomicReference<Running> release = new AtomicReference<>();
        MvcResult recalled = gated(RecallMapper.class, "insert",
                () -> perform(recallReq(senderQm(), m.batch(0), alertId, key("idem-rc"))),
                () -> {
                    release.set(background("pb5-release-B", () -> perform(releaseReq(senderQm(), alertId, m.batch(0), null, key("idem-rel")))));
                    awaitRowLockWait(release.get().thread, "alert");
                });
        MvcResult rejected = release.get().join();

        assertThat(status(recalled)).isEqualTo(201);
        assertThat(data(recalled).get("sourceAlertId").asLong()).isEqualTo(alertId);
        assertThat(status(rejected)).isEqualTo(409);
        assertThat(code(rejected)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(riskStatus(m.batch(0))).isEqualTo("RECALLED");
        assertThat(count("SELECT count(*) FROM alert_action WHERE alert_id = ? AND action = 'RELEASE_BATCH'", alertId)).isZero();
    }

    @Test
    @DisplayName("召回先持有批次行锁、交接接受等待：召回提交后接受被阻断（批次仍由发送方负责、交接保持 PENDING），接收方随后可拒收")
    void recallFirst_thenAcceptBlocked() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "EXT-RA-" + suffix, new BigDecimal("100"));
        Handover h = prepareDeliveredHandover(batchId);
        inspect(senderQm(), batchId, "SND-FAIL-RA", "FAIL", null);

        AtomicReference<Running> accept = new AtomicReference<>();
        MvcResult recalled = gated(RecallBatchMapper.class, "insert",
                () -> perform(recallReq(senderQm(), batchId, key("idem-rc"))),
                () -> {
                    accept.set(background("pb5-accept-B", () -> perform(acceptRequest(receiverSession, h.transferId(), new BigDecimal("100"),
                            key("idem-acc")))));
                    awaitRowLockWait(accept.get().thread, "batch");
                });
        MvcResult blocked = accept.get().join();

        assertThat(status(recalled)).isEqualTo(201);
        JsonNode seed = data(recalled).get("scope").get(0);
        assertThat(seed.get("openTransferStatus").asString()).isEqualTo("PENDING");
        assertThat(seed.get("shipmentStatus").asString()).isEqualTo("DELIVERED");
        assertThat(status(blocked)).isIn(409, 422);
        assertThat(code(blocked)).isEqualTo("BATCH_FLOW_BLOCKED");
        assertThat(transferStatus(h.transferId())).isEqualTo("PENDING");
        assertThat(batchOrgId(batchId)).isEqualTo(senderOrg.getId());
        assertThat(riskStatus(batchId)).isEqualTo("RECALLED");
        expect(rejectRequest(receiverSession, h.transferId(), key("idem-rej")), 200);
        assertThat(transferStatus(h.transferId())).isEqualTo("REJECTED");
        assertThat(riskStatus(batchId)).isEqualTo("RECALLED");
    }

    @Test
    @DisplayName("交接接受先持有批次行锁（责任组织转移）、召回等待：原组织召回 403 ORG_SCOPE_DENIED；新责任组织凭同一不合格证据召回成功")
    void acceptFirst_thenRecallDeniedForFormerOwner() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "EXT-AR-" + suffix, new BigDecimal("100"));
        Handover h = prepareDeliveredHandover(batchId);
        inspect(senderQm(), batchId, "SND-FAIL-AR", "FAIL", null);

        AtomicReference<Running> recall = new AtomicReference<>();
        MvcResult accepted = gated(BatchMapper.class, "updateOrgIdByIdAndVersion",
                () -> perform(acceptRequest(receiverSession, h.transferId(), new BigDecimal("100"), key("idem-acc"))),
                () -> {
                    recall.set(background("pb5-recall-B", () -> perform(recallReq(senderQm(), batchId, key("idem-rc-a")))));
                    awaitRowLockWait(recall.get().thread, "batch");
                });
        MvcResult denied = recall.get().join();

        assertThat(status(accepted)).isEqualTo(200);
        assertThat(status(denied)).isEqualTo(403);
        assertThat(code(denied)).isEqualTo("ORG_SCOPE_DENIED");
        assertThat(batchOrgId(batchId)).isEqualTo(receiverOrg.getId());
        assertThat(riskStatus(batchId)).isEqualTo("NORMAL");
        assertThat(count("SELECT count(*) FROM recall WHERE owner_org_id = ?", senderOrg.getId())).isZero();

        JsonNode byNewOwner = expect(recallReq(receiverQm(), batchId, key("idem-rc-b")), 201).get("data");
        assertThat(byNewOwner.get("ownerOrgId").asLong()).isEqualTo(receiverOrg.getId());
        assertThat(riskStatus(batchId)).isEqualTo("RECALLED");
    }

    @Test
    @DisplayName("两次关闭并发关闭同一召回（不同幂等键）：先提交者生效，后到者 409 INVALID_STATE_TRANSITION，只有一条关闭审计")
    void concurrentCloses_onlyOneTakesEffect() throws Exception {
        Long batchId = retailerBatchWithFailEvidence("CC");
        Long recallId = expect(recallReq(receiverQm(), batchId, key("idem-rc")), 201).get("data").get("id").asLong();

        AtomicReference<Running> second = new AtomicReference<>();
        MvcResult first = gated(RecallMapper.class, "close",
                () -> perform(postJson(receiverQm(), "/api/v1/recalls/" + recallId + "/close", key("idem-close-a"),
                        new RecallCloseRequest("DESTROYED", "库存已按演练流程销毁"))),
                () -> {
                    second.set(background("pb5-close-B", () -> perform(postJson(receiverQm(), "/api/v1/recalls/" + recallId + "/close",
                            key("idem-close-b"), new RecallCloseRequest("RETURNED", "库存已按演练流程退回")))));
                    awaitRowLockWait(second.get().thread, "recall");
                });
        MvcResult rejected = second.get().join();

        assertThat(status(first)).isEqualTo(200);
        assertThat(status(rejected)).isEqualTo(409);
        assertThat(code(rejected)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(jdbcTemplate.queryForObject("SELECT public_disposition FROM recall WHERE id = ?", String.class, recallId)).isEqualTo("DESTROYED");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'RECALL_CLOSE' AND object_id = ?", recallId)).isEqualTo(1);
        assertThat(riskStatus(batchId)).isEqualTo("RECALLED");
    }

    @Test
    @DisplayName("不同组织对同一谱系并发召回：加工企业召回上游 P（范围含零售持有的后续批次 C）与零售企业召回 C（范围含祖先 P）按批次 ID 升序加锁，无死锁，两次召回都生效")
    void crossOrgRecallsOnSameLineage_noDeadlock() throws Exception {
        Long parent = processorHeldBatch("EXT-XO-" + suffix, new BigDecimal("500"));
        MockHttpSession processorQm = newSession(receiverOrg, "prc_qm", QM_ROLE, "OWN_ORG");
        inspect(processorQm, parent, "PRC-FAIL-XO", "FAIL", null);
        JsonNode op = createAndSubmitOperation("PROCESS", List.of(opInput(parent, "500"), opOutput("480"), opOther("LOSS", "20")));
        Long child = op.get("items").findValues("batchId").stream().map(JsonNode::asLong).filter(id -> !id.equals(parent)).findFirst().orElseThrow();
        Organization retailer = createOrg("ORG_XR_" + suffix, "零售企业-" + suffix, "RETAILER");
        Site store = createSite(retailer.getId(), "XR-STORE-" + suffix, "零售门店-" + suffix, "STORE");
        MockHttpSession retailerOp = newSession(retailer, "xr_op", "OPERATOR", "OWN_ORG");
        MockHttpSession retailerQm = newSession(retailer, "xr_qm", QM_ROLE, "OWN_ORG");
        Long transferId = expect(postJson(receiverSession, "/api/v1/transfers", key("idem-trf-c"),
                new TransferCreateRequest(child, retailer.getId())), 201).get("data").get("id").asLong();
        createdTransferIds.add(transferId);
        Long shipmentId = expect(createShipmentRequest(receiverSession, key("idem-shp-c"), carrierOrg.getId(), receiverSite.getId(),
                store.getId()), 201).get("data").get("id").asLong();
        createdShipmentIds.add(shipmentId);
        expect(bindRequest(receiverSession, shipmentId, transferId, transferVersion(transferId)), 200);
        expect(submitRequest(receiverSession, transferId, transferVersion(transferId)), 200);
        dispatch(shipmentId);
        arrive(shipmentId);
        expect(postJson(retailerOp, "/api/v1/transfers/" + transferId + "/accept", key("idem-trf-acc"),
                new TransferAcceptRequest(new BigDecimal("480"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, transferVersion(transferId))), 200);
        inspect(retailerQm, child, "RT-FAIL-XO", "FAIL", null);
        assertThat(parent).isLessThan(child);

        AtomicReference<Running> retailerRecall = new AtomicReference<>();
        MvcResult upstream = gated(RecallMapper.class, "insert",
                () -> perform(recallReq(processorQm, parent, key("idem-rc-p"))),
                () -> {
                    retailerRecall.set(background("pb5-recall-R", () -> perform(recallReq(retailerQm, child, key("idem-rc-r")))));
                    awaitRowLockWait(retailerRecall.get().thread, "batch");
                });
        MvcResult downstream = retailerRecall.get().join();

        assertThat(status(upstream)).isEqualTo(201);
        assertThat(status(downstream)).isEqualTo(201);
        assertThat(data(upstream).get("scope")).anySatisfy(row -> {
            assertThat(row.get("batchId").asLong()).isEqualTo(child);
            assertThat(row.get("action").asString()).isEqualTo("NOTIFY_HOLDER");
        });
        assertThat(data(downstream).get("scope")).anySatisfy(row -> {
            assertThat(row.get("batchId").asLong()).isEqualTo(parent);
            assertThat(row.get("scopeRole").asString()).isEqualTo("ANCESTOR");
        });
        assertThat(riskStatus(parent)).isEqualTo("RECALLED");
        assertThat(riskStatus(child)).isEqualTo("RECALLED");
    }
}
