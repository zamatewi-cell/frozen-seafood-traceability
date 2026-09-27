package com.example.traceability.trace;

import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.quality.dto.RecallCreateRequest;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import com.example.traceability.trace.dto.TransferCreateRequest;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B 独立评审反例（真实 MySQL 8.4，全部经真实 API）。每个用例只断言统一业务契约 v1.1 的不变量，不绑定具体修复方案：
 * <ul>
 *   <li>反例 1：同一批次处于两个未处置告警中时，只凭其中一个告警的合格结论放行，不得使批次恢复 NORMAL（契约 §4.2：FROZEN
 *       表示“等待调查或质量结论”；§13 步骤 10：调查合格时才恢复 NORMAL）；另一个告警也必须仍能形成自己的结论并处置完毕。</li>
 *   <li>反例 2：召回通知属于批次（契约 §2.12 Recall 负责“通知”；§14 只有当前责任组织执行 Recall，已转出批次的历史参与组织不能再修改），
 *       被通知批次再次交接后，新的当前责任组织必须能看到该召回；历史持有方仍保留自己的历史记录。</li>
 * </ul>
 * 断言使用 SoftAssertions，一次报告全部被违反的不变量。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("Phase B 独立评审反例复现 MySQL 8.4 集成测试")
class PhaseBReviewCounterexamplesMysqlIntegrationTest extends AbstractPhaseBMysqlIT {

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String describe(MvcResult r) throws Exception {
        return r.getResponse().getStatus() + " " + r.getResponse().getContentAsString();
    }

    // =========================================================================
    // 反例 1：同一批次两个未处置告警
    // =========================================================================

    @Test
    @DisplayName("反例 1a：A1 合格、A2 不合格（该批次最新检验结论为不合格）时，依据 A1 放行不得使 B 恢复 NORMAL，接收方也不得接受 B")
    void ce1a_passOnOneAlertMustNotNormalizeWhileOtherOpenAlertFailed() throws Exception {
        TwoAlerts t = twoOpenAlertsOnOneBatch("CE1A");
        inspect(senderQm(), t.batchId(), "SND-A1-PASS", "PASS", t.a1());
        inspect(senderQm(), t.batchId(), "SND-A2-FAIL", "FAIL", t.a2());

        MvcResult releaseUnderA1 = perform(releaseReq(senderQm(), t.a1(), t.batchId(), null, key("idem-rel-a1")));
        String riskAfterRelease = riskStatus(t.batchId());
        MvcResult accept = perform(acceptRequest(receiverSession, t.manifest().transfer(0), new BigDecimal("500"), key("idem-acc")));

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(riskAfterRelease)
                .as("A2 仍未处置且关联 A2 的最新检验为不合格：B 必须保持 FROZEN（A1 放行响应：%s）", describe(releaseUnderA1))
                .isEqualTo("FROZEN");
        softly.assertThat(status(accept))
                .as("冻结批次不得被正常接受（接受响应：%s）", describe(accept))
                .isNotEqualTo(200);
        softly.assertThat(transferStatus(t.manifest().transfer(0))).as("交接保持 PENDING").isEqualTo("PENDING");
        softly.assertThat(batchOrgId(t.batchId())).as("责任组织仍为发货方").isEqualTo(senderOrg.getId());
        softly.assertAll();
    }

    @Test
    @DisplayName("反例 1b：两个未处置告警各自需要合格结论；只有两个结论都形成后 B 才恢复 NORMAL，且 A1、A2 都能形成处置结论")
    void ce1b_eachOpenAlertNeedsItsOwnDecisionAndBothStayResolvable() throws Exception {
        TwoAlerts t = twoOpenAlertsOnOneBatch("CE1B");
        inspect(senderQm(), t.batchId(), "SND-A1-PASS", "PASS", t.a1());

        MvcResult releaseUnderA1 = perform(releaseReq(senderQm(), t.a1(), t.batchId(), null, key("idem-rel-a1")));
        String riskAfterA1 = riskStatus(t.batchId());
        inspect(senderQm(), t.batchId(), "SND-A2-PASS", "PASS", t.a2());
        MvcResult releaseUnderA2 = perform(releaseReq(senderQm(), t.a2(), t.batchId(), null, key("idem-rel-a2")));
        String riskAfterA2 = riskStatus(t.batchId());
        MvcResult resolveA1 = perform(resolveReq(senderQm(), t.a1(), "A1 片段复检合格", key("idem-res-a1")));
        MvcResult resolveA2 = perform(resolveReq(senderQm(), t.a2(), "A2 片段复检合格", key("idem-res-a2")));

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(riskAfterA1)
                .as("只有 A1 的合格结论、A2 尚无结论：B 必须保持 FROZEN（A1 放行响应：%s）", describe(releaseUnderA1))
                .isEqualTo("FROZEN");
        softly.assertThat(status(releaseUnderA2))
                .as("A2 必须能依据关联 A2 的合格结论形成自己的放行结论（A2 放行响应：%s）", describe(releaseUnderA2))
                .isEqualTo(200);
        softly.assertThat(riskAfterA2).as("两个告警的合格结论都形成后 B 恢复 NORMAL").isEqualTo("NORMAL");
        softly.assertThat(status(resolveA1)).as("A1 能形成处置结论（响应：%s）", describe(resolveA1)).isEqualTo(200);
        softly.assertThat(status(resolveA2)).as("A2 不得被永久卡在处置中（响应：%s）", describe(resolveA2)).isEqualTo(200);
        softly.assertAll();
    }

    // =========================================================================
    // 评审修复的相邻情形：人工冻结 + 告警、批次最新的不相关不合格报告
    // =========================================================================

    @Test
    @DisplayName("人工冻结 + 告警：告警的合格结论只解除告警风险事项，批次保持 FROZEN，直到质量管理员人工解除冻结")
    void manualFreezeThenAlert_alertDecisionKeepsManualFreezeUntilManualRelease() throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 0);
        Manifest m = inTransitManifest("D2", LocalDateTime.now(ZoneOffset.UTC).minusHours(2), "500");
        Long b = m.batch(0);
        freeze(senderQm(), b, "在途前质量抽检异常，待调查");
        recordAt(m.shipmentId(), m.loadedAt().plusMinutes(5), "-10.00");
        arriveAt(m.shipmentId(), m.loadedAt().plusMinutes(10));
        Long a1 = onlyAlertId(m.shipmentId());
        acknowledge(a1);
        // 告警风险事项未解除时，人工接口不能绕过告警质量结论解除冻结
        expectProblem(releaseRequest(senderQm(), b, "抽检合格", key("idem-risk-r1")), 409, "ALERT_DECISION_REQUIRED");

        inspect(senderQm(), b, "SND-D2-PASS", "PASS", a1);
        JsonNode released = expect(releaseReq(senderQm(), a1, b, null, key("idem-rel-a1")), 200).get("data");

        JsonNode row = released.get("batches").get(0);
        assertThat(row.get("released").asBoolean()).as("A1 已形成放行结论").isTrue();
        assertThat(row.has("releaseTransitionId")).as("人工冻结仍未解除：本结论不写放行转换").isFalse();
        assertThat(row.get("currentRiskStatus").asString()).isEqualTo("FROZEN");
        assertThat(row.get("pendingHolds")).hasSize(1);
        assertThat(row.get("pendingHolds").get(0).get("type").asString()).isEqualTo("MANUAL_FREEZE");
        assertThat(riskStatus(b)).isEqualTo("FROZEN");
        assertThat(count("SELECT count(*) FROM alert_action WHERE alert_id = ? AND batch_id = ? AND action = 'RELEASE_BATCH' "
                + "AND risk_transition_id IS NULL", a1, b)).isEqualTo(1);
        assertThat(status(perform(acceptRequest(receiverSession, m.transfer(0), new BigDecimal("500"), key("idem-acc")))))
                .as("仍冻结的批次不能被接受").isNotEqualTo(200);

        // 告警可以形成处置结论（结论已记录）；人工冻结只能由人工解除（此时不再有未处置告警风险事项）
        expect(resolveReq(senderQm(), a1, "在途超温复检合格", key("idem-res-a1")), 200);
        expect(releaseRequest(senderQm(), b, "人工抽检复核合格，解除冻结", key("idem-risk-r2")), 201);
        assertThat(riskStatus(b)).isEqualTo("NORMAL");
        assertThat(jdbcTemplate.queryForList("SELECT CONCAT(source_type, ':', from_status, '>', to_status) FROM batch_risk_transition "
                + "WHERE batch_id = ? ORDER BY id", String.class, b)).containsExactly("MANUAL:NORMAL>FROZEN", "MANUAL:FROZEN>NORMAL");
        assertLedgerConsistent(b);
    }

    @Test
    @DisplayName("批次最新的检验报告为不相关（未关联本告警）的不合格报告时，不能依据较早的合格结论放行；之后新的合格结论可以放行")
    void latestUnrelatedFailReport_blocksReleaseUntilNewerPass() throws Exception {
        Manifest m = deliveredAlertManifest("D3", "500");
        Long b = m.batch(0);
        Long a1 = onlyAlertId(m.shipmentId());
        acknowledge(a1);
        inspect(senderQm(), b, "SND-D3-PASS", "PASS", a1);
        inspect(senderQm(), b, "SND-D3-ROUTINE-FAIL", "FAIL", null);

        expectProblem(releaseReq(senderQm(), a1, b, null, key("idem-rel-1")), 409, "INSPECTION_NOT_PASSED");
        assertThat(riskStatus(b)).isEqualTo("FROZEN");
        assertThat(count("SELECT count(*) FROM alert_action WHERE alert_id = ? AND action = 'RELEASE_BATCH'", a1)).isZero();

        // 更新的合格复检（关联本告警）成为批次最新证据后可以放行；这是唯一的风险事项，因此写入放行转换
        inspect(senderQm(), b, "SND-D3-RECHECK", "PASS", a1);
        JsonNode released = expect(releaseReq(senderQm(), a1, b, null, key("idem-rel-2")), 200).get("data");
        assertThat(released.get("batches").get(0).get("releaseTransitionId").asLong()).isPositive();
        assertThat(riskStatus(b)).isEqualTo("NORMAL");
        assertLedgerConsistent(b);
    }

    // =========================================================================
    // 反例 2：召回通知随批次交接
    // =========================================================================

    /** 零售组织：OPERATOR / QUALITY_MANAGER 会话与启用门店。 */
    private record Party(Organization org, MockHttpSession operator, MockHttpSession qm, Site store) {
    }

    private Party retailer(String tag) throws Exception {
        Organization org = createOrg("ORG_" + tag + "_" + suffix, "零售企业" + tag + "-" + suffix, "RETAILER");
        String prefix = tag.toLowerCase();
        return new Party(org, newSession(org, prefix + "_op", "OPERATOR", "OWN_ORG"), newSession(org, prefix + "_qm", QM_ROLE, "OWN_ORG"),
                createSite(org.getId(), tag + "-STORE-" + suffix, "门店" + tag + "-" + suffix, "STORE"));
    }

    /** 当前持有方经 Transfer + Shipment 把批次整批交给接收方，接收方接受（接受后接收方为当前责任组织）。 */
    private void handOver(MockHttpSession senderOperator, Long originSiteId, Long batchId, String qty, Party to) throws Exception {
        Long transferId = expect(postJson(senderOperator, "/api/v1/transfers", key("idem-trf-c"),
                new TransferCreateRequest(batchId, to.org().getId())), 201).get("data").get("id").asLong();
        createdTransferIds.add(transferId);
        Long shipmentId = expect(createShipmentRequest(senderOperator, key("idem-shp-c"), carrierOrg.getId(), originSiteId,
                to.store().getId()), 201).get("data").get("id").asLong();
        createdShipmentIds.add(shipmentId);
        expect(bindRequest(senderOperator, shipmentId, transferId, transferVersion(transferId)), 200);
        expect(submitRequest(senderOperator, transferId, transferVersion(transferId)), 200);
        dispatch(shipmentId);
        arrive(shipmentId);
        expect(postJson(to.operator(), "/api/v1/transfers/" + transferId + "/accept", key("idem-trf-acc"),
                new TransferAcceptRequest(new BigDecimal(qty), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, transferVersion(transferId))), 200);
        assertThat(batchOrgId(batchId)).isEqualTo(to.org().getId());
    }

    @Test
    @DisplayName("反例 2：零售企业 RA 持有的后续批次 C 收到上游召回通知后整批交给零售企业 RB，RB（当前责任组织）必须能看到该召回；RA 保留历史记录")
    void ce2_recallNoticeFollowsHandedOverBatchToCurrentResponsibleOrg() throws Exception {
        // 加工企业持有 P（最新检验不合格）→ PROCESS 得到 C → C 整批交给零售企业 RA
        Long parent = processorHeldBatch("EXT-CE2-" + suffix, new BigDecimal("500"));
        MockHttpSession processorQm = newSession(receiverOrg, "prc_qm", QM_ROLE, "OWN_ORG");
        inspect(processorQm, parent, "PRC-FAIL-CE2", "FAIL", null);
        JsonNode op = createAndSubmitOperation("PROCESS", List.of(opInput(parent, "500"), opOutput("480"), opOther("LOSS", "20")));
        Long child = op.get("items").findValues("batchId").stream().map(JsonNode::asLong).filter(id -> !id.equals(parent)).findFirst().orElseThrow();
        Party ra = retailer("RA");
        handOver(receiverSession, receiverSite.getId(), child, "480", ra);

        // 加工企业召回 P：正向圈定 C（RA 持有）→ 通知持有方，C 保持 NORMAL（跨组织不代为冻结 / 召回）
        JsonNode recall = expect(postJson(processorQm, "/api/v1/recalls", key("idem-rc"),
                new RecallCreateRequest(List.of(parent), "加工批次检验不合格，模拟召回", null)), 201).get("data");
        long recallId = recall.get("id").asLong();
        JsonNode notified = null;
        for (JsonNode row : recall.get("scope")) {
            if (row.get("batchId").asLong() == child) {
                notified = row;
            }
        }
        assertThat(notified).as("C 在召回正向范围内").isNotNull();
        assertThat(notified.get("action").asString()).isEqualTo("NOTIFY_HOLDER");
        assertThat(notified.get("holderOrgId").asLong()).isEqualTo(ra.org().getId());
        assertThat(riskStatus(child)).isEqualTo("NORMAL");
        expect(getReq(ra.qm(), "/api/v1/recalls/" + recallId), 200);

        // 通知期间 C 仍为 NORMAL：RA 把 C 整批交给零售企业 RB，RB 接受后成为 C 的当前责任组织
        Party rb = retailer("RB");
        handOver(ra.operator(), ra.store().getId(), child, "480", rb);

        MvcResult rbList = perform(getReq(rb.qm(), "/api/v1/recalls"));
        MvcResult rbDetail = perform(getReq(rb.qm(), "/api/v1/recalls/" + recallId));
        MvcResult raDetail = perform(getReq(ra.qm(), "/api/v1/recalls/" + recallId));
        boolean listedForRb = false;
        for (JsonNode r : objectMapper.readTree(rbList.getResponse().getContentAsString()).path("data")) {
            listedForRb |= r.path("id").asLong() == recallId;
        }
        boolean rbSeesChildRow = false;
        if (status(rbDetail) == 200) {
            for (JsonNode row : objectMapper.readTree(rbDetail.getResponse().getContentAsString()).path("data").path("scope")) {
                rbSeesChildRow |= row.path("batchId").asLong() == child;
            }
        }
        // 现状对照：证据按批次判定——RB 已可引用该召回作为 NORMAL 批次紧急召回的证据
        int descendantEvidence = count("SELECT count(*) FROM recall_batch WHERE batch_id = ? AND scope_role = 'DESCENDANT'", child);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(listedForRb).as("RB（C 的当前责任组织）的召回列表必须包含该召回（响应：%s）", describe(rbList)).isTrue();
        softly.assertThat(status(rbDetail)).as("RB 必须能查看该召回（响应：%s）", describe(rbDetail)).isEqualTo(200);
        softly.assertThat(rbSeesChildRow).as("RB 查看时必须包含其当前持有的批次 C 的范围行").isTrue();
        softly.assertThat(status(raDetail)).as("RA（历史持有方）保留自己的历史记录（响应：%s）", describe(raDetail)).isEqualTo(200);
        softly.assertThat(descendantEvidence).as("召回证据按批次判定（RB 已可据此发起紧急召回）").isEqualTo(1);
        softly.assertAll();

        // ---------------------------------------------------------------- 通知随批次：查看关系、字段过滤与批次风险事项
        // RB 作为新的当前责任组织风险冻结 C：这是 RB 的内部当前事实，历史持有方 RA 不得看到
        freeze(rb.qm(), child, "收到上游模拟召回通知，冻结调查");
        JsonNode rbView = expect(getReq(rb.qm(), "/api/v1/recalls/" + recallId), 200).get("data");
        JsonNode raView = expect(getReq(ra.qm(), "/api/v1/recalls/" + recallId), 200).get("data");
        JsonNode ownerView = expect(getReq(processorQm, "/api/v1/recalls/" + recallId), 200).get("data");

        assertThat(rbView.get("viewerRelation").asString()).isEqualTo("CURRENT_HOLDER");
        assertThat(rbView.get("scope")).hasSize(1);
        JsonNode rbRow = rbView.get("scope").get(0);
        assertThat(rbRow.get("batchId").asLong()).isEqualTo(child);
        assertThat(rbRow.get("heldByViewer").asBoolean()).isTrue();
        assertThat(rbRow.get("currentRiskStatus").asString()).isEqualTo("FROZEN");
        assertThat(rbRow.get("currentFlowStatus").asString()).isEqualTo("ACTIVE");
        assertThat(rbRow.get("holderOrgId").asLong()).as("发起时快照仍记录当时的持有方").isEqualTo(ra.org().getId());
        assertThat(rbView.has("resultSummary")).isFalse();

        assertThat(raView.get("viewerRelation").asString()).isEqualTo("HISTORICAL_HOLDER");
        assertThat(raView.get("scope")).hasSize(1);
        JsonNode raRow = raView.get("scope").get(0);
        assertThat(raRow.get("heldByViewer").asBoolean()).isFalse();
        assertThat(raRow.has("currentRiskStatus")).as("历史持有方看不到新责任组织的当前风险状态").isFalse();
        assertThat(raRow.has("currentFlowStatus")).as("历史持有方看不到新责任组织的当前流转状态").isFalse();
        assertThat(raRow.get("riskStatusBefore").asString()).as("只看发起时快照").isEqualTo("NORMAL");
        assertThat(raView.toString()).doesNotContain("FROZEN");

        assertThat(ownerView.get("viewerRelation").asString()).isEqualTo("OWNER");
        assertThat(ownerView.get("scope").size()).isGreaterThan(1);

        JsonNode rbListed = null;
        for (JsonNode r : expect(getReq(rb.qm(), "/api/v1/recalls"), 200).get("data")) {
            if (r.get("id").asLong() == recallId) {
                rbListed = r;
            }
        }
        assertThat(rbListed).isNotNull();
        assertThat(rbListed.get("viewerRelation").asString()).isEqualTo("CURRENT_HOLDER");
        JsonNode raListed = null;
        for (JsonNode r : expect(getReq(ra.qm(), "/api/v1/recalls"), 200).get("data")) {
            if (r.get("id").asLong() == recallId) {
                raListed = r;
            }
        }
        assertThat(raListed).isNotNull();
        assertThat(raListed.get("viewerRelation").asString()).isEqualTo("HISTORICAL_HOLDER");

        // 无关组织：看不到
        Party rc = retailer("RC");
        expectProblem(getReq(rc.qm(), "/api/v1/recalls/" + recallId), 403, "ORG_SCOPE_DENIED");
        assertThat(expect(getReq(rc.qm(), "/api/v1/recalls"), 200).get("data")).isEmpty();

        // 批次风险事项：通知属于批次，只对当前责任组织输出
        JsonNode holds = expect(getReq(rb.operator(), "/api/v1/batches/" + child + "/risk-holds"), 200).get("data");
        assertThat(holds.get("riskStatus").asString()).isEqualTo("FROZEN");
        assertThat(holds.get("manualFreezeHold").asBoolean()).isTrue();
        assertThat(holds.get("alertHolds")).isEmpty();
        assertThat(holds.get("recallNotices")).hasSize(1);
        assertThat(holds.get("recallNotices").get(0).get("recallId").asLong()).isEqualTo(recallId);
        assertThat(holds.get("recallNotices").get(0).get("ownerOrgId").asLong()).isEqualTo(receiverOrg.getId());
        expectProblem(getReq(ra.qm(), "/api/v1/batches/" + child + "/risk-holds"), 403, "ORG_SCOPE_DENIED");
        expectProblem(getReq(processorQm, "/api/v1/batches/" + child + "/risk-holds"), 403, "ORG_SCOPE_DENIED");
    }
}
