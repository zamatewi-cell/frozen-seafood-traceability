package com.example.traceability.trace;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.dto.BatchCreateRequest;
import com.example.traceability.batch.dto.BatchOperationCreateRequest;
import com.example.traceability.batch.dto.BatchOperationItemRequest;
import com.example.traceability.batch.dto.BatchSubmitRequest;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.trace.domain.PublicTraceCode;
import com.example.traceability.trace.domain.PublicTraceIdGenerator;
import com.example.traceability.trace.dto.CorrectTraceEventRequest;
import com.example.traceability.trace.dto.CreateTraceEventRequest;
import com.example.traceability.trace.dto.TransferAcceptRequest;
import com.example.traceability.trace.mapper.PublicTraceCodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase A Slice 6：PublicTraceCode 与消费者全链公开投影的真实 MySQL 8.4 集成测试。
 * <p>
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 控制。黄金链全部通过真实 API 建立（来源建批 → S0 → 加工接受 → PROCESS →
 * FREEZE 与更正 → SPLIT → 自有冷库出入库 → T2 / T3 装入同一 S1 → 零售接受 → 激活公开码 → 终端销售售罄），
 * 然后以匿名消费者身份查询 B2 / B3：只向上聚合祖先、兄弟互不泄露、有效事件、稳定排序、白名单字段、无写入。
 * MERGE 菱形、深链、等时排序与谱系完整性失败用例通过 JDBC 直接种子数据（MERGE 在 Phase A 未开放），
 * 只读验证投影查询，不伪造任何业务写入路径。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("Slice 6 公开追溯全链 MySQL 8.4 集成测试")
class PublicTraceChainMysqlIntegrationTest extends AbstractTransferShipmentMysqlIT {

    private static final String PUBLIC_URL = "/api/public/v1/public/traces/";

    @Autowired
    private PublicTraceCodeMapper publicTraceCodeMapper;

    private Organization retailerOrg;
    private AppUser retailerUser;
    private MockHttpSession retailerSession;
    private Site retailerStore;
    private Site processorColdStore;

    // 哨兵字符串：任何一个出现在匿名响应中都表示泄露
    private String sentinelOrigin;
    private String sentinelExternal;
    private String sentinelFreezeSummary;
    private String sentinelFreezeDetails;
    private String sentinelCorrectionReason;
    private String sentinelCorrectedSummary;
    private String sentinelWarehouseB2;
    private String sentinelWarehouseB3;
    private String sentinelStoreName;
    private String sentinelColdStoreName;
    private final List<String> usedKeys = new ArrayList<>();

    private static final LocalDateTime FREEZE_ORIGINAL_TIME = LocalDateTime.of(2020, 1, 2, 3, 4, 5);

    @BeforeEach
    void setUpRetailer() throws Exception {
        sentinelOrigin = "SENTINELORIGIN" + suffix;
        sentinelExternal = "SENTINELEXTNO" + suffix;
        sentinelFreezeSummary = "SENTINELFREEZESUMMARY" + suffix;
        sentinelFreezeDetails = "SENTINELFREEZEDETAILS" + suffix;
        sentinelCorrectionReason = "SENTINELCORRECTIONREASON" + suffix;
        sentinelCorrectedSummary = "SENTINELCORRECTEDSUMMARY" + suffix;
        sentinelWarehouseB2 = "SENTINELWAREHOUSEB2" + suffix;
        sentinelWarehouseB3 = "SENTINELWAREHOUSEB3" + suffix;
        sentinelStoreName = "SENTINELSTORE" + suffix;
        sentinelColdStoreName = "SENTINELCOLDSTORE" + suffix;

        retailerOrg = createOrg("ORG_RT6_" + suffix, "零售企业-" + suffix, "RETAILER");
        retailerUser = createUser(retailerOrg.getId(), "ret6_op_" + suffix);
        bindUserRole(retailerUser.getId(), operatorRole.getId());
        retailerStore = createSite(retailerOrg.getId(), "RT6-STORE-" + suffix, sentinelStoreName, "STORE");
        retailerSession = login(retailerUser.getUsername());
        processorColdStore = createSite(receiverOrg.getId(), "PRC6-COLD-" + suffix, sentinelColdStoreName, "COLD_STORE");
    }

    /** 黄金链上的批次与单据 ID。 */
    private record Chain(Long b0, Long b1, Long b2, Long b3, String b2Code, String b3Code, Long s1) {
    }

    // =========================================================================
    // 黄金链
    // =========================================================================

    @Test
    @DisplayName("黄金链：B2 = B0 → B1 → B2、B3 = B0 → B1 → B3；兄弟互不泄露；有效事件；CLOSED/NORMAL；温度数据不足；声明可见；两次查询完全一致")
    void goldenChainConsumerProjection() throws Exception {
        Chain c = buildChain();

        // ---------------- B2 ----------------
        JsonNode b2Body = anonymousGet(c.b2Code(), 200);
        JsonNode b2 = b2Body.get("data");
        assertThat(b2.get("publicTraceId").asString()).isEqualTo(c.b2Code());
        assertThat(b2.get("flowStatus").asString()).isEqualTo("CLOSED");
        assertThat(b2.get("riskStatus").asString()).isEqualTo("NORMAL");
        assertThat(b2.has("recallNotice")).isFalse();
        assertThat(b2.get("temperatureSummary").get("result").asString()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(b2.get("disclosure").asString()).contains("不作为货物物理真实性或防伪验证凭证");

        assertThat(nodes(b2)).containsExactly("N1:0:ORIGIN", "N2:1:INTERMEDIATE", "N3:2:TARGET");
        assertThat(edges(b2)).containsExactly("N1>N2:PROCESS", "N2>N3:SPLIT");
        assertThat(timeline(b2)).containsExactly(
                "N1:SOURCE", "N1:TRANSPORT", "N1:ARRIVAL",
                "N2:PROCESS", "N2:FREEZE",
                "N3:WAREHOUSE_IN", "N3:WAREHOUSE_OUT", "N3:TRANSPORT", "N3:ARRIVAL", "N3:SALE", "N3:SALE");
        assertNonDecreasingOccurredAt(b2);
        assertThat(timeline(b2)).noneMatch(t -> t.endsWith(":PACK"));
        assertThat(timeline(b2)).filteredOn(t -> t.endsWith(":PROCESS")).hasSize(1);

        // ---------------- B3：只含 B0 → B1 → B3，B2 不进入 ----------------
        JsonNode b3 = anonymousGet(c.b3Code(), 200).get("data");
        assertThat(b3.get("flowStatus").asString()).isEqualTo("CLOSED");
        assertThat(nodes(b3)).containsExactly("N1:0:ORIGIN", "N2:1:INTERMEDIATE", "N3:2:TARGET");
        assertThat(timeline(b3)).containsExactly(
                "N1:SOURCE", "N1:TRANSPORT", "N1:ARRIVAL",
                "N2:PROCESS", "N2:FREEZE",
                "N3:WAREHOUSE_IN", "N3:WAREHOUSE_OUT", "N3:TRANSPORT", "N3:ARRIVAL", "N3:SALE");
        assertThat(b3.toString()).doesNotContain(c.b2Code()).doesNotContain(sentinelWarehouseB2);
        assertThat(b2.toString()).doesNotContain(c.b3Code()).doesNotContain(sentinelWarehouseB3);

        // ---------------- 更正事件：只显示生效版本 ----------------
        List<JsonNode> freezes = new ArrayList<>();
        b2.get("timeline").forEach(i -> {
            if ("FREEZE".equals(i.get("eventType").asString())) {
                freezes.add(i);
            }
        });
        assertThat(freezes).hasSize(1);
        assertThat(b2Body.toString()).doesNotContain("2020-01-02T03:04:05");

        // ---------------- 稳定：同一数据库状态两次查询除 queriedAt 外完全一致 ----------------
        JsonNode again = anonymousGet(c.b2Code(), 200).get("data");
        assertThat(withoutQueriedAt(again)).isEqualTo(withoutQueriedAt(b2));
    }

    @Test
    @DisplayName("隐私：匿名响应只含白名单字段，内部 ID / 批号 / 单号 / 幂等键 / 场所 / 组织 / 摘要 / 扩展属性 / 更正原因 / 用户名全部不出现")
    void anonymousResponseLeaksNoPrivateValues() throws Exception {
        Chain c = buildChain();
        for (String code : List.of(c.b2Code(), c.b3Code())) {
            MvcResult r = perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(PUBLIC_URL + code));
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            String body = r.getResponse().getContentAsString();
            PublicTraceJsonWhitelist.assertOnlyWhitelistedKeys(objectMapper.readTree(body));

            List<String> forbidden = new ArrayList<>(List.of(
                    sentinelOrigin, sentinelExternal, sentinelFreezeSummary, sentinelFreezeDetails, sentinelCorrectionReason,
                    sentinelCorrectedSummary, sentinelWarehouseB2, sentinelWarehouseB3, sentinelStoreName, sentinelColdStoreName,
                    senderOrg.getName(), receiverOrg.getName(), carrierOrg.getName(), retailerOrg.getName(),
                    senderSite.getName(), receiverSite.getName(),
                    senderUser.getUsername(), receiverUser.getUsername(), carrierUser.getUsername(), retailerUser.getUsername(),
                    "浙A-" + suffix, "SYS:", "TB-", "detailsJson", "summary", "sourceObject", "idempotency", "operatorId",
                    "orgId", "siteId", "batchId", "shipmentId", "transferId", "saleId", "correction", "tokenHash", "token_hash"));
            forbidden.addAll(usedKeys);
            for (Long b : List.of(c.b0(), c.b1(), c.b2(), c.b3())) {
                forbidden.add(jdbcTemplate.queryForObject("SELECT trace_batch_no FROM batch WHERE id = ?", String.class, b));
            }
            forbidden.addAll(jdbcTemplate.queryForList("SELECT shipment_no FROM shipment WHERE sender_org_id IN (?, ?)", String.class,
                    senderOrg.getId(), receiverOrg.getId()));
            forbidden.addAll(jdbcTemplate.queryForList("SELECT transfer_no FROM transfer WHERE batch_id IN (?, ?, ?)", String.class,
                    c.b0(), c.b2(), c.b3()));
            forbidden.addAll(jdbcTemplate.queryForList("SELECT operation_no FROM batch_operation WHERE org_id = ?", String.class, receiverOrg.getId()));
            forbidden.addAll(jdbcTemplate.queryForList("SELECT token_hash FROM public_trace_code WHERE batch_id IN (?, ?)", String.class, c.b2(), c.b3()));
            for (String value : forbidden) {
                assertThat(body).as("匿名响应泄露了 %s", value).doesNotContain(value);
            }
        }
    }

    @Test
    @DisplayName("公开码状态语义：售罄 CLOSED 不会自动停用已激活码；当前责任组织 GET 仍返回同一 ACTIVE 码；历史参与组织 403；CLOSED 且无码的批次首次激活 422")
    void codeStatusSurvivesClosedAndEnterpriseReadScope() throws Exception {
        Chain c = buildChain();
        for (Long b : List.of(c.b2(), c.b3())) {
            assertThat(jdbcTemplate.queryForObject("SELECT flow_status FROM batch WHERE id = ?", String.class, b)).isEqualTo("CLOSED");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM public_trace_code WHERE batch_id = ?", String.class, b)).isEqualTo("ACTIVE");
            assertThat(count("SELECT count(*) FROM public_trace_code WHERE batch_id = ?", b)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT org_id FROM public_trace_code WHERE batch_id = ?", Long.class, b)).isEqualTo(retailerOrg.getId());
        }
        JsonNode current = expect(getReq(retailerSession, "/api/v1/batches/" + c.b2() + "/public-trace-code"), 200).get("data");
        assertThat(current.get("publicId").asString()).isEqualTo(c.b2Code());
        assertThat(current.get("status").asString()).isEqualTo("ACTIVE");
        // 加工企业（B2 历史参与组织）不能读取或停用已转出批次的公开码
        expectProblem(getReq(receiverSession, "/api/v1/batches/" + c.b2() + "/public-trace-code"), 403, "ORG_SCOPE_DENIED");
        expectProblem(postJson(receiverSession, "/api/v1/batches/" + c.b2() + "/public-trace-code/disable", key("idem-ptc-dis-x"), null), 403, "ORG_SCOPE_DENIED");
        // 零售企业对已激活码重复激活：幂等返回同一码（不新增、不轮换）
        JsonNode replay = expect(postJson(retailerSession, "/api/v1/batches/" + c.b2() + "/public-trace-code/activate", key("idem-ptc-again"), null), 200).get("data");
        assertThat(replay.get("publicId").asString()).isEqualTo(c.b2Code());
        assertThat(count("SELECT count(*) FROM public_trace_code WHERE batch_id = ?", c.b2())).isEqualTo(1);
        // 尚无码的 CLOSED 批次（加工企业负责的 B1）：沿用现有语义 422，不新增“关闭后激活”规则
        expectProblem(getReq(receiverSession, "/api/v1/batches/" + c.b1() + "/public-trace-code"), 404, "PUBLIC_TRACE_CODE_NOT_FOUND");
        expectProblem(postJson(receiverSession, "/api/v1/batches/" + c.b1() + "/public-trace-code/activate", key("idem-ptc-closed"), null), 422, "BATCH_FLOW_BLOCKED");
        assertThat(count("SELECT count(*) FROM public_trace_code WHERE batch_id = ?", c.b1())).isZero();
        // 匿名无法调用企业端读取接口
        expect(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/batches/" + c.b2() + "/public-trace-code"), 401);
    }

    @Test
    @DisplayName("匿名查询不产生任何写入；负向查找：随机码 / 停用码 / traceBatchNo / 其 26 位后缀 / batchId / 小写码一律 404，停用码与未知码响应一致")
    void noWritesAndLookupNegatives() throws Exception {
        Chain c = buildChain();
        Map<String, Object> before = snapshot(c);
        for (int i = 0; i < 3; i++) {
            anonymousGet(c.b2Code(), 200);
            anonymousGet(c.b3Code(), 200);
        }
        assertThat(snapshot(c)).isEqualTo(before);

        String traceBatchNo = jdbcTemplate.queryForObject("SELECT trace_batch_no FROM batch WHERE id = ?", String.class, c.b2());
        String suffix26 = traceBatchNo.substring(3);
        assertThat(suffix26).matches("^[A-Z2-7]{26}$");
        JsonNode unknown = anonymousGet(PublicTraceIdGenerator.generatePublicId(), 404);
        for (String probe : List.of(traceBatchNo, suffix26, String.valueOf(c.b2()), c.b2Code().toLowerCase())) {
            JsonNode body = anonymousGet(probe, 404);
            assertThat(body.get("code").asString()).isEqualTo("PUBLIC_TRACE_NOT_FOUND");
            assertThat(body.has("data")).isFalse();
        }

        // 停用（终态）后：与未知码不可区分
        expect(postJson(retailerSession, "/api/v1/batches/" + c.b3() + "/public-trace-code/disable", key("idem-ptc-dis"), null), 200);
        JsonNode disabled = anonymousGet(c.b3Code(), 404);
        for (String field : List.of("type", "title", "status", "code", "detail")) {
            assertThat(disabled.get(field)).as(field).isEqualTo(unknown.get(field));
        }
        // 停用终态不可恢复或轮换
        expectProblem(postJson(retailerSession, "/api/v1/batches/" + c.b3() + "/public-trace-code/activate", key("idem-ptc-react"), null), 409, "INVALID_STATE_TRANSITION");
        // 另一个批次的码不受影响
        anonymousGet(c.b2Code(), 200);
    }

    // =========================================================================
    // 交接与状态语义
    // =========================================================================

    @Test
    @DisplayName("交接保持同一公开码：来源企业激活 → Transfer ACCEPT 后加工企业读取同一 publicId，行数仍为 1，消费者投影不变；原发送方不能读取或停用")
    void transferKeepsSameCode() throws Exception {
        Long b = createAndSubmitActiveBatch(senderSession, "S6-TRF-" + suffix, new BigDecimal("100.000"));
        String code = expect(postJson(senderSession, "/api/v1/batches/" + b + "/public-trace-code/activate", key("idem-ptc-src"), null), 200)
                .get("data").get("publicId").asString();
        JsonNode beforeTransfer = anonymousGet(code, 200).get("data");

        Handover h = prepareDeliveredHandover(b);
        expect(postJson(receiverSession, "/api/v1/transfers/" + h.transferId() + "/accept", key("idem-trf-acc"),
                new TransferAcceptRequest(new BigDecimal("100.000"), "kg", OffsetDateTime.now(ZoneOffset.UTC), null, transferVersion(h.transferId()))), 200);

        JsonNode current = expect(getReq(receiverSession, "/api/v1/batches/" + b + "/public-trace-code"), 200).get("data");
        assertThat(current.get("publicId").asString()).isEqualTo(code);
        assertThat(count("SELECT count(*) FROM public_trace_code WHERE batch_id = ?", b)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT org_id FROM public_trace_code WHERE batch_id = ?", Long.class, b)).isEqualTo(receiverOrg.getId());
        expectProblem(getReq(senderSession, "/api/v1/batches/" + b + "/public-trace-code"), 403, "ORG_SCOPE_DENIED");
        expectProblem(postJson(senderSession, "/api/v1/batches/" + b + "/public-trace-code/disable", key("idem-ptc-src-dis"), null), 403, "ORG_SCOPE_DENIED");

        JsonNode afterTransfer = anonymousGet(code, 200).get("data");
        assertThat(nodes(afterTransfer)).containsExactly("N1:0:TARGET");
        assertThat(timeline(afterTransfer)).containsExactly("N1:SOURCE", "N1:TRANSPORT", "N1:ARRIVAL");
        assertThat(timeline(beforeTransfer)).containsExactly("N1:SOURCE");
    }

    @Test
    @DisplayName("种子数据 CLOSED + RECALLED：同时显示流转已关闭与模拟召回提示；公开码状态 RECALLED（将来的 Phase B 状态）仍可查询；召回提示只由批次风险状态决定")
    void closedRecalledSeededProjection() throws Exception {
        Long b = createAndSubmitActiveBatch(senderSession, "S6-RC-" + suffix, new BigDecimal("10.000"));
        String code = expect(postJson(senderSession, "/api/v1/batches/" + b + "/public-trace-code/activate", key("idem-ptc-rc"), null), 200)
                .get("data").get("publicId").asString();

        // Phase A 没有风险写入接口：仅在测试中直接写入以验证只读投影
        jdbcTemplate.update("UPDATE batch SET flow_status = 'CLOSED', risk_status = 'RECALLED' WHERE id = ?", b);
        JsonNode recalled = anonymousGet(code, 200).get("data");
        assertThat(recalled.get("flowStatus").asString()).isEqualTo("CLOSED");
        assertThat(recalled.get("riskStatus").asString()).isEqualTo("RECALLED");
        assertThat(recalled.get("recallNotice").asString()).contains("模拟召回演练").contains("已结束正常流转").contains("教学演练");

        // 码状态 RECALLED 不会被新增拒绝
        jdbcTemplate.update("UPDATE public_trace_code SET status = 'RECALLED' WHERE batch_id = ?", b);
        JsonNode recalledCode = anonymousGet(code, 200).get("data");
        assertThat(recalledCode.get("riskStatus").asString()).isEqualTo("RECALLED");
        assertThat(recalledCode.get("recallNotice").asString()).contains("模拟召回演练");

        // 码状态 RECALLED、批次风险 NORMAL：不显示召回提示（三个状态相互独立）
        jdbcTemplate.update("UPDATE batch SET risk_status = 'NORMAL' WHERE id = ?", b);
        JsonNode normal = anonymousGet(code, 200).get("data");
        assertThat(normal.get("flowStatus").asString()).isEqualTo("CLOSED");
        assertThat(normal.get("riskStatus").asString()).isEqualTo("NORMAL");
        assertThat(normal.has("recallNotice")).isFalse();
    }

    // =========================================================================
    // 种子谱系：DAG、深链、等时排序、完整性失败关闭
    // =========================================================================

    @Test
    @DisplayName("MERGE 菱形（种子）：X → Y1 / Y2 → Z，共同祖先 X 只出现一次，兄弟 S 的事件不出现；世代按最长路径；确定性输出")
    void mergeDiamondDedupAndSiblingExclusion() throws Exception {
        LocalDateTime t = LocalDateTime.of(2026, 9, 1, 0, 0);
        Long x = seedBatch("X");
        Long y1 = seedBatch("Y1");
        Long y2 = seedBatch("Y2");
        Long s = seedBatch("S");
        Long z = seedBatch("Z");
        Long split = seedOperation("SPLIT", t.plusHours(1));
        seedRelation(split, x, y1, "SPLIT");
        seedRelation(split, x, y2, "SPLIT");
        seedRelation(split, x, s, "SPLIT");
        Long merge = seedOperation("MERGE", t.plusHours(2));
        seedRelation(merge, y1, z, "MERGE");
        seedRelation(merge, y2, z, "MERGE");
        seedEvent(x, "SOURCE", t);
        seedEvent(y1, "WAREHOUSE_IN", t.plusHours(1).plusMinutes(10));
        seedEvent(y2, "WAREHOUSE_IN", t.plusHours(1).plusMinutes(10));
        seedEvent(s, "SALE", t.plusHours(1).plusMinutes(20));
        seedEvent(z, "SALE", t.plusHours(3));
        String code = seedCode(z);

        JsonNode data = anonymousGet(code, 200).get("data");
        assertThat(nodes(data)).containsExactly("N1:0:ORIGIN", "N2:1:INTERMEDIATE", "N3:1:INTERMEDIATE", "N4:2:TARGET");
        assertThat(edges(data)).containsExactly("N1>N2:SPLIT", "N1>N3:SPLIT", "N2>N4:MERGE", "N3>N4:MERGE");
        assertThat(timeline(data)).containsExactly("N1:SOURCE", "N2:WAREHOUSE_IN", "N3:WAREHOUSE_IN", "N4:SALE");
        assertThat(withoutQueriedAt(anonymousGet(code, 200).get("data"))).isEqualTo(withoutQueriedAt(data));
    }

    @Test
    @DisplayName("60 代深链（种子）：完整返回 60 个节点、59 条边，目标世代 59")
    void deepChain() throws Exception {
        int depth = 60;
        List<Long> ids = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 9, 1, 0, 0);
        for (int i = 0; i < depth; i++) {
            ids.add(seedBatch("D" + i));
            if (i > 0) {
                seedRelation(seedOperation("PROCESS", t.plusMinutes(i)), ids.get(i - 1), ids.get(i), "TRANSFORM");
            }
            seedEvent(ids.get(i), i == 0 ? "SOURCE" : "PROCESS", t.plusMinutes(i));
        }
        JsonNode data = anonymousGet(seedCode(ids.get(depth - 1)), 200).get("data");
        assertThat(data.get("lineage").get("nodes")).hasSize(depth);
        assertThat(data.get("lineage").get("edges")).hasSize(depth - 1);
        assertThat(data.get("lineage").get("nodes").get(depth - 1).get("generation").asInt()).isEqualTo(depth - 1);
        assertThat(data.get("timeline")).hasSize(depth);
    }

    @Test
    @DisplayName("等时稳定排序（种子）：同一业务时间先按世代（祖先在前），再按显式公开事件权重，最后按登记时间 / ID；PURCHASE 不出现")
    void equalTimestampOrdering() throws Exception {
        LocalDateTime same = LocalDateTime.of(2026, 9, 1, 12, 0);
        Long a = seedBatch("EA");
        Long b = seedBatch("EB");
        seedRelation(seedOperation("PROCESS", same), a, b, "TRANSFORM");
        // 故意按“逆序”插入（ID 递增与公开顺序相反）
        seedEvent(b, "SALE", same);
        seedEvent(b, "WAREHOUSE_IN", same);
        seedEvent(b, "PROCESS", same);
        seedEvent(a, "ARRIVAL", same);
        seedEvent(a, "PURCHASE", same);
        seedEvent(a, "TRANSPORT", same);
        seedEvent(a, "SOURCE", same);
        String code = seedCode(b);

        JsonNode data = anonymousGet(code, 200).get("data");
        assertThat(timeline(data)).containsExactly(
                "N1:SOURCE", "N1:TRANSPORT", "N1:ARRIVAL", "N2:PROCESS", "N2:WAREHOUSE_IN", "N2:SALE");
        assertThat(data.toString()).doesNotContain("PURCHASE");
        for (int i = 0; i < 3; i++) {
            assertThat(withoutQueriedAt(anonymousGet(code, 200).get("data"))).isEqualTo(withoutQueriedAt(data));
        }
    }

    @Test
    @DisplayName("谱系完整性失败关闭：祖先已逻辑删除 / 谱系边引用的操作已删除或未提交 / 引用不存在的父批次（绕过外键的种子）→ 500 PUBLIC_TRACE_LINEAGE_INTEGRITY，无任何部分谱系、无内部 ID")
    void lineageIntegrityFailsClosed() throws Exception {
        LocalDateTime t = LocalDateTime.of(2026, 9, 1, 0, 0);
        Long a = seedBatch("IA");
        Long b = seedBatch("IB");
        Long target = seedBatch("IT");
        Long opAb = seedOperation("PROCESS", t.plusHours(1));
        Long opBt = seedOperation("SPLIT", t.plusHours(2));
        seedRelation(opAb, a, b, "TRANSFORM");
        seedRelation(opBt, b, target, "SPLIT");
        seedEvent(a, "SOURCE", t);
        String code = seedCode(target);
        assertThat(nodes(anonymousGet(code, 200).get("data"))).hasSize(3);

        // 1. 祖先批次逻辑删除
        jdbcTemplate.update("UPDATE batch SET is_deleted = 1 WHERE id = ?", a);
        assertIntegrityFailure(code, a, b, target);
        jdbcTemplate.update("UPDATE batch SET is_deleted = 0 WHERE id = ?", a);

        // 2. 谱系边引用的批次操作已逻辑删除
        jdbcTemplate.update("UPDATE batch_operation SET is_deleted = 1 WHERE id = ?", opAb);
        assertIntegrityFailure(code, a, b, target);
        jdbcTemplate.update("UPDATE batch_operation SET is_deleted = 0 WHERE id = ?", opAb);

        // 3. 谱系边引用的批次操作不是已提交状态
        jdbcTemplate.update("UPDATE batch_operation SET status = 'CORRECTED' WHERE id = ?", opBt);
        assertIntegrityFailure(code, a, b, target);
        jdbcTemplate.update("UPDATE batch_operation SET status = 'SUBMITTED' WHERE id = ?", opBt);

        // 4. 引用不存在的父批次（外键已阻止；此处在单一连接上临时关闭外键检查以构造畸形数据）
        long missingParent = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 100000 FROM batch", Long.class);
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO batch_relation (operation_id, parent_batch_id, child_batch_id, relation_type, created_at) VALUES (?, ?, ?, 'TRANSFORM', NOW(6))")) {
                ps.setLong(1, opAb);
                ps.setLong(2, missingParent);
                ps.setLong(3, a);
                ps.executeUpdate();
            } finally {
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
        try {
            assertIntegrityFailure(code, a, b, target);
        } finally {
            jdbcTemplate.update("DELETE FROM batch_relation WHERE parent_batch_id = ?", missingParent);
        }

        // 恢复后恢复正常（证明失败不是永久副作用，GET 无写入）
        assertThat(nodes(anonymousGet(code, 200).get("data"))).hasSize(3);
        // 未知操作类型：chk_op_type 在数据库层即拒绝写入（schema 不允许构造），由组装器单元测试覆盖
    }

    // =========================================================================
    // 黄金链构建
    // =========================================================================

    private Chain buildChain() throws Exception {
        // B0：来源企业建批（带哨兵产地与外部批号）→ S0 → 加工企业接受
        BatchCreateRequest createReq = new BatchCreateRequest(
                "EXT-" + sentinelExternal + "-B0", testProduct.getId(), new BigDecimal("1000.000"), "kg",
                "DOMESTIC_CAPTURE", "舟山" + sentinelOrigin + "渔场", LocalDate.now(), null, null, 180);
        String createKey = trackKey("idem-b0-c");
        Long b0 = expect(postJson(senderSession, "/api/v1/batches", createKey, createReq), 201).get("data").get("id").asLong();
        createdBatchIds.add(b0);
        expect(postJson(senderSession, "/api/v1/batches/" + b0 + "/submit", trackKey("idem-b0-s"), new BatchSubmitRequest(0L)), 200);
        Handover h0 = prepareDeliveredHandover(b0);
        expect(postJson(receiverSession, "/api/v1/transfers/" + h0.transferId() + "/accept", trackKey("idem-t0-acc"),
                new TransferAcceptRequest(new BigDecimal("1000.000"), "kg", now(), null, transferVersion(h0.transferId()))), 200);

        // PROCESS：B0 1000 → B1 960 + LOSS 30 + SAMPLE 10
        Long b1 = outputs(operation("PROCESS", List.of(opInput(b0, "1000.000"), opOutput("960.000"),
                opOther("LOSS", "30.000"), opOther("SAMPLE", "10.000")))).get(0);

        // FREEZE（受控人工，带哨兵）→ 更正（新业务时间、哨兵原因）
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("note", sentinelFreezeDetails);
        JsonNode freeze = expect(postJson(receiverSession, "/api/v1/batches/" + b1 + "/events", trackKey("idem-frz"),
                new CreateTraceEventRequest("FREEZE", FREEZE_ORIGINAL_TIME.atOffset(ZoneOffset.UTC), null, "MANUAL", sentinelFreezeSummary, details)), 201).get("data");
        expect(postJson(receiverSession, "/api/v1/batches/" + b1 + "/events/" + freeze.get("id").asLong() + "/corrections", trackKey("idem-frz-cor"),
                new CorrectTraceEventRequest("FREEZE", now(), null, "MANUAL", sentinelCorrectedSummary, details, sentinelCorrectionReason)), 201);

        // SPLIT：B1 960 → B2 600 + B3 360（不生成任何事件）
        List<Long> split = outputs(operation("SPLIT", List.of(opInput(b1, "960.000"), opOutput("600.000"), opOutput("360.000"))));
        Long b2 = split.get(0);
        Long b3 = split.get(1);

        // 自有冷库入库 / 出库（摘要为哨兵）
        warehouse(b2, "WAREHOUSE_IN", sentinelWarehouseB2);
        warehouse(b2, "WAREHOUSE_OUT", sentinelWarehouseB2);
        warehouse(b3, "WAREHOUSE_IN", sentinelWarehouseB3);
        warehouse(b3, "WAREHOUSE_OUT", sentinelWarehouseB3);

        // T2 / T3 装入同一 S1：加工企业 → 零售企业
        Long t2 = createDraftTransfer(receiverSession, b2, retailerOrg.getId());
        Long t3 = createDraftTransfer(receiverSession, b3, retailerOrg.getId());
        JsonNode s1 = expect(createShipmentRequest(receiverSession, trackKey("idem-s1"), carrierOrg.getId(), receiverSite.getId(), retailerStore.getId()), 201).get("data");
        Long s1Id = s1.get("id").asLong();
        createdShipmentIds.add(s1Id);
        for (Long t : List.of(t2, t3)) {
            expect(bindRequest(receiverSession, s1Id, t, transferVersion(t)), 200);
        }
        for (Long t : List.of(t2, t3)) {
            expect(submitRequest(receiverSession, t, transferVersion(t)), 200);
        }
        dispatch(s1Id);
        arrive(s1Id);

        // 零售企业 ACCEPT → 激活公开码（在终端销售之前）→ 售罄
        expect(postJson(retailerSession, "/api/v1/transfers/" + t2 + "/accept", trackKey("idem-t2-acc"),
                new TransferAcceptRequest(new BigDecimal("600.000"), "kg", now(), null, transferVersion(t2))), 200);
        expect(postJson(retailerSession, "/api/v1/transfers/" + t3 + "/accept", trackKey("idem-t3-acc"),
                new TransferAcceptRequest(new BigDecimal("360.000"), "kg", now(), null, transferVersion(t3))), 200);
        String b2Code = activate(b2);
        String b3Code = activate(b3);
        sell(b2, "200");
        sell(b2, "400");
        sell(b3, "360");
        return new Chain(b0, b1, b2, b3, b2Code, b3Code, s1Id);
    }

    private JsonNode operation(String type, List<BatchOperationItemRequest> items) throws Exception {
        JsonNode draft = expect(postJson(receiverSession, "/api/v1/batch-operations", trackKey("idem-op-c"),
                new BatchOperationCreateRequest(type, now(), "Slice 6 测试", items)), 201).get("data");
        registerOperation(draft);
        return expect(submitOperationRequest(receiverSession, draft.get("id").asLong(), draft.get("version").asLong(), trackKey("idem-op-s")), 200).get("data");
    }

    private static List<Long> outputs(JsonNode op) {
        List<Long> ids = new ArrayList<>();
        op.get("items").forEach(i -> {
            if ("OUTPUT".equals(i.get("role").asString())) {
                ids.add(i.get("batchId").asLong());
            }
        });
        return ids;
    }

    private void warehouse(Long batchId, String type, String summary) throws Exception {
        expect(postJson(receiverSession, "/api/v1/batches/" + batchId + "/events", trackKey("idem-wh"),
                new CreateTraceEventRequest(type, now(), processorColdStore.getId(), "MANUAL", summary, null)), 201);
    }

    private String activate(Long batchId) throws Exception {
        return expect(postJson(retailerSession, "/api/v1/batches/" + batchId + "/public-trace-code/activate", trackKey("idem-ptc"), null), 200)
                .get("data").get("publicId").asString();
    }

    private void sell(Long batchId, String qty) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("siteId", retailerStore.getId());
        body.put("quantity", new BigDecimal(qty));
        body.put("occurredAt", now().toString());
        expect(postJson(retailerSession, "/api/v1/batches/" + batchId + "/sales", trackKey("idem-sale"), body), 201);
    }

    private String trackKey(String prefix) {
        String k = key(prefix);
        usedKeys.add(k);
        return k;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    // =========================================================================
    // 种子数据（只用于只读投影验证）
    // =========================================================================

    private Long seedBatch(String label) {
        Batch batch = new Batch();
        batch.setOrgId(receiverOrg.getId());
        batch.setCreationOrgId(receiverOrg.getId());
        batch.setProductId(testProduct.getId());
        batch.setTraceBatchNo("TB-SEED-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        batch.setExternalBatchNo("SEED-" + label);
        batch.setBatchType("PROCESSING");
        batch.setQuantity(new BigDecimal("10.000"));
        batch.setUnitCode("kg");
        batch.setOriginType("DOMESTIC_CAPTURE");
        batch.setOriginText("种子产地");
        batch.setFlowStatus("CLOSED");
        batch.setRiskStatus("NORMAL");
        batch.setVersion(0L);
        batch.setIsDeleted(0);
        batch.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchMapper.insert(batch);
        createdBatchIds.add(batch.getId());
        return batch.getId();
    }

    private Long seedOperation(String type, LocalDateTime occurredAt) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        String opNo = "OP-SEED-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO batch_operation (org_id, operation_no, operation_type, occurred_at, recorded_at, status, idempotency_key, "
                            + "version, is_deleted, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'SUBMITTED', ?, 0, 0, NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, receiverOrg.getId());
            ps.setString(2, opNo);
            ps.setString(3, type);
            ps.setTimestamp(4, Timestamp.valueOf(occurredAt));
            ps.setTimestamp(5, Timestamp.valueOf(occurredAt));
            ps.setString(6, "seed-op-" + UUID.randomUUID());
            return ps;
        }, holder);
        Long id = holder.getKey().longValue();
        createdOperationIds.add(id);
        return id;
    }

    private void seedRelation(Long operationId, Long parent, Long child, String relationType) {
        jdbcTemplate.update("INSERT INTO batch_relation (operation_id, parent_batch_id, child_batch_id, relation_type, created_at) VALUES (?, ?, ?, ?, NOW(6))",
                operationId, parent, child, relationType);
    }

    private void seedEvent(Long batchId, String type, LocalDateTime occurredAt) {
        jdbcTemplate.update("""
                INSERT INTO trace_event (batch_id, org_id, event_type, site_id, occurred_at, recorded_at,
                                         data_source, summary, details_json, status, idempotency_key, version, is_deleted, created_at, updated_at)
                VALUES (?, ?, ?, NULL, ?, ?, 'MANUAL', 'seed', NULL, 'SUBMITTED', ?, 0, 0, NOW(6), NOW(6))
                """, batchId, receiverOrg.getId(), type, Timestamp.valueOf(occurredAt), Timestamp.valueOf(occurredAt), "seed-evt-" + UUID.randomUUID());
    }

    private String seedCode(Long batchId) {
        String publicId = PublicTraceIdGenerator.generatePublicId();
        PublicTraceCode code = new PublicTraceCode();
        code.setBatchId(batchId);
        code.setOrgId(receiverOrg.getId());
        code.setPublicId(publicId);
        code.setTokenHash(PublicTraceIdGenerator.computeTokenHash(publicId));
        code.setStatus("ACTIVE");
        code.setActivatedAt(LocalDateTime.now(ZoneOffset.UTC));
        code.setVersion(0L);
        code.setIsDeleted(0);
        code.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        code.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        publicTraceCodeMapper.insert(code);
        return publicId;
    }

    // =========================================================================
    // 断言辅助
    // =========================================================================

    private JsonNode anonymousGet(String code, int expectedStatus) throws Exception {
        return expect(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(PUBLIC_URL + code), expectedStatus);
    }

    private void assertIntegrityFailure(String code, Long... internalIds) throws Exception {
        MvcResult r = perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(PUBLIC_URL + code));
        String body = r.getResponse().getContentAsString();
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(500);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("code").asString()).isEqualTo("PUBLIC_TRACE_LINEAGE_INTEGRITY");
        assertThat(json.has("data")).isFalse();
        assertThat(body).doesNotContain("\"nodes\"").doesNotContain("\"timeline\"");
        for (Long id : internalIds) {
            assertThat(json.get("detail").asString()).doesNotContain(String.valueOf(id));
        }
    }

    private static List<String> nodes(JsonNode data) {
        List<String> out = new ArrayList<>();
        data.get("lineage").get("nodes").forEach(n ->
                out.add(n.get("nodeKey").asString() + ":" + n.get("generation").asInt() + ":" + n.get("role").asString()));
        return out;
    }

    private static List<String> edges(JsonNode data) {
        List<String> out = new ArrayList<>();
        data.get("lineage").get("edges").forEach(e ->
                out.add(e.get("fromNodeKey").asString() + ">" + e.get("toNodeKey").asString() + ":" + e.get("operationType").asString()));
        return out;
    }

    private static List<String> timeline(JsonNode data) {
        List<String> out = new ArrayList<>();
        data.get("timeline").forEach(i -> out.add(i.get("nodeKey").asString() + ":" + i.get("eventType").asString()));
        return out;
    }

    private static void assertNonDecreasingOccurredAt(JsonNode data) {
        OffsetDateTime previous = null;
        for (JsonNode item : data.get("timeline")) {
            OffsetDateTime current = OffsetDateTime.parse(item.get("occurredAt").asString());
            if (previous != null) {
                assertThat(current).isAfterOrEqualTo(previous);
            }
            previous = current;
        }
    }

    private static JsonNode withoutQueriedAt(JsonNode data) {
        ObjectNode copy = ((ObjectNode) data).deepCopy();
        copy.remove("queriedAt");
        return copy;
    }

    private Map<String, Object> snapshot(Chain c) {
        Map<String, Object> s = new LinkedHashMap<>();
        for (Long b : List.of(c.b0(), c.b1(), c.b2(), c.b3())) {
            s.put("batch-" + b, jdbcTemplate.queryForMap("SELECT version, updated_at, flow_status, risk_status, org_id FROM batch WHERE id = ?", b));
        }
        s.put("codes", jdbcTemplate.queryForList("SELECT id, status, version, updated_at, org_id FROM public_trace_code WHERE batch_id IN (?, ?) ORDER BY id", c.b2(), c.b3()));
        for (String table : List.of("trace_event", "audit_log", "batch", "batch_relation", "sale", "transfer", "shipment",
                "public_trace_code", "public_trace_code_idempotency")) {
            s.put(table, count("SELECT count(*) FROM " + table));
        }
        return s;
    }
}
