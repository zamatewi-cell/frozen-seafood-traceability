package com.example.traceability.trace;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.dto.BatchCreateRequest;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.trace.domain.Transfer;
import com.example.traceability.trace.domain.TransferStatus;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import com.example.traceability.trace.dto.TransferCreateRequest;
import com.example.traceability.trace.dto.TransferRejectRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基于真实 MySQL 8.4 的企业间整批交接端到端集成测试（统一业务契约 v1.1 §7，Phase A Slice 2）。
 * <p>
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 控制。所有交接均通过真实运输任务完成：
 * DRAFT → 绑定 PLANNED Shipment → PENDING → Shipment IN_TRANSIT → DELIVERED → ACCEPT / REJECT。
 * <ul>
 *   <li>场景 A：跨企业接受（当前责任组织与公开追溯码归属原子转移、数量不变、ARRIVAL 仅来自 Shipment 到达、ACCEPT 不再生成 ARRIVAL）；</li>
 *   <li>场景 B：拒收（责任组织与追溯码归属不变，无 Transfer 触发的事件）；</li>
 *   <li>场景 C：负向校验（非持有者不可发起、非接收方不可决断、终态不可重复决断）；</li>
 *   <li>场景 D：幂等（同 key 重放、不同语义 409、uk_transfer_open_batch → 409）；</li>
 *   <li>场景 E：PENDING 交接阻断批次操作 INPUT；</li>
 *   <li>场景 F：批次双状态矩阵（提交与接受仅 ACTIVE+NORMAL）；</li>
 *   <li>场景 G：已消耗批次禁止交接；</li>
 *   <li>场景 H：SQL 层双边组织隔离；</li>
 *   <li>场景 I：RBAC（接收方质量管理员可决断、管理员与审计员不可写）；</li>
 *   <li>场景 J1/J2：真并发 accept/accept 与 accept/reject 竞争；</li>
 *   <li>场景 L：接受幂等重放；场景 M：128 位幂等键；场景 N：追溯码组织冲突整体回滚；</li>
 *   <li>Slice 2：未绑定不可提交、非 PLANNED 不可绑定 / 提交、未 DELIVERED 不可接受 / 拒收、历史参与组织只读。</li>
 * </ul>
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("企业间整批交接 MySQL 8.4 端到端集成测试")
class TransferMysqlIntegrationTest extends AbstractTransferShipmentMysqlIT {

    private TransferAcceptRequest acceptReq(String qty, long expectedVersion) {
        return new TransferAcceptRequest(new BigDecimal(qty), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, expectedVersion);
    }

    private TransferRejectRequest rejectReq(String reason, long expectedVersion) {
        return new TransferRejectRequest(reason, OffsetDateTime.now(ZoneOffset.UTC), expectedVersion);
    }

    private JsonNode accept(MockHttpSession session, Long transferId, Object req, int expectedStatus) throws Exception {
        return expect(postJson(session, "/api/v1/transfers/" + transferId + "/accept", key("idem-trf-acc"), req), expectedStatus);
    }

    private JsonNode reject(MockHttpSession session, Long transferId, Object req, int expectedStatus) throws Exception {
        return expect(postJson(session, "/api/v1/transfers/" + transferId + "/reject", key("idem-trf-rej"), req), expectedStatus);
    }

    private void activateTraceCode(Long batchId) throws Exception {
        expect(postJson(senderSession, "/api/v1/batches/" + batchId + "/public-trace-code/activate", key("idem-act"), null), 200);
    }

    private Long traceCodeOrg(Long batchId) {
        return jdbcTemplate.queryForObject("SELECT org_id FROM public_trace_code WHERE batch_id = ?", Long.class, batchId);
    }

    @Test
    @DisplayName("场景 A：通过运输任务完成跨企业交接，ACCEPT 转移责任组织与公开追溯码，数量不变，ARRIVAL 仅来自 Shipment 到达")
    void scenarioA_crossEnterpriseTransfer_accept_fullLifecycle() throws Exception {
        BigDecimal qty = new BigDecimal("1000.000");
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-A-" + suffix, qty);
        activateTraceCode(batchId);
        assertThat(traceCodeOrg(batchId)).isEqualTo(senderOrg.getId());

        // 1. 创建 DRAFT 交接，并验证幂等重放
        String createKey = key("idem-create-a");
        TransferCreateRequest createReq = new TransferCreateRequest(batchId, receiverOrg.getId());
        JsonNode created = expect(postJson(senderSession, "/api/v1/transfers", createKey, createReq), 201).get("data");
        Long transferId = created.get("id").asLong();
        createdTransferIds.add(transferId);
        assertThat(created.get("status").asString()).isEqualTo("DRAFT");
        assertThat(created.has("shipmentId")).isFalse();
        JsonNode replay = expect(postJson(senderSession, "/api/v1/transfers", createKey, createReq), 201).get("data");
        assertThat(replay.get("id").asLong()).isEqualTo(transferId);

        // 2. 创建 PLANNED 运输任务并绑定：交接仍为 DRAFT，版本 0 → 1
        Long shipmentId = createShipment(senderSession);
        JsonNode shipmentAfterBind = bind(shipmentId, transferId);
        assertThat(shipmentAfterBind.get("transfers")).hasSize(1);
        assertThat(transferStatus(transferId)).isEqualTo("DRAFT");
        assertThat(transferVersion(transferId)).isEqualTo(1L);

        // 3. 提交交接为 PENDING：批次责任组织不变
        JsonNode submitted = submit(transferId);
        assertThat(submitted.get("status").asString()).isEqualTo("PENDING");
        assertThat(submitted.get("shipmentId").asLong()).isEqualTo(shipmentId);
        assertThat(submitted.get("shipmentStatus").asString()).isEqualTo("PLANNED");
        assertThat(submitted.get("version").asLong()).isEqualTo(2L);
        assertThat(batchOrgId(batchId)).isEqualTo(senderOrg.getId());

        // 4. 承运商发运与到达：交接仍为 PENDING，责任组织仍为发货方
        dispatch(shipmentId);
        assertThat(countEvents(batchId, "TRANSPORT")).isEqualTo(1);
        assertThat(batchOrgId(batchId)).isEqualTo(senderOrg.getId());
        arrive(shipmentId);
        assertThat(transferStatus(transferId)).isEqualTo("PENDING");
        assertThat(batchOrgId(batchId)).isEqualTo(senderOrg.getId());
        assertThat(countEvents(batchId, "ARRIVAL")).isEqualTo(1);
        int eventsBeforeAccept = count("SELECT count(*) FROM trace_event WHERE batch_id = ?", batchId);

        // 5. 接收方 ACCEPT（存在 1.5kg 合理干耗）
        TransferAcceptRequest req = new TransferAcceptRequest(new BigDecimal("998.500"), "kg", OffsetDateTime.now(ZoneOffset.UTC), "合理干耗1.5kg", 2L);
        JsonNode accepted = accept(receiverSession, transferId, req, 200).get("data");
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("receivedQuantity").asDouble()).isEqualTo(998.5);
        assertThat(accepted.get("version").asLong()).isEqualTo(3L);

        // 6. 真实数据库事实：责任组织与追溯码归属转移、声明数量与双状态不变
        Batch updatedBatch = batchMapper.selectById(batchId);
        assertThat(updatedBatch.getOrgId()).isEqualTo(receiverOrg.getId());
        assertThat(updatedBatch.getQuantity()).isEqualByComparingTo(qty);
        assertThat(updatedBatch.getFlowStatus()).isEqualTo("ACTIVE");
        assertThat(updatedBatch.getRiskStatus()).isEqualTo("NORMAL");
        assertThat(traceCodeOrg(batchId)).isEqualTo(receiverOrg.getId());

        Transfer finalTransfer = transferMapper.selectById(transferId);
        assertThat(finalTransfer.getShippedAt()).isNull();
        assertThat(finalTransfer.getSubmittedRecordedAt()).isNotNull();
        assertThat(finalTransfer.getReceivedAt()).isNotNull();
        assertThat(finalTransfer.getDecisionRecordedAt()).isNotNull();
        assertThat(finalTransfer.getDecidedBy()).isEqualTo(receiverUser.getId());

        // 7. ACCEPT 不生成任何追溯事件：恰好一条 TRANSPORT 与一条 ARRIVAL，均来自该运输任务
        assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ?", batchId)).isEqualTo(eventsBeforeAccept);
        assertThat(countEvents(batchId, "TRANSPORT")).isEqualTo(1);
        assertThat(countEvents(batchId, "ARRIVAL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ? AND event_type = 'ARRIVAL' "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.sourceObjectType')) = 'SHIPMENT' "
                + "AND CAST(JSON_UNQUOTE(JSON_EXTRACT(details_json, '$.shipmentId')) AS UNSIGNED) = ?", batchId, shipmentId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM trace_event WHERE idempotency_key LIKE 'TRANSFER_ARRIVAL_%' AND batch_id = ?", batchId)).isZero();
        assertThat(count("SELECT count(*) FROM trace_event e JOIN transfer t ON t.batch_id = e.batch_id "
                + "WHERE e.batch_id = ? AND e.event_type = 'ARRIVAL' AND e.recorded_at <= t.decision_recorded_at", batchId)).isEqualTo(1);

        // 8. 审计
        assertThat(count("SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'SUBMIT' AND object_type = 'TRANSFER'", senderOrg.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'ACCEPT' AND object_type = 'TRANSFER'", receiverOrg.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("场景 B：运输任务到达后拒收，责任组织与公开追溯码归属不变，Transfer 不产生追溯事件")
    void scenarioB_crossEnterpriseTransfer_reject_preservesOwnershipAndNoEvent() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-B-" + suffix, new BigDecimal("200.000"));
        activateTraceCode(batchId);
        Handover h = prepareDeliveredHandover(batchId);
        int eventsBefore = count("SELECT count(*) FROM trace_event WHERE batch_id = ?", batchId);

        JsonNode rejected = reject(receiverSession, h.transferId(), rejectReq("解冻变质，不予收货", 2L), 200).get("data");
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejectionReason").asString()).isEqualTo("解冻变质，不予收货");

        Batch b = batchMapper.selectById(batchId);
        assertThat(b.getOrgId()).isEqualTo(senderOrg.getId());
        assertThat(b.getVersion()).isEqualTo(1L);
        assertThat(traceCodeOrg(batchId)).isEqualTo(senderOrg.getId());
        assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ?", batchId)).isEqualTo(eventsBefore);
        assertThat(count("SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'REJECT' AND object_type = 'TRANSFER'", receiverOrg.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("场景 C：负向校验（非持有者不可发起、非目标接收方与承运方不可决断、终态不可重复决断）")
    void scenarioC_negativeValidation_lifecycleAndRbac() throws Exception {
        Organization thirdOrg = createOrg("ORG_T_" + suffix, "第三方无关企业-" + suffix, "RETAILER");
        MockHttpSession thirdSession = newSession(thirdOrg, "thd_op", "OPERATOR", "OWN_ORG");
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-C-" + suffix, new BigDecimal("100.000"));

        // 1. 非持有者不可发起交接
        expectProblem(postJson(receiverSession, "/api/v1/transfers", key("idem-c-fail"),
                new TransferCreateRequest(batchId, thirdOrg.getId())), 403, "ORG_SCOPE_DENIED");

        Handover h = prepareDeliveredHandover(batchId);

        // 2. 第三方、发货方、承运方都不可 ACCEPT / REJECT
        expectProblem(postJson(thirdSession, "/api/v1/transfers/" + h.transferId() + "/accept", key("idem-c-thd"), acceptReq("100.000", 2L)), 403, "ORG_SCOPE_DENIED");
        expectProblem(postJson(senderSession, "/api/v1/transfers/" + h.transferId() + "/accept", key("idem-c-snd"), acceptReq("100.000", 2L)), 403, "ORG_SCOPE_DENIED");
        expectProblem(postJson(carrierSession, "/api/v1/transfers/" + h.transferId() + "/accept", key("idem-c-car"), acceptReq("100.000", 2L)), 403, "ORG_SCOPE_DENIED");
        expectProblem(postJson(thirdSession, "/api/v1/transfers/" + h.transferId() + "/reject", key("idem-c-thr"), rejectReq("非我司货物", 2L)), 403, "ORG_SCOPE_DENIED");
        expectProblem(postJson(carrierSession, "/api/v1/transfers/" + h.transferId() + "/reject", key("idem-c-crr"), rejectReq("承运商代拒", 2L)), 403, "ORG_SCOPE_DENIED");
        assertThat(batchOrgId(batchId)).isEqualTo(senderOrg.getId());

        // 3. 正常 ACCEPT 后终态不可重复决断
        accept(receiverSession, h.transferId(), acceptReq("100.000", 2L), 200);
        expectProblem(postJson(receiverSession, "/api/v1/transfers/" + h.transferId() + "/accept", key("idem-c-re-acc"), acceptReq("100.000", 3L)), 409, "INVALID_STATE_TRANSITION");
        expectProblem(postJson(receiverSession, "/api/v1/transfers/" + h.transferId() + "/reject", key("idem-c-re-rej"), rejectReq("后悔拒收", 3L)), 409, "INVALID_STATE_TRANSITION");
    }

    @Test
    @DisplayName("场景 D：幂等（同 key 重放返回同结果、不同语义 409、uk_transfer_open_batch 转换为 409）")
    void scenarioD_concurrencyAndIdempotency() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-D-" + suffix, new BigDecimal("350.000"));
        String idemKey = key("idem-d");
        TransferCreateRequest createReq = new TransferCreateRequest(batchId, receiverOrg.getId());

        Long transferId = expect(postJson(senderSession, "/api/v1/transfers", idemKey, createReq), 201).get("data").get("id").asLong();
        createdTransferIds.add(transferId);
        assertThat(expect(postJson(senderSession, "/api/v1/transfers", idemKey, createReq), 201).get("data").get("id").asLong()).isEqualTo(transferId);
        expectProblem(postJson(senderSession, "/api/v1/transfers", idemKey, new TransferCreateRequest(batchId, receiverOrg.getId() + 9999L)), 409, "IDEMPOTENCY_CONFLICT");
        expectProblem(postJson(senderSession, "/api/v1/transfers", key("idem-d-new"), createReq), 409, "BATCH_TRANSFER_CONFLICT");
        assertThat(count("SELECT count(*) FROM transfer WHERE batch_id = ?", batchId)).isEqualTo(1);
    }

    @Test
    @DisplayName("场景 E：未结束交接（DRAFT / PENDING）阻断批次操作把该批次作为 INPUT（409 BATCH_TRANSFER_OPEN）")
    void scenarioE_batchOperationMutex_openTransferBlocksInput() throws Exception {
        Long batchId = processorHeldBatch("BAT-IN-" + suffix, new BigDecimal("200.000"));
        Organization retailer = createOrg("ORG_E_RT_" + suffix, "零售企业-" + suffix, "RETAILER");
        Long transferId = createDraftTransfer(receiverSession, batchId, retailer.getId());

        expectProblem(createOperationRequest(receiverSession, key("idem-op-e"), "PROCESS",
                List.of(opInput(batchId, "200.000"), opOutput("200.000"))), 409, "BATCH_TRANSFER_OPEN");
        assertThat(count("SELECT count(*) FROM batch_operation WHERE org_id = ?", receiverOrg.getId())).isZero();
        assertThat(count("SELECT count(*) FROM batch WHERE produced_by_operation_id IS NOT NULL AND creation_org_id = ?", receiverOrg.getId())).isZero();

        // 删除交接草稿后即可全量加工
        expect(deleteReq(receiverSession, "/api/v1/transfers/" + transferId).param("expectedVersion", String.valueOf(transferVersion(transferId))), 204);
        createAndSubmitOperation("PROCESS", List.of(opInput(batchId, "200.000"), opOutput("200.000")));
        assertThat(jdbcTemplate.queryForObject("SELECT flow_status FROM batch WHERE id = ?", String.class, batchId)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("创建幂等域与责任组织解耦：双方持有相同 creation_idempotency_key 时 ACCEPT 仍成功，creation_org_id 不漂移")
    void acceptTransfer_sameCreationIdempotencyKeyAtBothSides_succeedsAndKeepsCreationOrgStable() throws Exception {
        useSourceTypeReceiver();
        String sharedCreationKey = key("idem-shared-creation");
        Long senderBatchId = createAndSubmitActiveBatch(senderSession, "EXT-SHARED-KEY-S", new BigDecimal("300.000"), sharedCreationKey);
        Long receiverOwnBatchId = createAndSubmitActiveBatch(receiverSession, "EXT-SHARED-KEY-R", new BigDecimal("100.000"), sharedCreationKey);

        Handover h = prepareDeliveredHandover(senderBatchId);
        accept(receiverSession, h.transferId(), acceptReq("300.000", 2L), 200);

        Batch after = batchMapper.selectById(senderBatchId);
        assertThat(after.getOrgId()).isEqualTo(receiverOrg.getId());
        assertThat(after.getCreationOrgId()).isEqualTo(senderOrg.getId());

        // 转出后原创建方重放原始创建请求：命中原批次，绝不产生第二条批次
        BatchCreateRequest replayReq = new BatchCreateRequest(
                "EXT-SHARED-KEY-S", testProduct.getId(), new BigDecimal("300.000"), "kg", "DOMESTIC_CAPTURE", "舟山渔场",
                LocalDate.now(), null, null, 180);
        JsonNode replayed = expect(postJson(senderSession, "/api/v1/batches", sharedCreationKey, replayReq), 201).get("data");
        assertThat(replayed.get("id").asLong()).isEqualTo(senderBatchId);
        assertThat(replayed.get("orgId").asLong()).isEqualTo(receiverOrg.getId());
        assertThat(replayed.has("creationOrgId")).isFalse();
        assertThat(count("SELECT count(*) FROM batch WHERE creation_org_id = ? AND creation_idempotency_key = ? AND is_deleted = 0",
                senderOrg.getId(), sharedCreationKey)).isEqualTo(1);

        Batch receiverOwn = batchMapper.selectById(receiverOwnBatchId);
        assertThat(receiverOwn.getOrgId()).isEqualTo(receiverOrg.getId());
        assertThat(receiverOwn.getCreationOrgId()).isEqualTo(receiverOrg.getId());
    }

    @Test
    @DisplayName("接收方同名外部批号不阻断交接：ACCEPT 成功且 traceBatchNo 与 externalBatchNo 不变")
    void acceptTransfer_sameExternalBatchNoAtReceiver_succeeds() throws Exception {
        useSourceTypeReceiver();
        String sameExternal = "BAT-DUP-EXT-" + suffix;
        Long senderBatchId = createAndSubmitActiveBatch(senderSession, sameExternal, new BigDecimal("300.000"));
        Long receiverExistingBatchId = createAndSubmitActiveBatch(receiverSession, sameExternal, new BigDecimal("100.000"));
        String traceBatchNoBefore = batchMapper.selectById(senderBatchId).getTraceBatchNo();

        Handover h = prepareDeliveredHandover(senderBatchId);
        accept(receiverSession, h.transferId(), acceptReq("300.000", 2L), 200);

        Batch senderBatchAfter = batchMapper.selectById(senderBatchId);
        assertThat(senderBatchAfter.getOrgId()).isEqualTo(receiverOrg.getId());
        assertThat(senderBatchAfter.getTraceBatchNo()).isEqualTo(traceBatchNoBefore);
        assertThat(senderBatchAfter.getExternalBatchNo()).isEqualTo(sameExternal);
        assertThat(batchMapper.selectById(receiverExistingBatchId).getOrgId()).isEqualTo(receiverOrg.getId());
        assertThat(count("SELECT count(*) FROM batch WHERE external_batch_no = ? AND is_deleted = 0", sameExternal)).isEqualTo(2);
    }

    @Test
    @DisplayName("场景 F：批次双状态矩阵（仅 ACTIVE+NORMAL 可提交；运输到达后批次冻结/召回禁止接受但允许拒收）")
    void scenarioF_batchStatusMatrix_blocksSubmissionAndAcceptance() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-F1-" + suffix, new BigDecimal("400.000"));
        Long transferId = createDraftTransfer(senderSession, batchId, receiverOrg.getId());
        Long shipmentId = createShipment(senderSession);
        bind(shipmentId, transferId);

        for (String[] state : new String[][]{{"ACTIVE", "FROZEN"}, {"ACTIVE", "RECALLED"}, {"CLOSED", "NORMAL"}, {"DRAFT", "NORMAL"}}) {
            jdbcTemplate.update("UPDATE batch SET flow_status = ?, risk_status = ? WHERE id = ?", state[0], state[1], batchId);
            expectProblem(submitRequest(senderSession, transferId, 1L), 409, "BATCH_FLOW_BLOCKED");
        }
        jdbcTemplate.update("UPDATE batch SET flow_status = 'ACTIVE', risk_status = 'NORMAL' WHERE id = ?", batchId);
        submit(transferId);
        dispatch(shipmentId);
        arrive(shipmentId);

        jdbcTemplate.update("UPDATE batch SET risk_status = 'FROZEN' WHERE id = ?", batchId);
        expectProblem(postJson(receiverSession, "/api/v1/transfers/" + transferId + "/accept", key("idem-f-acc"), acceptReq("400.000", 2L)), 409, "BATCH_FLOW_BLOCKED");
        assertThat(reject(receiverSession, transferId, rejectReq("到货时被质量冻结，拒收处理", 2L), 200).get("data").get("status").asString()).isEqualTo("REJECTED");
        assertThat(batchOrgId(batchId)).isEqualTo(senderOrg.getId());

        Long batchId2 = createAndSubmitActiveBatch(senderSession, "BAT-F2-" + suffix, new BigDecimal("400.000"));
        Handover h2 = prepareDeliveredHandover(batchId2);
        jdbcTemplate.update("UPDATE batch SET risk_status = 'RECALLED' WHERE id = ?", batchId2);
        expectProblem(postJson(receiverSession, "/api/v1/transfers/" + h2.transferId() + "/accept", key("idem-f2-acc"), acceptReq("400.000", 2L)), 409, "BATCH_FLOW_BLOCKED");
        reject(receiverSession, h2.transferId(), rejectReq("到货时已召回，拒收处理", 2L), 200);
        assertThat(batchOrgId(batchId2)).isEqualTo(senderOrg.getId());
    }

    @Test
    @DisplayName("场景 G：已被批次操作全量消耗（CLOSED）的批次禁止新建交接；已绑定交接草稿阻断批次操作")
    void scenarioG_consumedBatchMutex_blocksTransferCreationAndOperation() throws Exception {
        Organization retailer = createOrg("ORG_G_RT_" + suffix, "零售企业-" + suffix, "RETAILER");
        Long inBatch1 = processorHeldBatch("BAT-IN1-" + suffix, new BigDecimal("300.000"));
        createAndSubmitOperation("PROCESS", List.of(opInput(inBatch1, "300.000"), opOutput("300.000")));
        expectProblem(postJson(receiverSession, "/api/v1/transfers", key("idem-g-c1"), new TransferCreateRequest(inBatch1, retailer.getId())), 409, "BATCH_NOT_ACTIVE");

        Long inBatch2 = processorHeldBatch("BAT-IN2-" + suffix, new BigDecimal("300.000"));
        createDraftTransfer(receiverSession, inBatch2, retailer.getId());
        expectProblem(createOperationRequest(receiverSession, key("idem-g-op"), "SPLIT",
                List.of(opInput(inBatch2, "300.000"), opOutput("100.000"), opOutput("200.000"))), 409, "BATCH_TRANSFER_OPEN");
        assertThat(jdbcTemplate.queryForObject("SELECT flow_status FROM batch WHERE id = ?", String.class, inBatch2)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("场景 H：SQL 层双边组织隔离（第三方不可列表、不可查详情、不可提交）")
    void scenarioH_strictOrgScopeIsolation_listDetailAndActionsDenied() throws Exception {
        Organization thirdOrg = createOrg("ORG_H3_" + suffix, "隔离第三方企业-" + suffix, "RETAILER");
        MockHttpSession thirdSession = newSession(thirdOrg, "thd_h3", "OPERATOR", "OWN_ORG");
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-H-" + suffix, new BigDecimal("150.000"));
        Long transferId = createDraftTransfer(senderSession, batchId, receiverOrg.getId());

        JsonNode list = expect(getReq(thirdSession, "/api/v1/transfers"), 200);
        assertThat(list.get("data")).isEmpty();
        assertThat(list.get("meta").get("page").get("totalElements").asLong()).isZero();
        expectProblem(getReq(thirdSession, "/api/v1/transfers/" + transferId), 403, "ORG_SCOPE_DENIED");
        expectProblem(submitRequest(thirdSession, transferId, 0L), 403, "ORG_SCOPE_DENIED");
        // 承运商不是交接当事方，同样不可读取交接
        expectProblem(getReq(carrierSession, "/api/v1/transfers/" + transferId), 403, "ORG_SCOPE_DENIED");
    }

    @Test
    @DisplayName("场景 I：RBAC（接收方质量管理员可接受 / 拒收；系统管理员与审计员写操作被拦截）")
    void scenarioI_rbacPermissionMatrix_qualityManagerAllowedAndAdminAuditorRestricted() throws Exception {
        MockHttpSession receiverQmSession = newSession(receiverOrg, "rcv_qm", "QUALITY_MANAGER", "OWN_ORG");
        MockHttpSession adminSession = newSession(senderOrg, "sys_adm", "SYSTEM_ADMIN", "PLATFORM");
        MockHttpSession auditorSession = newSession(receiverOrg, "rcv_aud", "AUDITOR", "PLATFORM");

        Long batchA = createAndSubmitActiveBatch(senderSession, "BAT-I-A-" + suffix, new BigDecimal("100.000"));
        Handover a = prepareDeliveredHandover(batchA);
        assertThat(accept(receiverQmSession, a.transferId(), acceptReq("100.000", 2L), 200).get("data").get("status").asString()).isEqualTo("ACCEPTED");

        Long batchB = createAndSubmitActiveBatch(senderSession, "BAT-I-B-" + suffix, new BigDecimal("100.000"));
        Handover b = prepareDeliveredHandover(batchB);
        assertThat(reject(receiverQmSession, b.transferId(), rejectReq("质检不合格，代办拒收", 2L), 200).get("data").get("status").asString()).isEqualTo("REJECTED");

        Long batchC = createAndSubmitActiveBatch(senderSession, "BAT-I-C-" + suffix, new BigDecimal("100.000"));
        expectProblem(postJson(adminSession, "/api/v1/transfers", key("idem-i-adm"), new TransferCreateRequest(batchC, receiverOrg.getId())), 403, "ADMIN_RESTRICTED");
        expectProblem(postJson(auditorSession, "/api/v1/transfers", key("idem-i-aud"), new TransferCreateRequest(batchC, receiverOrg.getId())), 403, "ROLE_NOT_ALLOWED");
    }

    @Test
    @DisplayName("场景 J1：真并发（运输任务到达后双 accept 竞争：恰好一个成功，另一个 409，ACCEPT 不新增 ARRIVAL）")
    void scenarioJ1_trueConcurrency_twoAcceptsCompetition() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-J1-" + suffix, new BigDecimal("250.000"));
        Handover h = prepareDeliveredHandover(batchId);
        MockHttpSession s1 = login(receiverUser.getUsername());
        MockHttpSession s2 = login(receiverUser.getUsername());

        int[] statuses = race(
                () -> perform(postJson(s1, "/api/v1/transfers/" + h.transferId() + "/accept", key("idem-j1-1"), acceptReq("250.000", 2L))),
                () -> perform(postJson(s2, "/api/v1/transfers/" + h.transferId() + "/accept", key("idem-j1-2"), acceptReq("250.000", 2L))));
        assertThat(List.of(statuses[0], statuses[1])).containsExactlyInAnyOrder(200, 409);

        assertThat(transferStatus(h.transferId())).isEqualTo("ACCEPTED");
        assertThat(batchOrgId(batchId)).isEqualTo(receiverOrg.getId());
        assertThat(countEvents(batchId, "ARRIVAL")).isEqualTo(1);
    }

    @Test
    @DisplayName("场景 J2：真并发（accept 与 reject 竞争：恰好一个成功，另一个 409，失败方无副作用）")
    void scenarioJ2_trueConcurrency_acceptVsRejectCompetition() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-J2-" + suffix, new BigDecimal("260.000"));
        activateTraceCode(batchId);
        Handover h = prepareDeliveredHandover(batchId);
        MockHttpSession sAccept = login(receiverUser.getUsername());
        MockHttpSession sReject = login(receiverUser.getUsername());

        int[] statuses = race(
                () -> perform(postJson(sAccept, "/api/v1/transfers/" + h.transferId() + "/accept", key("idem-j2-acc"), acceptReq("260.000", 2L))),
                () -> perform(postJson(sReject, "/api/v1/transfers/" + h.transferId() + "/reject", key("idem-j2-rej"), rejectReq("并发拒收竞争测试", 2L))));
        assertThat(List.of(statuses[0], statuses[1])).containsExactlyInAnyOrder(200, 409);

        int acceptAudit = count("SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'ACCEPT' AND object_type = 'TRANSFER' AND object_id = ?", receiverOrg.getId(), h.transferId());
        int rejectAudit = count("SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'REJECT' AND object_type = 'TRANSFER' AND object_id = ?", receiverOrg.getId(), h.transferId());
        assertThat(acceptAudit + rejectAudit).isEqualTo(1);
        assertThat(countEvents(batchId, "ARRIVAL")).isEqualTo(1);
        if (statuses[0] == 200) {
            assertThat(transferStatus(h.transferId())).isEqualTo("ACCEPTED");
            assertThat(batchOrgId(batchId)).isEqualTo(receiverOrg.getId());
            assertThat(traceCodeOrg(batchId)).isEqualTo(receiverOrg.getId());
        } else {
            assertThat(transferStatus(h.transferId())).isEqualTo("REJECTED");
            assertThat(batchOrgId(batchId)).isEqualTo(senderOrg.getId());
            assertThat(traceCodeOrg(batchId)).isEqualTo(senderOrg.getId());
        }
    }

    @Test
    @DisplayName("场景 L：接受幂等重放返回同结果，不重复转移责任组织，也不产生任何追溯事件")
    void scenarioL_idempotentAccept_replaysSameResultAndSingleArrivalEvent() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-L-" + suffix, new BigDecimal("320.000"));
        Handover h = prepareDeliveredHandover(batchId);
        int eventsBefore = count("SELECT count(*) FROM trace_event WHERE batch_id = ?", batchId);

        String idemKey = key("idem-l-acc");
        TransferAcceptRequest req = acceptReq("320.000", 2L);
        expect(postJson(receiverSession, "/api/v1/transfers/" + h.transferId() + "/accept", idemKey, req), 200);
        JsonNode replay = expect(postJson(receiverSession, "/api/v1/transfers/" + h.transferId() + "/accept", idemKey, req), 200).get("data");
        assertThat(replay.get("status").asString()).isEqualTo("ACCEPTED");

        assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ?", batchId)).isEqualTo(eventsBefore);
        assertThat(countEvents(batchId, "ARRIVAL")).isEqualTo(1);
        assertThat(batchMapper.selectById(batchId).getVersion()).isEqualTo(2L);
        assertThat(count("SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'ACCEPT' AND object_id = ?", receiverOrg.getId(), h.transferId())).isEqualTo(1);
    }

    @Test
    @DisplayName("场景 M：最长 128 字符 Idempotency-Key 创建与幂等重放")
    void scenarioM_maxLength128IdempotencyKey_creationAndReplay() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-M-" + suffix, new BigDecimal("250.000"));
        String key128 = "idem-128-" + suffix + "-" + "k".repeat(110);
        assertThat(key128).hasSize(128);
        TransferCreateRequest req = new TransferCreateRequest(batchId, receiverOrg.getId());

        Long transferId = expect(postJson(senderSession, "/api/v1/transfers", key128, req), 201).get("data").get("id").asLong();
        createdTransferIds.add(transferId);
        assertThat(jdbcTemplate.queryForObject("SELECT idempotency_key FROM transfer WHERE id = ?", String.class, transferId)).isEqualTo(key128);
        assertThat(expect(postJson(senderSession, "/api/v1/transfers", key128, req), 201).get("data").get("id").asLong()).isEqualTo(transferId);
        assertThat(count("SELECT count(*) FROM transfer WHERE idempotency_key = ?", key128)).isEqualTo(1);
    }

    @Test
    @DisplayName("场景 N：公开追溯码持有组织不匹配导致 409 TRACE_CODE_ORG_CONFLICT 与整体回滚")
    void scenarioN_publicTraceCodeOrgMismatch_rollsBackEntireAcceptance() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-N-" + suffix, new BigDecimal("190.000"));
        activateTraceCode(batchId);
        Handover h = prepareDeliveredHandover(batchId);
        jdbcTemplate.update("UPDATE public_trace_code SET org_id = ? WHERE batch_id = ?", 999999L, batchId);

        String acceptKey = key("idem-n-acc");
        expectProblem(postJson(receiverSession, "/api/v1/transfers/" + h.transferId() + "/accept", acceptKey, acceptReq("190.000", 2L)), 409, "TRACE_CODE_ORG_CONFLICT");

        Transfer t = transferMapper.selectById(h.transferId());
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(t.getVersion()).isEqualTo(2L);
        Batch b = batchMapper.selectById(batchId);
        assertThat(b.getOrgId()).isEqualTo(senderOrg.getId());
        assertThat(b.getVersion()).isEqualTo(1L);
        assertThat(traceCodeOrg(batchId)).isEqualTo(999999L);
        assertThat(count("SELECT count(*) FROM transfer_idempotency WHERE org_id = ? AND idempotency_key = ?", receiverOrg.getId(), acceptKey)).isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE actor_org_id = ? AND action = 'ACCEPT' AND object_id = ?", receiverOrg.getId(), h.transferId())).isZero();
    }

    // =========================================================================
    // Slice 2：Transfer 与 Shipment 协作契约
    // =========================================================================

    @Test
    @DisplayName("Slice 2：未绑定运输任务的交接不能提交为 PENDING（409 SHIPMENT_NOT_BOUND）")
    void submitWithoutShipment_rejected() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-S2-UNB-" + suffix, new BigDecimal("100.000"));
        Long transferId = createDraftTransfer(senderSession, batchId, receiverOrg.getId());
        expectProblem(submitRequest(senderSession, transferId, 0L), 409, "SHIPMENT_NOT_BOUND");
        assertThat(transferStatus(transferId)).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("Slice 2：非 PLANNED 运输任务不能绑定交接，已绑定草稿在运输任务取消后被解绑、不能提交")
    void bindOrSubmitAgainstNonPlannedShipment_rejected() throws Exception {
        Long batch1 = createAndSubmitActiveBatch(senderSession, "BAT-S2-NP1-" + suffix, new BigDecimal("100.000"));
        Long batch2 = createAndSubmitActiveBatch(senderSession, "BAT-S2-NP2-" + suffix, new BigDecimal("100.000"));
        Handover h = preparePendingHandover(batch1);
        dispatch(h.shipmentId());

        // 运输任务已 IN_TRANSIT：新交接不能绑定
        Long late = createDraftTransfer(senderSession, batch2, receiverOrg.getId());
        expectProblem(bindRequest(senderSession, h.shipmentId(), late, 0L), 409, "SHIPMENT_NOT_PLANNED");

        // 取消的运输任务：取消时解绑草稿，草稿因此不能提交
        Long cancelled = createShipment(senderSession);
        bind(cancelled, late);
        expect(postJson(senderSession, "/api/v1/shipments/" + cancelled + "/cancel", key("idem-cxl"),
                new com.example.traceability.trace.dto.ShipmentCancelRequest("计划变更", shipmentVersion(cancelled))), 200);
        assertThat(shipmentStatus(cancelled)).isEqualTo("CANCELLED");
        expectProblem(submitRequest(senderSession, late, transferVersion(late)), 409, "SHIPMENT_NOT_BOUND");
        expectProblem(bindRequest(senderSession, cancelled, late, transferVersion(late)), 409, "SHIPMENT_NOT_PLANNED");
    }

    @Test
    @DisplayName("Slice 2：运输任务 PLANNED / IN_TRANSIT 时接收方不能 ACCEPT / REJECT（409 SHIPMENT_NOT_DELIVERED）")
    void acceptOrRejectBeforeDelivered_rejected() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-S2-ND-" + suffix, new BigDecimal("100.000"));
        Handover h = preparePendingHandover(batchId);

        expectProblem(postJson(receiverSession, "/api/v1/transfers/" + h.transferId() + "/accept", key("idem-nd-acc-p"), acceptReq("100.000", 2L)), 409, "SHIPMENT_NOT_DELIVERED");
        expectProblem(postJson(receiverSession, "/api/v1/transfers/" + h.transferId() + "/reject", key("idem-nd-rej-p"), rejectReq("未到货", 2L)), 409, "SHIPMENT_NOT_DELIVERED");
        dispatch(h.shipmentId());
        expectProblem(postJson(receiverSession, "/api/v1/transfers/" + h.transferId() + "/accept", key("idem-nd-acc-t"), acceptReq("100.000", 2L)), 409, "SHIPMENT_NOT_DELIVERED");
        expectProblem(postJson(receiverSession, "/api/v1/transfers/" + h.transferId() + "/reject", key("idem-nd-rej-t"), rejectReq("在途", 2L)), 409, "SHIPMENT_NOT_DELIVERED");

        assertThat(transferStatus(h.transferId())).isEqualTo("PENDING");
        assertThat(batchOrgId(batchId)).isEqualTo(senderOrg.getId());
        arrive(h.shipmentId());
        // 运输任务到达不自动接受交接
        assertThat(transferStatus(h.transferId())).isEqualTo("PENDING");
        accept(receiverSession, h.transferId(), acceptReq("100.000", 2L), 200);
    }

    @Test
    @DisplayName("Slice 2：交接接收方不能为承运组织（422 RECEIVER_ORG_TYPE_NOT_ALLOWED）")
    void createDraftForCarrierReceiver_rejected() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-S2-CR-" + suffix, new BigDecimal("100.000"));
        expectProblem(postJson(senderSession, "/api/v1/transfers", key("idem-cr"), new TransferCreateRequest(batchId, carrierOrg.getId())), 422, "RECEIVER_ORG_TYPE_NOT_ALLOWED");
    }

    @Test
    @DisplayName("Slice 2：ACCEPT 后原发货方仍可只读查询交接、运输任务与本组织历史事件，但不能修改已转出批次")
    void historicalSender_canReadOwnRecordsAfterAccept_butCannotModifyBatch() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "BAT-S2-HIS-" + suffix, new BigDecimal("1000.000"));
        Handover h = prepareDeliveredHandover(batchId);
        accept(receiverSession, h.transferId(), acceptReq("1000.000", 2L), 200);

        // 交接与运输任务历史记录
        assertThat(expect(getReq(senderSession, "/api/v1/transfers/" + h.transferId()), 200).get("data").get("status").asString()).isEqualTo("ACCEPTED");
        JsonNode sent = expect(getReq(senderSession, "/api/v1/transfers?direction=SENT&batchId=" + batchId), 200).get("data");
        assertThat(sent).hasSize(1);
        assertThat(expect(getReq(senderSession, "/api/v1/shipments/" + h.shipmentId()), 200).get("data").get("status").asString()).isEqualTo("DELIVERED");
        assertThat(expect(getReq(carrierSession, "/api/v1/shipments/" + h.shipmentId()), 200).get("data").get("status").asString()).isEqualTo("DELIVERED");

        // 本组织参与环节的历史事件（SOURCE / TRANSPORT / ARRIVAL 均由发货方作为当时的责任组织记录）
        JsonNode senderEvents = expect(getReq(senderSession, "/api/v1/batches/" + batchId + "/events"), 200).get("data");
        assertThat(senderEvents).extracting(e -> e.get("eventType").asString()).containsExactly("SOURCE", "TRANSPORT", "ARRIVAL");

        // 当前责任组织看到完整时间线
        JsonNode receiverEvents = expect(getReq(receiverSession, "/api/v1/batches/" + batchId + "/events"), 200).get("data");
        assertThat(receiverEvents).extracting(e -> e.get("eventType").asString()).containsExactly("SOURCE", "TRANSPORT", "ARRIVAL");

        // 无关组织无权读取
        Organization thirdOrg = createOrg("ORG_HIS_" + suffix, "无关企业-" + suffix, "RETAILER");
        MockHttpSession thirdSession = newSession(thirdOrg, "his_thd", "OPERATOR", "OWN_ORG");
        expectProblem(getReq(thirdSession, "/api/v1/batches/" + batchId + "/events"), 403, "ORG_SCOPE_DENIED");

        // 原发货方不能再对已转出的批次发起交接或写入事件
        expectProblem(postJson(senderSession, "/api/v1/transfers", key("idem-his-c"), new TransferCreateRequest(batchId, receiverOrg.getId())), 403, "ORG_SCOPE_DENIED");
        MvcResult write = perform(postJson(senderSession, "/api/v1/batches/" + batchId + "/events", key("idem-his-ev"),
                java.util.Map.of("eventType", "WAREHOUSE_IN", "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString(),
                        "dataSource", "MANUAL", "summary", "越权写入")));
        assertThat(write.getResponse().getStatus()).isEqualTo(403);
        assertThat(batchOrgId(batchId)).isEqualTo(receiverOrg.getId());
    }

    // =========================================================================
    // 并发辅助
    // =========================================================================

    @FunctionalInterface
    private interface Call {
        MvcResult run() throws Exception;
    }

    /**
     * 两个线程经 CyclicBarrier 同时发起请求，返回两者 HTTP 状态码。
     */
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
