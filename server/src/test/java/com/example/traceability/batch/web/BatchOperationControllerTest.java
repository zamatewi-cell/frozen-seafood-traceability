package com.example.traceability.batch.web;

import com.example.traceability.batch.application.BatchOperationApplicationService;
import com.example.traceability.batch.dto.BatchOperationCreateRequest;
import com.example.traceability.batch.dto.BatchOperationItemRequest;
import com.example.traceability.batch.dto.BatchOperationItemResponse;
import com.example.traceability.batch.dto.BatchOperationResponse;
import com.example.traceability.batch.dto.BatchRelationResponse;
import com.example.traceability.common.envelope.PageMeta;
import com.example.traceability.common.envelope.SuccessEnvelope;
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

import static org.hamcrest.Matchers.hasSize;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @MockitoBean
    private com.example.traceability.identity.mapper.SiteMapper siteMapper;

    @MockitoBean
    private com.example.traceability.trace.mapper.TraceEventMapper traceEventMapper;

    @MockitoBean
    private com.example.traceability.trace.mapper.PublicTraceCodeMapper publicTraceCodeMapper;

    @MockitoBean
    private com.example.traceability.trace.mapper.PublicTraceCodeIdempotencyMapper publicTraceCodeIdempotencyMapper;

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
    // 工具
    // =========================================================================

    private static BatchOperationCreateRequest processRequest(OffsetDateTime occurredAt) {
        return new BatchOperationCreateRequest(
                "PROCESS",
                occurredAt,
                "加工",
                List.of(
                        new BatchOperationItemRequest("INPUT", 101L, new BigDecimal("1000.000"), "kg"),
                        new BatchOperationItemRequest("OUTPUT", null, new BigDecimal("960.000"), "kg"),
                        new BatchOperationItemRequest("LOSS", null, new BigDecimal("30.000"), "kg"),
                        new BatchOperationItemRequest("SAMPLE", null, new BigDecimal("10.000"), "kg")
                )
        );
    }

    private static BatchOperationResponse operationResponse(String status, long version, OffsetDateTime now, List<BatchRelationResponse> relations) {
        boolean submitted = "SUBMITTED".equals(status);
        return new BatchOperationResponse(
                100L,
                10L,
                "OP202609091000000001",
                "PROCESS",
                now,
                now,
                status,
                "加工",
                true,
                new BigDecimal("1000.000"),
                new BigDecimal("960.000"),
                new BigDecimal("30.000"),
                BigDecimal.ZERO,
                new BigDecimal("10.000"),
                version,
                now,
                101L,
                now,
                101L,
                List.of(
                        new BatchOperationItemResponse(1L, 100L, 101L, "INPUT", new BigDecimal("1000.000"), "kg", new BigDecimal("1000.000"),
                                "TB-B0", null, 7L, "SOURCE", submitted ? "CLOSED" : "ACTIVE", "NORMAL"),
                        new BatchOperationItemResponse(2L, 100L, 103L, "OUTPUT", new BigDecimal("960.000"), "kg", new BigDecimal("960.000"),
                                "TB-B1", null, 7L, "PROCESSING", submitted ? "ACTIVE" : "DRAFT", "NORMAL"),
                        new BatchOperationItemResponse(3L, 100L, null, "LOSS", new BigDecimal("30.000"), "kg", new BigDecimal("30.000"),
                                null, null, null, null, null, null),
                        new BatchOperationItemResponse(4L, 100L, null, "SAMPLE", new BigDecimal("10.000"), "kg", new BigDecimal("10.000"),
                                null, null, null, null, null, null)
                ),
                relations
        );
    }

    private String processJson() throws Exception {
        return objectMapper.writeValueAsString(processRequest(OffsetDateTime.now(ZoneOffset.UTC)));
    }

    // =========================================================================
    // 1. 匿名访问与 CSRF 安全防护测试
    // =========================================================================

    @Test
    @DisplayName("匿名用户创建批次操作草稿返回 401 AUTH_REQUIRED")
    void createOperation_anonymous_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("匿名用户读取批次操作返回 401")
    void getOperation_anonymous_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/batch-operations/100"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("登录用户创建批次操作草稿但缺失 CSRF 返回 403")
    void createOperation_missingCsrf_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(user(operatorPrincipal))
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("删除草稿缺失 CSRF 返回 403")
    void deleteOperation_missingCsrf_forbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/batch-operations/100")
                        .with(user(operatorPrincipal))
                        .param("expectedVersion", "0"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("平台管理员创建批次操作草稿返回 403 ADMIN_RESTRICTED")
    void createOperation_admin_forbidden() throws Exception {
        when(operationService.createDraftOperation(any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_RESTRICTED", "管理员权限受限", "平台管理角色不可代办"));

        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(user(adminPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_RESTRICTED"))
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
                    { "role": "OUTPUT", "quantity": 100, "unitCode": "kg" }
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
                  "operationType": "PROCESS",
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
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("OUTPUT 保质期天数非正数返回 400 INVALID_REQUEST")
    void createOperation_nonPositiveShelfLife_badRequest() throws Exception {
        String json = """
                {
                  "operationType": "PROCESS",
                  "occurredAt": "2026-09-09T10:00:00Z",
                  "items": [
                    { "role": "INPUT", "batchId": 1, "quantity": 100, "unitCode": "kg" },
                    { "role": "OUTPUT", "quantity": 100, "unitCode": "kg", "shelfLifeDays": 0 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
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
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("按批次查询缺失 batchId 返回 400 INVALID_REQUEST")
    void listOperations_missingBatchId_badRequest() throws Exception {
        mockMvc.perform(get("/api/v1/batch-operations").with(user(operatorPrincipal)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("删除草稿缺失 expectedVersion 返回 400 INVALID_REQUEST")
    void deleteOperation_missingExpectedVersion_badRequest() throws Exception {
        mockMvc.perform(delete("/api/v1/batch-operations/100")
                        .with(user(operatorPrincipal))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // =========================================================================
    // 3. 业务异常映射测试
    // =========================================================================

    @Test
    @DisplayName("部分投入映射为 422 PARTIAL_INPUT_NOT_ALLOWED")
    void createOperation_partialInput_unprocessableEntity() throws Exception {
        when(operationService.createDraftOperation(any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "PARTIAL_INPUT_NOT_ALLOWED", "禁止部分投入", "请先 SPLIT"));

        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processJson()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PARTIAL_INPUT_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("MERGE 映射为 422 OPERATION_TYPE_NOT_SUPPORTED")
    void createOperation_merge_notSupported() throws Exception {
        when(operationService.createDraftOperation(any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "OPERATION_TYPE_NOT_SUPPORTED", "操作类型暂不支持", "当前 Slice 范围限制"));

        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processJson()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPERATION_TYPE_NOT_SUPPORTED"));
    }

    @Test
    @DisplayName("物料平衡违规映射为 422 BATCH_MASS_BALANCE_VIOLATION")
    void submitOperation_massBalanceViolation_unprocessableEntity() throws Exception {
        when(operationService.submitOperation(eq(100L), any(), any(), any()))
                .thenThrow(new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_MASS_BALANCE_VIOLATION", "物料平衡校验失败", "不平衡"));

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
    @DisplayName("其他组织读取操作映射为 403 ORG_SCOPE_DENIED；不存在映射为 404")
    void getOperation_forbiddenAndNotFound() throws Exception {
        when(operationService.getOperation(eq(100L), any()))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", "无权访问"));
        when(operationService.getOperation(eq(404L), any()))
                .thenThrow(new ResourceNotFoundException("未找到"));

        mockMvc.perform(get("/api/v1/batch-operations/100").with(user(operatorPrincipal)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"));
        mockMvc.perform(get("/api/v1/batch-operations/404").with(user(operatorPrincipal)))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 4. 成功响应契约与白名单投影测试
    // =========================================================================

    @Test
    @DisplayName("创建草稿成功返回 201 Created 与白名单响应契约（含合计与明细批次摘要）")
    void createOperation_success_responseContract() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-09T10:00:00.123Z");
        when(operationService.createDraftOperation(any(), any(), any())).thenReturn(operationResponse("DRAFT", 0L, now, List.of()));

        mockMvc.perform(post("/api/v1/batch-operations")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(processRequest(now))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.operationType").value("PROCESS"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.balanced").value(true))
                .andExpect(jsonPath("$.data.inputTotal").value(1000.0))
                .andExpect(jsonPath("$.data.outputTotal").value(960.0))
                .andExpect(jsonPath("$.data.lossTotal").value(30.0))
                .andExpect(jsonPath("$.data.sampleTotal").value(10.0))
                .andExpect(jsonPath("$.data.occurredAt").value("2026-09-09T10:00:00.123Z"))
                .andExpect(jsonPath("$.data.items", hasSize(4)))
                .andExpect(jsonPath("$.data.items[1].role").value("OUTPUT"))
                .andExpect(jsonPath("$.data.items[1].traceBatchNo").value("TB-B1"))
                .andExpect(jsonPath("$.data.items[1].batchType").value("PROCESSING"))
                .andExpect(jsonPath("$.data.items[1].batchFlowStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.items[2].batchId").doesNotExist())
                .andExpect(jsonPath("$.data.relations", hasSize(0)))
                // 确保白名单投影：内部安全字段绝不泄露
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.data.submissionIdempotencyKey").doesNotExist());
    }

    @Test
    @DisplayName("提交操作成功返回 200 OK 并携带生成谱系边数组")
    void submitOperation_success_responseContract() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-09T10:30:00Z");
        when(operationService.submitOperation(eq(100L), any(), any(), any())).thenReturn(operationResponse("SUBMITTED", 1L, now,
                List.of(new BatchRelationResponse(1L, 100L, 101L, 103L, "TRANSFORM", now))));

        mockMvc.perform(post("/api/v1/batch-operations/100/submit")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.items[0].batchFlowStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.items[1].batchFlowStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.relations", hasSize(1)))
                .andExpect(jsonPath("$.data.relations[0].parentBatchId").value(101))
                .andExpect(jsonPath("$.data.relations[0].childBatchId").value(103))
                .andExpect(jsonPath("$.data.relations[0].relationType").value("TRANSFORM"));
    }

    @Test
    @DisplayName("读取操作详情与按批次列表返回 200")
    void getAndList_success() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-09T10:30:00Z");
        BatchOperationResponse submitted = operationResponse("SUBMITTED", 1L, now, List.of());
        when(operationService.getOperation(eq(100L), any())).thenReturn(submitted);
        when(operationService.listOperationsByBatch(eq(101L), eq(1), eq(20), any()))
                .thenReturn(SuccessEnvelope.ofPage(List.of(submitted), new PageMeta(1, 20, 1L)));

        mockMvc.perform(get("/api/v1/batch-operations/100").with(user(operatorPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(100));
        mockMvc.perform(get("/api/v1/batch-operations").param("batchId", "101").with(user(operatorPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(100));
    }

    @Test
    @DisplayName("删除草稿成功返回 204 No Content")
    void deleteOperation_success_noContent() throws Exception {
        mockMvc.perform(delete("/api/v1/batch-operations/100")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .param("expectedVersion", "0"))
                .andExpect(status().isNoContent());
        verify(operationService).deleteDraftOperation(eq(100L), eq(0L), any());
    }
}
