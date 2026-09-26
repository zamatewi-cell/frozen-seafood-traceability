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

    /** 同一运输任务的一个批次 B，先后两个越界片段形成两个未处置告警 A1、A2（均已确认）。 */
    private record TwoAlerts(Manifest manifest, Long batchId, Long a1, Long a2) {
    }

    /**
     * 允许越界时长 0 的运输规则下：越界（片段 1 → A1，自动冻结 B）→ 回到范围内（片段结束）→ 再次越界（片段 2 → A2，B 已冻结只快照）
     * → 承运商确认到达 → 发货方质量管理员确认 A1、A2。交接仍为 PENDING，B 仍由发货方负责。
     */
    private TwoAlerts twoOpenAlertsOnOneBatch(String tag) throws Exception {
        publishTransportRule(testProduct.getId(), "-25.00", "-15.00", LONG_AGO, null, 0);
        Manifest m = inTransitManifest(tag, LocalDateTime.now(ZoneOffset.UTC).minusHours(2), "500");
        LocalDateTime t0 = m.loadedAt();
        recordAt(m.shipmentId(), t0.plusMinutes(5), "-10.00");
        recordAt(m.shipmentId(), t0.plusMinutes(10), "-18.00");
        recordAt(m.shipmentId(), t0.plusMinutes(15), "-10.00");
        arriveAt(m.shipmentId(), t0.plusMinutes(20));
        List<Long> alerts = alertRows(m.shipmentId()).stream().map(r -> ((Number) r.get("id")).longValue()).toList();
        assertThat(alerts).as("两个越界片段各形成一个告警").hasSize(2);
        Long batchId = m.batch(0);
        assertThat(riskStatus(batchId)).isEqualTo("FROZEN");
        assertThat(count("SELECT count(*) FROM alert_batch WHERE alert_id = ? AND batch_id = ? AND risk_status_before = 'NORMAL' "
                + "AND freeze_transition_id IS NOT NULL", alerts.get(0), batchId)).as("A1 自动冻结 B").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM alert_batch WHERE alert_id = ? AND batch_id = ? AND risk_status_before = 'FROZEN' "
                + "AND freeze_transition_id IS NULL", alerts.get(1), batchId)).as("A2 创建时 B 已冻结，只快照").isEqualTo(1);
        acknowledge(alerts.get(0));
        acknowledge(alerts.get(1));
        return new TwoAlerts(m, batchId, alerts.get(0), alerts.get(1));
    }

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
    }
}
