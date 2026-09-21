package com.example.traceability.batch.web;

import com.example.traceability.batch.application.BatchApplicationService;
import com.example.traceability.batch.dto.BatchCreateRequest;
import com.example.traceability.batch.dto.BatchPatchRequest;
import com.example.traceability.batch.dto.BatchQueryCriteria;
import com.example.traceability.batch.dto.BatchResponse;
import com.example.traceability.batch.dto.BatchSubmitRequest;
import com.example.traceability.common.envelope.PageMeta;
import com.example.traceability.common.envelope.SuccessEnvelope;
import com.example.traceability.common.exception.BusinessException;
import com.example.traceability.common.exception.GlobalExceptionHandler;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BatchController.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class, SecurityConfiguration.class})
@DisplayName("追溯批次 Web 接口与双状态/双编号契约测试")
class BatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BatchApplicationService batchService;

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
                10L, "ORG_FISHERY_01", "第一远洋捕捞公司", "SOURCE",
                List.of("OPERATOR"), List.of("ORG_ONLY"), true, true
        );

        adminPrincipal = new TraceSecurityPrincipal(
                1L, "admin", "平台管理员", "{noop}pwd",
                1L, "ORG_PLATFORM", "溯源管理中心", "PLATFORM",
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
        when(roleMapper.findActiveRolesByUserId(101L)).thenReturn(List.of(operatorRole));

        Role adminRole = new Role();
        adminRole.setRoleCode("ADMIN");
        adminRole.setScopeType("PLATFORM");
        adminRole.setStatus("ACTIVE");
        when(roleMapper.findActiveRolesByUserId(1L)).thenReturn(List.of(adminRole));
    }

    @Test
    @DisplayName("匿名访问批次所有端点一律返回 401 AUTH_REQUIRED")
    void anonymous_AccessDenied() throws Exception {
        mockMvc.perform(get("/api/v1/batches"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/batches/100"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(post("/api/v1/batches").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(patch("/api/v1/batches/100").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(post("/api/v1/batches/100/submit").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("非幂等写操作缺失 CSRF Token 一律返回 403 ACCESS_DENIED")
    void csrfProtection_MissingToken() throws Exception {
        BatchCreateRequest req = new BatchCreateRequest(
                "EXT-001", 500L, "SOURCE", new BigDecimal("100.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", null, null, null, null
        );

        // 1. POST /api/v1/batches 缺 CSRF Token
        mockMvc.perform(post("/api/v1/batches")
                        .with(user(operatorPrincipal))
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 2. PATCH /api/v1/batches/100 缺 CSRF Token
        mockMvc.perform(patch("/api/v1/batches/100")
                        .with(user(operatorPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 3. POST /api/v1/batches/100/submit 缺 CSRF Token
        mockMvc.perform(post("/api/v1/batches/100/submit")
                        .with(user(operatorPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("分页查询批次列表成功 - 返回包含双状态与双编号字段及分页元数据")
    void listBatches_Success() throws Exception {
        OffsetDateTime nowUtc = OffsetDateTime.of(2026, 9, 8, 8, 0, 0, 0, ZoneOffset.UTC);
        BatchResponse item = new BatchResponse(
                100L, 10L, 500L, "TB-ABCDEFGHIJKLMNOPQRSTUVWXYZ", "EXT-001", "SOURCE", new BigDecimal("100.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", LocalDate.of(2026, 9, 1), null, null, 180,
                "DRAFT", "NORMAL", 0L, nowUtc, 101L, nowUtc, 101L
        );

        PageMeta pageMeta = new PageMeta(1, 20, 1L);
        when(batchService.listBatches(any(BatchQueryCriteria.class), any(TraceSecurityPrincipal.class)))
                .thenReturn(SuccessEnvelope.ofPage(List.of(item), pageMeta));

        mockMvc.perform(get("/api/v1/batches")
                        .with(user(operatorPrincipal))
                        .param("flowStatus", "DRAFT")
                        .param("riskStatus", "NORMAL")
                        .param("traceBatchNo", "TB-ABC")
                        .param("externalBatchNo", "EXT-001")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].traceBatchNo").value("TB-ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
                .andExpect(jsonPath("$.data[0].externalBatchNo").value("EXT-001"))
                .andExpect(jsonPath("$.data[0].flowStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].riskStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data[0].batchNo").doesNotExist())
                .andExpect(jsonPath("$.data[0].status").doesNotExist())
                .andExpect(jsonPath("$.meta.page.number").value(1))
                .andExpect(jsonPath("$.meta.page.size").value(20))
                .andExpect(jsonPath("$.meta.page.totalElements").value(1));
    }

    @Test
    @DisplayName("查询批次详情成功 - 输出双状态双编号，绝不暴露 batchNo/status 或内部字段")
    void getBatch_Success() throws Exception {
        OffsetDateTime createdUtc = OffsetDateTime.of(2026, 9, 8, 9, 30, 0, 0, ZoneOffset.UTC);
        BatchResponse response = new BatchResponse(
                100L, 10L, 500L, "TB-ABCDEFGHIJKLMNOPQRSTUVWXYZ", "EXT-001", "SOURCE", new BigDecimal("100.000"), "kg",
                "DOMESTIC_CAPTURE", "东海舟山渔场", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 2), 180, "DRAFT", "NORMAL", 0L, createdUtc, 101L, createdUtc, 101L
        );

        when(batchService.getBatchById(eq(100L), any(TraceSecurityPrincipal.class))).thenReturn(response);

        mockMvc.perform(get("/api/v1/batches/100")
                        .with(user(operatorPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.orgId").value(10))
                .andExpect(jsonPath("$.data.productId").value(500))
                .andExpect(jsonPath("$.data.traceBatchNo").value("TB-ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
                .andExpect(jsonPath("$.data.externalBatchNo").value("EXT-001"))
                .andExpect(jsonPath("$.data.flowStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.riskStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.batchType").value("SOURCE"))
                .andExpect(jsonPath("$.data.quantity").value(100.000))
                .andExpect(jsonPath("$.data.unitCode").value("kg"))
                .andExpect(jsonPath("$.data.batchNo").doesNotExist())
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.isDeleted").doesNotExist())
                .andExpect(jsonPath("$.data.creationIdempotencyKey").doesNotExist());
    }

    @Test
    @DisplayName("查询批次详情失败 - 跨组织越权访问返回 403 ORG_SCOPE_DENIED")
    void getBatch_CrossOrgDenied() throws Exception {
        when(batchService.getBatchById(eq(200L), any(TraceSecurityPrincipal.class)))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "ORG_SCOPE_DENIED", "组织数据访问越权", "无权访问其他组织的批次数据"));

        mockMvc.perform(get("/api/v1/batches/200")
                        .with(user(operatorPrincipal)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORG_SCOPE_DENIED"))
                .andExpect(jsonPath("$.title").value("组织数据访问越权"));
    }

    @Test
    @DisplayName("创建批次草稿成功 - 返回 201 Created 且输出服务端 traceBatchNo 与 DRAFT+NORMAL")
    void createBatch_Success() throws Exception {
        BatchCreateRequest req = new BatchCreateRequest(
                "EXT-001", 500L, "SOURCE", new BigDecimal("100.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", LocalDate.of(2026, 9, 1), null, null, 180
        );

        OffsetDateTime nowUtc = OffsetDateTime.of(2026, 9, 8, 8, 0, 0, 0, ZoneOffset.UTC);
        BatchResponse response = new BatchResponse(
                100L, 10L, 500L, "TB-ABCDEFGHIJKLMNOPQRSTUVWXYZ", "EXT-001", "SOURCE", new BigDecimal("100.000"), "kg",
                "DOMESTIC_CAPTURE", "来源说明", LocalDate.of(2026, 9, 1), null, null, 180,
                "DRAFT", "NORMAL", 0L, nowUtc, 101L, nowUtc, 101L
        );

        when(batchService.createDraftBatch(any(BatchCreateRequest.class), eq(VALID_IDEMPOTENCY_KEY), any(TraceSecurityPrincipal.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/batches")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.traceBatchNo").value("TB-ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
                .andExpect(jsonPath("$.data.externalBatchNo").value("EXT-001"))
                .andExpect(jsonPath("$.data.flowStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.riskStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.batchNo").doesNotExist())
                .andExpect(jsonPath("$.data.status").doesNotExist());
    }

    @Test
    @DisplayName("创建批次草稿校验失败 - 缺少必填项或数量小数位超限返回 400 INVALID_REQUEST")
    void createBatch_ValidationFailure() throws Exception {
        // 缺少 productId 与 batchType
        String missingRequiredJson = """
                {
                    "quantity": 10.000,
                    "unitCode": "kg",
                    "originType": "DOMESTIC_CAPTURE",
                    "originText": "来源说明"
                }
                """;

        mockMvc.perform(post("/api/v1/batches")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingRequiredJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 数量小数位超限 (超过 3 位)
        String invalidScaleJson = """
                {
                    "productId": 500,
                    "batchType": "SOURCE",
                    "quantity": 10.1234,
                    "unitCode": "kg",
                    "originType": "DOMESTIC_CAPTURE",
                    "originText": "来源说明"
                }
                """;

        mockMvc.perform(post("/api/v1/batches")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidScaleJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // externalBatchNo 超长 (>64)
        String longExternalBatchJson = """
                {
                    "externalBatchNo": "EXT-LONG-BATCH-NO-123456789012345678901234567890123456789012345678901234567890",
                    "productId": 500,
                    "batchType": "SOURCE",
                    "quantity": 10.000,
                    "unitCode": "kg",
                    "originType": "DOMESTIC_CAPTURE",
                    "originText": "来源说明"
                }
                """;

        mockMvc.perform(post("/api/v1/batches")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(longExternalBatchJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("更新批次草稿成功 - 支持修改 externalBatchNo")
    void patchBatch_Success() throws Exception {
        BatchPatchRequest patchReq = new BatchPatchRequest(
                0L, new BigDecimal("120.000"), "EXT-UPDATED", "变更后原产地描述", null, null, null, null
        );

        OffsetDateTime nowUtc = OffsetDateTime.of(2026, 9, 8, 8, 0, 0, 0, ZoneOffset.UTC);
        BatchResponse response = new BatchResponse(
                100L, 10L, 500L, "TB-ABCDEFGHIJKLMNOPQRSTUVWXYZ", "EXT-UPDATED", "SOURCE", new BigDecimal("120.000"), "kg",
                "DOMESTIC_CAPTURE", "变更后原产地描述", LocalDate.of(2026, 9, 1), null, null, 180,
                "DRAFT", "NORMAL", 1L, nowUtc, 101L, nowUtc, 101L
        );

        when(batchService.patchDraftBatch(eq(100L), any(BatchPatchRequest.class), any(TraceSecurityPrincipal.class)))
                .thenReturn(response);

        mockMvc.perform(patch("/api/v1/batches/100")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(patchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.externalBatchNo").value("EXT-UPDATED"))
                .andExpect(jsonPath("$.data.quantity").value(120.000));
    }

    @Test
    @DisplayName("更新批次草稿失败 - 缺少 version 或 version 为负数返回 400 INVALID_REQUEST")
    void patchBatch_MissingVersion() throws Exception {
        String invalidJson = "{\"quantity\": 120.000}";

        mockMvc.perform(patch("/api/v1/batches/100")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        String negativeVersionJson = "{\"version\": -1}";
        mockMvc.perform(patch("/api/v1/batches/100")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(negativeVersionJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("提交激活批次草稿成功 - 返回 200 OK 且流转状态为 ACTIVE，风险为 NORMAL")
    void submitBatch_Success() throws Exception {
        BatchSubmitRequest submitReq = new BatchSubmitRequest(0L);

        OffsetDateTime nowUtc = OffsetDateTime.of(2026, 9, 8, 8, 0, 0, 0, ZoneOffset.UTC);
        BatchResponse response = new BatchResponse(
                100L, 10L, 500L, "TB-ABCDEFGHIJKLMNOPQRSTUVWXYZ", "EXT-001", "SOURCE", new BigDecimal("100.000"), "kg",
                "DOMESTIC_CAPTURE", "东海舟山", LocalDate.of(2026, 9, 1), null, null, 180,
                "ACTIVE", "NORMAL", 1L, nowUtc, 101L, nowUtc, 101L
        );

        when(batchService.submitDraftBatch(eq(100L), any(BatchSubmitRequest.class), any(TraceSecurityPrincipal.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/batches/100/submit")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.flowStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.riskStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("提交批次草稿失败 - 缺少 version 或 version 为负数返回 400 INVALID_REQUEST")
    void submitBatch_MissingVersion() throws Exception {
        mockMvc.perform(post("/api/v1/batches/100/submit")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/batches/100/submit")
                        .with(user(operatorPrincipal))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": -1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
