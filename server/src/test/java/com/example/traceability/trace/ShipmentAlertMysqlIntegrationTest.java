package com.example.traceability.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Phase B PB3 在途持续超温 → Shipment 级告警 → 受影响批次自动风险冻结（真实 MySQL 8.4，全部经真实 API）。
 * <p>
 * 覆盖：只按温度记录持久化的判定依据快照判定（单点、未达时长、方向改变、依据改变都不构成持续超温；允许时长 0 越界即构成）；
 * 乱序补登补全片段；同一片段只告警一次；经 Shipment → Transfer → Batch 快照受影响批次并经风险核心自动冻结（ALERT 来源、
 * 无操作人、系统幂等键），已冻结批次只快照不重复转换；不改变流转状态、数量、责任组织、公开追溯码，不生成 TraceEvent，
 * 不修改运输任务与交接；匿名公开投影只体现风险状态、不泄露告警；查看与确认的权限矩阵与幂等。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("在途持续超温告警与自动冻结 MySQL 8.4 集成测试（PB3）")
class ShipmentAlertMysqlIntegrationTest extends AbstractPhaseBMysqlIT {

    private LocalDateTime twoHoursAgo() {
        return LocalDateTime.now(ZoneOffset.UTC).minusHours(2);
    }

    /** 不得被告警 / 自动冻结改变的事实：批次（除风险状态与版本外）、运输任务、交接、事件、谱系、销售与公开追溯码。 */
    private List<Object> untouchedFacts(Manifest m) {
        List<Object> facts = new java.util.ArrayList<>();
        facts.add(jdbcTemplate.queryForList("SELECT id, org_id, flow_status, quantity, unit_code, product_id, trace_batch_no "
                + "FROM batch WHERE id IN (" + ids(m.batchIds()) + ") ORDER BY id"));
        facts.add(jdbcTemplate.queryForMap("SELECT status, version, loaded_at, unloaded_at FROM shipment WHERE id = ?", m.shipmentId()));
        facts.add(jdbcTemplate.queryForList("SELECT id, status, version, batch_id FROM transfer WHERE shipment_id = ? ORDER BY id", m.shipmentId()));
        for (Long b : m.batchIds()) {
            facts.add(businessSideEffects(b));
        }
        facts.add(jdbcTemplate.queryForList("SELECT batch_id, public_id, status, org_id, version FROM public_trace_code "
                + "WHERE batch_id IN (" + ids(m.batchIds()) + ") ORDER BY batch_id"));
        return facts;
    }

    private static String ids(List<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    // =========================================================================
    // 持续超温判定与自动冻结
    // =========================================================================

    @Test
    @DisplayName("连续高于上限达到允许时长：创建一条 Shipment 级告警，快照两个受影响批次并经风险核心自动冻结；其余事实全部不变，同一片段不再重复告警")
    void sustainedExcursion_createsOneAlert_andFreezesShipmentBatches() throws Exception {
        Long stageId = publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 1800);
        Long codeBatch = createAndSubmitActiveBatch(senderSession, "EXT-ALERT-CODE-" + suffix, new BigDecimal("600"));
        expect(activateCodeRequest(senderSession, codeBatch, key("idem-code")), 200);
        Long other = createAndSubmitActiveBatch(senderSession, "EXT-ALERT-OTHER-" + suffix, new BigDecimal("360"));
        Manifest m = inTransitManifestOf(List.of(codeBatch, other), twoHoursAgo());
        LocalDateTime t = m.loadedAt();
        String publicId = jdbcTemplate.queryForObject("SELECT public_id FROM public_trace_code WHERE batch_id = ?", String.class, codeBatch);
        long v2 = batchVersion(codeBatch);
        long v3 = batchVersion(other);
        List<Object> before = untouchedFacts(m);

        recordAt(m.shipmentId(), t.plusMinutes(5), "-18.00");
        JsonNode first = recordAt(m.shipmentId(), t.plusMinutes(10), "-12.00");
        recordAt(m.shipmentId(), t.plusMinutes(25), "-13.50");
        assertThat(alertRows(m.shipmentId())).as("25 minutes of excursion is below the 1800 s allowance").isEmpty();
        assertThat(riskStatus(codeBatch)).isEqualTo("NORMAL");

        JsonNode completing = recordAt(m.shipmentId(), t.plusMinutes(40), "-11.00");
        assertThat(completing.get("evaluation").asString()).as("the reading itself is still a single-point evaluation").isEqualTo("HIGH");

        Long alertId = onlyAlertId(m.shipmentId());
        Map<String, Object> alert = alertRows(m.shipmentId()).get(0);
        assertThat(alert).containsEntry("alert_type", "TEMP_OVER_UPPER").containsEntry("severity", "HIGH")
                .containsEntry("status", "OPEN").containsEntry("stage_code", "TRANSPORT");
        assertThat(((Number) alert.get("org_id")).longValue()).isEqualTo(senderOrg.getId());
        assertThat(((Number) alert.get("episode_start_record_id")).longValue()).isEqualTo(first.get("id").asLong());
        assertThat(((Number) alert.get("sustained_record_id")).longValue()).isEqualTo(completing.get("id").asLong());
        assertThat(((Number) alert.get("duration_seconds")).intValue()).isEqualTo(1800);
        assertThat(((Number) alert.get("rule_stage_id")).longValue()).isEqualTo(stageId);
        assertThat((BigDecimal) alert.get("rule_upper_limit")).isEqualByComparingTo("-15.00");
        assertThat(((Number) alert.get("rule_allowed_duration_seconds")).intValue()).isEqualTo(1800);
        assertThat((String) alert.get("reason")).contains("连续高于上限 -15.00").contains("1800 秒");
        assertThat(alert.get("acknowledged_by")).isNull();

        // 受影响批次快照与自动冻结（ALERT 来源、无操作人、系统键），流转状态 / 数量 / 责任组织 / 公开码 / 事件 / 运输任务 / 交接不变
        for (Long b : m.batchIds()) {
            assertThat(riskStatus(b)).isEqualTo("FROZEN");
            assertThat(alertLedgerRows(b, alertId)).isEqualTo(1);
            Map<String, Object> ledger = jdbcTemplate.queryForMap("SELECT * FROM batch_risk_transition WHERE batch_id = ?", b);
            assertThat(ledger).containsEntry("from_status", "NORMAL").containsEntry("to_status", "FROZEN")
                    .containsEntry("flow_status", "ACTIVE").containsEntry("source_type", "ALERT").containsEntry("actor_user_id", null)
                    .containsEntry("idempotency_key", "SYS:ALERT:" + alertId + ":BATCH:" + b);
            assertThat(((Number) ledger.get("org_id")).longValue()).isEqualTo(senderOrg.getId());
            assertThat(count("SELECT count(*) FROM alert_batch WHERE alert_id = ? AND batch_id = ? AND risk_status_before = 'NORMAL' "
                    + "AND freeze_transition_id = ?", alertId, b, ((Number) ledger.get("id")).longValue())).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'RISK_FREEZE' AND object_type = 'BATCH' AND object_id = ? "
                    + "AND actor_user_id IS NULL AND actor_org_id = ?", b, senderOrg.getId())).isEqualTo(1);
            assertLedgerConsistent(b);
        }
        assertThat(batchVersion(codeBatch)).isEqualTo(v2 + 1);
        assertThat(batchVersion(other)).isEqualTo(v3 + 1);
        assertThat(untouchedFacts(m)).as("alert + auto-freeze change nothing but risk status / version").isEqualTo(before);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'ALERT_TRIGGER' AND object_type = 'ALERT' AND object_id = ? "
                + "AND actor_user_id IS NULL", alertId)).isEqualTo(1);

        // 同一片段继续越界：不再创建告警，批次不再转换
        recordAt(m.shipmentId(), t.plusMinutes(50), "-10.00");
        assertThat(alertRows(m.shipmentId())).hasSize(1);
        assertThat(ledgerRows(codeBatch)).isEqualTo(1);

        // 企业端详情：受影响批次与当前风险状态；匿名公开投影只体现风险状态，不含告警
        JsonNode detail = alertDetail(senderSession, alertId);
        assertThat(detail.get("status").asString()).isEqualTo("OPEN");
        assertThat(detail.get("shipmentId").asLong()).isEqualTo(m.shipmentId());
        assertThat(detail.get("rule").get("allowedDurationSeconds").asInt()).isEqualTo(1800);
        assertThat(detail.get("batches")).hasSize(2);
        assertThat(detail.get("batches").get(0).get("batchId").asLong()).isEqualTo(codeBatch);
        assertThat(detail.get("batches").get(0).get("autoFrozen").asBoolean()).isTrue();
        assertThat(detail.get("batches").get(0).get("currentRiskStatus").asString()).isEqualTo("FROZEN");
        assertThat(detail.get("batches").get(0).get("transferStatus").asString()).isEqualTo("PENDING");
        assertThat(detail.get("actions")).isEmpty();
        assertThat(detail.has("idempotencyKey")).isFalse();

        String publicBody = mockMvc.perform(get("/api/public/v1/public/traces/" + publicId)).andReturn().getResponse().getContentAsString();
        JsonNode publicData = objectMapper.readTree(publicBody).get("data");
        assertThat(publicData.get("riskStatus").asString()).isEqualTo("FROZEN");
        assertThat(publicData.get("flowStatus").asString()).isEqualTo("ACTIVE");
        String alertNo = (String) alert.get("alert_no");
        assertThat(publicBody).doesNotContain(alertNo).doesNotContain("ALERT").doesNotContain("告警").doesNotContain("-11.00")
                .doesNotContain("alertId").doesNotContain("shipmentNo");
    }

    @Test
    @DisplayName("只有已测得的连续越界区间计时：单条越界、被 NORMAL / MISSING_CONTEXT 打断、方向改变都不构成持续超温")
    void nonSustainedSequences_neverAlert() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 1800);
        Manifest m = inTransitManifest("NOALERT", twoHoursAgo(), "500");
        LocalDateTime t = m.loadedAt();

        recordAt(m.shipmentId(), t.plusMinutes(1), "-10.00");     // 单条 HIGH
        recordAt(m.shipmentId(), t.plusMinutes(20), "-18.00");    // NORMAL 打断
        recordAt(m.shipmentId(), t.plusMinutes(40), "-10.00");    // HIGH
        recordAt(m.shipmentId(), t.plusMinutes(75), "-30.00");    // 方向改变 LOW
        recordAt(m.shipmentId(), t.plusMinutes(90), "-10.00");    // 再次 HIGH（与 40 分钟处的 HIGH 之间隔着 LOW）

        assertThat(alertRows(m.shipmentId())).isEmpty();
        assertThat(riskStatus(m.batch(0))).isEqualTo("NORMAL");
        assertThat(ledgerRows(m.batch(0))).isZero();
    }

    @Test
    @DisplayName("允许越界时长为 0：第一条越界记录即构成持续超温（低于下限），持续秒数 0")
    void zeroAllowedDuration_firstOutOfRangeReadingAlerts() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 0);
        Manifest m = inTransitManifest("ZERO", twoHoursAgo(), "500");

        recordAt(m.shipmentId(), m.loadedAt().plusMinutes(3), "-18.00");
        assertThat(alertRows(m.shipmentId())).isEmpty();
        JsonNode low = recordAt(m.shipmentId(), m.loadedAt().plusMinutes(4), "-26.00");

        Map<String, Object> alert = alertRows(m.shipmentId()).get(0);
        assertThat(alert).containsEntry("alert_type", "TEMP_UNDER_LOWER");
        assertThat(((Number) alert.get("duration_seconds")).intValue()).isZero();
        assertThat(((Number) alert.get("episode_start_record_id")).longValue()).isEqualTo(low.get("id").asLong());
        assertThat(((Number) alert.get("sustained_record_id")).longValue()).isEqualTo(low.get("id").asLong());
        assertThat((String) alert.get("reason")).contains("低于下限 -25.00").contains("0 秒");
        assertThat(riskStatus(m.batch(0))).isEqualTo("FROZEN");
    }

    @Test
    @DisplayName("乱序补登：较晚测量时间先登记、较早测量时间后登记，补全的片段以较早记录为开始、较晚记录为达到时长的记录")
    void outOfOrderReading_completesEpisode() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 1800);
        Manifest m = inTransitManifest("ORDER", twoHoursAgo(), "500");
        LocalDateTime t = m.loadedAt();

        JsonNode late = recordAt(m.shipmentId(), t.plusMinutes(60), "-12.00");
        assertThat(alertRows(m.shipmentId())).isEmpty();
        JsonNode early = recordAt(m.shipmentId(), t.plusMinutes(30), "-12.00");

        Map<String, Object> alert = alertRows(m.shipmentId()).get(0);
        assertThat(((Number) alert.get("episode_start_record_id")).longValue()).isEqualTo(early.get("id").asLong());
        assertThat(((Number) alert.get("sustained_record_id")).longValue()).isEqualTo(late.get("id").asLong());
        assertThat(((Number) alert.get("duration_seconds")).intValue()).isEqualTo(1800);
    }

    @Test
    @DisplayName("历史判定依据：判定只用记录登记时的允许时长快照；登记后改动被引用规则环节的允许时长不会让已有序列在后续登记时被重新判定为持续超温")
    void sustainedDecision_usesPersistedSnapshotNotCurrentStage() throws Exception {
        Long stageId = publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 3600);
        Manifest m = inTransitManifest("SNAP", twoHoursAgo(), "500");
        LocalDateTime t = m.loadedAt();
        recordAt(m.shipmentId(), t.plusMinutes(10), "-12.00");
        recordAt(m.shipmentId(), t.plusMinutes(50), "-12.00");   // 40 分钟 < 3600 秒快照
        assertThat(alertRows(m.shipmentId())).isEmpty();

        // 绕过应用改动规则环节（真实系统中已发布规则不可修改；这里只用于证明判定不读取当前环节）
        jdbcTemplate.update("UPDATE temperature_rule_stage SET allowed_duration_seconds = 600 WHERE id = ?", stageId);
        // 触发再次判定的是一条范围内记录（其快照为新值 600，但它结束了片段，不属于越界片段）
        recordAt(m.shipmentId(), t.plusMinutes(55), "-18.00");
        assertThat(alertRows(m.shipmentId())).as("the stored 3600 s basis governs the existing records").isEmpty();
        assertThat(riskStatus(m.batch(0))).isEqualTo("NORMAL");

        // 改动之后的新越界记录按新快照 600 秒判定，且与旧依据的记录不构成同一片段
        recordAt(m.shipmentId(), t.plusMinutes(60), "-12.00");
        recordAt(m.shipmentId(), t.plusMinutes(70), "-12.00");
        Map<String, Object> alert = alertRows(m.shipmentId()).get(0);
        assertThat(((Number) alert.get("rule_allowed_duration_seconds")).intValue()).isEqualTo(600);
        assertThat(((Number) alert.get("duration_seconds")).intValue()).isEqualTo(600);
    }

    @Test
    @DisplayName("已被人工冻结的批次只快照（FROZEN、无转换），其余 NORMAL 批次经告警自动冻结；人工台账不受影响")
    void alreadyFrozenBatch_snapshottedWithoutSecondTransition() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 0);
        Manifest m = inTransitManifest("PREFROZEN", twoHoursAgo(), "600", "360");
        freeze(senderQm(), m.batch(0), "在途前质量抽检异常");

        recordAt(m.shipmentId(), m.loadedAt().plusMinutes(5), "-10.00");
        Long alertId = onlyAlertId(m.shipmentId());

        assertThat(jdbcTemplate.queryForMap("SELECT risk_status_before, freeze_transition_id FROM alert_batch WHERE alert_id = ? AND batch_id = ?",
                alertId, m.batch(0))).containsEntry("risk_status_before", "FROZEN").containsEntry("freeze_transition_id", null);
        assertThat(alertLedgerRows(m.batch(0), alertId)).isZero();
        assertThat(ledgerRows(m.batch(0))).as("only the manual freeze").isEqualTo(1);
        assertThat(alertLedgerRows(m.batch(1), alertId)).isEqualTo(1);
        assertThat(riskStatus(m.batch(1))).isEqualTo("FROZEN");
        JsonNode detail = alertDetail(senderSession, alertId);
        assertThat(detail.get("batches").get(0).get("autoFrozen").asBoolean()).isFalse();
        assertThat(detail.get("batches").get(0).get("riskStatusBefore").asString()).isEqualTo("FROZEN");
        assertThat(detail.get("batches").get(1).get("autoFrozen").asBoolean()).isTrue();
        assertLedgerConsistent(m.batch(0));
        assertLedgerConsistent(m.batch(1));
    }

    @Test
    @DisplayName("自动冻结后 Phase A 守卫继续生效；运输任务仍可按应急规则确认到达（生成 ARRIVAL，责任组织仍为发货方），接收方不能 ACCEPT 冻结批次")
    void frozenInTransit_arrivalAllowed_acceptBlocked() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 0);
        Manifest m = inTransitManifest("ARRIVE", twoHoursAgo(), "500");
        LocalDateTime measured = m.loadedAt().plusMinutes(5);
        recordAt(m.shipmentId(), measured, "-10.00");
        assertThat(riskStatus(m.batch(0))).isEqualTo("FROZEN");

        arriveAt(m.shipmentId(), measured.plusMinutes(1));
        assertThat(shipmentStatus(m.shipmentId())).isEqualTo("DELIVERED");
        assertThat(countEvents(m.batch(0), "ARRIVAL")).isEqualTo(1);
        assertThat(batchOrgId(m.batch(0))).isEqualTo(senderOrg.getId());
        expectProblem(acceptRequest(receiverSession, m.transfer(0), new BigDecimal("500"), key("idem-acc")), 409, "BATCH_FLOW_BLOCKED");
        assertThat(transferStatus(m.transfer(0))).isEqualTo("PENDING");
        assertThat(batchOrgId(m.batch(0))).isEqualTo(senderOrg.getId());
        // 到达之后不能再登记在途温度，因此不会再有新的告警
        expectProblem(recordReq(m.shipmentId(), key("idem-temp"), measured.plusMinutes(2), "-10.00"), 409, "SHIPMENT_NOT_IN_TRANSIT");
        assertThat(alertRows(m.shipmentId())).hasSize(1);
    }

    // =========================================================================
    // 查看与确认
    // =========================================================================

    @Test
    @DisplayName("查看权限：发货方（任意角色）、接收方、承运方与平台只读可查看；第三方组织列表为空、详情 403；匿名 401；不存在 404；非法状态过滤 400")
    void visibilityMatrix() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 0);
        Manifest m = inTransitManifest("VIS", twoHoursAgo(), "500");
        recordAt(m.shipmentId(), m.loadedAt().plusMinutes(5), "-10.00");
        Long alertId = onlyAlertId(m.shipmentId());

        for (MockHttpSession s : List.of(senderSession, senderQm(), receiverSession, carrierSession)) {
            JsonNode list = expect(getReq(s, "/api/v1/alerts?shipmentId=" + m.shipmentId()), 200).get("data");
            assertThat(list).hasSize(1);
            assertThat(list.get(0).get("id").asLong()).isEqualTo(alertId);
            assertThat(list.get(0).has("batches")).as("list items carry no batch detail").isFalse();
            assertThat(alertDetail(s, alertId).get("batches")).hasSize(1);
        }
        MockHttpSession platform = newSession(createOrg("ORG_P_" + suffix, "平台-" + suffix, "PROCESSOR"), "plat", "SYSTEM_ADMIN", "PLATFORM");
        assertThat(alertDetail(platform, alertId).get("id").asLong()).isEqualTo(alertId);
        assertThat(expect(getReq(platform, "/api/v1/alerts?shipmentId=" + m.shipmentId()), 200).get("data")).hasSize(1);

        MockHttpSession outsider = newSession(createOrg("ORG_X_" + suffix, "无关企业-" + suffix, "PROCESSOR"), "out_qm", QM_ROLE, "OWN_ORG");
        assertThat(expect(getReq(outsider, "/api/v1/alerts"), 200).get("data")).isEmpty();
        expectProblem(getReq(outsider, "/api/v1/alerts/" + alertId), 403, "ORG_SCOPE_DENIED");
        expect(get("/api/v1/alerts/" + alertId), 401);
        expectProblem(getReq(senderSession, "/api/v1/alerts/999999999"), 404, "RESOURCE_NOT_FOUND");
        expectProblem(getReq(senderSession, "/api/v1/alerts?status=CLOSED"), 400, "INVALID_REQUEST");
        assertThat(expect(getReq(senderSession, "/api/v1/alerts?status=open&shipmentId=" + m.shipmentId()), 200).get("data")).hasSize(1);
        assertThat(expect(getReq(senderSession, "/api/v1/alerts?status=RESOLVED&shipmentId=" + m.shipmentId()), 200).get("data")).isEmpty();
    }

    @Test
    @DisplayName("确认：只有告警归属组织的质量管理员可以 OPEN → ACKNOWLEDGED（确认人即处置负责人）；同键同语义重放、同键不同语义 409、重复确认 409；不改变批次 / 交接 / 运输任务")
    void acknowledge_authorizationIdempotencyAndState() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 0);
        Manifest m = inTransitManifest("ACK", twoHoursAgo(), "500");
        recordAt(m.shipmentId(), m.loadedAt().plusMinutes(5), "-10.00");
        Long alertId = onlyAlertId(m.shipmentId());
        List<Object> before = untouchedFacts(m);
        long batchVersion = batchVersion(m.batch(0));

        expectProblem(acknowledgeReq(senderSession, alertId, null, key("idem-ack")), 403, "ACCESS_DENIED");
        expectProblem(acknowledgeReq(receiverQm(), alertId, null, key("idem-ack")), 403, "ORG_SCOPE_DENIED");
        MockHttpSession carrierQm = newSession(carrierOrg, "car_qm", QM_ROLE, "OWN_ORG");
        expectProblem(acknowledgeReq(carrierQm, alertId, null, key("idem-ack")), 403, "ORG_SCOPE_DENIED");
        MockHttpSession admin = newSession(senderOrg, "sys_adm", "SYSTEM_ADMIN", "PLATFORM");
        expectProblem(acknowledgeReq(admin, alertId, null, key("idem-ack")), 403, "ADMIN_RESTRICTED");
        expectProblem(acknowledgeReq(senderQm(), alertId, null, null), 400, "INVALID_REQUEST");
        expectProblem(acknowledgeReq(senderQm(), alertId, null, "SYS:ALERT:forged-key-000001"), 400, "INVALID_REQUEST");
        expectProblem(postJson(senderQm(), "/api/v1/alerts/" + alertId + "/acknowledge", key("idem-ack"),
                Map.of("note", "x", "status", "RESOLVED")), 400, "INVALID_REQUEST");
        expectProblem(acknowledgeReq(senderQm(), 999999999L, null, key("idem-ack")), 404, "RESOURCE_NOT_FOUND");
        assertThat(alertRows(m.shipmentId()).get(0)).containsEntry("status", "OPEN");

        String k = key("idem-ack");
        JsonNode acked = expect(acknowledgeReq(senderQm(), alertId, "  已确认，安排复检  ", k), 200).get("data");
        assertThat(acked.get("status").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(acked.get("acknowledgedBy").asLong()).isPositive();
        assertThat(acked.get("actions")).hasSize(1);
        assertThat(acked.get("actions").get(0).get("action").asString()).isEqualTo("ACKNOWLEDGE");
        assertThat(acked.get("actions").get(0).get("note").asString()).isEqualTo("已确认，安排复检");

        JsonNode replay = expect(acknowledgeReq(senderQm(), alertId, "已确认，安排复检", k), 200).get("data");
        assertThat(replay.get("version").asLong()).isEqualTo(acked.get("version").asLong());
        expectProblem(acknowledgeReq(senderQm(), alertId, "另一说明", k), 409, "IDEMPOTENCY_CONFLICT");
        expectProblem(acknowledgeReq(senderQm(), alertId, null, key("idem-ack")), 409, "INVALID_STATE_TRANSITION");

        assertThat(count("SELECT count(*) FROM alert_action WHERE alert_id = ?", alertId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'ALERT_ACKNOWLEDGE' AND object_id = ?", alertId)).isEqualTo(1);
        assertThat(untouchedFacts(m)).isEqualTo(before);
        assertThat(batchVersion(m.batch(0))).isEqualTo(batchVersion);
        assertThat(riskStatus(m.batch(0))).isEqualTo("FROZEN");
    }
}
