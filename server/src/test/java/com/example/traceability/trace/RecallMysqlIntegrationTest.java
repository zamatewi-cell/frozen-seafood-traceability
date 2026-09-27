package com.example.traceability.trace;

import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.quality.dto.RecallCloseRequest;
import com.example.traceability.quality.dto.RecallCreateRequest;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import com.example.traceability.trace.dto.TransferCreateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Phase B PB5 模拟召回：反向追溯 + 正向圈定 + 未售 / 在途 / 已售事实 + RECALLED 终态 + 关闭（真实 MySQL 8.4，全部经真实 API）。
 * <p>
 * 覆盖契约 §4.2 / §4.3 / §13 步骤 8–14 / §14：只有当前责任组织的质量管理员对本组织持有的批次发起召回；FROZEN 可直接召回，
 * NORMAL 需要证据（最新检验不合格或已被上游召回圈定）；正向后续批次中本组织持有的同时召回、其他组织持有的通知持有方，由持有方
 * 以上游召回为证据发起自己的召回；已售罄批次保持 CLOSED 同时记录 RECALLED；召回关闭后批次仍保留 RECALLED；召回不改变数量、
 * 责任组织、流转状态与公开追溯码，不生成 TraceEvent；消费者页面显示模拟召回提示与受控的处置进展，模拟召回批次的公开码不可停用。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("模拟召回 MySQL 8.4 集成测试（PB5）")
class RecallMysqlIntegrationTest extends AbstractPhaseBMysqlIT {

    /** 来源 → 加工企业 PROCESS → SPLIT 得到的谱系：b0 → b1 → {b2, b3}（全部由加工企业持有）。 */
    private record Chain(Long b0, Long b1, Long b2, Long b3) {
    }

    private Chain processorChain(String tag) throws Exception {
        Long b0 = processorHeldBatch("EXT-RC-" + tag + "-" + suffix, new BigDecimal("1000"));
        JsonNode process = createAndSubmitOperation("PROCESS", List.of(opInput(b0, "1000"), opOutput("960"), opOther("LOSS", "40")));
        Long b1 = outputs(process).get(0);
        JsonNode split = createAndSubmitOperation("SPLIT", List.of(opInput(b1, "960"), opOutput("600"), opOutput("360")));
        List<Long> parts = outputs(split);
        return new Chain(b0, b1, parts.get(0), parts.get(1));
    }

    private static List<Long> outputs(JsonNode operation) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : operation.get("items")) {
            if ("OUTPUT".equals(item.get("role").asString())) {
                ids.add(item.get("batchId").asLong());
            }
        }
        return ids.stream().sorted().toList();
    }

    /** 零售组织：OPERATOR / QUALITY_MANAGER 会话与启用门店。 */
    private record Retailer(Organization org, MockHttpSession operator, MockHttpSession qm, Site store) {
    }

    private Retailer retailer() throws Exception {
        Organization org = createOrg("ORG_RT_" + suffix, "零售企业-" + suffix, "RETAILER");
        return new Retailer(org, newSession(org, "rt_op", "OPERATOR", "OWN_ORG"), newSession(org, "rt_qm", QM_ROLE, "OWN_ORG"),
                createSite(org.getId(), "RT-STORE-" + suffix, "零售门店-" + suffix, "STORE"));
    }

    /** 加工企业把自己持有的批次经 Transfer + Shipment 交给零售企业，零售企业接受。 */
    private void shipToRetailer(Long batchId, BigDecimal qty, Retailer r) throws Exception {
        Long transferId = expect(postJson(receiverSession, "/api/v1/transfers", key("idem-trf-c"),
                new TransferCreateRequest(batchId, r.org().getId())), 201).get("data").get("id").asLong();
        createdTransferIds.add(transferId);
        Long shipmentId = expect(createShipmentRequest(receiverSession, key("idem-shp-c"), carrierOrg.getId(), receiverSite.getId(),
                r.store().getId()), 201).get("data").get("id").asLong();
        createdShipmentIds.add(shipmentId);
        expect(bindRequest(receiverSession, shipmentId, transferId, transferVersion(transferId)), 200);
        expect(submitRequest(receiverSession, transferId, transferVersion(transferId)), 200);
        dispatch(shipmentId);
        arrive(shipmentId);
        expect(postJson(r.operator(), "/api/v1/transfers/" + transferId + "/accept", key("idem-trf-acc"),
                new TransferAcceptRequest(qty, "kg", OffsetDateTime.now(ZoneOffset.UTC), null, transferVersion(transferId))), 200);
    }

    private MockHttpServletRequestBuilder recallReq(MockHttpSession session, List<Long> batchIds, String reason, Long alertId, String idemKey)
            throws Exception {
        return postJson(session, "/api/v1/recalls", idemKey, new RecallCreateRequest(batchIds, reason, alertId));
    }

    private MockHttpServletRequestBuilder closeReq(MockHttpSession session, Long recallId, String disposition, String summary, String idemKey)
            throws Exception {
        return postJson(session, "/api/v1/recalls/" + recallId + "/close", idemKey, new RecallCloseRequest(disposition, summary));
    }

    private JsonNode scopeRow(JsonNode recall, Long batchId) {
        for (JsonNode row : recall.get("scope")) {
            if (row.get("batchId").asLong() == batchId) {
                return row;
            }
        }
        throw new AssertionError("batch " + batchId + " not in recall scope " + recall.get("scope"));
    }

    private String publicIdOf(Long batchId) {
        return jdbcTemplate.queryForObject("SELECT public_id FROM public_trace_code WHERE batch_id = ?", String.class, batchId);
    }

    private JsonNode publicTrace(String publicId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/public/v1/public/traces/" + publicId)).andReturn().getResponse()
                .getContentAsString()).get("data");
    }

    // =========================================================================
    // 反向追溯 + 正向圈定 + 跨组织持有方召回
    // =========================================================================

    @Test
    @DisplayName("证据充分的紧急召回：加工企业召回已关闭的 b1，反向圈定 b0、正向召回本组织的 b2 并通知零售持有的 b3；零售企业以上游召回为证据召回已售罄的 b3（CLOSED + RECALLED），消费者看到模拟召回提示与处置进展")
    void lineageScope_crossOrgHolderAndSoldOut() throws Exception {
        Chain c = processorChain("LIN");
        Retailer r = retailer();
        shipToRetailer(c.b3(), new BigDecimal("360"), r);
        expect(activateCodeRequest(r.operator(), c.b3(), key("idem-code")), 200);
        expect(saleRequest(r.operator(), c.b3(), r.store().getId(), "360", key("idem-sale")), 201);
        assertThat(batchRow(c.b3())).containsEntry("flow_status", "CLOSED").containsEntry("risk_status", "NORMAL");
        int eventsBefore = count("SELECT count(*) FROM trace_event");
        Map<String, Object> b2Before = batchRow(c.b2());

        MockHttpSession processorQm = newSession(receiverOrg, "prc_qm", QM_ROLE, "OWN_ORG");
        // NORMAL 且没有证据：拒绝
        expectProblem(recallReq(processorQm, List.of(c.b1()), "来料问题", null, key("idem-rc")), 409, "RECALL_EVIDENCE_REQUIRED");
        inspect(processorQm, c.b1(), "PRC-FAIL-1", "FAIL", null);

        JsonNode recall = expect(recallReq(processorQm, List.of(c.b1()), "加工批次检出微生物超标（教学演练）", null, key("idem-rc")), 201)
                .get("data");
        assertThat(recall.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(recall.get("recallNo").asString()).startsWith("RCL-");
        JsonNode seed = scopeRow(recall, c.b1());
        assertThat(seed.get("scopeRole").asString()).isEqualTo("SEED");
        assertThat(seed.get("action").asString()).isEqualTo("RECALLED");
        assertThat(seed.get("flowStatus").asString()).isEqualTo("CLOSED");
        assertThat(seed.get("currentRiskStatus").asString()).isEqualTo("RECALLED");
        JsonNode own = scopeRow(recall, c.b2());
        assertThat(own.get("scopeRole").asString()).isEqualTo("DESCENDANT");
        assertThat(own.get("depth").asInt()).isEqualTo(1);
        assertThat(own.get("action").asString()).isEqualTo("RECALLED");
        assertThat(own.get("remainingQuantity").decimalValue()).isEqualByComparingTo("600");
        JsonNode notified = scopeRow(recall, c.b3());
        assertThat(notified.get("action").asString()).isEqualTo("NOTIFY_HOLDER");
        assertThat(notified.get("holderOrgId").asLong()).isEqualTo(r.org().getId());
        assertThat(notified.get("soldQuantity").decimalValue()).isEqualByComparingTo("360");
        assertThat(notified.get("remainingQuantity").decimalValue()).isEqualByComparingTo("0");
        assertThat(notified.get("publicCodeActive").asBoolean()).isTrue();
        JsonNode ancestor = scopeRow(recall, c.b0());
        assertThat(ancestor.get("scopeRole").asString()).isEqualTo("ANCESTOR");
        assertThat(ancestor.get("depth").asInt()).isEqualTo(-1);
        assertThat(ancestor.get("action").asString()).isEqualTo("TRACE_ONLY");
        assertThat(recall.get("summary").get("recalledCount").asInt()).isEqualTo(2);
        assertThat(recall.get("summary").get("notifiedCount").asInt()).isEqualTo(1);
        assertThat(recall.get("summary").get("soldQuantity").decimalValue()).isEqualByComparingTo("360");

        // 风险终态：b1 CLOSED + RECALLED，b2 ACTIVE + RECALLED；b0（上游）与 b3（他组织）风险不变；数量 / 组织 / 流转不变，无事件
        assertThat(batchRow(c.b1())).containsEntry("flow_status", "CLOSED").containsEntry("risk_status", "RECALLED");
        Map<String, Object> b2After = batchRow(c.b2());
        assertThat(b2After.get("risk_status")).isEqualTo("RECALLED");
        assertThat(b2After.get("org_id")).isEqualTo(b2Before.get("org_id"));
        assertThat((BigDecimal) b2After.get("quantity")).isEqualByComparingTo((BigDecimal) b2Before.get("quantity"));
        assertThat(riskStatus(c.b0())).isEqualTo("NORMAL");
        assertThat(riskStatus(c.b3())).isEqualTo("NORMAL");
        assertThat(count("SELECT count(*) FROM trace_event")).isEqualTo(eventsBefore);
        assertThat(count("SELECT count(*) FROM batch_risk_transition WHERE source_type = 'RECALL' AND source_recall_id = ? "
                + "AND to_status = 'RECALLED' AND actor_user_id IS NOT NULL", recall.get("id").asLong())).isEqualTo(2);
        assertLedgerConsistent(c.b1());
        assertLedgerConsistent(c.b2());
        // RECALLED 终态：人工冻结 / 解除与再次召回都被拒绝；已召回批次不能销售 / 加工
        expectProblem(freezeRequest(processorQm, c.b2(), "再冻结", key("idem-risk-f")), 409, "INVALID_STATE_TRANSITION");
        expectProblem(recallReq(processorQm, List.of(c.b2()), "再次召回", null, key("idem-rc")), 409, "INVALID_STATE_TRANSITION");

        // 零售持有方：只看到本组织持有的范围行，看不到内部处置总结；以上游召回为证据召回已售罄的 b3
        JsonNode holderView = expect(getReq(r.qm(), "/api/v1/recalls/" + recall.get("id").asLong()), 200).get("data");
        assertThat(holderView.get("scope")).hasSize(1);
        assertThat(holderView.get("scope").get(0).get("batchId").asLong()).isEqualTo(c.b3());
        assertThat(expect(getReq(r.qm(), "/api/v1/recalls"), 200).get("data")).hasSize(1);
        JsonNode retailRecall = expect(recallReq(r.qm(), List.of(c.b3()), "上游加工批次模拟召回，零售批次同步召回", null, key("idem-rc")), 201)
                .get("data");
        assertThat(scopeRow(retailRecall, c.b3()).get("action").asString()).isEqualTo("RECALLED");
        assertThat(scopeRow(retailRecall, c.b1()).get("scopeRole").asString()).isEqualTo("ANCESTOR");
        assertThat(batchRow(c.b3())).containsEntry("flow_status", "CLOSED").containsEntry("risk_status", "RECALLED");

        // 消费者：CLOSED + RECALLED，模拟召回提示与进行中的处置进展；不含召回编号 / 原因 / 组织
        String publicId = publicIdOf(c.b3());
        JsonNode trace = publicTrace(publicId);
        assertThat(trace.get("flowStatus").asString()).isEqualTo("CLOSED");
        assertThat(trace.get("riskStatus").asString()).isEqualTo("RECALLED");
        assertThat(trace.get("recallNotice").asString()).contains("模拟召回");
        assertThat(trace.get("recallDisposition").get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(trace.get("recallDisposition").has("closedDate")).isFalse();
        String body = trace.toString();
        assertThat(body).doesNotContain(retailRecall.get("recallNo").asString()).doesNotContain("上游加工批次")
                .doesNotContain(r.org().getName()).doesNotContain("recallId");
        // 模拟召回批次的公开码不可停用
        expectProblem(postJson(r.operator(), "/api/v1/batches/" + c.b3() + "/public-trace-code/disable", key("idem-code-d"), null), 409,
                "PUBLIC_TRACE_CODE_RECALL_LOCKED");

        // 关闭：受控公开处置结论；批次仍保留 RECALLED；消费者看到已完成的固定文案与日期
        expectProblem(closeReq(r.qm(), retailRecall.get("id").asLong(), "BURNED", "已处置", key("idem-rcc")), 400, "INVALID_REQUEST");
        expectProblem(closeReq(processorQm, retailRecall.get("id").asLong(), "DESTROYED", "已处置", key("idem-rcc")), 403, "ORG_SCOPE_DENIED");
        String closeKey = key("idem-rcc");
        JsonNode closed = expect(closeReq(r.qm(), retailRecall.get("id").asLong(), "DESTROYED", "门店已下架并按演练流程销毁 360 kg",
                closeKey), 200).get("data");
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("publicDisposition").asString()).isEqualTo("DESTROYED");
        assertThat(expect(closeReq(r.qm(), retailRecall.get("id").asLong(), "DESTROYED", "门店已下架并按演练流程销毁 360 kg", closeKey), 200)
                .get("data").get("version").asLong()).isEqualTo(closed.get("version").asLong());
        expectProblem(closeReq(r.qm(), retailRecall.get("id").asLong(), "RETURNED", "改口", key("idem-rcc")), 409, "INVALID_STATE_TRANSITION");
        assertThat(riskStatus(c.b3())).isEqualTo("RECALLED");
        JsonNode afterClose = publicTrace(publicId);
        assertThat(afterClose.get("riskStatus").asString()).isEqualTo("RECALLED");
        assertThat(afterClose.get("recallDisposition").get("status").asString()).isEqualTo("CLOSED");
        assertThat(afterClose.get("recallDisposition").get("label").asString()).contains("销毁处置").contains("非真实召回结论");
        assertThat(afterClose.get("recallDisposition").get("closedDate").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(afterClose.toString()).doesNotContain("门店已下架");
        assertThat(count("SELECT count(*) FROM audit_log WHERE object_type = 'RECALL' AND action IN ('RECALL_START', 'RECALL_CLOSE')"
                + " AND object_id IN (?, ?)", recall.get("id").asLong(), retailRecall.get("id").asLong())).isEqualTo(3);
    }

    // =========================================================================
    // 告警路径：冻结批次 → 召回 → 隔离交接拒收 → 告警处置结论
    // =========================================================================

    @Test
    @DisplayName("告警路径：冻结批次直接召回（引用来源告警），范围快照记录隔离交接与运输状态；接收方不能接受、可以拒收；召回计为告警处置，告警可形成处置结论")
    void alertPath_recallFrozenBatch() throws Exception {
        Manifest m = deliveredAlertManifest("RC-ALERT", "500", "360");
        Long alertId = onlyAlertId(m.shipmentId());
        quarantine(m.transfer(0), "500");
        acknowledge(alertId);

        expectProblem(recallReq(senderQm(), List.of(m.batch(0)), "告警批次召回", 999999999L, key("idem-rc")), 404, "RESOURCE_NOT_FOUND");
        Long unrelated = createAndSubmitActiveBatch(senderSession, "EXT-RC-UNREL-" + suffix, new BigDecimal("10"));
        freeze(senderQm(), unrelated, "无关冻结");
        expectProblem(recallReq(senderQm(), List.of(unrelated), "告警批次召回", alertId, key("idem-rc")), 422, "ALERT_BATCH_MISMATCH");

        JsonNode recall = expect(recallReq(senderQm(), List.of(m.batch(0)), "在途持续超温且检验不合格，启动模拟召回", alertId,
                key("idem-rc")), 201).get("data");
        assertThat(recall.get("sourceAlertId").asLong()).isEqualTo(alertId);
        JsonNode seed = scopeRow(recall, m.batch(0));
        assertThat(seed.get("riskStatusBefore").asString()).isEqualTo("FROZEN");
        assertThat(seed.get("openTransferStatus").asString()).isEqualTo("QUARANTINED");
        assertThat(seed.get("shipmentStatus").asString()).isEqualTo("DELIVERED");
        assertThat(seed.get("openTransferId").asLong()).isEqualTo(m.transfer(0));
        assertThat(riskStatus(m.batch(0))).isEqualTo("RECALLED");
        assertThat(transferStatus(m.transfer(0))).as("recall never changes transfers").isEqualTo("QUARANTINED");

        expectProblem(acceptRequest(receiverSession, m.transfer(0), new BigDecimal("500"), key("idem-acc")), 409, "BATCH_FLOW_BLOCKED");
        expect(rejectRequest(receiverSession, m.transfer(0), key("idem-rej")), 200);
        // 告警：批次 0 已召回（计为处置），批次 1 依据 PASS 放行后即可形成处置结论
        expectProblem(resolveReq(senderQm(), alertId, "处置", key("idem-res")), 409, "ALERT_BATCHES_PENDING");
        inspect(senderQm(), m.batch(1), "SND-PASS", "PASS", alertId);
        expect(releaseReq(senderQm(), alertId, m.batch(1), null, key("idem-rel")), 200);
        JsonNode resolved = expect(resolveReq(senderQm(), alertId, "批次 0 模拟召回，批次 1 复检合格放行", key("idem-res")), 200).get("data");
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        // 召回批次不能再经告警放行
        assertLedgerConsistent(m.batch(0));
    }

    // =========================================================================
    // 权限、校验与幂等
    // =========================================================================

    @Test
    @DisplayName("权限与校验：操作员 / 他组织 / 平台被拒；请求形状校验；同键同语义重放、同键不同语义 409；外部组织查询 403")
    void authorizationValidationAndIdempotency() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "EXT-RC-AUTH-" + suffix, new BigDecimal("100"));
        freeze(senderQm(), batchId, "抽检异常");

        expectProblem(recallReq(senderSession, List.of(batchId), "召回", null, key("idem-rc")), 403, "ACCESS_DENIED");
        expectProblem(recallReq(receiverQm(), List.of(batchId), "召回", null, key("idem-rc")), 403, "ORG_SCOPE_DENIED");
        MockHttpSession admin = newSession(senderOrg, "sys_adm", "SYSTEM_ADMIN", "PLATFORM");
        expectProblem(recallReq(admin, List.of(batchId), "召回", null, key("idem-rc")), 403, "ADMIN_RESTRICTED");
        expectProblem(recallReq(senderQm(), List.of(), "召回", null, key("idem-rc")), 400, "INVALID_REQUEST");
        expectProblem(recallReq(senderQm(), List.of(batchId), "  ", null, key("idem-rc")), 400, "INVALID_REQUEST");
        expectProblem(recallReq(senderQm(), List.of(batchId), "召回", null, null), 400, "INVALID_REQUEST");
        expectProblem(postJson(senderQm(), "/api/v1/recalls", key("idem-rc"), Map.of("batchIds", List.of(batchId), "reason", "召回",
                "status", "CLOSED")), 400, "INVALID_REQUEST");
        expectProblem(recallReq(senderQm(), List.of(999999999L), "召回", null, key("idem-rc")), 404, "RESOURCE_NOT_FOUND");
        assertThat(riskStatus(batchId)).isEqualTo("FROZEN");

        String k = key("idem-rc");
        JsonNode first = expect(recallReq(senderQm(), List.of(batchId, batchId), "抽检异常，启动模拟召回", null, k), 201).get("data");
        JsonNode replay = expect(recallReq(senderQm(), List.of(batchId), "抽检异常，启动模拟召回", null, k), 201).get("data");
        assertThat(replay.get("id").asLong()).isEqualTo(first.get("id").asLong());
        expectProblem(recallReq(senderQm(), List.of(batchId), "另一原因", null, k), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(count("SELECT count(*) FROM recall WHERE owner_org_id = ?", senderOrg.getId())).isEqualTo(1);
        assertThat(first.get("resultSummary")).isNull();

        MockHttpSession outsider = newSession(createOrg("ORG_X_" + suffix, "无关企业-" + suffix, "PROCESSOR"), "out_qm", QM_ROLE, "OWN_ORG");
        expectProblem(getReq(outsider, "/api/v1/recalls/" + first.get("id").asLong()), 403, "ORG_SCOPE_DENIED");
        assertThat(expect(getReq(outsider, "/api/v1/recalls"), 200).get("data")).isEmpty();
        expectProblem(getReq(senderSession, "/api/v1/recalls/999999999"), 404, "RESOURCE_NOT_FOUND");
        assertThat(expect(getReq(senderSession, "/api/v1/recalls"), 200).get("data").get(0).has("scope")).isFalse();
    }
}
