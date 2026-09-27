package com.example.traceability.trace;

import com.example.traceability.identity.domain.Site;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B PB4 隔离收货 → 检验证据 → 质量结论放行 / 拒收 → 告警处置结论（真实 MySQL 8.4，全部经真实 API）。
 * <p>
 * 覆盖契约 §10.2 步骤 5–7、§10.3、§13 步骤 5–11 与 §14：冻结批次到达后接收方不能接受，只能隔离收货或拒收；隔离期间批次责任组织
 * 仍为发送方，接收方不能加工 / 销售 / 再交接，只能提交检验证据；发货方质量管理员确认异常后依据关联告警的最新检验结论放行
 * （PASS 才能放行，FAIL 拒绝，缺报告拒绝），人工解除冻结不能绕过告警；放行后接收方接受隔离交接（沿用隔离登记的实收事实），
 * 或在任何风险状态下拒收；全部批次处置后形成告警处置结论。隔离、检验、放行与处置结论都不生成 TraceEvent。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("隔离收货、检验报告与告警质量处置 MySQL 8.4 集成测试（PB4）")
class QuarantineInspectionMysqlIntegrationTest extends AbstractPhaseBMysqlIT {

    private int events(Long batchId) {
        return count("SELECT count(*) FROM trace_event WHERE batch_id = ?", batchId);
    }

    // =========================================================================
    // 隔离收货
    // =========================================================================

    @Test
    @DisplayName("冻结批次到达后接收方不能接受；隔离收货登记实收数量与隔离场所，责任组织仍为发送方，隔离期间批次不能再交接 / 加工 / 销售，不生成事件")
    void quarantine_recordsReceiptAndKeepsSenderResponsible() throws Exception {
        Manifest m = deliveredAlertManifest("QUAR", "500");
        Long transferId = m.transfer(0);
        Long batchId = m.batch(0);
        int eventsBefore = events(batchId);
        Map<String, Object> batchBefore = batchRow(batchId);

        expectProblem(acceptRequest(receiverSession, transferId, new BigDecimal("500"), key("idem-acc")), 409, "BATCH_FLOW_BLOCKED");

        // 校验：差异原因、隔离场所（他组织 / 停用）、运输未到达、非接收方
        expectProblem(quarantineReq(receiverSession, transferId, "480", null, receiverSite.getId(), "隔离", key("idem-q")), 422,
                "DIFFERENCE_REASON_REQUIRED");
        expectProblem(quarantineReq(receiverSession, transferId, "500", null, senderSite.getId(), "隔离", key("idem-q")), 422,
                "QUARANTINE_SITE_INVALID");
        Site inactive = createSite(receiverOrg.getId(), "RCV-OFF-" + suffix, "停用隔离区-" + suffix, "COLD_STORE");
        jdbcTemplate.update("UPDATE site SET status = 'INACTIVE' WHERE id = ?", inactive.getId());
        expectProblem(quarantineReq(receiverSession, transferId, "500", null, inactive.getId(), "隔离", key("idem-q")), 422,
                "QUARANTINE_SITE_INVALID");
        expectProblem(quarantineReq(senderSession, transferId, "500", null, receiverSite.getId(), "隔离", key("idem-q")), 403,
                "ORG_SCOPE_DENIED");
        expectProblem(quarantineReq(receiverSession, transferId, "500", null, receiverSite.getId(), "   ", key("idem-q")), 400,
                "INVALID_REQUEST");
        assertThat(transferStatus(transferId)).isEqualTo("PENDING");

        String k = key("idem-q");
        long pendingVersion = transferVersion(transferId);
        JsonNode q = expect(quarantineReq(receiverSession, transferId, "498.5", "运输途中少量解冻滴水", receiverSite.getId(),
                "到货随附持续超温告警，隔离待检", k, pendingVersion), 200).get("data");
        assertThat(q.get("status").asString()).isEqualTo("QUARANTINED");
        assertThat(q.get("receivedQuantity").decimalValue()).isEqualByComparingTo("498.5");
        assertThat(q.get("differenceReason").asString()).isEqualTo("运输途中少量解冻滴水");
        assertThat(q.get("quarantineSiteId").asLong()).isEqualTo(receiverSite.getId());
        assertThat(q.get("quarantineReason").asString()).isEqualTo("到货随附持续超温告警，隔离待检");
        assertThat(q.get("batchRiskStatus").asString()).isEqualTo("FROZEN");
        assertThat(q.has("decisionRecordedAt")).isFalse();

        // 幂等：同键同语义重放、同键不同语义冲突；再次隔离 409
        assertThat(expect(quarantineReq(receiverSession, transferId, "498.5", "运输途中少量解冻滴水", receiverSite.getId(),
                "到货随附持续超温告警，隔离待检", k, pendingVersion), 200).get("data").get("version").asLong()).isEqualTo(q.get("version").asLong());
        expectProblem(quarantineReq(receiverSession, transferId, "500", null, receiverSite.getId(), "另一原因", k), 409, "IDEMPOTENCY_CONFLICT");
        expectProblem(quarantineReq(receiverSession, transferId, "500", null, receiverSite.getId(), "再次隔离", key("idem-q")), 409,
                "INVALID_STATE_TRANSITION");

        // 隔离不转移责任、不改变数量 / 风险 / 流转状态，不生成事件；隔离交接仍是未结束交接
        assertThat(batchRow(batchId)).isEqualTo(batchBefore);
        assertThat(events(batchId)).isEqualTo(eventsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT open_batch_id FROM transfer WHERE id = ?", Long.class, transferId)).isEqualTo(batchId);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'QUARANTINE' AND object_type = 'TRANSFER' AND object_id = ?", transferId))
                .isEqualTo(1);
        // 接收方不是责任组织：不能销售 / 交接该批次
        expectProblem(createTransferRequest(receiverSession, batchId, senderOrg.getId(), key("idem-trf")), 403, "ORG_SCOPE_DENIED");

        // 接收方可通过交接列表按 QUARANTINED 过滤
        JsonNode list = expect(getReq(receiverSession, "/api/v1/transfers?direction=RECEIVED&status=QUARANTINED"), 200).get("data");
        assertThat(list).anySatisfy(t -> assertThat(t.get("id").asLong()).isEqualTo(transferId));
    }

    // =========================================================================
    // 检验报告
    // =========================================================================

    @Test
    @DisplayName("检验报告：隔离接收方与当前责任组织的质量管理员可提交（关联告警），他组织 / 操作员被拒；结论只是证据，不改变任何状态；查询按组织过滤")
    void inspectionReports_submitterRolesAndVisibility() throws Exception {
        Manifest m = deliveredAlertManifest("INSP", "500");
        Long batchId = m.batch(0);
        Long alertId = onlyAlertId(m.shipmentId());
        Map<String, Object> batchBefore = batchRow(batchId);

        // 尚未隔离时接收方不是提交身份
        expectProblem(inspectionReq(receiverQm(), batchId, "RCV-001", "PASS", alertId, key("idem-insp")), 403, "ORG_SCOPE_DENIED");
        quarantine(m.transfer(0), "500");

        JsonNode rcv = inspect(receiverQm(), batchId, "RCV-001", "PASS", alertId);
        assertThat(rcv.get("submitterRole").asString()).isEqualTo("QUARANTINE_RECEIVER");
        assertThat(rcv.get("transferId").asLong()).isEqualTo(m.transfer(0));
        assertThat(rcv.get("alertId").asLong()).isEqualTo(alertId);
        JsonNode snd = inspect(senderQm(), batchId, "SND-001", "FAIL", alertId);
        assertThat(snd.get("submitterRole").asString()).isEqualTo("CURRENT_ORG");
        assertThat(snd.has("transferId")).isFalse();

        expectProblem(inspectionReq(receiverSession, batchId, "RCV-002", "PASS", alertId, key("idem-insp")), 403, "ACCESS_DENIED");
        MockHttpSession outsider = newSession(createOrg("ORG_X_" + suffix, "无关企业-" + suffix, "PROCESSOR"), "out_qm", QM_ROLE, "OWN_ORG");
        expectProblem(inspectionReq(outsider, batchId, "OUT-001", "PASS", null, key("idem-insp")), 403, "ORG_SCOPE_DENIED");
        expectProblem(inspectionReq(senderQm(), batchId, "SND-001", "PASS", null, key("idem-insp")), 409, "INSPECTION_REPORT_NO_DUPLICATE");
        expectProblem(inspectionReq(senderQm(), batchId, "SND-002", "PENDING", null, key("idem-insp")), 400, "INVALID_REQUEST");
        expectProblem(inspectionReq(senderQm(), batchId, "SND-003", "PASS", 999999999L, key("idem-insp")), 422, "ALERT_BATCH_MISMATCH");
        expectProblem(postJson(senderQm(), "/api/v1/batches/" + batchId + "/inspection-reports", key("idem-insp"),
                Map.of("reportNo", "SND-004", "institutionName", "机构", "inspectedAt", "2026-09-25T00:00:00Z", "itemsSummary", "x",
                        "conclusion", "PASS", "dataSource", "MANUAL", "institutionVerified", true)), 400, "INVALID_REQUEST");
        String k = key("idem-insp");
        JsonNode first = expect(inspectionReq(senderQm(), batchId, "SND-005", "PASS", null, k), 201).get("data");
        assertThat(first.has("alertId")).isFalse();
        assertThat(expect(inspectionReq(senderQm(), batchId, "SND-005", "PASS", null, k), 201).get("data").get("id").asLong())
                .isEqualTo(first.get("id").asLong());

        // 报告只是证据
        assertThat(batchRow(batchId)).isEqualTo(batchBefore);
        assertThat(riskStatus(batchId)).isEqualTo("FROZEN");
        assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ? AND event_type NOT IN ('SOURCE', 'TRANSPORT', 'ARRIVAL')", batchId))
                .isZero();

        // 查询：当前责任组织看全部，隔离接收方只看本组织，他组织 403
        assertThat(expect(getReq(senderSession, "/api/v1/batches/" + batchId + "/inspection-reports"), 200).get("data")).hasSize(3);
        JsonNode receiverView = expect(getReq(receiverSession, "/api/v1/batches/" + batchId + "/inspection-reports"), 200).get("data");
        assertThat(receiverView).hasSize(1);
        assertThat(receiverView.get(0).get("reportNo").asString()).isEqualTo("RCV-001");
        expectProblem(getReq(outsider, "/api/v1/batches/" + batchId + "/inspection-reports"), 403, "ORG_SCOPE_DENIED");

        // 告警详情显示关联本告警的最新结论与报告数
        JsonNode detail = alertDetail(senderSession, alertId);
        assertThat(detail.get("batches").get(0).get("latestInspectionConclusion").asString()).isEqualTo("FAIL");
        assertThat(detail.get("batches").get(0).get("inspectionCount").asInt()).isEqualTo(2);
    }

    // =========================================================================
    // 质量结论放行、接受 / 拒收与处置结论
    // =========================================================================

    @Test
    @DisplayName("完整合格路径：确认 → 缺报告 / FAIL 拒绝放行 → 人工解除被阻断 → 最新 PASS 放行（ALERT 来源、有操作人）→ 接收方接受隔离交接（沿用实收事实）→ 形成处置结论")
    void qualifiedPath_releaseThenAcceptThenResolve() throws Exception {
        Manifest m = deliveredAlertManifest("PASS", "500");
        Long batchId = m.batch(0);
        Long transferId = m.transfer(0);
        Long alertId = onlyAlertId(m.shipmentId());
        quarantine(transferId, "500");

        expectProblem(releaseReq(senderQm(), alertId, batchId, null, key("idem-rel")), 409, "ALERT_NOT_ACKNOWLEDGED");
        acknowledge(alertId);
        expectProblem(releaseReq(senderQm(), alertId, batchId, null, key("idem-rel")), 409, "INSPECTION_REQUIRED");
        inspect(senderQm(), batchId, "SND-PASS-0", "PASS", null);   // 不关联告警的报告不作为放行依据
        expectProblem(releaseReq(senderQm(), alertId, batchId, null, key("idem-rel")), 409, "INSPECTION_REQUIRED");
        inspect(receiverQm(), batchId, "RCV-FAIL-1", "FAIL", alertId);
        expectProblem(releaseReq(senderQm(), alertId, batchId, null, key("idem-rel")), 409, "INSPECTION_NOT_PASSED");
        expectProblem(releaseReq(receiverQm(), alertId, batchId, null, key("idem-rel")), 403, "ORG_SCOPE_DENIED");
        expectProblem(releaseReq(senderSession, alertId, batchId, null, key("idem-rel")), 403, "ACCESS_DENIED");
        // 人工解除冻结不能绕过告警质量结论
        expectProblem(releaseRequest(senderQm(), batchId, "绕过告警直接解除", key("idem-risk-r")), 409, "ALERT_DECISION_REQUIRED");
        expectProblem(resolveReq(senderQm(), alertId, "尚有批次未处置", key("idem-res")), 409, "ALERT_BATCHES_PENDING");
        assertThat(riskStatus(batchId)).isEqualTo("FROZEN");

        JsonNode pass = inspect(senderQm(), batchId, "SND-PASS-2", "PASS", alertId);
        String k = key("idem-rel");
        JsonNode released = expect(releaseReq(senderQm(), alertId, batchId, "复检合格，依据检验报告放行", k), 200).get("data");
        assertThat(riskStatus(batchId)).isEqualTo("NORMAL");
        JsonNode affected = released.get("batches").get(0);
        assertThat(affected.get("released").asBoolean()).isTrue();
        assertThat(affected.get("currentRiskStatus").asString()).isEqualTo("NORMAL");
        JsonNode releaseAction = released.get("actions").get(1);
        assertThat(releaseAction.get("action").asString()).isEqualTo("RELEASE_BATCH");
        assertThat(releaseAction.get("batchId").asLong()).isEqualTo(batchId);
        assertThat(releaseAction.get("inspectionReportId").asLong()).isEqualTo(pass.get("id").asLong());
        Map<String, Object> ledger = jdbcTemplate.queryForMap(
                "SELECT * FROM batch_risk_transition WHERE batch_id = ? ORDER BY id DESC LIMIT 1", batchId);
        assertThat(ledger).containsEntry("from_status", "FROZEN").containsEntry("to_status", "NORMAL").containsEntry("source_type", "ALERT")
                .containsEntry("reason", "复检合格，依据检验报告放行")
                .containsEntry("idempotency_key", "SYS:ALERT-RELEASE:" + alertId + ":BATCH:" + batchId);
        assertThat(((Number) ledger.get("source_alert_id")).longValue()).isEqualTo(alertId);
        assertThat(ledger.get("actor_user_id")).isNotNull();
        assertThat(releaseAction.get("riskTransitionId").asLong()).isEqualTo(((Number) ledger.get("id")).longValue());
        assertLedgerConsistent(batchId);
        // 幂等重放；再次放行 409；放行后交接仍为 QUARANTINED
        assertThat(expect(releaseReq(senderQm(), alertId, batchId, "复检合格，依据检验报告放行", k), 200).get("data").get("actions"))
                .hasSize(2);
        expectProblem(releaseReq(senderQm(), alertId, batchId, null, key("idem-rel")), 409, "INVALID_STATE_TRANSITION");
        assertThat(transferStatus(transferId)).isEqualTo("QUARANTINED");
        assertThat(batchOrgId(batchId)).isEqualTo(senderOrg.getId());

        // 接收方接受隔离交接：实收数量必须与隔离登记一致，沿用隔离时的到货时间与实收事实，责任组织转为接收方
        Map<String, Object> quarantined = jdbcTemplate.queryForMap("SELECT received_at, received_quantity, quarantine_site_id FROM transfer WHERE id = ?", transferId);
        expectProblem(acceptRequest(receiverSession, transferId, new BigDecimal("499"), key("idem-acc")), 422, "QUARANTINE_RECEIPT_MISMATCH");
        JsonNode accepted = expect(acceptRequest(receiverSession, transferId, new BigDecimal("500"), key("idem-acc")), 200).get("data");
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(batchOrgId(batchId)).isEqualTo(receiverOrg.getId());
        assertThat(jdbcTemplate.queryForMap("SELECT received_at, received_quantity, quarantine_site_id FROM transfer WHERE id = ?", transferId))
                .isEqualTo(quarantined);
        assertThat(jdbcTemplate.queryForObject("SELECT open_batch_id FROM transfer WHERE id = ?", Long.class, transferId)).isNull();

        // 形成处置结论
        expectProblem(resolveReq(senderQm(), alertId, "  ", key("idem-res")), 400, "INVALID_REQUEST");
        JsonNode resolved = expect(resolveReq(senderQm(), alertId, "复检合格，受影响批次已放行并由接收方接受", key("idem-res")), 200).get("data");
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolution").asString()).isEqualTo("复检合格，受影响批次已放行并由接收方接受");
        assertThat(count("SELECT count(*) FROM audit_log WHERE object_type = 'ALERT' AND object_id = ? AND action IN "
                + "('ALERT_TRIGGER', 'ALERT_ACKNOWLEDGE', 'ALERT_RELEASE_BATCH', 'ALERT_RESOLVE')", alertId)).isEqualTo(4);
        // 处置结论之后不能再关联检验报告（接收方此时已是当前责任组织）；再次处置 409
        expectProblem(inspectionReq(receiverQm(), batchId, "LATE-1", "PASS", alertId, key("idem-insp")), 409, "INVALID_STATE_TRANSITION");
        expectProblem(resolveReq(senderQm(), alertId, "再次处置", key("idem-res")), 409, "INVALID_STATE_TRANSITION");
        // 告警、检验与放行从不生成追溯事件（只有来源 / 运输 / 到达事件）
        assertThat(count("SELECT count(*) FROM trace_event WHERE batch_id = ? AND event_type NOT IN ('SOURCE', 'TRANSPORT', 'ARRIVAL')", batchId))
                .isZero();
    }

    @Test
    @DisplayName("不合格路径：隔离后在批次仍冻结时拒收，批次留在发送方且仍冻结，保留实收事实；拒收后批次不再有未结束交接")
    void failedPath_rejectFromQuarantineWhileFrozen() throws Exception {
        Manifest m = deliveredAlertManifest("REJ", "500", "360");
        Long alertId = onlyAlertId(m.shipmentId());
        quarantine(m.transfer(0), "500");
        acknowledge(alertId);
        inspect(receiverQm(), m.batch(0), "RCV-FAIL", "FAIL", alertId);

        JsonNode rejected = expect(rejectRequest(receiverSession, m.transfer(0), key("idem-rej")), 200).get("data");
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("receivedQuantity").decimalValue()).isEqualByComparingTo("500");
        assertThat(rejected.get("quarantineSiteId").asLong()).isEqualTo(receiverSite.getId());
        assertThat(batchOrgId(m.batch(0))).isEqualTo(senderOrg.getId());
        assertThat(riskStatus(m.batch(0))).isEqualTo("FROZEN");
        assertThat(count("SELECT count(*) FROM transfer WHERE batch_id = ? AND open_batch_id IS NOT NULL", m.batch(0))).isZero();
        // 未隔离直接拒收（PENDING → REJECTED）仍按原规则，不写实收数量
        JsonNode direct = expect(rejectRequest(receiverSession, m.transfer(1), key("idem-rej")), 200).get("data");
        assertThat(direct.has("receivedQuantity")).isFalse();
        assertThat(direct.has("quarantineSiteId")).isFalse();
        // 拒收后仍需质量处置：两个批次都冻结，处置结论被阻断
        expectProblem(resolveReq(senderQm(), alertId, "拒收", key("idem-res")), 409, "ALERT_BATCHES_PENDING");
        assertThat(riskStatus(m.batch(1))).isEqualTo("FROZEN");
    }

    @Test
    @DisplayName("未经告警的正常批次同样可以隔离收货，风险正常时隔离后可直接接受；PENDING 的正常批次仍可直接接受（Phase A 不变）")
    void quarantineWithoutAlert_thenAccept() throws Exception {
        Long batchId = createAndSubmitActiveBatch(senderSession, "EXT-QNA-" + suffix, new BigDecimal("300"));
        Handover h = prepareDeliveredHandover(batchId);
        expect(quarantineReq(receiverSession, h.transferId(), "300", null, receiverSite.getId(), "外包装破损，隔离复核", key("idem-q")), 200);
        assertThat(riskStatus(batchId)).isEqualTo("NORMAL");
        JsonNode accepted = expect(acceptRequest(receiverSession, h.transferId(), new BigDecimal("300"), key("idem-acc")), 200).get("data");
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("quarantineReason").asString()).isEqualTo("外包装破损，隔离复核");
        assertThat(batchOrgId(batchId)).isEqualTo(receiverOrg.getId());
        assertThat(List.of(countEvents(batchId, "ARRIVAL"), countEvents(batchId, "TRANSPORT"))).containsExactly(1, 1);
    }
}
