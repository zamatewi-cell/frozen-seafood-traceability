package com.example.traceability.trace;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.dto.BatchCreateRequest;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.trace.dto.CorrectTraceEventRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase B PB1 批次风险状态核心真实 MySQL 8.4 端到端集成测试（统一业务契约 v1.1 §4.2 / §4.3 / §5.9 / §10.2 / §13 / §14）。
 * <p>
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 控制。批次全部经真实 API 获得，风险状态全部经真实 PB1 冻结 / 解除接口改变；
 * 覆盖：转换矩阵与不变量（只改风险状态与版本）、ACTIVE / CLOSED（售罄、被操作消耗）转换、不向谱系传播、
 * 冻结期间复用 Phase A 守卫（交接创建 / 绑定 / 提交 / 接受、批次操作创建 / 提交、仓储、更正、首次激活公开码、销售），
 * 发运 / 到达与 PENDING+DELIVERED 拒收不受影响、查询与公开投影可用、权限、历史读取范围（当前 / 平台 / 历史参与 / 无关）、
 * 幂等与通用实体更新无法覆盖风险状态。确定性竞态见 {@link BatchRiskRaceMysqlIntegrationTest}。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("批次风险状态核心 MySQL 8.4 端到端集成测试（人工 FROZEN ⇄ NORMAL）")
class BatchRiskMysqlIntegrationTest extends AbstractBatchRiskMysqlIT {

    private MockHttpSession senderQm() throws Exception {
        return newSession(senderOrg, "snd_qm", QM_ROLE, "OWN_ORG");
    }

    private MockHttpSession receiverQm() throws Exception {
        return newSession(receiverOrg, "rcv_qm", QM_ROLE, "OWN_ORG");
    }

    private Long userId(String usernamePrefix) {
        return jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, usernamePrefix + "_" + suffix);
    }

    private Long createDraftSourceBatch() throws Exception {
        BatchCreateRequest req = new BatchCreateRequest("PB1-DRAFT-" + suffix, testProduct.getId(), new BigDecimal("50.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山渔场", LocalDate.now(), null, null, 180);
        Long id = expect(postJson(senderSession, "/api/v1/batches", key("idem-bat-d"), req), 201).get("data").get("id").asLong();
        createdBatchIds.add(id);
        return id;
    }

    /** BIGINT UNSIGNED 列经 JDBC 返回 BigInteger，统一按 long 比较。 */
    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    private static Map<String, Object> withoutVersionAndRisk(Map<String, Object> row) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(row);
        copy.remove("version");
        copy.remove("risk_status");
        return copy;
    }

    // =========================================================================
    // 转换与不变量
    // =========================================================================

    @Test
    @DisplayName("ACTIVE 来源批次：冻结 → 解除；只改 risk_status 与版本，台账 / 审计 / 批次同一事务一致，不生成任何 TraceEvent（含速冻 FREEZE）")
    void freezeAndRelease_activeBatch_onlyRiskChanges() throws Exception {
        MockHttpSession qm = senderQm();
        Long qmUserId = userId("snd_qm");
        Long b = createAndSubmitActiveBatch(senderSession, "PB1-A-" + suffix, new BigDecimal("1000.000"));
        Map<String, Object> before = batchRow(b);
        List<Integer> effectsBefore = businessSideEffects(b);

        JsonNode frozen = freeze(qm, b, "  来料抽检异常，等待复检  ");
        assertThat(frozen.get("batchId").asLong()).isEqualTo(b);
        assertThat(frozen.get("orgId").asLong()).isEqualTo(senderOrg.getId());
        assertThat(frozen.get("flowStatus").asString()).isEqualTo("ACTIVE");
        assertThat(frozen.get("fromStatus").asString()).isEqualTo("NORMAL");
        assertThat(frozen.get("toStatus").asString()).isEqualTo("FROZEN");
        assertThat(frozen.get("sourceType").asString()).isEqualTo("MANUAL");
        assertThat(frozen.get("reason").asString()).isEqualTo("来料抽检异常，等待复检");
        assertThat(frozen.get("actorUserId").asLong()).isEqualTo(qmUserId);
        assertThat(frozen.has("idempotencyKey")).isFalse();
        assertThat(frozen.has("requestHash")).isFalse();
        OffsetDateTime occurredAt = OffsetDateTime.parse(frozen.get("occurredAt").asString());
        assertThat(occurredAt).isBetween(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1));

        Map<String, Object> afterFreeze = batchRow(b);
        assertThat(afterFreeze.get("risk_status")).isEqualTo("FROZEN");
        assertThat(((Number) afterFreeze.get("version")).longValue()).isEqualTo(((Number) before.get("version")).longValue() + 1);
        assertThat(withoutVersionAndRisk(afterFreeze)).as("quantity / org / flow / identity unchanged").isEqualTo(withoutVersionAndRisk(before));
        assertThat(businessSideEffects(b)).as("no TraceEvent / transfer / relation / sale / code side effects").isEqualTo(effectsBefore);
        assertThat(countEvents(b, "FREEZE")).isZero();

        Map<String, Object> ledger = jdbcTemplate.queryForMap("SELECT * FROM batch_risk_transition WHERE batch_id = ?", b);
        assertThat(asLong(ledger.get("org_id"))).isEqualTo(senderOrg.getId());
        assertThat(ledger.get("flow_status")).isEqualTo("ACTIVE");
        assertThat(asLong(ledger.get("actor_user_id"))).isEqualTo(qmUserId);
        assertThat((String) ledger.get("request_hash")).matches("[0-9a-f]{64}");
        assertThat(ledger.get("occurred_at")).isEqualTo(occurredAt.toLocalDateTime());
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT actor_user_id, actor_org_id, result, change_summary_json FROM audit_log WHERE object_type = 'BATCH' AND object_id = ? AND action = 'RISK_FREEZE'", b);
        assertThat(asLong(audit.get("actor_user_id"))).isEqualTo(qmUserId);
        assertThat(asLong(audit.get("actor_org_id"))).isEqualTo(senderOrg.getId());
        assertThat(audit.get("result")).isEqualTo("SUCCESS");
        JsonNode summary = objectMapper.readTree(String.valueOf(audit.get("change_summary_json")));
        assertThat(summary.get("transitionId").asLong()).isEqualTo(frozen.get("id").asLong());
        assertThat(summary.get("toStatus").asString()).isEqualTo("FROZEN");

        JsonNode view = expect(getReq(senderSession, "/api/v1/batches/" + b), 200).get("data");
        assertThat(view.get("riskStatus").asString()).isEqualTo("FROZEN");
        assertThat(view.get("flowStatus").asString()).isEqualTo("ACTIVE");

        JsonNode released = release(qm, b, "复检合格，解除冻结");
        assertThat(released.get("fromStatus").asString()).isEqualTo("FROZEN");
        assertThat(released.get("toStatus").asString()).isEqualTo("NORMAL");
        Map<String, Object> afterRelease = batchRow(b);
        assertThat(afterRelease.get("risk_status")).isEqualTo("NORMAL");
        assertThat(((Number) afterRelease.get("version")).longValue()).isEqualTo(((Number) before.get("version")).longValue() + 2);
        assertThat(withoutVersionAndRisk(afterRelease)).isEqualTo(withoutVersionAndRisk(before));
        assertThat(businessSideEffects(b)).isEqualTo(effectsBefore);
        assertThat(ledgerRows(b)).isEqualTo(2);
        assertLedgerConsistent(b);

        JsonNode history = history(qm, b);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("toStatus").asString()).isEqualTo("FROZEN");
        assertThat(history.get(1).get("toStatus").asString()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("CLOSED（售罄）批次：冻结为 CLOSED/FROZEN、解除为 CLOSED/NORMAL，从不重新打开；冻结期间终端销售 422 且零写入")
    void retailer_saleGuard_andClosedBySale() throws Exception {
        useRetailerReceiver();
        MockHttpSession qm = receiverQm();
        Long b = receiverHeldBatch("PB1-S-" + suffix, new BigDecimal("600.000"));

        freeze(qm, b, "门店抽检待定");
        expectProblem(saleRequest(receiverSession, b, receiverSite.getId(), "200", key("idem-sale")), 422, "BATCH_FLOW_BLOCKED");
        assertThat(count("SELECT count(*) FROM sale WHERE batch_id = ?", b)).isZero();
        release(qm, b, "抽检合格");

        expect(saleRequest(receiverSession, b, receiverSite.getId(), "600", key("idem-sale")), 201);
        Map<String, Object> closed = batchRow(b);
        assertThat(closed.get("flow_status")).isEqualTo("CLOSED");

        JsonNode frozen = freeze(qm, b, "售罄后历史风险调查");
        assertThat(frozen.get("flowStatus").asString()).isEqualTo("CLOSED");
        Map<String, Object> closedFrozen = batchRow(b);
        assertThat(closedFrozen.get("flow_status")).isEqualTo("CLOSED");
        assertThat(closedFrozen.get("risk_status")).isEqualTo("FROZEN");
        assertThat(closedFrozen.get("first_sale_id")).isEqualTo(closed.get("first_sale_id"));
        assertThat(withoutVersionAndRisk(closedFrozen)).isEqualTo(withoutVersionAndRisk(closed));

        release(qm, b, "调查结束");
        Map<String, Object> closedNormal = batchRow(b);
        assertThat(closedNormal.get("flow_status")).isEqualTo("CLOSED");
        assertThat(closedNormal.get("risk_status")).isEqualTo("NORMAL");
        assertThat(ledgerRows(b)).isEqualTo(4);
        assertLedgerConsistent(b);
    }

    @Test
    @DisplayName("被批次操作全量消耗的 CLOSED 输入与其输出：各自独立冻结 / 解除，不向祖先、后代或同源批次传播")
    void consumedInput_andOutputs_noPropagation() throws Exception {
        MockHttpSession qm = receiverQm();
        Long b0 = processorHeldBatch("PB1-B0-" + suffix, new BigDecimal("1000.000"));
        JsonNode process = createAndSubmitOperation("PROCESS", List.of(opInput(b0, "1000.000"), opOutput("990.000"), opOther("LOSS", "10.000")));
        Long b1 = outputs(process).get(0);
        JsonNode split = createAndSubmitOperation("SPLIT", List.of(opInput(b1, "990.000"), opOutput("600.000"), opOutput("390.000")));
        List<Long> children = outputs(split);
        Long b2 = children.get(0);
        Long b3 = children.get(1);
        assertThat(batchRow(b1).get("flow_status")).isEqualTo("CLOSED");

        JsonNode f1 = freeze(qm, b1, "加工批次历史风险调查");
        assertThat(f1.get("flowStatus").asString()).isEqualTo("CLOSED");
        freeze(qm, b2, "拆分批次抽检");
        assertThat(riskStatus(b0)).isEqualTo("NORMAL");
        assertThat(riskStatus(b1)).isEqualTo("FROZEN");
        assertThat(riskStatus(b2)).isEqualTo("FROZEN");
        assertThat(riskStatus(b3)).as("sibling untouched").isEqualTo("NORMAL");
        assertThat(ledgerRows(b0)).isZero();
        assertThat(ledgerRows(b3)).isZero();
        assertThat(batchRow(b1).get("flow_status")).as("never reopened").isEqualTo("CLOSED");

        release(qm, b1, "调查结束");
        release(qm, b2, "复检合格");
        assertThat(riskStatus(b1)).isEqualTo("NORMAL");
        assertThat(riskStatus(b2)).isEqualTo("NORMAL");
        for (Long id : List.of(b0, b1, b2, b3)) {
            assertLedgerConsistent(id);
        }
    }

    private static List<Long> outputs(JsonNode operation) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : operation.get("items")) {
            if ("OUTPUT".equals(item.get("role").asString())) {
                ids.add(item.get("batchId").asLong());
            }
        }
        ids.sort(Long::compareTo);
        return ids;
    }

    @Test
    @DisplayName("DRAFT 不参与（409）；方向不符 409；RECALLED 为终态（ACTIVE / CLOSED 均 409）；已删除 404；失败不写台账与审计")
    void draftRecalledDeletedAndWrongDirection() throws Exception {
        MockHttpSession qm = senderQm();
        Long draft = createDraftSourceBatch();
        expectProblem(freezeRequest(qm, draft, "草稿抽检", key("idem-risk-f")), 409, "INVALID_STATE_TRANSITION");
        expectProblem(releaseRequest(qm, draft, "草稿解除", key("idem-risk-r")), 409, "INVALID_STATE_TRANSITION");

        Long b = createAndSubmitActiveBatch(senderSession, "PB1-D-" + suffix, new BigDecimal("100.000"));
        expectProblem(releaseRequest(qm, b, "未冻结不能解除", key("idem-risk-r")), 409, "INVALID_STATE_TRANSITION");
        freeze(qm, b, "抽检");
        expectProblem(freezeRequest(qm, b, "重复冻结", key("idem-risk-f")), 409, "INVALID_STATE_TRANSITION");
        release(qm, b, "复检合格");

        Long recalled = createAndSubmitActiveBatch(senderSession, "PB1-R-" + suffix, new BigDecimal("100.000"));
        // RECALLED 由 PB5 写入；此处仅以 SQL 夹具验证 PB1 不能离开该终态
        jdbcTemplate.update("UPDATE batch SET risk_status = 'RECALLED' WHERE id = ?", recalled);
        expectProblem(freezeRequest(qm, recalled, "冻结召回批次", key("idem-risk-f")), 409, "INVALID_STATE_TRANSITION");
        expectProblem(releaseRequest(qm, recalled, "解除召回批次", key("idem-risk-r")), 409, "INVALID_STATE_TRANSITION");
        jdbcTemplate.update("UPDATE batch SET flow_status = 'CLOSED' WHERE id = ?", recalled);
        expectProblem(freezeRequest(qm, recalled, "冻结召回批次", key("idem-risk-f")), 409, "INVALID_STATE_TRANSITION");
        expectProblem(releaseRequest(qm, recalled, "解除召回批次", key("idem-risk-r")), 409, "INVALID_STATE_TRANSITION");
        assertThat(riskStatus(recalled)).isEqualTo("RECALLED");

        Long deleted = createAndSubmitActiveBatch(senderSession, "PB1-X-" + suffix, new BigDecimal("100.000"));
        jdbcTemplate.update("UPDATE batch SET is_deleted = 1 WHERE id = ?", deleted);
        expectProblem(freezeRequest(qm, deleted, "已删除", key("idem-risk-f")), 404, "RESOURCE_NOT_FOUND");
        expectProblem(getReq(qm, "/api/v1/batches/" + deleted + "/risk-transitions"), 404, "RESOURCE_NOT_FOUND");

        assertThat(ledgerRows(draft)).isZero();
        assertThat(ledgerRows(recalled)).isZero();
        assertThat(ledgerRows(deleted)).isZero();
        assertThat(ledgerRows(b)).isEqualTo(2);
        assertThat(auditRows(draft, "RISK_FREEZE") + auditRows(recalled, "RISK_FREEZE") + auditRows(recalled, "RISK_RELEASE")).isZero();
        assertLedgerConsistent(b);
        assertThat(riskStatus(draft)).isEqualTo("NORMAL");
    }

    // =========================================================================
    // 权限与历史读取范围
    // =========================================================================

    @Test
    @DisplayName("写权限：OPERATOR 403 ACCESS_DENIED、其他组织质量管理员 403 ORG_SCOPE_DENIED、平台管理员 403 ADMIN_RESTRICTED、匿名 401；幂等键缺失 / SYS: 前缀 400")
    void writePermissions() throws Exception {
        Long b = createAndSubmitActiveBatch(senderSession, "PB1-P-" + suffix, new BigDecimal("100.000"));
        MockHttpSession otherQm = receiverQm();
        MockHttpSession admin = newSession(senderOrg, "sys_adm", "SYSTEM_ADMIN", "PLATFORM");

        expectProblem(freezeRequest(senderSession, b, "操作员冻结", key("idem-risk-f")), 403, "ACCESS_DENIED");
        expectProblem(freezeRequest(otherQm, b, "他组织冻结", key("idem-risk-f")), 403, "ORG_SCOPE_DENIED");
        expectProblem(freezeRequest(admin, b, "平台代办", key("idem-risk-f")), 403, "ADMIN_RESTRICTED");
        expect(post("/api/v1/batches/" + b + "/risk/freeze").with(csrf()).header("Idempotency-Key", key("idem-risk-f"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"reason\":\"匿名\"}"), 401);
        MockHttpSession qm = senderQm();
        expectProblem(freezeRequest(qm, b, "缺少幂等键", null), 400, "INVALID_REQUEST");
        expectProblem(freezeRequest(qm, b, "保留前缀", "SYS:ALERT:1:BATCH:" + b), 400, "INVALID_REQUEST");
        expectProblem(freezeRequest(qm, b, "   ", key("idem-risk-f")), 400, "INVALID_REQUEST");
        assertThat(ledgerRows(b)).isZero();
        assertThat(riskStatus(b)).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("历史读取：组织 A 冻结 / 解除 → ACCEPT 转给组织 B → A 只读本组织两行（看不到 B 的转换 / 原因 / 操作人）、B 与平台读完整历史、无关组织 C 403、匿名 401；A 不能再写，但可重放自己原来的幂等请求")
    void historyReadScopes_afterAccept() throws Exception {
        MockHttpSession aQm = senderQm();
        Long b = createAndSubmitActiveBatch(senderSession, "PB1-H-" + suffix, new BigDecimal("300.000"));
        String aFreezeKey = key("idem-risk-a");
        JsonNode aFreeze = expect(freezeRequest(aQm, b, "A 组织抽检", aFreezeKey), 201).get("data");
        release(aQm, b, "A 组织复检合格");

        Handover h = prepareDeliveredHandover(b);
        expect(acceptRequest(receiverSession, h.transferId(), new BigDecimal("300.000"), key("idem-trf-acc")), 200);
        assertThat(batchOrgId(b)).isEqualTo(receiverOrg.getId());
        MockHttpSession bQm = receiverQm();
        freeze(bQm, b, "B 组织入库抽检（仅 B 可见）");

        JsonNode aView = history(aQm, b);
        assertThat(aView).hasSize(2);
        for (JsonNode row : aView) {
            assertThat(row.get("orgId").asLong()).isEqualTo(senderOrg.getId());
            assertThat(row.get("reason").asString()).startsWith("A 组织");
        }
        assertThat(aView.toString()).doesNotContain("B 组织入库抽检");
        // 按字段比较操作人：整段 JSON 的数字子串匹配会误命中时间戳等无关数字（例如用户 76 与 "…12.767652Z"）
        Long bQmUserId = userId("rcv_qm");
        for (JsonNode row : aView) {
            assertThat(row.get("actorUserId").asLong()).as("no later-org actor").isNotEqualTo(bQmUserId);
        }
        assertThat(history(senderSession, b)).as("any role of the historical org sees its own rows").hasSize(2);

        JsonNode bView = history(receiverSession, b);
        assertThat(bView).hasSize(3);
        assertThat(bView.get(2).get("orgId").asLong()).isEqualTo(receiverOrg.getId());
        MockHttpSession admin = newSession(senderOrg, "plat_adm", "SYSTEM_ADMIN", "PLATFORM");
        assertThat(history(admin, b)).hasSize(3);

        Organization orgC = createOrg("ORG_U_" + suffix, "无关企业-" + suffix, "PROCESSOR");
        MockHttpSession cQm = newSession(orgC, "c_qm", QM_ROLE, "OWN_ORG");
        expectProblem(getReq(cQm, "/api/v1/batches/" + b + "/risk-transitions"), 403, "ORG_SCOPE_DENIED");
        expect(get("/api/v1/batches/" + b + "/risk-transitions"), 401);

        // 历史参与组织不因此获得写权限
        expectProblem(freezeRequest(aQm, b, "A 组织再冻结", key("idem-risk-f")), 403, "ORG_SCOPE_DENIED");
        expectProblem(releaseRequest(aQm, b, "A 组织解除 B 的冻结", key("idem-risk-r")), 403, "ORG_SCOPE_DENIED");
        // 但可重放本组织原来创建的转换（读取自己的历史记录，不产生新写入）
        JsonNode replay = expect(freezeRequest(aQm, b, "A 组织抽检", aFreezeKey), 201).get("data");
        assertThat(replay.get("id").asLong()).isEqualTo(aFreeze.get("id").asLong());
        expectProblem(freezeRequest(aQm, b, "A 组织换了原因", aFreezeKey), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(ledgerRows(b)).isEqualTo(3);
        assertThat(riskStatus(b)).isEqualTo("FROZEN");
        assertLedgerConsistent(b);
        release(bQm, b, "B 组织复检合格");
    }

    // =========================================================================
    // 冻结期间复用 Phase A 守卫
    // =========================================================================

    @Test
    @DisplayName("加工企业冻结期间：交接创建 409、批次操作创建 422、冷库仓储 422、事件更正 422、首次激活公开码 422，且零写入；查询仍可用；解除后全部恢复")
    void processorGuardsWhileFrozen() throws Exception {
        MockHttpSession qm = receiverQm();
        Site coldStore = createSite(receiverOrg.getId(), "PRC-COLD-" + suffix, "加工冷库-" + suffix, "COLD_STORE");
        Long b = processorHeldBatch("PB1-G-" + suffix, new BigDecimal("500.000"));
        JsonNode warehouse = expect(warehouseRequest(receiverSession, b, coldStore, key("idem-evt")), 201).get("data");

        freeze(qm, b, "加工前抽检");
        List<Integer> effects = businessSideEffects(b);
        int idem = count("SELECT count(*) FROM transfer_idempotency WHERE org_id = ?", receiverOrg.getId());
        expectProblem(createTransferRequest(receiverSession, b, senderOrg.getId(), key("idem-trf-c")), 409, "BATCH_NOT_ACTIVE");
        expectProblem(processRequest(receiverSession, b, "500.000", key("idem-op-c")), 422, "BATCH_FLOW_BLOCKED");
        expectProblem(warehouseRequest(receiverSession, b, coldStore, key("idem-evt")), 422, "BATCH_FLOW_BLOCKED");
        expectProblem(postJson(receiverSession, "/api/v1/batches/" + b + "/events/" + warehouse.get("id").asLong() + "/corrections", key("idem-evt-cor"),
                new CorrectTraceEventRequest("WAREHOUSE_IN", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5), coldStore.getId(), "MANUAL",
                        "冷库入库（更正）", null, "登记时间有误")), 422, "BATCH_FLOW_BLOCKED");
        expectProblem(activateCodeRequest(receiverSession, b, key("idem-ptc")), 422, "BATCH_FLOW_BLOCKED");
        assertThat(businessSideEffects(b)).isEqualTo(effects);
        assertThat(count("SELECT count(*) FROM transfer_idempotency WHERE org_id = ?", receiverOrg.getId())).isEqualTo(idem);
        assertThat(count("SELECT count(*) FROM batch_operation WHERE org_id = ?", receiverOrg.getId())).isZero();

        // 查询不受影响
        assertThat(expect(getReq(receiverSession, "/api/v1/batches/" + b), 200).get("data").get("riskStatus").asString()).isEqualTo("FROZEN");
        expect(getReq(receiverSession, "/api/v1/batches/" + b + "/events"), 200);
        expect(getReq(receiverSession, "/api/v1/batch-operations?batchId=" + b), 200);
        assertThat(history(receiverSession, b)).hasSize(1);

        release(qm, b, "抽检合格");
        expect(warehouseRequest(receiverSession, b, coldStore, key("idem-evt")), 201);
        expect(activateCodeRequest(receiverSession, b, key("idem-ptc")), 200);
        Long transferId = expect(createTransferRequest(receiverSession, b, senderOrg.getId(), key("idem-trf-c")), 201).get("data").get("id").asLong();
        createdTransferIds.add(transferId);
        assertLedgerConsistent(b);
    }

    @Test
    @DisplayName("冻结前创建的批次操作草稿：冻结期间提交 422（操作与输出保持 DRAFT），解除后提交成功")
    void operationDraftSubmitBlockedWhileFrozen() throws Exception {
        MockHttpSession qm = receiverQm();
        Long b = processorHeldBatch("PB1-O-" + suffix, new BigDecimal("400.000"));
        JsonNode draft = expect(processRequest(receiverSession, b, "400.000", key("idem-op-c")), 201).get("data");
        registerOperation(draft);
        Long opId = draft.get("id").asLong();

        freeze(qm, b, "投料前抽检");
        expectProblem(submitOperationRequest(receiverSession, opId, draft.get("version").asLong(), key("idem-op-s")), 422, "BATCH_FLOW_BLOCKED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM batch_operation WHERE id = ?", String.class, opId)).isEqualTo("DRAFT");
        for (Long out : outputs(draft)) {
            assertThat(batchRow(out).get("flow_status")).isEqualTo("DRAFT");
            expectProblem(freezeRequest(qm, out, "草稿输出不能冻结", key("idem-risk-f")), 409, "INVALID_STATE_TRANSITION");
        }
        assertThat(batchRow(b).get("flow_status")).isEqualTo("ACTIVE");

        release(qm, b, "抽检合格");
        expect(submitOperationRequest(receiverSession, opId, draft.get("version").asLong(), key("idem-op-s")), 200);
        assertThat(batchRow(b).get("flow_status")).isEqualTo("CLOSED");
        assertThat(batchRow(b).get("risk_status")).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("发送方冻结在途批次：绑定 409、提交 409；发运 / 到达保持现有定义不受影响；接受 409；PENDING+DELIVERED 拒收仍允许且批次保持 FROZEN / 发送方负责")
    void senderGuards_bindSubmitAccept_rejectAllowed() throws Exception {
        MockHttpSession qm = senderQm();
        Long b = createAndSubmitActiveBatch(senderSession, "PB1-T-" + suffix, new BigDecimal("200.000"));
        Long transferId = createDraftTransfer(senderSession, b, receiverOrg.getId());
        Long shipmentId = createShipment(senderSession);

        freeze(qm, b, "装车前抽检");
        expectProblem(bindRequest(senderSession, shipmentId, transferId, transferVersion(transferId)), 409, "BATCH_FLOW_BLOCKED");
        release(qm, b, "抽检合格");
        bind(shipmentId, transferId);

        freeze(qm, b, "提交前复检");
        expectProblem(submitRequest(senderSession, transferId, transferVersion(transferId)), 409, "BATCH_FLOW_BLOCKED");
        assertThat(transferStatus(transferId)).isEqualTo("DRAFT");
        release(qm, b, "复检合格");
        submit(transferId);

        freeze(qm, b, "运输途中风险调查");
        dispatch(shipmentId);
        arrive(shipmentId);
        assertThat(shipmentStatus(shipmentId)).isEqualTo("DELIVERED");
        expectProblem(acceptRequest(receiverSession, transferId, new BigDecimal("200.000"), key("idem-trf-acc")), 409, "BATCH_FLOW_BLOCKED");
        assertThat(transferStatus(transferId)).isEqualTo("PENDING");
        expect(rejectRequest(receiverSession, transferId, key("idem-trf-rej")), 200);
        assertThat(transferStatus(transferId)).isEqualTo("REJECTED");
        Map<String, Object> row = batchRow(b);
        assertThat(row.get("risk_status")).isEqualTo("FROZEN");
        assertThat(asLong(row.get("org_id"))).isEqualTo(senderOrg.getId());
        assertLedgerConsistent(b);
    }

    @Test
    @DisplayName("契约 §13 基线：在途 PENDING+DELIVERED 时发送方质量管理员冻结 → 接受 409 → 解除 → 接受成功；此后发送方 403、接收方质量管理员可冻结")
    void inTransitBaseline_releaseThenAccept() throws Exception {
        MockHttpSession aQm = senderQm();
        Long b = createAndSubmitActiveBatch(senderSession, "PB1-I-" + suffix, new BigDecimal("250.000"));
        Handover h = prepareDeliveredHandover(b);
        freeze(aQm, b, "运输途中持续超温疑似（人工判断）");
        expectProblem(acceptRequest(receiverSession, h.transferId(), new BigDecimal("250.000"), key("idem-trf-acc")), 409, "BATCH_FLOW_BLOCKED");
        assertThat(batchOrgId(b)).isEqualTo(senderOrg.getId());
        release(aQm, b, "调查合格");
        expect(acceptRequest(receiverSession, h.transferId(), new BigDecimal("250.000"), key("idem-trf-acc")), 200);
        assertThat(batchOrgId(b)).isEqualTo(receiverOrg.getId());
        expectProblem(freezeRequest(aQm, b, "已转出", key("idem-risk-f")), 403, "ORG_SCOPE_DENIED");
        freeze(receiverQm(), b, "接收方入库抽检");
        assertLedgerConsistent(b);
    }

    @Test
    @DisplayName("冻结期间公开投影可用（只读、零写入）：风险状态 FROZEN、流转状态不变、无召回提示；已激活码的重复激活按既有语义返回原码")
    void publicProjectionWhileFrozen() throws Exception {
        MockHttpSession qm = senderQm();
        Long b = createAndSubmitActiveBatch(senderSession, "PB1-C-" + suffix, new BigDecimal("80.000"));
        String publicId = expect(activateCodeRequest(senderSession, b, key("idem-ptc")), 200).get("data").get("publicId").asString();
        freeze(qm, b, "消费者投诉抽检");

        List<Integer> before = List.of(count("SELECT count(*) FROM batch_risk_transition"), count("SELECT count(*) FROM audit_log"),
                count("SELECT count(*) FROM trace_event"));
        long version = batchVersion(b);
        JsonNode trace = expect(get("/api/public/v1/public/traces/" + publicId), 200).get("data");
        assertThat(trace.get("riskStatus").asString()).isEqualTo("FROZEN");
        assertThat(trace.get("flowStatus").asString()).isEqualTo("ACTIVE");
        assertThat(trace.path("recallNotice").isMissingNode() || trace.path("recallNotice").isNull()).isTrue();
        assertThat(List.of(count("SELECT count(*) FROM batch_risk_transition"), count("SELECT count(*) FROM audit_log"),
                count("SELECT count(*) FROM trace_event"))).isEqualTo(before);
        assertThat(batchVersion(b)).isEqualTo(version);

        JsonNode again = expect(activateCodeRequest(senderSession, b, key("idem-ptc")), 200).get("data");
        assertThat(again.get("publicId").asString()).isEqualTo(publicId);
        assertThat(count("SELECT count(*) FROM public_trace_code WHERE batch_id = ?", b)).isEqualTo(1);

        release(qm, b, "抽检合格");
        assertThat(expect(get("/api/public/v1/public/traces/" + publicId), 200).get("data").get("riskStatus").asString()).isEqualTo("NORMAL");
    }

    // =========================================================================
    // 幂等与写入收敛
    // =========================================================================

    @Test
    @DisplayName("幂等：同键同语义重放原转换（一行台账、一条审计）；状态已被改变后重放仍成功；同键不同原因 / 冻结键用于解除 / 同键不同批次 409")
    void idempotency() throws Exception {
        MockHttpSession qm = senderQm();
        Long b = createAndSubmitActiveBatch(senderSession, "PB1-K-" + suffix, new BigDecimal("100.000"));
        Long other = createAndSubmitActiveBatch(senderSession, "PB1-K2-" + suffix, new BigDecimal("100.000"));
        String k1 = key("idem-risk-k1");

        JsonNode first = expect(freezeRequest(qm, b, "抽检", k1), 201).get("data");
        JsonNode replay = expect(freezeRequest(qm, b, " 抽检 ", k1), 201).get("data");
        assertThat(replay).isEqualTo(first);
        assertThat(ledgerRows(b)).isEqualTo(1);
        assertThat(auditRows(b, "RISK_FREEZE")).isEqualTo(1);

        expectProblem(freezeRequest(qm, b, "另一个原因", k1), 409, "IDEMPOTENCY_CONFLICT");
        expectProblem(releaseRequest(qm, b, "抽检", k1), 409, "IDEMPOTENCY_CONFLICT");

        release(qm, b, "复检合格");
        JsonNode afterChange = expect(freezeRequest(qm, b, "抽检", k1), 201).get("data");
        assertThat(afterChange.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(riskStatus(b)).as("replay after the state changed does not re-freeze").isEqualTo("NORMAL");

        expectProblem(freezeRequest(qm, other, "抽检", k1), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(riskStatus(other)).isEqualTo("NORMAL");
        assertThat(ledgerRows(other)).isZero();
        assertThat(ledgerRows(b)).isEqualTo(2);
        assertThat(auditRows(b, "RISK_FREEZE")).isEqualTo(1);
        assertLedgerConsistent(b);
    }

    @Test
    @DisplayName("写入收敛 MySQL 实证：普通实体更新 BatchMapper.updateById / update(entity, wrapper) 真实执行，但无法覆盖 risk_status")
    void entityUpdatesCannotOverwriteRiskStatus() throws Exception {
        MockHttpSession qm = senderQm();
        Long b = createAndSubmitActiveBatch(senderSession, "PB1-E-" + suffix, new BigDecimal("100.000"));
        freeze(qm, b, "抽检");

        Batch loaded = batchMapper.selectById(b);
        assertThat(loaded.getRiskStatus()).isEqualTo("FROZEN");
        long versionBefore = loaded.getVersion();
        loaded.setRiskStatus("NORMAL");
        loaded.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        assertThat(batchMapper.updateById(loaded)).as("the generic entity update really executed").isEqualTo(1);
        assertThat(batchVersion(b)).isEqualTo(versionBefore + 1);
        assertThat(riskStatus(b)).isEqualTo("FROZEN");

        Batch again = batchMapper.selectById(b);
        again.setRiskStatus("RECALLED");
        assertThat(batchMapper.update(again, new LambdaUpdateWrapper<Batch>().eq(Batch::getId, b))).isEqualTo(1);
        assertThat(riskStatus(b)).isEqualTo("FROZEN");

        release(qm, b, "复检合格");
        assertThat(riskStatus(b)).isEqualTo("NORMAL");
        assertLedgerConsistent(b);
    }
}
