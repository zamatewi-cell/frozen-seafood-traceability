package com.example.traceability.trace;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.dto.BatchPatchRequest;
import com.example.traceability.batch.mapper.BatchRelationMapper;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.trace.dto.CreateTraceEventRequest;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import com.example.traceability.trace.dto.TransferCreateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase A Slice 3：PROCESS / SPLIT 真实 MySQL 8.4 端到端集成测试。
 * <p>
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 控制。批次均通过真实 API 从来源建批、Transfer + Shipment 送达并由加工企业 ACCEPT 后获得。
 * 覆盖：黄金链 1000 = 960 + 30 + 10 与 960 = 600 + 360；全量消耗；恰好一个 INPUT；精确平衡；SPLIT 继承批次类型且不产生事件；
 * 历史参与组织只读；并发与事务回滚；V10 物理约束；递归 CTE 深链环检测。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("批次操作 PROCESS / SPLIT MySQL 8.4 端到端集成测试")
class BatchOperationMysqlIntegrationTest extends AbstractTransferShipmentMysqlIT {

    @Autowired
    private BatchRelationMapper relationMapper;

    @Autowired
    private javax.sql.DataSource dataSource;

    // =========================================================================
    // 黄金链
    // =========================================================================

    @Test
    @DisplayName("黄金链：B0 1000kg → PROCESS → B0 CLOSED + B1 960kg (LOSS 30, SAMPLE 10, 1 条 PROCESS) → SPLIT → B1 CLOSED + B2 600 / B3 360（无 PACK / PROCESS）")
    void goldenChain_processThenSplit() throws Exception {
        Long b0 = processorHeldBatch("B0-" + suffix, new BigDecimal("1000.000"));
        assertThat(countEvents(b0, "ARRIVAL")).isEqualTo(1);

        // ---- PROCESS ----
        JsonNode op1Draft = createOperation("PROCESS", List.of(
                opInput(b0, "1000.000"), opOutput("960.000"), opOther("LOSS", "30.000"), opOther("SAMPLE", "10.000")));
        Long op1 = op1Draft.get("id").asLong();
        Long b1 = outputIds(op1Draft).get(0);
        // 草稿阶段：OUTPUT 为 DRAFT，INPUT 不变，无事件、无谱系边
        assertBatch(b1, "DRAFT", "NORMAL", "960.000", "PROCESSING");
        assertBatch(b0, "ACTIVE", "NORMAL", "1000.000", "SOURCE");
        assertThat(count("SELECT count(*) FROM batch_relation WHERE operation_id = ?", op1)).isZero();
        // 普通批次接口不得绕过操作修改 OUTPUT 草稿
        expectProblem(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/batches/" + b1)
                .session(receiverSession).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new BatchPatchRequest(0L, new BigDecimal("900.000"), null, null, null, null, null, null))),
                409, "BATCH_OWNED_BY_OPERATION");

        String submitKey = key("idem-op-s");
        JsonNode op1Submitted = expect(submitOperationRequest(receiverSession, op1, 0L, submitKey), 200).get("data");
        assertThat(op1Submitted.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(op1Submitted.get("relations")).hasSize(1);

        assertBatch(b0, "CLOSED", "NORMAL", "1000.000", "SOURCE");
        assertBatch(b1, "ACTIVE", "NORMAL", "960.000", "PROCESSING");
        assertThat(longCol("SELECT consumed_by_operation_id FROM batch WHERE id = ?", b0)).isEqualTo(op1);
        assertThat(longCol("SELECT produced_by_operation_id FROM batch WHERE id = ?", b1)).isEqualTo(op1);
        assertThat(longCol("SELECT org_id FROM batch WHERE id = ?", b1)).isEqualTo(receiverOrg.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT freeze_date FROM batch WHERE id = ?", java.sql.Date.class, b1)).isNull();
        assertRelation(op1, b0, b1, "TRANSFORM");
        assertThat(countEvents(b1, "PROCESS")).isEqualTo(1);
        assertThat(countEvents(b0, "PROCESS")).isZero();
        assertThat(countEvents(b1, "FREEZE")).as("普通 PROCESS 不推导 FREEZE").isZero();
        Map<String, Object> processEvent = jdbcTemplate.queryForMap(
                "SELECT org_id, idempotency_key, summary, details_json->>'$.sourceObjectType' AS src, details_json->>'$.operationNo' AS opNo "
                        + "FROM trace_event WHERE batch_id = ? AND event_type = 'PROCESS'", b1);
        assertThat(((Number) processEvent.get("org_id")).longValue()).isEqualTo(receiverOrg.getId());
        assertThat(processEvent.get("idempotency_key")).isEqualTo("SYS:PROCESS:OPERATION:" + op1 + ":BATCH:" + b1);
        assertThat(processEvent.get("summary")).isEqualTo("加工产出 960 kg（投入 1000 kg，损耗 30 kg，留样 10 kg）");
        assertThat(processEvent.get("src")).isEqualTo("BATCH_OPERATION");
        assertThat(processEvent.get("opNo")).isEqualTo(op1Submitted.get("operationNo").asString());
        assertItemsBalance(op1, "1000.000", "960.000", "30.000", "0", "10.000");

        // 同键重放：不重复关闭 / 激活 / 事件；换键重提：409
        expect(submitOperationRequest(receiverSession, op1, 0L, submitKey), 200);
        expectProblem(submitOperationRequest(receiverSession, op1, 1L, key("idem-op-s2")), 409, "INVALID_STATE_TRANSITION");
        assertThat(countEvents(b1, "PROCESS")).isEqualTo(1);

        // 批次详情派生剩余量
        assertThat(new BigDecimal(expect(getReq(receiverSession, "/api/v1/batches/" + b0), 200).get("data").get("remainingQuantity").toString()))
                .isEqualByComparingTo("0");
        assertThat(new BigDecimal(expect(getReq(receiverSession, "/api/v1/batches/" + b1), 200).get("data").get("remainingQuantity").toString()))
                .isEqualByComparingTo("960");

        // ---- SPLIT ----
        JsonNode op2Submitted = createAndSubmitOperation("SPLIT", List.of(opInput(b1, "960.000"), opOutput("600.000"), opOutput("360.000")));
        Long op2 = op2Submitted.get("id").asLong();
        List<Long> splitOutputs = outputIds(op2Submitted);
        Long b2 = splitOutputs.get(0);
        Long b3 = splitOutputs.get(1);

        assertBatch(b1, "CLOSED", "NORMAL", "960.000", "PROCESSING");
        assertBatch(b2, "ACTIVE", "NORMAL", "600.000", "PROCESSING");
        assertBatch(b3, "ACTIVE", "NORMAL", "360.000", "PROCESSING");
        assertThat(longCol("SELECT consumed_by_operation_id FROM batch WHERE id = ?", b1)).isEqualTo(op2);
        assertRelation(op2, b1, b2, "SPLIT");
        assertRelation(op2, b1, b3, "SPLIT");
        for (Long b : List.of(b2, b3)) {
            assertThat(countEvents(b, "PACK")).as("SPLIT 不自动声称 PACK").isZero();
            assertThat(countEvents(b, "PROCESS")).as("SPLIT 不伪装成 PROCESS").isZero();
            assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ?", b)).isZero();
        }
        assertThat(countEvents(b1, "PROCESS")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM trace_event WHERE idempotency_key LIKE ?", "SYS:%OPERATION:" + op2 + "%")).isZero();
        assertItemsBalance(op2, "960.000", "960.000", "0", "0", "0");

        // 已关闭批次不可再次加工
        expectProblem(createOperationRequest(receiverSession, key("idem-op-x"), "PROCESS",
                List.of(opInput(b0, "1000.000"), opOutput("1000.000"))), 409, "BATCH_ALREADY_CONSUMED");
        // 人工接口不得伪造 PROCESS
        expectProblem(postJson(receiverSession, "/api/v1/batches/" + b2 + "/events", key("idem-evt"),
                new CreateTraceEventRequest("PROCESS", OffsetDateTime.now(ZoneOffset.UTC), null, "MANUAL", "伪造加工", null)),
                422, "EVENT_TYPE_NOT_MANUAL");
    }

    // =========================================================================
    // 数量规则
    // =========================================================================

    @Test
    @DisplayName("全量消耗：部分 INPUT（500/1000）被拒绝并提示先 SPLIT；操作与输出批次均不落库")
    void partialInput_rejected() throws Exception {
        Long b0 = processorHeldBatch("B0P-" + suffix, new BigDecimal("1000.000"));
        expectProblem(createOperationRequest(receiverSession, key("idem-op-p"), "PROCESS",
                List.of(opInput(b0, "500.000"), opOutput("480.000"), opOther("LOSS", "20.000"))), 422, "PARTIAL_INPUT_NOT_ALLOWED");
        assertThat(count("SELECT count(*) FROM batch_operation WHERE org_id = ?", receiverOrg.getId())).isZero();
        assertThat(count("SELECT count(*) FROM batch WHERE produced_by_operation_id IS NOT NULL AND creation_org_id = ?", receiverOrg.getId())).isZero();
        assertBatch(b0, "ACTIVE", "NORMAL", "1000.000", "SOURCE");
    }

    @Test
    @DisplayName("精确平衡：差 0.001 kg 被拒绝；scale 不同但数值相等（1000.0 = 960.000 + 30 + 10.00）通过")
    void exactMassBalance_numericComparison() throws Exception {
        Long b0 = processorHeldBatch("B0M-" + suffix, new BigDecimal("1000.000"));
        expectProblem(createOperationRequest(receiverSession, key("idem-op-m1"), "PROCESS",
                List.of(opInput(b0, "1000.000"), opOutput("959.999"), opOther("LOSS", "30"), opOther("SAMPLE", "10"))),
                422, "BATCH_MASS_BALANCE_VIOLATION");
        JsonNode op = createAndSubmitOperation("PROCESS",
                List.of(opInput(b0, "1000.0"), opOutput("960.000"), opOther("LOSS", "30"), opOther("SAMPLE", "10.00")));
        assertThat(op.get("balanced").asBoolean()).isTrue();
        assertBatch(b0, "CLOSED", "NORMAL", "1000.000", "SOURCE");
    }

    @Test
    @DisplayName("恰好一个 INPUT：PROCESS / SPLIT 双输入被拒绝；MERGE / REPACK 在当前 Slice 返回 OPERATION_TYPE_NOT_SUPPORTED")
    void singleInput_andUnsupportedTypes() throws Exception {
        Long b0 = processorHeldBatch("B0A-" + suffix, new BigDecimal("300.000"));
        Long b0b = processorHeldBatch("B0B-" + suffix, new BigDecimal("200.000"));
        expectProblem(createOperationRequest(receiverSession, key("idem-op-2i"), "PROCESS",
                List.of(opInput(b0, "300.000"), opInput(b0b, "200.000"), opOutput("500.000"))), 400, "INVALID_REQUEST");
        expectProblem(createOperationRequest(receiverSession, key("idem-op-2s"), "SPLIT",
                List.of(opInput(b0, "300.000"), opInput(b0b, "200.000"), opOutput("250.000"), opOutput("250.000"))), 400, "INVALID_REQUEST");
        expectProblem(createOperationRequest(receiverSession, key("idem-op-mg"), "MERGE",
                List.of(opInput(b0, "300.000"), opInput(b0b, "200.000"), opOutput("500.000"))), 422, "OPERATION_TYPE_NOT_SUPPORTED");
        expectProblem(createOperationRequest(receiverSession, key("idem-op-rp"), "REPACK",
                List.of(opInput(b0, "300.000"), opOutput("300.000"))), 422, "OPERATION_TYPE_NOT_SUPPORTED");
        expectProblem(postJson(receiverSession, "/api/v1/batch-operations", key("idem-op-ob"),
                new com.example.traceability.batch.dto.BatchOperationCreateRequest("PROCESS", OffsetDateTime.now(ZoneOffset.UTC), null,
                        List.of(opInput(b0, "300.000"), new com.example.traceability.batch.dto.BatchOperationItemRequest("OUTPUT", b0b, new BigDecimal("300.000"))))),
                400, "OUTPUT_BATCH_SERVER_GENERATED");
        assertThat(count("SELECT count(*) FROM batch_operation WHERE org_id = ?", receiverOrg.getId())).isZero();
    }

    @Test
    @DisplayName("SPLIT 继承输入批次类型：SOURCE 批次拆分产出为 SOURCE + ACTIVE，但不生成 SOURCE / PROCESS / PACK（Slice 3 实现规则：来源出处经谱系追溯到 B0）")
    void split_sourceBatch_keepsSourceType_withoutSourceEvent() throws Exception {
        Long b0 = processorHeldBatch("B0S-" + suffix, new BigDecimal("1000.000"));
        assertThat(countEvents(b0, "SOURCE")).isEqualTo(1);
        JsonNode op = createAndSubmitOperation("SPLIT", List.of(opInput(b0, "1000.000"), opOutput("700.000"), opOutput("300.000")));
        for (Long out : outputIds(op)) {
            assertBatch(out, "ACTIVE", "NORMAL", out.equals(outputIds(op).get(0)) ? "700.000" : "300.000", "SOURCE");
            assertThat(jdbcTemplate.queryForObject("SELECT product_id FROM batch WHERE id = ?", Long.class, out)).isEqualTo(testProduct.getId());
            assertThat(countEvents(out, "SOURCE")).as("Slice 3 实现规则：SPLIT 子批次随操作激活不生成新的 SOURCE").isZero();
            assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ?", out)).isZero();
            assertThat(count("SELECT count(*) FROM trace_event WHERE idempotency_key = ?", "SYS:SOURCE:BATCH:" + out)).isZero();
            // 子批次不能再经普通来源提交补出 SOURCE（加工企业无权提交来源批次；批次已 ACTIVE）
            expectProblem(postJson(receiverSession, "/api/v1/batches/" + out + "/submit", key("idem-bat-s"),
                    new com.example.traceability.batch.dto.BatchSubmitRequest(1L)), 403, "ORG_TYPE_NOT_ALLOWED");
        }
        assertBatch(b0, "CLOSED", "NORMAL", "1000.000", "SOURCE");
        assertThat(countEvents(b0, "SOURCE")).as("来源事实仍只在祖先 B0，恰好一条").isEqualTo(1);
    }

    @Test
    @DisplayName("OUTPUT 草稿闭合：创建幂等重放返回原 OUTPUT、不能 PATCH / 普通提交 / 交接；提交重放不重复谱系与事件")
    void operationOutputDrafts_areClosedToOrdinaryPaths() throws Exception {
        Long b0 = processorHeldBatch("B0O-" + suffix, new BigDecimal("1000.000"));
        Organization retailer = createOrg("ORG_OC_" + suffix, "零售企业-" + suffix, "RETAILER");
        String createKey = key("idem-op-oc");
        List<com.example.traceability.batch.dto.BatchOperationItemRequest> items =
                List.of(opInput(b0, "1000.000"), opOutput("970.000"), opOther("WASTE", "30.000"));
        // 真正的重放：完全相同的请求体（含同一业务发生时间）与同一幂等键
        com.example.traceability.batch.dto.BatchOperationCreateRequest body = new com.example.traceability.batch.dto.BatchOperationCreateRequest(
                "PROCESS", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5), "闭合测试", items);
        JsonNode first = expect(postJson(receiverSession, "/api/v1/batch-operations", createKey, body), 201).get("data");
        registerOperation(first);
        Long opId = first.get("id").asLong();
        List<Long> outputs = outputIds(first);
        JsonNode replay = expect(postJson(receiverSession, "/api/v1/batch-operations", createKey, body), 201).get("data");
        // 同键不同语义（业务发生时间不同）仍为 409
        expectProblem(createOperationRequest(receiverSession, createKey, "PROCESS", items), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(replay.get("id").asLong()).isEqualTo(opId);
        assertThat(outputIds(replay)).isEqualTo(outputs);
        assertThat(count("SELECT count(*) FROM batch WHERE produced_by_operation_id = ?", opId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM batch_operation WHERE org_id = ?", receiverOrg.getId())).isEqualTo(1);

        Long out = outputs.get(0);
        expectProblem(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/batches/" + out)
                .session(receiverSession).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new BatchPatchRequest(0L, new BigDecimal("999.000"), "EXT-X", "伪造来源", null, null, null, null))),
                409, "BATCH_OWNED_BY_OPERATION");
        expectProblem(postJson(receiverSession, "/api/v1/batches/" + out + "/submit", key("idem-bat-s"),
                new com.example.traceability.batch.dto.BatchSubmitRequest(0L)), 403, "ORG_TYPE_NOT_ALLOWED");
        expectProblem(postJson(receiverSession, "/api/v1/transfers", key("idem-trf-oc"), new TransferCreateRequest(out, retailer.getId())), 409, "BATCH_NOT_ACTIVE");
        assertBatch(out, "DRAFT", "NORMAL", "970.000", "PROCESSING");
        assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ?", out)).isZero();
        assertThat(count("SELECT count(*) FROM transfer WHERE batch_id = ?", out)).isZero();

        String submitKey = key("idem-op-oc-s");
        expect(submitOperationRequest(receiverSession, opId, 0L, submitKey), 200);
        expect(submitOperationRequest(receiverSession, opId, 0L, submitKey), 200);
        assertThat(count("SELECT count(*) FROM batch_relation WHERE operation_id = ?", opId)).isEqualTo(1);
        assertThat(countEvents(out, "PROCESS")).isEqualTo(1);
        assertBatch(out, "ACTIVE", "NORMAL", "970.000", "PROCESSING");
    }

    @Test
    @DisplayName("历史部分消耗批次：只能恰好消耗剩余量")
    void legacyPartiallyConsumed_consumesExactRemainder() throws Exception {
        Long b0 = processorHeldBatch("B0L-" + suffix, new BigDecimal("1000.000"));
        jdbcTemplate.update("INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, status, idempotency_key, version, is_deleted, created_at, updated_at) "
                + "VALUES (?, ?, 'PROCESS', NOW(6), 'SUBMITTED', ?, 1, 0, NOW(6), NOW(6))", receiverOrg.getId(), "OP-LEG-" + suffix, "legacy-" + suffix);
        Long legacyOp = longCol("SELECT id FROM batch_operation WHERE operation_no = ?", "OP-LEG-" + suffix);
        createdOperationIds.add(legacyOp);
        jdbcTemplate.update("INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity, version, is_deleted, created_at, updated_at) "
                + "VALUES (?, ?, 'INPUT', 400.000, 'kg', 400.000, 0, 0, NOW(6), NOW(6))", legacyOp, b0);

        expectProblem(createOperationRequest(receiverSession, key("idem-op-l1"), "PROCESS",
                List.of(opInput(b0, "1000.000"), opOutput("1000.000"))), 422, "PARTIAL_INPUT_NOT_ALLOWED");
        createAndSubmitOperation("PROCESS", List.of(opInput(b0, "600.000"), opOutput("600.000")));
        assertBatch(b0, "CLOSED", "NORMAL", "1000.000", "SOURCE");
    }

    // =========================================================================
    // 权限：历史参与组织只读
    // =========================================================================

    @Test
    @DisplayName("批次转出后：原加工企业可读本组织操作（含 QUALITY_MANAGER），但不能再操作该批次；新持有方与无关组织看不到加工企业内部单据")
    void historicalProcessor_readOnly() throws Exception {
        Long b0 = processorHeldBatch("B0H-" + suffix, new BigDecimal("1000.000"));
        JsonNode op1 = createAndSubmitOperation("PROCESS", List.of(opInput(b0, "1000.000"), opOutput("1000.000")));
        Long op1Id = op1.get("id").asLong();
        Long b1 = outputIds(op1).get(0);

        // 加工企业把 B1 交给第二家加工企业
        Organization p2 = createOrg("ORG_P2_" + suffix, "第二加工企业-" + suffix, "PROCESSOR");
        Site p2Site = createSite(p2.getId(), "P2-FAC-" + suffix, "第二加工厂-" + suffix, "FACTORY");
        MockHttpSession p2Session = newSession(p2, "p2_op", "OPERATOR", "OWN_ORG");
        Long transferId = createDraftTransfer(receiverSession, b1, p2.getId());
        JsonNode shipment = expect(createShipmentRequest(receiverSession, key("idem-shp-c"), carrierOrg.getId(), receiverSite.getId(), p2Site.getId()), 201).get("data");
        Long shipmentId = shipment.get("id").asLong();
        createdShipmentIds.add(shipmentId);
        expect(bindRequest(receiverSession, shipmentId, transferId, transferVersion(transferId)), 200);
        expect(submitRequest(receiverSession, transferId, transferVersion(transferId)), 200);
        dispatch(shipmentId);
        arrive(shipmentId);
        expect(postJson(p2Session, "/api/v1/transfers/" + transferId + "/accept", key("idem-acc-p2"),
                new TransferAcceptRequest(new BigDecimal("1000.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, transferVersion(transferId))), 200);
        assertThat(batchOrgId(b1)).isEqualTo(p2.getId());

        // 原加工企业：历史只读
        assertThat(expect(getReq(receiverSession, "/api/v1/batch-operations/" + op1Id), 200).get("data").get("id").asLong()).isEqualTo(op1Id);
        JsonNode ownList = expect(getReq(receiverSession, "/api/v1/batch-operations?batchId=" + b1), 200).get("data");
        assertThat(ownList).hasSize(1);
        assertThat(ownList.get(0).get("id").asLong()).isEqualTo(op1Id);
        MockHttpSession qmSession = newSession(receiverOrg, "prc_qm", "QUALITY_MANAGER", "OWN_ORG");
        assertThat(expect(getReq(qmSession, "/api/v1/batch-operations/" + op1Id), 200).get("data").get("status").asString()).isEqualTo("SUBMITTED");
        expectProblem(createOperationRequest(qmSession, key("idem-op-qm"), "SPLIT",
                List.of(opInput(b1, "1000.000"), opOutput("500.000"), opOutput("500.000"))), 403, "ACCESS_DENIED");
        // 原加工企业不能再加工已转出的批次
        expectProblem(createOperationRequest(receiverSession, key("idem-op-h"), "SPLIT",
                List.of(opInput(b1, "1000.000"), opOutput("500.000"), opOutput("500.000"))), 403, "ORG_SCOPE_DENIED");

        // 新持有方：可列表但只看到本组织操作（此处为空），读不到原加工企业的内部单据
        assertThat(expect(getReq(p2Session, "/api/v1/batch-operations?batchId=" + b1), 200).get("data")).isEmpty();
        expectProblem(getReq(p2Session, "/api/v1/batch-operations/" + op1Id), 403, "ORG_SCOPE_DENIED");
        // 无关组织：拒绝
        Organization retailer = createOrg("ORG_HR_" + suffix, "无关零售企业-" + suffix, "RETAILER");
        MockHttpSession retailerSession = newSession(retailer, "hr_op", "OPERATOR", "OWN_ORG");
        expectProblem(getReq(retailerSession, "/api/v1/batch-operations/" + op1Id), 403, "ORG_SCOPE_DENIED");
        expectProblem(getReq(retailerSession, "/api/v1/batch-operations?batchId=" + b1), 403, "ORG_SCOPE_DENIED");
        // 来源企业（非 PROCESSOR）不能加工
        expectProblem(createOperationRequest(senderSession, key("idem-op-src"), "PROCESS",
                List.of(opInput(b0, "1000.000"), opOutput("1000.000"))), 403, "ORG_TYPE_NOT_ALLOWED");
    }

    // =========================================================================
    // 删除草稿
    // =========================================================================

    @Test
    @DisplayName("删除草稿：操作、明细与 OUTPUT 草稿批次一并逻辑删除；输入批次可再次加工；旧创建键不可复用")
    void deleteDraft_releasesOutputsAndInput() throws Exception {
        Long b0 = processorHeldBatch("B0D-" + suffix, new BigDecimal("500.000"));
        String createKey = key("idem-op-d");
        JsonNode draft = expect(createOperationRequest(receiverSession, createKey, "SPLIT",
                List.of(opInput(b0, "500.000"), opOutput("200.000"), opOutput("300.000"))), 201).get("data");
        registerOperation(draft);
        Long opId = draft.get("id").asLong();
        List<Long> outs = outputIds(draft);

        expect(deleteReq(receiverSession, "/api/v1/batch-operations/" + opId).param("expectedVersion", "0"), 204);
        assertThat(count("SELECT count(*) FROM batch_operation WHERE id = ? AND is_deleted = 1", opId)).isEqualTo(1);
        for (Long out : outs) {
            assertThat(count("SELECT count(*) FROM batch WHERE id = ? AND is_deleted = 1", out)).isEqualTo(1);
        }
        expectProblem(getReq(receiverSession, "/api/v1/batches/" + outs.get(0)), 404, "RESOURCE_NOT_FOUND");
        expectProblem(createOperationRequest(receiverSession, createKey, "SPLIT",
                List.of(opInput(b0, "500.000"), opOutput("200.000"), opOutput("300.000"))), 409, "IDEMPOTENCY_CONFLICT");

        createAndSubmitOperation("SPLIT", List.of(opInput(b0, "500.000"), opOutput("250.000"), opOutput("250.000")));
        assertBatch(b0, "CLOSED", "NORMAL", "500.000", "SOURCE");
    }

    // =========================================================================
    // 并发与回滚
    // =========================================================================

    @Test
    @DisplayName("并发：两张草稿同时全量消耗同一批次，只有一张提交成功，仅一组输出激活、一条 PROCESS")
    void concurrentSubmit_twoDraftsSameInput_onlyOneWins() throws Exception {
        Long b0 = processorHeldBatch("B0C-" + suffix, new BigDecimal("1000.000"));
        JsonNode d1 = createOperation("PROCESS", List.of(opInput(b0, "1000.000"), opOutput("1000.000")));
        JsonNode d2 = createOperation("PROCESS", List.of(opInput(b0, "1000.000"), opOutput("990.000"), opOther("WASTE", "10.000")));

        int[] statuses = race(
                () -> perform(submitOperationRequest(receiverSession, d1.get("id").asLong(), 0L, key("idem-race-1"))),
                () -> perform(submitOperationRequest(receiverSession, d2.get("id").asLong(), 0L, key("idem-race-2"))));
        Arrays.sort(statuses);
        assertThat(statuses).containsExactly(200, 409);

        assertBatch(b0, "CLOSED", "NORMAL", "1000.000", "SOURCE");
        assertThat(count("SELECT count(*) FROM batch_operation WHERE id IN (?, ?) AND status = 'SUBMITTED'", d1.get("id").asLong(), d2.get("id").asLong())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM batch_relation WHERE parent_batch_id = ?", b0)).isEqualTo(1);
        int activeOutputs = 0;
        int processEvents = 0;
        for (Long out : List.of(outputIds(d1).get(0), outputIds(d2).get(0))) {
            activeOutputs += count("SELECT count(*) FROM batch WHERE id = ? AND flow_status = 'ACTIVE'", out);
            processEvents += countEvents(out, "PROCESS");
        }
        assertThat(activeOutputs).isEqualTo(1);
        assertThat(processEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("顺序 A：交接先成功 → 批次操作提交 409 BATCH_TRANSFER_OPEN（批次仍 ACTIVE，恰好一个业务动作成功）")
    void transferFirst_thenOperationSubmit_conflicts() throws Exception {
        Long b0 = processorHeldBatch("B0TA-" + suffix, new BigDecimal("500.000"));
        Organization retailer = createOrg("ORG_TA_" + suffix, "零售企业-" + suffix, "RETAILER");
        JsonNode draft = createOperation("PROCESS", List.of(opInput(b0, "500.000"), opOutput("500.000")));

        expect(postJson(receiverSession, "/api/v1/transfers", key("idem-ta-t"), new TransferCreateRequest(b0, retailer.getId())), 201);
        expectProblem(submitOperationRequest(receiverSession, draft.get("id").asLong(), 0L, key("idem-ta-o")), 409, "BATCH_TRANSFER_OPEN");

        assertBatch(b0, "ACTIVE", "NORMAL", "500.000", "SOURCE");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM batch_operation WHERE id = ?", String.class, draft.get("id").asLong())).isEqualTo("DRAFT");
        assertThat(count("SELECT count(*) FROM transfer WHERE batch_id = ? AND status = 'DRAFT' AND is_deleted = 0", b0)).isEqualTo(1);
        assertBatch(outputIds(draft).get(0), "DRAFT", "NORMAL", "500.000", "PROCESSING");
    }

    @Test
    @DisplayName("顺序 B：批次操作先成功 → 新建交接 409 BATCH_NOT_ACTIVE（批次 CLOSED，无未结束交接，恰好一个业务动作成功）")
    void operationFirst_thenTransferCreate_conflicts() throws Exception {
        Long b0 = processorHeldBatch("B0TB-" + suffix, new BigDecimal("500.000"));
        Organization retailer = createOrg("ORG_TB_" + suffix, "零售企业-" + suffix, "RETAILER");
        JsonNode draft = createOperation("PROCESS", List.of(opInput(b0, "500.000"), opOutput("500.000")));

        expect(submitOperationRequest(receiverSession, draft.get("id").asLong(), 0L, key("idem-tb-o")), 200);
        expectProblem(postJson(receiverSession, "/api/v1/transfers", key("idem-tb-t"), new TransferCreateRequest(b0, retailer.getId())), 409, "BATCH_NOT_ACTIVE");

        assertBatch(b0, "CLOSED", "NORMAL", "500.000", "SOURCE");
        assertThat(count("SELECT count(*) FROM transfer WHERE batch_id = ? AND status IN ('DRAFT', 'PENDING') AND is_deleted = 0", b0)).isZero();
    }

    @Test
    @DisplayName("确定性交错（READ COMMITTED 回归）：操作提交在批次行锁上等待期间交接提交，取得行锁后必须看到该交接并 409，不得同时成功")
    void transferCommittedWhileOperationWaitsOnBatchLock_operationSeesIt() throws Exception {
        Long b0 = processorHeldBatch("B0TI-" + suffix, new BigDecimal("500.000"));
        Organization retailer = createOrg("ORG_TI_" + suffix, "零售企业-" + suffix, "RETAILER");
        JsonNode draft = createOperation("PROCESS", List.of(opInput(b0, "500.000"), opOutput("500.000")));

        AtomicReference<MvcResult> submitResult = new AtomicReference<>();
        AtomicReference<Throwable> submitError = new AtomicReference<>();
        Thread submitter = new Thread(() -> {
            try {
                submitResult.set(perform(submitOperationRequest(receiverSession, draft.get("id").asLong(), 0L, key("idem-ti-o"))));
            } catch (Throwable t) {
                submitError.set(t);
            }
        }, "slice3-operation-submitter");
        // holder 使用被测应用自身的数据源（trace_user），模拟 Transfer 新建路径：锁定批次行 → 插入交接 → 提交
        java.sql.Connection holder = dataSource.getConnection();
        try {
            holder.setAutoCommit(false);
            try (var ps = holder.prepareStatement("SELECT id FROM batch WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, b0);
                ps.executeQuery().close();
            }
            submitter.start();
            // 确定性同步点：操作提交事务已完成锁前读取，正阻塞在本 schema 的 batch 行锁上
            awaitRowLockWaitOnBatchTable(submitter);
            try (var ps = holder.prepareStatement(
                    "INSERT INTO transfer (transfer_no, batch_id, sender_org_id, receiver_org_id, quantity, unit_code, status, idempotency_key) "
                            + "VALUES (?, ?, ?, ?, 500.000, 'kg', 'DRAFT', ?)")) {
                ps.setString(1, "TRF-TI-" + suffix);
                ps.setLong(2, b0);
                ps.setLong(3, receiverOrg.getId());
                ps.setLong(4, retailer.getId());
                ps.setString(5, "ti-" + suffix);
                ps.executeUpdate();
            }
            holder.commit();
        } finally {
            // 无论探针或断言是否失败：释放行锁，并等待提交线程结束后再交给 teardown 清理，避免与清理并发写入
            try {
                if (!holder.getAutoCommit()) {
                    holder.rollback();
                }
            } finally {
                holder.close();
            }
            if (submitter.getState() != Thread.State.NEW) {
                submitter.join(30_000);
                assertThat(submitter.isAlive()).as("operation submit thread did not finish").isFalse();
            }
        }
        if (submitError.get() != null) {
            throw new AssertionError("operation submit failed", submitError.get());
        }
        MvcResult result = submitResult.get();
        assertThat(result.getResponse().getStatus()).as("body=%s", result.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asString()).isEqualTo("BATCH_TRANSFER_OPEN");
        assertBatch(b0, "ACTIVE", "NORMAL", "500.000", "SOURCE");
        assertThat(count("SELECT count(*) FROM transfer WHERE batch_id = ? AND status = 'DRAFT'", b0)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM batch_relation WHERE operation_id = ?", draft.get("id").asLong())).isZero();
    }

    /**
     * 等待本 schema 的 batch 表上出现 InnoDB 行锁等待（即操作提交已阻塞在批次行锁上）。
     * <p>
     * performance_schema 的锁视图需要管理员权限：被测应用数据源（CI 中为非 root 的 trace_user）不能也不应读取，
     * 因此仅这一诊断探针沿用 V9 / V10 迁移测试的约定，通过 DB_ROOT_USERNAME / DB_ROOT_PASSWORD 单独建立管理连接；
     * 应用数据源、权限与业务行为均不变。若提交线程在出现锁等待前就已结束，说明未经过同步点，立即失败。
     * </p>
     */
    private void awaitRowLockWaitOnBatchTable(Thread submitter) throws Exception {
        String baseUrl = ((com.zaxxer.hikari.HikariDataSource) dataSource).getJdbcUrl();
        String rootUser = System.getenv().getOrDefault("DB_ROOT_USERNAME", "root");
        if (rootUser.isBlank()) {
            rootUser = "root";
        }
        String rootPassword = System.getenv().getOrDefault("DB_ROOT_PASSWORD", "");
        String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        String probe = "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
                + "WHERE l.OBJECT_SCHEMA = ? AND l.OBJECT_NAME = 'batch'";
        long deadline = System.currentTimeMillis() + 15_000;
        try (java.sql.Connection admin = java.sql.DriverManager.getConnection(baseUrl, rootUser, rootPassword);
             java.sql.PreparedStatement ps = admin.prepareStatement(probe)) {
            ps.setString(1, schema);
            while (true) {
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return;
                    }
                }
                assertThat(submitter.isAlive()).as("operation submit finished without waiting on the batch row lock").isTrue();
                assertThat(System.currentTimeMillis()).as("operation submit never waited on the batch row lock").isLessThan(deadline);
                Thread.sleep(20);
            }
        }
    }

    @Test
    @DisplayName("并发：新建交接与提交批次操作竞争同一批次，二者至多一个成功，不出现既关闭又有未结束交接")
    void concurrentTransferCreateVsOperationSubmit() throws Exception {
        Long b0 = processorHeldBatch("B0T-" + suffix, new BigDecimal("800.000"));
        Organization retailer = createOrg("ORG_CR_" + suffix, "零售企业-" + suffix, "RETAILER");
        JsonNode draft = createOperation("PROCESS", List.of(opInput(b0, "800.000"), opOutput("800.000")));

        int[] statuses = race(
                () -> perform(postJson(receiverSession, "/api/v1/transfers", key("idem-race-t"), new TransferCreateRequest(b0, retailer.getId()))),
                () -> perform(submitOperationRequest(receiverSession, draft.get("id").asLong(), 0L, key("idem-race-o"))));
        int transferStatus = statuses[0];
        int operationStatus = statuses[1];
        assertThat((transferStatus == 201 ? 1 : 0) + (operationStatus == 200 ? 1 : 0)).isEqualTo(1);
        String flow = jdbcTemplate.queryForObject("SELECT flow_status FROM batch WHERE id = ?", String.class, b0);
        int openTransfers = count("SELECT count(*) FROM transfer WHERE batch_id = ? AND status IN ('DRAFT', 'PENDING') AND is_deleted = 0", b0);
        if (operationStatus == 200) {
            assertThat(flow).isEqualTo("CLOSED");
            assertThat(openTransfers).isZero();
        } else {
            assertThat(flow).isEqualTo("ACTIVE");
            assertThat(openTransfers).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("事务回滚：PROCESS 事件身份被预先占用时整个提交回滚（操作 DRAFT、输入 ACTIVE、输出 DRAFT、无谱系边）")
    void processEventConflict_rollsBackEverything() throws Exception {
        Long b0 = processorHeldBatch("B0R-" + suffix, new BigDecimal("1000.000"));
        JsonNode draft = createOperation("PROCESS", List.of(opInput(b0, "1000.000"), opOutput("970.000"), opOther("LOSS", "30.000")));
        Long opId = draft.get("id").asLong();
        Long out = outputIds(draft).get(0);
        jdbcTemplate.update("INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, data_source, status, summary, idempotency_key) "
                        + "VALUES (?, ?, 'FREEZE', NOW(6), NOW(6), 'MANUAL', 'SUBMITTED', '占位', ?)",
                b0, receiverOrg.getId(), "SYS:PROCESS:OPERATION:" + opId + ":BATCH:" + out);

        expectProblem(submitOperationRequest(receiverSession, opId, 0L, key("idem-op-rb")), 409, "PROCESS_EVENT_CONFLICT");

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM batch_operation WHERE id = ?", String.class, opId)).isEqualTo("DRAFT");
        assertBatch(b0, "ACTIVE", "NORMAL", "1000.000", "SOURCE");
        assertThat(jdbcTemplate.queryForObject("SELECT consumed_by_operation_id FROM batch WHERE id = ?", Long.class, b0)).isNull();
        assertBatch(out, "DRAFT", "NORMAL", "970.000", "PROCESSING");
        assertThat(count("SELECT count(*) FROM batch_relation WHERE operation_id = ?", opId)).isZero();
        assertThat(countEvents(out, "PROCESS")).isZero();
    }

    // =========================================================================
    // V10 物理约束与 CTE
    // =========================================================================

    @Test
    @DisplayName("V10 物理约束：输出唯一产出、角色形状、被消耗必须 CLOSED、禁止重复上游边；V4 约束仍有效")
    void v10PhysicalConstraints() throws Exception {
        Long b0 = processorHeldBatch("B0V-" + suffix, new BigDecimal("100.000"));
        JsonNode op = createAndSubmitOperation("PROCESS", List.of(opInput(b0, "100.000"), opOutput("100.000")));
        Long opId = op.get("id").asLong();
        Long out = outputIds(op).get(0);
        jdbcTemplate.update("INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, status, idempotency_key, version, is_deleted, created_at, updated_at) "
                + "VALUES (?, ?, 'SPLIT', NOW(6), 'DRAFT', ?, 0, 0, NOW(6), NOW(6))", receiverOrg.getId(), "OP-V10-" + suffix, "v10-" + suffix);
        Long rawOp = longCol("SELECT id FROM batch_operation WHERE operation_no = ?", "OP-V10-" + suffix);
        createdOperationIds.add(rawOp);

        // 同一批次不能作为两个操作的 OUTPUT
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity, created_at, updated_at) "
                        + "VALUES (?, ?, 'OUTPUT', 100, 'kg', 100, NOW(6), NOW(6))", rawOp, out));
        // LOSS 不能引用批次；INPUT 必须引用批次
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity, created_at, updated_at) "
                        + "VALUES (?, ?, 'LOSS', 1, 'kg', 1, NOW(6), NOW(6))", rawOp, b0));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity, created_at, updated_at) "
                        + "VALUES (?, NULL, 'INPUT', 1, 'kg', 1, NOW(6), NOW(6))", rawOp));
        // 被操作全量消耗的批次必须为 CLOSED
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("UPDATE batch SET flow_status = 'ACTIVE' WHERE id = ?", b0));
        // 禁止重复上游边
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO batch_relation (operation_id, parent_batch_id, child_batch_id, relation_type, created_at) VALUES (?, ?, ?, 'SPLIT', NOW(6))", rawOp, b0, out));
        // 外键：明细不能引用不存在的批次
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO batch_operation_item (operation_id, batch_id, role, quantity, unit_code, normalized_quantity, created_at, updated_at) "
                        + "VALUES (?, ?, 'INPUT', 1, 'kg', 1, NOW(6), NOW(6))", rawOp, Long.MAX_VALUE / 2));
        // V4 约束仍有效
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, status, idempotency_key, version, is_deleted, created_at, updated_at) "
                        + "VALUES (?, ?, 'PROCESS', NOW(), 'INVALID_STATUS', ?, 0, 0, NOW(), NOW())", receiverOrg.getId(), "OP-BAD-" + suffix, "bad-" + suffix));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO batch_operation_item (operation_id, role, quantity, normalized_quantity, unit_code, created_at) VALUES (?, 'INVALID_ROLE', 1, 1, 'kg', NOW())", rawOp));
        assertThat(longCol("SELECT consumed_by_operation_id FROM batch WHERE id = ?", b0)).isEqualTo(opId);
    }

    @Test
    @DisplayName("递归 CTE：51 条边的深链中新增尾 → 头边被识别为成环；非成环边返回 0")
    void recursiveCte_deepChainCycleDetection() {
        Long opId = insertRawSubmittedOperation("OP-CTE-" + suffix);
        List<Long> chain = new ArrayList<>();
        for (int i = 0; i < 52; i++) {
            chain.add(insertRawBatch("TB-CTE-" + suffix + "-" + i));
        }
        for (int i = 0; i < 51; i++) {
            jdbcTemplate.update("INSERT INTO batch_relation (operation_id, parent_batch_id, child_batch_id, relation_type, created_at) VALUES (?, ?, ?, 'SPLIT', NOW(6))",
                    opId, chain.get(i), chain.get(i + 1));
        }
        // 拟新增 B52 → B1：从子批次 B1 出发沿下游可达 B52，成环
        assertThat(relationMapper.checkCycleWithCte(chain.get(0), chain.get(51))).isPositive();
        // 拟新增 B1 → 新批次：不成环
        Long fresh = insertRawBatch("TB-CTE-" + suffix + "-fresh");
        assertThat(relationMapper.checkCycleWithCte(fresh, chain.get(0))).isZero();
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    private static List<Long> outputIds(JsonNode operationData) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : operationData.get("items")) {
            if ("OUTPUT".equals(item.get("role").asString())) {
                ids.add(item.get("batchId").asLong());
            }
        }
        return ids;
    }

    private void assertBatch(Long batchId, String flow, String risk, String quantity, String batchType) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT flow_status, risk_status, quantity, batch_type FROM batch WHERE id = ?", batchId);
        assertThat(row.get("flow_status")).as("flow of %s", batchId).isEqualTo(flow);
        assertThat(row.get("risk_status")).as("risk of %s", batchId).isEqualTo(risk);
        assertThat((BigDecimal) row.get("quantity")).as("quantity of %s", batchId).isEqualByComparingTo(quantity);
        assertThat(row.get("batch_type")).as("type of %s", batchId).isEqualTo(batchType);
    }

    private void assertRelation(Long opId, Long parent, Long child, String type) {
        assertThat(count("SELECT count(*) FROM batch_relation WHERE operation_id = ? AND parent_batch_id = ? AND child_batch_id = ? AND relation_type = ?",
                opId, parent, child, type)).isEqualTo(1);
    }

    private void assertItemsBalance(Long opId, String input, String output, String loss, String waste, String sample) {
        assertThat(sumRole(opId, "INPUT")).isEqualByComparingTo(input);
        assertThat(sumRole(opId, "OUTPUT")).isEqualByComparingTo(output);
        assertThat(sumRole(opId, "LOSS")).isEqualByComparingTo(loss);
        assertThat(sumRole(opId, "WASTE")).isEqualByComparingTo(waste);
        assertThat(sumRole(opId, "SAMPLE")).isEqualByComparingTo(sample);
    }

    private BigDecimal sumRole(Long opId, String role) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM batch_operation_item WHERE operation_id = ? AND role = ? AND is_deleted = 0",
                BigDecimal.class, opId, role);
    }

    private Long longCol(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private Long insertRawSubmittedOperation(String operationNo) {
        jdbcTemplate.update("INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, status, idempotency_key, version, is_deleted, created_at, updated_at) "
                + "VALUES (?, ?, 'SPLIT', NOW(6), 'SUBMITTED', ?, 1, 0, NOW(6), NOW(6))", receiverOrg.getId(), operationNo, "raw-" + operationNo);
        Long id = longCol("SELECT id FROM batch_operation WHERE operation_no = ?", operationNo);
        createdOperationIds.add(id);
        return id;
    }

    private Long insertRawBatch(String traceBatchNo) {
        Batch b = new Batch();
        b.setOrgId(receiverOrg.getId());
        b.setCreationOrgId(receiverOrg.getId());
        b.setProductId(testProduct.getId());
        b.setTraceBatchNo(traceBatchNo);
        b.setBatchType("PROCESSING");
        b.setQuantity(new BigDecimal("10.000"));
        b.setUnitCode("kg");
        b.setOriginType("DOMESTIC_CAPTURE");
        b.setOriginText("舟山渔场");
        b.setFlowStatus("ACTIVE");
        b.setRiskStatus("NORMAL");
        b.setVersion(0L);
        b.setIsDeleted(0);
        b.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        b.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchMapper.insert(b);
        createdBatchIds.add(b.getId());
        return b.getId();
    }

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
