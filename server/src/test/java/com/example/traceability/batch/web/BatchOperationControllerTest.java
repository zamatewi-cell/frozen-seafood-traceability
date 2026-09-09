package com.example.traceability.batch.web;

import com.example.traceability.batch.application.BatchOperationApplicationService;
import com.example.traceability.batch.dto.BatchOperationCreateRequest;
import com.example.traceability.batch.dto.BatchOperationItemRequest;
import com.example.traceability.batch.dto.BatchOperationItemResponse;
import com.example.traceability.batch.dto.BatchOperationResponse;
import com.example.traceability.batch.dto.BatchOperationSubmitRequest;
import com.example.traceability.batch.dto.BatchRelationResponse;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.GlobalExceptionHandler;
import com.example.traceability.common.exception.ResourceNotFoundException;
import com.example.traceability.common.filter.RequestIdFilter;
import com.example.traceability.identity.config.SecurityConfiguration;
import com.example.traceability.identity.domain.AppUser;
import com.example.traceability.identity.domain.Organization;
import com.example.traceability.identity.domain.Role;
import com.example.traceability.identity.mapper.AppUserMapper;
import com.example.traceability.identity.mapper.OrganizationMapper;
import com.example.traceability.identity.mapper.RoleMapper;
import com.example.traceability.identity.mapper.UserRoleMapper;
import com.example.traceability.identity.security.TraceSecurityPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BatchOperationController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("批次操作与物料平衡 Web 接口与权限契约测试")
class BatchOperationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BatchOperationApplicationService operationService;

    @MockitoBean
    private AppUserMapper appUserMapper;

    @MockitoBean
    private OrganizationMapper organizationMapper;

    @MockitoBean
    private RoleMapper roleMapper;

    @MockitoBean
    private UserRoleMapper userRoleMapper;

    @MockitoBean
    private com.example.traceability.masterdata.mapper.ProductMapper productMapper;

    @MockitoBean
    private com.example.traceability.masterdata.mapper.TemperatureRuleMapper temperatureRuleMapper;

    @MockitoBean
    private com.example.traceability.masterdata.mapper.TemperatureRuleStageMapper temperatureRuleStageMapper;

    @MockitoBean
    private com.example.traceability.batch.mapper.BatchMapper batchMapper;

    @MockitoBean
    private com.example.traceability.batch.mapper.BatchOperationMapper batchOperationMapper;

    @MockitoBean
    private com.example.traceability.batch.mapper.BatchOperationItemMapper batchOperationItemMapper;

    @MockitoBean
    private com.example.traceability.batch.mapper.BatchRelationMapper batchRelationMapper;

    private TraceSecurityPrincipal operatorPrincipal;
    private TraceSecurityPrincipal adminPrincipal;

    private static final String VALID_IDEMPOTENCY_KEY = "idem-key-1234567890-abcdef";

    @BeforeEach
    void setUp() {
        operatorPrincipal = new TraceSecurityPrincipal(
                101L, "operator1", "企业操作员", "{noop}pwd",
                10L, "ORG_01", "第一加工厂", "PROCESSOR",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );

        adminPrincipal = new TraceSecurityPrincipal(
                1L, "admin", "平台管理员", "{noop}pwd",
                1L, "ORG_PLATFORM", "溯源中心", "PLATFORM",
                List.of("ADMIN"), List.of("PLATFORM"), true, true
        );

        AppUser activeUser = new AppUser();
        activeUser.setId(101L);
        activeUser.setOrgId(10L);
        activeUser.setStatus("ACTIVE");
        activeUser.setIsDeleted(0);
        when(appUserMapper.selectById(101L)).thenReturn(activeUser);

        AppUser adminUser = new AppUser();
        adminUser.setId(1L);
        adminUser.setOrgId(1L);
        adminUser.setStatus("ACTIVE");
        adminUser.setIsDeleted(0);
        when(appUserMapper.selectById(1L)).thenReturn(adminUser);

        Organization activeOrg = new Organization();
        activeOrg.setId(10L);
        activeOrg.setStatus("ACTIVE");
        activeOrg.setIsDeleted(0);
        when(organizationMapper.selectById(10L)).thenReturn(activeOrg);

        Organization platformOrg = new Organization();
        platformOrg.setId(1L);
        platformOrg.setStatus("ACTIVE");
        platformOrg.setIsDeleted(0);
        when(organizationMapper.selectById(1L)).thenReturn(platformOrg);

        Role operatorRole = new Role();
        operatorRole.setRoleCode("OPERATOR");
        operatorRole.setScopeType("ORG_ONLY");
        operatorRole.setStatus("ACTIVE");
        operatorRole.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(101L)).thenReturn(List.of(operatorRole));

        Role adminRole = new Role();
        adminRole.setRoleCode("ADMIN");
        adminRole.setScopeType("PLATFORM");
        adminRole.setStatus("ACTIVE");
        adminRole.setIsDeleted(0);
        when(roleMapper.findActiveRolesByUserId(1L)).thenReturn(List.of(adminRole));
    }

    // =========================================================================
    // 1. 匿名访问与 CSRF 安全防护测试
    // =========================================================================

    @Test
    @DisplayName("匿名用户创建批次操作草稿返回 401 AUTH_REQUIRED")
    void createOperation_anonymous_unauthorized() throws Exception {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "MERGE",
                OffsetDateTime.now(ZoneOffset.UTC),
                "备注",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("100.000"), "kg")
                )
        );

        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("登录用户创建批次操作草稿但缺失 CSRF 返回 403")
    void createOperation_missingCsrf_forbidden() throws Exception {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "MERGE",
                OffsetDateTime.now(ZoneOffset.UTC),
                "备注",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("100.000"), "kg")
                )
        );

        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(user(operatorPrincipal))
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("非 OPERATOR 角色创建批次操作草稿返回 403 ACCESS_DENIED")
    void createOperation_nonOperator_forbidden() throws Exception {
        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "MERGE",
                OffsetDateTime.now(ZoneOffset.UTC),
                "备注",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 102L, new BigDecimal("100.000"), "kg")
                )
        );

        when(operationService.createDraftOperation(any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足", "该操作仅限企业操作员执行"));

        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(user(adminPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403));
    }

    // =========================================================================
    // 2. 参数校验与边界测试
    // =========================================================================

    @Test
    @DisplayName("请求体缺失 operationType 返回 400 INVALID_REQUEST")
    void createOperation_missingOperationType_badRequest() throws Exception {
        String invalidJson = """
                {
                  "occurredAt": "2026-09-09T10:00:00Z",
                  "items": [
                    { "role": "INPUT", "batchId": 1, "quantity": 100, "unitCode": "kg" },
                    { "role": "OUTPUT", "batchId": 2, "quantity": 100, "unitCode": "kg" }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("请求体 items 中包含 null 元素返回 400 INVALID_REQUEST")
    void createOperation_nullItemInItems_badRequest() throws Exception {
        String jsonWithNullItem = """
                {
                  "operationType": "MERGE",
                  "occurredAt": "2026-09-09T10:00:00Z",
                  "items": [
                    { "role": "INPUT", "batchId": 1, "quantity": 100, "unitCode": "kg" },
                    null
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonWithNullItem))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("提交操作缺失 version 参数返回 400 INVALID_REQUEST")
    void submitOperation_missingVersion_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/batch-operations/100/submit")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    // =========================================================================
    // 3. 业务异常映射测试
    // =========================================================================

    @Test
    @DisplayName("物料平衡违规映射为 422 BATCH_MASS_BALANCE_VIOLATION")
    void submitOperation_massBalanceViolation_unprocessableEntity() throws Exception {
        when(operationService.submitOperation(eq(100L), any(), any(), any()))
                .thenThrow(new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "BATCH_MASS_BALANCE_VIOLATION",
                        "物料平衡校验失败",
                        "投入与产出损耗差值超差"
                ));

        mockMvc.perform(post("/api/v1/batch-operations/100/submit")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_MASS_BALANCE_VIOLATION"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @DisplayName("谱系环检测异常映射为 422 BATCH_RELATION_CYCLE")
    void submitOperation_relationCycle_unprocessableEntity() throws Exception {
        when(operationService.submitOperation(eq(100L), any(), any(), any()))
                .thenThrow(new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "BATCH_RELATION_CYCLE",
                        "批次谱系成环",
                        "检测到循环关系"
                ));

        mockMvc.perform(post("/api/v1/batch-operations/100/submit")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_RELATION_CYCLE"))
                .andExpect(jsonPath("$.status").value(422));
    }

    // =========================================================================
    // 4. 成功响应契约与白名单投影测试
    // =========================================================================

    @Test
    @DisplayName("创建草稿成功返回 201 Created 与白名单响应契约")
    void createOperation_success_responseContract() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-09T10:00:00.123Z");
        BatchOperationResponse response = new BatchOperationResponse(
                100L,
                10L,
                "OP202609091000000001",
                "MERGE",
                now,
                now,
                "DRAFT",
                "合并加工",
                true,
                0L,
                now,
                101L,
                now,
                101L,
                List.of(
                        new BatchOperationItemResponse(1L, 100L, 101L, "INPUT", new BigDecimal("600.000"), "kg", new BigDecimal("600.000")),
                        new BatchOperationItemResponse(2L, 100L, 103L, "OUTPUT", new BigDecimal("480.000"), "kg", new BigDecimal("480.000"))
                ),
                List.of()
        );

        when(operationService.createDraftOperation(any(), any(), any())).thenReturn(response);

        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "MERGE",
                now,
                "合并加工",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("600.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 103L, new BigDecimal("480.000"), "kg")
                )
        );

        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.orgId").value(10))
                .andExpect(jsonPath("$.data.operationNo").value("OP202609091000000001"))
                .andExpect(jsonPath("$.data.operationType").value("MERGE"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.balanced").value(true))
                .andExpect(jsonPath("$.data.occurredAt").value("2026-09-09T10:00:00.123Z"))
                .andExpect(jsonPath("$.data.recordedAt").value("2026-09-09T10:00:00.123Z"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-09-09T10:00:00.123Z"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-09-09T10:00:00.123Z"))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.relations", hasSize(0)))
                // 确保白名单投影：内部安全字段绝不泄露
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.data.submissionIdempotencyKey").doesNotExist());
    }

    @Test
    @DisplayName("创建不平衡草稿成功响应契约返回 balanced=false")
    void createOperation_unbalanced_responseContract() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-09T10:00:00Z");
        BatchOperationResponse response = new BatchOperationResponse(
                100L,
                10L,
                "OP202609091000000001",
                "PROCESS",
                now,
                now,
                "DRAFT",
                "不平衡加工草稿",
                false,
                0L,
                now,
                101L,
                now,
                101L,
                List.of(
                        new BatchOperationItemResponse(1L, 100L, 101L, "INPUT", new BigDecimal("100.000"), "kg", new BigDecimal("100.000")),
                        new BatchOperationItemResponse(2L, 100L, 103L, "OUTPUT", new BigDecimal("90.000"), "kg", new BigDecimal("90.000"))
                ),
                List.of()
        );

        when(operationService.createDraftOperation(any(), any(), any())).thenReturn(response);

        BatchOperationCreateRequest req = new BatchOperationCreateRequest(
                "PROCESS",
                now,
                "不平衡加工草稿",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("100.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", 103L, new BigDecimal("90.000"), "kg")
                )
        );

        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.balanced").value(false));
    }

    @Test
    @DisplayName("提交操作成功返回 200 OK 并携带生成谱系边数组")
    void submitOperation_success_responseContract() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-09T10:30:00Z");
        BatchOperationResponse response = new BatchOperationResponse(
                100L,
                10L,
                "OP202609091000000001",
                "MERGE",
                now,
                now,
                "SUBMITTED",
                "已提交",
                true,
                1L,
                now,
                101L,
                now,
                101L,
                List.of(
                        new BatchOperationItemResponse(1L, 100L, 101L, "INPUT", new BigDecimal("600.000"), "kg", new BigDecimal("600.000")),
                        new BatchOperationItemResponse(2L, 100L, 103L, "OUTPUT", new BigDecimal("600.000"), "kg", new BigDecimal("600.000"))
                ),
                List.of(
                        new BatchRelationResponse(1L, 100L, 101L, 103L, "MERGE", now)
                )
        );

        when(operationService.submitOperation(eq(100L), any(), any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/batch-operations/100/submit")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.relations", hasSize(1)))
                .andExpect(jsonPath("$.data.relations[0].parentBatchId").value(101))
                .andExpect(jsonPath("$.data.relations[0].childBatchId").value(103))
                .andExpect(jsonPath("$.data.relations[0].relationType").value("MERGE"));
    }
}
