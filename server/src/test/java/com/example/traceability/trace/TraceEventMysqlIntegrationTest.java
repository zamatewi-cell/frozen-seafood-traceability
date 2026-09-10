package com.example.traceability.trace;

import com.example.traceability.batch.domain.Batch;
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
import com.example.traceability.trace.dto.CorrectTraceEventRequest;
import com.example.traceability.trace.dto.CreateTraceEventRequest;
import com.example.traceability.trace.mapper.TraceEventMapper;
import jakarta.servlet.http.HttpSession;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基于真实 MySQL 8.4 的追溯事件与更正工作流端到端集成测试。
 * <p>
 * 受环境变量 {@code MYSQL_IT_ENABLED=true} 控制。
 * 覆盖追溯事件追加记录、更正链式关联、状态流转、组织隔离、并发幂等读写恢复与 Flyway V5 物理约束验证。
 * 测试结束后彻底物理清理，不污染静态数据。
 * </p>
 *
 * @author Seafood Traceability Team
 * @since 0.1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "true")
class TraceEventMysqlIntegrationTest {

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
    private BatchMapper batchMapper;

    @Autowired
    private SiteMapper siteMapper;

    @Autowired
    private TraceEventMapper traceEventMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdTraceEventIds = new ArrayList<>();
    private final List<Long> createdBatchIds = new ArrayList<>();
    private final List<Long> createdSiteIds = new ArrayList<>();
    private final List<Long> createdProductIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdOrgIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // 1. 清理追溯事件
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM trace_event WHERE org_id = ?", orgId);
        }
        for (Long eventId : createdTraceEventIds) {
            jdbcTemplate.update("DELETE FROM trace_event WHERE id = ?", eventId);
        }
        createdTraceEventIds.clear();

        // 2. 清理批次
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM batch WHERE org_id = ?", orgId);
        }
        for (Long batchId : createdBatchIds) {
            jdbcTemplate.update("DELETE FROM batch WHERE id = ?", batchId);
        }
        createdBatchIds.clear();

        // 3. 清理场所
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM site WHERE org_id = ?", orgId);
        }
        for (Long siteId : createdSiteIds) {
            jdbcTemplate.update("DELETE FROM site WHERE id = ?", siteId);
        }
        createdSiteIds.clear();

        // 4. 清理产品
        for (Long productId : createdProductIds) {
            jdbcTemplate.update("DELETE FROM product WHERE id = ?", productId);
        }
        createdProductIds.clear();

        // 5. 清理用户与关联角色
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUserIds.clear();

        for (Long roleId : createdRoleIds) {
            jdbcTemplate.update("DELETE FROM user_role WHERE role_id = ?", roleId);
            jdbcTemplate.update("DELETE FROM role WHERE id = ?", roleId);
        }
        createdRoleIds.clear();

        // 6. 清理组织
        for (Long orgId : createdOrgIds) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", orgId);
        }
        createdOrgIds.clear();
    }

    @Test
    @DisplayName("端到端验证 - 追溯事件录入、更正链式流转、双时间输出、组织隔离与CLOSED批次更正全流程")
    void testTraceEventLifecycle_EndToEnd_WithOrgIsolationAndIdempotency() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // 1. 动态创建组织 A 与 组织 B
        Organization orgA = createOrg("ORG_A_" + suffix, "测试远洋捕捞A");
        Organization orgB = createOrg("ORG_B_" + suffix, "测试冷链物流B");

        // 2. 获取或创建角色 OPERATOR
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");

        // 3. 创建用户 User A 与 User B
        String rawPassword = "TestPassword123!";
        AppUser userA = createUser(orgA.getId(), "usera_" + suffix, rawPassword);
        bindUserRole(userA.getId(), opRole.getId());

        AppUser userB = createUser(orgB.getId(), "userb_" + suffix, rawPassword);
        bindUserRole(userB.getId(), opRole.getId());

        // 4. 创建产品与场所
        Product product = createProduct("PRD_EVT_" + suffix, "极冻白虾", "ACTIVE");
        Site siteA = createSite(orgA.getId(), "SITE_A_" + suffix, "舟山码头冷库", "ACTIVE");
        Site siteInactive = createSite(orgA.getId(), "SITE_INA_" + suffix, "停用库区", "INACTIVE");
        Site siteB = createSite(orgB.getId(), "SITE_B_" + suffix, "宁波分销中心", "ACTIVE");

        // 5. 登录获取 Session
        HttpSession sessionA = loginAndGetSession(userA.getUsername(), rawPassword);
        HttpSession sessionB = loginAndGetSession(userB.getUsername(), rawPassword);

        // 6. 创建批次 A (DRAFT)
        String batchNo = "BATCH-EVT-" + suffix;
        BatchCreateRequest batchReq = new BatchCreateRequest(
                batchNo, product.getId(), "SOURCE",
                new BigDecimal("100.000"), "kg", "DOMESTIC_CAPTURE", "东海捕捞区",
                LocalDate.now(), null, null, 180
        );
        MvcResult batchCreateRes = mockMvc.perform(post("/api/v1/batches")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-batch-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchReq)))
                .andExpect(status().isCreated())
                .andReturn();
        Long batchIdA = objectMapper.readTree(batchCreateRes.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdBatchIds.add(batchIdA);

        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-09-09T10:00:00Z");
        String eventKey1 = "idem-evt-1-" + suffix;

        // 7. 批次状态矩阵约束：在 DRAFT 批次上创建事件 -> 422 BATCH_FLOW_BLOCKED
        CreateTraceEventRequest createReq1 = new CreateTraceEventRequest(
                "SOURCE", occurredAt, siteA.getId(), "MANUAL", "东海首批捕捞起网",
                Map.of("seaArea", "舟山海域", "waterTemp", -1.5)
        );
        mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/events")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", eventKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_FLOW_BLOCKED"));

        // 8. 提交批次 A (DRAFT -> ACTIVE)
        mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/submit")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchSubmitRequest(0L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 9. 关联停用场所创建事件 -> 422 SITE_NOT_ACTIVE
        CreateTraceEventRequest reqInactiveSite = new CreateTraceEventRequest(
                "SOURCE", occurredAt, siteInactive.getId(), "MANUAL", "停用场所起网", null
        );
        mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/events")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", eventKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqInactiveSite)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SITE_NOT_ACTIVE"));

        // 10. 跨组织关联 Site B 创建事件 -> 403 ORG_SCOPE_DENIED
        CreateTraceEventRequest reqCrossOrgSite = new CreateTraceEventRequest(
                "SOURCE", occurredAt, siteB.getId(), "MANUAL", "跨组织场所起网", null
        );
        mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/events")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", eventKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqCrossOrgSite)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));

        // 11. 正常创建合法事件 1 (POST /api/v1/batches/{batchId}/events) -> 201 Created
        MvcResult eventRes1 = mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/events")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", eventKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.eventType").value("SOURCE"))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.occurredAt").exists())
                .andExpect(jsonPath("$.data.recordedAt").exists())
                .andExpect(jsonPath("$.data.summary").value("东海首批捕捞起网"))
                .andExpect(jsonPath("$.data.detailsJson.seaArea").value("舟山海域"))
                .andExpect(jsonPath("$.data.correctsEventId").doesNotExist())
                .andExpect(jsonPath("$.data.orgId").value(orgA.getId()))
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist())
                .andReturn();

        Long eventId1 = objectMapper.readTree(eventRes1.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdTraceEventIds.add(eventId1);

        // 12. 相同幂等键相同载荷重试 -> 201 Created，返回原事件 ID
        mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/events")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", eventKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(eventId1));

        // 13. 相同幂等键不同载荷 -> 409 IDEMPOTENCY_CONFLICT
        CreateTraceEventRequest createConflict = new CreateTraceEventRequest(
                "PURCHASE", occurredAt, siteA.getId(), "MANUAL", "语义冲突载荷", null
        );
        mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/events")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", eventKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createConflict)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 14. 组织间隔离：User B 访问批次 A 的事件列表 -> 403 ORG_SCOPE_DENIED
        mockMvc.perform(get("/api/v1/batches/" + batchIdA + "/events")
                        .session((MockHttpSession) sessionB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));

        // 15. User A 查询事件列表 -> 200 OK，包含事件 1
        mockMvc.perform(get("/api/v1/batches/" + batchIdA + "/events")
                        .session((MockHttpSession) sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(eventId1))
                .andExpect(jsonPath("$.data[0].status").value("SUBMITTED"));

        // 16. 链式更正：对事件 1 发起更正 (POST /api/v1/batches/{batchId}/events/{eventId}/corrections)
        String correctKey1 = "idem-corr-1-" + suffix;
        CorrectTraceEventRequest correctReq1 = new CorrectTraceEventRequest(
                "SOURCE", occurredAt, siteA.getId(), "MANUAL", "东海捕捞起网修正",
                Map.of("seaArea", "舟山海域外缘", "waterTemp", -1.8), "修正捕捞经纬度偏差点"
        );
        MvcResult correctRes1 = mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/events/" + eventId1 + "/corrections")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", correctKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctReq1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.eventType").value("SOURCE"))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.correctsEventId").value(eventId1))
                .andExpect(jsonPath("$.data.correctionReason").value("修正捕捞经纬度偏差点"))
                .andReturn();

        Long eventId2 = objectMapper.readTree(correctRes1.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdTraceEventIds.add(eventId2);

        // 17. 数据库物理状态核查：原事件 1 状态已变为 CORRECTED
        String status1 = jdbcTemplate.queryForObject("SELECT status FROM trace_event WHERE id = ?", String.class, eventId1);
        assertThat(status1).isEqualTo("CORRECTED");

        // 18. 事件列表查询：包含两条事件，原记录 CORRECTED，新记录 SUBMITTED
        mockMvc.perform(get("/api/v1/batches/" + batchIdA + "/events")
                        .session((MockHttpSession) sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.id == " + eventId1 + ")].status").value("CORRECTED"))
                .andExpect(jsonPath("$.data[?(@.id == " + eventId2 + ")].status").value("SUBMITTED"));

        // 19. 链式更正防分叉：再次对已被更正的原事件 1 发起更正 (不同幂等键) -> 409 EVENT_ALREADY_CORRECTED
        CorrectTraceEventRequest forkReq = new CorrectTraceEventRequest(
                "SOURCE", occurredAt, siteA.getId(), "MANUAL", "试图分叉更正", null, "非法二次更正"
        );
        mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/events/" + eventId1 + "/corrections")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-fork-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(forkReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ALREADY_CORRECTED"));

        // 20. 更正重试在状态检查前识别：使用相同幂等键再次请求更正事件 1 -> 201 Created，安全返回原更正事件 2
        mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/events/" + eventId1 + "/corrections")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", correctKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctReq1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(eventId2));

        // 21. 批次状态矩阵：CLOSED 批次禁止创建新事件，但允许合规发起链式更正
        jdbcTemplate.update("UPDATE batch SET status = 'CLOSED' WHERE id = ?", batchIdA);

        CreateTraceEventRequest closedNewReq = new CreateTraceEventRequest(
                "PROCESS", occurredAt, siteA.getId(), "MANUAL", "归档批次追加事件", null
        );
        mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/events")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-closed-new-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(closedNewReq)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_FLOW_BLOCKED"));

        // 在 CLOSED 批次上对最新版本 eventId2 发起更正 -> 201 Created
        CorrectTraceEventRequest closedCorrReq = new CorrectTraceEventRequest(
                "SOURCE", occurredAt, siteA.getId(), "MANUAL", "归档批次审计更正", null, "历史审计校准"
        );
        MvcResult correctRes2 = mockMvc.perform(post("/api/v1/batches/" + batchIdA + "/events/" + eventId2 + "/corrections")
                        .session((MockHttpSession) sessionA)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-closed-corr-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(closedCorrReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.correctsEventId").value(eventId2))
                .andExpect(jsonPath("$.data.correctionReason").value("历史审计校准"))
                .andReturn();

        Long eventId3 = objectMapper.readTree(correctRes2.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdTraceEventIds.add(eventId3);
    }

    @Test
    @DisplayName("真实 MySQL 并发幂等双更正: 两个并发请求携带相同幂等键同时更正，只生成一行更正记录且返回相同更正 ID")
    void testConcurrentSameKeyCorrection_IdempotentRecovery() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_CONC_C_" + suffix, "并发更正企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser opUser = createUser(org.getId(), "op_conc_c_" + suffix, rawPassword);
        bindUserRole(opUser.getId(), opRole.getId());
        Product product = createProduct("PRD_CONC_C_" + suffix, "并发更正产品", "ACTIVE");
        Site site = createSite(org.getId(), "SITE_C_" + suffix, "并发更正车间", "ACTIVE");

        HttpSession session = loginAndGetSession(opUser.getUsername(), rawPassword);

        // 创建并激活批次
        Batch batch = new Batch();
        batch.setOrgId(org.getId());
        batch.setProductId(product.getId());
        batch.setBatchNo("BATCH-CONC-C-" + suffix);
        batch.setBatchType("SOURCE");
        batch.setQuantity(new BigDecimal("50.000"));
        batch.setUnitCode("kg");
        batch.setOriginType("DOMESTIC_CAPTURE");
        batch.setOriginText("并发原产地");
        batch.setStatus("ACTIVE");
        batch.setCreationIdempotencyKey("idem-batch-c-" + suffix);
        batch.setVersion(1L);
        batch.setIsDeleted(0);
        batch.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchMapper.insert(batch);
        createdBatchIds.add(batch.getId());

        // 插入待更正的原始事件
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-09-09T12:00:00Z");
        CreateTraceEventRequest createReq = new CreateTraceEventRequest(
                "PROCESS", occurredAt, site.getId(), "MANUAL", "初始加工工序", null
        );
        MvcResult origResult = mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/events")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-orig-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        Long origEventId = objectMapper.readTree(origResult.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdTraceEventIds.add(origEventId);

        String sameCorrectionKey = "idem-same-corr-" + suffix;
        CorrectTraceEventRequest corrReq = new CorrectTraceEventRequest(
                "PROCESS", occurredAt, site.getId(), "MANUAL", "并发修正加工参数", null, "传感器零点校准"
        );

        CountDownLatch startLatch = new CountDownLatch(1);

        CompletableFuture<MvcResult> future1 = CompletableFuture.supplyAsync(() -> {
            try {
                startLatch.await();
                return mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/events/" + origEventId + "/corrections")
                                .session((MockHttpSession) session)
                                .with(csrf())
                                .header("Idempotency-Key", sameCorrectionKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(corrReq)))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<MvcResult> future2 = CompletableFuture.supplyAsync(() -> {
            try {
                startLatch.await();
                return mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/events/" + origEventId + "/corrections")
                                .session((MockHttpSession) session)
                                .with(csrf())
                                .header("Idempotency-Key", sameCorrectionKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(corrReq)))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        startLatch.countDown();
        CompletableFuture.allOf(future1, future2).join();

        MvcResult result1 = future1.get();
        MvcResult result2 = future2.get();

        assertThat(result1.getResponse().getStatus()).isEqualTo(201);
        assertThat(result2.getResponse().getStatus()).isEqualTo(201);

        Long corrId1 = objectMapper.readTree(result1.getResponse().getContentAsString()).path("data").path("id").asLong();
        Long corrId2 = objectMapper.readTree(result2.getResponse().getContentAsString()).path("data").path("id").asLong();

        // 关键断言：两个并发请求必须返回同一个更正事件 ID
        assertThat(corrId1).isEqualTo(corrId2);
        createdTraceEventIds.add(corrId1);

        // 数据库物理核查：针对该 target 原事件恰好只有 1 条更正记录
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trace_event WHERE corrects_event_id = ?",
                Integer.class, origEventId
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("真实 MySQL 并发不同幂等键更正竞争: 一个成功 201，另一个被排他锁拦截返回 409 EVENT_ALREADY_CORRECTED，杜绝分叉")
    void testConcurrentDifferentKeyCorrection_ConflictRejection() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_CONC_D_" + suffix, "并发竞争企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser opUser = createUser(org.getId(), "op_conc_d_" + suffix, rawPassword);
        bindUserRole(opUser.getId(), opRole.getId());
        Product product = createProduct("PRD_CONC_D_" + suffix, "并发竞争产品", "ACTIVE");
        Site site = createSite(org.getId(), "SITE_D_" + suffix, "并发竞争车间", "ACTIVE");

        HttpSession session = loginAndGetSession(opUser.getUsername(), rawPassword);

        // 创建并激活批次
        Batch batch = new Batch();
        batch.setOrgId(org.getId());
        batch.setProductId(product.getId());
        batch.setBatchNo("BATCH-CONC-D-" + suffix);
        batch.setBatchType("SOURCE");
        batch.setQuantity(new BigDecimal("60.000"));
        batch.setUnitCode("kg");
        batch.setOriginType("DOMESTIC_CAPTURE");
        batch.setOriginText("并发竞争原产地");
        batch.setStatus("ACTIVE");
        batch.setCreationIdempotencyKey("idem-batch-d-" + suffix);
        batch.setVersion(1L);
        batch.setIsDeleted(0);
        batch.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchMapper.insert(batch);
        createdBatchIds.add(batch.getId());

        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-09-09T14:00:00Z");
        CreateTraceEventRequest createReq = new CreateTraceEventRequest(
                "PROCESS", occurredAt, site.getId(), "MANUAL", "初始分拣", null
        );
        MvcResult origResult = mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/events")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-orig-d-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        Long origEventId = objectMapper.readTree(origResult.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdTraceEventIds.add(origEventId);

        String key1 = "idem-diff-corr-1-" + suffix;
        String key2 = "idem-diff-corr-2-" + suffix;

        CorrectTraceEventRequest corrReq1 = new CorrectTraceEventRequest(
                "PROCESS", occurredAt, site.getId(), "MANUAL", "更正A分支", null, "原因A"
        );
        CorrectTraceEventRequest corrReq2 = new CorrectTraceEventRequest(
                "PROCESS", occurredAt, site.getId(), "MANUAL", "更正B分支", null, "原因B"
        );

        CountDownLatch startLatch = new CountDownLatch(1);

        CompletableFuture<MvcResult> future1 = CompletableFuture.supplyAsync(() -> {
            try {
                startLatch.await();
                return mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/events/" + origEventId + "/corrections")
                                .session((MockHttpSession) session)
                                .with(csrf())
                                .header("Idempotency-Key", key1)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(corrReq1)))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<MvcResult> future2 = CompletableFuture.supplyAsync(() -> {
            try {
                startLatch.await();
                return mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/events/" + origEventId + "/corrections")
                                .session((MockHttpSession) session)
                                .with(csrf())
                                .header("Idempotency-Key", key2)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(corrReq2)))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        startLatch.countDown();
        CompletableFuture.allOf(future1, future2).join();

        int status1 = future1.get().getResponse().getStatus();
        int status2 = future2.get().getResponse().getStatus();

        // 必然是一个 201 成功，另一个 409 冲突
        assertThat(List.of(status1, status2)).containsExactlyInAnyOrder(201, 409);

        MvcResult conflictResult = status1 == 409 ? future1.get() : future2.get();
        JsonNode conflictJson = objectMapper.readTree(conflictResult.getResponse().getContentAsString());
        assertThat(conflictJson.path("code").asText()).isEqualTo("EVENT_ALREADY_CORRECTED");

        MvcResult successResult = status1 == 201 ? future1.get() : future2.get();
        Long successCorrId = objectMapper.readTree(successResult.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdTraceEventIds.add(successCorrId);

        // 数据库物理核查：更正记录恰好只有 1 条，杜绝多版本分叉
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trace_event WHERE corrects_event_id = ?",
                Integer.class, origEventId
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("真实 MySQL 事务可见性证据: 在同一 REPEATABLE READ 事务中，普通快照读看不到外部提交的更正，当前锁定读穿透快照读获取最新提交")
    void testRepeatableRead_SnapshotBlindSpot_And_ForUpdateCurrentRead() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_RR_E_" + suffix, "事务可见性企业");
        Product product = createProduct("PRD_RR_E_" + suffix, "事务可见性产品", "ACTIVE");

        Batch batch = new Batch();
        batch.setOrgId(org.getId());
        batch.setProductId(product.getId());
        batch.setBatchNo("BATCH-RR-E-" + suffix);
        batch.setBatchType("SOURCE");
        batch.setQuantity(new BigDecimal("10.000"));
        batch.setUnitCode("kg");
        batch.setOriginType("DOMESTIC_CAPTURE");
        batch.setOriginText("产地说明");
        batch.setStatus("ACTIVE");
        batch.setCreationIdempotencyKey("idem-batch-rr-" + suffix);
        batch.setVersion(0L);
        batch.setIsDeleted(0);
        batch.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchMapper.insert(batch);
        createdBatchIds.add(batch.getId());

        // 预先插入一条 SUBMITTED 事件
        String origKey = "idem-orig-rr-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, data_source, status, summary, idempotency_key)
                VALUES (?, ?, 'PROCESS', '2026-09-09 10:00:00', '2026-09-09 10:00:00', 'MANUAL', 'SUBMITTED', '原事件', ?)
                """, batch.getId(), org.getId(), origKey);

        Long origEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM trace_event WHERE org_id = ? AND idempotency_key = ?", Long.class, org.getId(), origKey);
        createdTraceEventIds.add(origEventId);

        String corrKey = "idem-corr-rr-" + suffix;

        // 打开两个物理连接：connA 模拟当前长事务 (REPEATABLE READ)，connB 模拟外部并发事务
        try (Connection connA = jdbcTemplate.getDataSource().getConnection();
             Connection connB = jdbcTemplate.getDataSource().getConnection()) {

            connA.setAutoCommit(false);
            connA.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            // 1. connA 执行初始普通快照读，建立该事务的 Read View
            try (PreparedStatement stmtA1 = connA.prepareStatement(
                    "SELECT status FROM trace_event WHERE id = ? AND is_deleted = 0")) {
                stmtA1.setLong(1, origEventId);
                try (ResultSet rsA1 = stmtA1.executeQuery()) {
                    assertThat(rsA1.next()).isTrue();
                    assertThat(rsA1.getString("status")).isEqualTo("SUBMITTED");
                }
            }

            // 2. connB 在外部插入更正记录，并将原事件更新为 CORRECTED 并立即提交
            connB.setAutoCommit(false);
            try (PreparedStatement stmtB1 = connB.prepareStatement("""
                    INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, data_source, status, summary, corrects_event_id, correction_reason, idempotency_key)
                    VALUES (?, ?, 'PROCESS', '2026-09-09 10:00:00', '2026-09-09 10:05:00', 'MANUAL', 'SUBMITTED', '更正记录', ?, '修正原因', ?)
                    """)) {
                stmtB1.setLong(1, batch.getId());
                stmtB1.setLong(2, org.getId());
                stmtB1.setLong(3, origEventId);
                stmtB1.setString(4, corrKey);
                stmtB1.executeUpdate();
            }
            try (PreparedStatement stmtB2 = connB.prepareStatement(
                    "UPDATE trace_event SET status = 'CORRECTED' WHERE id = ?")) {
                stmtB2.setLong(1, origEventId);
                stmtB2.executeUpdate();
            }
            connB.commit();

            Long corrEventId = jdbcTemplate.queryForObject(
                    "SELECT id FROM trace_event WHERE org_id = ? AND idempotency_key = ?", Long.class, org.getId(), corrKey);
            createdTraceEventIds.add(corrEventId);

            // 3. connA 在同一事务中再次执行普通快照读
            // 证据 1: REPEATABLE READ 快照读无法看到外部后提交的状态变更与新插入行 (快照读盲区)
            try (PreparedStatement stmtA2 = connA.prepareStatement(
                    "SELECT status FROM trace_event WHERE id = ? AND is_deleted = 0")) {
                stmtA2.setLong(1, origEventId);
                try (ResultSet rsA2 = stmtA2.executeQuery()) {
                    assertThat(rsA2.next()).isTrue();
                    assertThat(rsA2.getString("status")).isEqualTo("SUBMITTED"); // 依然是旧状态！
                }
            }
            try (PreparedStatement stmtA2Key = connA.prepareStatement(
                    "SELECT COUNT(*) FROM trace_event WHERE org_id = ? AND idempotency_key = ?")) {
                stmtA2Key.setLong(1, org.getId());
                stmtA2Key.setString(2, corrKey);
                try (ResultSet rsKey = stmtA2Key.executeQuery()) {
                    assertThat(rsKey.next()).isTrue();
                    assertThat(rsKey.getInt(1)).isEqualTo(0); // 普通读查不到外部已提交的更正行！
                }
            }

            // 4. connA 执行当前锁定读 (SELECT ... FOR UPDATE)
            // 证据 2: 当前读直接读取存储引擎最新已提交行，穿透快照读盲区，成功读取到并发提交的 CORRECTED 状态与更正记录！
            try (PreparedStatement stmtLockTarget = connA.prepareStatement(
                    "SELECT status FROM trace_event WHERE id = ? AND is_deleted = 0 FOR UPDATE")) {
                stmtLockTarget.setLong(1, origEventId);
                try (ResultSet rsLock = stmtLockTarget.executeQuery()) {
                    assertThat(rsLock.next()).isTrue();
                    assertThat(rsLock.getString("status")).isEqualTo("CORRECTED"); // 成功穿透快照读！
                }
            }
            try (PreparedStatement stmtLockKey = connA.prepareStatement(
                    "SELECT id, corrects_event_id FROM trace_event WHERE org_id = ? AND idempotency_key = ? AND is_deleted = 0 FOR UPDATE")) {
                stmtLockKey.setLong(1, org.getId());
                stmtLockKey.setString(2, corrKey);
                try (ResultSet rsLockKey = stmtLockKey.executeQuery()) {
                    assertThat(rsLockKey.next()).isTrue();
                    assertThat(rsLockKey.getLong("corrects_event_id")).isEqualTo(origEventId); // 成功当前读出更正记录！
                }
            }

            connA.commit();
        }
    }

    @Test
    @DisplayName("数据库底层物理约束保障 - Flyway V5 MySQL 8.4 物理 CHECK 约束与防分叉唯一索引拦截非法脏数据")
    void testFlywayV5_PhysicalCheckConstraints_EnforcedByDatabase() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_CHK_E_" + suffix, "事件约束企业");
        Product product = createProduct("PRD_CHK_E_" + suffix, "事件约束产品", "ACTIVE");

        Batch batch = new Batch();
        batch.setOrgId(org.getId());
        batch.setProductId(product.getId());
        batch.setBatchNo("BATCH-CHK-E-" + suffix);
        batch.setBatchType("SOURCE");
        batch.setQuantity(new BigDecimal("10.000"));
        batch.setUnitCode("kg");
        batch.setOriginType("DOMESTIC_CAPTURE");
        batch.setOriginText("约束产地");
        batch.setStatus("ACTIVE");
        batch.setCreationIdempotencyKey("idem-batch-chk-" + suffix);
        batch.setVersion(0L);
        batch.setIsDeleted(0);
        batch.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchMapper.insert(batch);
        createdBatchIds.add(batch.getId());

        Long orgId = org.getId();
        Long batchId = batch.getId();

        // 1. 非法 event_type 触发 chk_trace_event_type
        DataAccessException exType = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, data_source, status, summary, idempotency_key)
                        VALUES (?, ?, 'INVALID_TYPE', NOW(), NOW(), 'MANUAL', 'SUBMITTED', '摘要', 'idem-chk-1')
                        """, batchId, orgId)
        );
        assertThat(exType.getMessage()).containsIgnoringCase("chk_trace_event_type");

        // 2. 非法 data_source 触发 chk_trace_event_data_source
        DataAccessException exSource = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, data_source, status, summary, idempotency_key)
                        VALUES (?, ?, 'PROCESS', NOW(), NOW(), 'UNKNOWN_SOURCE', 'SUBMITTED', '摘要', 'idem-chk-2')
                        """, batchId, orgId)
        );
        assertThat(exSource.getMessage()).containsIgnoringCase("chk_trace_event_data_source");

        // 3. 非法 status 触发 chk_trace_event_status
        DataAccessException exStatus = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, data_source, status, summary, idempotency_key)
                        VALUES (?, ?, 'PROCESS', NOW(), NOW(), 'MANUAL', 'DRAFT', '摘要', 'idem-chk-3')
                        """, batchId, orgId)
        );
        assertThat(exStatus.getMessage()).containsIgnoringCase("chk_trace_event_status");

        // 4. 非法更正形状 1: corrects_event_id 为空但 correction_reason 不为空 触发 chk_trace_event_correction_shape
        DataAccessException exShape1 = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, data_source, status, summary, correction_reason, idempotency_key)
                        VALUES (?, ?, 'PROCESS', NOW(), NOW(), 'MANUAL', 'SUBMITTED', '摘要', '原因非空但更正ID为空', 'idem-chk-4')
                        """, batchId, orgId)
        );
        assertThat(exShape1.getMessage()).containsIgnoringCase("chk_trace_event_correction_shape");

        // 5. 非法更正形状 2: corrects_event_id 不为空但 correction_reason 为空 触发 chk_trace_event_correction_shape
        DataAccessException exShape2 = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, data_source, status, summary, corrects_event_id, idempotency_key)
                        VALUES (?, ?, 'PROCESS', NOW(), NOW(), 'MANUAL', 'SUBMITTED', '摘要', 99999, 'idem-chk-5')
                        """, batchId, orgId)
        );
        assertThat(exShape2.getMessage()).containsIgnoringCase("chk_trace_event_correction_shape");

        // 6. 插入合法基础事件
        jdbcTemplate.update("""
                INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, data_source, status, summary, idempotency_key)
                VALUES (?, ?, 'PROCESS', NOW(), NOW(), 'MANUAL', 'SUBMITTED', '基准事件', 'idem-chk-base')
                """, batchId, orgId);
        Long baseEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM trace_event WHERE org_id = ? AND idempotency_key = 'idem-chk-base'", Long.class, orgId);
        createdTraceEventIds.add(baseEventId);

        // 插入首次更正 (成功)
        jdbcTemplate.update("""
                INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, data_source, status, summary, corrects_event_id, correction_reason, idempotency_key)
                VALUES (?, ?, 'PROCESS', NOW(), NOW(), 'MANUAL', 'SUBMITTED', '首次更正', ?, '首次更正原因', 'idem-chk-corr-1')
                """, batchId, orgId, baseEventId);
        Long firstCorrId = jdbcTemplate.queryForObject(
                "SELECT id FROM trace_event WHERE org_id = ? AND idempotency_key = 'idem-chk-corr-1'", Long.class, orgId);
        createdTraceEventIds.add(firstCorrId);

        // 7. 再次更正相同 baseEventId 触发 uk_trace_event_corrects 唯一键冲突
        DataAccessException exFork = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("""
                        INSERT INTO trace_event (batch_id, org_id, event_type, occurred_at, recorded_at, data_source, status, summary, corrects_event_id, correction_reason, idempotency_key)
                        VALUES (?, ?, 'PROCESS', NOW(), NOW(), 'MANUAL', 'SUBMITTED', '分叉更正', ?, '分叉原因', 'idem-chk-corr-2')
                        """, batchId, orgId, baseEventId)
        );
        assertThat(exFork.getMessage()).containsIgnoringCase("uk_trace_event_corrects");
    }

    @Test
    @DisplayName("端到端验证 - 权限与组织隔离：非 OPERATOR 角色、跨组织 OPERATOR、平台管理角色写操作均被拦截 (403)")
    void testAccessControl_NonOperatorAndCrossOrg_Forbidden() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization orgA = createOrg("ORG_AUTH_A_" + suffix, "权限测试组织A");
        Organization orgB = createOrg("ORG_AUTH_B_" + suffix, "权限测试组织B");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        Role auditorRole = getOrCreateRole("AUDITOR", "企业审计员", "ORG_ONLY");
        Role adminRole = getOrCreateRole("ADMIN", "平台管理员", "PLATFORM");

        String rawPassword = "TestPassword123!";
        AppUser operatorA = createUser(orgA.getId(), "op_a_" + suffix, rawPassword);
        bindUserRole(operatorA.getId(), opRole.getId());

        AppUser auditorA = createUser(orgA.getId(), "auditor_a_" + suffix, rawPassword);
        bindUserRole(auditorA.getId(), auditorRole.getId());

        AppUser operatorB = createUser(orgB.getId(), "op_b_" + suffix, rawPassword);
        bindUserRole(operatorB.getId(), opRole.getId());

        AppUser platformAdminUser = createUser(orgA.getId(), "admin_" + suffix, rawPassword);
        bindUserRole(platformAdminUser.getId(), adminRole.getId());

        Product product = createProduct("PRD_AUTH_" + suffix, "权限测试产品", "ACTIVE");
        Site siteA = createSite(orgA.getId(), "SITE_AUTH_A_" + suffix, "测试基地A", "ACTIVE");

        // 创建组织 A 的 ACTIVE 批次
        Batch batchA = new Batch();
        batchA.setOrgId(orgA.getId());
        batchA.setProductId(product.getId());
        batchA.setBatchNo("BATCH-AUTH-A-" + suffix);
        batchA.setBatchType("SOURCE");
        batchA.setQuantity(new BigDecimal("20.000"));
        batchA.setUnitCode("kg");
        batchA.setOriginType("DOMESTIC_CAPTURE");
        batchA.setOriginText("舟山海域");
        batchA.setStatus("ACTIVE");
        batchA.setCreationIdempotencyKey("idem-batch-auth-" + suffix);
        batchA.setVersion(0L);
        batchA.setIsDeleted(0);
        batchA.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchA.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchMapper.insert(batchA);
        createdBatchIds.add(batchA.getId());

        HttpSession sessionOpA = loginAndGetSession(operatorA.getUsername(), rawPassword);
        HttpSession sessionAuditorA = loginAndGetSession(auditorA.getUsername(), rawPassword);
        HttpSession sessionOpB = loginAndGetSession(operatorB.getUsername(), rawPassword);
        HttpSession sessionAdmin = loginAndGetSession(platformAdminUser.getUsername(), rawPassword);

        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-09-09T11:00:00Z");

        // 1. 非 OPERATOR 用户 (组织 A 审计员) 向本组织批次创建事件 -> 403 ACCESS_DENIED
        CreateTraceEventRequest createReq = new CreateTraceEventRequest(
                "SOURCE", occurredAt, siteA.getId(), "MANUAL", "审计员尝试创建", null
        );
        mockMvc.perform(post("/api/v1/batches/" + batchA.getId() + "/events")
                        .session((MockHttpSession) sessionAuditorA)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-auth-fail-1-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 2. 平台管理角色用户向企业批次创建事件 -> 403 ACCESS_DENIED
        mockMvc.perform(post("/api/v1/batches/" + batchA.getId() + "/events")
                        .session((MockHttpSession) sessionAdmin)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-auth-fail-2-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 3. 组织 B 的 OPERATOR 向组织 A 批次创建事件 -> 403 ORG_SCOPE_DENIED
        mockMvc.perform(post("/api/v1/batches/" + batchA.getId() + "/events")
                        .session((MockHttpSession) sessionOpB)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-auth-fail-3-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));

        // 4. 组织 A 的 OPERATOR 正常创建一条基准事件
        MvcResult baseRes = mockMvc.perform(post("/api/v1/batches/" + batchA.getId() + "/events")
                        .session((MockHttpSession) sessionOpA)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-auth-base-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        Long baseEventId = objectMapper.readTree(baseRes.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdTraceEventIds.add(baseEventId);

        // 5. 组织 B 的 OPERATOR 对组织 A 批次的事件发起更正 -> 403 ORG_SCOPE_DENIED
        CorrectTraceEventRequest correctReq = new CorrectTraceEventRequest(
                "SOURCE", occurredAt, siteA.getId(), "MANUAL", "跨组织更正", null, "非法跨组织"
        );
        mockMvc.perform(post("/api/v1/batches/" + batchA.getId() + "/events/" + baseEventId + "/corrections")
                        .session((MockHttpSession) sessionOpB)
                        .with(csrf())
                        .header("Idempotency-Key", "idem-auth-fail-4-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));

        // 6. Append-only 不可变性反例：尝试使用 PUT /api/v1/batches/{batchId}/events/{eventId} 就地覆盖修改 -> 405 Method Not Allowed
        mockMvc.perform(put("/api/v1/batches/" + batchA.getId() + "/events/" + baseEventId)
                        .session((MockHttpSession) sessionOpA)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"试图覆盖原事件\"}"))
                .andExpect(status().isMethodNotAllowed());

        // 7. Append-only 不可变性反例：尝试使用 DELETE /api/v1/batches/{batchId}/events/{eventId} 物理删除 -> 405 Method Not Allowed
        mockMvc.perform(delete("/api/v1/batches/" + batchA.getId() + "/events/" + baseEventId)
                        .session((MockHttpSession) sessionOpA)
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed());

        // 8. 数据库物理状态复核：确认事件记录仍然存在，且核心字段及状态保持不变
        String currentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM trace_event WHERE id = ?", String.class, baseEventId);
        String currentSummary = jdbcTemplate.queryForObject(
                "SELECT summary FROM trace_event WHERE id = ?", String.class, baseEventId);
        Long currentVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM trace_event WHERE id = ?", Long.class, baseEventId);
        Integer isDeleted = jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM trace_event WHERE id = ?", Integer.class, baseEventId);

        assertThat(currentStatus).isEqualTo("SUBMITTED");
        assertThat(currentSummary).isEqualTo("审计员尝试创建");
        assertThat(currentVersion).isEqualTo(0L);
        assertThat(isDeleted).isEqualTo(0);
    }

    @Test
    @DisplayName("真实 MySQL 事务原子性实证 - 更正流程新版本已插入但旧版本状态更新失败时，触发全事务回滚 (新版本不残留，旧版本保持 SUBMITTED)")
    void testCorrectionWorkflow_AtomicRollback_WhenOldVersionUpdateFails() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_RB_" + suffix, "回滚实证企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_rb_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_RB_" + suffix, "回滚测试产品", "ACTIVE");
        Site site = createSite(org.getId(), "SITE_RB_" + suffix, "回滚测试码头", "ACTIVE");

        Batch batch = new Batch();
        batch.setOrgId(org.getId());
        batch.setProductId(product.getId());
        batch.setBatchNo("BATCH-RB-" + suffix);
        batch.setBatchType("SOURCE");
        batch.setQuantity(new BigDecimal("15.000"));
        batch.setUnitCode("kg");
        batch.setOriginType("DOMESTIC_CAPTURE");
        batch.setOriginText("东海海域");
        batch.setStatus("ACTIVE");
        batch.setCreationIdempotencyKey("idem-batch-rb-" + suffix);
        batch.setVersion(0L);
        batch.setIsDeleted(0);
        batch.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchMapper.insert(batch);
        createdBatchIds.add(batch.getId());

        HttpSession session = loginAndGetSession(operator.getUsername(), rawPassword);
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-09-09T10:00:00Z");

        // 1. 正常创建基准事件
        CreateTraceEventRequest createReq = new CreateTraceEventRequest(
                "SOURCE", occurredAt, site.getId(), "MANUAL", "原始基准事件", null
        );
        String baseKey = "idem-rb-base-" + suffix;
        MvcResult baseRes = mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/events")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", baseKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        Long baseEventId = objectMapper.readTree(baseRes.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdTraceEventIds.add(baseEventId);

        // 验证基准事件初始状态
        String initialStatus = jdbcTemplate.queryForObject("SELECT status FROM trace_event WHERE id = ?", String.class, baseEventId);
        Long initialVersion = jdbcTemplate.queryForObject("SELECT version FROM trace_event WHERE id = ?", Long.class, baseEventId);
        assertThat(initialStatus).isEqualTo("SUBMITTED");
        assertThat(initialVersion).isEqualTo(0L);

        // 2. 动态创建 MySQL BEFORE UPDATE 触发器，在更新目标事件为 CORRECTED 时抛出异常，强制使旧版本状态更新失败
        String triggerName = "trg_test_rollback_" + suffix;
        jdbcTemplate.execute(String.format("""
                CREATE TRIGGER %s
                BEFORE UPDATE ON trace_event
                FOR EACH ROW
                BEGIN
                    IF OLD.id = %d AND NEW.status = 'CORRECTED' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Simulated database update failure for rollback test';
                    END IF;
                END;
                """, triggerName, baseEventId));

        String failedCorrectionKey = "idem-rb-corr-fail-" + suffix;
        CorrectTraceEventRequest corrReq = new CorrectTraceEventRequest(
                "SOURCE", occurredAt, site.getId(), "MANUAL", "尝试更正摘要", null, "测试触发器回滚"
        );

        try {
            // 3. 发起更正请求：应用层执行流为 [insert newEvent -> update oldEvent status]，update 时被触发器拒绝
            mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/events/" + baseEventId + "/corrections")
                            .session((MockHttpSession) session)
                            .with(csrf())
                            .header("Idempotency-Key", failedCorrectionKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(corrReq)))
                    .andExpect(status().is5xxServerError());

            // 4. 真实数据库物理状态核查：验证原子性回滚
            // 4.1 新更正事件必须不存在 (已被事务完全回滚)
            Integer newEventCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM trace_event WHERE corrects_event_id = ?", Integer.class, baseEventId);
            assertThat(newEventCount).isEqualTo(0);

            Integer keyCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM trace_event WHERE idempotency_key = ?", Integer.class, failedCorrectionKey);
            assertThat(keyCount).isEqualTo(0);

            // 4.2 旧原事件状态仍为 SUBMITTED，version 仍为 0
            String statusAfterRollback = jdbcTemplate.queryForObject(
                    "SELECT status FROM trace_event WHERE id = ?", String.class, baseEventId);
            Long versionAfterRollback = jdbcTemplate.queryForObject(
                    "SELECT version FROM trace_event WHERE id = ?", Long.class, baseEventId);
            assertThat(statusAfterRollback).isEqualTo("SUBMITTED");
            assertThat(versionAfterRollback).isEqualTo(0L);

        } finally {
            // 5. 必须物理清理临时触发器，避免污染后续测试
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName + ";");
        }
    }

    @Test
    @DisplayName("端到端验证 - 事件列表稳定排序：完全相同 occurred_at 与 recorded_at 时，严格按 id ASC 排序")
    void testEventListOrdering_IdenticalTimestamps_StrictlyOrderedByIdAsc() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_ORD_" + suffix, "排序测试企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_ord_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_ORD_" + suffix, "排序测试产品", "ACTIVE");
        Site site = createSite(org.getId(), "SITE_ORD_" + suffix, "排序测试冷库", "ACTIVE");

        Batch batch = new Batch();
        batch.setOrgId(org.getId());
        batch.setProductId(product.getId());
        batch.setBatchNo("BATCH-ORD-" + suffix);
        batch.setBatchType("SOURCE");
        batch.setQuantity(new BigDecimal("30.000"));
        batch.setUnitCode("kg");
        batch.setOriginType("DOMESTIC_CAPTURE");
        batch.setOriginText("排序产地");
        batch.setStatus("ACTIVE");
        batch.setCreationIdempotencyKey("idem-batch-ord-" + suffix);
        batch.setVersion(0L);
        batch.setIsDeleted(0);
        batch.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchMapper.insert(batch);
        createdBatchIds.add(batch.getId());

        HttpSession session = loginAndGetSession(operator.getUsername(), rawPassword);
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-09-09T08:00:00Z");

        // 连续创建 3 条事件
        List<Long> eventIds = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            CreateTraceEventRequest req = new CreateTraceEventRequest(
                    "PROCESS", occurredAt, site.getId(), "MANUAL", "排序测试事件 " + i, null
            );
            MvcResult res = mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/events")
                            .session((MockHttpSession) session)
                            .with(csrf())
                            .header("Idempotency-Key", "idem-ord-" + i + "-" + suffix)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andReturn();
            Long id = objectMapper.readTree(res.getResponse().getContentAsString()).path("data").path("id").asLong();
            eventIds.add(id);
            createdTraceEventIds.add(id);
        }

        // 核心步骤：直接通过数据库将 3 条事件的 occurred_at 和 recorded_at 强制更新为完全一致的时间点
        String fixedOccurred = "2026-09-09 08:30:00.123";
        String fixedRecorded = "2026-09-09 08:35:00.456";
        jdbcTemplate.update("UPDATE trace_event SET occurred_at = ?, recorded_at = ? WHERE id IN (?, ?, ?)",
                fixedOccurred, fixedRecorded, eventIds.get(0), eventIds.get(1), eventIds.get(2));

        // 通过真实 GET /api/v1/batches/{batchId}/events 查询
        MvcResult listRes = mockMvc.perform(get("/api/v1/batches/" + batch.getId() + "/events")
                        .session((MockHttpSession) session))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode dataArray = objectMapper.readTree(listRes.getResponse().getContentAsString()).path("data");
        assertThat(dataArray.size()).isEqualTo(3);

        long returnedId0 = dataArray.get(0).path("id").asLong();
        long returnedId1 = dataArray.get(1).path("id").asLong();
        long returnedId2 = dataArray.get(2).path("id").asLong();

        // 明确断言严格按 id ASC 递增排序
        assertThat(returnedId0).isEqualTo(eventIds.get(0));
        assertThat(returnedId1).isEqualTo(eventIds.get(1));
        assertThat(returnedId2).isEqualTo(eventIds.get(2));
        assertThat(returnedId0).isLessThan(returnedId1);
        assertThat(returnedId1).isLessThan(returnedId2);
    }

    @Test
    @DisplayName("端到端验证 - SIMULATED 真实存储回显与双时间关系：recordedAt 晚于历史 occurredAt")
    void testDataSourceSimulated_And_RecordedAtAfterOccurredAt() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrg("ORG_SIM_" + suffix, "模拟数据测试企业");
        Role opRole = getOrCreateRole("OPERATOR", "企业操作员", "ORG_ONLY");
        String rawPassword = "TestPassword123!";
        AppUser operator = createUser(org.getId(), "op_sim_" + suffix, rawPassword);
        bindUserRole(operator.getId(), opRole.getId());

        Product product = createProduct("PRD_SIM_" + suffix, "模拟数据产品", "ACTIVE");
        Site site = createSite(org.getId(), "SITE_SIM_" + suffix, "数字孪生试验场", "ACTIVE");

        Batch batch = new Batch();
        batch.setOrgId(org.getId());
        batch.setProductId(product.getId());
        batch.setBatchNo("BATCH-SIM-" + suffix);
        batch.setBatchType("SOURCE");
        batch.setQuantity(new BigDecimal("50.000"));
        batch.setUnitCode("kg");
        batch.setOriginType("DOMESTIC_CAPTURE");
        batch.setOriginText("模拟海域");
        batch.setStatus("ACTIVE");
        batch.setCreationIdempotencyKey("idem-batch-sim-" + suffix);
        batch.setVersion(0L);
        batch.setIsDeleted(0);
        batch.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchMapper.insert(batch);
        createdBatchIds.add(batch.getId());

        HttpSession session = loginAndGetSession(operator.getUsername(), rawPassword);

        // 设定一个明确的历史业务发生时间 (3 天前)
        OffsetDateTime historicalOccurredAt = OffsetDateTime.parse("2026-09-06T09:15:30.123+08:00");
        CreateTraceEventRequest simReq = new CreateTraceEventRequest(
                "FREEZE", historicalOccurredAt, site.getId(), "SIMULATED", "冷库速冻数字孪生模拟上报",
                Map.of("targetTemp", -25.5, "simulationModel", "v3.2")
        );

        String simKey = "idem-sim-" + suffix;
        MvcResult res = mockMvc.perform(post("/api/v1/batches/" + batch.getId() + "/events")
                        .session((MockHttpSession) session)
                        .with(csrf())
                        .header("Idempotency-Key", simKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(simReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dataSource").value("SIMULATED"))
                .andExpect(jsonPath("$.data.summary").value("冷库速冻数字孪生模拟上报"))
                .andReturn();

        JsonNode data = objectMapper.readTree(res.getResponse().getContentAsString()).path("data");
        Long eventId = data.path("id").asLong();
        createdTraceEventIds.add(eventId);

        // 1. 解析响应时间并明确断言 recordedAt 与历史 occurredAt 的先后关系
        OffsetDateTime respOccurredAt = OffsetDateTime.parse(data.path("occurredAt").asText());
        OffsetDateTime respRecordedAt = OffsetDateTime.parse(data.path("recordedAt").asText());

        // 断言 recordedAt 严格晚于历史 occurredAt
        assertThat(respRecordedAt.toInstant()).isAfter(respOccurredAt.toInstant());
        // 断言 occurredAt UTC 时间与输入一致
        assertThat(respOccurredAt.toInstant()).isEqualTo(historicalOccurredAt.toInstant());

        // 2. MySQL 真实底层存储核查
        String dbSource = jdbcTemplate.queryForObject("SELECT data_source FROM trace_event WHERE id = ?", String.class, eventId);
        assertThat(dbSource).isEqualTo("SIMULATED");

        // 3. GET 接口列表查询原样返回 SIMULATED
        mockMvc.perform(get("/api/v1/batches/" + batch.getId() + "/events")
                        .session((MockHttpSession) session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].dataSource").value("SIMULATED"))
                .andExpect(jsonPath("$.data[0].summary").value("冷库速冻数字孪生模拟上报"));
    }

    private HttpSession loginAndGetSession(String username, String rawPassword) throws Exception {
        LoginRequest loginRequest = new LoginRequest(username, rawPassword);
        MvcResult mvcResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();
        return mvcResult.getRequest().getSession(false);
    }

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
        UserRole ur = new UserRole();
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        ur.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userRoleMapper.insert(ur);
    }

    private Product createProduct(String productCode, String publicName, String status) {
        Product p = new Product();
        p.setProductCode(productCode);
        p.setPublicName(publicName);
        p.setCategory("FISH");
        p.setSpecification("500g/包");
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

    private Site createSite(Long orgId, String siteNo, String name, String status) {
        Site s = new Site();
        s.setOrgId(orgId);
        s.setSiteNo(siteNo);
        s.setName(name);
        s.setSiteType("PORT");
        s.setAddressText("浙江省舟山市普陀区测试码头");
        s.setTimezone("Asia/Shanghai");
        s.setStatus(status);
        s.setVersion(0L);
        s.setIsDeleted(0);
        s.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        s.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        siteMapper.insert(s);
        createdSiteIds.add(s.getId());
        return s;
    }
}
