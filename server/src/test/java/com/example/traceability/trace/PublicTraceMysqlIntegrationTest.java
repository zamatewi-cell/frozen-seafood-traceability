package com.example.traceability.trace;

import com.example.traceability.batch.domain.Batch;
import com.example.traceability.batch.domain.BatchFlowStatus;
import com.example.traceability.batch.domain.BatchRiskStatus;
import com.example.traceability.batch.dto.BatchCreateRequest;
import com.example.traceability.batch.dto.BatchSubmitRequest;
import com.example.traceability.batch.mapper.BatchMapper;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.domain.Site;
import com.example.traceability.identity.domain.UserRole;
import com.example.traceability.identity.dto.LoginRequest;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.mapper.SiteMapper;
import com.example.traceability.identity.mapper.UserRoleMapper;
import com.example.traceability.masterdata.domain.Product;
import com.example.traceability.masterdata.mapper.ProductMapper;
import com.example.traceability.trace.domain.PublicTraceCode;
import com.example.traceability.trace.domain.PublicTraceCodeStatus;
import com.example.traceability.trace.domain.PublicTraceIdGenerator;
import com.example.traceability.trace.dto.CorrectTraceEventRequest;
import com.example.traceability.trace.dto.CreateTraceEventRequest;
import com.example.traceability.trace.mapper.PublicTraceCodeIdempotencyMapper;
import com.example.traceability.trace.mapper.PublicTraceCodeMapper;
import com.example.traceability.trace.mapper.TraceEventMapper;
import jakarta.servlet.http.HttpSession;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基于真实 MySQL 8.4 的批次公开追溯码与消费者投影端到端集成测试。
 * <p>
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 控制。
 * 覆盖对外公开码激活、停用、统一幂等表绑定与反例拦截、高并发锁竞争恢复、
 * 消费者匿名白名单投影、机密哨兵字符串过滤、停用事务原子性回滚实证、
 * 404 不可探测性对比及 Flyway V6 底层物理约束验证。
 * 测试结束后彻底物理清理，包含统一幂等表及所有测试夹具。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
@DisplayName("真实 MySQL 8.4 下公开追溯码与消费者投影集成测试")
class PublicTraceMysqlIntegrationTest {

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
    private SiteMapper siteMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private BatchMapper batchMapper;

    @Autowired
    private TraceEventMapper traceEventMapper;

    @Autowired
    private PublicTraceCodeMapper publicTraceCodeMapper;

    @Autowired
    private PublicTraceCodeIdempotencyMapper publicTraceCodeIdempotencyMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdPublicCodeIds = new ArrayList<>();
    private final List<Long> createdTraceEventIds = new ArrayList<>();
    private final List<Long> createdBatchIds = new ArrayList<>();
    private final List<Long> createdSiteIds = new ArrayList<>();
    private final List<Long> createdProductIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdOrgIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // 1. 清理统一幂等记录表 (按组织或批次)
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM public_trace_code_idempotency WHERE org_id = ?", orgId);
        }
        for (Long batchId : createdBatchIds) {
            jdbcTemplate.update("DELETE FROM public_trace_code_idempotency WHERE batch_id = ?", batchId);
        }

        // 2. 清理公开追溯码表
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM public_trace_code WHERE org_id = ?", orgId);
        }
        for (Long codeId : createdPublicCodeIds) {
            jdbcTemplate.update("DELETE FROM public_trace_code WHERE id = ?", codeId);
        }
        createdPublicCodeIds.clear();

        // 3. 清理追溯事件
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM trace_event WHERE org_id = ?", orgId);
        }
        for (Long eventId : createdTraceEventIds) {
            jdbcTemplate.update("DELETE FROM trace_event WHERE id = ?", eventId);
        }
        createdTraceEventIds.clear();

        // 4. 清理批次
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM batch WHERE org_id = ?", orgId);
        }
        for (Long batchId : createdBatchIds) {
            jdbcTemplate.update("DELETE FROM batch WHERE id = ?", batchId);
        }
        createdBatchIds.clear();

        // 5. 清理场所
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM site WHERE org_id = ?", orgId);
        }
        for (Long siteId : createdSiteIds) {
            jdbcTemplate.update("DELETE FROM site WHERE id = ?", siteId);
        }
        createdSiteIds.clear();

        // 6. 清理产品
        for (Long productId : createdProductIds) {
            jdbcTemplate.update("DELETE FROM product WHERE id = ?", productId);
        }
        createdProductIds.clear();

        // 7. 清理用户角色关联与用户
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUserIds.clear();

        // 8. 清理新创建角色
        for (Long roleId : createdRoleIds) {
            jdbcTemplate.update("DELETE FROM role WHERE id = ?", roleId);
        }
        createdRoleIds.clear();

        // 9. 清理组织
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", orgId);
        }
        createdOrgIds.clear();
    }

    @Test
    @DisplayName("端到端全生命周期：批次激活 -> 消费者白名单查询 -> 状态流转 -> 停用 -> 再次查询 404")
    void testPublicTraceLifecycle_EndToEnd() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_TC_" + suffix, "溯源全流程企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_tc_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_TC_" + suffix, "东海野生大黄鱼", "ACTIVE");
        Site site = createSite(org.getId(), "SITE_TC_" + suffix, "舟山定海码头冷库");

        // 1. 登录
        HttpSession session = login(operator.getUsername(), rawPassword);

        // 2. 创建并激活批次
        String rawBatchNo = "BATCH-RAW-" + suffix;
        String rawOrigin = "浙江舟山渔场4号深度作业区-" + suffix;
        Batch batch = createAndSubmitBatch(session, product.getId(), rawBatchNo, rawOrigin);

        // 3. 记录两条事件：一条原事件随后被更正，一条为有效事件
        OffsetDateTime occurredAt1 = OffsetDateTime.of(2026, 9, 1, 8, 0, 0, 0, ZoneOffset.UTC);
        CreateTraceEventRequest ev1Req = new CreateTraceEventRequest(
                "SOURCE", occurredAt1, site.getId(), "MANUAL", "出塘采收初始记录", Map.of("temp", -15.0)
        );
        String event1Key = "idem-ev1-" + suffix;
        MvcResult ev1Res = mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/events")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", event1Key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ev1Req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode ev1Node = objectMapper.readTree(ev1Res.getResponse().getContentAsString()).get("data");
        Long ev1Id = ev1Node.get("id").asLong();
        createdTraceEventIds.add(ev1Id);

        // 更正事件 1
        OffsetDateTime occurredAt1Corrected = OffsetDateTime.of(2026, 9, 1, 8, 30, 0, 0, ZoneOffset.UTC);
        CorrectTraceEventRequest corrReq = new CorrectTraceEventRequest(
                "SOURCE", occurredAt1Corrected, site.getId(), "SIMULATED", "采收校准更正", Map.of("temp", -18.0), "修正水温记录"
        );
        String corrKey = "idem-corr-" + suffix;
        MvcResult corrRes = mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/events/" + ev1Id + "/corrections")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", corrKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(corrReq)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode corrNode = objectMapper.readTree(corrRes.getResponse().getContentAsString()).get("data");
        createdTraceEventIds.add(corrNode.get("id").asLong());

        // 4. 企业操作员激活该批次的对外公开追溯码
        String activateKey = "idem-act-" + suffix;
        MvcResult actRes = mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/public-trace-code/activate")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", activateKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.batchId").value(batch.getId()))
                .andReturn();

        JsonNode actNode = objectMapper.readTree(actRes.getResponse().getContentAsString()).get("data");
        String publicId = actNode.get("publicId").asText();
        Long codeId = actNode.get("id").asLong();
        createdPublicCodeIds.add(codeId);

        // 验证数据库真实落库字段
        String dbTokenHash = jdbcTemplate.queryForObject("SELECT token_hash FROM public_trace_code WHERE id = ?", String.class, codeId);
        assertThat(dbTokenHash).isEqualTo(PublicTraceIdGenerator.computeTokenHash(publicId));

        // 验证统一幂等表绑定记录
        Integer idemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public_trace_code_idempotency WHERE org_id = ? AND idempotency_key = ?",
                Integer.class, org.getId(), activateKey);
        assertThat(idemCount).isEqualTo(1);

        // 5. 消费者匿名免认证免 CSRF 查询公开追溯投影
        MvcResult pubQueryRes = mockMvc.perform(get("/api/public/v1/public/traces/" + publicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicTraceId").value(publicId))
                .andExpect(jsonPath("$.data.product.name").value("东海野生大黄鱼"))
                .andExpect(jsonPath("$.data.flowStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.riskStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.temperatureSummary.result").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.data.timeline.length()").value(1)) // 仅包含更正后的 SUBMITTED 版本，排除 CORRECTED 原版本
                .andExpect(jsonPath("$.data.timeline[0].dataSourceLabel").value(containsString("SIMULATED")))
                .andReturn();

        String pubJson = pubQueryRes.getResponse().getContentAsString();
        // 严格禁止词典检查
        assertThat(pubJson).doesNotContain(rawBatchNo);
        assertThat(pubJson).doesNotContain(rawOrigin);
        assertThat(pubJson).doesNotContain(dbTokenHash);
        assertThat(pubJson).doesNotContain("token_hash");
        assertThat(pubJson).doesNotContain("is_deleted");
        assertThat(pubJson).doesNotContain("detailsJson");

        // 6. 批次变为 RECALLED 状态后，查询如实反映 riskStatus=RECALLED 且有明确模拟召回声明
        jdbcTemplate.update("UPDATE batch SET risk_status = 'RECALLED' WHERE id = ?", batch.getId());
        mockMvc.perform(get("/api/public/v1/public/traces/" + publicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flowStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.riskStatus").value("RECALLED"))
                .andExpect(jsonPath("$.data.recallNotice").value(containsString("模拟召回演练")));

        // 7. 批次变为 FROZEN 与 CLOSED 状态
        jdbcTemplate.update("UPDATE batch SET risk_status = 'FROZEN' WHERE id = ?", batch.getId());
        mockMvc.perform(get("/api/public/v1/public/traces/" + publicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flowStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.riskStatus").value("FROZEN"))
                .andExpect(jsonPath("$.data.recallNotice").doesNotExist());

        jdbcTemplate.update("UPDATE batch SET flow_status = 'CLOSED', risk_status = 'NORMAL' WHERE id = ?", batch.getId());
        mockMvc.perform(get("/api/public/v1/public/traces/" + publicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flowStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.riskStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.recallNotice").doesNotExist());

        // 8. 企业操作员停用公开追溯码 (disable is terminal)
        String disableKey = "idem-dis-" + suffix;
        mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/public-trace-code/disable")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", disableKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"))
                .andExpect(jsonPath("$.data.disabledAt").isNotEmpty());

        // 数据库物理验证：未物理删除，is_deleted 保持 0，status 为 DISABLED
        Integer isDeleted = jdbcTemplate.queryForObject("SELECT is_deleted FROM public_trace_code WHERE id = ?", Integer.class, codeId);
        String statusAfterDisable = jdbcTemplate.queryForObject("SELECT status FROM public_trace_code WHERE id = ?", String.class, codeId);
        assertThat(isDeleted).isEqualTo(0);
        assertThat(statusAfterDisable).isEqualTo("DISABLED");

        // 9. 停用后，消费者端再次查询统一返回 404 PUBLIC_TRACE_NOT_FOUND
        mockMvc.perform(get("/api/public/v1/public/traces/" + publicId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUBLIC_TRACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("P0-1 反例1：key B 对已有 ACTIVE 码调用成功后，再用 key B 激活 batch2 必须报 409 IDEMPOTENCY_KEY_REUSED")
    void testIdempotencyCounterExample1_KeyReusedAcrossBatchesAfterActiveHit() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_CE1_" + suffix, "反例1企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_ce1_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_CE1_" + suffix, "反例产品1", "ACTIVE");
        Long batch1 = insertBatchDirect(org.getId(), product.getId(), "BATCH-CE1-1-" + suffix, "ACTIVE");
        Long batch2 = insertBatchDirect(org.getId(), product.getId(), "BATCH-CE1-2-" + suffix, "ACTIVE");
        createdBatchIds.add(batch1);
        createdBatchIds.add(batch2);

        HttpSession session = login(operator.getUsername(), rawPassword);

        // 1. key A 首次激活 batch1
        String keyA = "idem-act-keyA-" + suffix;
        mockMvc.perform(post("/api/v1/batches/" + batch1 + "/public-trace-code/activate")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", keyA))
                .andExpect(status().isOk());

        // 2. key B 对已处于 ACTIVE 的 batch1 调用激活 (语义等价调用，返回 200 原码并在同事务内绑定 key B)
        String keyB = "idem-act-keyB-" + suffix;
        mockMvc.perform(post("/api/v1/batches/" + batch1 + "/public-trace-code/activate")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", keyB))
                .andExpect(status().isOk());

        // 验证统一幂等表已持久绑定 key B 到 batch1
        Integer keyBCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public_trace_code_idempotency WHERE org_id = ? AND idempotency_key = ? AND batch_id = ?",
                Integer.class, org.getId(), keyB, batch1);
        assertThat(keyBCount).isEqualTo(1);

        // 3. 再用 key B 尝试激活 batch2 -> 必须拦截并报 409 IDEMPOTENCY_KEY_REUSED
        mockMvc.perform(post("/api/v1/batches/" + batch2 + "/public-trace-code/activate")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", keyB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @DisplayName("P0-1 反例2：key C 对已 DISABLED 码调用停用成功后，再用 key C 停用 batch2 必须报 409 IDEMPOTENCY_KEY_REUSED")
    void testIdempotencyCounterExample2_KeyReusedAcrossBatchesAfterDisabledHit() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_CE2_" + suffix, "反例2企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_ce2_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_CE2_" + suffix, "反例产品2", "ACTIVE");
        Long batch1 = insertBatchDirect(org.getId(), product.getId(), "BATCH-CE2-1-" + suffix, "ACTIVE");
        Long batch2 = insertBatchDirect(org.getId(), product.getId(), "BATCH-CE2-2-" + suffix, "ACTIVE");
        createdBatchIds.add(batch1);
        createdBatchIds.add(batch2);

        HttpSession session = login(operator.getUsername(), rawPassword);

        // 激活 batch1 和 batch2
        String act1 = "idem-act-b1-" + suffix;
        String act2 = "idem-act-b2-" + suffix;
        mockMvc.perform(post("/api/v1/batches/" + batch1 + "/public-trace-code/activate")
                        .session((MockHttpSession) session).with(csrf()).header("Idempotency-Key", act1))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/batches/" + batch2 + "/public-trace-code/activate")
                        .session((MockHttpSession) session).with(csrf()).header("Idempotency-Key", act2))
                .andExpect(status().isOk());

        // 1. 停用 batch1
        String dis1 = "idem-dis-b1-" + suffix;
        mockMvc.perform(post("/api/v1/batches/" + batch1 + "/public-trace-code/disable")
                        .session((MockHttpSession) session).with(csrf()).header("Idempotency-Key", dis1))
                .andExpect(status().isOk());

        // 2. key C 对已 DISABLED 的 batch1 再次调用停用 (返回 200 并持久绑定 key C 到 batch1)
        String keyC = "idem-dis-keyC-" + suffix;
        mockMvc.perform(post("/api/v1/batches/" + batch1 + "/public-trace-code/disable")
                        .session((MockHttpSession) session).with(csrf()).header("Idempotency-Key", keyC))
                .andExpect(status().isOk());

        // 验证统一幂等表已持久绑定 key C 到 batch1
        Integer keyCCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public_trace_code_idempotency WHERE org_id = ? AND idempotency_key = ? AND batch_id = ?",
                Integer.class, org.getId(), keyC, batch1);
        assertThat(keyCCount).isEqualTo(1);

        // 3. 再用 key C 尝试停用 batch2 -> 必须拦截并报 409 IDEMPOTENCY_KEY_REUSED
        mockMvc.perform(post("/api/v1/batches/" + batch2 + "/public-trace-code/disable")
                        .session((MockHttpSession) session).with(csrf()).header("Idempotency-Key", keyC))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @DisplayName("P0-1 反例3：已有 ACTIVE 码时同 key 跨动作并发竞争 (ACTIVATE 语义重放 vs DISABLE 停用)，恰好一个 200 另一方必为 409 IDEMPOTENCY_KEY_REUSED")
    void testIdempotencyCounterExample3_ConcurrentCrossActionRace() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_CE3_" + suffix, "反例3企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_ce3_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_CE3_" + suffix, "反例产品3", "ACTIVE");
        Long batchId = insertBatchDirect(org.getId(), product.getId(), "BATCH-CE3-" + suffix, "ACTIVE");
        createdBatchIds.add(batchId);

        // 前置条件：先激活该批次公开追溯码，确保资源已存在且处于 ACTIVE 状态
        HttpSession initSession = login(operator.getUsername(), rawPassword);
        String initKey = "idem-init-act-" + suffix;
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                        .session((MockHttpSession) initSession)
                        .with(csrf())
                        .header("Idempotency-Key", initKey))
                .andExpect(status().isOk());

        // 避免多线程共享同一个 MockHttpSession 实例，分别独立登录获取会话
        HttpSession session1 = login(operator.getUsername(), rawPassword);
        HttpSession session2 = login(operator.getUsername(), rawPassword);
        String competingKey = "idem-race-cross-action-" + suffix;

        CountDownLatch latch = new CountDownLatch(1);

        // 线程 1：尝试再次激活 (ACTIVATE 语义重放)
        CompletableFuture<MvcResult> fAct = CompletableFuture.supplyAsync(() -> {
            try {
                latch.await();
                return mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                                .session((MockHttpSession) session1)
                                .with(csrf())
                                .header("Idempotency-Key", competingKey))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 线程 2：尝试停用 (DISABLE)
        CompletableFuture<MvcResult> fDis = CompletableFuture.supplyAsync(() -> {
            try {
                latch.await();
                return mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/disable")
                                .session((MockHttpSession) session2)
                                .with(csrf())
                                .header("Idempotency-Key", competingKey))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        latch.countDown();
        MvcResult rAct = fAct.get(10, TimeUnit.SECONDS);
        MvcResult rDis = fDis.get(10, TimeUnit.SECONDS);

        int stAct = rAct.getResponse().getStatus();
        int stDis = rDis.getResponse().getStatus();

        // 核心实证：由于码预先已存在，先胜出的一方必定 200，随后或并发的另一方必定被 409 IDEMPOTENCY_KEY_REUSED 拦截
        assertThat((stAct == 200 && stDis == 409) || (stDis == 200 && stAct == 409)).isTrue();
        MvcResult failedRes = stAct == 409 ? rAct : rDis;
        JsonNode errJson = objectMapper.readTree(failedRes.getResponse().getContentAsString());
        assertThat(errJson.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        // 统一幂等表该 key 仅有一条绑定记录
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public_trace_code_idempotency WHERE org_id = ? AND idempotency_key = ?",
                Integer.class, org.getId(), competingKey);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    @DisplayName("P0-4A 激活事务原子性回滚实证：通过临时 CHECK 约束触发幂等绑定失败，验证追溯码和幂等表均回滚为0条且 finally 恢复 schema")
    void testRollbackAtomicity_OnActivationFailure() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_ACT_RB_" + suffix, "激活回滚测试企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_act_rb_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_ACT_RB_" + suffix, "激活回滚产品", "ACTIVE");
        Long batchId = insertBatchDirect(org.getId(), product.getId(), "BATCH-ACT-RB-" + suffix, "ACTIVE");
        createdBatchIds.add(batchId);

        HttpSession session = login(operator.getUsername(), rawPassword);

        // 注入临时物理 CHECK 约束：让 public_trace_code_idempotency 的 ACTIVATE 插入失败
        String chkName = "chk_test_act_fail_" + suffix;
        jdbcTemplate.execute(String.format(
                "ALTER TABLE public_trace_code_idempotency ADD CONSTRAINT %s CHECK (action <> 'ACTIVATE')",
                chkName));

        String actKey = "idem-act-rb-fail-" + suffix;
        try {
            // 发起激活请求，由于事务内第二步幂等绑定触发 CHECK 约束失败抛出 500
            mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                            .session((MockHttpSession) session)
                            .with(csrf())
                            .header("Idempotency-Key", actKey))
                    .andExpect(status().is5xxServerError());

            // 核心验证数据库物理状态：public_trace_code 也被事务回滚为 0 条
            Integer codeCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public_trace_code WHERE batch_id = ?", Integer.class, batchId);
            assertThat(codeCount).isEqualTo(0);

            // 统一幂等表记录也回滚为 0 条
            Integer idemCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public_trace_code_idempotency WHERE batch_id = ?", Integer.class, batchId);
            assertThat(idemCount).isEqualTo(0);

        } finally {
            // 物理恢复 schema
            jdbcTemplate.execute("ALTER TABLE public_trace_code_idempotency DROP CHECK " + chkName);
        }
    }

    @Test
    @DisplayName("P0-4B 同一批次、两个不同幂等键并发激活：两个请求均200、publicId相同、码仅1条、幂等表有2条绑定")
    void testConcurrentActivation_SameBatchDifferentKeys() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_C1_" + suffix, "并发测试企业1");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_c1_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_C1_" + suffix, "并发产品1", "ACTIVE");
        Long batchId = insertBatchDirect(org.getId(), product.getId(), "BATCH-C1-" + suffix, "ACTIVE");
        createdBatchIds.add(batchId);

        // 分别独立登录获取两个会话，避免线程竞争
        HttpSession session1 = login(operator.getUsername(), rawPassword);
        HttpSession session2 = login(operator.getUsername(), rawPassword);
        String key1 = "idem-c1-k1-" + suffix;
        String key2 = "idem-c1-k2-" + suffix;

        CountDownLatch latch = new CountDownLatch(1);

        CompletableFuture<MvcResult> f1 = CompletableFuture.supplyAsync(() -> {
            try {
                latch.await();
                return mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                                .session((MockHttpSession) session1)
                                .with(csrf())
                                .header("Idempotency-Key", key1))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<MvcResult> f2 = CompletableFuture.supplyAsync(() -> {
            try {
                latch.await();
                return mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                                .session((MockHttpSession) session2)
                                .with(csrf())
                                .header("Idempotency-Key", key2))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        latch.countDown();
        MvcResult r1 = f1.get(10, TimeUnit.SECONDS);
        MvcResult r2 = f2.get(10, TimeUnit.SECONDS);

        assertThat(r1.getResponse().getStatus()).isEqualTo(200);
        assertThat(r2.getResponse().getStatus()).isEqualTo(200);

        String pubId1 = objectMapper.readTree(r1.getResponse().getContentAsString()).get("data").get("publicId").asText();
        String pubId2 = objectMapper.readTree(r2.getResponse().getContentAsString()).get("data").get("publicId").asText();
        assertThat(pubId1).isEqualTo(pubId2);

        // 数据库底层物理断言：public_trace_code 只有 1 条
        Integer codeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public_trace_code WHERE batch_id = ?", Integer.class, batchId);
        assertThat(codeCount).isEqualTo(1);

        // 统一幂等表有 2 条持久绑定记录
        Integer idemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public_trace_code_idempotency WHERE batch_id = ?", Integer.class, batchId);
        assertThat(idemCount).isEqualTo(2);
    }

    @Test
    @DisplayName("P0-4C 同一批次、相同幂等键并发激活：两个请求均200、publicId相同、码仅1条、幂等表仅1条绑定")
    void testConcurrentActivation_SameBatchSameKey() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_C2_" + suffix, "并发测试企业2");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_c2_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_C2_" + suffix, "并发产品2", "ACTIVE");
        Long batchId = insertBatchDirect(org.getId(), product.getId(), "BATCH-C2-" + suffix, "ACTIVE");
        createdBatchIds.add(batchId);

        HttpSession session1 = login(operator.getUsername(), rawPassword);
        HttpSession session2 = login(operator.getUsername(), rawPassword);
        String sameKey = "idem-c2-same-" + suffix;

        CountDownLatch latch = new CountDownLatch(1);

        CompletableFuture<MvcResult> f1 = CompletableFuture.supplyAsync(() -> {
            try {
                latch.await();
                return mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                                .session((MockHttpSession) session1)
                                .with(csrf())
                                .header("Idempotency-Key", sameKey))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<MvcResult> f2 = CompletableFuture.supplyAsync(() -> {
            try {
                latch.await();
                return mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                                .session((MockHttpSession) session2)
                                .with(csrf())
                                .header("Idempotency-Key", sameKey))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        latch.countDown();
        MvcResult r1 = f1.get(10, TimeUnit.SECONDS);
        MvcResult r2 = f2.get(10, TimeUnit.SECONDS);

        assertThat(r1.getResponse().getStatus()).isEqualTo(200);
        assertThat(r2.getResponse().getStatus()).isEqualTo(200);

        String pubId1 = objectMapper.readTree(r1.getResponse().getContentAsString()).get("data").get("publicId").asText();
        String pubId2 = objectMapper.readTree(r2.getResponse().getContentAsString()).get("data").get("publicId").asText();
        assertThat(pubId1).isEqualTo(pubId2);

        // 数据库底层物理断言：public_trace_code 只有 1 条
        Integer codeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public_trace_code WHERE batch_id = ?", Integer.class, batchId);
        assertThat(codeCount).isEqualTo(1);

        // 统一幂等表针对该 key 仅有 1 条绑定记录
        Integer idemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public_trace_code_idempotency WHERE org_id = ? AND idempotency_key = ?",
                Integer.class, org.getId(), sameKey);
        assertThat(idemCount).isEqualTo(1);
    }

    @Test
    @DisplayName("P0-4D 同一组织两个不同批次、同一幂等键并发激活：必为一成(200)一败(409 IDEMPOTENCY_KEY_REUSED)，触发 DuplicateKey 当前读恢复")
    void testConcurrentActivation_DifferentBatchesSameKey_TriggersDuplicateKeyAnd409() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_C3_" + suffix, "并发测试企业3");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_c3_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_C3_" + suffix, "并发产品3", "ACTIVE");
        Long batchId1 = insertBatchDirect(org.getId(), product.getId(), "BATCH-C3-1-" + suffix, "ACTIVE");
        Long batchId2 = insertBatchDirect(org.getId(), product.getId(), "BATCH-C3-2-" + suffix, "ACTIVE");
        createdBatchIds.add(batchId1);
        createdBatchIds.add(batchId2);

        HttpSession session1 = login(operator.getUsername(), rawPassword);
        HttpSession session2 = login(operator.getUsername(), rawPassword);
        String sharedKey = "idem-c3-shared-" + suffix;

        CountDownLatch latch = new CountDownLatch(1);

        CompletableFuture<MvcResult> f1 = CompletableFuture.supplyAsync(() -> {
            try {
                latch.await();
                return mockMvc.perform(post("/api/v1/batches/" + batchId1 + "/public-trace-code/activate")
                                .session((MockHttpSession) session1)
                                .with(csrf())
                                .header("Idempotency-Key", sharedKey))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<MvcResult> f2 = CompletableFuture.supplyAsync(() -> {
            try {
                latch.await();
                return mockMvc.perform(post("/api/v1/batches/" + batchId2 + "/public-trace-code/activate")
                                .session((MockHttpSession) session2)
                                .with(csrf())
                                .header("Idempotency-Key", sharedKey))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        latch.countDown();
        MvcResult r1 = f1.get(10, TimeUnit.SECONDS);
        MvcResult r2 = f2.get(10, TimeUnit.SECONDS);

        int st1 = r1.getResponse().getStatus();
        int st2 = r2.getResponse().getStatus();

        // 核心实证：只能一个 200，另一个必须 409 IDEMPOTENCY_KEY_REUSED
        assertThat((st1 == 200 && st2 == 409) || (st2 == 200 && st1 == 409)).isTrue();

        MvcResult failedRes = st1 == 409 ? r1 : r2;
        Long successBatchId = st1 == 200 ? batchId1 : batchId2;
        Long failedBatchId = st1 == 409 ? batchId1 : batchId2;

        JsonNode errJson = objectMapper.readTree(failedRes.getResponse().getContentAsString());
        assertThat(errJson.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        // 数据库物理状态断言：
        // 成功批次有且仅有 1 条追溯码
        Integer okCodeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public_trace_code WHERE batch_id = ?", Integer.class, successBatchId);
        assertThat(okCodeCount).isEqualTo(1);

        // 失败批次因事务回滚，码记录数为 0
        Integer failedCodeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public_trace_code WHERE batch_id = ?", Integer.class, failedBatchId);
        assertThat(failedCodeCount).isEqualTo(0);

        // 统一幂等表针对该 key 仅留存 1 条记录，且绑定的正是成功批次
        Long boundBatchId = jdbcTemplate.queryForObject(
                "SELECT batch_id FROM public_trace_code_idempotency WHERE org_id = ? AND idempotency_key = ?",
                Long.class, org.getId(), sharedKey);
        assertThat(boundBatchId).isEqualTo(successBatchId);
    }

    @Test
    @DisplayName("P0-5 停用事务原子性回滚实证：通过临时 CHECK 约束触发停用失败，验证事务完整回滚且 finally 恢复 schema")
    void testRollbackAtomicity_OnDisableFailure() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_DIS_RB_" + suffix, "停用回滚测试企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_dis_rb_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_DIS_RB_" + suffix, "停用回滚产品", "ACTIVE");
        Long batchId = insertBatchDirect(org.getId(), product.getId(), "BATCH-DIS-RB-" + suffix, "ACTIVE");
        createdBatchIds.add(batchId);

        HttpSession session = login(operator.getUsername(), rawPassword);

        // 1. 正常激活
        String actKey = "idem-act-rb-" + suffix;
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                        .session((MockHttpSession) session).with(csrf()).header("Idempotency-Key", actKey))
                .andExpect(status().isOk());

        // 2. 注入临时物理 CHECK 约束：禁止更新为 DISABLED
        String chkName = "chk_test_dis_fail_" + suffix;
        jdbcTemplate.execute(String.format(
                "ALTER TABLE public_trace_code ADD CONSTRAINT %s CHECK (status != 'DISABLED')",
                chkName));

        String disKey = "idem-dis-rb-fail-" + suffix;
        try {
            // 发起停用请求，由于触发 CHECK 约束失败
            mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/disable")
                            .session((MockHttpSession) session)
                            .with(csrf())
                            .header("Idempotency-Key", disKey))
                    .andExpect(status().is5xxServerError());

            // 验证数据库物理状态：码保持 ACTIVE，disabled_at 为空
            String codeStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM public_trace_code WHERE batch_id = ?", String.class, batchId);
            String disabledAt = jdbcTemplate.queryForObject(
                    "SELECT disabled_at FROM public_trace_code WHERE batch_id = ?", String.class, batchId);
            assertThat(codeStatus).isEqualTo("ACTIVE");
            assertThat(disabledAt).isNull();

            // 验证统一幂等表无该停用 key 残留记录
            Integer disKeyCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM public_trace_code_idempotency WHERE org_id = ? AND idempotency_key = ?",
                    Integer.class, org.getId(), disKey);
            assertThat(disKeyCount).isEqualTo(0);

        } finally {
            // 物理恢复 schema
            jdbcTemplate.execute("ALTER TABLE public_trace_code DROP CHECK " + chkName);
        }
    }

    @Test
    @DisplayName("P0-5 多事件完全相同时间戳排序稳定性测试：公开 timeline 严格按 ID ASC 稳定单调递增排序")
    void testTimelineDeterministicOrder_SameTimestampOrderedByIdAsc() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_ORD_" + suffix, "排序测试企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_ord_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_ORD_" + suffix, "排序产品", "ACTIVE");
        Site site = createSite(org.getId(), "SITE_ORD_" + suffix, "排序测试冷库");
        Long batchId = insertBatchDirect(org.getId(), product.getId(), "BATCH-ORD-" + suffix, "ACTIVE");
        createdBatchIds.add(batchId);

        HttpSession session = login(operator.getUsername(), rawPassword);

        // 插入 3 条完全相同业务时间 (occurredAt) 与登记时间 (recordedAt) 的有效事件
        LocalDateTime sameTime = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
        String sql = """
                INSERT INTO trace_event (batch_id, org_id, event_type, site_id, occurred_at, recorded_at,
                                         data_source, summary, details_json, status, idempotency_key, version, is_deleted, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'MANUAL', ?, '{}', 'SUBMITTED', ?, 0, 0, NOW(6), NOW(6))
                """;

        jdbcTemplate.update(sql, batchId, org.getId(), "SOURCE", site.getId(), sameTime, sameTime, "事件A", "idem-evt-1-" + suffix);
        Long id1 = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdTraceEventIds.add(id1);

        jdbcTemplate.update(sql, batchId, org.getId(), "TRANSPORT", site.getId(), sameTime, sameTime, "事件B", "idem-evt-2-" + suffix);
        Long id2 = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdTraceEventIds.add(id2);

        jdbcTemplate.update(sql, batchId, org.getId(), "FREEZE", site.getId(), sameTime, sameTime, "事件C", "idem-evt-3-" + suffix);
        Long id3 = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdTraceEventIds.add(id3);

        // 激活公开追溯码
        String actKey = "idem-ord-" + suffix;
        MvcResult actRes = mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                        .session((MockHttpSession) session).with(csrf()).header("Idempotency-Key", actKey))
                .andExpect(status().isOk())
                .andReturn();
        String pubId = objectMapper.readTree(actRes.getResponse().getContentAsString()).get("data").get("publicId").asText();

        // 消费者查询时间线
        MvcResult res = mockMvc.perform(get("/api/public/v1/public/traces/" + pubId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode timeline = objectMapper.readTree(res.getResponse().getContentAsString()).get("data").get("timeline");
        assertThat(timeline.size()).isEqualTo(3);

        // 验证受控标签与根据 ID ASC 的严格确定性顺序：SOURCE -> TRANSPORT -> FREEZE
        assertThat(timeline.get(0).get("event").asText()).isEqualTo("原料采收/出塘");
        assertThat(timeline.get(1).get("event").asText()).isEqualTo("冷链干线运输");
        assertThat(timeline.get(2).get("event").asText()).isEqualTo("速冻冷冻");
    }

    @Test
    @DisplayName("P0-3 机密哨兵字符串测试：summary/detailsJson/operator/site/org/idempotency 中的机密文本绝不泄露")
    void testConfidentialSentinelStringNeverLeaked() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String secretOrg = "SENTINEL_SECRET_ORG_NAME_" + suffix;
        String secretSummary = "SENTINEL_SECRET_EVENT_SUMMARY_" + suffix;
        String secretDetails = "SENTINEL_SECRET_DETAILS_EXTRA_FIELD_" + suffix;
        String secretSite = "SENTINEL_SECRET_SITE_NAME_" + suffix;

        Organization org = createOrg("ORG_SEC_" + suffix, secretOrg);
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_sec_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_SEC_" + suffix, "机密测试产品", "ACTIVE");
        Site site = createSite(org.getId(), "SITE_SEC_" + suffix, secretSite);
        Long batchId = insertBatchDirect(org.getId(), product.getId(), "BATCH-SEC-" + suffix, "ACTIVE");
        createdBatchIds.add(batchId);

        // 插入带机密 summary 与 details_json 的事件
        String eventSql = """
                INSERT INTO trace_event (batch_id, org_id, event_type, site_id, occurred_at, recorded_at,
                                         data_source, summary, details_json, status, idempotency_key, version, is_deleted, created_at, updated_at)
                VALUES (?, ?, 'SOURCE', ?, NOW(6), NOW(6), 'MANUAL', ?, ?, 'SUBMITTED', ?, 0, 0, NOW(6), NOW(6))
                """;
        String detailsJson = "{\"secret\": \"" + secretDetails + "\"}";
        jdbcTemplate.update(eventSql, batchId, org.getId(), site.getId(), secretSummary, detailsJson, "idem-evt-sec-" + suffix);
        Long eventId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdTraceEventIds.add(eventId);

        HttpSession session = login(operator.getUsername(), rawPassword);
        String actKey = "idem-sec-key-" + suffix;
        MvcResult actRes = mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                        .session((MockHttpSession) session).with(csrf()).header("Idempotency-Key", actKey))
                .andExpect(status().isOk())
                .andReturn();
        String pubId = objectMapper.readTree(actRes.getResponse().getContentAsString()).get("data").get("publicId").asText();

        // 消费者查询全文断言
        MvcResult pubRes = mockMvc.perform(get("/api/public/v1/public/traces/" + pubId))
                .andExpect(status().isOk())
                .andReturn();

        String body = pubRes.getResponse().getContentAsString();
        assertThat(body).doesNotContain(secretOrg);
        assertThat(body).doesNotContain(secretSummary);
        assertThat(body).doesNotContain(secretDetails);
        assertThat(body).doesNotContain(secretSite);
        assertThat(body).doesNotContain(actKey);
        assertThat(body).doesNotContain("detailsJson");
        assertThat(body).doesNotContain("is_deleted");
    }

    @Test
    @DisplayName("P0-5 未知码与 DISABLED 码 404 响应字段一致性对比 (外部不可探测)")
    void testUnknownAndDisabled404ResponseIndistinguishable() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_404_" + suffix, "404一致性企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_404_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_404_" + suffix, "404产品", "ACTIVE");
        Long batchId = insertBatchDirect(org.getId(), product.getId(), "BATCH-404-" + suffix, "ACTIVE");
        createdBatchIds.add(batchId);

        HttpSession session = login(operator.getUsername(), rawPassword);

        // 激活并停用
        String actKey = "idem-404-act-" + suffix;
        MvcResult actRes = mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                        .session((MockHttpSession) session).with(csrf()).header("Idempotency-Key", actKey))
                .andExpect(status().isOk())
                .andReturn();
        String disabledPubId = objectMapper.readTree(actRes.getResponse().getContentAsString()).get("data").get("publicId").asText();

        String disKey = "idem-404-dis-" + suffix;
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/disable")
                        .session((MockHttpSession) session).with(csrf()).header("Idempotency-Key", disKey))
                .andExpect(status().isOk());

        // 1. 查询被停用的码
        MvcResult disabledRes = mockMvc.perform(get("/api/public/v1/public/traces/" + disabledPubId))
                .andExpect(status().isNotFound())
                .andReturn();

        // 2. 查询一个完全随机生成的有效格式未知码
        String unknownPubId = PublicTraceIdGenerator.generatePublicId();
        MvcResult unknownRes = mockMvc.perform(get("/api/public/v1/public/traces/" + unknownPubId))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode disabledNode = objectMapper.readTree(disabledRes.getResponse().getContentAsString());
        JsonNode unknownNode = objectMapper.readTree(unknownRes.getResponse().getContentAsString());

        // 断言响应完全同形且关键字段完全一致
        assertThat(disabledNode.get("code").asText()).isEqualTo(unknownNode.get("code").asText()).isEqualTo("PUBLIC_TRACE_NOT_FOUND");
        assertThat(disabledNode.get("title").asText()).isEqualTo(unknownNode.get("title").asText());
        assertThat(disabledNode.get("detail").asText()).isEqualTo(unknownNode.get("detail").asText());
    }

    @Test
    @DisplayName("P0-5 权限反例：平台管理员角色 (PLATFORM) 尝试写操作被拒绝 (403 ACCESS_DENIED)")
    void testPlatformAdminWriteOperationRejected() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization platOrg = createOrg("ORG_PLT_" + suffix, "平台企业");
        Role platAdminRole = getOrCreateRole("ADMIN", "平台管理员", "PLATFORM");
        String rawPassword = "TestPassword123!";
        AppUser platAdmin = createUser(platOrg.getId(), "admin_" + suffix, rawPassword);
        bindUserRole(platAdmin.getId(), platAdminRole.getId());

        Product product = createProduct("PRD_PLT_" + suffix, "平台产品", "ACTIVE");
        Long batchId = insertBatchDirect(platOrg.getId(), product.getId(), "BATCH-PLT-" + suffix, "ACTIVE");
        createdBatchIds.add(batchId);

        HttpSession session = login(platAdmin.getUsername(), rawPassword);

        // 平台管理员尝试激活批次 -> 403 ACCESS_DENIED
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-plt-act-" + suffix))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 平台管理员尝试停用批次 -> 403 ACCESS_DENIED
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/disable")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-plt-dis-" + suffix))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("P0-4 安全反例：公开路径匿名 POST 请求被拦截且不形成 CSRF 绕过")
    void testAnonymousPostToPublicTraceRejected() throws Exception {
        String randomId = PublicTraceIdGenerator.generatePublicId();
        mockMvc.perform(post("/api/public/v1/public/traces/" + randomId))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    assertThat(sc).isIn(401, 403);
                });
    }

    @Test
    @DisplayName("Flyway V6 物理约束验证：一批一码、统一幂等唯一约束、状态枚举与形状 CHECK 约束底层拦截")
    void testFlywayV6_PhysicalCheckConstraints_EnforcedByDatabase() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_V6_" + suffix, "V6物理约束企业");
        Product product = createProduct("PRD_V6_" + suffix, "V6产品", "ACTIVE");
        Long batchId = insertBatchDirect(org.getId(), product.getId(), "BATCH-V6-" + suffix, "ACTIVE");
        createdBatchIds.add(batchId);

        // 1. 正常插入第一条
        String pub1 = PublicTraceIdGenerator.generatePublicId();
        String hash1 = PublicTraceIdGenerator.computeTokenHash(pub1);
        jdbcTemplate.update("""
                INSERT INTO public_trace_code (batch_id, org_id, public_id, token_hash, status, activated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', NOW(6))
                """, batchId, org.getId(), pub1, hash1);

        // 2. 验证 uk_public_trace_code_batch (一批一码硬件级唯一约束拦截)
        String pub2 = PublicTraceIdGenerator.generatePublicId();
        String hash2 = PublicTraceIdGenerator.computeTokenHash(pub2);
        DataAccessException exBatch = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO public_trace_code (batch_id, org_id, public_id, token_hash, status, activated_at)
                        VALUES (?, ?, ?, ?, 'ACTIVE', NOW(6))
                        """, batchId, org.getId(), pub2, hash2));
        assertThat(exBatch.getMessage()).contains("uk_public_trace_code_batch");

        // 3. 验证 uk_ptc_idem_org_key (统一幂等表同组织重复键真实物理唯一索引拦截)
        String sharedKey = "idem-v6-k1-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO public_trace_code_idempotency (org_id, idempotency_key, action, batch_id, public_trace_code_id, request_hash, created_at)
                VALUES (?, ?, 'ACTIVATE', ?, 1, 'HASH1', NOW(6))
                """, org.getId(), sharedKey, batchId);

        DataAccessException exIdem = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO public_trace_code_idempotency (org_id, idempotency_key, action, batch_id, public_trace_code_id, request_hash, created_at)
                        VALUES (?, ?, 'DISABLE', ?, 1, 'HASH2', NOW(6))
                        """, org.getId(), sharedKey, batchId));
        assertThat(exIdem.getMessage()).contains("uk_ptc_idem_org_key");

        // 4. 验证 chk_public_trace_code_status (非法状态值拦截)
        Long batchId2 = insertBatchDirect(org.getId(), product.getId(), "BATCH-V6-2-" + suffix, "ACTIVE");
        createdBatchIds.add(batchId2);
        DataAccessException exStatus = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO public_trace_code (batch_id, org_id, public_id, token_hash, status, activated_at)
                        VALUES (?, ?, ?, ?, 'INVALID_STATUS', NOW(6))
                        """, batchId2, org.getId(), pub2, hash2));
        assertThat(exStatus.getMessage()).containsAnyOf("chk_public_trace_code_status", "chk_public_trace_code_disable_shape");

        // 5. 验证 chk_public_trace_code_disable_shape (ACTIVE 状态下携带 disabled_at 违规拦截)
        DataAccessException exShape = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO public_trace_code (batch_id, org_id, public_id, token_hash, status, activated_at, disabled_at)
                        VALUES (?, ?, ?, ?, 'ACTIVE', NOW(6), NOW(6))
                        """, batchId2, org.getId(), pub2, hash2));
        assertThat(exShape.getMessage()).contains("chk_public_trace_code_disable_shape");
    }

    @Test
    @DisplayName("V6 升级兼容性实证：public_trace_code.org_id 物理 NOT NULL 且与 batch.org_id 完全一致，孤儿插入物理拒绝")
    void testV6_OrgIdNotNull_AndConsistentWithBatchOrgId() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_V6_COMPAT_" + suffix, "V6兼容性验证企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_v6_compat_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_V6_C_" + suffix, "V6兼容产品", "ACTIVE");
        Long batchId = insertBatchDirect(org.getId(), product.getId(), "BATCH-V6-C-" + suffix, "ACTIVE");
        createdBatchIds.add(batchId);

        HttpSession session = login(operator.getUsername(), rawPassword);
        String actKey = "idem-v6-compat-" + suffix;

        // 1. 通过业务应用服务激活追溯码
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/public-trace-code/activate")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", actKey))
                .andExpect(status().isOk());

        // 2. 底层物理查询：证明最终 org_id 非空且与 batch.org_id 完全一致
        Long actualOrgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM public_trace_code WHERE batch_id = ?", Long.class, batchId);
        assertThat(actualOrgId).isNotNull();
        assertThat(actualOrgId).isEqualTo(org.getId());

        // 3. 验证 MySQL 元数据层面 org_id 列物理设置为 NOT NULL (IS_NULLABLE = 'NO')
        String isNullable = jdbcTemplate.queryForObject("""
                SELECT IS_NULLABLE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'public_trace_code' AND COLUMN_NAME = 'org_id'
                """, String.class);
        assertThat(isNullable).isEqualTo("NO");

        // 4. 验证直接插入缺失 org_id 或 org_id 为 null 的孤儿数据时，被 MySQL 底层硬件级约束物理拒绝
        String orphanPubId = PublicTraceIdGenerator.generatePublicId();
        String orphanHash = PublicTraceIdGenerator.computeTokenHash(orphanPubId);
        Long batchIdOrphan = insertBatchDirect(org.getId(), product.getId(), "BATCH-V6-ORP-" + suffix, "ACTIVE");
        createdBatchIds.add(batchIdOrphan);

        assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO public_trace_code (batch_id, org_id, public_id, token_hash, status)
                        VALUES (?, NULL, ?, ?, 'ACTIVE')
                        """, batchIdOrphan, orphanPubId, orphanHash));
    }

    // 辅助数据构建方法
    private Organization createOrg(String orgNo, String name) {
        Organization org = new Organization();
        org.setOrgNo(orgNo);
        org.setName(name);
        org.setOrgType("SOURCE");
        org.setStatus("ACTIVE");
        org.setVersion(0L);
        org.setIsDeleted(0);
        org.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        org.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        organizationMapper.insert(org);
        createdOrgIds.add(org.getId());
        return org;
    }

    private Role getOrCreateRole(String roleCode, String roleName, String dataScope) {
        Role existing = roleMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Role>()
                        .eq(Role::getRoleCode, roleCode)
        );
        if (existing != null) {
            return existing;
        }
        Role role = new Role();
        role.setRoleCode(roleCode);
        role.setName(roleName);
        role.setScopeType(dataScope);
        role.setStatus("ACTIVE");
        role.setVersion(0L);
        role.setIsDeleted(0);
        role.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        role.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        roleMapper.insert(role);
        createdRoleIds.add(role.getId());
        return role;
    }

    private AppUser createUser(Long orgId, String username, String rawPassword) {
        AppUser user = new AppUser();
        user.setOrgId(orgId);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setStatus("ACTIVE");
        user.setVersion(0L);
        user.setIsDeleted(0);
        user.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        appUserMapper.insert(user);
        createdUserIds.add(user.getId());
        return user;
    }

    private void bindUserRole(Long userId, Long roleId) {
        UserRole userRole = new UserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        userRole.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userRoleMapper.insert(userRole);
    }

    private Product createProduct(String productCode, String publicName, String status) {
        Product product = new Product();
        product.setProductCode(productCode);
        product.setPublicName(publicName);
        product.setCategory("FISH");
        product.setSpecification("500g-600g/条");
        product.setSourceType("DOMESTIC_CAPTURE");
        product.setBaseUnitCode("kg");
        product.setStatus(status);
        product.setVersion(0L);
        product.setIsDeleted(0);
        product.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        product.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        productMapper.insert(product);
        createdProductIds.add(product.getId());
        return product;
    }

    private Site createSite(Long orgId, String siteNo, String name) {
        Site site = new Site();
        site.setOrgId(orgId);
        site.setSiteNo(siteNo);
        site.setName(name);
        site.setSiteType("FACTORY");
        site.setTimezone("Asia/Shanghai");
        site.setStatus("ACTIVE");
        site.setVersion(0L);
        site.setIsDeleted(0);
        site.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        site.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        siteMapper.insert(site);
        createdSiteIds.add(site.getId());
        return site;
    }

    private Long insertBatchDirect(Long orgId, Long productId, String batchNo, String status) {
        Batch batch = new Batch();
        batch.setOrgId(orgId);
        batch.setCreationOrgId(orgId);
        batch.setProductId(productId);
        batch.setTraceBatchNo("TB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        batch.setExternalBatchNo(batchNo);
        batch.setBatchType("SOURCE");
        batch.setQuantity(new BigDecimal("100.000"));
        batch.setUnitCode("kg");
        batch.setOriginType("DOMESTIC_CAPTURE");
        batch.setOriginText("东海渔场作业区");
        batch.setFlowStatus(status);
        batch.setRiskStatus("NORMAL");
        batch.setCreationIdempotencyKey("idem-batch-" + UUID.randomUUID());
        batch.setVersion(0L);
        batch.setIsDeleted(0);
        batch.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchMapper.insert(batch);
        return batch.getId();
    }

    private Batch createAndSubmitBatch(HttpSession session, Long productId, String batchNo, String originText) throws Exception {
        BatchCreateRequest req = new BatchCreateRequest(
                batchNo, productId, "SOURCE", new BigDecimal("100.000"), "kg",
                "DOMESTIC_CAPTURE", originText,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), 180
        );
        String createKey = "idem-bc-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult res = mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        Long batchId = objectMapper.readTree(res.getResponse().getContentAsString()).get("data").get("id").asLong();
        createdBatchIds.add(batchId);

        // 提交激活
        BatchSubmitRequest submitReq = new BatchSubmitRequest(0L);
        mockMvc.perform(post("/api/v1/batches/" + batchId + "/submit")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitReq)))
                .andExpect(status().isOk());

        return batchMapper.selectByIdIgnoreTenant(batchId);
    }

    private HttpSession login(String username, String rawPassword) throws Exception {
        LoginRequest loginReq = new LoginRequest(username, rawPassword);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        return result.getRequest().getSession(false);
    }
}
