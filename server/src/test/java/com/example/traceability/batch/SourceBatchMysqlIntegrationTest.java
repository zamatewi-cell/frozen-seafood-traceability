package com.example.traceability.batch;

import com.example.traceability.batch.dto.BatchSubmitRequest;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.domain.UserRole;
import com.example.traceability.identity.dto.LoginRequest;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.mapper.UserRoleMapper;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase A / Slice 1 来源建批纵向闭环的真实 MySQL 8.4 集成测试。
 * <p>
 * 覆盖：SOURCE 组织 OPERATOR 建立 DRAFT/NORMAL 来源批次、非来源组织与非 OPERATOR 拒绝、客户端服务端字段拒绝、
 * traceBatchNo 全局唯一与 externalBatchNo 可重复、创建幂等重放与冲突、提交激活与唯一 SOURCE 事件、
 * SOURCE 写入失败时批次激活回滚、并发提交至多一条 SOURCE、人工接口禁止伪造 SOURCE、产品停用、乐观锁与组织隔离。
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 控制。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
class SourceBatchMysqlIntegrationTest {

    private static final String PASSWORD = "SourceSlice1Pass!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private OrganizationMapper organizationMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdProductIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdOrgIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();

    private String suffix;
    private Organization sourceOrgA;
    private Organization sourceOrgB;
    private Organization processorOrg;
    private Product activeProduct;
    private HttpSession sessionA;
    private HttpSession sessionB;
    private HttpSession sessionProcessor;
    private HttpSession sessionViewer;
    private AppUser operatorA;

    @BeforeEach
    void setUp() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        sourceOrgA = createOrg("S1_SRC_A_" + suffix, "来源捕捞企业A", "SOURCE");
        sourceOrgB = createOrg("S1_SRC_B_" + suffix, "来源养殖企业B", "SOURCE");
        processorOrg = createOrg("S1_PROC_" + suffix, "加工企业", "PROCESSOR");

        Role operatorRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        Role auditorRole = getOrCreateRole("AUDITOR", "企业审计员", "ORG_ONLY");

        operatorA = createUser(sourceOrgA.getId(), "s1_src_a_" + suffix, operatorRole);
        createUser(sourceOrgB.getId(), "s1_src_b_" + suffix, operatorRole);
        createUser(processorOrg.getId(), "s1_proc_" + suffix, operatorRole);
        createUser(sourceOrgA.getId(), "s1_view_" + suffix, auditorRole);

        activeProduct = createProduct("S1-FISH-" + suffix, "来源建批冷冻大黄鱼", "ACTIVE");

        sessionA = login("s1_src_a_" + suffix);
        sessionB = login("s1_src_b_" + suffix);
        sessionProcessor = login("s1_proc_" + suffix);
        sessionViewer = login("s1_view_" + suffix);
    }

    @AfterEach
    void tearDown() {
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM trace_event WHERE org_id = ?", orgId);
            jdbcTemplate.update("DELETE FROM batch WHERE creation_org_id = ? OR org_id = ?", orgId, orgId);
        }
        for (Long productId : createdProductIds) {
            jdbcTemplate.update("DELETE FROM product WHERE id = ?", productId);
        }
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        for (Long roleId : createdRoleIds) {
            jdbcTemplate.update("DELETE FROM user_role WHERE role_id = ?", roleId);
            jdbcTemplate.update("DELETE FROM role WHERE id = ?", roleId);
        }
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", orgId);
        }
        createdProductIds.clear();
        createdUserIds.clear();
        createdRoleIds.clear();
        createdOrgIds.clear();
    }

    @Test
    @DisplayName("SOURCE 组织 OPERATOR：建立 DRAFT/NORMAL 来源批次 → 提交激活 ACTIVE/NORMAL → 自动生成唯一 SOURCE（结构化事实正确）")
    void sourceBatch_CreateSubmit_GeneratesExactlyOneSourceEvent() throws Exception {
        Map<String, Object> body = sourceBody("SRC-2026-001", "1000");
        body.put("captureDate", "2026-09-01");
        body.put("freezeDate", "2026-09-02");
        body.put("shelfLifeDays", 365);

        JsonNode draft = createBatch(sessionA, body, "idem-s1-main-" + suffix, 201);
        long batchId = draft.path("id").asLong();
        String traceBatchNo = draft.path("traceBatchNo").asText();
        assertThat(draft.path("batchType").asText()).isEqualTo("SOURCE");
        assertThat(draft.path("flowStatus").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("riskStatus").asText()).isEqualTo("NORMAL");
        assertThat(draft.path("orgId").asLong()).isEqualTo(sourceOrgA.getId());
        assertThat(draft.path("unitCode").asText()).isEqualTo("kg");
        assertThat(draft.path("externalBatchNo").asText()).isEqualTo("SRC-2026-001");
        assertThat(traceBatchNo).matches("^TB-[0-9A-Z]{26}$");
        assertThat(countSourceEvents(batchId)).isZero();

        MvcResult submitRes = submit(sessionA, batchId, 0L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flowStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.riskStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.traceBatchNo").value(traceBatchNo))
                .andReturn();
        assertThat(submitRes.getResponse().getStatus()).isEqualTo(200);

        // 真实 TraceEvent API：恰好一条 SOURCE
        MvcResult eventsRes = mockMvc.perform(get("/api/v1/batches/" + batchId + "/events").session((MockHttpSession) sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn();
        JsonNode event = objectMapper.readTree(eventsRes.getResponse().getContentAsString()).path("data").get(0);
        assertThat(event.path("eventType").asText()).isEqualTo("SOURCE");
        assertThat(event.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(event.path("batchId").asLong()).isEqualTo(batchId);
        assertThat(event.path("orgId").asLong()).isEqualTo(sourceOrgA.getId());
        assertThat(event.path("operatorId").asLong()).isEqualTo(operatorA.getId());
        assertThat(event.path("dataSource").asText()).isEqualTo("MANUAL");
        assertThat(event.has("idempotencyKey")).isFalse();
        JsonNode details = event.path("detailsJson");
        assertThat(details.path("sourceObjectType").asText()).isEqualTo("BATCH");
        assertThat(details.path("sourceObjectId").asLong()).isEqualTo(batchId);
        assertThat(details.path("traceBatchNo").asText()).isEqualTo(traceBatchNo);
        assertThat(details.path("productId").asLong()).isEqualTo(activeProduct.getId());
        assertThat(details.path("originType").asText()).isEqualTo("DOMESTIC_CAPTURE");
        assertThat(details.path("originText").asText()).isEqualTo("东海舟山渔场");
        assertThat(details.path("quantity").asText()).isEqualTo("1000.000");
        assertThat(details.path("unitCode").asText()).isEqualTo("kg");
        assertThat(details.path("captureDate").asText()).isEqualTo("2026-09-01");
        assertThat(details.path("freezeDate").asText()).isEqualTo("2026-09-02");
        assertThat(details.has("productionDate")).isFalse();
        assertThat(details.path("occurredAtBasis").asText()).isEqualTo("BATCH_ACTIVATION");

        // 数据库事实：服务端幂等身份、occurredAt = 批次激活时刻（与 batch.updated_at 同一时刻）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT e.idempotency_key, e.occurred_at, b.updated_at FROM trace_event e JOIN batch b ON b.id = e.batch_id WHERE e.batch_id = ?",
                batchId);
        assertThat(row.get("idempotency_key")).isEqualTo("SYS:SOURCE:BATCH:" + batchId);
        assertThat(row.get("occurred_at")).isEqualTo(row.get("updated_at"));

        // 重复提交 → 409，仍然只有一条 SOURCE
        submit(sessionA, batchId, 1L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        assertThat(countSourceEvents(batchId)).isEqualTo(1);
    }

    @Test
    @DisplayName("权限：非 SOURCE 组织 OPERATOR 与 SOURCE 组织非 OPERATOR 创建 / 提交一律 403，不落库")
    void permissions_NonSourceOrgAndNonOperatorRejected() throws Exception {
        createBatchRaw(sessionProcessor, sourceBody(null, "10"), "idem-s1-proc-" + suffix)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_TYPE_NOT_ALLOWED"));
        createBatchRaw(sessionViewer, sourceBody(null, "10"), "idem-s1-view-" + suffix)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(countBatches(processorOrg.getId())).isZero();

        long batchId = createBatch(sessionA, sourceBody(null, "10"), "idem-s1-perm-" + suffix, 201).path("id").asLong();
        submit(sessionProcessor, batchId, 0L)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_TYPE_NOT_ALLOWED"));
        submit(sessionViewer, batchId, 0L)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(flowStatus(batchId)).isEqualTo("DRAFT");
        assertThat(countSourceEvents(batchId)).isZero();
    }

    @Test
    @DisplayName("客户端不能指定 traceBatchNo / orgId / status / flowStatus / 任意 batchType：400 且不落库")
    void clientCannotSpecifyServerFields() throws Exception {
        Map<String, Object> forbidden = new LinkedHashMap<>();
        forbidden.put("traceBatchNo", "TB-CLIENTCHOSENVALUE0000000");
        forbidden.put("orgId", sourceOrgB.getId());
        forbidden.put("creationOrgId", sourceOrgB.getId());
        forbidden.put("status", "ACTIVE");
        forbidden.put("flowStatus", "ACTIVE");
        forbidden.put("riskStatus", "FROZEN");
        forbidden.put("batchType", "SOURCE");
        for (Map.Entry<String, Object> field : forbidden.entrySet()) {
            Map<String, Object> body = sourceBody("SRC-FORBID", "10");
            body.put(field.getKey(), field.getValue());
            createBatchRaw(sessionA, body, "idem-s1-forbid-" + field.getKey() + "-" + suffix)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        for (String batchType : List.of("PROCESSING", "DISTRIBUTION", "SALE")) {
            Map<String, Object> body = sourceBody("SRC-FORBID", "10");
            body.put("batchType", batchType);
            createBatchRaw(sessionA, body, "idem-s1-type-" + batchType + "-" + suffix)
                    .andExpect(status().isBadRequest());
        }
        assertThat(countBatches(sourceOrgA.getId())).isZero();
        Integer clientTrace = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE trace_batch_no = 'TB-CLIENTCHOSENVALUE0000000'", Integer.class);
        assertThat(clientTrace).isZero();
    }

    @Test
    @DisplayName("traceBatchNo 全局唯一；externalBatchNo 同组织与跨组织均可重复；幂等重放同语义返回原批次，不同语义 409")
    void identifiersAndIdempotency() throws Exception {
        String key = "idem-s1-idem-" + suffix;
        JsonNode first = createBatch(sessionA, sourceBody("SRC-DUP", "10"), key, 201);
        JsonNode sameOrgDuplicateExternal = createBatch(sessionA, sourceBody("SRC-DUP", "20"), "idem-s1-dup2-" + suffix, 201);
        JsonNode crossOrgDuplicateExternal = createBatch(sessionB, sourceBody("SRC-DUP", "30"), "idem-s1-dup3-" + suffix, 201);

        assertThat(List.of(first, sameOrgDuplicateExternal, crossOrgDuplicateExternal))
                .extracting(n -> n.path("externalBatchNo").asText())
                .containsOnly("SRC-DUP");
        assertThat(List.of(first, sameOrgDuplicateExternal, crossOrgDuplicateExternal))
                .extracting(n -> n.path("traceBatchNo").asText())
                .doesNotHaveDuplicates();
        Integer globalTraceDuplicates = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT trace_batch_no FROM batch GROUP BY trace_batch_no HAVING COUNT(*) > 1) d", Integer.class);
        assertThat(globalTraceDuplicates).isZero();

        // 同 key 同语义重放 → 原批次（同 id、同 traceBatchNo）
        JsonNode replay = createBatch(sessionA, sourceBody("SRC-DUP", "10"), key, 201);
        assertThat(replay.path("id").asLong()).isEqualTo(first.path("id").asLong());
        assertThat(replay.path("traceBatchNo").asText()).isEqualTo(first.path("traceBatchNo").asText());

        // 同 key 不同语义 → 409
        createBatchRaw(sessionA, sourceBody("SRC-DUP", "11"), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(countBatches(sourceOrgA.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("SOURCE 写入失败时批次激活整体回滚：批次保持 DRAFT/NORMAL、版本不变、无 SOURCE")
    void sourceEventFailure_RollsBackActivation() throws Exception {
        long anchorId = createBatch(sessionA, sourceBody(null, "5"), "idem-s1-anchor-" + suffix, 201).path("id").asLong();
        submit(sessionA, anchorId, 0L).andExpect(status().isOk());
        long batchId = createBatch(sessionA, sourceBody(null, "8"), "idem-s1-rb-" + suffix, 201).path("id").asLong();

        // 在同一组织下预先占用该批次的 SOURCE 服务端幂等身份，使 SOURCE 插入触发唯一键冲突
        jdbcTemplate.update("""
                INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, data_source, status,
                                         idempotency_key, summary, version, is_deleted, created_at, updated_at)
                VALUES (?, ?, 'FREEZE', NOW(6), NOW(6), 'MANUAL', 'SUBMITTED', ?, '占用身份', 0, 0, NOW(6), NOW(6))
                """, anchorId, sourceOrgA.getId(), "SYS:SOURCE:BATCH:" + batchId);

        submit(sessionA, batchId, 0L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_EVENT_CONFLICT"));

        Map<String, Object> batch = jdbcTemplate.queryForMap("SELECT flow_status, risk_status, version FROM batch WHERE id = ?", batchId);
        assertThat(batch.get("flow_status")).isEqualTo("DRAFT");
        assertThat(batch.get("risk_status")).isEqualTo("NORMAL");
        assertThat(((Number) batch.get("version")).longValue()).isZero();
        assertThat(countSourceEvents(batchId)).isZero();
    }

    @Test
    @DisplayName("并发提交同一来源批次：恰好一个成功，另一个 409，最终只有一条 SOURCE")
    void concurrentSubmits_ProduceAtMostOneSource() throws Exception {
        long batchId = createBatch(sessionA, sourceBody(null, "100"), "idem-s1-conc-" + suffix, 201).path("id").asLong();
        HttpSession sessionA2 = login("s1_src_a_" + suffix);

        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> submitStatusAfter(start, sessionA, batchId));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> submitStatusAfter(start, sessionA2, batchId));
        start.countDown();

        List<Integer> statuses = List.of(first.get(), second.get());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(flowStatus(batchId)).isEqualTo("ACTIVE");
        assertThat(countSourceEvents(batchId)).isEqualTo(1);
    }

    @Test
    @DisplayName("人工事件接口不能创建 SOURCE，也不能使用 SYS: 保留幂等键")
    void manualEventApi_CannotForgeSource() throws Exception {
        long batchId = createBatch(sessionA, sourceBody(null, "50"), "idem-s1-manual-" + suffix, 201).path("id").asLong();
        submit(sessionA, batchId, 0L).andExpect(status().isOk());

        Map<String, Object> forged = new LinkedHashMap<>();
        forged.put("eventType", "SOURCE");
        forged.put("occurredAt", "2026-09-01T00:00:00Z");
        forged.put("dataSource", "MANUAL");
        forged.put("summary", "伪造来源");
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/events")
                        .session((MockHttpSession) sessionA).with(csrf())
                        .header("Idempotency-Key", "idem-s1-forge-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(forged)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVENT_TYPE_NOT_MANUAL"));

        forged.put("eventType", "FREEZE");
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/events")
                        .session((MockHttpSession) sessionA).with(csrf())
                        .header("Idempotency-Key", "SYS:SOURCE:BATCH:" + (batchId + 1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(forged)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(countSourceEvents(batchId)).isEqualTo(1);
    }

    @Test
    @DisplayName("产品停用后不能提交 422；过期版本 409 VERSION_CONFLICT；两者都保持 DRAFT 且无 SOURCE")
    void inactiveProductAndStaleVersion() throws Exception {
        long batchId = createBatch(sessionA, sourceBody(null, "10"), "idem-s1-ina-" + suffix, 201).path("id").asLong();
        jdbcTemplate.update("UPDATE product SET status = 'INACTIVE' WHERE id = ?", activeProduct.getId());
        submit(sessionA, batchId, 0L)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_ACTIVE"));
        jdbcTemplate.update("UPDATE product SET status = 'ACTIVE' WHERE id = ?", activeProduct.getId());

        submit(sessionA, batchId, 7L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        assertThat(flowStatus(batchId)).isEqualTo("DRAFT");
        assertThat(countSourceEvents(batchId)).isZero();
    }

    @Test
    @DisplayName("组织隔离：其他来源组织不能读取、提交或查看事件；列表不可见")
    void organizationIsolation() throws Exception {
        long batchId = createBatch(sessionA, sourceBody("SRC-ISO", "10"), "idem-s1-iso-" + suffix, 201).path("id").asLong();

        mockMvc.perform(get("/api/v1/batches/" + batchId).session((MockHttpSession) sessionB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));
        submit(sessionB, batchId, 0L)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));

        submit(sessionA, batchId, 0L).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/batches/" + batchId + "/events").session((MockHttpSession) sessionB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));
        mockMvc.perform(get("/api/v1/batches").param("externalBatchNo", "SRC-ISO").session((MockHttpSession) sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ------------------------------------------------------------------------------------------

    private Map<String, Object> sourceBody(String externalBatchNo, String quantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (externalBatchNo != null) {
            body.put("externalBatchNo", externalBatchNo);
        }
        body.put("productId", activeProduct.getId());
        body.put("quantity", new java.math.BigDecimal(quantity));
        body.put("unitCode", "kg");
        body.put("originType", "DOMESTIC_CAPTURE");
        body.put("originText", "东海舟山渔场");
        return body;
    }

    private org.springframework.test.web.servlet.ResultActions createBatchRaw(HttpSession session, Map<String, Object> body, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/batches")
                .session((MockHttpSession) session).with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode createBatch(HttpSession session, Map<String, Object> body, String key, int expectedStatus) throws Exception {
        MvcResult res = createBatchRaw(session, body, key).andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).path("data");
    }

    private org.springframework.test.web.servlet.ResultActions submit(HttpSession session, long batchId, long version) throws Exception {
        return mockMvc.perform(post("/api/v1/batches/" + batchId + "/submit")
                .session((MockHttpSession) session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new BatchSubmitRequest(version))));
    }

    private int submitStatusAfter(CountDownLatch start, HttpSession session, long batchId) {
        try {
            start.await();
            return submit(session, batchId, 0L).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int countSourceEvents(long batchId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trace_event WHERE batch_id = ? AND event_type = 'SOURCE' AND status = 'SUBMITTED' AND is_deleted = 0",
                Integer.class, batchId);
        return count == null ? 0 : count;
    }

    private int countBatches(Long orgId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM batch WHERE creation_org_id = ?", Integer.class, orgId);
        return count == null ? 0 : count;
    }

    private String flowStatus(long batchId) {
        return jdbcTemplate.queryForObject("SELECT flow_status FROM batch WHERE id = ?", String.class, batchId);
    }

    private HttpSession login(String username) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return res.getRequest().getSession(false);
    }

    private Organization createOrg(String orgNo, String name, String orgType) {
        Organization org = new Organization();
        org.setOrgNo(orgNo);
        org.setName(name);
        org.setOrgType(orgType);
        org.setStatus("ACTIVE");
        org.setVersion(0L);
        org.setIsDeleted(0);
        org.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        org.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        organizationMapper.insert(org);
        createdOrgIds.add(org.getId());
        return org;
    }

    private Role getOrCreateRole(String roleCode, String name, String scopeType) {
        Role existing = roleMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Role>()
                        .eq(Role::getRoleCode, roleCode)
        );
        if (existing != null) {
            return existing;
        }
        Role role = new Role();
        role.setRoleCode(roleCode);
        role.setName(name);
        role.setScopeType(scopeType);
        role.setStatus("ACTIVE");
        role.setVersion(0L);
        role.setIsDeleted(0);
        role.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        role.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        roleMapper.insert(role);
        createdRoleIds.add(role.getId());
        return role;
    }

    private AppUser createUser(Long orgId, String username, Role role) {
        AppUser user = new AppUser();
        user.setOrgId(orgId);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setStatus("ACTIVE");
        user.setVersion(0L);
        user.setIsDeleted(0);
        user.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        appUserMapper.insert(user);
        createdUserIds.add(user.getId());

        UserRole ur = new UserRole();
        ur.setUserId(user.getId());
        ur.setRoleId(role.getId());
        ur.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userRoleMapper.insert(ur);
        return user;
    }

    private Product createProduct(String productCode, String publicName, String status) {
        Product p = new Product();
        p.setProductCode(productCode);
        p.setPublicName(publicName);
        p.setCategory("FISH");
        p.setSpecification("500g/条");
        p.setSourceType("DOMESTIC_CAPTURE");
        p.setBaseUnitCode("kg");
        p.setStatus(status);
        p.setVersion(0L);
        p.setIsDeleted(0);
        p.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        p.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        productMapper.insert(p);
        createdProductIds.add(p.getId());
        return p;
    }
}
